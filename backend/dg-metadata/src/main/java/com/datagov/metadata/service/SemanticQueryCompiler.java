package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.data.spi.query.ReadOnlySqlGuard;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogColumn;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.SemanticEntities.SemanticLink;
import com.datagov.metadata.entity.SemanticEntities.SemanticMapping;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.mapper.CatalogColumnMapper;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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
 * 确定性语义 SQL 编译器（OpenSpec: semantic-ontology-kernel · Stage 2）。
 *
 * <p>纯 Java 确定性编译，零 LLM 依赖、零 JDBC 驱动依赖（CONSTITUTION C1/C2/C3/C5/C8）：
 * <ol>
 *   <li>存在性与权限闸门：校验所有指标/维度/过滤均已 PUBLISHED、属于当前 Workspace、绑定 Standard（C2）且物理表可读（C4/C5）；</li>
 *   <li>自动选表与最短确定性 Join 规划：按 {@code md_semantic_mapping} + {@code md_semantic_link(STRUCTURAL)} 生成 JOIN；</li>
 *   <li>多事实表 1:N 防扇出倍增（Fan-out / Chasm Trap）：当指标跨多张事实表时自动改写为各自按共享维度预聚合的 CTE 再关联；</li>
 *   <li>衍生比率除零保护（{@code NULLIF(..., 0)}）、自动注入 {@code defaultFilters} 与知识图谱 {@code EXCLUDES} 约束；</li>
 *   <li>经 {@code dg-data-spi} {@link ReadOnlySqlGuard} 强校验只读并注入 {@code LIMIT}，附带 SHA-256 审计指纹。</li>
 * </ol>
 */
@Service
public class SemanticQueryCompiler {

    private static final Set<String> ALLOWED_OPS = Set.of("=", "!=", ">", ">=", "<", "<=", "IN", "NOT_IN", "LIKE");
    private static final Set<String> ALLOWED_AGGS = Set.of("SUM", "COUNT", "COUNT_DISTINCT", "AVG", "MIN", "MAX");
    private static final Pattern SAFE_IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$.]*$");

    private final SemanticOntologyService ontologyService;
    private final CatalogDatasetMapper datasetMapper;
    private final CatalogColumnMapper columnMapper;
    private final ObjectMapper json;

    public SemanticQueryCompiler(SemanticOntologyService ontologyService,
                                 CatalogDatasetMapper datasetMapper,
                                 CatalogColumnMapper columnMapper,
                                 ObjectMapper json) {
        this.ontologyService = ontologyService;
        this.datasetMapper = datasetMapper;
        this.columnMapper = columnMapper;
        this.json = json;
    }

    // ── Spec & Result Records ────────────────────────────────────────────

    public record FilterClause(String objectCode, String op, List<String> values) {
    }

    public record TimeRange(String dimensionCode, String start, String end) {
    }

    public record OrderClause(String objectCode, String dir) {
    }

    public record SemanticQuerySpec(
            String semanticViewId,
            List<String> metrics,
            List<String> dimensions,
            List<FilterClause> filters,
            TimeRange timeRange,
            List<OrderClause> orderBy,
            Integer limit
    ) {
    }

    public record CompileContext(
            String workspaceId,
            Set<String> allowedDatasetIds,
            String dialect, // STARROCKS / MYSQL / POSTGRESQL
            int maxRows
    ) {
    }

    public record CompiledColumn(String name, String bizName, String role) {
    }

    public record CompiledSemanticQuery(
            String sql,
            String dialect,
            String primaryDatasetId,
            List<String> involvedDatasetIds,
            List<CompiledColumn> columns,
            List<String> semanticSnapshotRefs,
            String auditFingerprint,
            List<String> appliedExclusions,
            boolean fanoutCteUsed,
            List<String> warnings
    ) {
    }

    // ── Main Compile Entry ───────────────────────────────────────────────

    public CompiledSemanticQuery compile(SemanticQuerySpec spec, CompileContext ctx) {
        if (spec == null || ctx == null || ctx.workspaceId() == null || ctx.workspaceId().isBlank()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED, "编译请求与 workspaceId 不能为空");
        }
        List<String> metricCodes = spec.metrics() == null ? List.of() : spec.metrics();
        List<String> dimCodes = spec.dimensions() == null ? List.of() : spec.dimensions();
        if (metricCodes.isEmpty() && dimCodes.isEmpty()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED, "至少需要指定一个已发布指标或维度");
        }

        String ws = ctx.workspaceId();
        String dialect = ctx.dialect() == null || ctx.dialect().isBlank()
                ? "STARROCKS" : ctx.dialect().trim().toUpperCase(Locale.ROOT);
        int maxRows = Math.max(1, Math.min(2000, ctx.maxRows() <= 0 ? 200 : ctx.maxRows()));
        int effectiveLimit = spec.limit() == null ? maxRows : Math.max(1, Math.min(maxRows, spec.limit()));

        // 加载已发布语义快照
        List<SemanticObject> publishedObjects = ontologyService.listPublishedObjectsInternal(ws);
        Map<String, SemanticObject> byCode = publishedObjects.stream()
                .collect(Collectors.toMap(o -> o.getCode().toLowerCase(Locale.ROOT), Function.identity(), (a, b) -> a));
        Map<String, SemanticObject> byId = publishedObjects.stream()
                .collect(Collectors.toMap(SemanticObject::getId, Function.identity(), (a, b) -> a));
        Map<String, List<SemanticMapping>> mappingsByObj = ontologyService.listWorkspaceMappingsInternal(ws)
                .stream()
                .collect(Collectors.groupingBy(SemanticMapping::getObjectId));
        List<SemanticLink> publishedLinks = ontologyService.listPublishedLinksInternal(ws);

        // 1. 解析并校验所有指标、维度与过滤对象
        List<SemanticObject> metricObjs = new ArrayList<>();
        Set<String> allReferencedObjectIds = new LinkedHashSet<>();
        for (String code : metricCodes) {
            SemanticObject m = requirePublished(byCode, code, "METRIC");
            // C2 闸门：指标必须具备 standardRef
            if (m.getStandardRef() == null || m.getStandardRef().isBlank()) {
                throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED,
                        "指标 " + m.getCode() + " 缺少 Standard 口径绑定（CONSTITUTION C2），拒绝编译");
            }
            metricObjs.add(m);
            allReferencedObjectIds.add(m.getId());
        }

        List<SemanticObject> dimObjs = new ArrayList<>();
        for (String code : dimCodes) {
            SemanticObject d = requirePublished(byCode, code, null);
            dimObjs.add(d);
            allReferencedObjectIds.add(d.getId());
        }

        // 粒度/允许维度校验
        for (SemanticObject m : metricObjs) {
            Map<String, Object> mSpec = readJsonMap(m.getSpecJson());
            if (mSpec.get("allowedDimensions") instanceof List<?> allowed && !allowed.isEmpty()) {
                Set<String> allowedSet = allowed.stream()
                        .map(v -> String.valueOf(v).toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
                for (SemanticObject d : dimObjs) {
                    if (!allowedSet.contains(d.getCode().toLowerCase(Locale.ROOT))
                            && !allowedSet.contains(d.getId().toLowerCase(Locale.ROOT))) {
                        throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED,
                                "维度 " + d.getCode() + " 不在指标 " + m.getCode() + " 允许的分析粒度范围内");
                    }
                }
            }
        }

        // 展开复合衍生指标依赖的底层基础指标
        List<ResolvedMetric> resolvedMetrics = new ArrayList<>();
        Set<String> requiredDatasetIds = new LinkedHashSet<>();
        Set<String> requiredColumnIds = new LinkedHashSet<>();
        List<String> appliedExclusions = new ArrayList<>();
        List<FilterClause> effectiveFilters = new ArrayList<>(spec.filters() == null ? List.of() : spec.filters());

        for (SemanticObject m : metricObjs) {
            ResolvedMetric rm = resolveMetric(m, byCode, byId, mappingsByObj, publishedLinks,
                    effectiveFilters, appliedExclusions, allReferencedObjectIds);
            resolvedMetrics.add(rm);
            for (BaseMetricPart part : rm.baseParts()) {
                requiredDatasetIds.add(part.mapping().getCatalogDatasetId());
                if (part.mapping().getCatalogColumnId() != null) {
                    requiredColumnIds.add(part.mapping().getCatalogColumnId());
                }
            }
        }

        // 收集维度、过滤与时间窗涉及的映射
        Map<String, SemanticMapping> chosenDimMappings = new LinkedHashMap<>();
        Set<String> factDatasetIds = resolvedMetrics.stream()
                .flatMap(rm -> rm.baseParts().stream())
                .map(p -> p.mapping().getCatalogDatasetId())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (SemanticObject d : dimObjs) {
            SemanticMapping m = chooseMapping(d, mappingsByObj.getOrDefault(d.getId(), List.of()), factDatasetIds);
            chosenDimMappings.put(d.getCode(), m);
            requiredDatasetIds.add(m.getCatalogDatasetId());
            if (m.getCatalogColumnId() != null) {
                requiredColumnIds.add(m.getCatalogColumnId());
            }
        }

        List<ResolvedFilter> resolvedFilters = new ArrayList<>();
        for (FilterClause f : effectiveFilters) {
            SemanticObject fo = requirePublished(byCode, f.objectCode(), null);
            allReferencedObjectIds.add(fo.getId());
            SemanticMapping fm = chooseMapping(fo, mappingsByObj.getOrDefault(fo.getId(), List.of()), factDatasetIds);
            requiredDatasetIds.add(fm.getCatalogDatasetId());
            if (fm.getCatalogColumnId() != null) {
                requiredColumnIds.add(fm.getCatalogColumnId());
            }
            resolvedFilters.add(new ResolvedFilter(fo, fm, normalizeOp(f.op()), f.values() == null ? List.of() : f.values()));
        }

        ResolvedTimeFilter resolvedTime = null;
        if (spec.timeRange() != null && spec.timeRange().dimensionCode() != null && !spec.timeRange().dimensionCode().isBlank()) {
            SemanticObject to = requirePublished(byCode, spec.timeRange().dimensionCode(), null);
            allReferencedObjectIds.add(to.getId());
            SemanticMapping tm = chooseMapping(to, mappingsByObj.getOrDefault(to.getId(), List.of()), factDatasetIds);
            requiredDatasetIds.add(tm.getCatalogDatasetId());
            if (tm.getCatalogColumnId() != null) {
                requiredColumnIds.add(tm.getCatalogColumnId());
            }
            resolvedTime = new ResolvedTimeFilter(to, tm, spec.timeRange().start(), spec.timeRange().end());
        }

        // 2. ACL 物理表权限闸门（C4 / C5）
        if (ctx.allowedDatasetIds() != null) {
            for (String dsId : requiredDatasetIds) {
                if (!ctx.allowedDatasetIds().contains(dsId)) {
                    throw new BizException(ErrorCode.PLT_FORBIDDEN,
                            "当前用户无权访问语义查询涉及的物理数据集: " + dsId);
                }
            }
        }

        // 加载涉及的物理表名与列名
        Map<String, CatalogDataset> datasets = requiredDatasetIds.isEmpty() ? Map.of()
                : datasetMapper.selectList(new LambdaQueryWrapper<CatalogDataset>()
                        .eq(CatalogDataset::getWorkspaceId, ws)
                        .in(CatalogDataset::getId, requiredDatasetIds))
                .stream().collect(Collectors.toMap(CatalogDataset::getId, Function.identity(), (a, b) -> a));

        Map<String, CatalogColumn> columns = requiredColumnIds.isEmpty() ? Map.of()
                : columnMapper.selectList(new LambdaQueryWrapper<CatalogColumn>()
                        .in(CatalogColumn::getId, requiredColumnIds))
                .stream().collect(Collectors.toMap(CatalogColumn::getId, Function.identity(), (a, b) -> a));

        // 3. 判断单事实表直出 vs 多事实表防扇出 CTE 编译
        boolean useFanoutCte = factDatasetIds.size() >= 2;
        List<String> warnings = new ArrayList<>();
        String rawSql;
        List<CompiledColumn> outputColumns = new ArrayList<>();

        if (useFanoutCte) {
            rawSql = compileMultiFactCte(
                    dimObjs, resolvedMetrics, factDatasetIds, mappingsByObj,
                    resolvedFilters, resolvedTime, spec.orderBy(), effectiveLimit,
                    datasets, columns, dialect, outputColumns, warnings);
        } else {
            String baseDatasetId = factDatasetIds.isEmpty()
                    ? requiredDatasetIds.iterator().next()
                    : factDatasetIds.iterator().next();
            rawSql = compileSingleFactOrStarJoin(
                    baseDatasetId, dimObjs, chosenDimMappings, resolvedMetrics,
                    resolvedFilters, resolvedTime, spec.orderBy(), effectiveLimit,
                    publishedLinks, mappingsByObj, datasets, columns, dialect, outputColumns, warnings);
        }

        // 4. 强制经 dg-data-spi 只读 SQL 护栏校验（C3）
        String safeSql = ReadOnlySqlGuard.requireReadOnly(rawSql);

        // 5. 生成不可变语义版本快照引用与审计指纹（C8）
        List<String> snapshotRefs = new ArrayList<>();
        for (String oid : allReferencedObjectIds) {
            SemanticObject o = byId.get(oid);
            if (o != null) {
                String dgst = o.getContentDigest() == null ? "none"
                        : o.getContentDigest().substring(0, Math.min(12, o.getContentDigest().length()));
                snapshotRefs.add(o.getCode() + "@v" + (o.getVersionNo() == null ? 1 : o.getVersionNo()) + "#" + dgst);
            }
        }
        String fingerprint = SemanticOntologyService.sha256(
                ws + "|" + dialect + "|" + String.join(",", snapshotRefs) + "|" + safeSql);

        String primaryDatasetId = factDatasetIds.isEmpty()
                ? (requiredDatasetIds.isEmpty() ? null : requiredDatasetIds.iterator().next())
                : factDatasetIds.iterator().next();

        return new CompiledSemanticQuery(
                safeSql,
                dialect,
                primaryDatasetId,
                new ArrayList<>(requiredDatasetIds),
                outputColumns,
                snapshotRefs,
                fingerprint,
                appliedExclusions,
                useFanoutCte,
                warnings);
    }

    // ── Single-Fact / Star-Join Compilation ──────────────────────────────

    private String compileSingleFactOrStarJoin(
            String baseDatasetId,
            List<SemanticObject> dimObjs,
            Map<String, SemanticMapping> dimMappings,
            List<ResolvedMetric> resolvedMetrics,
            List<ResolvedFilter> filters,
            ResolvedTimeFilter timeFilter,
            List<OrderClause> orderBy,
            int limit,
            List<SemanticLink> links,
            Map<String, List<SemanticMapping>> mappingsByObj,
            Map<String, CatalogDataset> datasets,
            Map<String, CatalogColumn> columns,
            String dialect,
            List<CompiledColumn> outputColumns,
            List<String> warnings) {

        Map<String, String> tableAliases = new LinkedHashMap<>();
        tableAliases.put(baseDatasetId, "t0");

        // 规划需要的关联表
        Set<String> extraDatasets = new LinkedHashSet<>();
        for (SemanticMapping m : dimMappings.values()) {
            if (!baseDatasetId.equals(m.getCatalogDatasetId())) {
                extraDatasets.add(m.getCatalogDatasetId());
            }
        }
        for (ResolvedFilter f : filters) {
            if (!baseDatasetId.equals(f.mapping().getCatalogDatasetId())) {
                extraDatasets.add(f.mapping().getCatalogDatasetId());
            }
        }
        if (timeFilter != null && !baseDatasetId.equals(timeFilter.mapping().getCatalogDatasetId())) {
            extraDatasets.add(timeFilter.mapping().getCatalogDatasetId());
        }

        List<String> joinClauses = new ArrayList<>();
        int aliasIdx = 1;
        for (String targetDs : extraDatasets) {
            String targetAlias = "t" + (aliasIdx++);
            tableAliases.put(targetDs, targetAlias);
            JoinEdge join = findJoinBetweenDatasets(baseDatasetId, targetDs, links, mappingsByObj);
            if (join == null) {
                throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED,
                        "物理数据集 " + baseDatasetId + " 与 " + targetDs + " 之间缺少已发布的 STRUCTURAL 关联路径（md_semantic_link）");
            }
            joinClauses.add(join.joinType() + " JOIN " + tableName(targetDs, datasets) + " " + targetAlias
                    + " ON t0." + safeIdent(join.leftCol()) + " = " + targetAlias + "." + safeIdent(join.rightCol()));
        }

        List<String> selectExprs = new ArrayList<>();
        List<String> groupByExprs = new ArrayList<>();
        Map<String, String> aliasByCode = new HashMap<>();

        for (SemanticObject d : dimObjs) {
            SemanticMapping m = dimMappings.get(d.getCode());
            String expr = columnExpr(tableAliases.get(m.getCatalogDatasetId()), m, columns);
            String colAlias = sanitizeAlias(d.getCode());
            selectExprs.add(expr + " AS " + colAlias);
            groupByExprs.add(expr);
            aliasByCode.put(d.getCode().toLowerCase(Locale.ROOT), colAlias);
            outputColumns.add(new CompiledColumn(colAlias, d.getName(), "DIMENSION"));
        }

        for (ResolvedMetric rm : resolvedMetrics) {
            String metricExpr = renderMetricExpr(rm, tableAliases, columns, dialect);
            String colAlias = sanitizeAlias(rm.metric().getCode());
            selectExprs.add(metricExpr + " AS " + colAlias);
            aliasByCode.put(rm.metric().getCode().toLowerCase(Locale.ROOT), colAlias);
            outputColumns.add(new CompiledColumn(colAlias, rm.metric().getName(), "METRIC"));
        }

        List<String> whereParts = buildWhereParts(filters, timeFilter, tableAliases, columns);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(String.join(", ", selectExprs))
                .append(" FROM ").append(tableName(baseDatasetId, datasets)).append(" t0");
        for (String jc : joinClauses) {
            sql.append(" ").append(jc);
        }
        if (!whereParts.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereParts));
        }
        if (!groupByExprs.isEmpty() && !resolvedMetrics.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", groupByExprs));
        }
        appendOrderAndLimit(sql, orderBy, aliasByCode, limit);
        return sql.toString();
    }

    // ── Multi-Fact Fan-out / Chasm Trap Protection CTE Compilation ───────

    private String compileMultiFactCte(
            List<SemanticObject> dimObjs,
            List<ResolvedMetric> resolvedMetrics,
            Set<String> factDatasetIds,
            Map<String, List<SemanticMapping>> mappingsByObj,
            List<ResolvedFilter> filters,
            ResolvedTimeFilter timeFilter,
            List<OrderClause> orderBy,
            int limit,
            Map<String, CatalogDataset> datasets,
            Map<String, CatalogColumn> columns,
            String dialect,
            List<CompiledColumn> outputColumns,
            List<String> warnings) {

        warnings.add("检测到跨多事实表指标组合，已自动启用防扇出 CTE 预聚合改写（Fan-out Protection）");

        List<String> dsList = new ArrayList<>(factDatasetIds);
        List<String> cteSqls = new ArrayList<>();
        Map<String, String> cteNameByDs = new LinkedHashMap<>();
        Map<String, String> basePartAliasById = new HashMap<>();

        // 按事实表分组基础指标
        Map<String, List<BaseMetricPart>> partsByDs = new LinkedHashMap<>();
        for (String ds : dsList) {
            partsByDs.put(ds, new ArrayList<>());
        }
        int partIdx = 0;
        for (ResolvedMetric rm : resolvedMetrics) {
            for (BaseMetricPart p : rm.baseParts()) {
                String pAlias = "m_" + (partIdx++);
                basePartAliasById.put(p.key(), pAlias);
                partsByDs.computeIfAbsent(p.mapping().getCatalogDatasetId(), k -> new ArrayList<>()).add(p);
            }
        }

        for (int i = 0; i < dsList.size(); i++) {
            String dsId = dsList.get(i);
            String cteName = "cte_fact_" + i;
            cteNameByDs.put(dsId, cteName);

            List<String> sel = new ArrayList<>();
            List<String> grp = new ArrayList<>();
            for (SemanticObject d : dimObjs) {
                SemanticMapping dm = findMappingOnDataset(d, mappingsByObj.getOrDefault(d.getId(), List.of()), dsId);
                String expr = columnExpr("f", dm, columns);
                String dAlias = sanitizeAlias(d.getCode());
                sel.add(expr + " AS " + dAlias);
                grp.add(expr);
            }
            for (BaseMetricPart p : partsByDs.getOrDefault(dsId, List.of())) {
                String agg = renderAggregation(p.aggregation(), columnExpr("f", p.mapping(), columns));
                sel.add(agg + " AS " + basePartAliasById.get(p.key()));
            }

            // 仅下推映射到当前事实表的过滤条件
            List<String> localWhere = new ArrayList<>();
            for (ResolvedFilter rf : filters) {
                if (dsId.equals(rf.mapping().getCatalogDatasetId())) {
                    localWhere.add(renderFilter(columnExpr("f", rf.mapping(), columns), rf.op(), rf.values()));
                }
            }
            if (timeFilter != null && dsId.equals(timeFilter.mapping().getCatalogDatasetId())) {
                localWhere.addAll(renderTimeFilter(columnExpr("f", timeFilter.mapping(), columns), timeFilter));
            }

            StringBuilder cte = new StringBuilder();
            cte.append(cteName).append(" AS (SELECT ").append(String.join(", ", sel))
                    .append(" FROM ").append(tableName(dsId, datasets)).append(" f");
            if (!localWhere.isEmpty()) {
                cte.append(" WHERE ").append(String.join(" AND ", localWhere));
            }
            if (!grp.isEmpty()) {
                cte.append(" GROUP BY ").append(String.join(", ", grp));
            }
            cte.append(")");
            cteSqls.add(cte.toString());
        }

        // 汇总外层 SELECT
        String firstCte = cteNameByDs.get(dsList.get(0));
        List<String> outerSelect = new ArrayList<>();
        Map<String, String> aliasByCode = new HashMap<>();

        for (SemanticObject d : dimObjs) {
            String dAlias = sanitizeAlias(d.getCode());
            outerSelect.add(firstCte + "." + dAlias + " AS " + dAlias);
            aliasByCode.put(d.getCode().toLowerCase(Locale.ROOT), dAlias);
            outputColumns.add(new CompiledColumn(dAlias, d.getName(), "DIMENSION"));
        }

        for (ResolvedMetric rm : resolvedMetrics) {
            String colAlias = sanitizeAlias(rm.metric().getCode());
            String expr;
            if (rm.derivedRatio()) {
                BaseMetricPart num = rm.baseParts().get(0);
                BaseMetricPart den = rm.baseParts().get(1);
                String numRef = cteNameByDs.get(num.mapping().getCatalogDatasetId()) + "." + basePartAliasById.get(num.key());
                String denRef = cteNameByDs.get(den.mapping().getCatalogDatasetId()) + "." + basePartAliasById.get(den.key());
                expr = safeRatioExpr(numRef, denRef, dialect);
            } else {
                BaseMetricPart single = rm.baseParts().get(0);
                expr = cteNameByDs.get(single.mapping().getCatalogDatasetId()) + "." + basePartAliasById.get(single.key());
            }
            outerSelect.add(expr + " AS " + colAlias);
            aliasByCode.put(rm.metric().getCode().toLowerCase(Locale.ROOT), colAlias);
            outputColumns.add(new CompiledColumn(colAlias, rm.metric().getName(), "METRIC"));
        }

        StringBuilder sql = new StringBuilder();
        sql.append("WITH ").append(String.join(", ", cteSqls))
                .append(" SELECT ").append(String.join(", ", outerSelect))
                .append(" FROM ").append(firstCte);
        for (int i = 1; i < dsList.size(); i++) {
            String nextCte = cteNameByDs.get(dsList.get(i));
            if (dimObjs.isEmpty()) {
                sql.append(" CROSS JOIN ").append(nextCte);
            } else {
                List<String> onParts = dimObjs.stream()
                        .map(d -> {
                            String a = sanitizeAlias(d.getCode());
                            return firstCte + "." + a + " = " + nextCte + "." + a;
                        }).toList();
                sql.append(" LEFT JOIN ").append(nextCte).append(" ON ").append(String.join(" AND ", onParts));
            }
        }
        appendOrderAndLimit(sql, orderBy, aliasByCode, limit);
        return sql.toString();
    }

    // ── Metric & Join Helpers ────────────────────────────────────────────

    private record BaseMetricPart(String key, SemanticObject object, String aggregation, SemanticMapping mapping) {
    }

    private record ResolvedMetric(SemanticObject metric, boolean derivedRatio, List<BaseMetricPart> baseParts) {
    }

    private record ResolvedFilter(SemanticObject object, SemanticMapping mapping, String op, List<String> values) {
    }

    private record ResolvedTimeFilter(SemanticObject object, SemanticMapping mapping, String start, String end) {
    }

    private record JoinEdge(String leftCol, String rightCol, String joinType) {
    }

    private ResolvedMetric resolveMetric(
            SemanticObject metric,
            Map<String, SemanticObject> byCode,
            Map<String, SemanticObject> byId,
            Map<String, List<SemanticMapping>> mappingsByObj,
            List<SemanticLink> publishedLinks,
            List<FilterClause> effectiveFilters,
            List<String> appliedExclusions,
            Set<String> allReferencedObjectIds) {

        Map<String, Object> spec = readJsonMap(metric.getSpecJson());

        // 自动注入 METRIC.defaultFilters
        if (spec.get("defaultFilters") instanceof List<?> dfs) {
            for (Object item : dfs) {
                if (item instanceof Map<?, ?> fm) {
                    String oc = stringOrNull(fm.get("objectCode"));
                    if (oc == null && fm.get("objectId") instanceof String oid && byId.containsKey(oid)) {
                        oc = byId.get(oid).getCode();
                    }
                    String op = stringOrNull(fm.get("op"));
                    List<String> vals = toStringList(fm.get("values"));
                    if (oc != null && op != null) {
                        effectiveFilters.add(new FilterClause(oc, op, vals));
                        appliedExclusions.add("defaultFilter:" + oc + " " + op + " " + vals);
                    }
                }
            }
        }

        // 自动注入知识图谱 EXCLUDES / CONSTRAINS 边携带的结构化过滤（joinSpecJson 可存过滤条件）
        for (SemanticLink link : publishedLinks) {
            if ("EXCLUDES".equals(link.getLinkType()) && metric.getId().equals(link.getToObjectId())) {
                SemanticObject sourceObj = byId.get(link.getFromObjectId());
                String label = sourceObj == null ? link.getFromObjectId() : sourceObj.getName();
                appliedExclusions.add("KG_EXCLUDES:" + label + (link.getRationale() == null ? "" : " (" + link.getRationale() + ")"));
                if (link.getJoinSpecJson() != null && !link.getJoinSpecJson().isBlank()) {
                    for (Map<String, Object> cond : readJsonList(link.getJoinSpecJson())) {
                        String oc = stringOrNull(cond.get("objectCode"));
                        String op = stringOrNull(cond.get("op"));
                        List<String> vals = toStringList(cond.get("values"));
                        if (oc != null && op != null) {
                            effectiveFilters.add(new FilterClause(oc, op, vals));
                        }
                    }
                }
            }
        }

        String metricKind = String.valueOf(spec.getOrDefault("metricKind", "BASE")).toUpperCase(Locale.ROOT);
        String agg = String.valueOf(spec.getOrDefault("aggregation", "SUM")).toUpperCase(Locale.ROOT);

        if ("DERIVED".equals(metricKind) || "RATIO".equals(agg)) {
            SemanticObject numObj = findRef(spec, "numeratorObjectCode", "numeratorObjectId", byCode, byId);
            SemanticObject denObj = findRef(spec, "denominatorObjectCode", "denominatorObjectId", byCode, byId);
            if (numObj == null || denObj == null) {
                throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED,
                        "衍生比率指标 " + metric.getCode() + " 缺少已发布的分子或分母指标引用");
            }
            allReferencedObjectIds.add(numObj.getId());
            allReferencedObjectIds.add(denObj.getId());
            BaseMetricPart numPart = resolveBasePart(numObj, mappingsByObj, "num_" + metric.getId());
            BaseMetricPart denPart = resolveBasePart(denObj, mappingsByObj, "den_" + metric.getId());
            return new ResolvedMetric(metric, true, List.of(numPart, denPart));
        }

        BaseMetricPart base = resolveBasePart(metric, mappingsByObj, "base_" + metric.getId());
        return new ResolvedMetric(metric, false, List.of(base));
    }

    private BaseMetricPart resolveBasePart(SemanticObject obj, Map<String, List<SemanticMapping>> mappingsByObj, String key) {
        Map<String, Object> spec = readJsonMap(obj.getSpecJson());
        String agg = String.valueOf(spec.getOrDefault("aggregation", "SUM")).toUpperCase(Locale.ROOT);
        if (!ALLOWED_AGGS.contains(agg)) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED,
                    "不支持的聚合函数 " + agg + "（对象: " + obj.getCode() + "）");
        }
        List<SemanticMapping> list = mappingsByObj.getOrDefault(obj.getId(), List.of());
        if (list.isEmpty()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED,
                    "指标 " + obj.getCode() + " 尚未绑定物理表列映射（md_semantic_mapping）");
        }
        SemanticMapping chosen = list.stream()
                .sorted(Comparator.comparingInt(m -> m.getPriority() == null ? 100 : m.getPriority()))
                .findFirst().orElseThrow();
        return new BaseMetricPart(key, obj, agg, chosen);
    }

    private SemanticObject findRef(Map<String, Object> spec, String codeKey, String idKey,
                                   Map<String, SemanticObject> byCode, Map<String, SemanticObject> byId) {
        String code = stringOrNull(spec.get(codeKey));
        if (code != null) {
            return byCode.get(code.toLowerCase(Locale.ROOT));
        }
        String id = stringOrNull(spec.get(idKey));
        if (id != null) {
            return byId.get(id);
        }
        return null;
    }

    private SemanticMapping chooseMapping(SemanticObject obj, List<SemanticMapping> candidates, Set<String> preferredDatasets) {
        if (candidates == null || candidates.isEmpty()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED,
                    "语义对象 " + obj.getCode() + " 缺少物理表列映射");
        }
        // 优先命中事实表上已有的映射，减少不必要的 JOIN
        for (SemanticMapping m : candidates) {
            if (preferredDatasets.contains(m.getCatalogDatasetId())) {
                return m;
            }
        }
        return candidates.stream()
                .sorted(Comparator
                        .comparing((SemanticMapping m) -> !Boolean.TRUE.equals(m.getIsLookupTable()))
                        .thenComparingInt(m -> m.getPriority() == null ? 100 : m.getPriority()))
                .findFirst().orElseThrow();
    }

    private SemanticMapping findMappingOnDataset(SemanticObject obj, List<SemanticMapping> candidates, String datasetId) {
        return candidates.stream()
                .filter(m -> datasetId.equals(m.getCatalogDatasetId()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED,
                        "多事实表 CTE 编译失败：共享维度 " + obj.getCode() + " 未在事实表 " + datasetId + " 上配置物理映射"));
    }

    private JoinEdge findJoinBetweenDatasets(String leftDsId, String rightDsId,
                                             List<SemanticLink> links,
                                             Map<String, List<SemanticMapping>> mappingsByObj) {
        // 先检查显式 STRUCTURAL 边的 joinSpecJson
        for (SemanticLink link : links) {
            if (!"STRUCTURAL".equals(link.getEdgeCategory()) || link.getJoinSpecJson() == null) {
                continue;
            }
            Set<String> fromDs = mappingsByObj.getOrDefault(link.getFromObjectId(), List.of())
                    .stream().map(SemanticMapping::getCatalogDatasetId).collect(Collectors.toSet());
            Set<String> toDs = mappingsByObj.getOrDefault(link.getToObjectId(), List.of())
                    .stream().map(SemanticMapping::getCatalogDatasetId).collect(Collectors.toSet());

            boolean direct = fromDs.contains(leftDsId) && toDs.contains(rightDsId);
            boolean reverse = fromDs.contains(rightDsId) && toDs.contains(leftDsId);
            if (!direct && !reverse) {
                continue;
            }
            List<Map<String, Object>> specs = readJsonList(link.getJoinSpecJson());
            if (!specs.isEmpty()) {
                Map<String, Object> first = specs.get(0);
                String lc = stringOrNull(first.get("leftColumn"));
                String rc = stringOrNull(first.get("rightColumn"));
                String jt = String.valueOf(first.getOrDefault("joinType", "LEFT")).toUpperCase(Locale.ROOT);
                if (!Set.of("LEFT", "INNER", "RIGHT").contains(jt)) {
                    jt = "LEFT";
                }
                if (lc != null && rc != null) {
                    return direct ? new JoinEdge(lc, rc, jt) : new JoinEdge(rc, lc, jt);
                }
            }
        }
        return null;
    }

    private String renderMetricExpr(ResolvedMetric rm, Map<String, String> tableAliases,
                                    Map<String, CatalogColumn> columns, String dialect) {
        if (rm.derivedRatio()) {
            BaseMetricPart num = rm.baseParts().get(0);
            BaseMetricPart den = rm.baseParts().get(1);
            String numAgg = renderAggregation(num.aggregation(),
                    columnExpr(tableAliases.get(num.mapping().getCatalogDatasetId()), num.mapping(), columns));
            String denAgg = renderAggregation(den.aggregation(),
                    columnExpr(tableAliases.get(den.mapping().getCatalogDatasetId()), den.mapping(), columns));
            return safeRatioExpr(numAgg, denAgg, dialect);
        }
        BaseMetricPart p = rm.baseParts().get(0);
        return renderAggregation(p.aggregation(),
                columnExpr(tableAliases.get(p.mapping().getCatalogDatasetId()), p.mapping(), columns));
    }

    private String safeRatioExpr(String numExpr, String denExpr, String dialect) {
        String castType = "POSTGRESQL".equals(dialect) ? "DOUBLE PRECISION" : "DOUBLE";
        return "CASE WHEN (" + denExpr + ") = 0 THEN NULL ELSE CAST(" + numExpr + " AS " + castType
                + ") / NULLIF(" + denExpr + ", 0) END";
    }

    private String renderAggregation(String agg, String colExpr) {
        return switch (agg) {
            case "COUNT_DISTINCT" -> "COUNT(DISTINCT " + colExpr + ")";
            case "SUM", "COUNT", "AVG", "MIN", "MAX" -> agg + "(" + colExpr + ")";
            default -> throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED, "不支持的聚合: " + agg);
        };
    }

    private List<String> buildWhereParts(List<ResolvedFilter> filters, ResolvedTimeFilter timeFilter,
                                         Map<String, String> tableAliases, Map<String, CatalogColumn> columns) {
        List<String> where = new ArrayList<>();
        for (ResolvedFilter f : filters) {
            String expr = columnExpr(tableAliases.get(f.mapping().getCatalogDatasetId()), f.mapping(), columns);
            where.add(renderFilter(expr, f.op(), f.values()));
        }
        if (timeFilter != null) {
            String expr = columnExpr(tableAliases.get(timeFilter.mapping().getCatalogDatasetId()), timeFilter.mapping(), columns);
            where.addAll(renderTimeFilter(expr, timeFilter));
        }
        return where;
    }

    private String renderFilter(String colExpr, String op, List<String> values) {
        if (values.isEmpty()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED, "过滤条件缺少取值");
        }
        return switch (op) {
            case "IN" -> colExpr + " IN (" + values.stream().map(this::quoteLiteral).collect(Collectors.joining(", ")) + ")";
            case "NOT_IN" -> colExpr + " NOT IN (" + values.stream().map(this::quoteLiteral).collect(Collectors.joining(", ")) + ")";
            default -> colExpr + " " + op + " " + quoteLiteral(values.get(0));
        };
    }

    private List<String> renderTimeFilter(String colExpr, ResolvedTimeFilter tf) {
        List<String> parts = new ArrayList<>();
        if (tf.start() != null && !tf.start().isBlank()) {
            parts.add(colExpr + " >= " + quoteLiteral(tf.start().trim()));
        }
        if (tf.end() != null && !tf.end().isBlank()) {
            parts.add(colExpr + " <= " + quoteLiteral(tf.end().trim()));
        }
        return parts;
    }

    private void appendOrderAndLimit(StringBuilder sql, List<OrderClause> orderBy,
                                     Map<String, String> aliasByCode, int limit) {
        if (orderBy != null && !orderBy.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (OrderClause o : orderBy) {
                String alias = aliasByCode.get(o.objectCode().toLowerCase(Locale.ROOT));
                if (alias != null) {
                    String dir = "ASC".equalsIgnoreCase(o.dir()) ? "ASC" : "DESC";
                    parts.add(alias + " " + dir);
                }
            }
            if (!parts.isEmpty()) {
                sql.append(" ORDER BY ").append(String.join(", ", parts));
            }
        }
        sql.append(" LIMIT ").append(limit);
    }

    private SemanticObject requirePublished(Map<String, SemanticObject> byCode, String code, String expectedType) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED, "语义对象编码不能为空");
        }
        SemanticObject obj = byCode.get(code.trim().toLowerCase(Locale.ROOT));
        if (obj == null) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED,
                    "未找到已发布的语义对象: " + code + "（请先在语义本体工作台发布）");
        }
        if (expectedType != null && !expectedType.equalsIgnoreCase(obj.getObjectType())) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED,
                    "对象 " + code + " 类型为 " + obj.getObjectType() + "，期望 " + expectedType);
        }
        return obj;
    }

    private String tableName(String datasetId, Map<String, CatalogDataset> datasets) {
        CatalogDataset ds = datasets.get(datasetId);
        String name = ds == null ? datasetId : (ds.getQualifiedName() != null && !ds.getQualifiedName().isBlank()
                ? ds.getQualifiedName() : ds.getName());
        return safeIdent(name);
    }

    private String columnExpr(String tableAlias, SemanticMapping mapping, Map<String, CatalogColumn> columns) {
        if (mapping.getExpressionSql() != null && !mapping.getExpressionSql().isBlank()) {
            return mapping.getExpressionSql().trim();
        }
        CatalogColumn col = mapping.getCatalogColumnId() == null ? null : columns.get(mapping.getCatalogColumnId());
        if (col == null || col.getName() == null || col.getName().isBlank()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED,
                    "映射 " + mapping.getId() + " 缺少物理列名或表达式");
        }
        return tableAlias + "." + safeIdent(col.getName());
    }

    private String safeIdent(String ident) {
        String s = ident == null ? "" : ident.trim();
        if (!SAFE_IDENT.matcher(s).matches()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED, "非法 SQL 标识符: " + ident);
        }
        return s;
    }

    private String sanitizeAlias(String code) {
        return code.replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(Locale.ROOT);
    }

    private String normalizeOp(String op) {
        String norm = op == null ? "=" : op.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_OPS.contains(norm)) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED, "不支持的过滤操作符: " + op);
        }
        return norm;
    }

    private String quoteLiteral(String val) {
        if (val == null) {
            return "NULL";
        }
        if (val.matches("^-?\\d+(?:\\.\\d+)?$")) {
            return val;
        }
        return "'" + val.replace("'", "''") + "'";
    }

    private String stringOrNull(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isBlank() ? null : s;
    }

    private List<String> toStringList(Object o) {
        if (o instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return o == null ? List.of() : List.of(String.valueOf(o));
    }

    private Map<String, Object> readJsonMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> readJsonList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
