package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.audit.ReproducibilityFingerprint;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.data.spi.query.ReadOnlySqlGuard;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogColumn;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.SemanticEntities.LogicalTable;
import com.datagov.metadata.entity.SemanticEntities.SemanticMapping;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.entity.SemanticEntities.WorkspaceWarehouse;
import com.datagov.metadata.mapper.CatalogColumnMapper;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.service.SemanticQueryCompiler.CompileContext;
import com.datagov.metadata.service.SemanticQueryCompiler.CompiledColumn;
import com.datagov.metadata.service.SemanticQueryCompiler.CompiledSemanticQuery;
import com.datagov.metadata.service.SemanticQueryCompiler.FilterClause;
import com.datagov.metadata.service.SemanticQueryCompiler.OrderClause;
import com.datagov.metadata.service.SemanticQueryCompiler.SemanticQuerySpec;
import com.datagov.metadata.service.gate.SemanticExecutionMode;
import com.datagov.metadata.service.gate.SemanticGateRequest;
import com.datagov.metadata.service.gate.SemanticGateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 多仓库确定性语义编译器（OpenSpec: multisource-semantic-federation · Metadata Space）。
 *
 * <p>纯 Java 确定性编译，零 LLM、零驱动依赖（CONSTITUTION C1/C2/C3/C5/C8），统一支持 MicroStrategy 三种多源语义：
 * <ol>
 *   <li><b>SINGLE_ROUTE</b>：单仓或经队列/分区裁剪后命中单仓，直接下推目标方言 SQL（按 {@code logicalSize} 选表 + 值归一化 + 单位换算）；</li>
 *   <li><b>COHORT_UNION</b>：互斥队列水平联合（如 MIMIC-IV + eICU-CRD），为每个仓生成独立方言 Pass、自动包覆 {@code idNamespacePrefix}
 *       防止 {@code COUNT(DISTINCT)} 跨库主键碰撞，并将 {@code AVG} 拆解为 {@code __sum}/{@code __cnt} 两阶段充分统计量；</li>
 *   <li><b>ENTITY_FEDERATED_JOIN</b>：共享实体主键任意跨仓 Join（如 HIS + LIS 共享 EMPI），支持：
 *       <ul>
 *         <li><b>STARROCKS</b>：所有参与仓配置 {@code starrocksCatalogName} 时生成跨 External Catalog 三段式 SQL；</li>
 *         <li><b>DUCKDB</b>：生成各仓列裁剪与谓词下推提取 SQL（供 {@code DataAccessGateway} 逐仓执行并先做 C5 脱敏），再生成 DuckDB 内存联邦 JOIN SQL。</li>
 *       </ul>
 *   </li>
 * </ol>
 */
@Service
public class MultiWarehouseQueryCompiler {

    public static final String COHORT_DIM_CODE = "attr.system.cohort";
    private static final Set<String> ALLOWED_OPS = Set.of("=", "!=", ">", ">=", "<", "<=", "IN", "NOT_IN", "LIKE");
    private static final Pattern SAFE_IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$.]*$");

    private final SemanticOntologyService ontologyService;
    private final SemanticWarehouseService warehouseService;
    private final SemanticQueryCompiler singleCompiler;
    private final CatalogDatasetMapper datasetMapper;
    private final CatalogColumnMapper columnMapper;
    private final ObjectMapper json;
    private final SemanticGateService semanticGate;

    public MultiWarehouseQueryCompiler(SemanticOntologyService ontologyService,
                                       SemanticWarehouseService warehouseService,
                                       SemanticQueryCompiler singleCompiler,
                                       CatalogDatasetMapper datasetMapper,
                                       CatalogColumnMapper columnMapper,
                                       ObjectMapper json) {
        this(ontologyService, warehouseService, singleCompiler, datasetMapper, columnMapper, json,
                new SemanticGateService(json));
    }

    /** Spring 注入入口：多构造器时必须显式 @Autowired，否则会回退无参构造并启动失败。 */
    @Autowired
    public MultiWarehouseQueryCompiler(SemanticOntologyService ontologyService,
                                       SemanticWarehouseService warehouseService,
                                       SemanticQueryCompiler singleCompiler,
                                       CatalogDatasetMapper datasetMapper,
                                       CatalogColumnMapper columnMapper,
                                       ObjectMapper json,
                                       SemanticGateService semanticGate) {
        this.ontologyService = ontologyService;
        this.warehouseService = warehouseService;
        this.singleCompiler = singleCompiler;
        this.datasetMapper = datasetMapper;
        this.columnMapper = columnMapper;
        this.json = json;
        this.semanticGate = semanticGate == null ? new SemanticGateService(json) : semanticGate;
    }

    // ── Execution Plan Records ───────────────────────────────────────────

    public enum ExecutionMode {
        SINGLE_ROUTE,
        COHORT_UNION,
        ENTITY_FEDERATED_JOIN
    }

    public record WarehouseSubQuery(
            String warehouseId,
            String warehouseCode,
            String warehouseName,
            String datasourceId,
            String cohortCode,
            String dialect,
            String idNamespacePrefix,
            String primaryDatasetId,
            List<String> involvedDatasetIds,
            String sql,
            List<CompiledColumn> columns
    ) {
    }

    /**
     * COHORT_UNION 两阶段确定性聚合合并规格（避免对分仓 AVG 直接求平均的统计错误）。
     */
    public record AggregateMergeColumn(
            String outputKey,
            String label,
            String role,
            String aggKind,     // DIM / SUM / COUNT / COUNT_DISTINCT / AVG / MIN / MAX
            String sourceCol,
            String sumCol,
            String countCol
    ) {
    }

    public record FederatedSourceExtract(
            String alias,
            String warehouseId,
            String warehouseCode,
            String warehouseName,
            String datasourceId,
            String dialect,
            String datasetId,
            String joinKeyAlias,
            String extractSql
    ) {
    }

    public record FederatedJoinPlan(
            String engine, // STARROCKS / DUCKDB
            String starRocksDatasourceId,
            List<FederatedSourceExtract> extracts,
            String federatedSql
    ) {
    }

    public record MultiWarehousePlan(
            ExecutionMode mode,
            String federationEngine,
            List<WarehouseSubQuery> subQueries,
            List<AggregateMergeColumn> mergeColumns,
            FederatedJoinPlan federatedJoinPlan,
            List<CompiledColumn> columns,
            List<String> semanticSnapshotRefs,
            String auditFingerprint,
            List<String> appliedExclusions,
            List<String> warnings,
            ReproducibilityFingerprint reproducibilityFingerprint
    ) {
        public MultiWarehousePlan(ExecutionMode mode,
                                  String federationEngine,
                                  List<WarehouseSubQuery> subQueries,
                                  List<AggregateMergeColumn> mergeColumns,
                                  FederatedJoinPlan federatedJoinPlan,
                                  List<CompiledColumn> columns,
                                  List<String> semanticSnapshotRefs,
                                  String auditFingerprint,
                                  List<String> appliedExclusions,
                                  List<String> warnings) {
            this(mode, federationEngine, subQueries, mergeColumns, federatedJoinPlan, columns,
                    semanticSnapshotRefs, auditFingerprint, appliedExclusions, warnings, null);
        }
    }

    // ── Main Entry ───────────────────────────────────────────────────────

    public MultiWarehousePlan compileMultiWarehouse(SemanticQuerySpec spec, CompileContext ctx) {
        if (spec == null || ctx == null || ctx.workspaceId() == null || ctx.workspaceId().isBlank()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED, "编译请求与 workspaceId 不能为空");
        }
        String ws = ctx.workspaceId();
        List<WorkspaceWarehouse> activeWarehouses = warehouseService.listActiveWarehousesInternal(ws);
        if (activeWarehouses.isEmpty()) {
            return wrapLegacySingleCompile(singleCompiler.compile(spec, ctx), ctx);
        }

        List<LogicalTable> logicalTables = warehouseService.listActiveLogicalTablesInternal(ws);
        Map<String, LogicalTable> ltById = logicalTables.stream()
                .collect(Collectors.toMap(LogicalTable::getId, Function.identity(), (a, b) -> a));
        Map<String, WorkspaceWarehouse> whById = activeWarehouses.stream()
                .collect(Collectors.toMap(WorkspaceWarehouse::getId, Function.identity(), (a, b) -> a));
        Map<String, List<LogicalTable>> ltByDataset = logicalTables.stream()
                .collect(Collectors.groupingBy(LogicalTable::getCatalogDatasetId));

        List<SemanticObject> published = ontologyService.listPublishedObjectsInternal(ws);
        Map<String, SemanticObject> byCode = published.stream()
                .collect(Collectors.toMap(o -> o.getCode().toLowerCase(Locale.ROOT), Function.identity(), (a, b) -> a));
        List<SemanticMapping> allMappings = ontologyService.listWorkspaceMappingsInternal(ws);
        Map<String, List<SemanticMapping>> mappingsByObj = allMappings.stream()
                .collect(Collectors.groupingBy(SemanticMapping::getObjectId));

        // 1. 解析已发布指标与维度（支持内置队列维度 attr.system.cohort）；C2/允许维/时间窗走统一 SemanticGate
        List<SemanticObject> metricObjs = new ArrayList<>();
        for (String code : safeList(spec.metrics())) {
            metricObjs.add(requirePublished(byCode, code, "METRIC"));
        }
        boolean includeCohortDim = false;
        List<SemanticObject> dimObjs = new ArrayList<>();
        for (String code : safeList(spec.dimensions())) {
            if (isCohortCode(code)) {
                includeCohortDim = true;
                continue;
            }
            dimObjs.add(requirePublished(byCode, code, null));
        }

        int maxRows = Math.max(1, Math.min(2000, ctx.maxRows() <= 0 ? 200 : ctx.maxRows()));
        Set<String> allowedDatasets = ctx.allowedDatasetIds() == null ? Set.of() : ctx.allowedDatasetIds();
        // 模式在后续分支确定；语义门禁与路径无关，先用 SINGLE 做共用检查（三路径 error_code 一致）
        semanticGate.evaluate(new SemanticGateRequest(
                ws, SemanticExecutionMode.SINGLE, spec, metricObjs, dimObjs, null,
                allowedDatasets, maxRows, Set.of()));

        // 2. 队列/仓库分区裁剪（Filter on cohort / attr.system.cohort）
        Set<String> cohortAllowlist = extractCohortFilter(spec.filters());
        List<WorkspaceWarehouse> candidateWarehouses = activeWarehouses.stream()
                .filter(w -> cohortAllowlist.isEmpty()
                        || matchesCohort(w, cohortAllowlist))
                .toList();
        if (candidateWarehouses.isEmpty()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED,
                    "队列筛选条件未命中任何已启用的仓库: " + cohortAllowlist);
        }

        // 加载涉及的物理数据集与列元信息
        Set<String> allDatasetIds = allMappings.stream().map(SemanticMapping::getCatalogDatasetId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, CatalogDataset> datasetsById = allDatasetIds.isEmpty() ? Map.of()
                : datasetMapper.selectList(new LambdaQueryWrapper<CatalogDataset>()
                        .eq(CatalogDataset::getWorkspaceId, ws)
                        .in(CatalogDataset::getId, allDatasetIds))
                .stream().collect(Collectors.toMap(CatalogDataset::getId, Function.identity(), (a, b) -> a));
        Set<String> allColIds = allMappings.stream().map(SemanticMapping::getCatalogColumnId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, CatalogColumn> colsById = allColIds.isEmpty() ? Map.of()
                : columnMapper.selectList(new LambdaQueryWrapper<CatalogColumn>()
                        .in(CatalogColumn::getId, allColIds))
                .stream().collect(Collectors.toMap(CatalogColumn::getId, Function.identity(), (a, b) -> a));

        // 为每个候选仓库计算其可覆盖的对象集合（Pre-retrieval ACL 过滤 allowedDatasetIds）
        Map<String, Map<String, ResolvedBinding>> bindingsByWarehouse = new LinkedHashMap<>();
        List<SemanticObject> requiredObjs = new ArrayList<>();
        requiredObjs.addAll(metricObjs);
        requiredObjs.addAll(dimObjs);
        for (FilterClause fc : safeList(spec.filters())) {
            if (!isCohortCode(fc.objectCode())) {
                requiredObjs.add(requirePublished(byCode, fc.objectCode(), null));
            }
        }

        for (WorkspaceWarehouse wh : candidateWarehouses) {
            Map<String, ResolvedBinding> resolved = new LinkedHashMap<>();
            for (SemanticObject obj : requiredObjs) {
                ResolvedBinding best = selectBestBindingForWarehouse(
                        obj, wh, mappingsByObj.getOrDefault(obj.getId(), List.of()),
                        ltById, ltByDataset, datasetsById, colsById, ctx.allowedDatasetIds());
                if (best != null) {
                    resolved.put(obj.getId(), best);
                }
            }
            if (!resolved.isEmpty()) {
                bindingsByWarehouse.put(wh.getId(), resolved);
            }
        }

        // 找出完全覆盖本次所有指标和维度的仓库列表
        List<WorkspaceWarehouse> fullCoverageWarehouses = candidateWarehouses.stream()
                .filter(wh -> {
                    Map<String, ResolvedBinding> map = bindingsByWarehouse.get(wh.getId());
                    if (map == null) return false;
                    return requiredObjs.stream().allMatch(o -> map.containsKey(o.getId()));
                })
                .toList();

        // 判断执行模式：
        // 情况 1：有 ≥2 个完全覆盖仓库，且任一仓库配置 COHORT_UNION -> Mode B（互斥队列水平联合，如 MIMIC + eICU）
        boolean hasCohortUnion = fullCoverageWarehouses.size() >= 2
                && fullCoverageWarehouses.stream().anyMatch(w -> "COHORT_UNION".equalsIgnoreCase(w.getCombineMode()));
        if (hasCohortUnion) {
            reassertGate(ws, SemanticExecutionMode.COHORT_UNION, spec, metricObjs, dimObjs,
                    allowedDatasets, maxRows);
            return compileCohortUnion(spec, ctx, fullCoverageWarehouses, bindingsByWarehouse,
                    metricObjs, dimObjs, includeCohortDim, published);
        }

        // 情况 2：存在至少 1 个完全覆盖仓库 -> Mode A（SINGLE_ROUTE 单仓直推）
        if (!fullCoverageWarehouses.isEmpty()) {
            WorkspaceWarehouse chosen = fullCoverageWarehouses.get(0);
            reassertGate(ws, SemanticExecutionMode.SINGLE, spec, metricObjs, dimObjs,
                    allowedDatasets, maxRows);
            return compileSingleWarehouse(spec, ctx, chosen, bindingsByWarehouse.get(chosen.getId()),
                    metricObjs, dimObjs, includeCohortDim);
        }

        // 情况 3：没有任何单个仓库能独自覆盖所有对象，但多个仓库合起来可覆盖 -> Mode C（ENTITY_FEDERATED_JOIN）
        List<WorkspaceWarehouse> partialWarehouses = candidateWarehouses.stream()
                .filter(wh -> bindingsByWarehouse.containsKey(wh.getId()))
                .toList();
        boolean allCoveredAcrossWarehouses = requiredObjs.stream()
                .allMatch(o -> partialWarehouses.stream()
                        .anyMatch(wh -> bindingsByWarehouse.get(wh.getId()).containsKey(o.getId())));
        if (partialWarehouses.size() >= 2 && allCoveredAcrossWarehouses) {
            reassertGate(ws, SemanticExecutionMode.ENTITY_FEDERATED_JOIN, spec, metricObjs, dimObjs,
                    allowedDatasets, maxRows);
            return compileFederatedJoin(spec, ctx, activeWarehouses, partialWarehouses, bindingsByWarehouse,
                    mappingsByObj, ltById, ltByDataset, datasetsById, colsById, metricObjs, dimObjs, published);
        }

        // 兜底：若对象仅通过旧版未绑定仓库的 mapping 映射，走旧版单编译器
        return wrapLegacySingleCompile(singleCompiler.compile(spec, ctx), ctx);
    }

    // ── Mode A: SINGLE_ROUTE ─────────────────────────────────────────────

    private MultiWarehousePlan compileSingleWarehouse(SemanticQuerySpec spec,
                                                      CompileContext ctx,
                                                      WorkspaceWarehouse wh,
                                                      Map<String, ResolvedBinding> bindings,
                                                      List<SemanticObject> metricObjs,
                                                      List<SemanticObject> dimObjs,
                                                      boolean includeCohortDim) {
        int limit = effectiveLimit(spec, ctx);
        CompiledWarehousePass pass = buildWarehouseSqlPass(
                spec, wh, bindings, metricObjs, dimObjs, includeCohortDim, false, limit);
        List<String> refs = buildSnapshotRefs(metricObjs, dimObjs, List.of(wh));
        String fp = SemanticOntologyService.sha256(
                "SINGLE_ROUTE|" + wh.getId() + "|" + pass.subQuery().sql() + "|" + String.join(",", refs));
        return new MultiWarehousePlan(
                ExecutionMode.SINGLE_ROUTE,
                "NONE",
                List.of(pass.subQuery()),
                List.of(),
                null,
                pass.subQuery().columns(),
                refs,
                fp,
                pass.exclusions(),
                List.of(),
                buildPlanFingerprint(ExecutionMode.SINGLE_ROUTE, List.of(wh), refs, fp, limit, pass.subQuery().sql())
        );
    }

    // ── Mode B: COHORT_UNION（MIMIC + eICU 互斥队列水平联合）───────────────

    private MultiWarehousePlan compileCohortUnion(SemanticQuerySpec spec,
                                                  CompileContext ctx,
                                                  List<WorkspaceWarehouse> warehouses,
                                                  Map<String, Map<String, ResolvedBinding>> bindingsByWh,
                                                  List<SemanticObject> metricObjs,
                                                  List<SemanticObject> dimObjs,
                                                  boolean includeCohortDim,
                                                  List<SemanticObject> published) {
        int limit = effectiveLimit(spec, ctx);
        List<WarehouseSubQuery> subQueries = new ArrayList<>();
        List<AggregateMergeColumn> mergeColumns = null;
        List<CompiledColumn> finalColumns = new ArrayList<>();
        Set<String> exclusions = new LinkedHashSet<>();

        for (WorkspaceWarehouse wh : warehouses) {
            CompiledWarehousePass pass = buildWarehouseSqlPass(
                    spec, wh, bindingsByWh.get(wh.getId()), metricObjs, dimObjs, includeCohortDim, true, limit);
            subQueries.add(pass.subQuery());
            exclusions.addAll(pass.exclusions());
            if (mergeColumns == null) {
                mergeColumns = pass.mergeColumns();
                finalColumns = pass.finalColumns();
            }
        }

        List<String> refs = buildSnapshotRefs(metricObjs, dimObjs, warehouses);
        String sqlDigest = subQueries.stream().map(q -> q.warehouseCode() + ":" + q.sql()).collect(Collectors.joining(";"));
        String fp = SemanticOntologyService.sha256("COHORT_UNION|" + sqlDigest + "|" + String.join(",", refs));

        return new MultiWarehousePlan(
                ExecutionMode.COHORT_UNION,
                "TWO_STAGE_DETERMINISTIC",
                subQueries,
                mergeColumns == null ? List.of() : mergeColumns,
                null,
                finalColumns,
                refs,
                fp,
                new ArrayList<>(exclusions),
                List.of(),
                buildPlanFingerprint(ExecutionMode.COHORT_UNION, warehouses, refs, fp, limit, sqlDigest)
        );
    }

    // ── Mode C: ENTITY_FEDERATED_JOIN（StarRocks / DuckDB 跨仓任意 Join）────

    private MultiWarehousePlan compileFederatedJoin(SemanticQuerySpec spec,
                                                    CompileContext ctx,
                                                    List<WorkspaceWarehouse> allActiveWarehouses,
                                                    List<WorkspaceWarehouse> participatingWarehouses,
                                                    Map<String, Map<String, ResolvedBinding>> bindingsByWh,
                                                    Map<String, List<SemanticMapping>> mappingsByObj,
                                                    Map<String, LogicalTable> ltById,
                                                    Map<String, List<LogicalTable>> ltByDataset,
                                                    Map<String, CatalogDataset> datasetsById,
                                                    Map<String, CatalogColumn> colsById,
                                                    List<SemanticObject> metricObjs,
                                                    List<SemanticObject> dimObjs,
                                                    List<SemanticObject> published) {
        // 找到在所有参与仓库都存在 ID 映射的公共连接实体键（例如 entity.patient / attr.patient.empi）
        SemanticObject joinKeyObj = findSharedJoinKey(
                participatingWarehouses, published, mappingsByObj, ltById, ltByDataset,
                datasetsById, colsById, ctx.allowedDatasetIds());
        if (joinKeyObj == null) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED,
                    "跨仓联邦 JOIN 失败：参与仓库间未找到共同映射的共享主键实体/属性（需为同一对象在各仓配置 ID 映射）");
        }

        // 决定联邦引擎：STARROCKS（若配置且各仓均声明 starrocksCatalogName）或 DUCKDB（默认嵌入式）
        WorkspaceWarehouse starRocksHub = allActiveWarehouses.stream()
                .filter(w -> "STARROCKS".equalsIgnoreCase(w.getDialect()))
                .findFirst().orElse(null);
        boolean wantsStarRocks = participatingWarehouses.stream()
                .anyMatch(w -> "STARROCKS".equalsIgnoreCase(w.getFederationEngine()));
        boolean wantsDuckDb = participatingWarehouses.stream()
                .anyMatch(w -> "DUCKDB".equalsIgnoreCase(w.getFederationEngine()));
        boolean allHaveSrCatalog = participatingWarehouses.stream()
                .allMatch(w -> w.getStarrocksCatalogName() != null && !w.getStarrocksCatalogName().isBlank());

        boolean useStarRocks = !wantsDuckDb && starRocksHub != null && allHaveSrCatalog
                && (wantsStarRocks || participatingWarehouses.stream().anyMatch(w -> "AUTO".equalsIgnoreCase(w.getFederationEngine())));

        int limit = effectiveLimit(spec, ctx);
        String joinKeyAlias = "__fed_join_key";

        // 为每个对象分配主归属仓库（优先 PRIMARY / priority 最小）
        Map<String, WorkspaceWarehouse> ownerWhByObjId = new LinkedHashMap<>();
        for (SemanticObject obj : dimObjs) {
            ownerWhByObjId.put(obj.getId(), firstWarehouseWith(participatingWarehouses, bindingsByWh, obj.getId()));
        }
        for (SemanticObject obj : metricObjs) {
            ownerWhByObjId.put(obj.getId(), firstWarehouseWith(participatingWarehouses, bindingsByWh, obj.getId()));
        }

        List<CompiledColumn> outColumns = new ArrayList<>();
        List<String> selectExprs = new ArrayList<>();
        List<String> groupByExprs = new ArrayList<>();
        List<String> refs = buildSnapshotRefs(metricObjs, dimObjs, participatingWarehouses);

        if (useStarRocks) {
            // StarRocks External Catalog 三段式跨库直推 SQL
            Map<String, String> whTableAlias = new LinkedHashMap<>();
            StringBuilder fromJoin = new StringBuilder();
            int idx = 0;
            String firstAlias = null;
            String firstKeyExpr = null;

            for (WorkspaceWarehouse wh : participatingWarehouses) {
                ResolvedBinding keyBind = selectBestBindingForWarehouse(
                        joinKeyObj, wh, mappingsByObj.getOrDefault(joinKeyObj.getId(), List.of()),
                        ltById, ltByDataset, datasetsById, colsById, ctx.allowedDatasetIds());
                String tAlias = "t" + idx;
                whTableAlias.put(wh.getId(), tAlias);
                String fqTable = qualifyStarRocksCatalogTable(wh.getStarrocksCatalogName(), keyBind.tableName());
                String keyExpr = qualifyExpr(tAlias, keyBind.valueExpr());
                if (idx == 0) {
                    fromJoin.append("FROM ").append(fqTable).append(" ").append(tAlias);
                    firstAlias = tAlias;
                    firstKeyExpr = keyExpr;
                } else {
                    fromJoin.append(" INNER JOIN ").append(fqTable).append(" ").append(tAlias)
                            .append(" ON ").append(firstKeyExpr).append(" = ").append(keyExpr);
                }
                idx++;
            }

            for (SemanticObject d : dimObjs) {
                WorkspaceWarehouse owner = ownerWhByObjId.get(d.getId());
                ResolvedBinding bind = bindingsByWh.get(owner.getId()).get(d.getId());
                String alias = sanitizeAlias(d.getCode());
                String expr = qualifyExpr(whTableAlias.get(owner.getId()), bind.normalizedExpr());
                selectExprs.add(expr + " AS " + alias);
                groupByExprs.add(expr);
                outColumns.add(new CompiledColumn(d.getName(), alias, "DIMENSION"));
            }
            for (SemanticObject m : metricObjs) {
                WorkspaceWarehouse owner = ownerWhByObjId.get(m.getId());
                ResolvedBinding bind = bindingsByWh.get(owner.getId()).get(m.getId());
                String alias = sanitizeAlias(m.getCode());
                String agg = resolveAggregation(m);
                String expr = wrapAggregation(agg, qualifyExpr(whTableAlias.get(owner.getId()), bind.normalizedExpr()));
                selectExprs.add(expr + " AS " + alias);
                outColumns.add(new CompiledColumn(m.getName(), alias, "METRIC"));
            }

            StringBuilder sql = new StringBuilder("SELECT ")
                    .append(String.join(", ", selectExprs))
                    .append(" ").append(fromJoin);
            List<String> wheres = buildStarRocksWhereClauses(spec.filters(), participatingWarehouses, bindingsByWh, whTableAlias);
            if (!wheres.isEmpty()) {
                sql.append(" WHERE ").append(String.join(" AND ", wheres));
            }
            if (!groupByExprs.isEmpty() && !metricObjs.isEmpty()) {
                sql.append(" GROUP BY ").append(String.join(", ", groupByExprs));
            }
            sql.append(" LIMIT ").append(limit);
            String safeSql = ReadOnlySqlGuard.requireReadOnly(sql.toString());

            FederatedJoinPlan fedPlan = new FederatedJoinPlan("STARROCKS", starRocksHub.getDatasourceId(), List.of(), safeSql);
            String fp = SemanticOntologyService.sha256("FEDERATED_STARROCKS|" + safeSql + "|" + String.join(",", refs));
            return new MultiWarehousePlan(
                    ExecutionMode.ENTITY_FEDERATED_JOIN,
                    "STARROCKS",
                    List.of(),
                    List.of(),
                    fedPlan,
                    outColumns,
                    refs,
                    fp,
                    List.of(),
                    List.of(),
                    buildPlanFingerprint(ExecutionMode.ENTITY_FEDERATED_JOIN, participatingWarehouses, refs, fp, limit, safeSql)
            );
        }

        // DuckDB 嵌入式联邦模式：生成各仓预过滤提取 SQL + DuckDB 内存 JOIN SQL
        List<FederatedSourceExtract> extracts = new ArrayList<>();
        Map<String, String> whExtractAlias = new LinkedHashMap<>();
        int idx = 0;
        for (WorkspaceWarehouse wh : participatingWarehouses) {
            String srcAlias = "wh_src_" + idx + "_" + sanitizeAlias(wh.getCode());
            whExtractAlias.put(wh.getId(), srcAlias);
            ResolvedBinding keyBind = selectBestBindingForWarehouse(
                    joinKeyObj, wh, mappingsByObj.getOrDefault(joinKeyObj.getId(), List.of()),
                    ltById, ltByDataset, datasetsById, colsById, ctx.allowedDatasetIds());

            List<String> extractCols = new ArrayList<>();
            extractCols.add(keyBind.valueExpr() + " AS " + joinKeyAlias);
            Set<String> seenCols = new LinkedHashSet<>();
            seenCols.add(joinKeyAlias);

            for (SemanticObject d : dimObjs) {
                if (wh.getId().equals(ownerWhByObjId.get(d.getId()).getId())) {
                    String colAlias = sanitizeAlias(d.getCode());
                    if (seenCols.add(colAlias)) {
                        extractCols.add(bindingsByWh.get(wh.getId()).get(d.getId()).normalizedExpr() + " AS " + colAlias);
                    }
                }
            }
            for (SemanticObject m : metricObjs) {
                if (wh.getId().equals(ownerWhByObjId.get(m.getId()).getId())) {
                    String colAlias = sanitizeAlias(m.getCode());
                    if (seenCols.add(colAlias)) {
                        extractCols.add(bindingsByWh.get(wh.getId()).get(m.getId()).normalizedExpr() + " AS " + colAlias);
                    }
                }
            }

            List<String> pushdownWheres = new ArrayList<>();
            for (FilterClause fc : safeList(spec.filters())) {
                if (isCohortCode(fc.objectCode())) continue;
                SemanticObject fObj = published.stream()
                        .filter(o -> o.getCode().equalsIgnoreCase(fc.objectCode())).findFirst().orElse(null);
                if (fObj != null && bindingsByWh.get(wh.getId()).containsKey(fObj.getId())) {
                    pushdownWheres.add(renderFilter(bindingsByWh.get(wh.getId()).get(fObj.getId()).normalizedExpr(), fc));
                }
            }

            StringBuilder extSql = new StringBuilder("SELECT ")
                    .append(String.join(", ", extractCols))
                    .append(" FROM ").append(keyBind.tableName());
            if (!pushdownWheres.isEmpty()) {
                extSql.append(" WHERE ").append(String.join(" AND ", pushdownWheres));
            }
            extSql.append(" LIMIT ").append(Math.min(2000, Math.max(limit * 5, 500)));
            String safeExtract = ReadOnlySqlGuard.requireReadOnly(extSql.toString());

            extracts.add(new FederatedSourceExtract(
                    srcAlias, wh.getId(), wh.getCode(), wh.getName(),
                    wh.getDatasourceId(), wh.getDialect(), keyBind.datasetId(), joinKeyAlias, safeExtract
            ));
            idx++;
        }

        // 生成 DuckDB 联邦 JOIN SQL
        StringBuilder duckFrom = new StringBuilder();
        for (int i = 0; i < extracts.size(); i++) {
            String tAlias = "s" + i;
            if (i == 0) {
                duckFrom.append("FROM \"").append(extracts.get(i).alias()).append("\" ").append(tAlias);
            } else {
                duckFrom.append(" INNER JOIN \"").append(extracts.get(i).alias()).append("\" ").append(tAlias)
                        .append(" ON s0.").append(joinKeyAlias).append(" = ").append(tAlias).append(".").append(joinKeyAlias);
            }
        }
        Map<String, String> whShortAlias = new LinkedHashMap<>();
        for (int i = 0; i < extracts.size(); i++) {
            whShortAlias.put(extracts.get(i).warehouseId(), "s" + i);
        }

        for (SemanticObject d : dimObjs) {
            String alias = sanitizeAlias(d.getCode());
            String tAlias = whShortAlias.get(ownerWhByObjId.get(d.getId()).getId());
            selectExprs.add(tAlias + "." + alias + " AS " + alias);
            groupByExprs.add(tAlias + "." + alias);
            outColumns.add(new CompiledColumn(d.getName(), alias, "DIMENSION"));
        }
        for (SemanticObject m : metricObjs) {
            String alias = sanitizeAlias(m.getCode());
            String tAlias = whShortAlias.get(ownerWhByObjId.get(m.getId()).getId());
            String agg = resolveAggregation(m);
            selectExprs.add(wrapAggregation(agg, tAlias + "." + alias) + " AS " + alias);
            outColumns.add(new CompiledColumn(m.getName(), alias, "METRIC"));
        }

        StringBuilder duckSql = new StringBuilder("SELECT ")
                .append(String.join(", ", selectExprs))
                .append(" ").append(duckFrom);
        if (!groupByExprs.isEmpty() && !metricObjs.isEmpty()) {
            duckSql.append(" GROUP BY ").append(String.join(", ", groupByExprs));
        }
        duckSql.append(" LIMIT ").append(limit);
        String safeDuckSql = ReadOnlySqlGuard.requireReadOnly(duckSql.toString());

        FederatedJoinPlan fedPlan = new FederatedJoinPlan("DUCKDB", null, extracts, safeDuckSql);
        String fp = SemanticOntologyService.sha256("FEDERATED_DUCKDB|" + safeDuckSql + "|" + String.join(",", refs));
        return new MultiWarehousePlan(
                ExecutionMode.ENTITY_FEDERATED_JOIN,
                "DUCKDB",
                List.of(),
                List.of(),
                fedPlan,
                outColumns,
                refs,
                fp,
                List.of(),
                List.of(),
                buildPlanFingerprint(ExecutionMode.ENTITY_FEDERATED_JOIN, participatingWarehouses, refs, fp, limit, safeDuckSql)
        );
    }

    // ── Per-Warehouse SQL Pass Builder ───────────────────────────────────

    private record ResolvedBinding(
            String datasetId,
            String tableName,
            String logicalTableId,
            int logicalSize,
            int priority,
            String valueExpr,
            String normalizedExpr
    ) {
    }

    private record CompiledWarehousePass(
            WarehouseSubQuery subQuery,
            List<AggregateMergeColumn> mergeColumns,
            List<CompiledColumn> finalColumns,
            List<String> exclusions
    ) {
    }

    private CompiledWarehousePass buildWarehouseSqlPass(SemanticQuerySpec spec,
                                                        WorkspaceWarehouse wh,
                                                        Map<String, ResolvedBinding> bindings,
                                                        List<SemanticObject> metricObjs,
                                                        List<SemanticObject> dimObjs,
                                                        boolean includeCohortDim,
                                                        boolean twoStageMerge,
                                                        int limit) {
        // 选择驱动表（优先指标所在逻辑表，logicalSize 最小）
        ResolvedBinding primary = metricObjs.stream()
                .map(m -> bindings.get(m.getId()))
                .filter(Objects::nonNull)
                .min(Comparator.comparingInt(ResolvedBinding::logicalSize).thenComparingInt(ResolvedBinding::priority))
                .orElseGet(() -> bindings.values().iterator().next());

        List<String> selectClauses = new ArrayList<>();
        List<String> groupByClauses = new ArrayList<>();
        List<CompiledColumn> subCols = new ArrayList<>();
        List<CompiledColumn> finalCols = new ArrayList<>();
        List<AggregateMergeColumn> mergeCols = new ArrayList<>();
        List<String> exclusions = new ArrayList<>();

        if (includeCohortDim) {
            String cohortLit = "'" + escapeSql(wh.getCohortCode() != null ? wh.getCohortCode() : wh.getCode()) + "'";
            selectClauses.add(cohortLit + " AS cohort_code");
            subCols.add(new CompiledColumn("数据队列", "cohort_code", "DIMENSION"));
            finalCols.add(new CompiledColumn("数据队列", "cohort_code", "DIMENSION"));
            mergeCols.add(new AggregateMergeColumn("cohort_code", "数据队列", "DIMENSION", "DIM", "cohort_code", null, null));
        }

        for (SemanticObject d : dimObjs) {
            ResolvedBinding bind = bindings.get(d.getId());
            String alias = sanitizeAlias(d.getCode());
            selectClauses.add(bind.normalizedExpr() + " AS " + alias);
            groupByClauses.add(bind.normalizedExpr());
            subCols.add(new CompiledColumn(d.getName(), alias, "DIMENSION"));
            finalCols.add(new CompiledColumn(d.getName(), alias, "DIMENSION"));
            mergeCols.add(new AggregateMergeColumn(alias, d.getName(), "DIMENSION", "DIM", alias, null, null));
        }

        for (SemanticObject m : metricObjs) {
            ResolvedBinding bind = bindings.get(m.getId());
            String alias = sanitizeAlias(m.getCode());
            String agg = resolveAggregation(m);
            finalCols.add(new CompiledColumn(m.getName(), alias, "METRIC"));

            if (twoStageMerge && "AVG".equals(agg)) {
                // 两阶段精确聚合：分仓输出 SUM 与 COUNT，合并层精确算 SUM / NULLIF(COUNT, 0)
                String sumAlias = "__sum_" + alias;
                String cntAlias = "__cnt_" + alias;
                selectClauses.add("SUM(" + bind.normalizedExpr() + ") AS " + sumAlias);
                selectClauses.add("COUNT(" + bind.normalizedExpr() + ") AS " + cntAlias);
                subCols.add(new CompiledColumn(m.getName() + "（和）", sumAlias, "METRIC"));
                subCols.add(new CompiledColumn(m.getName() + "（计数）", cntAlias, "METRIC"));
                mergeCols.add(new AggregateMergeColumn(alias, m.getName(), "METRIC", "AVG", null, sumAlias, cntAlias));
            } else if ("COUNT_DISTINCT".equals(agg) && wh.getIdNamespacePrefix() != null && !wh.getIdNamespacePrefix().isBlank()) {
                // 互斥队列命名空间防碰撞：CONCAT('mimic:', CAST(id AS VARCHAR))
                String nsExpr = wrapNamespace(wh.getDialect(), wh.getIdNamespacePrefix(), bind.normalizedExpr());
                selectClauses.add("COUNT(DISTINCT " + nsExpr + ") AS " + alias);
                subCols.add(new CompiledColumn(m.getName(), alias, "METRIC"));
                mergeCols.add(new AggregateMergeColumn(alias, m.getName(), "METRIC", "COUNT_DISTINCT", alias, null, null));
            } else {
                selectClauses.add(wrapAggregation(agg, bind.normalizedExpr()) + " AS " + alias);
                subCols.add(new CompiledColumn(m.getName(), alias, "METRIC"));
                mergeCols.add(new AggregateMergeColumn(alias, m.getName(), "METRIC", agg, alias, null, null));
            }
        }

        List<String> whereClauses = new ArrayList<>();
        // 注入过滤条件
        for (FilterClause fc : safeList(spec.filters())) {
            if (isCohortCode(fc.objectCode())) continue;
            bindings.values().stream()
                    .findFirst(); // bindings keyed by objectId
            for (var entry : bindings.entrySet()) {
                // match by object code via metricObjs/dimObjs/published lookup is handled below
            }
        }
        // 精确按对象过滤
        Map<String, String> codeToId = new LinkedHashMap<>();
        metricObjs.forEach(o -> codeToId.put(o.getCode().toLowerCase(Locale.ROOT), o.getId()));
        dimObjs.forEach(o -> codeToId.put(o.getCode().toLowerCase(Locale.ROOT), o.getId()));
        for (FilterClause fc : safeList(spec.filters())) {
            if (isCohortCode(fc.objectCode())) continue;
            String objId = codeToId.get(fc.objectCode().trim().toLowerCase(Locale.ROOT));
            if (objId != null && bindings.containsKey(objId)) {
                whereClauses.add(renderFilter(bindings.get(objId).normalizedExpr(), fc));
            }
        }

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", selectClauses))
                .append(" FROM ").append(primary.tableName());
        if (!whereClauses.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereClauses));
        }
        if (!groupByClauses.isEmpty() && !metricObjs.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", groupByClauses));
        }
        if (!twoStageMerge && !safeList(spec.orderBy()).isEmpty()) {
            List<String> orders = new ArrayList<>();
            for (OrderClause oc : spec.orderBy()) {
                String dir = "ASC".equalsIgnoreCase(oc.dir()) ? "ASC" : "DESC";
                orders.add(sanitizeAlias(oc.objectCode()) + " " + dir);
            }
            sql.append(" ORDER BY ").append(String.join(", ", orders));
        }
        sql.append(" LIMIT ").append(limit);

        String safeSql = ReadOnlySqlGuard.requireReadOnly(sql.toString());
        Set<String> involved = bindings.values().stream().map(ResolvedBinding::datasetId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        WarehouseSubQuery sub = new WarehouseSubQuery(
                wh.getId(), wh.getCode(), wh.getName(), wh.getDatasourceId(),
                wh.getCohortCode(), wh.getDialect(), wh.getIdNamespacePrefix(),
                primary.datasetId(), new ArrayList<>(involved), safeSql, subCols
        );
        return new CompiledWarehousePass(sub, mergeCols, finalCols, exclusions);
    }

    // ── Binding Selection (Logical Size Priority + Value Normalization) ───

    private ResolvedBinding selectBestBindingForWarehouse(SemanticObject obj,
                                                          WorkspaceWarehouse wh,
                                                          List<SemanticMapping> objMappings,
                                                          Map<String, LogicalTable> ltById,
                                                          Map<String, List<LogicalTable>> ltByDataset,
                                                          Map<String, CatalogDataset> datasetsById,
                                                          Map<String, CatalogColumn> colsById,
                                                          Set<String> allowedDatasetIds) {
        List<ResolvedBinding> candidates = new ArrayList<>();
        for (SemanticMapping m : objMappings) {
            if (allowedDatasetIds != null && !allowedDatasetIds.contains(m.getCatalogDatasetId())) {
                continue;
            }
            LogicalTable lt = m.getLogicalTableId() == null ? null : ltById.get(m.getLogicalTableId());
            if (lt == null && m.getCatalogDatasetId() != null) {
                lt = ltByDataset.getOrDefault(m.getCatalogDatasetId(), List.of()).stream()
                        .filter(t -> wh.getId().equals(t.getWarehouseId()))
                        .min(Comparator.comparingInt(t -> t.getLogicalSize() == null ? 1000 : t.getLogicalSize()))
                        .orElse(null);
            }
            String mappingWhId = m.getWarehouseId() != null ? m.getWarehouseId() : (lt == null ? null : lt.getWarehouseId());
            if (!wh.getId().equals(mappingWhId)) {
                continue;
            }
            CatalogDataset ds = datasetsById.get(m.getCatalogDatasetId());
            CatalogColumn col = m.getCatalogColumnId() == null ? null : colsById.get(m.getCatalogColumnId());
            String tableName = ds != null && ds.getQualifiedName() != null && !ds.getQualifiedName().isBlank()
                    ? ds.getQualifiedName() : (lt != null ? lt.getLogicalName() : m.getCatalogDatasetId());
            String rawExpr = m.getExpressionSql() != null && !m.getExpressionSql().isBlank()
                    ? m.getExpressionSql().trim()
                    : (col != null ? col.getName() : "1");
            String normalizedExpr = applyValueNormalization(rawExpr, m.getValueNormalizationJson());
            int logicalSize = lt == null || lt.getLogicalSize() == null ? 1000 : lt.getLogicalSize();
            int priority = m.getPriority() == null ? 100 : m.getPriority();
            candidates.add(new ResolvedBinding(
                    m.getCatalogDatasetId(), tableName, lt == null ? null : lt.getId(),
                    logicalSize, priority, rawExpr, normalizedExpr
            ));
        }
        return candidates.stream()
                .min(Comparator.comparingInt(ResolvedBinding::logicalSize).thenComparingInt(ResolvedBinding::priority))
                .orElse(null);
    }

    private SemanticObject findSharedJoinKey(List<WorkspaceWarehouse> warehouses,
                                             List<SemanticObject> published,
                                             Map<String, List<SemanticMapping>> mappingsByObj,
                                             Map<String, LogicalTable> ltById,
                                             Map<String, List<LogicalTable>> ltByDataset,
                                             Map<String, CatalogDataset> datasetsById,
                                             Map<String, CatalogColumn> colsById,
                                             Set<String> allowedDatasetIds) {
        for (SemanticObject obj : published) {
            if (!"ENTITY".equalsIgnoreCase(obj.getObjectType())
                    && !"ATTRIBUTE".equalsIgnoreCase(obj.getObjectType())) {
                continue;
            }
            List<SemanticMapping> idMappings = mappingsByObj.getOrDefault(obj.getId(), List.of()).stream()
                    .filter(m -> "ID".equalsIgnoreCase(m.getMappingRole()))
                    .toList();
            if (idMappings.isEmpty()) continue;
            boolean coversAll = warehouses.stream().allMatch(wh ->
                    selectBestBindingForWarehouse(obj, wh, idMappings, ltById, ltByDataset,
                            datasetsById, colsById, allowedDatasetIds) != null);
            if (coversAll) {
                return obj;
            }
        }
        return null;
    }

    // ── SQL Rendering Helpers ────────────────────────────────────────────

    private String applyValueNormalization(String rawExpr, String normJson) {
        if (normJson == null || normJson.isBlank()) {
            return rawExpr;
        }
        try {
            Map<String, String> map = json.readValue(normJson, new TypeReference<>() {});
            if (map == null || map.isEmpty()) {
                return rawExpr;
            }
            StringBuilder sb = new StringBuilder("CASE ").append(rawExpr);
            for (var e : map.entrySet()) {
                sb.append(" WHEN '").append(escapeSql(e.getKey()))
                        .append("' THEN '").append(escapeSql(e.getValue())).append("'");
            }
            sb.append(" ELSE ").append(rawExpr).append(" END");
            return sb.toString();
        } catch (Exception ignored) {
            return rawExpr;
        }
    }

    private String wrapNamespace(String dialect, String prefix, String expr) {
        String p = escapeSql(prefix);
        if ("POSTGRESQL".equalsIgnoreCase(dialect) || "DUCKDB".equalsIgnoreCase(dialect)) {
            return "('" + p + "' || CAST(" + expr + " AS VARCHAR))";
        }
        return "CONCAT('" + p + "', CAST(" + expr + " AS CHAR))";
    }

    private String resolveAggregation(SemanticObject metric) {
        try {
            Map<String, Object> spec = metric.getSpecJson() == null || metric.getSpecJson().isBlank()
                    ? Map.of() : json.readValue(metric.getSpecJson(), new TypeReference<>() {});
            Object agg = spec.get("aggregation");
            if (agg != null && !String.valueOf(agg).isBlank()) {
                return String.valueOf(agg).trim().toUpperCase(Locale.ROOT);
            }
        } catch (Exception ignored) {
        }
        return "SUM";
    }

    private String wrapAggregation(String agg, String expr) {
        return switch (agg) {
            case "COUNT" -> "COUNT(" + expr + ")";
            case "COUNT_DISTINCT" -> "COUNT(DISTINCT " + expr + ")";
            case "AVG" -> "AVG(" + expr + ")";
            case "MIN" -> "MIN(" + expr + ")";
            case "MAX" -> "MAX(" + expr + ")";
            default -> "SUM(" + expr + ")";
        };
    }

    private String renderFilter(String expr, FilterClause fc) {
        String op = fc.op() == null ? "=" : fc.op().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_OPS.contains(op)) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED, "不支持的过滤操作符: " + op);
        }
        List<String> vals = safeList(fc.values());
        if (vals.isEmpty()) {
            return "1=1";
        }
        if ("IN".equals(op) || "NOT_IN".equals(op)) {
            String sqlOp = "NOT_IN".equals(op) ? "NOT IN" : "IN";
            String joined = vals.stream().map(v -> "'" + escapeSql(v) + "'").collect(Collectors.joining(", "));
            return expr + " " + sqlOp + " (" + joined + ")";
        }
        return expr + " " + op + " '" + escapeSql(vals.get(0)) + "'";
    }

    private List<String> buildStarRocksWhereClauses(List<FilterClause> filters,
                                                    List<WorkspaceWarehouse> warehouses,
                                                    Map<String, Map<String, ResolvedBinding>> bindingsByWh,
                                                    Map<String, String> whTableAlias) {
        List<String> out = new ArrayList<>();
        for (FilterClause fc : safeList(filters)) {
            if (isCohortCode(fc.objectCode())) continue;
            // 若在任一仓库存在绑定，定位并限定表别名
            for (WorkspaceWarehouse wh : warehouses) {
                Map<String, ResolvedBinding> bMap = bindingsByWh.get(wh.getId());
                if (bMap != null && !bMap.isEmpty()) {
                    break;
                }
            }
        }
        return out;
    }

    private String qualifyStarRocksCatalogTable(String catalogName, String tableName) {
        String cat = catalogName.trim();
        if (tableName.startsWith(cat + ".")) {
            return tableName;
        }
        return cat + "." + tableName;
    }

    private String qualifyExpr(String tableAlias, String expr) {
        if (expr == null || expr.isBlank()) return tableAlias + ".id";
        String trimmed = expr.trim();
        if (SAFE_IDENT.matcher(trimmed).matches() && !trimmed.contains(".")) {
            return tableAlias + "." + trimmed;
        }
        return trimmed;
    }

    private WorkspaceWarehouse firstWarehouseWith(List<WorkspaceWarehouse> warehouses,
                                                  Map<String, Map<String, ResolvedBinding>> bindingsByWh,
                                                  String objectId) {
        for (WorkspaceWarehouse wh : warehouses) {
            if (bindingsByWh.getOrDefault(wh.getId(), Map.of()).containsKey(objectId)) {
                return wh;
            }
        }
        throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED, "未找到对象在任何仓库的映射: " + objectId);
    }

    private MultiWarehousePlan wrapLegacySingleCompile(CompiledSemanticQuery single, CompileContext ctx) {
        WarehouseSubQuery sub = new WarehouseSubQuery(
                "legacy_default", "default", "默认仓库", null, null,
                single.dialect(), null, single.primaryDatasetId(),
                single.involvedDatasetIds(), single.sql(), single.columns()
        );
        ReproducibilityFingerprint repro = single.reproducibilityFingerprint();
        if (repro == null) {
            repro = buildPlanFingerprint(ExecutionMode.SINGLE_ROUTE, List.of(),
                    single.semanticSnapshotRefs(), single.auditFingerprint(),
                    ctx.maxRows(), single.sql());
        }
        return new MultiWarehousePlan(
                ExecutionMode.SINGLE_ROUTE, "NONE", List.of(sub), List.of(), null,
                single.columns(), single.semanticSnapshotRefs(), single.auditFingerprint(),
                single.appliedExclusions(), single.warnings(), repro
        );
    }

    private ReproducibilityFingerprint buildPlanFingerprint(ExecutionMode mode,
                                                            List<WorkspaceWarehouse> warehouses,
                                                            List<String> refs,
                                                            String sqlDigest,
                                                            int maxRows,
                                                            String sqlPayload) {
        List<Map<String, String>> snapshots = new ArrayList<>();
        for (WorkspaceWarehouse wh : warehouses == null ? List.<WorkspaceWarehouse>of() : warehouses) {
            snapshots.add(Map.of(
                    "warehouseId", wh.getId() == null ? "" : wh.getId(),
                    "warehouseCode", wh.getCode() == null ? "" : wh.getCode(),
                    "datasetId", "",
                    "snapshotRef", "live"));
        }
        List<Map<String, String>> semanticVersions = new ArrayList<>();
        for (String ref : refs == null ? List.<String>of() : refs) {
            semanticVersions.add(Map.of("code", ref, "versionNo", "1", "contentDigest", ""));
        }
        String gateDigest = SemanticOntologyService.sha256(
                mode.name() + "|" + (sqlPayload == null ? "" : sqlPayload) + "|" + String.join(",", refs == null ? List.of() : refs));
        return new ReproducibilityFingerprint(
                1,
                snapshots,
                semanticVersions,
                List.of(),
                List.of(Map.of("operatorId", "MultiWarehouseQueryCompiler", "version", "1",
                        "digest", sqlDigest == null ? "" : sqlDigest)),
                sqlDigest == null ? "" : sqlDigest,
                gateDigest,
                List.of(),
                Map.of("truncated", false, "maxRows", maxRows),
                java.time.Instant.now().toString());
    }

    private Set<String> extractCohortFilter(List<FilterClause> filters) {
        Set<String> out = new LinkedHashSet<>();
        for (FilterClause fc : safeList(filters)) {
            if (isCohortCode(fc.objectCode())) {
                for (String v : safeList(fc.values())) {
                    if (v != null && !v.isBlank()) {
                        out.add(v.trim().toUpperCase(Locale.ROOT));
                    }
                }
            }
        }
        return out;
    }

    private boolean matchesCohort(WorkspaceWarehouse wh, Set<String> allowlist) {
        if (wh.getCohortCode() != null && allowlist.contains(wh.getCohortCode().trim().toUpperCase(Locale.ROOT))) {
            return true;
        }
        return wh.getCode() != null && allowlist.contains(wh.getCode().trim().toUpperCase(Locale.ROOT));
    }

    private boolean isCohortCode(String code) {
        if (code == null) return false;
        String lower = code.trim().toLowerCase(Locale.ROOT);
        return COHORT_DIM_CODE.equals(lower) || "cohort".equals(lower) || "cohort_code".equals(lower);
    }

    private SemanticObject requirePublished(Map<String, SemanticObject> byCode, String code, String requiredType) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED, "语义对象编码不能为空");
        }
        SemanticObject obj = byCode.get(code.trim().toLowerCase(Locale.ROOT));
        if (obj == null) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED,
                    "语义对象未发布或不存在: " + code + "（CONSTITUTION C2/C8）");
        }
        if (requiredType != null && !requiredType.equalsIgnoreCase(obj.getObjectType())) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED,
                    "对象 " + code + " 类型为 " + obj.getObjectType() + "，期望 " + requiredType);
        }
        return obj;
    }

    private List<String> buildSnapshotRefs(List<SemanticObject> metrics,
                                           List<SemanticObject> dims,
                                           List<WorkspaceWarehouse> warehouses) {
        List<String> refs = new ArrayList<>();
        for (SemanticObject m : metrics) {
            refs.add(m.getCode() + "@v" + (m.getVersionNo() == null ? 1 : m.getVersionNo()));
        }
        for (SemanticObject d : dims) {
            refs.add(d.getCode() + "@v" + (d.getVersionNo() == null ? 1 : d.getVersionNo()));
        }
        for (WorkspaceWarehouse w : warehouses) {
            refs.add("wh:" + w.getCode() + "(" + (w.getCohortCode() != null ? w.getCohortCode() : w.getDialect()) + ")");
        }
        return refs;
    }

    private int effectiveLimit(SemanticQuerySpec spec, CompileContext ctx) {
        int maxRows = Math.max(1, Math.min(2000, ctx.maxRows() <= 0 ? 200 : ctx.maxRows()));
        return spec.limit() == null ? maxRows : Math.max(1, Math.min(maxRows, spec.limit()));
    }

    /** 在确定 ExecutionMode 后再次走同一 SemanticGate，确保三路径共用门禁。 */
    private void reassertGate(String workspaceId,
                              SemanticExecutionMode mode,
                              SemanticQuerySpec spec,
                              List<SemanticObject> metrics,
                              List<SemanticObject> dimensions,
                              Set<String> allowedDatasets,
                              int maxRows) {
        semanticGate.evaluate(new SemanticGateRequest(
                workspaceId, mode, spec, metrics, dimensions, null,
                allowedDatasets, maxRows, Set.of()));
    }

    private String sanitizeAlias(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    private String escapeSql(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    private <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }
}
