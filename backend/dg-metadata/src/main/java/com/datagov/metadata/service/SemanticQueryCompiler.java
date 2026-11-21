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
import com.datagov.common.audit.ReproducibilityFingerprint;
import com.datagov.metadata.service.gate.CompileReject;
import com.datagov.metadata.service.gate.CompileRejectException;
import com.datagov.metadata.service.gate.NullAsZeroGuard;
import com.datagov.metadata.service.gate.SemanticCompileErrorCode;
import com.datagov.metadata.service.gate.SemanticExecutionMode;
import com.datagov.metadata.service.gate.SemanticGatePass;
import com.datagov.metadata.service.gate.SemanticGateRequest;
import com.datagov.metadata.service.gate.SemanticGateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final SemanticGateService semanticGate;
    private final NullAsZeroGuard nullAsZeroGuard = new NullAsZeroGuard();

    public SemanticQueryCompiler(SemanticOntologyService ontologyService,
                                 CatalogDatasetMapper datasetMapper,
                                 CatalogColumnMapper columnMapper,
                                 ObjectMapper json) {
        this(ontologyService, datasetMapper, columnMapper, json, new SemanticGateService(json));
    }

    /** Spring 注入入口：多构造器时必须显式 @Autowired，否则会回退无参构造并启动失败。 */
    @Autowired
    public SemanticQueryCompiler(SemanticOntologyService ontologyService,
                                 CatalogDatasetMapper datasetMapper,
                                 CatalogColumnMapper columnMapper,
                                 ObjectMapper json,
                                 SemanticGateService semanticGate) {
        this.ontologyService = ontologyService;
        this.datasetMapper = datasetMapper;
        this.columnMapper = columnMapper;
        this.json = json;
        this.semanticGate = semanticGate == null ? new SemanticGateService(json) : semanticGate;
    }

    // ── Spec & Result Records ────────────────────────────────────────────

    public record FilterClause(String objectCode, String op, List<String> values) {
    }

    public record TimeRange(String dimensionCode, String start, String end) {
    }

    public record OrderClause(String objectCode, String dir) {
    }

    /** SMQ at_modifiers（v0：USING 路径消歧 / SET_GRAIN） */
    public record AtModifier(String op, String rel, String grain) {
    }

    public record SemanticQuerySpec(
            String semanticViewId,
            List<String> metrics,
            List<String> dimensions,
            List<FilterClause> filters,
            TimeRange timeRange,
            List<OrderClause> orderBy,
            Integer limit,
            String grain,
            List<AtModifier> atModifiers
    ) {
        /** 兼容旧 7 参构造（无 grain / atModifiers） */
        public SemanticQuerySpec(String semanticViewId,
                                 List<String> metrics,
                                 List<String> dimensions,
                                 List<FilterClause> filters,
                                 TimeRange timeRange,
                                 List<OrderClause> orderBy,
                                 Integer limit) {
            this(semanticViewId, metrics, dimensions, filters, timeRange, orderBy, limit, null, List.of());
        }
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
            List<String> warnings,
            ReproducibilityFingerprint reproducibilityFingerprint
    ) {
        public CompiledSemanticQuery(String sql,
                                     String dialect,
                                     String primaryDatasetId,
                                     List<String> involvedDatasetIds,
                                     List<CompiledColumn> columns,
                                     List<String> semanticSnapshotRefs,
                                     String auditFingerprint,
                                     List<String> appliedExclusions,
                                     boolean fanoutCteUsed,
                                     List<String> warnings) {
            this(sql, dialect, primaryDatasetId, involvedDatasetIds, columns, semanticSnapshotRefs,
                    auditFingerprint, appliedExclusions, fanoutCteUsed, warnings, null);
        }
    }

    // ── Main Compile Entry ───────────────────────────────────────────────

    public CompiledSemanticQuery compile(SemanticQuerySpec spec, CompileContext ctx) {
        if (spec == null || ctx == null || ctx.workspaceId() == null || ctx.workspaceId().isBlank()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED, "编译请求与 workspaceId 不能为空");
        }
        List<String> metricCodes = spec.metrics() == null ? List.of() : spec.metrics();
        List<String> dimCodes = spec.dimensions() == null ? List.of() : spec.dimensions();

        String ws = ctx.workspaceId();
        String dialect = ctx.dialect() == null || ctx.dialect().isBlank()
                ? "STARROCKS" : ctx.dialect().trim().toUpperCase(Locale.ROOT);
        int maxRows = Math.max(1, Math.min(2000, ctx.maxRows() <= 0 ? 200 : ctx.maxRows()));

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

        rejectForbiddenAtModifiers(spec.atModifiers());

        // 1. 解析已发布指标/维度（Standard、允许维、时间窗、ACL 等统一走 SemanticGate）
        List<SemanticObject> metricObjs = new ArrayList<>();
        Set<String> allReferencedObjectIds = new LinkedHashSet<>();
        for (String code : metricCodes) {
            SemanticObject m = requirePublished(byCode, code, "METRIC");
            metricObjs.add(m);
            allReferencedObjectIds.add(m.getId());
        }

        List<SemanticObject> dimObjs = new ArrayList<>();
        for (String code : dimCodes) {
            SemanticObject d = requirePublished(byCode, code, null);
            dimObjs.add(d);
            allReferencedObjectIds.add(d.getId());
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

        // 2. 统一 SemanticGate（C2 Standard / 允许维 / 时间窗 / 数据集 ACL / 截断）
        Set<String> allowedDatasets = ctx.allowedDatasetIds() == null ? Set.of() : ctx.allowedDatasetIds();
        SemanticGatePass gatePass = semanticGate.evaluate(new SemanticGateRequest(
                ws,
                SemanticExecutionMode.SINGLE,
                spec,
                metricObjs,
                dimObjs,
                spec.grain(),
                allowedDatasets,
                maxRows,
                requiredDatasetIds));
        int effectiveLimit = gatePass.effectiveLimit();

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
        if (gatePass.truncated()) {
            warnings.add("请求 limit 超过门禁 maxRows，已截断为 " + effectiveLimit
                    + "（gateDigest=" + gatePass.gateDigest().substring(0, Math.min(12, gatePass.gateDigest().length())) + "）");
        }
        for (SemanticObject m : metricObjs) {
            warnSemiAdditiveAndLod(m, warnings);
        }
        List<SemanticObject> grainDims = applyLodGrain(dimObjs, resolvedMetrics, byCode, warnings);
        // LOD 改写后若引入新维，补齐映射选择
        Map<String, SemanticMapping> grainDimMappings = new LinkedHashMap<>(chosenDimMappings);
        for (SemanticObject d : grainDims) {
            if (!grainDimMappings.containsKey(d.getCode())) {
                SemanticMapping m = chooseMapping(d, mappingsByObj.getOrDefault(d.getId(), List.of()), factDatasetIds);
                grainDimMappings.put(d.getCode(), m);
                requiredDatasetIds.add(m.getCatalogDatasetId());
                if (m.getCatalogColumnId() != null) {
                    requiredColumnIds.add(m.getCatalogColumnId());
                }
            }
        }
        // LOD 可能新增数据集/列，刷新缓存
        if (requiredDatasetIds.size() > datasets.size()) {
            datasets = requiredDatasetIds.isEmpty() ? Map.of()
                    : datasetMapper.selectList(new LambdaQueryWrapper<CatalogDataset>()
                            .eq(CatalogDataset::getWorkspaceId, ws)
                            .in(CatalogDataset::getId, requiredDatasetIds))
                    .stream().collect(Collectors.toMap(CatalogDataset::getId, Function.identity(), (a, b) -> a));
        }
        if (requiredColumnIds.size() > columns.size()) {
            columns = requiredColumnIds.isEmpty() ? Map.of()
                    : columnMapper.selectList(new LambdaQueryWrapper<CatalogColumn>()
                            .in(CatalogColumn::getId, requiredColumnIds))
                    .stream().collect(Collectors.toMap(CatalogColumn::getId, Function.identity(), (a, b) -> a));
        }
        String rawSql;
        List<CompiledColumn> outputColumns = new ArrayList<>();

        if (useFanoutCte) {
            rawSql = compileMultiFactCte(
                    grainDims, resolvedMetrics, factDatasetIds, mappingsByObj,
                    resolvedFilters, resolvedTime, spec.orderBy(), effectiveLimit,
                    datasets, columns, dialect, outputColumns, warnings);
        } else {
            String baseDatasetId = factDatasetIds.isEmpty()
                    ? requiredDatasetIds.iterator().next()
                    : factDatasetIds.iterator().next();
            rawSql = compileSingleFactOrStarJoin(
                    baseDatasetId, grainDims, grainDimMappings, resolvedMetrics,
                    resolvedFilters, resolvedTime, spec.orderBy(), effectiveLimit,
                    publishedLinks, mappingsByObj, datasets, columns, dialect, outputColumns, warnings,
                    spec.atModifiers(), byId);
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

        List<Map<String, String>> semanticVersions = new ArrayList<>();
        List<Map<String, String>> standardVersions = new ArrayList<>();
        for (String oid : allReferencedObjectIds) {
            SemanticObject o = byId.get(oid);
            if (o == null) {
                continue;
            }
            semanticVersions.add(Map.of(
                    "code", o.getCode() == null ? "" : o.getCode(),
                    "versionNo", String.valueOf(o.getVersionNo() == null ? 1 : o.getVersionNo()),
                    "contentDigest", o.getContentDigest() == null ? "" : o.getContentDigest()));
            if (o.getStandardRef() != null && !o.getStandardRef().isBlank()) {
                standardVersions.add(Map.of("standardId", o.getStandardRef(), "version", "bound"));
            }
        }
        List<Map<String, String>> datasourceSnapshots = requiredDatasetIds.stream()
                .map(id -> Map.of("datasetId", id, "snapshotRef", "live"))
                .toList();
        ReproducibilityFingerprint repro = new ReproducibilityFingerprint(
                1,
                datasourceSnapshots,
                semanticVersions,
                standardVersions,
                List.of(Map.of("operatorId", "SemanticQueryCompiler", "version", "1", "digest", fingerprint)),
                fingerprint,
                gatePass.gateDigest(),
                List.of(),
                Map.of("truncated", gatePass.truncated(), "maxRows", effectiveLimit),
                java.time.Instant.now().toString());

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
                warnings,
                repro);
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
            List<String> warnings,
            List<AtModifier> atModifiers,
            Map<String, SemanticObject> byId) {

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
            JoinEdge join = findJoinBetweenDatasets(
                    baseDatasetId, targetDs, links, mappingsByObj, atModifiers, byId);
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
            String metricExpr = renderSemiAdditiveMetricExpr(
                    rm, tableAliases, columns, dialect, timeFilter, warnings);
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

    /**
     * 半加性：有 window_groupings + timeRange 时，用「窗口末态」聚合（PG: (ARRAY_AGG ORDER BY time DESC)[1]；
     * 其它方言回退普通聚合并告警）。
     */
    private String renderSemiAdditiveMetricExpr(ResolvedMetric rm,
                                                Map<String, String> tableAliases,
                                                Map<String, CatalogColumn> columns,
                                                String dialect,
                                                ResolvedTimeFilter timeFilter,
                                                List<String> warnings) {
        Map<String, Object> spec = readJsonMap(rm.metric().getSpecJson());
        Object add = spec.get("additivity");
        List<String> windows = List.of();
        if (add instanceof Map<?, ?> a && "semi_additive".equalsIgnoreCase(stringOrNull(a.get("kind")))) {
            windows = toStringList(a.get("window_groupings"));
        }
        if (windows.isEmpty()) {
            return renderMetricExpr(rm, tableAliases, columns, dialect);
        }
        if (timeFilter == null || rm.derivedRatio() || rm.baseParts().size() != 1) {
            warnings.add("SEMI_ADDITIVE_FALLBACK:" + rm.metric().getCode()
                    + " 需要单一基础度量 + timeRange 才能应用 window_groupings 末态取值");
            return renderMetricExpr(rm, tableAliases, columns, dialect);
        }
        BaseMetricPart p = rm.baseParts().get(0);
        boolean nullAsZero = readNullAsZero(rm.metric().getSpecJson());
        String col = columnExpr(tableAliases.get(p.mapping().getCatalogDatasetId()), p.mapping(), columns, nullAsZero);
        String timeExpr = columnExpr(tableAliases.get(timeFilter.mapping().getCatalogDatasetId()),
                timeFilter.mapping(), columns);
        // 末态快照：按当前 GROUP BY 粒度取 time 最新非空值（半加性余额类）
        return switch (dialect) {
            case "POSTGRESQL" -> "(ARRAY_AGG(" + col + " ORDER BY " + timeExpr
                    + " DESC) FILTER (WHERE " + col + " IS NOT NULL))[1]";
            case "STARROCKS" -> "max_by(" + col + ", " + timeExpr + ")";
            case "MYSQL" -> "SUBSTRING_INDEX(GROUP_CONCAT(" + col
                    + " ORDER BY " + timeExpr + " DESC SEPARATOR 0x1F), 0x1F, 1)";
            default -> {
                // DuckDB 等：max_by(value, time)
                yield "max_by(" + col + ", " + timeExpr + ")";
            }
        };
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
                // W3/SMQ：多事实缝合用 FULL OUTER + NULL 安全等值（防 NULL 维裂变）
                List<String> onParts = dimObjs.stream()
                        .map(d -> {
                            String a = sanitizeAlias(d.getCode());
                            return nullSafeEq(dialect, firstCte + "." + a, nextCte + "." + a);
                        }).toList();
                sql.append(" FULL OUTER JOIN ").append(nextCte)
                        .append(" ON ").append(String.join(" AND ", onParts));
            }
        }
        appendOrderAndLimit(sql, orderBy, aliasByCode, limit);
        return sql.toString();
    }

    // ── Metric & Join Helpers ────────────────────────────────────────────

    private void rejectForbiddenAtModifiers(List<AtModifier> modifiers) {
        if (modifiers == null || modifiers.isEmpty()) {
            return;
        }
        for (AtModifier m : modifiers) {
            if (m == null || m.op() == null) {
                continue;
            }
            String op = m.op().trim().toUpperCase(Locale.ROOT);
            if (op.contains("DISABLE") || op.contains("BYPASS") || op.equals("IGNORE_EXCLUDES")
                    || op.equals("IGNORE_RLS") || op.equals("SKIP_SECURITY")) {
                throw new CompileRejectException(CompileReject.of(
                        SemanticCompileErrorCode.SECURITY_FIELD_FORBIDDEN,
                        "at_modifiers 不可关闭 EXCLUDES/RLS/安全谓词: " + m.op(),
                        "$.at_modifiers",
                        List.of("USING", "SET_GRAIN"),
                        "删除该 modifier，仅使用 USING / SET_GRAIN"));
            }
            if (!Set.of("USING", "SET_GRAIN").contains(op)) {
                throw new CompileRejectException(CompileReject.of(
                        SemanticCompileErrorCode.SECURITY_FIELD_FORBIDDEN,
                        "不支持的 at_modifiers.op: " + m.op(),
                        "$.at_modifiers",
                        List.of("USING", "SET_GRAIN"),
                        "将 op 改为 USING 或 SET_GRAIN"));
            }
        }
    }

    private void warnSemiAdditiveAndLod(SemanticObject metric, List<String> warnings) {
        Map<String, Object> spec = readJsonMap(metric.getSpecJson());
        Object add = spec.get("additivity");
        if (add instanceof Map<?, ?> a) {
            String kind = stringOrNull(a.get("kind"));
            if (kind != null && "semi_additive".equalsIgnoreCase(kind)) {
                List<String> windows = toStringList(a.get("window_groupings"));
                if (windows.isEmpty()) {
                    warnings.add("SEMI_ADDITIVE_MISSING_WINDOW:" + metric.getCode()
                            + " 缺少 window_groupings，聚合可能沿非加性维错误累加");
                } else {
                    warnings.add("SEMI_ADDITIVE_WINDOW_APPLIED:" + metric.getCode()
                            + " window_groupings=" + windows
                            + " → 编译为 PARTITION 末态取值（ROW_NUMBER/DISTINCT ON）");
                }
            }
        }
        Object lod = spec.get("lod");
        if (lod instanceof Map<?, ?> l) {
            String type = stringOrNull(l.get("type"));
            if (type != null && !"NONE".equalsIgnoreCase(type) && !type.isBlank()) {
                warnings.add("LOD_GRAIN_REWRITE:" + metric.getCode()
                        + " lod.type=" + type + " dimensions=" + toStringList(l.get("dimensions")));
            }
        }
    }

    /** LOD FIXED/INCLUDE/EXCLUDE：改写参与 GROUP BY / SELECT 的维度集合。 */
    private List<SemanticObject> applyLodGrain(
            List<SemanticObject> dimObjs,
            List<ResolvedMetric> resolvedMetrics,
            Map<String, SemanticObject> byCode,
            List<String> warnings) {
        List<SemanticObject> working = new ArrayList<>(dimObjs);
        for (ResolvedMetric rm : resolvedMetrics) {
            Map<String, Object> spec = readJsonMap(rm.metric().getSpecJson());
            Object lod = spec.get("lod");
            if (!(lod instanceof Map<?, ?> l)) {
                continue;
            }
            String type = stringOrNull(l.get("type"));
            if (type == null || "NONE".equalsIgnoreCase(type) || type.isBlank()) {
                continue;
            }
            List<String> lodDims = toStringList(l.get("dimensions"));
            String upper = type.toUpperCase(Locale.ROOT);
            if ("FIXED".equals(upper)) {
                working = new ArrayList<>();
                for (String code : lodDims) {
                    SemanticObject o = byCode.get(code.toLowerCase(Locale.ROOT));
                    if (o != null) {
                        working.add(o);
                    } else {
                        warnings.add("LOD_FIXED_UNRESOLVED:" + rm.metric().getCode() + "->" + code);
                    }
                }
            } else if ("INCLUDE".equals(upper)) {
                Set<String> present = working.stream()
                        .map(o -> o.getCode() == null ? "" : o.getCode().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet());
                for (String code : lodDims) {
                    String key = code.toLowerCase(Locale.ROOT);
                    if (present.contains(key)) {
                        continue;
                    }
                    SemanticObject o = byCode.get(key);
                    if (o != null) {
                        working.add(o);
                        present.add(key);
                    } else {
                        warnings.add("LOD_INCLUDE_UNRESOLVED:" + rm.metric().getCode() + "->" + code);
                    }
                }
            } else if ("EXCLUDE".equals(upper)) {
                Set<String> drop = lodDims.stream()
                        .map(c -> c.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet());
                working = working.stream()
                        .filter(o -> o.getCode() == null || !drop.contains(o.getCode().toLowerCase(Locale.ROOT)))
                        .collect(Collectors.toCollection(ArrayList::new));
            }
        }
        return working;
    }

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
                                             Map<String, List<SemanticMapping>> mappingsByObj,
                                             List<AtModifier> atModifiers,
                                             Map<String, SemanticObject> byId) {
        record Candidate(String bridgeCode, JoinEdge edge) {
        }
        List<Candidate> candidates = new ArrayList<>();
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
            if (specs.isEmpty()) {
                continue;
            }
            Map<String, Object> first = specs.get(0);
            String lc = stringOrNull(first.get("leftColumn"));
            String rc = stringOrNull(first.get("rightColumn"));
            String jt = String.valueOf(first.getOrDefault("joinType", "LEFT")).toUpperCase(Locale.ROOT);
            if (!Set.of("LEFT", "INNER", "RIGHT").contains(jt)) {
                jt = "LEFT";
            }
            if (lc == null || rc == null) {
                continue;
            }
            String fromCode = byId == null || byId.get(link.getFromObjectId()) == null
                    ? link.getFromObjectId()
                    : byId.get(link.getFromObjectId()).getCode();
            String toCode = byId == null || byId.get(link.getToObjectId()) == null
                    ? link.getToObjectId()
                    : byId.get(link.getToObjectId()).getCode();
            String bridge = (fromCode == null ? "" : fromCode) + "|" + (toCode == null ? "" : toCode);
            JoinEdge edge = direct ? new JoinEdge(lc, rc, jt) : new JoinEdge(rc, lc, jt);
            candidates.add(new Candidate(bridge, edge));
            // 也登记端点 code 供 USING 匹配
            if (fromCode != null) {
                candidates.add(new Candidate(fromCode, edge));
            }
            if (toCode != null && !toCode.equals(fromCode)) {
                candidates.add(new Candidate(toCode, edge));
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        // 按边去重（同一 JoinEdge 可能因 bridge 登记重复）
        List<JoinEdge> uniqueEdges = candidates.stream()
                .map(Candidate::edge)
                .distinct()
                .toList();
        if (uniqueEdges.size() == 1) {
            return uniqueEdges.get(0);
        }

        List<String> usingRels = (atModifiers == null ? List.<AtModifier>of() : atModifiers).stream()
                .filter(m -> m != null && m.op() != null && "USING".equalsIgnoreCase(m.op().trim()))
                .map(AtModifier::rel)
                .filter(r -> r != null && !r.isBlank())
                .map(r -> r.trim().toLowerCase(Locale.ROOT))
                .toList();
        if (!usingRels.isEmpty()) {
            List<JoinEdge> matched = candidates.stream()
                    .filter(c -> c.bridgeCode() != null
                            && usingRels.contains(c.bridgeCode().toLowerCase(Locale.ROOT)))
                    .map(Candidate::edge)
                    .distinct()
                    .toList();
            if (matched.size() == 1) {
                return matched.get(0);
            }
        }

        List<String> allowed = candidates.stream()
                .map(Candidate::bridgeCode)
                .filter(Objects::nonNull)
                .filter(c -> !c.contains("|"))
                .distinct()
                .sorted()
                .toList();
        throw new CompileRejectException(CompileReject.of(
                SemanticCompileErrorCode.MULTI_FACT_AMBIGUOUS_PATH,
                "数据集 " + leftDsId + " 与 " + rightDsId + " 之间存在多条 STRUCTURAL 路径，请用 at_modifiers.USING 指定桥接实体/维",
                "$.at_modifiers",
                allowed,
                "在 at_modifiers 中加入 {\"op\":\"USING\",\"rel\":\"<候选 code>\"} 后重试 compile_and_validate",
                Map.of("leftDatasetId", leftDsId, "rightDatasetId", rightDsId, "pathCount", uniqueEdges.size())));
    }

    private String renderMetricExpr(ResolvedMetric rm, Map<String, String> tableAliases,
                                    Map<String, CatalogColumn> columns, String dialect) {
        boolean nullAsZero = readNullAsZero(rm.metric().getSpecJson());
        if (rm.derivedRatio()) {
            BaseMetricPart num = rm.baseParts().get(0);
            BaseMetricPart den = rm.baseParts().get(1);
            String numAgg = renderAggregation(num.aggregation(),
                    columnExpr(tableAliases.get(num.mapping().getCatalogDatasetId()), num.mapping(), columns, nullAsZero));
            String denAgg = renderAggregation(den.aggregation(),
                    columnExpr(tableAliases.get(den.mapping().getCatalogDatasetId()), den.mapping(), columns, nullAsZero));
            return safeRatioExpr(numAgg, denAgg, dialect);
        }
        BaseMetricPart p = rm.baseParts().get(0);
        return renderAggregation(p.aggregation(),
                columnExpr(tableAliases.get(p.mapping().getCatalogDatasetId()), p.mapping(), columns, nullAsZero));
    }

    private String safeRatioExpr(String numExpr, String denExpr, String dialect) {
        // 除零与空分母均返回 NULL，绝不 COALESCE 为 0（W3）
        String castType = "POSTGRESQL".equals(dialect) ? "DOUBLE PRECISION" : "DOUBLE";
        String ratio = "CAST(" + numExpr + " AS " + castType + ") / NULLIF(CAST(" + denExpr + " AS " + castType + "), 0)";
        nullAsZeroGuard.assertAllowed(ratio, false);
        return ratio;
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
        return columnExpr(tableAlias, mapping, columns, false);
    }

    private String columnExpr(String tableAlias, SemanticMapping mapping, Map<String, CatalogColumn> columns,
                              boolean nullAsZeroAllowed) {
        if (mapping.getExpressionSql() != null && !mapping.getExpressionSql().isBlank()) {
            String expr = mapping.getExpressionSql().trim();
            nullAsZeroGuard.assertAllowed(expr, nullAsZeroAllowed);
            return expr;
        }
        CatalogColumn col = mapping.getCatalogColumnId() == null ? null : columns.get(mapping.getCatalogColumnId());
        if (col == null || col.getName() == null || col.getName().isBlank()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED,
                    "映射 " + mapping.getId() + " 缺少物理列名或表达式");
        }
        return tableAlias + "." + safeIdent(col.getName());
    }

    private boolean readNullAsZero(String specJson) {
        Object v = readJsonMap(specJson).get("null_as_zero");
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
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

    /** NULL 安全等值：PG/StarRocks 用 IS NOT DISTINCT FROM；MySQL 映射 &lt;=&gt; */
    private String nullSafeEq(String dialect, String left, String right) {
        if ("MYSQL".equalsIgnoreCase(dialect)) {
            return left + " <=> " + right;
        }
        return left + " IS NOT DISTINCT FROM " + right;
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
