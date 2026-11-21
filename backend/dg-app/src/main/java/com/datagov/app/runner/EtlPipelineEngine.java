package com.datagov.app.runner;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.data.spi.query.ReadOnlySqlGuard;
import com.datagov.runtime.engine.JobRunner;
import com.datagov.runtime.etl.EtlBadRowLimitExceededException;
import com.datagov.runtime.etl.EtlRunSummary;
import com.datagov.runtime.spi.ExecutionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 轻量级 ETL 管道内核(lightweight-etl-pipeline Phase 1):隔离 DuckDB 会话 + 抽取 + 转换 + 落地。
 *
 * <p>被 {@link EtlPipelineRunner}(正式执行)与 {@code EtlPipelineController}(预览)共用,
 * 保证「预览看到的」与「执行跑出来的」是同一套语义。
 *
 * <h3>安全模型(顺序有意义)</h3>
 * <ol>
 *   <li>每次运行一条全新的 {@code jdbc:duckdb:} 内存连接,运行结束即销毁,互不可见;</li>
 *   <li><b>先抽取、后锁定</b>:SOURCE_JDBC 由 Java 侧经 {@link EtlJdbcGateway} 读出、写入 DuckDB 临时表;
 *       SOURCE_FILE 经 {@link EtlObjectStore} 取成临时文件后 {@code read_csv / read_json_auto / read_parquet}
 *       进临时表,SOURCE_API 经 {@link EtlApiFetcher} 取 JSON 后逐行写入(都发生在锁定之前);
 *       之后执行 {@code SET enable_external_access=false} 与 {@code SET lock_configuration=true},
 *       此后用户 SQL 既读不了本地文件 / 网络 (read_csv、httpfs、ATTACH 全部失效),也改不回配置;</li>
 *   <li>转换步骤 SQL 必须通过 {@link ReadOnlySqlGuard}(单条只读查询),再包进
 *       {@code CREATE TEMP VIEW},永不直接执行用户给的 DDL/DML;</li>
 *   <li>源端连接只读;Sink 写目标端沿用 {@link TableCopier} 的批量提交与幂等语义。</li>
 * </ol>
 *
 * <h3>计划形状(etl.ir/v1)</h3>
 * <pre>
 * { schemaVersion: "etl.ir/v1", kind: "ETL_PIPELINE", jobId?,
 *   nodes: [{ id, type: SOURCE_JDBC|SOURCE_FILE|SOURCE_API|SINK_JDBC|SINK_PARQUET|FILTER|SQL..., config?: {...} }],
 *   edges: [{ from, to }],
 *   duckdbSteps: [{ nodeId, sql }],      // 按序执行;sql 用 sanitized 节点 id 引用上游
 *   watermarkSpecs: [{ nodeId, column, type }],
 *   lineageEdges: [...], sink: {...} }
 * </pre>
 * 节点 id 在 DuckDB 里的名字见 {@link #tempName(String)}(编译器生成 SQL 时必须用同一规则)。
 */
@Component
public class EtlPipelineEngine {

    private static final Logger log = LoggerFactory.getLogger(EtlPipelineEngine.class);

    public static final String SCHEMA_VERSION = "etl.ir/v1";
    public static final String KIND = "ETL_PIPELINE";
    public static final String SOURCE_JDBC = "SOURCE_JDBC";
    public static final String SINK_JDBC = "SINK_JDBC";
    public static final String SOURCE_FILE = "SOURCE_FILE";
    public static final String SOURCE_API = "SOURCE_API";
    public static final String SINK_PARQUET = "SINK_PARQUET";

    /** 引擎会在锁定前抽取的源节点类型(按计划里的节点顺序)。 */
    private static final Set<String> SOURCE_TYPES = Set.of(SOURCE_JDBC, SOURCE_FILE, SOURCE_API);
    private static final Set<String> SINK_TYPES = Set.of(SINK_JDBC, SINK_PARQUET);

    private static final Set<String> STEP_TYPES = Set.of("FILTER", "SQL", "TRANSFORM_SQL", "TRANSFORM");
    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final int EXTRACT_BATCH = 5_000;
    private static final int PROGRESS_INTERVAL = 5_000;
    private static final Pattern MEMORY_LIMIT = Pattern.compile("^\\d{1,6}\\s?(MB|GB)$",
            Pattern.CASE_INSENSITIVE);

    private final EtlJdbcGateway gateway;
    private final ObjectProvider<EtlWatermarkService> watermarks;
    private final ObjectProvider<EtlCodesetResolver> codesetResolver;
    private final ObjectProvider<EtlObjectStore> objectStore;
    private final ObjectProvider<EtlApiFetcher> apiFetcher;

    @Autowired
    public EtlPipelineEngine(EtlJdbcGateway gateway,
                             ObjectProvider<EtlWatermarkService> watermarks,
                             ObjectProvider<EtlCodesetResolver> codesetResolver,
                             ObjectProvider<EtlObjectStore> objectStore,
                             ObjectProvider<EtlApiFetcher> apiFetcher) {
        this.gateway = gateway;
        this.watermarks = watermarks;
        this.codesetResolver = codesetResolver;
        this.objectStore = objectStore;
        this.apiFetcher = apiFetcher;
    }

    /** 只有 JDBC 源 / 目标的引擎(没有对象存储与接口取数能力)。 */
    public EtlPipelineEngine(EtlJdbcGateway gateway,
                             ObjectProvider<EtlWatermarkService> watermarks,
                             ObjectProvider<EtlCodesetResolver> codesetResolver) {
        this(gateway, watermarks, codesetResolver,
                new StaticListableBeanFactory().getBeanProvider(EtlObjectStore.class),
                new StaticListableBeanFactory().getBeanProvider(EtlApiFetcher.class));
    }

    // ── 计划模型 ────────────────────────────────────────────────────────

    public record Node(String id, String type, Map<String, Object> config) {
        public String viewName() {
            return tempName(id);
        }

        String str(String key) {
            Object v = config.get(key);
            return v == null || v.toString().isBlank() ? null : v.toString();
        }
    }

    public record Edge(String from, String to) {
    }

    public record Step(String nodeId, String sql) {
    }

    public record WatermarkSpec(String nodeId, String column, String type) {
    }

    /**
     * 坏行策略(来自计划的 {@code badRowPolicy},缺省 FAIL_FAST)。
     *
     * @param deadLetter    ROUTE_TO_DEAD_LETTER:行本体路由到死信存储
     * @param captureValues 死信里能否保留行内容(编译器确认所有列都已识别敏感级别时才为 true)
     * @param maskColumns   必须掩码的列名(小写)
     */
    public record BadRowPolicy(String mode, Long maxBadRows, Double maxBadRatio, boolean deadLetter,
                               boolean captureValues, Set<String> maskColumns) {

        public static final String FAIL_FAST = "FAIL_FAST";
        public static final String SKIP_BAD_ROWS = "SKIP_BAD_ROWS";
        public static final String ROUTE_TO_DEAD_LETTER = "ROUTE_TO_DEAD_LETTER";

        public static BadRowPolicy failFast() {
            return new BadRowPolicy(FAIL_FAST, null, null, false, false, Set.of());
        }

        public boolean tolerant() {
            return !FAIL_FAST.equals(mode);
        }
    }

    /** 码表映射节点的执行规格(编译器写入计划的 {@code codesets})。 */
    public record CodesetSpec(String nodeId, String table, String codesetId, String codesetRef,
                              String inputNodeId, String column, String outputColumn, String mapTo,
                              String onMiss, boolean ignoreCase, boolean sampleValues) {

        /** 与编译器生成 SQL 时同一个键表达式。 */
        String keyExpr() {
            String col = "t.\"" + column.replace("\"", "\"\"") + "\"";
            return ignoreCase ? "lower(CAST(" + col + " AS VARCHAR))" : "CAST(" + col + " AS VARCHAR)";
        }

        /** 输入表里「有值但码表里没有」的行的 FROM/WHERE 片段。 */
        String missFrom() {
            String col = "t.\"" + column.replace("\"", "\"\"") + "\"";
            return " FROM \"" + tempName(inputNodeId) + "\" AS t LEFT JOIN \"" + table
                    + "\" AS cs ON " + keyExpr() + " = cs.\"code\" WHERE " + col
                    + " IS NOT NULL AND cs.\"code\" IS NULL";
        }
    }

    public record Plan(String jobId, List<Node> nodes, List<Edge> edges, List<Step> steps,
                       Map<String, WatermarkSpec> watermarks, BadRowPolicy badRowPolicy,
                       List<CodesetSpec> codesets) {

        public Optional<Node> node(String id) {
            return nodes.stream().filter(n -> n.id().equals(id)).findFirst();
        }

        public List<Node> byType(String type) {
            return nodes.stream().filter(n -> n.type().equals(type)).toList();
        }
    }

    /**
     * 运行限额。
     *
     * @param maxSourceRows 每个源节点最多抽取多少行,0 = 不限(正式执行);预览用来封顶
     * @param deadlineNanos {@link System#nanoTime()} 基准的截止时刻,0 = 不限
     */
    public record Limits(long maxSourceRows, String memoryLimit, int threads, long deadlineNanos) {

        public static Limits execution() {
            return new Limits(0, "2GB", 4, 0);
        }

        public static Limits preview(long maxSourceRows, String memoryLimit, int timeoutSeconds) {
            return new Limits(maxSourceRows, memoryLimit, 2,
                    System.nanoTime() + timeoutSeconds * 1_000_000_000L);
        }
    }

    public record PreviewColumn(String name, String type) {
    }

    public record PreviewResult(String nodeId, List<PreviewColumn> columns,
                                List<Map<String, Object>> rows, boolean truncated) {
    }

    // ── 解析 ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Plan parse(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("ETL 计划为空");
        }
        if (isCompiledPlan(raw)) {
            raw = fromCompiledPlan(raw);
        }
        Object schema = raw.get("schemaVersion");
        if (schema != null && !SCHEMA_VERSION.equals(schema.toString())) {
            throw new IllegalArgumentException("不支持的 ETL IR 版本: " + schema);
        }
        Object kind = raw.get("kind");
        if (kind != null && !KIND.equals(kind.toString())) {
            throw new IllegalArgumentException("计划 kind 不是 ETL_PIPELINE: " + kind);
        }

        List<Node> nodes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> seenViews = new LinkedHashSet<>();
        for (Object item : list(raw.get("nodes"))) {
            if (!(item instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("nodes 中含非对象元素");
            }
            Map<String, Object> map = (Map<String, Object>) m;
            String id = text(map.get("id"));
            String type = text(map.get("type"));
            if (id == null || type == null) {
                throw new IllegalArgumentException("节点缺少 id 或 type");
            }
            if (!seen.add(id) || !seenViews.add(tempName(id))) {
                throw new IllegalArgumentException("节点 id 重复(或清洗后同名): " + id);
            }
            Map<String, Object> config = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (!"id".equals(k) && !"type".equals(k) && !"config".equals(k)) {
                    config.put(k, v);
                }
            });
            if (map.get("config") instanceof Map<?, ?> nested) {
                ((Map<String, Object>) nested).forEach(config::put);
            }
            nodes.add(new Node(id, type.toUpperCase(Locale.ROOT), config));
        }
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("ETL 计划没有节点");
        }

        List<Edge> edges = new ArrayList<>();
        for (Object item : list(raw.get("edges"))) {
            if (item instanceof Map<?, ?> m) {
                String from = text(firstNonNull(m.get("from"), m.get("source")));
                String to = text(firstNonNull(m.get("to"), m.get("target")));
                if (from != null && to != null) {
                    edges.add(new Edge(from, to));
                }
            }
        }

        List<Step> steps = new ArrayList<>();
        for (Object item : list(raw.get("duckdbSteps"))) {
            if (item instanceof Map<?, ?> m) {
                String nodeId = text(m.get("nodeId"));
                String sql = text(m.get("sql"));
                if (nodeId == null || sql == null) {
                    throw new IllegalArgumentException("duckdbSteps 含缺少 nodeId 或 sql 的步骤");
                }
                steps.add(new Step(nodeId, sql));
            }
        }
        if (steps.isEmpty()) {
            steps.addAll(deriveSteps(nodes, edges));
        }

        Map<String, WatermarkSpec> marks = new LinkedHashMap<>();
        for (Object item : list(raw.get("watermarkSpecs"))) {
            if (item instanceof Map<?, ?> m) {
                String nodeId = text(firstNonNull(m.get("nodeId"), m.get("sourceNodeId")));
                String column = text(firstNonNull(m.get("column"), m.get("watermarkColumn")));
                String type = text(firstNonNull(m.get("type"), m.get("watermarkType")));
                if (nodeId != null && column != null) {
                    marks.put(nodeId, new WatermarkSpec(nodeId, column,
                            type == null ? "STRING" : type.toUpperCase(Locale.ROOT)));
                }
            }
        }
        String jobId = text(firstNonNull(raw.get("jobId"), raw.get("jobDefinitionId")));
        return new Plan(jobId, List.copyOf(nodes), List.copyOf(edges), List.copyOf(steps),
                Map.copyOf(marks), parseBadRowPolicy(raw), parseCodesets(raw));
    }

    // ── 编译产物适配 ────────────────────────────────────────────────────

    private static final Pattern VIEW_PREFIX = Pattern.compile(
            "^\\s*CREATE\\s+OR\\s+REPLACE\\s+TEMP\\s+VIEW\\s+\"(?:[^\"]|\"\")+\"\\s+AS\\s+",
            Pattern.CASE_INSENSITIVE);

    /**
     * 是否是 {@code EtlPipelineCompiler} 的编译产物(含 {@code executionOrder},
     * 且 duckdbSteps 带 {@code step: LOAD|SQL|SINK})。手写的 Phase 1 形状计划不满足,继续走原解析。
     */
    private static boolean isCompiledPlan(Map<String, Object> raw) {
        if (raw.get("executionOrder") == null || !(raw.get("duckdbSteps") instanceof List<?> steps)) {
            return false;
        }
        return steps.stream().anyMatch(i -> i instanceof Map<?, ?> m && m.get("step") != null);
    }

    /**
     * 编译产物 → 引擎认识的形状。节点来自 duckdbSteps(已按 executionOrder 且只含对 Sink 有贡献的节点),
     * 边来自 {@code ir.edges}。引擎暂不支持的源 / 目标类型在这里就明确拒绝,
     * 而不是等到视图创建时给一个让人摸不着头脑的「表不存在」。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> fromCompiledPlan(Map<String, Object> raw) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Object item : list(raw.get("duckdbSteps"))) {
            Map<String, Object> step = (Map<String, Object>) item;
            String nodeId = String.valueOf(step.get("nodeId"));
            String kind = String.valueOf(step.get("step"));
            switch (kind) {
                case "LOAD" -> {
                    Map<String, Object> source = (Map<String, Object>) step.get("source");
                    String sourceType = source == null ? null : String.valueOf(source.get("type"));
                    String nodeType = switch (sourceType == null ? "" : sourceType) {
                        case "JDBC" -> SOURCE_JDBC;
                        case "FILE" -> SOURCE_FILE;
                        case "API" -> SOURCE_API;
                        default -> throw new IllegalArgumentException("DuckDB 引擎不支持源类型 " + sourceType
                                + "(节点 " + nodeId + "),支持 JDBC / FILE / API");
                    };
                    Map<String, Object> config = new LinkedHashMap<>(source);
                    config.remove("type");
                    nodes.add(node(nodeId, nodeType, config));
                }
                case "SQL" -> {
                    String sql = String.valueOf(step.get("sql"));
                    Matcher m = VIEW_PREFIX.matcher(sql);
                    if (!m.find()) {
                        throw new IllegalArgumentException("节点 " + nodeId + " 的 SQL 不是 CREATE TEMP VIEW 形状");
                    }
                    nodes.add(node(nodeId, String.valueOf(step.getOrDefault("kind", "TRANSFORM_SQL")),
                            new LinkedHashMap<>()));
                    Map<String, Object> legacy = new LinkedHashMap<>();
                    legacy.put("nodeId", nodeId);
                    legacy.put("sql", sql.substring(m.end()));
                    steps.add(legacy);
                }
                case "SINK" -> {
                    Map<String, Object> sink = (Map<String, Object>) step.get("sink");
                    String sinkType = sink == null ? null : String.valueOf(sink.get("type"));
                    if (!"JDBC".equals(sinkType) && !"PARQUET".equals(sinkType)) {
                        throw new IllegalArgumentException("DuckDB 引擎不支持目标类型 " + sinkType
                                + "(节点 " + nodeId + "),支持 JDBC / PARQUET");
                    }
                    Map<String, Object> config = new LinkedHashMap<>(sink);
                    config.remove("type");
                    if (sink.get("columnMapping") != null) {
                        config.put("columnMappings", sink.get("columnMapping"));
                    }
                    config.put("inputNodeId", step.get("input"));
                    if ("PARQUET".equals(sinkType)) {
                        // 这两项在写出时才会碰到,但属于「计划本身就写不出来」,在解析阶段就拒绝
                        if (sink.get("partitionBy") instanceof Collection<?> pb && !pb.isEmpty()) {
                            throw new IllegalArgumentException("SINK_PARQUET 暂不支持 partitionBy(节点 "
                                    + nodeId + "):分区写出会产生多个文件,目前只写单个 Parquet 文件");
                        }
                        String mode = sink.get("writeMode") == null ? "OVERWRITE"
                                : sink.get("writeMode").toString().toUpperCase(Locale.ROOT);
                        if (!"OVERWRITE".equals(mode)) {
                            throw new IllegalArgumentException("SINK_PARQUET 只支持 writeMode=OVERWRITE(节点 "
                                    + nodeId + "),实际 " + mode);
                        }
                    }
                    nodes.add(node(nodeId, "PARQUET".equals(sinkType) ? SINK_PARQUET : SINK_JDBC, config));
                }
                default -> throw new IllegalArgumentException("未知的计划步骤类型: " + kind + "(节点 " + nodeId + ")");
            }
        }
        Map<String, Object> ir = raw.get("ir") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> adapted = new LinkedHashMap<>();
        adapted.put("schemaVersion", raw.getOrDefault("irVersion", SCHEMA_VERSION));
        adapted.put("kind", KIND);
        adapted.put("jobId", raw.get("jobId"));
        adapted.put("nodes", nodes);
        adapted.put("edges", ir.getOrDefault("edges", List.of()));
        adapted.put("duckdbSteps", steps);
        adapted.put("watermarkSpecs", raw.getOrDefault("watermarkSpecs", List.of()));
        adapted.put("badRowPolicy", raw.get("badRowPolicy"));
        adapted.put("errorPolicy", raw.get("errorPolicy"));
        adapted.put("codesets", raw.getOrDefault("codesets", List.of()));
        return adapted;
    }

    private static Map<String, Object> node(String id, String type, Map<String, Object> config) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("config", config);
        return node;
    }

    @SuppressWarnings("unchecked")
    private static BadRowPolicy parseBadRowPolicy(Map<String, Object> raw) {
        Object policyRaw = firstNonNull(raw.get("badRowPolicy"), raw.get("errorPolicy"));
        if (!(policyRaw instanceof Map<?, ?> policy)) {
            return BadRowPolicy.failFast();
        }
        Map<String, Object> map = (Map<String, Object>) policy;
        String mode = text(map.get("mode")) == null ? BadRowPolicy.FAIL_FAST
                : text(map.get("mode")).toUpperCase(Locale.ROOT);
        if ("SKIP".equals(mode)) {
            mode = BadRowPolicy.SKIP_BAD_ROWS;
        }
        if (!Set.of(BadRowPolicy.FAIL_FAST, BadRowPolicy.SKIP_BAD_ROWS,
                BadRowPolicy.ROUTE_TO_DEAD_LETTER).contains(mode)) {
            throw new IllegalArgumentException("不支持的 errorPolicy.mode: " + mode);
        }
        Long maxRows = map.get("maxBadRows") instanceof Number n ? Long.valueOf(n.longValue()) : null;
        Double maxRatio = map.get("maxBadRatio") instanceof Number n ? Double.valueOf(n.doubleValue()) : null;
        boolean deadLetter = BadRowPolicy.ROUTE_TO_DEAD_LETTER.equals(mode);
        // 没有 badRowPolicy(只有 errorPolicy)时拿不到编译器的敏感列结论,保守:不保留行内容
        boolean capture = Boolean.TRUE.equals(map.get("captureValues"));
        Set<String> mask = new HashSet<>();
        if (map.get("maskColumns") instanceof Collection<?> cols) {
            cols.stream().filter(Objects::nonNull)
                    .forEach(c -> mask.add(String.valueOf(c).toLowerCase(Locale.ROOT)));
        }
        return new BadRowPolicy(mode, maxRows, maxRatio, deadLetter, capture, Set.copyOf(mask));
    }

    @SuppressWarnings("unchecked")
    private static List<CodesetSpec> parseCodesets(Map<String, Object> raw) {
        List<CodesetSpec> specs = new ArrayList<>();
        for (Object item : list(raw.get("codesets"))) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) m;
            String table = text(map.get("table"));
            if (table == null || !SAFE_IDENT.matcher(table).matches()) {
                throw new IllegalArgumentException("码表节点的表名不合法: " + table);
            }
            String column = text(map.get("column"));
            String codesetId = text(map.get("codesetId"));
            if (column == null || codesetId == null || text(map.get("nodeId")) == null
                    || text(map.get("inputNodeId")) == null) {
                throw new IllegalArgumentException("码表节点规格不完整: " + text(map.get("nodeId")));
            }
            specs.add(new CodesetSpec(text(map.get("nodeId")), table, codesetId, text(map.get("codesetRef")),
                    text(map.get("inputNodeId")), column,
                    text(map.get("outputColumn")) == null ? column : text(map.get("outputColumn")),
                    text(map.get("mapTo")) == null ? "CANONICAL_VALUE" : text(map.get("mapTo")),
                    text(map.get("onMiss")) == null ? "KEEP_ORIGINAL" : text(map.get("onMiss")),
                    Boolean.TRUE.equals(map.get("ignoreCase")), Boolean.TRUE.equals(map.get("sampleValues"))));
        }
        return List.copyOf(specs);
    }

    /**
     * 计划里没有 duckdbSteps 时的最小回退(Phase 1 允许没有编译器):FILTER 节点用
     * {@code condition/where},SQL 类节点用 {@code sql}。按 nodes 顺序产出,上游取 edges 里指向它的第一条。
     */
    private List<Step> deriveSteps(List<Node> nodes, List<Edge> edges) {
        List<Step> steps = new ArrayList<>();
        for (Node node : nodes) {
            if (!STEP_TYPES.contains(node.type())) {
                continue;
            }
            String sql = node.str("sql");
            if (sql == null) {
                String condition = node.str("condition") != null
                        ? node.str("condition") : node.str("where");
                String upstream = edges.stream().filter(e -> e.to().equals(node.id()))
                        .map(Edge::from).findFirst().orElse(null);
                if (condition == null || upstream == null) {
                    throw new IllegalArgumentException(
                            "节点 " + node.id() + " 没有可执行的 SQL(缺 sql,或缺 condition/上游边)");
                }
                sql = "SELECT * FROM \"" + tempName(upstream) + "\" WHERE " + condition;
            }
            steps.add(new Step(node.id(), sql));
        }
        return steps;
    }

    /** 节点 id → DuckDB 对象名:非 [A-Za-z0-9_] 一律换成下划线,数字开头加 {@code n_} 前缀。 */
    public static String tempName(String nodeId) {
        String cleaned = nodeId.replaceAll("[^A-Za-z0-9_]", "_");
        if (cleaned.isEmpty() || Character.isDigit(cleaned.charAt(0))) {
            cleaned = "n_" + cleaned;
        }
        return cleaned;
    }

    /** Sink 的上游关系:显式 inputNodeId → 指向 sink 的边 → 最后一个转换步骤 → 唯一的源节点。 */
    public String sinkInput(Plan plan, Node sink) {
        String explicit = sink.str("inputNodeId");
        if (explicit != null) {
            return explicit;
        }
        Optional<String> byEdge = plan.edges().stream().filter(e -> e.to().equals(sink.id()))
                .map(Edge::from).findFirst();
        if (byEdge.isPresent()) {
            return byEdge.get();
        }
        if (!plan.steps().isEmpty()) {
            return plan.steps().get(plan.steps().size() - 1).nodeId();
        }
        List<Node> sources = plan.nodes().stream().filter(n -> SOURCE_TYPES.contains(n.type())).toList();
        if (sources.size() == 1) {
            return sources.get(0).id();
        }
        throw new IllegalArgumentException("无法确定 Sink " + sink.id() + " 的上游节点");
    }

    // ── 会话 ────────────────────────────────────────────────────────────

    /** 一次运行的 DuckDB 会话。关闭即销毁内存库。 */
    public static final class Session implements AutoCloseable {
        private final Connection duck;
        private final Plan plan;
        private final Map<String, String> watermarkMax = new LinkedHashMap<>();
        private final Map<String, Long> codesetMisses = new LinkedHashMap<>();
        private final EtlBadRowLedger ledger;
        private String memoryLimit = "2GB";
        private long rowsRead;
        private long rowsWritten;
        /** 进入 Sink 的行数:坏行比例的分母(再加上码表阶段剔除的坏行)。 */
        private long rowsSeenBySink;

        private Session(Connection duck, Plan plan) {
            this.duck = duck;
            this.plan = plan;
            BadRowPolicy policy = plan.badRowPolicy();
            this.ledger = new EtlBadRowLedger(policy.maskColumns(), policy.captureValues(), policy.deadLetter());
        }

        /** 本次运行的坏行台账。运行失败时调用方仍应在 finally 里取走它的摘要。 */
        public EtlBadRowLedger ledger() {
            return ledger;
        }

        /** 码表映射节点 id → 未命中行数(与 onMiss 策略无关)。 */
        public Map<String, Long> codesetMisses() {
            return codesetMisses;
        }

        public long rowsWritten() {
            return rowsWritten;
        }

        public Connection duck() {
            return duck;
        }

        public Plan plan() {
            return plan;
        }

        public long rowsRead() {
            return rowsRead;
        }

        /** 本次抽取观察到的各源节点水位列最大值(节点 id → 字符串值)。 */
        public Map<String, String> watermarkMax() {
            return watermarkMax;
        }

        @Override
        public void close() {
            try {
                duck.close();
            } catch (SQLException e) {
                log.warn("关闭 DuckDB 会话失败", e);
            }
        }
    }

    /**
     * 建立会话:抽取全部源 → 锁定 DuckDB → 建转换视图。
     *
     * @return 调用方负责 close
     */
    public Session open(String workspaceId, Plan plan, Limits limits, BooleanSupplier canceled,
                        java.util.function.LongConsumer onRead) throws Exception {
        Connection duck = DriverManager.getConnection("jdbc:duckdb:");
        Session session = new Session(duck, plan);
        try {
            try (Statement cfg = duck.createStatement()) {
                String memory = limits.memoryLimit();
                if (memory == null || !MEMORY_LIMIT.matcher(memory).matches()) {
                    throw new IllegalArgumentException("非法的 memory_limit: " + memory);
                }
                cfg.execute("SET memory_limit = '" + memory + "'");
                cfg.execute("SET threads TO " + Math.max(1, limits.threads()));
                session.memoryLimit = memory;
            }
            for (Node source : plan.nodes()) {
                switch (source.type()) {
                    case SOURCE_JDBC -> extractSource(workspaceId, session, source, limits, canceled, onRead);
                    case SOURCE_FILE -> extractFile(workspaceId, session, source, limits, onRead);
                    case SOURCE_API -> extractApi(workspaceId, session, source, limits, onRead);
                    default -> {
                        // 转换 / Sink 节点不在这里处理
                    }
                }
            }
            // 码表同样要在锁定前装进会话:此后用户 SQL 只能在内存临时表上算
            for (CodesetSpec codeset : plan.codesets()) {
                loadCodeset(workspaceId, session, codeset);
            }
            lockDown(duck);
            for (Step step : plan.steps()) {
                createView(duck, step);
            }
            return session;
        } catch (Exception e) {
            session.close();
            throw e;
        }
    }

    /** 抽取完成后收紧:用户 SQL 从这一刻起只能在内存临时表上算。 */
    private static void lockDown(Connection duck) throws SQLException {
        try (Statement st = duck.createStatement()) {
            st.execute("SET enable_external_access = false");
            // 锁住配置:否则一条 SET enable_external_access=true 就能解开上一行
            st.execute("SET lock_configuration = true");
        }
    }

    private static void createView(Connection duck, Step step) throws SQLException {
        String safeSql = ReadOnlySqlGuard.requireReadOnly(step.sql());
        String view = tempName(step.nodeId());
        try (Statement st = duck.createStatement()) {
            st.execute("CREATE OR REPLACE TEMP VIEW \"" + view + "\" AS " + safeSql);
        }
    }

    // ── 码表 ────────────────────────────────────────────────────────────

    private void loadCodeset(String workspaceId, Session session, CodesetSpec spec) throws SQLException {
        EtlCodesetResolver resolver = codesetResolver.getIfAvailable();
        if (resolver == null) {
            throw new IllegalStateException("计划含码表映射节点 " + spec.nodeId() + ",但没有可用的码表解析器");
        }
        Map<String, String> mapping = resolver.resolve(workspaceId, spec.codesetId(), spec.mapTo(),
                spec.ignoreCase());
        try (Statement st = session.duck().createStatement()) {
            st.execute("CREATE TEMP TABLE \"" + spec.table() + "\" (\"code\" VARCHAR PRIMARY KEY, \"std\" VARCHAR)");
        }
        try (PreparedStatement insert = session.duck().prepareStatement(
                "INSERT INTO \"" + spec.table() + "\" VALUES (?, ?)")) {
            int pending = 0;
            for (Map.Entry<String, String> e : mapping.entrySet()) {
                insert.setString(1, e.getKey());
                insert.setString(2, e.getValue());
                insert.addBatch();
                if (++pending >= EXTRACT_BATCH) {
                    insert.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                insert.executeBatch();
            }
        }
        log.info("ETL 码表节点 {} 已装载 codeset={} 条目={}", spec.nodeId(), spec.codesetRef(), mapping.size());
    }

    /**
     * 统计各码表节点的未命中行数,并按坏行策略处理 {@code onMiss=BAD_ROW} 的那些
     * (主流里这些行已被节点 SQL 剔除;这里负责计数、入台账、按 FAIL_FAST / 阈值判失败)。
     *
     * <p>必须在 {@link #open} 之后、写 Sink 之前调用。未命中数对所有 onMiss 策略一律统计 ——
     * KEEP_ORIGINAL / NULL / DEFAULT 下行不会被剔除,但「有多少值没标准化」本身就是治理信号。
     * 样例代码值只在编译器声明该列无敏感标签时才进台账。
     */
    public void analyzeCodesets(Session session) throws SQLException {
        BadRowPolicy policy = session.plan().badRowPolicy();
        for (CodesetSpec spec : session.plan().codesets()) {
            long misses;
            try (Statement st = session.duck().createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*)" + spec.missFrom())) {
                misses = rs.next() ? rs.getLong(1) : 0;
            }
            session.codesetMisses.put(spec.nodeId(), misses);
            if (misses == 0) {
                continue;
            }
            log.warn("ETL 码表节点 {} 有 {} 行代码值未命中码表 {}(onMiss={})",
                    spec.nodeId(), misses, spec.codesetRef(), spec.onMiss());
            if (!"BAD_ROW".equals(spec.onMiss())) {
                continue;
            }
            boolean samplesAllowed = spec.sampleValues() && !session.ledger.isMasked(spec.column());
            List<String> codes = samplesAllowed ? distinctMissedCodes(session, spec) : List.of();
            List<Map<String, Object>> body = policy.deadLetter()
                    ? missedRows(session, spec, EtlBadRowLedger.MAX_BODY_ROWS) : List.of();
            session.ledger.addCodesetMisses(spec.nodeId(), spec.codesetRef(), spec.column(), misses, codes, body);

            if (!policy.tolerant()) {
                throw new EtlBadRowLimitExceededException("码表映射节点 " + spec.nodeId() + " 有 " + misses
                        + " 行代码值未命中码表 " + spec.codesetRef() + ",errorPolicy=FAIL_FAST 下整次执行失败",
                        summary(session, policy));
            }
            checkBadRowCount(session, policy);
        }
    }

    private static List<String> distinctMissedCodes(Session session, CodesetSpec spec) throws SQLException {
        List<String> codes = new ArrayList<>();
        try (Statement st = session.duck().createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT CAST(t.\"" + spec.column().replace("\"", "\"\"")
                     + "\" AS VARCHAR) AS c" + spec.missFrom() + " ORDER BY c LIMIT "
                     + EtlBadRowLedger.MAX_SAMPLES)) {
            while (rs.next()) {
                String code = rs.getString(1);
                codes.add(code != null && code.length() > 64 ? code.substring(0, 64) + "…" : code);
            }
        }
        return codes;
    }

    private static List<Map<String, Object>> missedRows(Session session, CodesetSpec spec, int limit)
            throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement st = session.duck().createStatement();
             ResultSet rs = st.executeQuery("SELECT t.*" + spec.missFrom() + " LIMIT " + limit)) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.put(md.getColumnLabel(i), jsonSafe(rs.getObject(i)));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    // ── 坏行阈值与统计 ──────────────────────────────────────────────────

    /** 统计摘要(只有计数与比例)。 */
    public EtlRunSummary summary(Session session, BadRowPolicy policy) {
        long bad = session.ledger.total();
        // 码表阶段被剔除的坏行没进 Sink,但属于「本该写入的行」,要计入分母
        long preSinkBad = session.plan().codesets().stream()
                .filter(c -> "BAD_ROW".equals(c.onMiss()))
                .mapToLong(c -> session.ledger.countOf(c.nodeId())).sum();
        long total = session.rowsSeenBySink + preSinkBad;
        return new EtlRunSummary("DUCKDB", session.rowsRead(), session.rowsWritten, bad,
                EtlRunSummary.rate(bad, total), session.codesetMisses, policy.mode());
    }

    /** 绝对数阈值:随时可判。比例阈值只在全部行处理完后判(中途的比例没有意义)。 */
    private void checkBadRowCount(Session session, BadRowPolicy policy) {
        if (policy.maxBadRows() != null && session.ledger.total() > policy.maxBadRows()) {
            throw new EtlBadRowLimitExceededException("坏行数 " + session.ledger.total()
                    + " 超出 errorPolicy.maxBadRows=" + policy.maxBadRows(), summary(session, policy));
        }
    }

    private void checkBadRowRatio(Session session, BadRowPolicy policy) {
        if (policy.maxBadRatio() == null) {
            return;
        }
        EtlRunSummary s = summary(session, policy);
        if (s.badRowRate() > policy.maxBadRatio()) {
            throw new EtlBadRowLimitExceededException(String.format(Locale.ROOT,
                    "坏行比例 %.4f 超出 errorPolicy.maxBadRatio=%s", s.badRowRate(), policy.maxBadRatio()), s);
        }
    }

    // ── 抽取 ────────────────────────────────────────────────────────────

    private enum Kind { LONG, DOUBLE, DECIMAL, BOOL, DATE, TIMESTAMP, STRING }

    private record DuckColumn(String name, String duckType, Kind kind) {
    }

    private void extractSource(String workspaceId, Session session, Node source, Limits limits,
                               BooleanSupplier canceled, java.util.function.LongConsumer onRead)
            throws Exception {
        String dataSourceId = require(source, "dataSourceId");
        String table = require(source, "table");
        String qualified = gateway.qualifiedTable(workspaceId, dataSourceId,
                source.str("database"), source.str("schema"), table);

        String columnList = "*";
        if (source.config().get("columns") instanceof Collection<?> cols && !cols.isEmpty()) {
            columnList = cols.stream().map(c -> gateway.quote(workspaceId, dataSourceId,
                    String.valueOf(c))).reduce((a, b) -> a + ", " + b).orElse("*");
        }

        List<String> predicates = new ArrayList<>();
        // 与 TableCopier 同一个折中:whereClause 由用户填写、未参数化,但源连接只读,
        // 最坏是读到不该读的行,写不坏源库。
        String where = source.str("whereClause");
        if (where != null) {
            predicates.add("(" + where + ")");
        }

        Object watermarkParam = null;
        WatermarkSpec spec = session.plan().watermarks().get(source.id());
        EtlWatermarkService store = watermarks.getIfAvailable();
        if (spec != null && store != null && session.plan().jobId() != null) {
            Optional<String> last = store.lastValue(workspaceId, session.plan().jobId(), source.id());
            if (last.isPresent() && !last.get().isBlank()) {
                predicates.add(gateway.quote(workspaceId, dataSourceId, spec.column()) + " > ?");
                watermarkParam = watermarkValue(spec.type(), last.get());
            }
        }
        String sql = "SELECT " + columnList + " FROM " + qualified
                + (predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates));

        String name = source.viewName();
        long extracted = 0;
        try (Connection src = gateway.open(workspaceId, dataSourceId, true);
             PreparedStatement read = src.prepareStatement(sql,
                     ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            read.setFetchSize(EXTRACT_BATCH);
            if (limits.maxSourceRows() > 0) {
                read.setMaxRows((int) Math.min(Integer.MAX_VALUE, limits.maxSourceRows()));
            }
            if (watermarkParam != null) {
                read.setObject(1, watermarkParam);
            }
            try (ResultSet rs = read.executeQuery()) {
                List<DuckColumn> columns = describe(rs.getMetaData());
                createTable(session.duck(), name, columns);
                String insert = "INSERT INTO \"" + name + "\" VALUES ("
                        + String.join(", ", java.util.Collections.nCopies(columns.size(), "?")) + ")";
                try (PreparedStatement write = session.duck().prepareStatement(insert)) {
                    int pending = 0;
                    while (rs.next()) {
                        if (canceled.getAsBoolean()) {
                            throw new InterruptedException("执行已被取消");
                        }
                        checkDeadline(limits);
                        for (int i = 0; i < columns.size(); i++) {
                            write.setObject(i + 1, readCell(rs, i + 1, columns.get(i).kind()));
                        }
                        write.addBatch();
                        extracted++;
                        if (++pending >= EXTRACT_BATCH) {
                            write.executeBatch();
                            pending = 0;
                        }
                        if (extracted % PROGRESS_INTERVAL == 0 && onRead != null) {
                            onRead.accept(session.rowsRead + extracted);
                        }
                    }
                    if (pending > 0) {
                        write.executeBatch();
                    }
                }
            }
        }
        session.rowsRead += extracted;
        if (onRead != null) {
            onRead.accept(session.rowsRead);
        }
        if (spec != null && extracted > 0) {
            try (Statement st = session.duck().createStatement();
                 ResultSet rs = st.executeQuery("SELECT max(\"" + spec.column().replace("\"", "\"\"")
                         + "\") FROM \"" + name + "\"")) {
                if (rs.next() && rs.getObject(1) != null) {
                    session.watermarkMax.put(source.id(), String.valueOf(rs.getObject(1)));
                }
            } catch (SQLException e) {
                // 水位列不在抽取结果里(比如 columns 没选它):不推进,而不是让整条管道失败
                log.warn("ETL 水位列 {} 在源节点 {} 的抽取结果中不存在,本次不推进", spec.column(), source.id());
            }
        }
        log.info("ETL 源节点 {} 抽取 {} 行", source.id(), extracted);
    }

    private static List<DuckColumn> describe(ResultSetMetaData md) throws SQLException {
        List<DuckColumn> columns = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String label = md.getColumnLabel(i);
            if (!names.add(label.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("源表返回了重复的列名: " + label);
            }
            int precision = md.getPrecision(i);
            int scale = md.getScale(i);
            DuckColumn column = switch (md.getColumnType(i)) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT ->
                        new DuckColumn(label, "BIGINT", Kind.LONG);
                case Types.FLOAT, Types.REAL, Types.DOUBLE -> new DuckColumn(label, "DOUBLE", Kind.DOUBLE);
                case Types.DECIMAL, Types.NUMERIC -> precision > 0 && precision <= 38 && scale >= 0
                        && scale <= precision
                        ? new DuckColumn(label, "DECIMAL(" + precision + "," + scale + ")", Kind.DECIMAL)
                        : new DuckColumn(label, "DOUBLE", Kind.DOUBLE);
                case Types.BOOLEAN, Types.BIT -> new DuckColumn(label, "BOOLEAN", Kind.BOOL);
                case Types.DATE -> new DuckColumn(label, "DATE", Kind.DATE);
                case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE ->
                        new DuckColumn(label, "TIMESTAMP", Kind.TIMESTAMP);
                default -> new DuckColumn(label, "VARCHAR", Kind.STRING);
            };
            columns.add(column);
        }
        if (columns.isEmpty()) {
            throw new IllegalStateException("源表没有可抽取的字段");
        }
        return columns;
    }

    private static void createTable(Connection duck, String name, List<DuckColumn> columns)
            throws SQLException {
        String defs = columns.stream()
                .map(c -> "\"" + c.name().replace("\"", "\"\"") + "\" " + c.duckType())
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        try (Statement st = duck.createStatement()) {
            st.execute("CREATE TEMP TABLE \"" + name + "\" (" + defs + ")");
        }
    }

    private static Object readCell(ResultSet rs, int i, Kind kind) throws SQLException {
        Object value = switch (kind) {
            case LONG -> {
                long v = rs.getLong(i);
                yield rs.wasNull() ? null : v;
            }
            case DOUBLE -> {
                double v = rs.getDouble(i);
                yield rs.wasNull() ? null : v;
            }
            case DECIMAL -> rs.getBigDecimal(i);
            case BOOL -> {
                boolean v = rs.getBoolean(i);
                yield rs.wasNull() ? null : v;
            }
            case DATE -> rs.getDate(i);
            case TIMESTAMP -> rs.getTimestamp(i);
            case STRING -> rs.getString(i);
        };
        return value;
    }

    private static Object watermarkValue(String type, String value) {
        try {
            return switch (type) {
                case "LONG", "INT", "INTEGER", "BIGINT", "NUMBER", "NUMERIC", "DECIMAL" ->
                        new BigDecimal(value.trim());
                case "TIMESTAMP", "DATETIME" -> Timestamp.valueOf(
                        value.trim().replace('T', ' ').replaceAll("Z$", ""));
                case "DATE" -> java.sql.Date.valueOf(value.trim().substring(0, 10));
                default -> value;
            };
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("水位线值 '" + value + "' 无法按 " + type + " 解析", e);
        }
    }

    // ── 文件 / 接口源 ───────────────────────────────────────────────────

    private static EtlObjectStore.ObjectRef objectRef(Node node) {
        String fileId = node.str("fileId");
        String dataSourceId = node.str("dataSourceId");
        String path = node.str("path");
        if (fileId == null && (dataSourceId == null || path == null)) {
            throw new IllegalArgumentException("节点 " + node.id() + " 必须指定 fileId,或 dataSourceId + path");
        }
        return new EtlObjectStore.ObjectRef(fileId, dataSourceId, path);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> optionsOf(Node node) {
        return node.config().get("options") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /**
     * SOURCE_FILE:经 {@link EtlObjectStore} 把对象取成引擎自建的临时文件,
     * 在<b>外部访问仍开放</b>时 {@code CREATE TEMP TABLE AS SELECT * FROM read_xxx(临时文件)};
     * 临时文件随后删除。计划里只有 fileId / dataSourceId + 相对路径,从不出现服务器绝对路径。
     */
    private void extractFile(String workspaceId, Session session, Node source, Limits limits,
                             java.util.function.LongConsumer onRead) throws Exception {
        EtlObjectStore store = objectStore.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("计划含文件源节点 " + source.id() + ",但没有可用的对象存储实现");
        }
        String format = EtlFileFormats.normalize(require(source, "format"));
        Map<String, Object> options = optionsOf(source);
        EtlObjectStore.ObjectRef ref = objectRef(source);
        String name = source.viewName();
        Path dir = Files.createTempDirectory("dg-etl-src-");
        long rows;
        try {
            EtlObjectStore.Fetched fetched = store.fetch(workspaceId, ref, format, options, dir);
            String limit = limits.maxSourceRows() > 0 ? " LIMIT " + limits.maxSourceRows() : "";
            try (Statement st = session.duck().createStatement()) {
                st.execute("CREATE TEMP TABLE \"" + name + "\" AS SELECT * FROM "
                        + EtlFileFormats.readExpression(fetched.format(), fetched.path().toString(), options)
                        + limit);
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM \"" + name + "\"")) {
                    rows = rs.next() ? rs.getLong(1) : 0;
                }
            }
        } finally {
            deleteQuietly(dir);
        }
        session.rowsRead += rows;
        if (onRead != null) {
            onRead.accept(session.rowsRead);
        }
        log.info("ETL 文件源节点 {} 抽取 {} 行 format={} {}", source.id(), rows, format, ref.describe());
    }

    /** SOURCE_API:Java 侧 HTTP 取数,解析 JSON 数组,按行写入 DuckDB 临时表(锁定之前)。 */
    private void extractApi(String workspaceId, Session session, Node source, Limits limits,
                            java.util.function.LongConsumer onRead) throws Exception {
        EtlApiFetcher fetcher = apiFetcher.getIfAvailable();
        if (fetcher == null) {
            throw new IllegalStateException("计划含接口源节点 " + source.id() + ",但没有可用的接口取数实现");
        }
        String url = source.str("url");
        String apiConfigId = source.str("apiConfigId");
        if (url == null && apiConfigId == null) {
            throw new IllegalArgumentException("节点 " + source.id() + " 必须指定 url 或 apiConfigId");
        }
        List<Map<String, Object>> rows = fetcher.fetch(workspaceId, url, apiConfigId,
                source.str("recordsPath"), limits.maxSourceRows());
        if (rows.isEmpty()) {
            // 没有记录就推不出列,下游 SQL 引用列时只会得到「列不存在」;这里直接说清楚
            throw new IllegalStateException("接口源节点 " + source.id() + " 返回 0 条记录,无法推断列结构");
        }
        List<DuckColumn> columns = inferColumns(rows);
        String name = source.viewName();
        createTable(session.duck(), name, columns);
        String insert = "INSERT INTO \"" + name + "\" VALUES ("
                + String.join(", ", java.util.Collections.nCopies(columns.size(), "?")) + ")";
        try (PreparedStatement write = session.duck().prepareStatement(insert)) {
            int pending = 0;
            for (Map<String, Object> row : rows) {
                checkDeadline(limits);
                for (int i = 0; i < columns.size(); i++) {
                    DuckColumn column = columns.get(i);
                    write.setObject(i + 1, apiCell(row.get(column.name()), column.kind()));
                }
                write.addBatch();
                if (++pending >= EXTRACT_BATCH) {
                    write.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                write.executeBatch();
            }
        }
        session.rowsRead += rows.size();
        if (onRead != null) {
            onRead.accept(session.rowsRead);
        }
        log.info("ETL 接口源节点 {} 抽取 {} 行", source.id(), rows.size());
    }

    /** 列 = 各行键的并集(保持首次出现顺序);类型取所有非空值的最窄公共类型,混杂则 VARCHAR。 */
    private static List<DuckColumn> inferColumns(List<Map<String, Object>> rows) {
        Map<String, boolean[]> seen = new LinkedHashMap<>();   // [long, double, bool, other]
        Set<String> lowerNames = new HashSet<>();
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> e : row.entrySet()) {
                boolean[] flags = seen.get(e.getKey());
                if (flags == null) {
                    if (!lowerNames.add(e.getKey().toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("接口返回了仅大小写不同的重复字段: " + e.getKey());
                    }
                    flags = new boolean[4];
                    seen.put(e.getKey(), flags);
                }
                Object v = e.getValue();
                if (v == null) {
                    continue;
                }
                if (v instanceof Boolean) {
                    flags[2] = true;
                } else if (v instanceof Double || v instanceof Float || v instanceof BigDecimal) {
                    flags[1] = true;
                } else if (v instanceof Number) {
                    flags[0] = true;
                } else {
                    flags[3] = true;
                }
            }
        }
        List<DuckColumn> columns = new ArrayList<>();
        seen.forEach((name, f) -> {
            boolean number = f[0] || f[1];
            DuckColumn column;
            if (f[3] || (f[2] && number) || (!f[0] && !f[1] && !f[2])) {
                column = new DuckColumn(name, "VARCHAR", Kind.STRING);
            } else if (f[1]) {
                column = new DuckColumn(name, "DOUBLE", Kind.DOUBLE);
            } else if (f[0]) {
                column = new DuckColumn(name, "BIGINT", Kind.LONG);
            } else {
                column = new DuckColumn(name, "BOOLEAN", Kind.BOOL);
            }
            columns.add(column);
        });
        return columns;
    }

    private static Object apiCell(Object value, Kind kind) {
        if (value == null) {
            return null;
        }
        return switch (kind) {
            case LONG -> ((Number) value).longValue();
            case DOUBLE -> ((Number) value).doubleValue();
            case BOOL -> value;
            default -> String.valueOf(value);
        };
    }

    private static void deleteQuietly(Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("ETL 临时文件清理失败 {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("ETL 临时目录清理失败 {}", dir, e);
        }
    }

    // ── 预览 ────────────────────────────────────────────────────────────

    /**
     * 预览某个节点的输出(前 {@code limit} 行)。源节点只抽样 {@code limits.maxSourceRows} 行,
     * 所以含聚合/去重的下游预览是<b>基于样本</b>的结果,不等于全量结果。
     */
    public PreviewResult preview(String workspaceId, Plan plan, String nodeId, int limit,
                                 Limits limits) throws Exception {
        Node target = nodeId == null || nodeId.isBlank() ? null : plan.node(nodeId).orElseThrow(
                () -> new IllegalArgumentException("节点不存在: " + nodeId));
        if (target == null) {
            List<Node> sinks = plan.nodes().stream().filter(n -> SINK_TYPES.contains(n.type())).toList();
            if (sinks.isEmpty()) {
                throw new IllegalArgumentException("请指定要预览的 nodeId");
            }
            target = sinks.get(0);
        }
        String resolvedId = SINK_TYPES.contains(target.type()) ? sinkInput(plan, target) : target.id();

        try (Session session = open(workspaceId, plan, limits, () -> false, null);
             Statement st = session.duck().createStatement()) {
            plan.node(resolvedId).orElseThrow(
                    () -> new IllegalArgumentException("节点不存在: " + resolvedId));
            st.setQueryTimeout(remainingSeconds(limits));
            try (ResultSet rs = st.executeQuery("SELECT * FROM (SELECT * FROM \""
                    + tempName(resolvedId) + "\") AS __dg_etl_preview LIMIT " + (limit + 1))) {
                ResultSetMetaData md = rs.getMetaData();
                List<PreviewColumn> columns = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    columns.add(new PreviewColumn(md.getColumnLabel(i), md.getColumnTypeName(i)));
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() >= limit) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columns.size(); i++) {
                        row.put(columns.get(i - 1).name(), jsonSafe(rs.getObject(i)));
                    }
                    rows.add(row);
                }
                return new PreviewResult(resolvedId, columns, rows, truncated);
            }
        }
    }

    private static Object jsonSafe(Object v) {
        if (v == null || v instanceof Number || v instanceof Boolean || v instanceof String) {
            return v instanceof BigDecimal bd ? bd.toPlainString() : v;
        }
        return String.valueOf(v);
    }

    private static int remainingSeconds(Limits limits) {
        if (limits.deadlineNanos() == 0) {
            return 0;
        }
        long left = (limits.deadlineNanos() - System.nanoTime()) / 1_000_000_000L;
        return (int) Math.max(1, left);
    }

    private static void checkDeadline(Limits limits) {
        if (limits.deadlineNanos() != 0 && System.nanoTime() > limits.deadlineNanos()) {
            throw new BizException(ErrorCode.DAT_QUERY_FAILED, "ETL 预览超时");
        }
    }

    // ── Sink ────────────────────────────────────────────────────────────

    /**
     * 把 sink 上游关系写入目标端。批量提交 / 幂等声明与 {@link TableCopier} 一致:
     * APPEND 的第一次提交起即 {@code markUnsafeToRetry}(重投会写重);OVERWRITE / UPSERT 幂等,不标记。
     *
     * @return 写入行数
     */
    public long writeSink(String workspaceId, Session session, Node sink, JobRunner.RunContext context)
            throws Exception {
        if (SINK_PARQUET.equals(sink.type())) {
            return writeParquetSink(workspaceId, session, sink, context);
        }
        String dataSourceId = require(sink, "dataSourceId");
        String table = require(sink, "table");
        String writeMode = sink.str("writeMode") == null ? "APPEND"
                : sink.str("writeMode").toUpperCase(Locale.ROOT);
        if (!Set.of("APPEND", "OVERWRITE", "UPSERT").contains(writeMode)) {
            throw new IllegalArgumentException("不支持的写入模式: " + writeMode);
        }
        List<String> primaryKeys = new ArrayList<>();
        if (sink.config().get("primaryKeys") instanceof Collection<?> pks) {
            pks.stream().filter(Objects::nonNull).map(String::valueOf).forEach(primaryKeys::add);
        }
        int batchSize = 1000;
        if (sink.config().get("batchSize") != null) {
            try {
                batchSize = Math.max(1, Integer.parseInt(sink.config().get("batchSize").toString()));
            } catch (NumberFormatException ignored) {
                // 保持默认
            }
        }
        String qualified = gateway.qualifiedTable(workspaceId, dataSourceId,
                sink.str("database"), sink.str("schema"), table);
        String input = tempName(sinkInput(session.plan(), sink));

        // 可选 columnMappings: DuckDB 列 → 目标列。未给则同名全列。
        Map<String, String> mappings = new LinkedHashMap<>();
        Object rawMappings = firstNonNull(sink.config().get("columnMappings"),
                sink.config().get("fieldMappings"));
        if (rawMappings instanceof Map<?, ?> mm) {
            mm.forEach((k, v) -> mappings.put(String.valueOf(k), String.valueOf(v)));
        }

        String select = mappings.isEmpty() ? "SELECT * FROM \"" + input + "\""
                : "SELECT " + mappings.keySet().stream()
                .map(c -> "\"" + c.replace("\"", "\"\"") + "\"")
                .reduce((a, b) -> a + ", " + b).orElseThrow() + " FROM \"" + input + "\"";

        BadRowPolicy policy = session.plan().badRowPolicy();
        long written = 0;
        long read = 0;
        try (Connection target = gateway.open(workspaceId, dataSourceId, false);
             Statement st = session.duck().createStatement()) {
            target.setAutoCommit(false);
            try (ResultSet rs = st.executeQuery(select)) {
                ResultSetMetaData md = rs.getMetaData();
                int count = md.getColumnCount();
                List<String> labels = new ArrayList<>(count);
                List<String> targetColumns = new ArrayList<>(count);
                for (int i = 1; i <= count; i++) {
                    String label = md.getColumnLabel(i);
                    labels.add(label);
                    targetColumns.add(mappings.isEmpty() ? label : mappings.get(label));
                }
                String insertSql = gateway.insertSql(workspaceId, dataSourceId, qualified,
                        targetColumns, writeMode, primaryKeys);

                if ("OVERWRITE".equals(writeMode)) {
                    // DELETE 而不是 TRUNCATE:TRUNCATE 在多数库是隐式提交的 DDL,会让后续失败的回滚失去意义
                    try (Statement del = target.createStatement()) {
                        int deleted = del.executeUpdate("DELETE FROM " + qualified);
                        target.commit();
                        log.info("ETL OVERWRITE 已清空目标表 {} 删除 {} 行", qualified, deleted);
                    }
                }
                boolean idempotent = !"APPEND".equals(writeMode);
                // 容忍坏行时要留住当前批次的行,批次失败后才能逐行重放定位坏行
                List<Object[]> pendingRows = policy.tolerant() ? new ArrayList<>() : null;
                long batchStart = 0;
                // 不用 try-with-resources:有些驱动(如 DuckDB)批次失败后会把 PreparedStatement 一并关掉,
                // 容忍坏行时需要在失败后换一个新的继续写
                PreparedStatement write = target.prepareStatement(insertSql);
                try {
                    int pending = 0;
                    while (rs.next()) {
                        context.throwIfCanceled();
                        if (write.isClosed()) {
                            write = target.prepareStatement(insertSql);
                        }
                        Object[] values = new Object[count];
                        for (int i = 1; i <= count; i++) {
                            values[i - 1] = rs.getObject(i);
                            write.setObject(i, values[i - 1]);
                        }
                        write.addBatch();
                        if (pendingRows != null) {
                            pendingRows.add(values);
                        }
                        read++;
                        session.rowsSeenBySink = read;
                        if (++pending >= batchSize) {
                            written += flushBatch(write, insertSql, target, idempotent, context, session,
                                    sink.id(), policy, pendingRows, labels, batchStart);
                            session.rowsWritten = written;
                            batchStart = read;
                            pending = 0;
                            if (pendingRows != null) {
                                pendingRows.clear();
                            }
                            context.progress().accept(new ExecutionEngine.EngineMetric(
                                    session.rowsRead(), written, 0));
                        }
                    }
                    if (pending > 0) {
                        if (write.isClosed()) {
                            write = target.prepareStatement(insertSql);
                        }
                        written += flushBatch(write, insertSql, target, idempotent, context, session,
                                sink.id(), policy, pendingRows, labels, batchStart);
                        session.rowsWritten = written;
                    }
                } catch (SQLException | RuntimeException e) {
                    try {
                        target.rollback();
                    } catch (SQLException rollbackError) {
                        log.warn("ETL Sink 回滚失败", rollbackError);
                    }
                    try {
                        context.progress().accept(
                                new ExecutionEngine.EngineMetric(session.rowsRead(), written, 0));
                    } catch (RuntimeException ignored) {
                        log.warn("ETL 失败时回报已写入行数没成功 execution={}", context.executionId());
                    }
                    throw e;
                } finally {
                    try {
                        write.close();
                    } catch (SQLException ignored) {
                        // 已被驱动关闭
                    }
                }
            }
        }
        log.info("ETL Sink 完成 {} 读{}行 写{}行 坏行{} 模式={}", qualified, read, written,
                session.ledger.total(), writeMode);
        // 比例阈值要等全部行处理完才有意义
        checkBadRowRatio(session, policy);
        return written;
    }

    /**
     * SINK_PARQUET。锁定后的会话不能 {@code COPY ... TO}(外部访问已关),所以:
     * Java 从锁定会话里 {@code SELECT * FROM 上游视图} 流式读出,写进一个<b>只由 Java 驱动的、
     * 独立的临时 DuckDB 连接</b>(不执行任何用户 SQL),由它 {@code COPY ... TO 临时 Parquet};
     * 再经 {@link EtlObjectStore#put} 落到目标对象。用户 SQL 全程碰不到文件系统。
     *
     * <p>列类型沿用抽取时的映射(整数统一为 BIGINT、小数 / 浮点按精度),不保证与上游源表物理类型逐一相同。
     * 目标按 OVERWRITE 语义覆盖,幂等,所以不标记 unsafe-to-retry。
     */
    private long writeParquetSink(String workspaceId, Session session, Node sink,
                                  JobRunner.RunContext context) throws Exception {
        EtlObjectStore store = objectStore.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("计划含 Parquet 目标节点 " + sink.id() + ",但没有可用的对象存储实现");
        }
        EtlObjectStore.ObjectRef ref = objectRef(sink);
        String compression = sink.str("compression") == null ? "SNAPPY" : sink.str("compression");
        String duckCompression = EtlFileFormats.parquetCompression(compression);
        String input = tempName(sinkInput(session.plan(), sink));

        Path dir = Files.createTempDirectory("dg-etl-sink-");
        long written = 0;
        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = session.duck().createStatement()) {
            try (Statement cfg = writer.createStatement()) {
                cfg.execute("SET memory_limit = '" + session.memoryLimit + "'");
            }
            Path out = dir.resolve("sink.parquet");
            try (ResultSet rs = st.executeQuery("SELECT * FROM \"" + input + "\"")) {
                List<DuckColumn> columns = describe(rs.getMetaData());
                createTable(writer, "etl_out", columns);
                String insert = "INSERT INTO \"etl_out\" VALUES ("
                        + String.join(", ", java.util.Collections.nCopies(columns.size(), "?")) + ")";
                try (PreparedStatement write = writer.prepareStatement(insert)) {
                    int pending = 0;
                    while (rs.next()) {
                        context.throwIfCanceled();
                        for (int i = 0; i < columns.size(); i++) {
                            write.setObject(i + 1, readCell(rs, i + 1, columns.get(i).kind()));
                        }
                        write.addBatch();
                        written++;
                        session.rowsSeenBySink = written;
                        if (++pending >= EXTRACT_BATCH) {
                            write.executeBatch();
                            pending = 0;
                            context.progress().accept(new ExecutionEngine.EngineMetric(
                                    session.rowsRead(), 0, 0));
                        }
                    }
                    if (pending > 0) {
                        write.executeBatch();
                    }
                }
            }
            try (Statement copy = writer.createStatement()) {
                copy.execute("COPY \"etl_out\" TO " + EtlFileFormats.literal(out.toString())
                        + " (FORMAT PARQUET, COMPRESSION " + EtlFileFormats.literal(duckCompression) + ")");
            }
            store.put(workspaceId, ref, out, compression);
            session.rowsWritten = written;
            context.progress().accept(new ExecutionEngine.EngineMetric(session.rowsRead(), written, 0));
        } finally {
            deleteQuietly(dir);
        }
        log.info("ETL Parquet Sink 完成 node={} 写{}行 compression={} {}", sink.id(), written, compression,
                ref.describe());
        checkBadRowRatio(session, session.plan().badRowPolicy());
        return written;
    }

    /**
     * 提交一个批次。FAIL_FAST:任何失败原样抛出(调用方回滚)。
     * 容忍坏行:批次失败 → 回滚该批次 → 逐行重放,把写不进去的行记入台账并继续。
     */
    private long flushBatch(PreparedStatement write, String insertSql, Connection target, boolean idempotent,
                            JobRunner.RunContext context, Session session, String sinkNodeId,
                            BadRowPolicy policy, List<Object[]> rows, List<String> labels, long batchStart)
            throws SQLException {
        if (!policy.tolerant()) {
            return flush(write, target, idempotent, context);
        }
        try {
            return flush(write, target, idempotent, context);
        } catch (SQLException batchError) {
            try {
                write.clearBatch();
            } catch (SQLException closed) {
                // DuckDB 等驱动在批次失败时已关闭语句;下面逐行重放用的是新语句
            }
            target.rollback();
            log.warn("ETL Sink 批次写入失败,改为逐行重放以定位坏行(批次起点第 {} 行, {} 行)",
                    batchStart, rows.size());
        }
        long written = 0;
        PreparedStatement single = target.prepareStatement(insertSql);
        try {
            for (int i = 0; i < rows.size(); i++) {
                Object[] values = rows.get(i);
                try {
                    if (single.isClosed()) {
                        // 同批次失败一样:DuckDB 等驱动语句执行失败后会自行关闭,换新的继续
                        single = target.prepareStatement(insertSql);
                    }
                    for (int c = 0; c < values.length; c++) {
                        single.setObject(c + 1, values[c]);
                    }
                    single.executeUpdate();
                    target.commit();
                    written++;
                    if (!idempotent) {
                        context.markUnsafeToRetry();
                    }
                } catch (SQLException rowError) {
                    target.rollback();
                    Map<String, Object> body = null;
                    if (policy.captureValues() && policy.deadLetter()) {
                        body = new LinkedHashMap<>();
                        for (int c = 0; c < values.length; c++) {
                            body.put(labels.get(c), jsonSafe(values[c]));
                        }
                    }
                    session.ledger.addSinkError(sinkNodeId, batchStart + i, rowError, body);
                    checkBadRowCount(session, policy);
                }
            }
        } finally {
            try {
                single.close();
            } catch (SQLException ignored) {
                // 已被驱动关闭
            }
        }
        return written;
    }

    private static long flush(PreparedStatement write, Connection target, boolean idempotent,
                              JobRunner.RunContext context) throws SQLException {
        int[] results = write.executeBatch();
        target.commit();
        // 提交点立即声明:重投会把这一批再写一遍(APPEND)。
        if (!idempotent) {
            context.markUnsafeToRetry();
        }
        write.clearBatch();
        long written = 0;
        for (int result : results) {
            written += result >= 0 ? result : (result == Statement.SUCCESS_NO_INFO ? 1 : 0);
        }
        return written;
    }

    /** 整条管道成功后推进水位线。无 {@link EtlWatermarkService} Bean 或无 jobId 时静默跳过。 */
    public void advanceWatermarks(String workspaceId, Session session, String executionId) {
        EtlWatermarkService store = watermarks.getIfAvailable();
        String jobId = session.plan().jobId();
        if (store == null || jobId == null) {
            return;
        }
        session.watermarkMax().forEach((nodeId, value) -> {
            WatermarkSpec spec = session.plan().watermarks().get(nodeId);
            if (spec != null) {
                store.advance(workspaceId, jobId, nodeId, spec.column(), spec.type(), value, executionId);
            }
        });
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    private static String require(Node node, String key) {
        String value = node.str(key);
        if (value == null) {
            throw new IllegalArgumentException("节点 " + node.id() + " 缺少必填项: " + key);
        }
        return value;
    }

    private static List<?> list(Object raw) {
        return raw instanceof List<?> l ? l : List.of();
    }

    private static String text(Object raw) {
        return raw == null || raw.toString().isBlank() ? null : raw.toString().trim();
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }
}
