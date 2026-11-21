package com.datagov.app.insight;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.app.insight.ChartSpecRecommender.ChartSpec;
import com.datagov.common.api.PageResult;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.FederatedQueryGateway;
import com.datagov.data.spi.FederatedQueryGateway.DuckDbFederatedRequest;
import com.datagov.data.spi.FederatedQueryGateway.FederatedResult;
import com.datagov.data.spi.FederatedQueryGateway.FederatedTableInput;
import com.datagov.data.spi.query.SqlQuery;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogSource;
import com.datagov.metadata.entity.SemanticEntities.WorkspaceWarehouse;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.CatalogSourceMapper;
import com.datagov.metadata.service.DataQueryService;
import com.datagov.metadata.service.MultiWarehouseQueryCompiler;
import com.datagov.metadata.service.MultiWarehouseQueryCompiler.AggregateMergeColumn;
import com.datagov.metadata.service.MultiWarehouseQueryCompiler.ExecutionMode;
import com.datagov.metadata.service.MultiWarehouseQueryCompiler.FederatedJoinPlan;
import com.datagov.metadata.service.MultiWarehouseQueryCompiler.FederatedSourceExtract;
import com.datagov.metadata.service.MultiWarehouseQueryCompiler.MultiWarehousePlan;
import com.datagov.metadata.service.MultiWarehouseQueryCompiler.WarehouseSubQuery;
import com.datagov.metadata.service.SemanticOntologyService;
import com.datagov.metadata.service.SemanticWarehouseService;
import com.datagov.metadata.service.SemanticOntologyService.ObjectView;
import com.datagov.metadata.service.SemanticOntologyService.SemanticViewOption;
import com.datagov.metadata.service.SemanticOntologyService.SubgraphNode;
import com.datagov.metadata.service.SemanticOntologyService.SubgraphResult;
import com.datagov.metadata.service.SemanticOntologyService.SubgraphSearchRequest;
import com.datagov.metadata.service.SemanticQueryCompiler;
import com.datagov.metadata.service.SemanticQueryCompiler.CompileContext;
import com.datagov.metadata.service.SemanticQueryCompiler.CompiledColumn;
import com.datagov.metadata.service.SemanticQueryCompiler.CompiledSemanticQuery;
import com.datagov.metadata.service.SemanticQueryCompiler.FilterClause;
import com.datagov.metadata.service.SemanticQueryCompiler.OrderClause;
import com.datagov.metadata.service.SemanticQueryCompiler.SemanticQuerySpec;
import com.datagov.metadata.service.SemanticQueryCompiler.TimeRange;
import com.datagov.metadata.service.SemanticValueLinkingService;
import com.datagov.metadata.service.SemanticValueLinkingService.NormalizedTimeRange;
import com.datagov.metadata.service.SemanticValueLinkingService.ValueMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 原生语义问数引擎（完全替代 SuperSonic · OpenSpec: semantic-ontology-kernel）。
 *
 * <p>完整链路严格遵守 CONSTITUTION C2/C3/C5/C8：
 * <ol>
 *   <li>子图召回（{@link SemanticOntologyService#searchSubgraph}，逐跳 ACL）</li>
 *   <li>L1/L2 维度值对齐（{@link SemanticValueLinkingService#matchValues}）与确定性相对时间归一化</li>
 *   <li>构造结构化 {@link SemanticQuerySpec}（歧义返回 {@code CLARIFY_NEEDED}，无匹配指标返回 {@code INCONCLUSIVE}）</li>
 *   <li>{@link SemanticQueryCompiler} 确定性编译只读 SQL 与 SHA-256 审计指纹</li>
 *   <li>若底层资产挂载了可用 JDBC 数据源，经 {@link DataQueryService}（{@code DataAccessGateway}）执行只读查询</li>
 * </ol>
 */
@Component
public class NativeInsightEngine implements InsightQueryEngine {

    private static final Logger log = LoggerFactory.getLogger(NativeInsightEngine.class);

    private final SemanticOntologyService ontologyService;
    private final SemanticValueLinkingService valueLinkingService;
    private final SemanticQueryCompiler compiler;
    private final CatalogDatasetMapper datasetMapper;
    private final CatalogSourceMapper sourceMapper;
    private final DataQueryService dataQueryService;
    private final InsightProperties properties;
    private final ChartSpecRecommender chartRecommender;
    private final MultiWarehouseQueryCompiler multiWarehouseCompiler;
    private final SemanticWarehouseService warehouseService;
    private final FederatedQueryGateway federatedQueryGateway;

    @org.springframework.beans.factory.annotation.Autowired
    public NativeInsightEngine(SemanticOntologyService ontologyService,
                               SemanticValueLinkingService valueLinkingService,
                               SemanticQueryCompiler compiler,
                               CatalogDatasetMapper datasetMapper,
                               CatalogSourceMapper sourceMapper,
                               DataQueryService dataQueryService,
                               InsightProperties properties,
                               ChartSpecRecommender chartRecommender,
                               MultiWarehouseQueryCompiler multiWarehouseCompiler,
                               SemanticWarehouseService warehouseService,
                               FederatedQueryGateway federatedQueryGateway) {
        this.ontologyService = ontologyService;
        this.valueLinkingService = valueLinkingService;
        this.compiler = compiler;
        this.datasetMapper = datasetMapper;
        this.sourceMapper = sourceMapper;
        this.dataQueryService = dataQueryService;
        this.properties = properties;
        this.chartRecommender = chartRecommender;
        this.multiWarehouseCompiler = multiWarehouseCompiler;
        this.warehouseService = warehouseService;
        this.federatedQueryGateway = federatedQueryGateway;
    }

    /** 兼容既有测试构造器（8 参）。 */
    public NativeInsightEngine(SemanticOntologyService ontologyService,
                               SemanticValueLinkingService valueLinkingService,
                               SemanticQueryCompiler compiler,
                               CatalogDatasetMapper datasetMapper,
                               CatalogSourceMapper sourceMapper,
                               DataQueryService dataQueryService,
                               InsightProperties properties,
                               ChartSpecRecommender chartRecommender) {
        this(ontologyService, valueLinkingService, compiler, datasetMapper, sourceMapper,
                dataQueryService, properties, chartRecommender, null, null, null);
    }

    /** 兼容既有测试构造器（7 参）。 */
    public NativeInsightEngine(SemanticOntologyService ontologyService,
                               SemanticValueLinkingService valueLinkingService,
                               SemanticQueryCompiler compiler,
                               CatalogDatasetMapper datasetMapper,
                               CatalogSourceMapper sourceMapper,
                               DataQueryService dataQueryService,
                               InsightProperties properties) {
        this(ontologyService, valueLinkingService, compiler, datasetMapper, sourceMapper,
                dataQueryService, properties, new ChartSpecRecommender(), null, null, null);
    }

    @Override
    public String engineId() {
        return "native";
    }

    @Override
    public List<DatasetOption> datasets() {
        List<SemanticViewOption> views = ontologyService.listSemanticViews();
        List<DatasetOption> out = new ArrayList<>(views.size());
        long idx = 1L;
        for (SemanticViewOption v : views) {
            out.add(new DatasetOption(idx++, v.name(), v.domain(), v.id()));
        }
        return out;
    }

    public CompiledSemanticQuery compilePreview(SemanticQuerySpec spec) {
        String ws = WorkspaceContext.requireWorkspaceId();
        return compiler.compile(spec, new CompileContext(
                ws, null, properties.getDialect(), properties.getMaxRows()));
    }

    @Override
    public EngineAskResult ask(String question, Long datasetId, SemanticQuerySpec explicitSpec) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String queryId = Ids.of("nsq");

        SemanticQuerySpec spec = explicitSpec;
        List<ValueMatch> valueMatches = List.of();
        NormalizedTimeRange relTime = null;

        if (spec == null) {
            SubgraphResult subgraph = ontologyService.searchSubgraph(
                    new SubgraphSearchRequest(question, 2, 8, null, false));
            valueMatches = valueLinkingService.matchValues(ws, question);
            relTime = valueLinkingService.normalizeRelativeTime(question, LocalDate.now());

            List<SubgraphNode> metrics = subgraph.nodes().stream()
                    .filter(n -> "METRIC".equalsIgnoreCase(n.objectType()))
                    .toList();
            List<SubgraphNode> dims = subgraph.nodes().stream()
                    .filter(n -> "DIMENSION".equalsIgnoreCase(n.objectType())
                            || "ATTRIBUTE".equalsIgnoreCase(n.objectType()))
                    .toList();

            // C8：未命中任何已发布指标或维度，拒绝瞎猜，显式返回 INCONCLUSIVE
            if (metrics.isEmpty() && dims.isEmpty()) {
                return new EngineAskResult(
                        queryId, "INCONCLUSIVE", null, List.of(), List.of(),
                        "未在当前空间的已发布语义本体中匹配到对应指标或维度，拒绝猜测（CONSTITUTION C8）。请先在「语义本体」工作台发布指标口径或换一种问法。",
                        null, subgraph.snapshotRefs(), null, List.of(), List.of());
            }

            // 多义指标消歧：若召回 >= 2 个种子级（hop=0）冲突指标且问题未精确指定，返回 CLARIFY_NEEDED 供用户一键点选
            List<SubgraphNode> seedMetrics = metrics.stream()
                    .filter(m -> m.hopDistance() == 0)
                    .toList();
            if (seedMetrics.size() >= 2 && hasDisambiguationConflict(seedMetrics, question)) {
                List<ClarificationOption> options = seedMetrics.stream()
                        .map(m -> new ClarificationOption(
                                m.code(), m.name(),
                                String.valueOf(m.aiContext().getOrDefault("disambiguation", m.name()))))
                        .toList();
                return new EngineAskResult(
                        queryId, "CLARIFY_NEEDED", null, List.of(), List.of(),
                        "检测到多个相近口径的已发布指标，请选择期望口径后继续查询：",
                        null, subgraph.snapshotRefs(), null, List.of(), options);
            }

            List<String> chosenMetrics = metrics.stream().limit(2).map(SubgraphNode::code).toList();
            List<String> chosenDims = dims.stream()
                    .filter(d -> !isTimeDimension(d) || mentionsTrend(question))
                    .limit(2)
                    .map(SubgraphNode::code)
                    .toList();

            List<FilterClause> filters = valueMatches.stream()
                    .map(vm -> new FilterClause(vm.objectCode(), "=", List.of(vm.rawValue())))
                    .toList();

            TimeRange timeRange = null;
            if (relTime != null) {
                SubgraphNode timeDim = dims.stream().filter(this::isTimeDimension).findFirst().orElse(null);
                if (timeDim != null) {
                    timeRange = new TimeRange(timeDim.code(),
                            relTime.startDate().toString(), relTime.endDate().toString());
                }
            }

            List<OrderClause> orderBy = chosenMetrics.isEmpty()
                    ? List.of() : List.of(new OrderClause(chosenMetrics.get(0), "DESC"));

            spec = new SemanticQuerySpec(
                    datasetId == null ? "view_default" : "view_" + datasetId,
                    chosenMetrics, chosenDims, filters, timeRange, orderBy, properties.getMaxRows());
        }

        // 若当前空间配置了多仓库挂载，优先走多仓确定性编译与联邦编排（SINGLE_ROUTE / COHORT_UNION / ENTITY_FEDERATED_JOIN）
        if (multiWarehouseCompiler != null && warehouseService != null) {
            List<WorkspaceWarehouse> activeWarehouses = warehouseService.listActiveWarehousesInternal(ws);
            if (!activeWarehouses.isEmpty()) {
                return executeMultiWarehouse(
                        ws, queryId, question, spec, valueMatches, relTime);
            }
        }

        CompiledSemanticQuery compiled = compiler.compile(spec, new CompileContext(
                ws, null, properties.getDialect(), properties.getMaxRows()));

        List<InsightColumn> cols = compiled.columns().stream()
                .map(c -> new InsightColumn(c.bizName(), c.name(), "METRIC".equals(c.role()) ? "NUMBER" : "STRING"))
                .toList();

        List<ObjectView> publishedObjects = safePublishedObjects();

        // 若主物理表绑定了可用 JDBC 数据源，通过 DataQueryService（DataAccessGateway）执行只读查询
        String dataSourceId = resolveDataSourceId(ws, compiled.primaryDatasetId());
        if (dataSourceId != null) {
            try {
                SqlQuery.Result sqlResult = dataQueryService.execute(
                        dataSourceId,
                        new SqlQuery.Request(compiled.sql(), properties.getMaxRows(), properties.getTimeoutSeconds()));
                List<InsightColumn> execCols = sqlResult.columns().isEmpty() ? cols
                        : sqlResult.columns().stream()
                                .map(c -> new InsightColumn(
                                        bizLabel(c.name(), compiled.columns()),
                                        c.name(),
                                        c.canonicalType() != null ? c.canonicalType().name() : c.rawType()))
                                .toList();
                List<Map<String, Object>> mappedRows = new ArrayList<>(sqlResult.rows().size());
                for (List<String> rawRow : sqlResult.rows()) {
                    Map<String, Object> rowMap = new LinkedHashMap<>();
                    for (int i = 0; i < execCols.size(); i++) {
                        String key = execCols.get(i).bizName();
                        rowMap.put(key, i < rawRow.size() ? rawRow.get(i) : null);
                    }
                    mappedRows.add(rowMap);
                }
                ChartSpec chartSpec = chartRecommender.recommend(
                        "SUCCESS", question, execCols, mappedRows, spec, publishedObjects);
                String summary = buildSummary(compiled, sqlResult.rowCount(), valueMatches, relTime, true);
                return new EngineAskResult(
                        queryId, "SUCCESS", compiled.sql(), execCols, mappedRows,
                        summary, null, compiled.semanticSnapshotRefs(),
                        compiled.auditFingerprint(), compiled.appliedExclusions(), List.of(),
                        spec, chartSpec, null, null, List.of(), List.of(),
                        compiled.reproducibilityFingerprint());
            } catch (Exception ex) {
                log.warn("原生引擎执行数据源 {} 查询失败，回退返回确定性编译结果: {}", dataSourceId, ex.getMessage());
                ChartSpec chartSpec = chartRecommender.recommend(
                        "COMPILED_ONLY", question, cols, List.of(), spec, publishedObjects);
                return new EngineAskResult(
                        queryId, "COMPILED_ONLY", compiled.sql(), cols, List.of(),
                        "已完成确定性语义编译（数据源暂不可达：" + ex.getMessage() + "），可在预览区核验 SQL 与审计指纹。",
                        null, compiled.semanticSnapshotRefs(),
                        compiled.auditFingerprint(), compiled.appliedExclusions(), List.of(),
                        spec, chartSpec, null, null, List.of(), List.of(),
                        compiled.reproducibilityFingerprint());
            }
        }

        // 未绑定物理 JDBC 连接器（如纯元数据/离线目录模式）时，明确返回 COMPILED_ONLY（诚实汇报，不伪造数据）
        ChartSpec chartSpec = chartRecommender.recommend(
                "COMPILED_ONLY", question, cols, List.of(), spec, publishedObjects);
        String summary = buildSummary(compiled, 0, valueMatches, relTime, false);
        return new EngineAskResult(
                queryId, "COMPILED_ONLY", compiled.sql(), cols, List.of(),
                summary, null, compiled.semanticSnapshotRefs(),
                compiled.auditFingerprint(), compiled.appliedExclusions(), List.of(),
                spec, chartSpec, null, null, List.of(), List.of(),
                compiled.reproducibilityFingerprint());
    }

    // ── Multi-Warehouse & Federated Execution ───────────────────────────

    private EngineAskResult executeMultiWarehouse(String ws,
                                                  String queryId,
                                                  String question,
                                                  SemanticQuerySpec spec,
                                                  List<ValueMatch> valueMatches,
                                                  NormalizedTimeRange relTime) {
        MultiWarehousePlan plan = multiWarehouseCompiler.compileMultiWarehouse(
                spec, new CompileContext(ws, null, properties.getDialect(), properties.getMaxRows()));
        List<ObjectView> publishedObjects = safePublishedObjects();
        List<InsightColumn> planCols = plan.columns().stream()
                .map(c -> new InsightColumn(c.name(), c.bizName(), "METRIC".equals(c.role()) ? "NUMBER" : "STRING"))
                .toList();

        if (plan.mode() == ExecutionMode.COHORT_UNION) {
            return executeCohortUnion(queryId, question, spec, plan, planCols, publishedObjects);
        }
        if (plan.mode() == ExecutionMode.ENTITY_FEDERATED_JOIN) {
            return executeFederatedJoin(queryId, question, spec, plan, planCols, publishedObjects);
        }

        // SINGLE_ROUTE
        WarehouseSubQuery sub = plan.subQueries().get(0);
        List<WarehouseSqlPass> sqlPasses = List.of(new WarehouseSqlPass(
                sub.warehouseCode(), sub.warehouseName(), sub.cohortCode(), sub.dialect(), sub.sql()));
        List<String> whNames = List.of(sub.warehouseName());
        String dsId = sub.datasourceId() != null ? sub.datasourceId() : resolveDataSourceId(ws, sub.primaryDatasetId());
        if (dsId != null) {
            try {
                SqlQuery.Result sqlResult = dataQueryService.execute(
                        dsId, new SqlQuery.Request(sub.sql(), properties.getMaxRows(), properties.getTimeoutSeconds()));
                List<InsightColumn> execCols = toExecCols(sqlResult, plan.columns(), planCols);
                List<Map<String, Object>> rows = toRowMaps(sqlResult, execCols);
                ChartSpec chartSpec = chartRecommender.recommend(
                        "SUCCESS", question, execCols, rows, spec, publishedObjects);
                return new EngineAskResult(
                        queryId, "SUCCESS", sub.sql(), execCols, rows,
                        "单仓确定性路由（" + sub.warehouseName() + "）查询完成，返回 " + rows.size() + " 条结果。",
                        null, plan.semanticSnapshotRefs(), plan.auditFingerprint(),
                        plan.appliedExclusions(), List.of(), spec, chartSpec,
                        plan.mode().name(), plan.federationEngine(), whNames, sqlPasses,
                        plan.reproducibilityFingerprint());
            } catch (Exception ex) {
                log.warn("多仓单路执行失败 warehouse={}: {}", sub.warehouseCode(), ex.getMessage());
            }
        }
        ChartSpec chartSpec = chartRecommender.recommend(
                "COMPILED_ONLY", question, planCols, List.of(), spec, publishedObjects);
        return new EngineAskResult(
                queryId, "COMPILED_ONLY", sub.sql(), planCols, List.of(),
                "已完成单仓确定性语义编译（" + sub.warehouseName() + " 数据源暂不可达），可核验 SQL 与审计指纹。",
                null, plan.semanticSnapshotRefs(), plan.auditFingerprint(),
                plan.appliedExclusions(), List.of(), spec, chartSpec,
                plan.mode().name(), plan.federationEngine(), whNames, sqlPasses,
                        plan.reproducibilityFingerprint());
    }

    private EngineAskResult executeCohortUnion(String queryId,
                                               String question,
                                               SemanticQuerySpec spec,
                                               MultiWarehousePlan plan,
                                               List<InsightColumn> planCols,
                                               List<ObjectView> publishedObjects) {
        List<WarehouseSqlPass> sqlPasses = plan.subQueries().stream()
                .map(s -> new WarehouseSqlPass(s.warehouseCode(), s.warehouseName(), s.cohortCode(), s.dialect(), s.sql()))
                .toList();
        List<String> whNames = plan.subQueries().stream().map(WarehouseSubQuery::warehouseName).toList();
        String combinedSql = sqlPasses.stream()
                .map(p -> "-- [" + p.warehouseName() + " / " + p.dialect() + "]\n" + p.sql())
                .collect(java.util.stream.Collectors.joining("\n\n"));

        List<Map<String, Object>> allRawRows = new ArrayList<>();
        List<String> failedWarehouses = new ArrayList<>();
        for (WarehouseSubQuery sub : plan.subQueries()) {
            if (sub.datasourceId() == null) {
                failedWarehouses.add(sub.warehouseName() + "(未绑定数据源)");
                continue;
            }
            try {
                SqlQuery.Result res = dataQueryService.execute(
                        sub.datasourceId(),
                        new SqlQuery.Request(sub.sql(), properties.getMaxRows(), properties.getTimeoutSeconds()));
                List<InsightColumn> subCols = toExecCols(res, sub.columns(), List.of());
                allRawRows.addAll(toRowMaps(res, subCols));
            } catch (Exception ex) {
                log.warn("COHORT_UNION 分仓执行失败 warehouse={}: {}", sub.warehouseCode(), ex.getMessage());
                failedWarehouses.add(sub.warehouseName() + "(" + ex.getMessage() + ")");
            }
        }

        if (!failedWarehouses.isEmpty()) {
            // C8：任一队列仓库执行失败，绝不静默遗漏队列而冒充全量联合结果
            ChartSpec chartSpec = chartRecommender.recommend(
                    "COMPILED_ONLY", question, planCols, List.of(), spec, publishedObjects);
            return new EngineAskResult(
                    queryId, "COMPILED_ONLY", combinedSql, planCols, List.of(),
                    "多队列联合（COHORT_UNION）部分或全部仓库暂不可达（" + String.join("；", failedWarehouses)
                            + "），按 CONSTITUTION C8 拒绝拼装不完整结果，已输出各仓确定性 SQL 供核验。",
                    null, plan.semanticSnapshotRefs(), plan.auditFingerprint(),
                    plan.appliedExclusions(), List.of(), spec, chartSpec,
                    plan.mode().name(), plan.federationEngine(), whNames, sqlPasses,
                        plan.reproducibilityFingerprint());
        }

        List<Map<String, Object>> mergedRows = mergeCohortRows(allRawRows, plan.mergeColumns());
        ChartSpec chartSpec = chartRecommender.recommend(
                "SUCCESS", question, planCols, mergedRows, spec, publishedObjects);
        return new EngineAskResult(
                queryId, "SUCCESS", combinedSql, planCols, mergedRows,
                "已完成跨队列水平联合（" + String.join(" + ", whNames) + " · 两阶段确定性统计汇总），返回 "
                        + mergedRows.size() + " 条结果。",
                null, plan.semanticSnapshotRefs(), plan.auditFingerprint(),
                plan.appliedExclusions(), List.of(), spec, chartSpec,
                plan.mode().name(), plan.federationEngine(), whNames, sqlPasses,
                        plan.reproducibilityFingerprint());
    }

    private EngineAskResult executeFederatedJoin(String queryId,
                                                 String question,
                                                 SemanticQuerySpec spec,
                                                 MultiWarehousePlan plan,
                                                 List<InsightColumn> planCols,
                                                 List<ObjectView> publishedObjects) {
        FederatedJoinPlan fedPlan = plan.federatedJoinPlan();
        List<WarehouseSqlPass> sqlPasses = new ArrayList<>();
        List<String> whNames = new ArrayList<>();

        if ("STARROCKS".equalsIgnoreCase(fedPlan.engine())) {
            sqlPasses.add(new WarehouseSqlPass(
                    "starrocks_federation", "StarRocks External Catalog", "FEDERATED", "STARROCKS", fedPlan.federatedSql()));
            whNames.add("StarRocks External Catalog");
            try {
                SqlQuery.Result res = dataQueryService.execute(
                        fedPlan.starRocksDatasourceId(),
                        new SqlQuery.Request(fedPlan.federatedSql(), properties.getMaxRows(), properties.getTimeoutSeconds()));
                List<InsightColumn> execCols = toExecCols(res, plan.columns(), planCols);
                List<Map<String, Object>> rows = toRowMaps(res, execCols);
                ChartSpec chartSpec = chartRecommender.recommend(
                        "SUCCESS", question, execCols, rows, spec, publishedObjects);
                return new EngineAskResult(
                        queryId, "SUCCESS", fedPlan.federatedSql(), execCols, rows,
                        "已通过 StarRocks External Catalog 完成跨仓库任意联邦 JOIN，返回 " + rows.size() + " 条结果。",
                        null, plan.semanticSnapshotRefs(), plan.auditFingerprint(),
                        plan.appliedExclusions(), List.of(), spec, chartSpec,
                        plan.mode().name(), "STARROCKS", whNames, sqlPasses,
                        plan.reproducibilityFingerprint());
            } catch (Exception ex) {
                log.warn("StarRocks 联邦查询失败: {}", ex.getMessage());
                ChartSpec chartSpec = chartRecommender.recommend(
                        "COMPILED_ONLY", question, planCols, List.of(), spec, publishedObjects);
                return new EngineAskResult(
                        queryId, "COMPILED_ONLY", fedPlan.federatedSql(), planCols, List.of(),
                        "已完成 StarRocks 跨 Catalog 联邦编译（执行暂不可达：" + ex.getMessage() + "）。",
                        null, plan.semanticSnapshotRefs(), plan.auditFingerprint(),
                        plan.appliedExclusions(), List.of(), spec, chartSpec,
                        plan.mode().name(), "STARROCKS", whNames, sqlPasses,
                        plan.reproducibilityFingerprint());
            }
        }

        // DUCKDB 嵌入式联邦模式
        for (FederatedSourceExtract ext : fedPlan.extracts()) {
            whNames.add(ext.warehouseName());
            sqlPasses.add(new WarehouseSqlPass(
                    ext.warehouseCode(), ext.warehouseName() + "（提取）", ext.alias(), ext.dialect(), ext.extractSql()));
        }
        sqlPasses.add(new WarehouseSqlPass(
                "duckdb_federation", "DuckDB 嵌入式联邦引擎", "FEDERATED", "DUCKDB", fedPlan.federatedSql()));

        if (federatedQueryGateway == null) {
            ChartSpec chartSpec = chartRecommender.recommend(
                    "COMPILED_ONLY", question, planCols, List.of(), spec, publishedObjects);
            return new EngineAskResult(
                    queryId, "COMPILED_ONLY", fedPlan.federatedSql(), planCols, List.of(),
                    "已完成 DuckDB 联邦编译（未装配 FederatedQueryGateway）。",
                    null, plan.semanticSnapshotRefs(), plan.auditFingerprint(),
                    plan.appliedExclusions(), List.of(), spec, chartSpec,
                    plan.mode().name(), "DUCKDB", whNames, sqlPasses,
                        plan.reproducibilityFingerprint());
        }

        List<FederatedTableInput> inputs = new ArrayList<>();
        boolean anyTruncated = false;
        try {
            for (FederatedSourceExtract ext : fedPlan.extracts()) {
                SqlQuery.Result extRes = dataQueryService.execute(
                        ext.datasourceId(),
                        new SqlQuery.Request(ext.extractSql(), SqlQuery.Request.MAX_ALLOWED_ROWS, properties.getTimeoutSeconds()));
                if (extRes.truncated()) {
                    anyTruncated = true;
                }
                List<Map<String, Object>> extRowMaps = new ArrayList<>(extRes.rows().size());
                for (List<String> raw : extRes.rows()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (int i = 0; i < extRes.columns().size(); i++) {
                        m.put(extRes.columns().get(i).name(), i < raw.size() ? raw.get(i) : null);
                    }
                    extRowMaps.add(m);
                }
                inputs.add(new FederatedTableInput(ext.alias(), extRes.columns(), extRowMaps));
            }

            FederatedResult fedRes = federatedQueryGateway.executeDuckDbJoin(new DuckDbFederatedRequest(
                    inputs, fedPlan.federatedSql(), properties.getMaxRows(), properties.getTimeoutSeconds()));
            List<InsightColumn> execCols = fedRes.columns().isEmpty() ? planCols
                    : fedRes.columns().stream()
                            .map(c -> new InsightColumn(
                                    bizLabel(c.name(), plan.columns()),
                                    c.name(),
                                    c.canonicalType() != null ? c.canonicalType().name() : c.rawType()))
                            .toList();
            ChartSpec chartSpec = chartRecommender.recommend(
                    "SUCCESS", question, execCols, fedRes.rows(), spec, publishedObjects);
            String summary = "已通过 DuckDB 嵌入式联邦引擎完成跨仓 JOIN（" + String.join(" ⋈ ", whNames) + "），返回 "
                    + fedRes.rowCount() + " 条结果"
                    + (anyTruncated ? "（注意：部分源表提取已达行数上限，建议生产大数据量切换 StarRocks External Catalog）" : "。");
            return new EngineAskResult(
                    queryId, "SUCCESS", fedPlan.federatedSql(), execCols, fedRes.rows(),
                    summary, null, plan.semanticSnapshotRefs(), plan.auditFingerprint(),
                    plan.appliedExclusions(), List.of(), spec, chartSpec,
                    plan.mode().name(), "DUCKDB", whNames, sqlPasses,
                        plan.reproducibilityFingerprint());
        } catch (Exception ex) {
            log.warn("DuckDB 联邦执行失败: {}", ex.getMessage());
            ChartSpec chartSpec = chartRecommender.recommend(
                    "COMPILED_ONLY", question, planCols, List.of(), spec, publishedObjects);
            return new EngineAskResult(
                    queryId, "COMPILED_ONLY", fedPlan.federatedSql(), planCols, List.of(),
                    "已完成 DuckDB 跨仓联邦编译（源仓库暂不可达：" + ex.getMessage() + "），可核验各仓提取 SQL 与联邦 JOIN SQL。",
                    null, plan.semanticSnapshotRefs(), plan.auditFingerprint(),
                    plan.appliedExclusions(), List.of(), spec, chartSpec,
                    plan.mode().name(), "DUCKDB", whNames, sqlPasses,
                        plan.reproducibilityFingerprint());
        }
    }

    /**
     * 确定性两阶段统计合并器（C3）：支持 SUM/COUNT/COUNT_DISTINCT/MIN/MAX 及精确加权 AVG（SUM(__sum)/SUM(__cnt)）。
     */
    static List<Map<String, Object>> mergeCohortRows(List<Map<String, Object>> rawRows,
                                                     List<AggregateMergeColumn> mergeColumns) {
        if (rawRows == null || rawRows.isEmpty()) {
            return List.of();
        }
        List<AggregateMergeColumn> dims = mergeColumns.stream()
                .filter(c -> "DIM".equalsIgnoreCase(c.aggKind()))
                .toList();
        List<AggregateMergeColumn> metrics = mergeColumns.stream()
                .filter(c -> !"DIM".equalsIgnoreCase(c.aggKind()))
                .toList();

        Map<List<String>, Map<String, Double>> accMap = new LinkedHashMap<>();
        Map<List<String>, Map<String, Object>> dimValMap = new LinkedHashMap<>();

        for (Map<String, Object> row : rawRows) {
            List<String> key = new ArrayList<>(dims.size());
            Map<String, Object> dimVals = new LinkedHashMap<>();
            for (AggregateMergeColumn d : dims) {
                Object val = row.get(d.sourceCol());
                key.add(val == null ? "__NULL__" : String.valueOf(val));
                dimVals.put(d.outputKey(), val);
            }
            dimValMap.putIfAbsent(key, dimVals);
            Map<String, Double> acc = accMap.computeIfAbsent(key, k -> new LinkedHashMap<>());

            for (AggregateMergeColumn m : metrics) {
                String k = m.outputKey();
                if ("AVG".equalsIgnoreCase(m.aggKind())) {
                    double s = toDouble(row.get(m.sumCol()));
                    double c = toDouble(row.get(m.countCol()));
                    acc.merge(k + "__sum", s, Double::sum);
                    acc.merge(k + "__cnt", c, Double::sum);
                } else if ("MIN".equalsIgnoreCase(m.aggKind())) {
                    double v = toDouble(row.get(m.sourceCol()));
                    acc.merge(k, v, Math::min);
                } else if ("MAX".equalsIgnoreCase(m.aggKind())) {
                    double v = toDouble(row.get(m.sourceCol()));
                    acc.merge(k, v, Math::max);
                } else {
                    double v = toDouble(row.get(m.sourceCol()));
                    acc.merge(k, v, Double::sum);
                }
            }
        }

        List<Map<String, Object>> out = new ArrayList<>(accMap.size());
        for (var entry : accMap.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>(dimValMap.get(entry.getKey()));
            Map<String, Double> acc = entry.getValue();
            for (AggregateMergeColumn m : metrics) {
                String k = m.outputKey();
                if ("AVG".equalsIgnoreCase(m.aggKind())) {
                    double s = acc.getOrDefault(k + "__sum", 0.0);
                    double c = acc.getOrDefault(k + "__cnt", 0.0);
                    double avg = c == 0.0 ? 0.0 : Math.round((s / c) * 10000.0) / 10000.0;
                    row.put(k, avg);
                } else if ("COUNT".equalsIgnoreCase(m.aggKind()) || "COUNT_DISTINCT".equalsIgnoreCase(m.aggKind())) {
                    row.put(k, Math.round(acc.getOrDefault(k, 0.0)));
                } else {
                    double v = Math.round(acc.getOrDefault(k, 0.0) * 10000.0) / 10000.0;
                    row.put(k, v);
                }
            }
            out.add(row);
        }
        return out;
    }

    private List<InsightColumn> toExecCols(SqlQuery.Result sqlResult,
                                           List<CompiledColumn> compiledCols,
                                           List<InsightColumn> fallback) {
        if (sqlResult.columns() == null || sqlResult.columns().isEmpty()) {
            return fallback;
        }
        return sqlResult.columns().stream()
                .map(c -> new InsightColumn(
                        bizLabel(c.name(), compiledCols),
                        c.name(),
                        c.canonicalType() != null ? c.canonicalType().name() : c.rawType()))
                .toList();
    }

    private List<Map<String, Object>> toRowMaps(SqlQuery.Result sqlResult, List<InsightColumn> execCols) {
        List<Map<String, Object>> mappedRows = new ArrayList<>(sqlResult.rows().size());
        for (List<String> rawRow : sqlResult.rows()) {
            Map<String, Object> rowMap = new LinkedHashMap<>();
            for (int i = 0; i < execCols.size(); i++) {
                String key = execCols.get(i).bizName();
                rowMap.put(key, i < rawRow.size() ? rawRow.get(i) : null);
            }
            mappedRows.add(rowMap);
        }
        return mappedRows;
    }

    private static double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private List<ObjectView> safePublishedObjects() {
        try {
            PageResult<ObjectView> page = ontologyService.listObjects(1, 200, null, null, null, "PUBLISHED");
            return page == null || page.records() == null ? List.of() : page.records();
        } catch (Exception e) {
            return List.of();
        }
    }

    private String resolveDataSourceId(String workspaceId, String catalogDatasetId) {
        if (catalogDatasetId == null || catalogDatasetId.isBlank()) {
            return null;
        }
        CatalogDataset ds = datasetMapper.selectOne(new LambdaQueryWrapper<CatalogDataset>()
                .eq(CatalogDataset::getWorkspaceId, workspaceId)
                .eq(CatalogDataset::getId, catalogDatasetId));
        if (ds == null || ds.getSourceId() == null) {
            return null;
        }
        CatalogSource src = sourceMapper.selectOne(new LambdaQueryWrapper<CatalogSource>()
                .eq(CatalogSource::getWorkspaceId, workspaceId)
                .eq(CatalogSource::getId, ds.getSourceId()));
        return src == null ? null : src.getDataSourceId();
    }

    private boolean hasDisambiguationConflict(List<SubgraphNode> seedMetrics, String question) {
        String qLower = question == null ? "" : question.toLowerCase(Locale.ROOT);
        long exactHits = seedMetrics.stream()
                .filter(m -> qLower.contains(m.name().toLowerCase(Locale.ROOT))
                        || qLower.contains(m.code().toLowerCase(Locale.ROOT)))
                .count();
        if (exactHits == 1) {
            return false;
        }
        return seedMetrics.stream().anyMatch(m -> m.aiContext().containsKey("disambiguation"));
    }

    private boolean isTimeDimension(SubgraphNode node) {
        if ("DIMENSION".equalsIgnoreCase(node.objectType())
                && Boolean.TRUE.equals(node.spec().get("isTimeDimension"))) {
            return true;
        }
        String lower = node.code().toLowerCase(Locale.ROOT);
        return lower.contains("date") || lower.contains("time") || lower.contains("month");
    }

    private boolean mentionsTrend(String question) {
        if (question == null) {
            return false;
        }
        return question.contains("趋势") || question.contains("按月") || question.contains("按天")
                || question.contains("按日") || question.contains("每日") || question.contains("每月");
    }

    private String bizLabel(String colName, List<CompiledColumn> compiledCols) {
        for (CompiledColumn c : compiledCols) {
            if (c.name().equalsIgnoreCase(colName)) {
                return c.bizName();
            }
        }
        return colName;
    }

    private String buildSummary(CompiledSemanticQuery compiled, int rowCount,
                                List<ValueMatch> valueMatches, NormalizedTimeRange relTime, boolean executed) {
        StringBuilder sb = new StringBuilder();
        if (executed) {
            sb.append("原生语义引擎执行完成，返回 ").append(rowCount).append(" 行");
        } else {
            sb.append("已完成确定性语义 SQL 编译（当前物理表未绑定活跃 JDBC 数据源，未执行取数）");
        }
        if (!valueMatches.isEmpty()) {
            sb.append("；值对齐：").append(valueMatches.stream()
                    .map(v -> v.matchedPhrase() + "→" + v.rawValue())
                    .reduce((a, b) -> a + ", " + b).orElse(""));
        }
        if (relTime != null) {
            sb.append("；时间窗：").append(relTime.startDate()).append("~").append(relTime.endDate());
        }
        if (!compiled.appliedExclusions().isEmpty()) {
            sb.append("；自动生效口径约束 ").append(compiled.appliedExclusions().size()).append(" 条");
        }
        return sb.toString();
    }
}
