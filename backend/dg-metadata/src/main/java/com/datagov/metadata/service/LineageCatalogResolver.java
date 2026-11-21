package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogColumn;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogSource;
import com.datagov.metadata.mapper.CatalogColumnMapper;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.CatalogSourceMapper;
import com.datagov.metadata.service.LineageService.NodeRef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 血缘节点与 Catalog 资产的对齐器(column-lineage-e2e P1-3)。
 *
 * <p>血缘边只认 {@code qualifiedName}，而图谱的表级聚合、敏感传播、ACL 都需要节点
 * 绑定 {@code catalogDatasetId / catalogColumnId}。这里把「数据源 / 库 / schema / 表 / 列」
 * 这组作业配置里的自然键解析成 Catalog 主键，解析不到就返回空 —— <b>绝不猜</b>(C8)：
 * 绑错资产比不绑更糟，它会让别人的表出现在这条血缘的下游。
 *
 * <p>所有方法显式接收 {@code workspaceId}：调用方可能是事件监听线程，没有
 * {@code WorkspaceContext}。
 *
 * <p>限定名约定与 {@code CatalogAssetService} 落库一致：
 * {@code database.schema.table}，无 schema 时为 {@code database.table}。
 */
@Component
public class LineageCatalogResolver {

    public static final String NODE_TYPE_COLUMN = "COLUMN";

    private final CatalogDatasetMapper datasetMapper;
    private final CatalogColumnMapper columnMapper;
    private final CatalogSourceMapper sourceMapper;

    public LineageCatalogResolver(CatalogDatasetMapper datasetMapper,
                                  CatalogColumnMapper columnMapper,
                                  CatalogSourceMapper sourceMapper) {
        this.datasetMapper = datasetMapper;
        this.columnMapper = columnMapper;
        this.sourceMapper = sourceMapper;
    }

    // ── 数据集 ──────────────────────────────────────────────────────────

    /**
     * 解析数据集。
     *
     * <p>指定了 {@code datasourceId} 时只在该数据源对应的 Catalog 来源里找：那个数据源
     * 还没有被采集进目录就返回空，而不是退到整个工作空间去撞同名表。
     * 候选限定名按精确度从高到低尝试；精确未命中时再按「以 .schema.table 结尾」
     * 兜底，但兜底必须唯一，出现多个候选视为歧义，返回空。
     */
    public Optional<CatalogDataset> resolveDataset(String workspaceId, String datasourceId,
                                                   String database, String schema, String table) {
        if (isBlank(workspaceId) || isBlank(table)) {
            return Optional.empty();
        }
        Set<String> sourceIds = null;
        if (!isBlank(datasourceId)) {
            sourceIds = sourceIdsOf(workspaceId, datasourceId);
            if (sourceIds.isEmpty()) {
                return Optional.empty();
            }
        }

        for (String candidate : candidateQualifiedNames(database, schema, table)) {
            List<CatalogDataset> hits = datasetMapper.selectList(baseDatasetQuery(workspaceId, sourceIds)
                    .eq(CatalogDataset::getQualifiedName, candidate));
            Optional<CatalogDataset> unique = unique(hits);
            if (unique.isPresent()) {
                return unique;
            }
            if (hits != null && hits.size() > 1) {
                return Optional.empty();
            }
        }

        // 没给 database 时(作业配置常如此)，按尾部匹配兜底。
        if (isBlank(database)) {
            String suffix = "." + tail(schema, table);
            List<CatalogDataset> hits = datasetMapper.selectList(baseDatasetQuery(workspaceId, sourceIds)
                    .likeLeft(CatalogDataset::getQualifiedName, suffix));
            List<CatalogDataset> exact = hits == null ? List.of() : hits.stream()
                    .filter(d -> d.getQualifiedName() != null
                            && d.getQualifiedName().toLowerCase(Locale.ROOT)
                            .endsWith(suffix.toLowerCase(Locale.ROOT)))
                    .toList();
            return unique(exact);
        }
        return Optional.empty();
    }

    /** 与 {@link #resolveDataset} 相同，只返回 ID。 */
    public Optional<String> resolveDatasetId(String workspaceId, String datasourceId,
                                             String database, String schema, String table) {
        return resolveDataset(workspaceId, datasourceId, database, schema, table)
                .map(CatalogDataset::getId);
    }

    // ── 列 ──────────────────────────────────────────────────────────────

    /** 解析列。先精确匹配，再大小写不敏感匹配(唯一时才算命中)。 */
    public Optional<CatalogColumn> resolveColumnEntity(String workspaceId, String catalogDatasetId,
                                                       String columnName) {
        if (isBlank(catalogDatasetId) || isBlank(columnName)) {
            return Optional.empty();
        }
        List<CatalogColumn> columns = columnsOf(catalogDatasetId);
        for (CatalogColumn column : columns) {
            if (columnName.equals(column.getName())) {
                return Optional.of(column);
            }
        }
        List<CatalogColumn> loose = columns.stream()
                .filter(c -> c.getName() != null && c.getName().equalsIgnoreCase(columnName))
                .toList();
        return unique(loose);
    }

    /** @return catalogColumnId */
    public Optional<String> resolveColumn(String workspaceId, String catalogDatasetId,
                                          String columnName) {
        return resolveColumnEntity(workspaceId, catalogDatasetId, columnName)
                .map(CatalogColumn::getId);
    }

    /** 数据集的列名清单，按 ordinal 排序；整库迁移这类只有表名的作业靠它展开列。 */
    public List<String> listColumnNames(String catalogDatasetId) {
        return columnsOf(catalogDatasetId).stream().map(CatalogColumn::getName).toList();
    }

    // ── 节点引用 ────────────────────────────────────────────────────────

    /**
     * 构造列级 {@link NodeRef}。
     *
     * <p>数据集解析成功时用 Catalog 的 {@code qualifiedName + "." + 列名}，
     * 保证与资产详情页、视图采集产出的节点是<b>同一个节点</b>(血缘节点按
     * workspace + type + qualifiedName 去重)；解析不到时退化为由入参拼出的
     * {@code db.schema.table.column}(忽略空段)，catalog ID 留空，等历史回填。
     */
    public NodeRef resolveNodeRef(String workspaceId, String datasourceId, String database,
                                  String schema, String table, String columnName) {
        Optional<CatalogDataset> dataset =
                resolveDataset(workspaceId, datasourceId, database, schema, table);
        String datasetId = dataset.map(CatalogDataset::getId).orElse(null);
        String columnId = dataset
                .flatMap(d -> resolveColumn(workspaceId, d.getId(), columnName))
                .orElse(null);
        String qualified = dataset.map(CatalogDataset::getQualifiedName)
                .filter(name -> !isBlank(name))
                .map(name -> name + "." + columnName)
                .orElseGet(() -> joinNonBlank(database, schema, table, columnName));
        return new NodeRef(NODE_TYPE_COLUMN, qualified, datasetId, columnId, columnName);
    }

    /** 不带 Catalog 对齐的列级引用，用于文件 / API 这类没有表资产的源端。 */
    public static NodeRef externalColumnRef(String qualifiedName, String displayName) {
        return new NodeRef(NODE_TYPE_COLUMN, qualifiedName, null, null, displayName);
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private Set<String> sourceIdsOf(String workspaceId, String datasourceId) {
        List<CatalogSource> sources = sourceMapper.selectList(new LambdaQueryWrapper<CatalogSource>()
                .eq(CatalogSource::getWorkspaceId, workspaceId)
                .eq(CatalogSource::getDataSourceId, datasourceId));
        Set<String> ids = new LinkedHashSet<>();
        if (sources != null) {
            for (CatalogSource source : sources) {
                ids.add(source.getId());
            }
        }
        return ids;
    }

    private static LambdaQueryWrapper<CatalogDataset> baseDatasetQuery(String workspaceId,
                                                                       Set<String> sourceIds) {
        LambdaQueryWrapper<CatalogDataset> query = new LambdaQueryWrapper<CatalogDataset>()
                .eq(CatalogDataset::getWorkspaceId, workspaceId);
        if (sourceIds != null) {
            query.in(CatalogDataset::getSourceId, sourceIds);
        }
        return query;
    }

    private List<CatalogColumn> columnsOf(String catalogDatasetId) {
        if (isBlank(catalogDatasetId)) {
            return List.of();
        }
        List<CatalogColumn> columns = columnMapper.selectList(new LambdaQueryWrapper<CatalogColumn>()
                .eq(CatalogColumn::getDatasetId, catalogDatasetId));
        if (columns == null) {
            return List.of();
        }
        List<CatalogColumn> sorted = new ArrayList<>(columns);
        sorted.sort(Comparator.comparing(CatalogColumn::getOrdinal,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return sorted;
    }

    /** 精确度从高到低：db.schema.table → db.table → schema.table → table。 */
    static List<String> candidateQualifiedNames(String database, String schema, String table) {
        Set<String> candidates = new LinkedHashSet<>();
        if (!isBlank(database) && !isBlank(schema)) {
            candidates.add(database.trim() + "." + schema.trim() + "." + table.trim());
        }
        if (!isBlank(database)) {
            candidates.add(database.trim() + "." + table.trim());
        }
        if (!isBlank(schema)) {
            candidates.add(schema.trim() + "." + table.trim());
        }
        if (isBlank(database)) {
            candidates.add(table.trim());
        }
        return new ArrayList<>(candidates);
    }

    private static String tail(String schema, String table) {
        return isBlank(schema) ? table.trim() : schema.trim() + "." + table.trim();
    }

    private static String joinNonBlank(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (isBlank(part)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(part.trim());
        }
        return sb.toString();
    }

    private static <T> Optional<T> unique(List<T> list) {
        return list != null && list.size() == 1 ? Optional.of(list.get(0)) : Optional.empty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
