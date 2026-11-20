package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.query.ReadOnlySqlGuard;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogColumn;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.entity.SemanticEntities.LogicalTable;
import com.datagov.metadata.entity.SemanticEntities.SemanticMapping;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.entity.SemanticEntities.WorkspaceWarehouse;
import com.datagov.metadata.mapper.CatalogColumnMapper;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.DataSourceMapper;
import com.datagov.metadata.mapper.LogicalTableMapper;
import com.datagov.metadata.mapper.SemanticMappingMapper;
import com.datagov.metadata.mapper.WorkspaceWarehouseMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Workspace 多仓库目录与逻辑表服务（OpenSpec: multisource-semantic-federation）。
 *
 * <p>借鉴 MicroStrategy MultiSource Option & Warehouse Catalog：
 * <ul>
 *   <li>一个 Workspace 可挂载 1..N 个 {@link WorkspaceWarehouse}（PRIMARY/SECONDARY、队列标识、命名空间、组合模式、联邦引擎）；</li>
 *   <li>将多仓库物理表投影为 {@link LogicalTable}，支持 {@code logicalSize} 选表权重与 {@code aliasOfTableId} 解决 Attribute Role；</li>
 *   <li>提供跨仓异构映射矩阵（Mapping Matrix），直观呈现同一本体在多仓库的物理绑定。</li>
 * </ul>
 */
@Service
public class SemanticWarehouseService {

    public static final Set<String> WAREHOUSE_ROLES = Set.of("PRIMARY", "SECONDARY");
    public static final Set<String> COMBINE_MODES = Set.of("SINGLE_ROUTE", "COHORT_UNION", "ENTITY_FEDERATED_JOIN");
    public static final Set<String> FEDERATION_ENGINES = Set.of("AUTO", "STARROCKS", "DUCKDB");
    public static final Set<String> TABLE_ROLES = Set.of("LOOKUP", "FACT", "BRIDGE", "VIEW_SQL");

    private final WorkspaceWarehouseMapper warehouseMapper;
    private final LogicalTableMapper logicalTableMapper;
    private final DataSourceMapper dataSourceMapper;
    private final CatalogDatasetMapper datasetMapper;
    private final CatalogColumnMapper columnMapper;
    private final SemanticMappingMapper mappingMapper;
    private final SemanticOntologyService ontologyService;
    private final ObjectMapper json;

    public SemanticWarehouseService(WorkspaceWarehouseMapper warehouseMapper,
                                    LogicalTableMapper logicalTableMapper,
                                    DataSourceMapper dataSourceMapper,
                                    CatalogDatasetMapper datasetMapper,
                                    CatalogColumnMapper columnMapper,
                                    SemanticMappingMapper mappingMapper,
                                    SemanticOntologyService ontologyService,
                                    ObjectMapper json) {
        this.warehouseMapper = warehouseMapper;
        this.logicalTableMapper = logicalTableMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.datasetMapper = datasetMapper;
        this.columnMapper = columnMapper;
        this.mappingMapper = mappingMapper;
        this.ontologyService = ontologyService;
        this.json = json;
    }

    // ── DTOs ─────────────────────────────────────────────────────────────

    public record WarehouseRequest(
            String id,
            String datasourceId,
            String code,
            String name,
            String warehouseRole,
            String cohortCode,
            String dialect,
            String idNamespacePrefix,
            String combineMode,
            String federationEngine,
            String starrocksCatalogName,
            Integer priority,
            String status
    ) {
    }

    public record WarehouseView(
            String id,
            String datasourceId,
            String datasourceName,
            String datasourceType,
            String code,
            String name,
            String warehouseRole,
            String cohortCode,
            String dialect,
            String idNamespacePrefix,
            String combineMode,
            String federationEngine,
            String starrocksCatalogName,
            int priority,
            String status,
            long logicalTableCount,
            Instant updatedAt
    ) {
    }

    public record LogicalTableRequest(
            String id,
            String warehouseId,
            String catalogDatasetId,
            String logicalName,
            String tableRole,
            String aliasOfTableId,
            Integer logicalSize,
            String customViewSql,
            String status
    ) {
    }

    public record LogicalTableView(
            String id,
            String warehouseId,
            String warehouseCode,
            String warehouseName,
            String cohortCode,
            String catalogDatasetId,
            String datasetName,
            String datasetQualifiedName,
            String logicalName,
            String tableRole,
            String aliasOfTableId,
            String aliasOfName,
            int logicalSize,
            String customViewSql,
            String status,
            Instant updatedAt
    ) {
    }

    public record AliasRequest(String logicalName, String tableRole, Integer logicalSize) {
    }

    public record WarehouseMappingCell(
            String mappingId,
            String mappingRole,
            String logicalTableId,
            String logicalTableName,
            String catalogDatasetId,
            String datasetQualifiedName,
            String catalogColumnId,
            String columnName,
            String expressionSql,
            Map<String, String> valueNormalization,
            String sourceUnit,
            String targetUnit,
            int priority
    ) {
    }

    public record WarehouseMappingRow(
            String warehouseId,
            String warehouseCode,
            String warehouseName,
            String cohortCode,
            String dialect,
            String combineMode,
            String federationEngine,
            List<WarehouseMappingCell> mappings
    ) {
    }

    public record HeterogeneousMappingMatrix(
            String objectId,
            String objectCode,
            String objectName,
            String objectType,
            String scope,
            List<WarehouseMappingRow> warehouses,
            List<WarehouseMappingCell> unboundMappings
    ) {
    }

    // ── Warehouse CRUD ───────────────────────────────────────────────────

    public List<WarehouseView> listWarehouses() {
        String ws = WorkspaceContext.requireWorkspaceId();
        List<WorkspaceWarehouse> rows = warehouseMapper.selectList(
                new LambdaQueryWrapper<WorkspaceWarehouse>()
                        .eq(WorkspaceWarehouse::getWorkspaceId, ws)
                        .orderByAsc(WorkspaceWarehouse::getPriority)
                        .orderByAsc(WorkspaceWarehouse::getCode));
        return toWarehouseViews(ws, rows);
    }

    @Transactional
    public WarehouseView upsertWarehouse(WarehouseRequest req) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String userId = WorkspaceContext.require().userId();
        if (req == null || req.datasourceId() == null || req.datasourceId().isBlank()
                || req.code() == null || req.code().isBlank()
                || req.name() == null || req.name().isBlank()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, "datasourceId、code、name 为必填项");
        }
        String role = upperOrDefault(req.warehouseRole(), "SECONDARY");
        if (!WAREHOUSE_ROLES.contains(role)) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, "不支持的仓库角色: " + role);
        }
        String combineMode = upperOrDefault(req.combineMode(), "SINGLE_ROUTE");
        if (!COMBINE_MODES.contains(combineMode)) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, "不支持的多仓组合模式: " + combineMode);
        }
        String fedEngine = upperOrDefault(req.federationEngine(), "AUTO");
        if (!FEDERATION_ENGINES.contains(fedEngine)) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, "不支持的联邦引擎: " + fedEngine);
        }
        String code = req.code().trim().toLowerCase(Locale.ROOT);
        Instant now = Instant.now();

        WorkspaceWarehouse entity;
        boolean creating = req.id() == null || req.id().isBlank();
        if (creating) {
            Long dup = warehouseMapper.selectCount(new LambdaQueryWrapper<WorkspaceWarehouse>()
                    .eq(WorkspaceWarehouse::getWorkspaceId, ws)
                    .apply("lower(code) = {0}", code));
            if (dup != null && dup > 0) {
                throw new BizException(ErrorCode.MTD_SEMANTIC_CODE_DUPLICATED, "仓库标识已存在: " + code);
            }
            entity = new WorkspaceWarehouse();
            entity.setId(Ids.of("wh"));
            entity.setWorkspaceId(ws);
            entity.setCreatedAt(now);
            entity.setCreatedBy(userId);
        } else {
            entity = warehouseMapper.selectOne(new LambdaQueryWrapper<WorkspaceWarehouse>()
                    .eq(WorkspaceWarehouse::getWorkspaceId, ws)
                    .eq(WorkspaceWarehouse::getId, req.id().trim()));
            if (entity == null) {
                throw new BizException(ErrorCode.SYS_NOT_FOUND, "仓库挂载不存在: " + req.id());
            }
        }

        entity.setDatasourceId(req.datasourceId().trim());
        entity.setCode(code);
        entity.setName(req.name().trim());
        entity.setWarehouseRole(role);
        entity.setCohortCode(blankToNull(req.cohortCode()));
        entity.setDialect(upperOrDefault(req.dialect(), "POSTGRESQL"));
        entity.setIdNamespacePrefix(blankToNull(req.idNamespacePrefix()));
        entity.setCombineMode(combineMode);
        entity.setFederationEngine(fedEngine);
        entity.setStarrocksCatalogName(blankToNull(req.starrocksCatalogName()));
        entity.setPriority(req.priority() == null ? 100 : req.priority());
        entity.setStatus(upperOrDefault(req.status(), "ACTIVE"));
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(userId);

        if (creating) {
            warehouseMapper.insert(entity);
        } else {
            warehouseMapper.updateById(entity);
        }
        return toWarehouseViews(ws, List.of(entity)).get(0);
    }

    @Transactional
    public void deleteWarehouse(String id) {
        String ws = WorkspaceContext.requireWorkspaceId();
        WorkspaceWarehouse existing = warehouseMapper.selectOne(new LambdaQueryWrapper<WorkspaceWarehouse>()
                .eq(WorkspaceWarehouse::getWorkspaceId, ws)
                .eq(WorkspaceWarehouse::getId, id));
        if (existing == null) {
            throw new BizException(ErrorCode.SYS_NOT_FOUND, "仓库挂载不存在");
        }
        warehouseMapper.deleteById(id);
    }

    // ── Logical Table & Alias CRUD ───────────────────────────────────────

    public List<LogicalTableView> listLogicalTables(String warehouseId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        LambdaQueryWrapper<LogicalTable> q = new LambdaQueryWrapper<LogicalTable>()
                .eq(LogicalTable::getWorkspaceId, ws);
        if (warehouseId != null && !warehouseId.isBlank()) {
            q.eq(LogicalTable::getWarehouseId, warehouseId.trim());
        }
        q.orderByAsc(LogicalTable::getLogicalSize).orderByAsc(LogicalTable::getLogicalName);
        return toLogicalTableViews(ws, logicalTableMapper.selectList(q));
    }

    @Transactional
    public LogicalTableView upsertLogicalTable(LogicalTableRequest req) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String userId = WorkspaceContext.require().userId();
        if (req == null || req.warehouseId() == null || req.warehouseId().isBlank()
                || req.catalogDatasetId() == null || req.catalogDatasetId().isBlank()
                || req.logicalName() == null || req.logicalName().isBlank()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, "warehouseId、catalogDatasetId、logicalName 为必填项");
        }
        WorkspaceWarehouse wh = warehouseMapper.selectOne(new LambdaQueryWrapper<WorkspaceWarehouse>()
                .eq(WorkspaceWarehouse::getWorkspaceId, ws)
                .eq(WorkspaceWarehouse::getId, req.warehouseId().trim()));
        if (wh == null) {
            throw new BizException(ErrorCode.SYS_NOT_FOUND, "所属仓库挂载不存在: " + req.warehouseId());
        }
        String tableRole = upperOrDefault(req.tableRole(), "FACT");
        if (!TABLE_ROLES.contains(tableRole)) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, "不支持的逻辑表角色: " + tableRole);
        }
        if (req.customViewSql() != null && !req.customViewSql().isBlank()) {
            ReadOnlySqlGuard.requireReadOnly(req.customViewSql().trim());
        }

        Instant now = Instant.now();
        boolean creating = req.id() == null || req.id().isBlank();
        LogicalTable lt;
        if (creating) {
            lt = new LogicalTable();
            lt.setId(Ids.of("lt"));
            lt.setWorkspaceId(ws);
            lt.setCreatedAt(now);
            lt.setCreatedBy(userId);
        } else {
            lt = logicalTableMapper.selectOne(new LambdaQueryWrapper<LogicalTable>()
                    .eq(LogicalTable::getWorkspaceId, ws)
                    .eq(LogicalTable::getId, req.id().trim()));
            if (lt == null) {
                throw new BizException(ErrorCode.SYS_NOT_FOUND, "逻辑表不存在: " + req.id());
            }
        }
        lt.setWarehouseId(wh.getId());
        lt.setCatalogDatasetId(req.catalogDatasetId().trim());
        lt.setLogicalName(req.logicalName().trim());
        lt.setTableRole(tableRole);
        lt.setAliasOfTableId(blankToNull(req.aliasOfTableId()));
        lt.setLogicalSize(req.logicalSize() == null ? 1000 : Math.max(1, req.logicalSize()));
        lt.setCustomViewSql(blankToNull(req.customViewSql()));
        lt.setStatus(upperOrDefault(req.status(), "ACTIVE"));
        lt.setUpdatedAt(now);
        lt.setUpdatedBy(userId);

        if (creating) {
            logicalTableMapper.insert(lt);
        } else {
            logicalTableMapper.updateById(lt);
        }
        return toLogicalTableViews(ws, List.of(lt)).get(0);
    }

    @Transactional
    public LogicalTableView createLogicalTableAlias(String baseTableId, AliasRequest req) {
        String ws = WorkspaceContext.requireWorkspaceId();
        if (req == null || req.logicalName() == null || req.logicalName().isBlank()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, "别名名称不能为空");
        }
        LogicalTable base = logicalTableMapper.selectOne(new LambdaQueryWrapper<LogicalTable>()
                .eq(LogicalTable::getWorkspaceId, ws)
                .eq(LogicalTable::getId, baseTableId));
        if (base == null) {
            throw new BizException(ErrorCode.SYS_NOT_FOUND, "基础逻辑表不存在: " + baseTableId);
        }
        return upsertLogicalTable(new LogicalTableRequest(
                null,
                base.getWarehouseId(),
                base.getCatalogDatasetId(),
                req.logicalName().trim(),
                req.tableRole() == null || req.tableRole().isBlank() ? base.getTableRole() : req.tableRole(),
                base.getId(),
                req.logicalSize() == null ? base.getLogicalSize() : req.logicalSize(),
                base.getCustomViewSql(),
                "ACTIVE"
        ));
    }

    @Transactional
    public void deleteLogicalTable(String id) {
        String ws = WorkspaceContext.requireWorkspaceId();
        LogicalTable existing = logicalTableMapper.selectOne(new LambdaQueryWrapper<LogicalTable>()
                .eq(LogicalTable::getWorkspaceId, ws)
                .eq(LogicalTable::getId, id));
        if (existing == null) {
            throw new BizException(ErrorCode.SYS_NOT_FOUND, "逻辑表不存在");
        }
        logicalTableMapper.deleteById(id);
    }

    // ── Heterogeneous Mapping Matrix ─────────────────────────────────────

    public HeterogeneousMappingMatrix mappingMatrix(String objectId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        SemanticOntologyService.ObjectView obj = ontologyService.getObject(objectId);

        List<WorkspaceWarehouse> warehouses = warehouseMapper.selectList(
                new LambdaQueryWrapper<WorkspaceWarehouse>()
                        .eq(WorkspaceWarehouse::getWorkspaceId, ws)
                        .orderByAsc(WorkspaceWarehouse::getPriority));
        Map<String, LogicalTable> logicalTables = logicalTableMapper.selectList(
                        new LambdaQueryWrapper<LogicalTable>().eq(LogicalTable::getWorkspaceId, ws))
                .stream().collect(Collectors.toMap(LogicalTable::getId, Function.identity(), (a, b) -> a));

        List<SemanticMapping> mappings = mappingMapper.selectList(
                new LambdaQueryWrapper<SemanticMapping>()
                        .eq(SemanticMapping::getWorkspaceId, ws)
                        .eq(SemanticMapping::getObjectId, objectId)
                        .orderByAsc(SemanticMapping::getPriority));

        Set<String> dsIds = mappings.stream().map(SemanticMapping::getCatalogDatasetId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> colIds = mappings.stream().map(SemanticMapping::getCatalogColumnId)
                .filter(Objects::nonNull).collect(Collectors.toSet());

        Map<String, CatalogDataset> datasets = dsIds.isEmpty() ? Map.of()
                : datasetMapper.selectList(new LambdaQueryWrapper<CatalogDataset>()
                        .eq(CatalogDataset::getWorkspaceId, ws)
                        .in(CatalogDataset::getId, dsIds))
                .stream().collect(Collectors.toMap(CatalogDataset::getId, Function.identity(), (a, b) -> a));
        Map<String, CatalogColumn> columns = colIds.isEmpty() ? Map.of()
                : columnMapper.selectList(new LambdaQueryWrapper<CatalogColumn>()
                        .in(CatalogColumn::getId, colIds))
                .stream().collect(Collectors.toMap(CatalogColumn::getId, Function.identity(), (a, b) -> a));

        Map<String, List<WarehouseMappingCell>> byWh = new LinkedHashMap<>();
        for (WorkspaceWarehouse w : warehouses) {
            byWh.put(w.getId(), new ArrayList<>());
        }
        List<WarehouseMappingCell> unbound = new ArrayList<>();

        for (SemanticMapping m : mappings) {
            LogicalTable lt = m.getLogicalTableId() == null ? null : logicalTables.get(m.getLogicalTableId());
            String whId = m.getWarehouseId() != null ? m.getWarehouseId() : (lt == null ? null : lt.getWarehouseId());
            CatalogDataset ds = datasets.get(m.getCatalogDatasetId());
            CatalogColumn col = m.getCatalogColumnId() == null ? null : columns.get(m.getCatalogColumnId());
            WarehouseMappingCell cell = new WarehouseMappingCell(
                    m.getId(),
                    m.getMappingRole(),
                    m.getLogicalTableId(),
                    lt == null ? null : lt.getLogicalName(),
                    m.getCatalogDatasetId(),
                    ds == null ? m.getCatalogDatasetId() : ds.getQualifiedName(),
                    m.getCatalogColumnId(),
                    col == null ? null : col.getName(),
                    m.getExpressionSql(),
                    readStringMap(m.getValueNormalizationJson()),
                    m.getSourceUnit(),
                    m.getTargetUnit(),
                    m.getPriority() == null ? 100 : m.getPriority()
            );
            if (whId != null && byWh.containsKey(whId)) {
                byWh.get(whId).add(cell);
            } else {
                unbound.add(cell);
            }
        }

        List<WarehouseMappingRow> rows = warehouses.stream()
                .map(w -> new WarehouseMappingRow(
                        w.getId(), w.getCode(), w.getName(), w.getCohortCode(),
                        w.getDialect(), w.getCombineMode(), w.getFederationEngine(),
                        byWh.getOrDefault(w.getId(), List.of())))
                .toList();

        return new HeterogeneousMappingMatrix(
                obj.id(), obj.code(), obj.name(), obj.objectType(), obj.scope(), rows, unbound
        );
    }

    // ── Internal Helpers for Compiler / Engine ───────────────────────────

    public List<WorkspaceWarehouse> listActiveWarehousesInternal(String workspaceId) {
        return warehouseMapper.selectList(new LambdaQueryWrapper<WorkspaceWarehouse>()
                .eq(WorkspaceWarehouse::getWorkspaceId, workspaceId)
                .eq(WorkspaceWarehouse::getStatus, "ACTIVE")
                .orderByAsc(WorkspaceWarehouse::getPriority)
                .orderByAsc(WorkspaceWarehouse::getCode));
    }

    public List<LogicalTable> listActiveLogicalTablesInternal(String workspaceId) {
        return logicalTableMapper.selectList(new LambdaQueryWrapper<LogicalTable>()
                .eq(LogicalTable::getWorkspaceId, workspaceId)
                .eq(LogicalTable::getStatus, "ACTIVE")
                .orderByAsc(LogicalTable::getLogicalSize));
    }

    // ── View Mappers ─────────────────────────────────────────────────────

    private List<WarehouseView> toWarehouseViews(String ws, List<WorkspaceWarehouse> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<String> dsIds = list.stream().map(WorkspaceWarehouse::getDatasourceId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, DataSourceEntity> dsMap = dsIds.isEmpty() ? Map.of()
                : dataSourceMapper.selectList(new LambdaQueryWrapper<DataSourceEntity>()
                        .eq(DataSourceEntity::getWorkspaceId, ws)
                        .in(DataSourceEntity::getId, dsIds))
                .stream().collect(Collectors.toMap(DataSourceEntity::getId, Function.identity(), (a, b) -> a));

        Set<String> whIds = list.stream().map(WorkspaceWarehouse::getId).collect(Collectors.toSet());
        Map<String, Long> ltCounts = logicalTableMapper.selectList(
                        new LambdaQueryWrapper<LogicalTable>()
                                .eq(LogicalTable::getWorkspaceId, ws)
                                .in(LogicalTable::getWarehouseId, whIds))
                .stream().collect(Collectors.groupingBy(LogicalTable::getWarehouseId, Collectors.counting()));

        List<WarehouseView> out = new ArrayList<>(list.size());
        for (WorkspaceWarehouse w : list) {
            DataSourceEntity ds = dsMap.get(w.getDatasourceId());
            out.add(new WarehouseView(
                    w.getId(), w.getDatasourceId(),
                    ds == null ? w.getDatasourceId() : ds.getName(),
                    ds == null || ds.getType() == null ? null : ds.getType().name(),
                    w.getCode(), w.getName(), w.getWarehouseRole(), w.getCohortCode(),
                    w.getDialect(), w.getIdNamespacePrefix(), w.getCombineMode(),
                    w.getFederationEngine(), w.getStarrocksCatalogName(),
                    w.getPriority() == null ? 100 : w.getPriority(),
                    w.getStatus(), ltCounts.getOrDefault(w.getId(), 0L), w.getUpdatedAt()));
        }
        return out;
    }

    private List<LogicalTableView> toLogicalTableViews(String ws, List<LogicalTable> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<String, WorkspaceWarehouse> whMap = warehouseMapper.selectList(
                        new LambdaQueryWrapper<WorkspaceWarehouse>().eq(WorkspaceWarehouse::getWorkspaceId, ws))
                .stream().collect(Collectors.toMap(WorkspaceWarehouse::getId, Function.identity(), (a, b) -> a));
        Set<String> dsIds = list.stream().map(LogicalTable::getCatalogDatasetId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, CatalogDataset> dsMap = dsIds.isEmpty() ? Map.of()
                : datasetMapper.selectList(new LambdaQueryWrapper<CatalogDataset>()
                        .eq(CatalogDataset::getWorkspaceId, ws)
                        .in(CatalogDataset::getId, dsIds))
                .stream().collect(Collectors.toMap(CatalogDataset::getId, Function.identity(), (a, b) -> a));
        Map<String, LogicalTable> allLt = logicalTableMapper.selectList(
                        new LambdaQueryWrapper<LogicalTable>().eq(LogicalTable::getWorkspaceId, ws))
                .stream().collect(Collectors.toMap(LogicalTable::getId, Function.identity(), (a, b) -> a));

        List<LogicalTableView> out = new ArrayList<>(list.size());
        for (LogicalTable lt : list) {
            WorkspaceWarehouse wh = whMap.get(lt.getWarehouseId());
            CatalogDataset ds = dsMap.get(lt.getCatalogDatasetId());
            LogicalTable base = lt.getAliasOfTableId() == null ? null : allLt.get(lt.getAliasOfTableId());
            out.add(new LogicalTableView(
                    lt.getId(), lt.getWarehouseId(),
                    wh == null ? null : wh.getCode(),
                    wh == null ? null : wh.getName(),
                    wh == null ? null : wh.getCohortCode(),
                    lt.getCatalogDatasetId(),
                    ds == null ? lt.getCatalogDatasetId() : ds.getName(),
                    ds == null ? lt.getCatalogDatasetId() : ds.getQualifiedName(),
                    lt.getLogicalName(), lt.getTableRole(),
                    lt.getAliasOfTableId(), base == null ? null : base.getLogicalName(),
                    lt.getLogicalSize() == null ? 1000 : lt.getLogicalSize(),
                    lt.getCustomViewSql(), lt.getStatus(), lt.getUpdatedAt()));
        }
        return out;
    }

    private String upperOrDefault(String raw, String fallback) {
        return raw == null || raw.isBlank() ? fallback : raw.trim().toUpperCase(Locale.ROOT);
    }

    private String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private Map<String, String> readStringMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
