package com.datagov.app.lineage;

import com.datagov.app.runner.TargetNaming;
import com.datagov.metadata.dto.RuleDtos.RuleView;
import com.datagov.metadata.service.LineageCatalogResolver;
import com.datagov.metadata.service.LineageService.EdgeInput;
import com.datagov.metadata.service.LineageService.NodeRef;
import com.datagov.metadata.service.RuleService;
import com.datagov.metadata.service.lineage.SqlLineageParserClient;
import com.datagov.metadata.service.lineage.SqlLineageParserClient.ColumnEdge;
import com.datagov.metadata.service.lineage.SqlLineageParserClient.ParseRequest;
import com.datagov.metadata.service.lineage.SqlLineageParserClient.ParseResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 平台内建作业的结构化列级血缘抽取器(column-lineage-e2e P1-4)。
 *
 * <p>只读 JobDef 的 config 或编译后的物理计划，产出<b>边草稿</b>({@link EdgeDraft})，
 * 本身<b>不写血缘</b>：写入由调用方(P1-5 的事件监听)交给 {@code LineageService}。
 * 放在 dg-app 的原因是 C1 —— dg-control 不可见 dg-metadata，而对齐 Catalog 资产
 * 需要 {@link LineageCatalogResolver}；读 {@code JobDef} 与 dg-runtime 的执行事实
 * 则是装配层的本职。
 *
 * <p>支持的作业类型(同时接受 {@code OFFLINE_SYNC} 与 {@code OfflineSync} 两种写法)：
 * <ul>
 *   <li><b>OfflineSync</b>：{@code fieldMappings}(源列→目标列)。无规则 → {@code COPY}；
 *       有 {@code fieldRules} → {@code TRANSFORM} 并写规则摘要；规则含脱敏/哈希类
 *       → {@code maskingApplied=true} 并写 {@code maskingEvidence}。</li>
 *   <li><b>DbMigration</b>：按源表在 Catalog 中的列清单，叠加
 *       {@code tablePrefix / tableSuffix / lowercaseNames}(与 {@link TargetNaming} 同源)
 *       映射目标列，产出 {@code COPY} 边。</li>
 *   <li><b>FileParse / ApiParse</b>：源端是没有表资产的文件 / 接口字段，节点为
 *       {@code file://{source}/{field}}，连向目标表列。</li>
 * </ul>
 *
 * <p><b>C8：不猜。</b>整库迁移里源表在 Catalog 中找不到就跳过该表并给出警告，
 * 不会凭空编列名；规则 ID 查不到时只标记「未知规则」的 {@code TRANSFORM}，
 * 绝不据此声称已脱敏 —— 误报脱敏会让敏感标签沿血缘被错误地阻断。
 */
@Component
public class StructuredJobLineageExtractor {

    /** 脱敏 / 不可逆哈希类函数白名单，与 design.md §5 保持一致。 */
    private static final Set<String> MASKING_TOKENS = Set.of(
            "MASK", "MD5", "SHA", "SHA1", "SHA256", "SHA512", "HASH",
            "ENCRYPT", "DESENSITIZE");
    private static final Pattern CREDENTIAL_PARAM = Pattern.compile(".*credential.*",
            Pattern.CASE_INSENSITIVE);

    public static final String ORIGIN = "AUTO";
    public static final String RELATION_COPY = "COPY";
    public static final String RELATION_TRANSFORM = "TRANSFORM";
    public static final String DEPENDENCY_DIRECT = "DIRECT";

    /** 抽取器自己需要的规则信息，与 Metadata 的 {@code RuleView} 解耦以便测试。 */
    public record RuleInfo(String id, String kind, Map<String, String> params) {
    }

    /** 按 ID 批量取规则(保持传入顺序，查不到的 ID 不出现在结果里)。 */
    @FunctionalInterface
    public interface RuleLookup {
        Map<String, RuleInfo> load(String workspaceId, Collection<String> ruleIds);
    }

    /**
     * 边草稿。比 {@code LineageService.EdgeInput} 多了 {@code dependencyType} 与脱敏阻断标记
     * (P1-2 落地后由 {@code LineageService} 直接消费)；{@link #toEdgeInput()} 提供向现有
     * 写入接口的降级映射。
     */
    public record EdgeDraft(NodeRef from, NodeRef to, String relation, String dependencyType,
                            String transformExpr, Double confidence, String origin,
                            boolean maskingApplied, String maskingEvidence) {

        public EdgeInput toEdgeInput() {
            return new EdgeInput(from, to, relation, transformExpr, confidence, origin,
                    dependencyType, maskingApplied, maskingEvidence);
        }
    }

    /** 抽取结果：边 + 抽取过程中的警告(跳过的表、未知规则等)。 */
    public record Extraction(String jobType, List<EdgeDraft> edges, List<String> warnings) {

        public static Extraction empty(String jobType, String warning) {
            return new Extraction(jobType, List.of(), warning == null ? List.of() : List.of(warning));
        }

        public List<EdgeInput> toEdgeInputs() {
            return edges.stream().map(EdgeDraft::toEdgeInput).toList();
        }
    }

    private final LineageCatalogResolver resolver;
    private final RuleLookup ruleLookup;
    /** 可为 null(测试 / 未部署解析 sidecar):此时 Custom SQL 节点只给警告,不补列映射。 */
    private final SqlLineageParserClient parserClient;

    @Autowired
    public StructuredJobLineageExtractor(LineageCatalogResolver resolver, RuleService ruleService,
                                         SqlLineageParserClient parserClient) {
        this(resolver, ruleLookupOf(ruleService), parserClient);
    }

    public StructuredJobLineageExtractor(LineageCatalogResolver resolver, RuleService ruleService) {
        this(resolver, ruleLookupOf(ruleService), null);
    }

    public StructuredJobLineageExtractor(LineageCatalogResolver resolver, RuleLookup ruleLookup) {
        this(resolver, ruleLookup, null);
    }

    public StructuredJobLineageExtractor(LineageCatalogResolver resolver, RuleLookup ruleLookup,
                                         SqlLineageParserClient parserClient) {
        this.resolver = resolver;
        this.ruleLookup = ruleLookup;
        this.parserClient = parserClient;
    }

    private static RuleLookup ruleLookupOf(RuleService ruleService) {
        return (workspaceId, ids) -> {
            Map<String, RuleInfo> result = new LinkedHashMap<>();
            for (RuleView view : ruleService.loadForExecution(workspaceId, List.copyOf(ids))) {
                result.put(view.id(), new RuleInfo(view.id(),
                        view.kind() == null ? null : view.kind().name(), view.params()));
            }
            return result;
        };
    }

    // ── 入口 ────────────────────────────────────────────────────────────

    /**
     * @param jobType      {@code OFFLINE_SYNC / DB_MIGRATION / FILE_PARSE / API_PARSE}
     *                     (大小写与下划线不敏感)
     * @param planOrConfig JobDef 的 config，或编译后的物理计划(含 {@code source}/{@code target} 段)
     */
    public Extraction extract(String workspaceId, String jobType, Map<String, Object> planOrConfig) {
        String normalized = normalizeType(jobType);
        if (planOrConfig == null || planOrConfig.isEmpty()) {
            return Extraction.empty(normalized, "作业配置为空，未抽取到血缘");
        }
        Spec spec = Spec.of(planOrConfig);
        return switch (normalized) {
            case "OFFLINE_SYNC" -> extractFieldMapped(workspaceId, normalized, spec, false);
            case "FILE_PARSE", "API_PARSE" -> extractFieldMapped(workspaceId, normalized, spec, true);
            case "DB_MIGRATION" -> extractDbMigration(workspaceId, spec);
            case "ETL_PIPELINE" -> extractEtlPipeline(workspaceId, planOrConfig);
            default -> Extraction.empty(normalized, "作业类型 " + jobType + " 不支持结构化血缘抽取");
        };
    }

    // ── EtlPipeline ─────────────────────────────────────────────────────

    /**
     * 轻量级 ETL 管道(etl.ir/v1)。<b>优先用编译产物里的 {@code lineageEdges}</b>:
     * <pre>{ from: {dataSourceId, database, schema, table, column}, to: {...},
     *        relation?: COPY|TRANSFORM, transformExpr?, confidence?, maskingApplied?, maskingEvidence? }</pre>
     * 也接受端点直接给 {@code qualifiedName}(外部列)。没有 lineageEdges 时只在<b>可以不猜</b>的最小形状下回退:
     * 单个 SOURCE_JDBC → (可选 FILTER) → SINK_JDBC 且 Sink 给了 columnMappings,产出 COPY 边;
     * 其余(含任意 SQL 转换)一律不猜,返回警告(C8)。
     *
     * <p><b>Custom SQL 补齐(P1-6)</b>:计划里含 {@code TRANSFORM_CUSTOM_SQL}(兼容 {@code CUSTOM_SQL})
     * 节点时(顶层 {@code nodes} 或 {@code ir.nodes}),把 {@code {{上游}}} 重写成物理表标识符、
     * 包成 {@code CREATE TABLE ... AS SELECT} 交给 {@link SqlLineageParserClient},按 Sink 的列映射
     * 把「物理源表列 → Sink 列」边并入结果;解析 INCONCLUSIVE 只加警告、不造边。
     */
    @SuppressWarnings("unchecked")
    private Extraction extractEtlPipeline(String workspaceId, Map<String, Object> plan) {
        List<String> warnings = new ArrayList<>();
        List<EdgeDraft> edges = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        List<Map<String, Object>> nodes = etlNodeList(plan);
        // 编译器对 Custom SQL 只能给出表级(无 column)的低置信边;若 SQL 解析补齐了列级边,
        // 这些「端点不完整」的警告就是噪音,只在没补齐时才保留。
        List<String> tableLevelWarnings = new ArrayList<>();

        Object declared = plan.get("lineageEdges");
        if (declared instanceof Collection<?> items && !items.isEmpty()) {
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> raw)) {
                    warnings.add("lineageEdges 含非对象元素,已跳过");
                    continue;
                }
                Map<String, Object> edge = (Map<String, Object>) raw;
                NodeRef from = etlEndpoint(workspaceId, edge.get("from"));
                NodeRef to = etlEndpoint(workspaceId, edge.get("to"));
                if (from == null || to == null) {
                    String skipped = "lineageEdges 含无法解析的端点,已跳过: " + edge;
                    if (lacksColumn(edge.get("from")) || lacksColumn(edge.get("to"))) {
                        tableLevelWarnings.add(skipped);
                    } else {
                        warnings.add(skipped);
                    }
                    continue;
                }
                if (Objects.equals(from.qualifiedName(), to.qualifiedName())
                        || !seen.add(from.qualifiedName() + "|" + to.qualifiedName())) {
                    continue;
                }
                String relation = edge.get("relation") == null
                        ? (edge.get("transformExpr") == null ? RELATION_COPY : RELATION_TRANSFORM)
                        : edge.get("relation").toString().toUpperCase(Locale.ROOT);
                String expr = edge.get("transformExpr") == null ? null
                        : truncate(edge.get("transformExpr").toString(), 1000);
                Double confidence = edge.get("confidence") instanceof Number n ? n.doubleValue() : 1.0;
                boolean masked = Boolean.parseBoolean(String.valueOf(edge.get("maskingApplied")));
                String evidence = edge.get("maskingEvidence") == null ? null
                        : truncate(edge.get("maskingEvidence").toString(), 512);
                edges.add(new EdgeDraft(from, to, relation, DEPENDENCY_DIRECT, expr, confidence,
                        ORIGIN, masked, masked ? evidence : null));
            }
            // P1-6:编译器 lineageEdges 优先;Custom SQL 节点再用 SQL 解析补齐列级边(只增不改)。
            List<String> enrichWarnings = new ArrayList<>();
            int enriched = enrichCustomSql(workspaceId, plan, nodes, edges, seen, enrichWarnings);
            warnings.addAll(enrichWarnings);
            if (enriched == 0) {
                warnings.addAll(tableLevelWarnings);
            }
            return new Extraction("ETL_PIPELINE", edges, warnings);
        }

        // 没有 lineageEdges:Custom SQL 节点仍可由 SQL 解析给出列级边。
        List<String> enrichWarnings = new ArrayList<>();
        enrichCustomSql(workspaceId, plan, nodes, edges, seen, enrichWarnings);
        if (!edges.isEmpty()) {
            warnings.addAll(enrichWarnings);
            return new Extraction("ETL_PIPELINE", edges, warnings);
        }

        List<Map<String, Object>> sources = nodes.stream()
                .filter(n -> "SOURCE_JDBC".equals(typeOf(n))).toList();
        List<Map<String, Object>> sinks = nodes.stream()
                .filter(n -> "SINK_JDBC".equals(typeOf(n))).toList();
        boolean onlyFilters = nodes.stream().map(StructuredJobLineageExtractor::typeOf)
                .allMatch(t -> "SOURCE_JDBC".equals(t) || "SINK_JDBC".equals(t)
                        || "FILTER".equals(t) || "TRANSFORM_FILTER".equals(t));
        if (sources.size() != 1 || sinks.size() != 1 || !onlyFilters) {
            List<String> reasons = new ArrayList<>(enrichWarnings);
            reasons.add("计划未携带 lineageEdges,且不是 单源→(过滤)→单 Sink 的最小形状,无法在不猜测的前提下抽取列级血缘");
            return new Extraction("ETL_PIPELINE", List.of(), reasons);
        }
        Map<String, Object> source = sources.get(0);
        Map<String, Object> sink = sinks.get(0);
        Map<String, String> mappings = stringMap(firstNonNull(sink.get("columnMappings"),
                sink.get("fieldMappings")));
        if (mappings.isEmpty()) {
            return Extraction.empty("ETL_PIPELINE", "Sink 没有列映射,未抽取到血缘");
        }
        for (Map.Entry<String, String> mapping : mappings.entrySet()) {
            NodeRef from = resolver.resolveNodeRef(workspaceId, str(source.get("dataSourceId")),
                    str(source.get("database")), str(source.get("schema")), str(source.get("table")),
                    mapping.getKey());
            NodeRef to = resolver.resolveNodeRef(workspaceId, str(sink.get("dataSourceId")),
                    str(sink.get("database")), str(sink.get("schema")), str(sink.get("table")),
                    mapping.getValue());
            if (Objects.equals(from.qualifiedName(), to.qualifiedName())
                    || !seen.add(from.qualifiedName() + "|" + to.qualifiedName())) {
                continue;
            }
            edges.add(new EdgeDraft(from, to, RELATION_COPY, DEPENDENCY_DIRECT, null, 1.0,
                    ORIGIN, false, null));
        }
        return new Extraction("ETL_PIPELINE", edges, warnings);
    }

    @SuppressWarnings("unchecked")
    private NodeRef etlEndpoint(String workspaceId, Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        Map<String, Object> endpoint = (Map<String, Object>) m;
        String qualified = str(endpoint.get("qualifiedName"));
        if (qualified != null) {
            return LineageCatalogResolver.externalColumnRef(qualified,
                    str(firstNonNull(endpoint.get("column"), qualified)));
        }
        String table = str(endpoint.get("table"));
        String column = str(endpoint.get("column"));
        if (table == null || column == null) {
            return null;
        }
        return resolver.resolveNodeRef(workspaceId, str(endpoint.get("dataSourceId")),
                str(endpoint.get("database")), str(endpoint.get("schema")), table, column);
    }

    /** 节点的 config 子对象与顶层键合并(与 EtlPipelineEngine.parse 同一规则)。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> flattenNode(Map<String, Object> node) {
        Map<String, Object> flat = new LinkedHashMap<>(node);
        if (node.get("config") instanceof Map<?, ?> nested) {
            flat.putAll((Map<String, Object>) nested);
        }
        return flat;
    }

    /** 节点类型:兼容 {@code type}(旧写法)与 etl.ir/v1 的 {@code kind}。 */
    private static String typeOf(Map<String, Object> node) {
        String type = str(node.get("type"));
        if (type == null) {
            type = str(node.get("kind"));
        }
        return type == null ? "" : type.toUpperCase(Locale.ROOT);
    }

    // ── Custom SQL 列级补齐(P1-6) ──────────────────────────────────────

    /** 解析 SQL 时用的虚拟写入目标;只借它让解析器认出「这是一条产出列的查询」。 */
    private static final String SQL_OUTPUT_TABLE = "__dg_etl_out";
    /**
     * DuckDB 的 SELECT 语法与 PostgreSQL 一致;sidecar 目前只支持
     * postgres / mysql / doris / starrocks / oracle / spark / hive / tsql,没有 duckdb。
     */
    private static final String CUSTOM_SQL_DIALECT = "postgres";
    private static final Pattern SQL_PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}\\s]+)\\s*}}");

    private static boolean isCustomSql(String type) {
        return "TRANSFORM_CUSTOM_SQL".equals(type) || "CUSTOM_SQL".equals(type);
    }

    private static boolean isPassThrough(String type) {
        return "TRANSFORM_FILTER".equals(type) || "FILTER".equals(type);
    }

    /** 物理表(Custom SQL 的上游 SOURCE_JDBC)。 */
    private record SourceTable(String dataSourceId, String database, String schema, String table) {
        String key() {
            String t = table.toLowerCase(Locale.ROOT);
            return schema == null ? t : schema.toLowerCase(Locale.ROOT) + "." + t;
        }

        /** 重写进 SQL 的标识符;解析器按 schema.table 对齐,database 不参与。 */
        String sqlIdentifier() {
            return schema == null ? quoteIdent(table) : quoteIdent(schema) + "." + quoteIdent(table);
        }
    }

    /** Sink 目标表及列映射(键为小写 SQL 输出列名)。 */
    private record SinkTarget(String nodeId, String dataSourceId, String database, String schema,
                              String table, Map<String, String> mapping) {
    }

    private static String quoteIdent(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static boolean lacksColumn(Object endpoint) {
        return !(endpoint instanceof Map<?, ?> m) || str(m.get("column")) == null;
    }

    /** 计划里的节点(扁平化);顶层 {@code nodes} 优先,否则取 {@code ir.nodes}。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> etlNodeList(Map<String, Object> plan) {
        Object raw = plan.get("nodes");
        if (!(raw instanceof Collection<?>) && plan.get("ir") instanceof Map<?, ?> ir) {
            raw = ir.get("nodes");
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (raw instanceof Collection<?> items) {
            for (Object n : items) {
                if (n instanceof Map<?, ?> m) {
                    nodes.add(flattenNode((Map<String, Object>) m));
                }
            }
        }
        return nodes;
    }

    /** 节点连线 [from, to];顶层 {@code edges} 优先,否则取 {@code ir.edges}。 */
    private static List<String[]> etlEdgeList(Map<String, Object> plan) {
        Object raw = plan.get("edges");
        if (!(raw instanceof Collection<?>) && plan.get("ir") instanceof Map<?, ?> ir) {
            raw = ir.get("edges");
        }
        List<String[]> edges = new ArrayList<>();
        if (raw instanceof Collection<?> items) {
            for (Object e : items) {
                if (e instanceof Map<?, ?> m) {
                    String from = str(m.get("from"));
                    String to = str(m.get("to"));
                    if (from != null && to != null) {
                        edges.add(new String[]{from, to});
                    }
                }
            }
        }
        return edges;
    }

    private static List<String> neighbours(List<String[]> edges, String id, int selfIdx, int otherIdx) {
        List<String> result = new ArrayList<>();
        for (String[] e : edges) {
            if (e[selfIdx].equals(id)) {
                result.add(e[otherIdx]);
            }
        }
        return result;
    }

    /**
     * 对 Custom SQL 节点调用 {@link SqlLineageParserClient} 补齐列映射,把结果并入 {@code edges}。
     *
     * <p>只做能确定的事(C8):
     * <ul>
     *   <li>每个 {@code {{上游}}} 必须经「仅过滤」链回溯到唯一的 SOURCE_JDBC 物理表,否则整节点跳过;</li>
     *   <li>节点输出必须经「仅过滤」链直连唯一 JDBC Sink,SQL 输出列按 Sink 的 columnMapping
     *       (无映射则同名)落到 Sink 列,未写入 Sink 的列不产边;</li>
     *   <li>解析 {@code INCONCLUSIVE / ERROR} 只加警告,不产边;</li>
     *   <li>与编译器 lineageEdges 已有的 from→to 重复时保留编译器那条。</li>
     * </ul>
     *
     * @return 新增的边数
     */
    private int enrichCustomSql(String workspaceId, Map<String, Object> plan,
                                List<Map<String, Object>> nodeList, List<EdgeDraft> edges,
                                Set<String> seen, List<String> warnings) {
        List<Map<String, Object>> customs = nodeList.stream()
                .filter(n -> isCustomSql(typeOf(n))).toList();
        if (customs.isEmpty()) {
            return 0;
        }
        if (parserClient == null) {
            warnings.add("管道含 Custom SQL 节点,但未接入 SQL 血缘解析,未补齐列级映射");
            return 0;
        }
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> n : nodeList) {
            String id = str(n.get("id"));
            if (id != null) {
                byId.putIfAbsent(id, n);
            }
        }
        List<String[]> graph = etlEdgeList(plan);
        SinkTarget sink = resolveSink(plan, nodeList);
        if (sink == null) {
            warnings.add("Custom SQL 节点无法定位唯一的 JDBC Sink 目标表,未补齐列级映射");
            return 0;
        }
        Set<String> declaredPairs = new LinkedHashSet<>(seen);
        Set<String> enrichedKeys = new LinkedHashSet<>();
        int added = 0;
        for (Map<String, Object> custom : customs) {
            added += enrichOneCustomSql(workspaceId, custom, byId, graph, sink,
                    declaredPairs, enrichedKeys, edges, warnings);
        }
        return added;
    }

    private int enrichOneCustomSql(String workspaceId, Map<String, Object> custom,
                                   Map<String, Map<String, Object>> byId, List<String[]> graph,
                                   SinkTarget sink, Set<String> declaredPairs,
                                   Set<String> enrichedKeys, List<EdgeDraft> edges,
                                   List<String> warnings) {
        String nodeId = str(custom.get("id"));
        String label = "Custom SQL 节点 " + (nodeId == null ? "(无 id)" : nodeId);
        String sql = str(custom.get("sql"));
        if (nodeId == null || sql == null) {
            warnings.add(label + " 缺少 id 或 sql,已跳过列级补齐");
            return 0;
        }
        if (!feedsSink(nodeId, sink, byId, graph)) {
            warnings.add(label + " 的输出没有(仅经过滤)直连 JDBC Sink,无法映射到 Sink 列,已跳过");
            return 0;
        }

        Map<String, SourceTable> tables = new LinkedHashMap<>();
        Matcher matcher = SQL_PLACEHOLDER.matcher(sql);
        StringBuilder rewritten = new StringBuilder();
        while (matcher.find()) {
            SourceTable source = upstreamSource(matcher.group(1), byId, graph);
            if (source == null) {
                warnings.add(label + " 引用的上游 {{" + matcher.group(1)
                        + "}} 不是(仅经过滤的)SOURCE_JDBC 物理表,已跳过列级补齐");
                return 0;
            }
            SourceTable clash = tables.putIfAbsent(source.key(), source);
            if (clash != null && !clash.equals(source)) {
                warnings.add(label + " 的多个上游物理表同名(" + source.key()
                        + ")但来自不同库/数据源,SQL 解析无法区分,已跳过");
                return 0;
            }
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(source.sqlIdentifier()));
        }
        matcher.appendTail(rewritten);
        if (tables.isEmpty()) {
            warnings.add(label + " 没有通过 {{上游}} 引用任何表,已跳过列级补齐");
            return 0;
        }

        Map<String, List<String>> catalogSchema = new LinkedHashMap<>();
        Map<String, Map<String, String>> columnCase = new LinkedHashMap<>();
        for (SourceTable t : tables.values()) {
            Optional<String> datasetId = resolver.resolveDatasetId(workspaceId, t.dataSourceId(),
                    t.database(), t.schema(), t.table());
            List<String> columns = datasetId.map(resolver::listColumnNames).orElse(List.of());
            if (!columns.isEmpty()) {
                catalogSchema.put(t.key(), columns);
                Map<String, String> byLower = new LinkedHashMap<>();
                columns.forEach(c -> byLower.putIfAbsent(c.toLowerCase(Locale.ROOT), c));
                columnCase.put(t.key(), byLower);
            }
        }

        String body = rewritten.toString().trim();
        while (body.endsWith(";")) {
            body = body.substring(0, body.length() - 1).trim();
        }
        ParseResponse response;
        try {
            response = parserClient.parseColumn(new ParseRequest(CUSTOM_SQL_DIALECT,
                    "CREATE TABLE " + quoteIdent(SQL_OUTPUT_TABLE) + " AS " + body, null,
                    catalogSchema.isEmpty() ? null : catalogSchema));
        } catch (RuntimeException e) {
            warnings.add(label + " 的 SQL 血缘解析调用失败,未补齐列级映射: " + e.getClass().getSimpleName());
            return 0;
        }
        if (response == null || !response.ok()) {
            String detail = response == null ? null : response.diagnosticSummary();
            warnings.add(label + " 的 SQL 列级血缘未能确定("
                    + (response == null ? "无响应" : response.status()) + ")"
                    + (detail == null ? "" : ": " + detail) + ",未补齐列级映射");
            return 0;
        }

        int added = 0;
        for (ColumnEdge ce : response.allEdges()) {
            SourceTable source = tables.get(ce.sourceTable() == null ? ""
                    : ce.sourceTable().toLowerCase(Locale.ROOT));
            if (source == null || ce.sourceColumn() == null || ce.targetColumn() == null) {
                warnings.add(label + " 的解析结果含无法对应到上游物理表的来源,已跳过: "
                        + ce.sourceTable() + "." + ce.sourceColumn());
                continue;
            }
            String sinkColumn = sinkColumnFor(sink, ce.targetColumn());
            if (sinkColumn == null) {
                continue; // 该 SQL 输出列没有写入 Sink,不产边
            }
            String sourceColumn = columnCase.getOrDefault(source.key(), Map.of())
                    .getOrDefault(ce.sourceColumn().toLowerCase(Locale.ROOT), ce.sourceColumn());
            NodeRef from = resolver.resolveNodeRef(workspaceId, source.dataSourceId(),
                    source.database(), source.schema(), source.table(), sourceColumn);
            NodeRef to = resolver.resolveNodeRef(workspaceId, sink.dataSourceId(), sink.database(),
                    sink.schema(), sink.table(), sinkColumn);
            if (Objects.equals(from.qualifiedName(), to.qualifiedName())) {
                continue;
            }
            String pair = from.qualifiedName() + "|" + to.qualifiedName();
            String relation = ce.relation() == null || ce.relation().isBlank()
                    ? RELATION_TRANSFORM : ce.relation().trim().toUpperCase(Locale.ROOT);
            String dependency = "INDIRECT".equalsIgnoreCase(ce.dependencyType())
                    ? "INDIRECT" : DEPENDENCY_DIRECT;
            if (declaredPairs.contains(pair)
                    || !enrichedKeys.add(pair + "|" + relation + "|" + dependency)) {
                continue;
            }
            edges.add(new EdgeDraft(from, to, relation, dependency,
                    ce.transformExpr() == null ? null : truncate(ce.transformExpr(), 1000),
                    1.0, ORIGIN, ce.maskingApplied(),
                    ce.maskingApplied() ? "sql:masking-function" : null));
            added++;
        }
        response.statements().forEach(s -> s.warnings().forEach(w -> warnings.add(label + ": " + w)));
        return added;
    }

    /** 无映射 → 同名;有映射 → 只取映射里出现的列(未出现的列没有写入 Sink)。 */
    private static String sinkColumnFor(SinkTarget sink, String sqlOutputColumn) {
        if (sink.mapping().isEmpty()) {
            return sqlOutputColumn;
        }
        return sink.mapping().get(sqlOutputColumn.toLowerCase(Locale.ROOT));
    }

    /** 节点输出是否仅经过滤链直达 Sink 节点。 */
    private static boolean feedsSink(String nodeId, SinkTarget sink,
                                     Map<String, Map<String, Object>> byId, List<String[]> graph) {
        String current = nodeId;
        for (int hops = 0; hops < 32; hops++) {
            List<String> outs = neighbours(graph, current, 0, 1);
            if (outs.size() != 1) {
                return false;
            }
            String next = outs.get(0);
            Map<String, Object> node = byId.get(next);
            if (node == null) {
                return false;
            }
            String type = typeOf(node);
            if ("SINK_JDBC".equals(type)) {
                return sink.nodeId() == null || sink.nodeId().equals(next);
            }
            if (!isPassThrough(type)) {
                return false;
            }
            current = next;
        }
        return false;
    }

    /** 沿「仅过滤」链回溯到唯一的 SOURCE_JDBC;列集合因此与物理表一致。 */
    private static SourceTable upstreamSource(String id, Map<String, Map<String, Object>> byId,
                                              List<String[]> graph) {
        String current = id;
        for (int hops = 0; hops < 32; hops++) {
            Map<String, Object> node = byId.get(current);
            if (node == null) {
                return null;
            }
            String type = typeOf(node);
            if ("SOURCE_JDBC".equals(type)) {
                String table = str(node.get("table"));
                if (table == null || table.contains(".")) {
                    return null;
                }
                return new SourceTable(str(node.get("dataSourceId")), str(node.get("database")),
                        str(node.get("schema")), table);
            }
            if (!isPassThrough(type)) {
                return null;
            }
            List<String> ins = neighbours(graph, current, 1, 0);
            if (ins.size() != 1) {
                return null;
            }
            current = ins.get(0);
        }
        return null;
    }

    /** 编译产物 {@code sinkSpec} 优先(是编译后的事实),否则取唯一的 SINK_JDBC 节点。 */
    @SuppressWarnings("unchecked")
    private static SinkTarget resolveSink(Map<String, Object> plan, List<Map<String, Object>> nodes) {
        Map<String, Object> spec = null;
        if (plan.get("sinkSpec") instanceof Map<?, ?> m && str(m.get("table")) != null) {
            spec = (Map<String, Object>) m;
        } else {
            List<Map<String, Object>> sinks = nodes.stream()
                    .filter(n -> "SINK_JDBC".equals(typeOf(n))).toList();
            if (sinks.size() == 1 && str(sinks.get(0).get("table")) != null) {
                spec = sinks.get(0);
            }
        }
        if (spec == null) {
            return null;
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        stringMap(firstNonNull(spec.get("columnMapping"),
                firstNonNull(spec.get("columnMappings"), spec.get("fieldMappings"))))
                .forEach((k, v) -> mapping.put(k.toLowerCase(Locale.ROOT), v));
        return new SinkTarget(str(spec.get("nodeId")) != null ? str(spec.get("nodeId")) : str(spec.get("id")),
                str(spec.get("dataSourceId")), str(spec.get("database")), str(spec.get("schema")),
                str(spec.get("table")), mapping);
    }

    private static String str(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private static Map<String, String> stringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (v != null) {
                result.put(String.valueOf(k), String.valueOf(v));
            }
        });
        return result;
    }

    // ── OfflineSync / FileParse / ApiParse ──────────────────────────────

    private Extraction extractFieldMapped(String workspaceId, String jobType, Spec spec,
                                          boolean externalSource) {
        List<String> warnings = new ArrayList<>();
        if (isBlank(spec.targetTable)) {
            return Extraction.empty(jobType, "缺少目标表，未抽取到血缘");
        }
        if (spec.fieldMappings.isEmpty()) {
            return Extraction.empty(jobType, "缺少字段映射，未抽取到血缘");
        }
        Map<String, RuleInfo> rules = loadRules(workspaceId, spec.fieldRules);

        List<EdgeDraft> edges = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, String> mapping : spec.fieldMappings.entrySet()) {
            String sourceField = mapping.getKey();
            String targetField = mapping.getValue();
            if (isBlank(sourceField) || isBlank(targetField)) {
                warnings.add("字段映射含空列名，已跳过: " + sourceField + " -> " + targetField);
                continue;
            }
            NodeRef from = externalSource
                    ? LineageCatalogResolver.externalColumnRef(
                    externalColumnName(spec, sourceField), sourceField)
                    : resolver.resolveNodeRef(workspaceId, spec.sourceDs, spec.sourceDb,
                    spec.sourceSchema, spec.sourceTable, sourceField);
            NodeRef to = resolver.resolveNodeRef(workspaceId, spec.targetDs, spec.targetDb,
                    spec.targetSchema, spec.targetTable, targetField);
            if (Objects.equals(from.qualifiedName(), to.qualifiedName())) {
                warnings.add("源列与目标列相同，已跳过: " + from.qualifiedName());
                continue;
            }
            if (!seen.add(from.qualifiedName() + "|" + to.qualifiedName())) {
                continue;
            }
            edges.add(buildEdge(from, to, sourceField, spec.fieldRules.get(sourceField),
                    rules, warnings));
        }
        return new Extraction(jobType, edges, warnings);
    }

    private EdgeDraft buildEdge(NodeRef from, NodeRef to, String sourceField,
                                List<String> ruleRefs, Map<String, RuleInfo> rules,
                                List<String> warnings) {
        if (ruleRefs == null || ruleRefs.isEmpty()) {
            return new EdgeDraft(from, to, RELATION_COPY, DEPENDENCY_DIRECT, null, 1.0,
                    ORIGIN, false, null);
        }
        List<String> summary = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        for (String ref : ruleRefs) {
            RuleInfo rule = rules.get(ref);
            if (rule != null) {
                summary.add(describe(rule));
                String maskedBy = maskingEvidence(rule);
                if (maskedBy != null) {
                    evidence.add(maskedBy);
                }
            } else if (isMaskingToken(ref)) {
                // 计划里直接写了函数名(而不是 ruleId)的情形。
                summary.add(ref.trim().toUpperCase(Locale.ROOT));
                evidence.add("function:" + ref.trim().toUpperCase(Locale.ROOT));
            } else {
                summary.add("rule:" + ref);
                warnings.add("字段 " + sourceField + " 引用的规则 " + ref
                        + " 无法解析，未判定其是否脱敏");
            }
        }
        boolean masked = !evidence.isEmpty();
        return new EdgeDraft(from, to, RELATION_TRANSFORM, DEPENDENCY_DIRECT,
                truncate(String.join(" -> ", summary), 1000), 1.0, ORIGIN, masked,
                masked ? truncate(String.join("; ", evidence), 512) : null);
    }

    // ── DbMigration ─────────────────────────────────────────────────────

    private Extraction extractDbMigration(String workspaceId, Spec spec) {
        if (spec.tables.isEmpty()) {
            return Extraction.empty("DB_MIGRATION",
                    "整库迁移未显式列出表清单，无法在不猜测的前提下展开列级血缘");
        }
        List<String> warnings = new ArrayList<>();
        List<EdgeDraft> edges = new ArrayList<>();
        for (String table : spec.tables) {
            Optional<String> datasetId = resolver.resolveDatasetId(workspaceId, spec.sourceDs,
                    spec.sourceDb, spec.sourceSchema, table);
            if (datasetId.isEmpty()) {
                warnings.add("源表 " + table + " 尚未进入数据目录，已跳过列级血缘");
                continue;
            }
            List<String> columns = resolver.listColumnNames(datasetId.get());
            if (columns.isEmpty()) {
                warnings.add("源表 " + table + " 在数据目录中没有字段，已跳过");
                continue;
            }
            String targetTable = TargetNaming.table(table, spec.prefix, spec.suffix, spec.lowercase);
            for (String column : columns) {
                NodeRef from = resolver.resolveNodeRef(workspaceId, spec.sourceDs, spec.sourceDb,
                        spec.sourceSchema, table, column);
                NodeRef to = resolver.resolveNodeRef(workspaceId, spec.targetDs, spec.targetDb,
                        spec.targetSchema, targetTable,
                        TargetNaming.column(column, spec.lowercase));
                if (Objects.equals(from.qualifiedName(), to.qualifiedName())) {
                    continue;
                }
                edges.add(new EdgeDraft(from, to, RELATION_COPY, DEPENDENCY_DIRECT, null, 1.0,
                        ORIGIN, false, null));
            }
        }
        return new Extraction("DB_MIGRATION", edges, warnings);
    }

    // ── 规则 ────────────────────────────────────────────────────────────

    private Map<String, RuleInfo> loadRules(String workspaceId, Map<String, List<String>> fieldRules) {
        Set<String> ids = new LinkedHashSet<>();
        fieldRules.values().forEach(ids::addAll);
        ids.removeIf(StructuredJobLineageExtractor::isMaskingToken);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, RuleInfo> loaded = ruleLookup.load(workspaceId, ids);
        return loaded == null ? Map.of() : loaded;
    }

    /** 规则是否属于脱敏 / 哈希类，是则返回证据串，否则 null。 */
    static String maskingEvidence(RuleInfo rule) {
        if (rule == null || rule.kind() == null) {
            return null;
        }
        String kind = rule.kind().toUpperCase(Locale.ROOT);
        if ("MASK".equals(kind)) {
            String mode = rule.params() == null ? null : rule.params().get("mode");
            // PARTIAL / HASH / FIXED 三种模式都不可还原，一律视为脱敏阻断。
            String normalizedMode = isBlank(mode) ? "PARTIAL" : mode.trim().toUpperCase(Locale.ROOT);
            return "rule:" + rule.id() + "(MASK/" + normalizedMode + ")";
        }
        return MASKING_TOKENS.contains(kind) ? "rule:" + rule.id() + "(" + kind + ")" : null;
    }

    private static String describe(RuleInfo rule) {
        StringBuilder sb = new StringBuilder(rule.kind() == null ? "RULE" : rule.kind());
        if (rule.params() != null && !rule.params().isEmpty()) {
            List<String> pairs = new ArrayList<>();
            rule.params().forEach((k, v) -> {
                // 凭据 ID 只是引用，但没有必要把它带进血缘边。
                if (!CREDENTIAL_PARAM.matcher(k).matches() && !isBlank(v)) {
                    pairs.add(k + "=" + v);
                }
            });
            if (!pairs.isEmpty()) {
                sb.append('(').append(String.join(",", pairs)).append(')');
            }
        }
        return sb.toString();
    }

    private static boolean isMaskingToken(String token) {
        return token != null && MASKING_TOKENS.contains(token.trim().toUpperCase(Locale.ROOT));
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    /** {@code file://{source}/{field}}：source = 数据源 ID + 文件路径 / 接口路径。 */
    private static String externalColumnName(Spec spec, String field) {
        StringBuilder sb = new StringBuilder("file://");
        String ds = trimSlashes(spec.sourceDs);
        String path = trimSlashes(spec.sourcePath);
        if (!ds.isEmpty()) {
            sb.append(ds);
        }
        if (!path.isEmpty()) {
            if (!ds.isEmpty()) {
                sb.append('/');
            }
            sb.append(path);
        }
        if (ds.isEmpty() && path.isEmpty()) {
            sb.append("unknown");
        }
        return sb.append('/').append(field.trim()).toString();
    }

    private static String trimSlashes(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        int start = 0;
        int end = v.length();
        while (start < end && v.charAt(start) == '/') {
            start++;
        }
        while (end > start && v.charAt(end - 1) == '/') {
            end--;
        }
        return v.substring(start, end);
    }

    static String normalizeType(String jobType) {
        if (jobType == null) {
            return "";
        }
        String letters = jobType.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
        return switch (letters) {
            case "OFFLINESYNC" -> "OFFLINE_SYNC";
            case "DBMIGRATION" -> "DB_MIGRATION";
            case "FILEPARSE" -> "FILE_PARSE";
            case "APIPARSE" -> "API_PARSE";
            case "ETLPIPELINE" -> "ETL_PIPELINE";
            default -> letters;
        };
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 对 config(扁平键)与物理计划(嵌套 source/target 段)的统一读取。
     * 两者同时存在时以计划为准 —— 计划是编译后的事实。
     */
    private static final class Spec {
        String sourceDs;
        String sourceDb;
        String sourceSchema;
        String sourceTable;
        String sourcePath;
        String targetDs;
        String targetDb;
        String targetSchema;
        String targetTable;
        Map<String, String> fieldMappings = Map.of();
        Map<String, List<String>> fieldRules = Map.of();
        List<String> tables = List.of();
        String prefix;
        String suffix;
        boolean lowercase;

        static Spec of(Map<String, Object> root) {
            Spec spec = new Spec();
            Map<String, Object> source = section(root, "source");
            Map<String, Object> target = section(root, "target");
            Map<String, Object> naming = section(root, "naming");

            spec.sourceDs = pick(source, "dataSourceId", root, "sourceDataSourceId");
            spec.sourceDb = pick(source, "database", root, "sourceDatabase");
            spec.sourceSchema = pick(source, "schema", root, "sourceSchema");
            spec.sourceTable = pick(source, "table", root, "sourceTable");
            spec.sourcePath = pick(source, "path", root, "path");
            spec.targetDs = pick(target, "dataSourceId", root, "targetDataSourceId");
            spec.targetDb = pick(target, "database", root, "targetDatabase");
            spec.targetSchema = pick(target, "schema", root, "targetSchema");
            spec.targetTable = pick(target, "table", root, "targetTable");

            spec.fieldMappings = stringMap(root.get("fieldMappings"));
            spec.fieldRules = ruleMap(root.get("fieldRules"));

            Object tables = source.containsKey("tables") ? source.get("tables") : root.get("tables");
            spec.tables = stringList(tables);
            spec.prefix = pick(naming, "prefix", root, "tablePrefix");
            spec.suffix = pick(naming, "suffix", root, "tableSuffix");
            Object lower = naming.containsKey("lowercase") ? naming.get("lowercase")
                    : root.get("lowercaseNames");
            spec.lowercase = lower != null && Boolean.parseBoolean(String.valueOf(lower));
            return spec;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> section(Map<String, Object> root, String key) {
            Object value = root.get(key);
            return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }

        private static String pick(Map<String, Object> nested, String nestedKey,
                                   Map<String, Object> flat, String flatKey) {
            Object value = nested.get(nestedKey);
            if (value == null) {
                value = flat.get(flatKey);
            }
            return value == null ? null : value.toString();
        }

        private static Map<String, String> stringMap(Object raw) {
            if (!(raw instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, String> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
            return result;
        }

        private static Map<String, List<String>> ruleMap(Object raw) {
            if (!(raw instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, List<String>> result = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                List<String> ids = stringList(v);
                if (!ids.isEmpty()) {
                    result.put(String.valueOf(k), ids);
                }
            });
            return result;
        }

        private static List<String> stringList(Object raw) {
            if (!(raw instanceof Collection<?> list)) {
                return List.of();
            }
            return list.stream().filter(Objects::nonNull).map(String::valueOf)
                    .filter(s -> !s.isBlank()).toList();
        }
    }
}
