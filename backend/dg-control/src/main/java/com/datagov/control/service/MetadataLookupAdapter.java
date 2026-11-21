package com.datagov.control.service;

import com.datagov.control.compile.JobCompiler;
import com.datagov.data.spi.DataSourceType;
import com.datagov.metadata.domain.DataSourceStatus;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.mapper.DataSourceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogColumn;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.SensitivityEntities.ColumnSensitivity;
import com.datagov.metadata.mapper.CatalogColumnMapper;
import com.datagov.metadata.mapper.ColumnSensitivityMapper;
import com.datagov.metadata.service.CatalogService;
import com.datagov.metadata.service.LineageCatalogResolver;
import com.datagov.metadata.service.SemanticCodesetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 编译器向 Metadata 提问的实现。
 *
 * <p>它是 Control 与 Metadata 之间<b>唯一</b>的接触点。集中在一处的价值:
 * 要回答"编译期都读了 Metadata 的什么"这个问题时,只看这一个类,而不必翻遍编译器。
 *
 * <p>注意它只读,而且只读四样东西。Control 的 must_not_do 里没有明写"不得写
 * Metadata",但 H.3 的禁止边里有 —— 编译一次不该改变任何定义的状态。
 */
@Component
public class MetadataLookupAdapter implements JobCompiler.MetadataLookup {

    private static final Logger log = LoggerFactory.getLogger(MetadataLookupAdapter.class);

    private final DataSourceMapper dataSourceMapper;
    private final CatalogService catalogService;
    private final LineageCatalogResolver catalogResolver;
    private final CatalogColumnMapper columnMapper;
    private final ColumnSensitivityMapper sensitivityMapper;
    private final SemanticCodesetService codesetService;

    public MetadataLookupAdapter(DataSourceMapper dataSourceMapper, CatalogService catalogService,
                                 LineageCatalogResolver catalogResolver,
                                 CatalogColumnMapper columnMapper,
                                 ColumnSensitivityMapper sensitivityMapper,
                                 SemanticCodesetService codesetService) {
        this.dataSourceMapper = dataSourceMapper;
        this.catalogService = catalogService;
        this.catalogResolver = catalogResolver;
        this.columnMapper = columnMapper;
        this.sensitivityMapper = sensitivityMapper;
        this.codesetService = codesetService;
    }

    @Override
    public boolean isDataSourceAvailable(String workspaceId, String dataSourceId) {
        DataSourceEntity entity = load(workspaceId, dataSourceId);
        return entity != null && entity.getStatus() == DataSourceStatus.AVAILABLE;
    }

    @Override
    public String dataSourceName(String workspaceId, String dataSourceId) {
        DataSourceEntity entity = load(workspaceId, dataSourceId);
        // 拿不到就回显 ID。报错里出现一个 ID 总好过出现 "null" ——
        // 至少运维还能拿它去查。
        return entity == null ? dataSourceId : entity.getName();
    }

    @Override
    public DataSourceType dataSourceType(String workspaceId, String dataSourceId) {
        DataSourceEntity entity = load(workspaceId, dataSourceId);
        return entity == null ? null : entity.getType();
    }

    @Override
    public Map<String, String> tableColumns(String workspaceId, String dataSourceId,
                                            String database, String schema, String table) {
        try {
            return catalogService.snapshotColumns(workspaceId, dataSourceId, database, schema, table);
        } catch (RuntimeException e) {
            // 没有快照不是编译器的错,是"还没浏览过这张表"。返回空 Map,
            // 由编译器判定成一条带定位的诊断("请先浏览该表的结构"),
            // 而不是在这里抛出一个用户看不懂的异常。
            log.debug("取不到表结构快照 ds={} {}.{}.{}", dataSourceId, database, schema, table, e);
            return Map.of();
        }
    }

    @Override
    public Map<String, String> sensitiveColumnLevels(String workspaceId, String dataSourceId,
                                                     String database, String schema, String table) {
        try {
            Optional<CatalogDataset> dataset =
                    catalogResolver.resolveDataset(workspaceId, dataSourceId, database, schema, table);
            if (dataset.isEmpty()) {
                return Map.of();
            }
            List<CatalogColumn> columns = columnMapper.selectList(new LambdaQueryWrapper<CatalogColumn>()
                    .eq(CatalogColumn::getDatasetId, dataset.get().getId()));
            if (columns.isEmpty()) {
                return Map.of();
            }
            Map<String, String> nameById = new HashMap<>();
            columns.forEach(c -> nameById.put(c.getId(), c.getName()));
            List<ColumnSensitivity> labels = sensitivityMapper.selectList(
                    new LambdaQueryWrapper<ColumnSensitivity>()
                            .eq(ColumnSensitivity::getWorkspaceId, workspaceId)
                            .in(ColumnSensitivity::getColumnId, nameById.keySet())
                            .ne(ColumnSensitivity::getStatus, "REJECTED"));
            Map<String, String> levels = new LinkedHashMap<>();
            for (ColumnSensitivity label : labels) {
                String name = nameById.get(label.getColumnId());
                if (name != null && label.getLevel() != null) {
                    // 同一字段多个标签取最高级别("L4" > "L3" 字典序即级别序)
                    levels.merge(name, label.getLevel().toUpperCase(), (a, b) -> a.compareTo(b) >= 0 ? a : b);
                }
            }
            return levels;
        } catch (RuntimeException e) {
            log.debug("取不到敏感标签 ds={} {}.{}.{}", dataSourceId, database, schema, table, e);
            return Map.of();
        }
    }

    @Override
    public Optional<JobCompiler.CodesetInfo> codeset(String workspaceId, String ref) {
        try {
            return codesetService.resolve(workspaceId, ref).map(obj -> new JobCompiler.CodesetInfo(
                    obj.getId(), obj.getCode(), obj.getName(), obj.getStatus(),
                    codesetService.entryCount(workspaceId, obj)));
        } catch (RuntimeException e) {
            // 查询失败不等于码表不存在;但编译器只能二选一,宁可让它报「不存在」并附原因日志
            log.warn("码表解析失败 ref={}", ref, e);
            return Optional.empty();
        }
    }

    private DataSourceEntity load(String workspaceId, String dataSourceId) {
        if (dataSourceId == null || dataSourceId.isBlank()) {
            return null;
        }
        return dataSourceMapper.selectOne(new LambdaQueryWrapper<DataSourceEntity>()
                .eq(DataSourceEntity::getId, dataSourceId)
                .eq(DataSourceEntity::getWorkspaceId, workspaceId));
    }
}
