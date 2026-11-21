package com.datagov.control.compile;

import com.datagov.control.domain.JobType;
import com.datagov.control.etl.EtlConfig;
import com.datagov.control.etl.EtlDag;
import com.datagov.control.etl.EtlEdge;
import com.datagov.control.etl.EtlErrorPolicy;
import com.datagov.control.etl.EtlNode;
import com.datagov.control.etl.EtlNodeKind;
import com.datagov.control.etl.EtlPipelineIr;
import com.datagov.control.etl.EtlSql;
import com.datagov.control.etl.EtlSqlGuard;
import com.datagov.data.spi.DataSourceType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 轻量 ETL 管道(etl.ir/v1)的编译器。
 *
 * <p>编译链:IR 形状 → 图结构(边引用、入出度、环)→ 按拓扑序逐节点校验配置并做
 * Schema / 敏感级别 / 列级来源推断 → 生成 DuckDB 步骤、血缘边、水位线与 Sink 规格。
 *
 * <p><b>计划里没有连接串与凭据,也没有任何明文数据</b>,只有数据源 ID 与 SQL。
 * 凭据由 Runner 在执行期经 Platform 的一次性句柄解析。
 *
 * <p>物理计划形状:
 * <pre>
 * kind, irVersion, ir, errorPolicy,
 * executionOrder   : [nodeId...]          仅含对 Sink 有贡献的节点
 * duckdbSteps      : [ {nodeId, kind, step: LOAD|SQL|SINK, ...} ]
 *                      LOAD → source 规格(Runner 把数据装进名为 nodeId 的表)
 *                      SQL  → sql 为 CREATE OR REPLACE TEMP VIEW "nodeId" AS ...
 *                      SINK → input + sink 规格
 * lineageEdges     : [ {from:{...}, to:{...}, relation, transformExpr, dependencyType,
 *                       maskingApplied, confidence} ]
 * watermarkSpecs   : [ {nodeId, column, type, initialValue} ]
 * sinkSpec         : 唯一 Sink 的规格
 * </pre>
 *
 * <p>MVP 约束:恰好一个 Sink(水位线「Sink 成功后推进」在多 Sink 下语义不清,留待后续)。
 */
@Component
public class EtlPipelineCompiler implements JobCompiler {

    static final int DEFAULT_BATCH_SIZE = 1000;
    static final int MAX_BATCH_SIZE = 50_000;

    private static final Set<String> WRITE_MODES = Set.of("APPEND", "OVERWRITE", "UPSERT");
    private static final Set<String> JOIN_TYPES = Set.of("INNER", "LEFT", "RIGHT", "FULL");
    private static final Set<String> AGG_FUNCS =
            Set.of("SUM", "COUNT", "AVG", "MIN", "MAX", "COUNT_DISTINCT");
    private static final Set<String> FILE_FORMATS = Set.of("CSV", "JSON", "NDJSON", "PARQUET");
    private static final Set<String> COMPRESSIONS = Set.of("SNAPPY", "ZSTD", "GZIP", "NONE");
    private static final Set<String> WATERMARK_TYPES = Set.of("TIMESTAMP", "NUMBER", "STRING");
    /** 脱敏动作:用了它,字段就不再是原值。 */
    private static final Set<String> MASKING_ACTIONS =
            Set.of("MASK_PARTIAL", "HASH_SHA256", "REDACT", "NULLIFY");
    private static final Set<String> CLEANSE_ACTIONS = Set.of(
            "TRIM", "UPPER", "LOWER", "DEFAULT_IF_NULL",
            "MASK_PARTIAL", "HASH_SHA256", "REDACT", "NULLIFY");
    private static final String UNKNOWN_TYPE = "UNKNOWN";
    private static final Set<String> ENGINES = Set.of("DUCKDB", "SEATUNNEL");
    private static final Set<String> CODESET_MAP_TO = Set.of("CANONICAL_VALUE", "DISPLAY_LABEL");
    private static final Set<String> CODESET_ON_MISS = Set.of("KEEP_ORIGINAL", "NULL", "DEFAULT", "BAD_ROW");
    /** 码表在 DuckDB 会话里的表名前缀;节点 id 受 EtlIrParser 限制,拼接后仍是安全标识符。 */
    static final String CODESET_TABLE_PREFIX = "__dg_cs_";

    @Override
    public JobType jobType() {
        return JobType.ETL_PIPELINE;
    }

    // ── 内部模型 ────────────────────────────────────────────────────────

    private record Origin(String nodeId, String column) {
    }

    /** 一个输出列:类型、敏感级别(已脱敏则为 null)、列级来源、是否经过脱敏、最近一次变换表达式。 */
    private record Col(String type, String level, Set<Origin> origins, boolean masked, String expr) {
        Col withLevel(String newLevel) {
            return new Col(type, newLevel, origins, masked, expr);
        }
    }

    /**
     * 节点输出。{@code known=false} 表示列集合无法静态推断(自定义 SQL、读不到表结构的源)。
     * 此时 {@code carry} 以列名为键粗粒度携带敏感级别,{@code sources} 是上游源节点集合
     * (列级血缘不可得时退化为表级)。
     */
    private record Out(boolean known, LinkedHashMap<String, Col> cols,
                       Map<String, String> carry, Set<String> sources) {
        static Out unknown(Map<String, String> carry, Set<String> sources) {
            return new Out(false, new LinkedHashMap<>(), carry, sources);
        }
    }

    private static final class Graph {
        final Map<String, EtlNode> byId = new LinkedHashMap<>();
        final Map<String, List<EtlEdge>> incoming = new HashMap<>();
        final Map<String, List<EtlEdge>> outgoing = new HashMap<>();
    }

    /** 编译过程中累积的产物。 */
    private static final class Acc {
        final Map<String, Out> outs = new HashMap<>();
        final Map<String, Map<String, Object>> steps = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> sourceDesc = new HashMap<>();
        final List<Map<String, Object>> watermarks = new ArrayList<>();
        final List<Map<String, Object>> codesets = new ArrayList<>();
        final Map<String, Boolean> incrementalSource = new HashMap<>();
        Map<String, Object> sinkSpec;
        List<Map<String, Object>> lineage = new ArrayList<>();
        String sinkNodeId;
    }

    // ── 入口 ────────────────────────────────────────────────────────────

    @Override
    public CompileResult compile(CompileContext context) {
        CompileResult.Collector collector = new CompileResult.Collector();
        Map<String, Object> config = context.config();

        CompilerSupport.warnUnknownKeys(collector, config == null ? Map.of() : config,
                EtlPipelineIr.KNOWN_KEYS);

        EtlPipelineIr ir = EtlPipelineIr.parse(config, collector);
        if (ir == null) {
            return CompileResult.failure(collector.all());
        }

        Graph graph = buildGraph(ir, collector);
        EtlDag.TopoResult topo = EtlDag.sort(ir.nodes(), ir.edges());
        if (topo.hasCycle()) {
            collector.error(CompileStage.DAG_VALIDATION, "edges",
                    "管道存在环:涉及节点 " + String.join(", ", topo.cycleNodes()),
                    "ETL 管道必须是有向无环图,请删除形成回路的那条边");
        }
        if (collector.hasErrors()) {
            // 图都不成立时逐节点校验只会产生同因异果的噪音
            return CompileResult.failure(collector.all());
        }

        String engine = validateEngine(config, collector);

        String workspaceId = CompilerSupport.workspaceOf(context.definition());
        Acc acc = new Acc();
        for (String id : topo.order()) {
            compileNode(graph.byId.get(id), graph, acc, ir, context.metadata(), workspaceId, collector);
        }

        if (collector.hasErrors()) {
            return CompileResult.failure(collector.all());
        }
        String jobId = context.definition() == null ? null : context.definition().getId();
        return CompileResult.success(buildPlan(ir, graph, topo.order(), acc, engine, jobId, collector),
                collector.all());
    }

    // ── 图结构 ──────────────────────────────────────────────────────────

    private Graph buildGraph(EtlPipelineIr ir, CompileResult.Collector collector) {
        Graph graph = new Graph();
        for (EtlNode node : ir.nodes()) {
            graph.byId.put(node.id(), node);
            graph.incoming.put(node.id(), new ArrayList<>());
            graph.outgoing.put(node.id(), new ArrayList<>());
        }

        Set<String> seenEdges = new HashSet<>();
        for (int i = 0; i < ir.edges().size(); i++) {
            EtlEdge edge = ir.edges().get(i);
            String location = "edges[" + i + "]";
            if (!graph.byId.containsKey(edge.from())) {
                collector.error(CompileStage.DAG_VALIDATION, location,
                        "边的起点节点不存在: " + edge.from());
                continue;
            }
            if (!graph.byId.containsKey(edge.to())) {
                collector.error(CompileStage.DAG_VALIDATION, location,
                        "边的终点节点不存在: " + edge.to());
                continue;
            }
            if (edge.from().equals(edge.to())) {
                collector.error(CompileStage.DAG_VALIDATION, location, "节点不能连向自己: " + edge.from());
                continue;
            }
            if (!seenEdges.add(edge.from() + "->" + edge.to())) {
                collector.error(CompileStage.DAG_VALIDATION, location,
                        "重复的边: " + edge.from() + " → " + edge.to());
                continue;
            }
            graph.incoming.get(edge.to()).add(edge);
            graph.outgoing.get(edge.from()).add(edge);
        }

        int sources = 0;
        int sinks = 0;
        for (EtlNode node : ir.nodes()) {
            EtlNodeKind kind = node.kind();
            String location = "nodes." + node.id();
            int in = graph.incoming.get(node.id()).size();
            int out = graph.outgoing.get(node.id()).size();
            if (kind.isSource()) {
                sources++;
                if (in > 0) {
                    collector.error(CompileStage.DAG_VALIDATION, location, "数据源节点不能有上游");
                }
            } else if (kind.isSink()) {
                sinks++;
                if (out > 0) {
                    collector.error(CompileStage.DAG_VALIDATION, location, "目标节点不能有下游");
                }
            }
            if (!kind.isSource() && (in < kind.minInputs() || in > kind.maxInputs())) {
                collector.error(CompileStage.DAG_VALIDATION, location,
                        "%s 需要 %s 路输入,当前 %d 路".formatted(kind.name(),
                                kind.minInputs() == kind.maxInputs()
                                        ? String.valueOf(kind.minInputs())
                                        : kind.minInputs() + "~" + kind.maxInputs(), in));
            }
            if (!kind.isSink() && out == 0) {
                collector.warn(CompileStage.DAG_VALIDATION, location,
                        "节点没有下游,它的输出不会被使用", "连到后续节点,或删除这个节点");
            }
            if (kind == EtlNodeKind.TRANSFORM_JOIN) {
                checkJoinPorts(graph.incoming.get(node.id()), location, collector);
            }
        }
        if (sources == 0) {
            collector.error(CompileStage.DAG_VALIDATION, "nodes", "管道没有数据源节点(SOURCE_*)");
        }
        if (sinks == 0) {
            collector.error(CompileStage.DAG_VALIDATION, "nodes", "管道没有目标节点(SINK_*)");
        } else if (sinks > 1) {
            collector.error(CompileStage.DAG_VALIDATION, "nodes",
                    "当前版本一个管道只支持一个目标节点,发现 " + sinks + " 个",
                    "水位线在 Sink 成功后推进,多个 Sink 时语义不清;请拆成多个管道");
        }
        return graph;
    }

    private void checkJoinPorts(List<EtlEdge> incoming, String location, CompileResult.Collector collector) {
        if (incoming.size() != 2) {
            return; // 入度错误已另行报告
        }
        String p0 = incoming.get(0).port();
        String p1 = incoming.get(1).port();
        if (p0 == null && p1 == null) {
            return; // 按边顺序:先左后右
        }
        if (p0 == null || p1 == null || p0.equals(p1)) {
            collector.error(CompileStage.DAG_VALIDATION, location,
                    "JOIN 的两条入边要么都不写 port(按声明顺序先左后右),要么一条 left 一条 right");
        }
    }

    // ── 节点编译 ────────────────────────────────────────────────────────

    private void compileNode(EtlNode node, Graph graph, Acc acc, EtlPipelineIr ir,
                             MetadataLookup metadata, String workspaceId,
                             CompileResult.Collector collector) {
        String id = node.id();
        String loc = "nodes." + id;
        Map<String, Object> cfg = node.config();
        List<EtlEdge> in = graph.incoming.get(id);
        Out input = in.isEmpty() ? null : acc.outs.get(in.get(0).from());

        switch (node.kind()) {
            case SOURCE_JDBC -> acc.outs.put(id, compileJdbcSource(node, acc, metadata, workspaceId, collector));
            case SOURCE_FILE -> acc.outs.put(id, compileFileSource(node, acc, collector));
            case SOURCE_API -> acc.outs.put(id, compileApiSource(node, acc, collector));
            case TRANSFORM_FILTER -> {
                String cond = EtlConfig.str(cfg, "condition");
                requireExpression(collector, loc + ".condition", cond, "过滤条件");
                acc.outs.put(id, input);
                if (cond != null) {
                    putSql(acc, node, "SELECT * FROM " + EtlSql.ident(in.get(0).from()) + " WHERE (" + cond + ")");
                }
            }
            case TRANSFORM_SELECT -> acc.outs.put(id, compileSelect(node, in.get(0).from(), input, acc, collector));
            case TRANSFORM_DERIVE -> acc.outs.put(id, compileDerive(node, in.get(0).from(), input, acc, collector));
            case TRANSFORM_CLEANSE_MASK ->
                    acc.outs.put(id, compileCleanse(node, in.get(0).from(), input, acc, collector));
            case TRANSFORM_JOIN -> acc.outs.put(id, compileJoin(node, in, acc, collector));
            case TRANSFORM_AGGREGATE ->
                    acc.outs.put(id, compileAggregate(node, in.get(0).from(), input, acc, collector));
            case TRANSFORM_DEDUP -> acc.outs.put(id, compileDedup(node, in.get(0).from(), input, acc, collector));
            case TRANSFORM_CUSTOM_SQL -> acc.outs.put(id, compileCustomSql(node, in, acc, collector));
            case TRANSFORM_CODESET_LOOKUP -> acc.outs.put(id,
                    compileCodesetLookup(node, in.get(0).from(), input, acc, ir, metadata, workspaceId, collector));
            case SINK_JDBC -> compileJdbcSink(node, in.get(0).from(), input, acc, ir, metadata, workspaceId, collector);
            case SINK_PARQUET -> compileParquetSink(node, in.get(0).from(), input, acc, collector);
        }
    }

    private void putSql(Acc acc, EtlNode node, String selectSql) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("nodeId", node.id());
        step.put("kind", node.kind().name());
        step.put("step", "SQL");
        step.put("sql", "CREATE OR REPLACE TEMP VIEW " + EtlSql.ident(node.id()) + " AS " + selectSql);
        acc.steps.put(node.id(), step);
    }

    // ── 数据源 ──────────────────────────────────────────────────────────

    private Out compileJdbcSource(EtlNode node, Acc acc, MetadataLookup metadata, String workspaceId,
                                  CompileResult.Collector collector) {
        String id = node.id();
        String loc = "nodes." + id;
        Map<String, Object> cfg = node.config();
        String ds = EtlConfig.str(cfg, "dataSourceId");
        String db = EtlConfig.str(cfg, "database");
        String schema = EtlConfig.str(cfg, "schema");
        String table = EtlConfig.str(cfg, "table");

        boolean dsOk = CompilerSupport.requireAvailableDataSource(
                collector, metadata, workspaceId, ds, loc + ".dataSourceId", "源数据源");
        if (table == null) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".table", "源表未指定");
        }

        Map<String, String> tableCols = Map.of();
        Map<String, String> levels = Map.of();
        if (dsOk && table != null) {
            tableCols = metadata.tableColumns(workspaceId, ds, db, schema, table);
            if (tableCols.isEmpty()) {
                // 与离线同步不同:ETL 允许没有结构快照的源,只是少了静态推断与列级校验
                collector.warn(CompileStage.SCHEMA_VALIDATION, loc,
                        "读不到源表「%s」的结构快照,将跳过该节点的 Schema 推断与列级校验".formatted(table),
                        "在数据源管理里浏览一次该表,可获得字段校验、敏感字段提醒与列级血缘");
            } else {
                levels = metadata.sensitiveColumnLevels(workspaceId, ds, db, schema, table);
            }
        }

        List<String> selected = EtlConfig.strings(cfg, "columns");
        for (String column : selected) {
            if (!tableCols.isEmpty() && !tableCols.containsKey(column)) {
                collector.error(CompileStage.SCHEMA_VALIDATION, loc + ".columns." + column,
                        "源表没有字段「%s」".formatted(column));
            }
        }

        String where = EtlConfig.str(cfg, "whereClause");
        if (where != null) {
            requireExpression(collector, loc + ".whereClause", where, "源端过滤条件");
        }

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "JDBC");
        source.put("dataSourceId", ds);
        putIfNotNull(source, "database", db);
        putIfNotNull(source, "schema", schema);
        source.put("table", table);
        if (!selected.isEmpty()) {
            source.put("columns", selected);
        }
        putIfNotNull(source, "whereClause", where);

        Map<String, Object> incremental = EtlConfig.map(cfg, "incremental");
        boolean isIncremental = !incremental.isEmpty();
        if (isIncremental) {
            compileIncremental(id, loc, incremental, tableCols, acc, source, collector);
        }
        acc.incrementalSource.put(id, isIncremental);

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("type", "TABLE");
        descriptor.put("nodeId", id);
        descriptor.put("dataSourceId", ds);
        putIfNotNull(descriptor, "database", db);
        putIfNotNull(descriptor, "schema", schema);
        descriptor.put("table", table);
        acc.sourceDesc.put(id, descriptor);

        putLoad(acc, node, source);

        if (tableCols.isEmpty()) {
            return Out.unknown(Map.of(), Set.of(id));
        }
        LinkedHashMap<String, Col> cols = new LinkedHashMap<>();
        List<String> names = selected.isEmpty() ? new ArrayList<>(tableCols.keySet()) : selected;
        for (String name : names) {
            if (!tableCols.containsKey(name)) {
                continue;
            }
            cols.put(name, new Col(tableCols.get(name), normalizeLevel(levels.get(name)),
                    Set.of(new Origin(id, name)), false, null));
        }
        return new Out(true, cols, Map.of(), Set.of(id));
    }

    private void compileIncremental(String id, String loc, Map<String, Object> incremental,
                                    Map<String, String> tableCols, Acc acc, Map<String, Object> source,
                                    CompileResult.Collector collector) {
        String column = EtlConfig.str(incremental, "column");
        if (column == null) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".incremental.column",
                    "增量抽取必须指定水位线字段");
            return;
        }
        if (!tableCols.isEmpty() && !tableCols.containsKey(column)) {
            collector.error(CompileStage.SCHEMA_VALIDATION, loc + ".incremental.column",
                    "源表没有水位线字段「%s」".formatted(column));
            return;
        }
        String type = EtlConfig.str(incremental, "type");
        if (type != null) {
            type = type.toUpperCase(Locale.ROOT);
            if (!WATERMARK_TYPES.contains(type)) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".incremental.type",
                        "水位线类型无效: " + type, "可选值: TIMESTAMP / NUMBER / STRING");
                return;
            }
        } else {
            type = inferWatermarkType(tableCols.get(column));
            if (type == null) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".incremental.type",
                        "无法从表结构推断水位线类型,请显式指定 type",
                        "可选值: TIMESTAMP / NUMBER / STRING");
                return;
            }
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("nodeId", id);
        spec.put("column", column);
        spec.put("type", type);
        putIfNotNull(spec, "initialValue", EtlConfig.str(incremental, "initialValue"));
        acc.watermarks.add(spec);
        source.put("watermarkColumn", column);
    }

    private static String inferWatermarkType(String canonicalType) {
        if (canonicalType == null) {
            return null;
        }
        String t = canonicalType.toUpperCase(Locale.ROOT);
        if (t.contains("TIME") || t.contains("DATE")) {
            return "TIMESTAMP";
        }
        if (t.contains("INT") || t.contains("LONG") || t.contains("DECIMAL") || t.contains("NUMERIC")
                || t.contains("DOUBLE") || t.contains("FLOAT") || t.contains("NUMBER")) {
            return "NUMBER";
        }
        return "STRING";
    }

    private Out compileFileSource(EtlNode node, Acc acc, CompileResult.Collector collector) {
        String id = node.id();
        String loc = "nodes." + id;
        Map<String, Object> cfg = node.config();
        String format = EtlConfig.str(cfg, "format");
        if (format == null || !FILE_FORMATS.contains(format.toUpperCase(Locale.ROOT))) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".format",
                    "文件格式无效: " + format, "可选值: " + String.join(" / ", new java.util.TreeSet<>(FILE_FORMATS)));
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "FILE");
        if (format != null) {
            source.put("format", format.toUpperCase(Locale.ROOT));
        }
        String ref = requireObjectRef(cfg, loc, "源文件", source, collector);
        putIfNotNull(source, "options", cfg.get("options"));

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("type", "FILE");
        descriptor.put("nodeId", id);
        descriptor.put("path", ref == null ? id : ref);
        acc.sourceDesc.put(id, descriptor);
        acc.incrementalSource.put(id, false);
        putLoad(acc, node, source);
        return declaredColumns(id, cfg, loc, collector);
    }

    private Out compileApiSource(EtlNode node, Acc acc, CompileResult.Collector collector) {
        String id = node.id();
        String loc = "nodes." + id;
        Map<String, Object> cfg = node.config();
        String url = EtlConfig.str(cfg, "url");
        String apiConfigId = EtlConfig.str(cfg, "apiConfigId");
        if (url == null && apiConfigId == null) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc,
                    "接口源必须指定 url 或 apiConfigId");
        }
        if (url != null) {
            String lower = url.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".url",
                        "接口地址只允许 http(s): " + url);
            }
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "API");
        putIfNotNull(source, "url", url);
        putIfNotNull(source, "apiConfigId", apiConfigId);
        putIfNotNull(source, "recordsPath", EtlConfig.str(cfg, "recordsPath"));

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("type", "API");
        descriptor.put("nodeId", id);
        descriptor.put("path", url != null ? url : apiConfigId);
        acc.sourceDesc.put(id, descriptor);
        acc.incrementalSource.put(id, false);
        putLoad(acc, node, source);
        return declaredColumns(id, cfg, loc, collector);
    }

    /** 文件 / 接口源可以声明列:{@code columns:[{name,type}]};没声明则列集合未知。 */
    private Out declaredColumns(String id, Map<String, Object> cfg, String loc,
                                CompileResult.Collector collector) {
        List<Map<String, Object>> declared = EtlConfig.maps(cfg, "columns");
        if (declared.isEmpty()) {
            return Out.unknown(Map.of(), Set.of(id));
        }
        LinkedHashMap<String, Col> cols = new LinkedHashMap<>();
        for (Map<String, Object> item : declared) {
            String name = EtlConfig.str(item, "name");
            if (name == null) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".columns", "列声明缺少 name");
                continue;
            }
            cols.put(name, new Col(EtlConfig.str(item, "type", UNKNOWN_TYPE), null,
                    Set.of(new Origin(id, name)), false, null));
        }
        return new Out(true, cols, Map.of(), Set.of(id));
    }

    private void putLoad(Acc acc, EtlNode node, Map<String, Object> source) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("nodeId", node.id());
        step.put("kind", node.kind().name());
        step.put("step", "LOAD");
        step.put("source", source);
        acc.steps.put(node.id(), step);
    }

    // ── 转换 ────────────────────────────────────────────────────────────

    private Out compileSelect(EtlNode node, String inputId, Out input, Acc acc,
                              CompileResult.Collector collector) {
        String loc = "nodes." + node.id();
        Map<String, Object> cfg = node.config();
        List<String[]> items = selectItems(cfg.get("columns"));
        List<String> exclude = EtlConfig.strings(cfg, "exclude");
        Map<String, Object> rename = EtlConfig.map(cfg, "rename");

        if (items.isEmpty() && exclude.isEmpty() && rename.isEmpty()) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc,
                    "SELECT 节点必须配置 columns / exclude / rename 之一");
            return input;
        }
        if (!items.isEmpty() && (!exclude.isEmpty() || !rename.isEmpty())) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc,
                    "columns 与 exclude / rename 不能同时使用",
                    "用 columns: [{name, as}] 同时表达选择与改名");
            return input;
        }

        String in = EtlSql.ident(inputId);
        LinkedHashMap<String, Col> cols = new LinkedHashMap<>();
        String sql;
        if (!items.isEmpty()) {
            List<String> exprs = new ArrayList<>();
            for (String[] item : items) {
                requireColumn(collector, input, item[0], loc + ".columns." + item[0]);
                String alias = item[1] == null ? item[0] : item[1];
                if (cols.containsKey(alias)) {
                    collector.error(CompileStage.SCHEMA_VALIDATION, loc + ".columns." + alias,
                            "输出列名重复: " + alias);
                }
                if (input.known() && input.cols().containsKey(item[0])) {
                    cols.put(alias, input.cols().get(item[0]));
                }
                exprs.add(item[1] == null ? EtlSql.ident(item[0])
                        : EtlSql.ident(item[0]) + " AS " + EtlSql.ident(item[1]));
            }
            sql = "SELECT " + String.join(", ", exprs) + " FROM " + in;
        } else {
            for (String column : exclude) {
                requireColumn(collector, input, column, loc + ".exclude." + column);
            }
            List<String> renames = new ArrayList<>();
            for (Map.Entry<String, Object> e : rename.entrySet()) {
                requireColumn(collector, input, e.getKey(), loc + ".rename." + e.getKey());
                if (e.getValue() == null || e.getValue().toString().isBlank()) {
                    collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".rename." + e.getKey(),
                            "新列名为空");
                    continue;
                }
                renames.add(EtlSql.ident(e.getKey()) + " AS " + EtlSql.ident(e.getValue().toString()));
            }
            StringBuilder sb = new StringBuilder("SELECT *");
            if (!exclude.isEmpty()) {
                sb.append(" EXCLUDE (").append(String.join(", ", exclude.stream().map(EtlSql::ident).toList())).append(")");
            }
            if (!renames.isEmpty()) {
                sb.append(" RENAME (").append(String.join(", ", renames)).append(")");
            }
            sql = sb.append(" FROM ").append(in).toString();
            if (input.known()) {
                input.cols().forEach((name, col) -> {
                    if (exclude.contains(name)) {
                        return;
                    }
                    Object renamed = rename.get(name);
                    cols.put(renamed == null ? name : renamed.toString(), col);
                });
            }
        }
        putSql(acc, node, sql);
        return input.known() ? new Out(true, cols, Map.of(), input.sources())
                : renameCarry(input, items, rename);
    }

    /** 未知 schema 下 SELECT 改名:敏感携带按新名字继续跟随。 */
    private Out renameCarry(Out input, List<String[]> items, Map<String, Object> rename) {
        Map<String, String> carry = new LinkedHashMap<>(input.carry());
        for (String[] item : items) {
            if (item[1] != null && carry.containsKey(item[0])) {
                carry.put(item[1], carry.get(item[0]));
            }
        }
        rename.forEach((k, v) -> {
            if (v != null && carry.containsKey(k)) {
                carry.put(v.toString(), carry.get(k));
            }
        });
        return Out.unknown(carry, input.sources());
    }

    private Out compileDerive(EtlNode node, String inputId, Out input, Acc acc,
                              CompileResult.Collector collector) {
        String loc = "nodes." + node.id();
        List<Map<String, Object>> derivations = EtlConfig.maps(node.config(), "derivations");
        if (derivations.isEmpty()) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".derivations",
                    "DERIVE 节点至少需要一条派生列");
            return input;
        }
        LinkedHashMap<String, Col> cols = colsOf(input);
        Map<String, String> carry = new LinkedHashMap<>(input.carry());
        List<String> replaces = new ArrayList<>();
        List<String> adds = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> d : derivations) {
            String name = EtlConfig.str(d, "name");
            String expr = EtlConfig.str(d, "expression");
            if (name == null) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".derivations", "派生列缺少 name");
                continue;
            }
            if (!seen.add(name)) {
                collector.error(CompileStage.SCHEMA_VALIDATION, loc + ".derivations." + name,
                        "派生列名重复: " + name);
                continue;
            }
            if (!requireExpression(collector, loc + ".derivations." + name, expr, "派生表达式")) {
                continue;
            }
            Set<String> ids = EtlSqlGuard.identifiersOf(expr);
            String level = null;
            Set<Origin> origins = new LinkedHashSet<>();
            for (Map.Entry<String, Col> e : colsOf(input).entrySet()) {
                if (ids.contains(e.getKey().toUpperCase(Locale.ROOT))) {
                    origins.addAll(e.getValue().origins());
                    level = maxLevel(level, e.getValue().level());
                }
            }
            for (Map.Entry<String, String> e : input.carry().entrySet()) {
                if (ids.contains(e.getKey().toUpperCase(Locale.ROOT))) {
                    level = maxLevel(level, e.getValue());
                }
            }
            String rendered = "(" + expr + ") AS " + EtlSql.ident(name);
            if (input.known() && input.cols().containsKey(name)) {
                replaces.add(rendered);
            } else {
                adds.add(rendered);
            }
            if (input.known()) {
                cols.put(name, new Col(UNKNOWN_TYPE, level, origins, false, expr));
            } else if (level != null) {
                carry.put(name, level);
            }
        }
        StringBuilder sb = new StringBuilder("SELECT *");
        if (!replaces.isEmpty()) {
            sb.append(" REPLACE (").append(String.join(", ", replaces)).append(")");
        }
        for (String add : adds) {
            sb.append(", ").append(add);
        }
        putSql(acc, node, sb.append(" FROM ").append(EtlSql.ident(inputId)).toString());
        return input.known() ? new Out(true, cols, Map.of(), input.sources())
                : Out.unknown(carry, input.sources());
    }

    private Out compileCleanse(EtlNode node, String inputId, Out input, Acc acc,
                               CompileResult.Collector collector) {
        String loc = "nodes." + node.id();
        List<Map<String, Object>> rules = EtlConfig.maps(node.config(), "rules");
        if (rules.isEmpty()) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".rules",
                    "清洗 / 脱敏节点至少需要一条规则");
            return input;
        }
        LinkedHashMap<String, Col> cols = colsOf(input);
        Map<String, String> carry = new LinkedHashMap<>(input.carry());
        Map<String, String> replaceByColumn = new LinkedHashMap<>();
        for (Map<String, Object> rule : rules) {
            String column = EtlConfig.str(rule, "column");
            String action = EtlConfig.str(rule, "action");
            String rloc = loc + ".rules." + column;
            if (column == null || action == null) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".rules",
                        "规则必须同时有 column 与 action");
                continue;
            }
            action = action.toUpperCase(Locale.ROOT);
            if (!CLEANSE_ACTIONS.contains(action)) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, rloc + ".action",
                        "未知的清洗动作: " + action,
                        "可选值: " + String.join(" / ", new java.util.TreeSet<>(CLEANSE_ACTIONS)));
                continue;
            }
            if (replaceByColumn.containsKey(column)) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, rloc,
                        "同一字段只能配一条规则(多条会互相覆盖): " + column,
                        "需要组合处理请先 CLEANSE_MASK 再接一个 CLEANSE_MASK 节点");
                continue;
            }
            requireColumn(collector, input, column, rloc);
            String col = EtlSql.ident(column);
            String expr = switch (action) {
                case "TRIM" -> "trim(CAST(" + col + " AS VARCHAR))";
                case "UPPER" -> "upper(CAST(" + col + " AS VARCHAR))";
                case "LOWER" -> "lower(CAST(" + col + " AS VARCHAR))";
                case "DEFAULT_IF_NULL" -> {
                    String value = EtlConfig.str(rule, "value");
                    if (value == null) {
                        collector.error(CompileStage.STRUCTURAL_VALIDATION, rloc + ".value",
                                "DEFAULT_IF_NULL 必须指定 value");
                        yield null;
                    }
                    yield "coalesce(" + col + ", " + EtlSql.literal(value) + ")";
                }
                case "MASK_PARTIAL" -> maskPartial(rule, col, rloc, collector);
                case "HASH_SHA256" -> "sha256(CAST(" + col + " AS VARCHAR))";
                case "REDACT" -> "CASE WHEN " + col + " IS NULL THEN NULL ELSE '***' END";
                case "NULLIFY" -> "CAST(NULL AS VARCHAR)";
                default -> null;
            };
            if (expr == null) {
                continue;
            }
            replaceByColumn.put(column, "(" + expr + ") AS " + col);
            if (MASKING_ACTIONS.contains(action)) {
                Col old = cols.get(column);
                if (old != null) {
                    cols.put(column, new Col("VARCHAR", null, old.origins(), true, action + "(" + column + ")"));
                } else {
                    carry.remove(column);
                }
            } else {
                Col old = cols.get(column);
                if (old != null) {
                    cols.put(column, new Col("VARCHAR", old.level(), old.origins(), old.masked(), action + "(" + column + ")"));
                }
            }
        }
        if (!replaceByColumn.isEmpty()) {
            putSql(acc, node, "SELECT * REPLACE (" + String.join(", ", replaceByColumn.values())
                    + ") FROM " + EtlSql.ident(inputId));
        }
        return input.known() ? new Out(true, cols, Map.of(), input.sources())
                : Out.unknown(carry, input.sources());
    }

    private String maskPartial(Map<String, Object> rule, String col, String rloc,
                               CompileResult.Collector collector) {
        Long prefix = EtlConfig.longVal(rule, "keepPrefix");
        Long suffix = EtlConfig.longVal(rule, "keepSuffix");
        long p = prefix == null ? 3 : prefix;
        long s = suffix == null ? 4 : suffix;
        if (p < 0 || s < 0 || p > 64 || s > 64) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, rloc,
                    "keepPrefix / keepSuffix 必须在 0~64 之间");
            return null;
        }
        String maskChar = EtlConfig.str(rule, "maskChar", "*");
        if (maskChar.length() != 1) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, rloc + ".maskChar", "maskChar 必须是单个字符");
            return null;
        }
        String v = "CAST(" + col + " AS VARCHAR)";
        String mask = EtlSql.literal(maskChar);
        return "CASE WHEN " + col + " IS NULL THEN NULL "
                + "WHEN length(" + v + ") <= " + (p + s) + " THEN repeat(" + mask + ", length(" + v + ")) "
                + "ELSE substr(" + v + ", 1, " + p + ") || repeat(" + mask + ", length(" + v + ") - " + (p + s)
                + ") || substr(" + v + ", length(" + v + ") - " + s + " + 1) END";
    }

    private Out compileJoin(EtlNode node, List<EtlEdge> in, Acc acc, CompileResult.Collector collector) {
        String loc = "nodes." + node.id();
        EtlEdge leftEdge = in.get(0);
        EtlEdge rightEdge = in.get(1);
        if ("right".equals(in.get(0).port())) {
            leftEdge = in.get(1);
            rightEdge = in.get(0);
        }
        Out left = acc.outs.get(leftEdge.from());
        Out right = acc.outs.get(rightEdge.from());
        Map<String, Object> cfg = node.config();

        String joinType = EtlConfig.str(cfg, "joinType", "INNER").toUpperCase(Locale.ROOT);
        if (!JOIN_TYPES.contains(joinType)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".joinType",
                    "连接类型无效: " + joinType, "可选值: INNER / LEFT / RIGHT / FULL");
            joinType = "INNER";
        }

        List<String[]> keys = new ArrayList<>();
        Object rawOn = cfg.get("on");
        if (rawOn instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Object l = m.get("left");
                    Object r = m.get("right");
                    if (l != null && r != null) {
                        keys.add(new String[]{l.toString(), r.toString()});
                    }
                } else if (item != null && !item.toString().isBlank()) {
                    keys.add(new String[]{item.toString(), item.toString()});
                }
            }
        }
        if (keys.isEmpty()) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".on",
                    "JOIN 必须指定连接键 on: [{left, right}]",
                    "没有连接键的 JOIN 是笛卡尔积,几乎一定是配置遗漏");
            return Out.unknown(Map.of(), union(left.sources(), right.sources()));
        }
        for (String[] key : keys) {
            requireColumn(collector, left, key[0], loc + ".on.left." + key[0]);
            requireColumn(collector, right, key[1], loc + ".on.right." + key[1]);
        }

        boolean sameNames = keys.stream().allMatch(k -> k[0].equals(k[1]));
        String l = EtlSql.ident(leftEdge.from());
        String r = EtlSql.ident(rightEdge.from());
        StringBuilder from = new StringBuilder(" FROM " + l + " AS l " + joinType + " JOIN " + r + " AS r ");
        List<String> rightKeys = keys.stream().map(k -> k[1]).toList();
        if (sameNames) {
            from.append("USING (").append(String.join(", ", keys.stream().map(k -> EtlSql.ident(k[0])).toList())).append(")");
        } else {
            from.append("ON ").append(String.join(" AND ", keys.stream()
                    .map(k -> "l." + EtlSql.ident(k[0]) + " = r." + EtlSql.ident(k[1])).toList()));
        }

        Set<String> sources = union(left.sources(), right.sources());
        if (left.known() && right.known()) {
            LinkedHashMap<String, Col> cols = new LinkedHashMap<>();
            List<String> select = new ArrayList<>();
            left.cols().forEach((name, col) -> {
                cols.put(name, col);
                select.add(sameNames && rightKeys.contains(name)
                        ? "coalesce(l." + EtlSql.ident(name) + ", r." + EtlSql.ident(name) + ") AS " + EtlSql.ident(name)
                        : "l." + EtlSql.ident(name));
            });
            right.cols().forEach((name, col) -> {
                if (sameNames && rightKeys.contains(name)) {
                    return; // USING 键已由左侧合并输出
                }
                String outName = name;
                if (cols.containsKey(name)) {
                    outName = name + "_r";
                    collector.warn(CompileStage.SCHEMA_VALIDATION, loc,
                            "左右两侧都有字段「%s」,右侧的已改名为「%s」".formatted(name, outName),
                            "需要别的名字请在 JOIN 前后用 SELECT 改名");
                }
                cols.put(outName, col);
                select.add("r." + EtlSql.ident(name) + (outName.equals(name) ? "" : " AS " + EtlSql.ident(outName)));
            });
            putSql(acc, node, "SELECT " + String.join(", ", select) + from);
            return new Out(true, cols, Map.of(), sources);
        }

        String rightStar = sameNames
                ? "r.* EXCLUDE (" + String.join(", ", rightKeys.stream().map(EtlSql::ident).toList()) + ")"
                : "r.*";
        putSql(acc, node, "SELECT l.*, " + rightStar + from);
        Map<String, String> carry = new LinkedHashMap<>();
        carryOf(left).forEach(carry::put);
        carryOf(right).forEach(carry::putIfAbsent);
        return Out.unknown(carry, sources);
    }

    private Out compileAggregate(EtlNode node, String inputId, Out input, Acc acc,
                                 CompileResult.Collector collector) {
        String loc = "nodes." + node.id();
        Map<String, Object> cfg = node.config();
        List<String> groupBy = EtlConfig.strings(cfg, "groupBy");
        List<Map<String, Object>> aggs = EtlConfig.maps(cfg, "aggregations");
        if (aggs.isEmpty()) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".aggregations",
                    "AGGREGATE 节点至少需要一个聚合");
            return input;
        }
        LinkedHashMap<String, Col> cols = new LinkedHashMap<>();
        Map<String, String> carry = new LinkedHashMap<>();
        List<String> select = new ArrayList<>();
        for (String g : groupBy) {
            requireColumn(collector, input, g, loc + ".groupBy." + g);
            select.add(EtlSql.ident(g));
            if (input.known() && input.cols().containsKey(g)) {
                cols.put(g, input.cols().get(g));
            } else if (input.carry().containsKey(g)) {
                carry.put(g, input.carry().get(g));
            }
        }
        for (Map<String, Object> agg : aggs) {
            String func = EtlConfig.str(agg, "func");
            String column = EtlConfig.str(agg, "column");
            if (func == null || column == null) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".aggregations",
                        "聚合必须同时有 func 与 column(COUNT 可用 \"*\")");
                continue;
            }
            func = func.toUpperCase(Locale.ROOT);
            if (!AGG_FUNCS.contains(func)) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".aggregations",
                        "未知聚合函数: " + func, "可选值: " + String.join(" / ", new java.util.TreeSet<>(AGG_FUNCS)));
                continue;
            }
            boolean star = column.equals("*");
            if (star && !func.equals("COUNT")) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".aggregations",
                        "只有 COUNT 可以作用于 *");
                continue;
            }
            if (!star) {
                requireColumn(collector, input, column, loc + ".aggregations." + column);
            }
            String alias = EtlConfig.str(agg, "as", func.toLowerCase(Locale.ROOT) + "_" + (star ? "all" : column));
            if (cols.containsKey(alias) || carry.containsKey(alias) || groupBy.contains(alias)) {
                collector.error(CompileStage.SCHEMA_VALIDATION, loc + ".aggregations." + alias,
                        "输出列名重复: " + alias);
                continue;
            }
            String sqlFunc = func.equals("COUNT_DISTINCT") ? "COUNT(DISTINCT " + EtlSql.ident(column) + ")"
                    : func + "(" + (star ? "*" : EtlSql.ident(column)) + ")";
            select.add(sqlFunc + " AS " + EtlSql.ident(alias));

            // COUNT 类聚合不泄露原值;SUM/MIN/MAX/AVG 对敏感字段仍视为敏感
            boolean leaksValue = !func.startsWith("COUNT");
            if (input.known()) {
                Col src = star ? null : input.cols().get(column);
                cols.put(alias, new Col(UNKNOWN_TYPE, leaksValue && src != null ? src.level() : null,
                        src == null ? Set.of() : src.origins(), src != null && src.masked(),
                        func + "(" + column + ")"));
            } else if (leaksValue && input.carry().containsKey(column)) {
                carry.put(alias, input.carry().get(column));
            }
        }
        String sql = "SELECT " + String.join(", ", select) + " FROM " + EtlSql.ident(inputId)
                + (groupBy.isEmpty() ? "" : " GROUP BY " + String.join(", ", groupBy.stream().map(EtlSql::ident).toList()));
        putSql(acc, node, sql);
        return input.known() ? new Out(true, cols, Map.of(), input.sources())
                : Out.unknown(carry, input.sources());
    }

    private Out compileDedup(EtlNode node, String inputId, Out input, Acc acc,
                             CompileResult.Collector collector) {
        String loc = "nodes." + node.id();
        Map<String, Object> cfg = node.config();
        List<String> keys = EtlConfig.strings(cfg, "keys");
        if (keys.isEmpty()) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".keys", "DEDUP 必须指定去重键 keys");
            return input;
        }
        keys.forEach(k -> requireColumn(collector, input, k, loc + ".keys." + k));
        String orderBy = EtlConfig.str(cfg, "orderBy");
        String order = EtlConfig.str(cfg, "order", "DESC").toUpperCase(Locale.ROOT);
        if (!order.equals("ASC") && !order.equals("DESC")) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".order", "order 只能是 ASC / DESC");
            order = "DESC";
        }
        if (orderBy == null) {
            collector.warn(CompileStage.STRUCTURAL_VALIDATION, loc,
                    "DEDUP 没有指定 orderBy,同键多行时保留哪一行是不确定的",
                    "指定 orderBy(如更新时间)并选择 DESC 保留最新");
        } else {
            requireColumn(collector, input, orderBy, loc + ".orderBy");
        }
        String partition = String.join(", ", keys.stream().map(EtlSql::ident).toList());
        putSql(acc, node, "SELECT * FROM " + EtlSql.ident(inputId)
                + " QUALIFY row_number() OVER (PARTITION BY " + partition
                + (orderBy == null ? "" : " ORDER BY " + EtlSql.ident(orderBy) + " " + order) + ") = 1");
        return input;
    }

    private Out compileCustomSql(EtlNode node, List<EtlEdge> in, Acc acc, CompileResult.Collector collector) {
        String loc = "nodes." + node.id();
        Set<String> upstream = new LinkedHashSet<>();
        in.forEach(e -> upstream.add(e.from()));
        Set<String> sources = new LinkedHashSet<>();
        Map<String, String> carry = new LinkedHashMap<>();
        for (String up : upstream) {
            Out o = acc.outs.get(up);
            sources.addAll(o.sources());
            carryOf(o).forEach((k, v) -> carry.merge(k, v, EtlPipelineCompiler::maxLevel));
        }

        EtlSqlGuard.Result guard = EtlSqlGuard.validateSelect(EtlConfig.str(node.config(), "sql"), upstream);
        if (!guard.ok()) {
            for (String problem : guard.problems()) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".sql", problem,
                        "自定义 SQL 只允许单条 SELECT / WITH,并通过 {{上游节点id}} 引用上游表");
            }
            return Out.unknown(Map.of(), sources);
        }
        putSql(acc, node, guard.rewritten());

        // 输出列无法静态推断:按"出现过的列名(或 SELECT *)"粗粒度携带上游敏感级别
        Map<String, String> outCarry = new LinkedHashMap<>();
        carry.forEach((name, level) -> {
            if (guard.hasStar() || guard.words().contains(name.toUpperCase(Locale.ROOT))) {
                outCarry.put(name, level);
            }
        });
        return Out.unknown(outCarry, sources);
    }

    // ── 码表映射 ────────────────────────────────────────────────────────

    /**
     * TRANSFORM_CODESET_LOOKUP:把某一列的代码值按平台权威码表标准化。
     *
     * <p>config:
     * <pre>
     * codesetRef   必填  码表(语义对象)id 或 code;别名 codesetId / codesetCode
     * column       必填  待标准化的上游列
     * outputColumn 可选  输出列名;缺省 = column(原地替换);不同名则新增一列,保留原列
     * mapTo        可选  CANONICAL_VALUE(缺省,同义词归一到值字典 raw_value) | DISPLAY_LABEL
     * onMiss       可选  KEEP_ORIGINAL(缺省) | NULL | DEFAULT(需 defaultValue) | BAD_ROW
     * ignoreCase   可选  false
     * </pre>
     * 未命中语义:NULL 输入不算未命中(它本来就没有代码);BAD_ROW 把未命中行移出主流并按
     * errorPolicy 处理;其余三种保留行,但未命中数照常统计(见计划里的 {@code codesets})。
     *
     * <p>C2:码表必须存在且不是已废弃 / 已驳回的;未发布的只告警。
     * 条目<b>不</b>进入计划,执行期按 {@code codesetId} 解析当时发布的版本。
     */
    private Out compileCodesetLookup(EtlNode node, String inputId, Out input, Acc acc, EtlPipelineIr ir,
                                     MetadataLookup metadata, String workspaceId,
                                     CompileResult.Collector collector) {
        String id = node.id();
        String loc = "nodes." + id;
        Map<String, Object> cfg = node.config();

        String ref = EtlConfig.str(cfg, "codesetRef",
                EtlConfig.str(cfg, "codesetId", EtlConfig.str(cfg, "codesetCode")));
        String column = EtlConfig.str(cfg, "column");
        String outputColumn = EtlConfig.str(cfg, "outputColumn", column);
        String mapTo = EtlConfig.str(cfg, "mapTo", "CANONICAL_VALUE").toUpperCase(Locale.ROOT);
        String onMiss = EtlConfig.str(cfg, "onMiss", "KEEP_ORIGINAL").toUpperCase(Locale.ROOT);
        boolean ignoreCase = EtlConfig.bool(cfg, "ignoreCase", false);
        String defaultValue = EtlConfig.str(cfg, "defaultValue");

        boolean valid = true;
        if (ref == null) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".codesetRef",
                    "码表映射必须指定 codesetRef(码表的语义对象 id 或 code)");
            valid = false;
        }
        if (column == null) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".column", "码表映射必须指定 column");
            valid = false;
        }
        if (!CODESET_MAP_TO.contains(mapTo)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".mapTo",
                    "mapTo 无效: " + mapTo, "可选值: CANONICAL_VALUE / DISPLAY_LABEL");
            valid = false;
        }
        if (!CODESET_ON_MISS.contains(onMiss)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".onMiss",
                    "onMiss 无效: " + onMiss, "可选值: KEEP_ORIGINAL / NULL / DEFAULT / BAD_ROW");
            valid = false;
        } else if ("DEFAULT".equals(onMiss) && defaultValue == null) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".defaultValue",
                    "onMiss=DEFAULT 必须指定 defaultValue");
            valid = false;
        }

        // C2:码表必须是平台里真实存在的权威标准,而不是配置里随手写的一个名字
        CodesetInfo info = null;
        if (ref != null) {
            info = metadata.codeset(workspaceId, ref).orElse(null);
            if (info == null) {
                collector.error(CompileStage.DEPENDENCY_VALIDATION, loc + ".codesetRef",
                        "码表「%s」不存在,或在当前空间不可见".formatted(ref),
                        "先在「元数据 → 语义本体」发布一个带值字典的码集,再引用它的 id 或 code");
                valid = false;
            } else if (info.retired()) {
                collector.error(CompileStage.DEPENDENCY_VALIDATION, loc + ".codesetRef",
                        "码表「%s」已是 %s 状态,不能再被引用".formatted(info.code(), info.status()),
                        "改用现行发布的码表");
                valid = false;
            } else {
                if (!info.published()) {
                    collector.warn(CompileStage.DEPENDENCY_VALIDATION, loc + ".codesetRef",
                            "码表「%s」当前为 %s,尚未发布,它不是权威标准".formatted(info.code(), info.status()),
                            "发布后再上线管道,否则映射结果可能随码表修订而变化");
                }
                if (info.entryCount() == 0) {
                    collector.warn(CompileStage.DEPENDENCY_VALIDATION, loc + ".codesetRef",
                            "码表「%s」的值字典为空,所有值都会未命中".formatted(info.code()),
                            "先为码集维护值字典条目");
                }
            }
        }

        requireColumn(collector, input, column, loc + ".column");
        boolean inPlace = column != null && column.equals(outputColumn);
        if (!inPlace && input.known() && outputColumn != null && input.cols().containsKey(outputColumn)) {
            collector.error(CompileStage.SCHEMA_VALIDATION, loc + ".outputColumn",
                    "输出列「%s」已存在于上游".formatted(outputColumn),
                    "想覆盖原列请省略 outputColumn;想新增列请换一个名字");
            valid = false;
        }
        if ("BAD_ROW".equals(onMiss) && EtlErrorPolicy.FAIL_FAST.equals(ir.errorPolicy().mode())) {
            collector.warn(CompileStage.PLAN_OPTIMIZATION, loc + ".onMiss",
                    "onMiss=BAD_ROW 且 errorPolicy=FAIL_FAST:出现任何一个未命中值,整次执行都会失败",
                    "想容忍少量未命中,请把 errorPolicy 改为 SKIP_BAD_ROWS / ROUTE_TO_DEAD_LETTER 并设阈值");
        }
        if (!valid || column == null || ref == null) {
            return input;
        }

        String csTable = CODESET_TABLE_PREFIX + id;
        String codeRef = info == null ? ref : info.code();
        String col = EtlSql.ident(column);
        String keyExpr = ignoreCase ? "lower(CAST(t." + col + " AS VARCHAR))" : "CAST(t." + col + " AS VARCHAR)";
        String valueExpr = switch (onMiss) {
            case "NULL", "BAD_ROW" -> "cs.\"std\"";
            case "DEFAULT" -> "CASE WHEN t." + col + " IS NULL THEN NULL ELSE coalesce(cs.\"std\", "
                    + EtlSql.literal(defaultValue) + ") END";
            default -> "coalesce(cs.\"std\", CAST(t." + col + " AS VARCHAR))";
        };
        String from = " FROM " + EtlSql.ident(inputId) + " AS t LEFT JOIN " + EtlSql.ident(csTable)
                + " AS cs ON " + keyExpr + " = cs.\"code\""
                + ("BAD_ROW".equals(onMiss) ? " WHERE (t." + col + " IS NULL OR cs.\"code\" IS NOT NULL)" : "");
        String select = inPlace
                ? "SELECT t.* REPLACE (" + valueExpr + " AS " + col + ")"
                : "SELECT t.*, " + valueExpr + " AS " + EtlSql.ident(outputColumn);
        putSql(acc, node, select + from);

        Col inCol = input.known() ? input.cols().get(column) : null;
        // 未命中样例只在「列已知、没有敏感标签、也不是脱敏产物」时允许进摘要(C5:摘要里没有 L3/L4 明文)
        boolean sampleValues = inCol != null && inCol.level() == null && !inCol.masked();
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("nodeId", id);
        spec.put("table", csTable);
        spec.put("codesetRef", ref);
        putIfNotNull(spec, "codesetId", info == null ? null : info.id());
        putIfNotNull(spec, "codesetCode", info == null ? null : info.code());
        spec.put("inputNodeId", inputId);
        spec.put("column", column);
        spec.put("outputColumn", outputColumn);
        spec.put("mapTo", mapTo);
        spec.put("onMiss", onMiss);
        spec.put("ignoreCase", ignoreCase);
        putIfNotNull(spec, "defaultValue", defaultValue);
        spec.put("sampleValues", sampleValues);
        acc.steps.get(id).put("codeset", spec);
        acc.codesets.add(spec);

        String expr = "CODESET(" + codeRef + ", " + mapTo + ")(" + column + ")";
        if (input.known()) {
            LinkedHashMap<String, Col> cols = new LinkedHashMap<>(input.cols());
            Col out = inCol == null
                    ? new Col("VARCHAR", null, Set.of(), false, expr)
                    : new Col("VARCHAR", inCol.level(), inCol.origins(), inCol.masked(), expr);
            cols.put(outputColumn, out);
            return new Out(true, cols, Map.of(), input.sources());
        }
        Map<String, String> carry = new LinkedHashMap<>(input.carry());
        if (!inPlace && carry.containsKey(column)) {
            carry.put(outputColumn, carry.get(column));
        }
        return Out.unknown(carry, input.sources());
    }

    // ── 目标 ────────────────────────────────────────────────────────────

    private void compileJdbcSink(EtlNode node, String inputId, Out input, Acc acc, EtlPipelineIr ir,
                                 MetadataLookup metadata, String workspaceId,
                                 CompileResult.Collector collector) {
        String id = node.id();
        String loc = "nodes." + id;
        Map<String, Object> cfg = node.config();
        String ds = EtlConfig.str(cfg, "dataSourceId");
        String db = EtlConfig.str(cfg, "database");
        String schema = EtlConfig.str(cfg, "schema");
        String table = EtlConfig.str(cfg, "table");

        boolean dsOk = CompilerSupport.requireAvailableDataSource(
                collector, metadata, workspaceId, ds, loc + ".dataSourceId", "目标数据源");
        final Map<String, String> targetCols = dsOk
                ? CompilerSupport.requireTableColumns(collector, metadata, workspaceId, ds,
                        db, schema, table, loc + ".table", "目标表")
                : Map.of();
        if (!dsOk && table == null) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".table", "目标表未指定");
        }

        String writeMode = EtlConfig.str(cfg, "writeMode", "APPEND").toUpperCase(Locale.ROOT);
        if (!WRITE_MODES.contains(writeMode)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".writeMode",
                    "写入模式无效: " + writeMode, "可选值: APPEND / OVERWRITE / UPSERT");
        }

        Map<String, String> mapping = sinkMapping(cfg, input);
        // 目标列校验:仅当上游列已知且有映射时才做
        if (input.known() && !mapping.isEmpty()) {
            if (!targetCols.isEmpty()) {
                Map<String, String> sourceTypes = new LinkedHashMap<>();
                mapping.forEach((src, tgt) -> {
                    Col col = input.cols().get(src);
                    String type = col == null ? null : col.type();
                    // 类型未知(如派生列)不做兼容性判断,但存在性仍要校验
                    sourceTypes.put(src, type == null || UNKNOWN_TYPE.equals(type)
                            ? targetColumnType(targetCols, tgt) : type);
                });
                CompilerSupport.validateFieldMappings(collector, sourceTypes, targetCols, mapping,
                        loc + ".columnMapping");
            }
        } else if (!input.known() && !targetCols.isEmpty()) {
            collector.warn(CompileStage.SCHEMA_VALIDATION, loc,
                    "上游输出列无法静态推断,已跳过与目标表的字段映射校验",
                    "自定义 SQL 之后可接一个 SELECT 节点显式声明列");
        }

        if ("UPSERT".equals(writeMode)) {
            validateUpsert(collector, cfg, loc, mapping, input, targetCols,
                    dsOk ? metadata.dataSourceType(workspaceId, ds) : null);
        }
        if ("OVERWRITE".equals(writeMode)) {
            collector.warn(CompileStage.STRUCTURAL_VALIDATION, loc + ".writeMode",
                    "OVERWRITE 会在写入前清空目标表",
                    "确认目标表没有其它来源的数据,否则它们会一并被删除");
            if (acc.incrementalSource.containsValue(true)) {
                collector.warn(CompileStage.STRUCTURAL_VALIDATION, loc + ".writeMode",
                        "增量抽取配合 OVERWRITE:目标表会被只含增量的数据整表覆盖",
                        "增量场景请用 APPEND 或 UPSERT");
            }
        }

        int batchSize = DEFAULT_BATCH_SIZE;
        if (cfg.get("batchSize") != null) {
            Long raw = EtlConfig.longVal(cfg, "batchSize");
            if (raw == null || raw < 1 || raw > MAX_BATCH_SIZE) {
                collector.error(CompileStage.PLAN_OPTIMIZATION, loc + ".batchSize",
                        "写入批次必须是 1-%d 之间的整数,当前 %s".formatted(MAX_BATCH_SIZE, cfg.get("batchSize")));
            } else {
                batchSize = raw.intValue();
            }
        }

        Map<String, Object> sink = new LinkedHashMap<>();
        sink.put("type", "JDBC");
        sink.put("nodeId", id);
        sink.put("dataSourceId", ds);
        putIfNotNull(sink, "database", db);
        putIfNotNull(sink, "schema", schema);
        sink.put("table", table);
        sink.put("writeMode", writeMode);
        if ("UPSERT".equals(writeMode)) {
            sink.put("primaryKeys", EtlConfig.strings(cfg, "primaryKeys"));
        }
        sink.put("batchSize", batchSize);
        if (!mapping.isEmpty()) {
            sink.put("columnMapping", new LinkedHashMap<>(mapping));
        }
        sink.put("errorPolicy", ir.errorPolicy().toMap());

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", "TABLE");
        target.put("nodeId", id);
        target.put("dataSourceId", ds);
        putIfNotNull(target, "database", db);
        putIfNotNull(target, "schema", schema);
        target.put("table", table);
        finishSink(node, inputId, input, mapping, target, sink, acc, collector);
    }

    private void compileParquetSink(EtlNode node, String inputId, Out input, Acc acc,
                                    CompileResult.Collector collector) {
        String loc = "nodes." + node.id();
        Map<String, Object> cfg = node.config();
        Map<String, Object> sink = new LinkedHashMap<>();
        sink.put("type", "PARQUET");
        sink.put("nodeId", node.id());
        String ref = requireObjectRef(cfg, loc, "目标文件", sink, collector);

        String compression = EtlConfig.str(cfg, "compression", "SNAPPY").toUpperCase(Locale.ROOT);
        if (!COMPRESSIONS.contains(compression)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".compression",
                    "压缩格式无效: " + compression, "可选值: SNAPPY / ZSTD / GZIP / NONE");
        }
        sink.put("compression", compression);
        List<String> partitionBy = EtlConfig.strings(cfg, "partitionBy");
        partitionBy.forEach(p -> requireColumn(collector, input, p, loc + ".partitionBy." + p));
        if (!partitionBy.isEmpty()) {
            sink.put("partitionBy", partitionBy);
        }
        sink.put("writeMode", EtlConfig.str(cfg, "writeMode", "OVERWRITE").toUpperCase(Locale.ROOT));

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", "FILE");
        target.put("nodeId", node.id());
        target.put("path", ref == null ? node.id() : ref);
        finishSink(node, inputId, input, Map.of(), target, sink, acc, collector);
    }

    /** Sink 收尾:敏感字段提醒、血缘边、SINK 步骤。 */
    private void finishSink(EtlNode node, String inputId, Out input, Map<String, String> mapping,
                            Map<String, Object> target, Map<String, Object> sink, Acc acc,
                            CompileResult.Collector collector) {
        String loc = "nodes." + node.id();
        // 敏感字段:L3/L4 未经 CLEANSE_MASK 落到目标。MVP 只提醒不阻断 ——
        // 级别标注可能滞后或偏严,硬拦会让用户被迫绕开平台
        if (input.known()) {
            input.cols().forEach((name, col) -> {
                boolean written = mapping.isEmpty() || mapping.containsKey(name);
                if (written && isHigh(col.level())) {
                    collector.warn(CompileStage.PLAN_OPTIMIZATION, loc + "." + name,
                            "%s 级敏感字段「%s」未经脱敏将写入目标".formatted(col.level(), name),
                            "在 Sink 之前加 TRANSFORM_CLEANSE_MASK 对它做 MASK_PARTIAL / HASH_SHA256 / REDACT,"
                                    + "或确认目标有同等的访问控制");
                }
            });
        } else {
            input.carry().forEach((name, level) -> {
                if (isHigh(level)) {
                    collector.warn(CompileStage.PLAN_OPTIMIZATION, loc + "." + name,
                            "%s 级敏感字段「%s」可能未经脱敏流入目标(上游列无法静态推断,按名称匹配)"
                                    .formatted(level, name),
                            "在自定义 SQL 之后加 TRANSFORM_CLEANSE_MASK,或在 SQL 里显式脱敏");
                }
            });
        }

        acc.lineage = lineageEdges(input, mapping, target, acc);
        acc.sinkSpec = sink;
        acc.sinkNodeId = node.id();

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("nodeId", node.id());
        step.put("kind", node.kind().name());
        step.put("step", "SINK");
        step.put("input", inputId);
        step.put("sink", sink);
        acc.steps.put(node.id(), step);
    }

    private void validateUpsert(CompileResult.Collector collector, Map<String, Object> cfg, String loc,
                                Map<String, String> mapping, Out input, Map<String, String> targetCols,
                                DataSourceType targetType) {
        List<String> keys = EtlConfig.strings(cfg, "primaryKeys");
        if (keys.isEmpty()) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".primaryKeys",
                    "UPSERT 模式必须指定主键字段", "改用 APPEND,或补上用于判断记录是否已存在的字段");
            return;
        }
        for (String key : keys) {
            if (!targetCols.isEmpty() && !targetCols.containsKey(key)) {
                collector.error(CompileStage.SCHEMA_VALIDATION, loc + ".primaryKeys." + key,
                        "目标表没有主键字段「%s」".formatted(key));
            } else if (input.known() && !mapping.isEmpty() && !mapping.containsValue(key)) {
                // 主键没有被写入:UPSERT 永远匹配不上,退化成 APPEND 且无报错
                collector.error(CompileStage.SCHEMA_VALIDATION, loc + ".primaryKeys." + key,
                        "主键字段「%s」没有出现在写入的字段里".formatted(key),
                        "UPSERT 靠主键判断记录是否存在,它必须有值可写");
            }
        }
        if (targetType == DataSourceType.DORIS || targetType == DataSourceType.STARROCKS) {
            collector.warn(CompileStage.STRUCTURAL_VALIDATION, loc + ".writeMode",
                    "%s 没有 UPSERT 语法,按主键覆盖由表模型保证".formatted(targetType.displayName()),
                    "目标表必须建成 UNIQUE KEY(Doris)或主键模型(StarRocks)");
        } else if (targetType == DataSourceType.POSTGRESQL) {
            collector.warn(CompileStage.STRUCTURAL_VALIDATION, loc + ".primaryKeys",
                    "PostgreSQL 的 ON CONFLICT 依赖目标表上真实存在的主键或唯一约束",
                    "这几列上没有约束时执行会直接报错(42P10)");
        }
    }

    /** Sink 的字段映射:显式 columnMapping 优先;否则上游列已知时为恒等映射;未知时为空。 */
    private Map<String, String> sinkMapping(Map<String, Object> cfg, Out input) {
        Map<String, Object> explicit = EtlConfig.map(cfg, "columnMapping");
        Map<String, String> mapping = new LinkedHashMap<>();
        if (!explicit.isEmpty()) {
            explicit.forEach((k, v) -> mapping.put(k, v == null ? null : v.toString()));
            return mapping;
        }
        if (input.known()) {
            input.cols().keySet().forEach(name -> mapping.put(name, name));
        }
        return mapping;
    }

    private static String targetColumnType(Map<String, String> targetCols, String target) {
        String type = target == null ? null : targetCols.get(target);
        return type == null ? "" : type;
    }

    // ── 血缘 ────────────────────────────────────────────────────────────

    private List<Map<String, Object>> lineageEdges(Out input, Map<String, String> mapping,
                                                   Map<String, Object> target, Acc acc) {
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (input.known()) {
            for (Map.Entry<String, Col> e : input.cols().entrySet()) {
                String srcName = e.getKey();
                if (!mapping.isEmpty() && !mapping.containsKey(srcName)) {
                    continue;
                }
                String tgtName = mapping.isEmpty() ? srcName : mapping.get(srcName);
                Col col = e.getValue();
                for (Origin origin : col.origins()) {
                    Map<String, Object> from = new LinkedHashMap<>(acc.sourceDesc.get(origin.nodeId()));
                    from.put("column", origin.column());
                    Map<String, Object> to = new LinkedHashMap<>(target);
                    to.put("column", tgtName);
                    if (!seen.add(origin.nodeId() + "." + origin.column() + ">" + tgtName)) {
                        continue;
                    }
                    edges.add(lineageEdge(from, to, col.expr(), col.masked(), 1.0));
                }
            }
            if (!edges.isEmpty()) {
                return edges;
            }
        }
        // 列级不可得:退化为表级,置信度低一档,让消费方知道这是推断
        for (String sourceId : new java.util.TreeSet<>(input.sources())) {
            Map<String, Object> from = new LinkedHashMap<>(acc.sourceDesc.get(sourceId));
            edges.add(lineageEdge(from, new LinkedHashMap<>(target), null, false, 0.5));
        }
        return edges;
    }

    private Map<String, Object> lineageEdge(Map<String, Object> from, Map<String, Object> to,
                                            String transformExpr, boolean masked, double confidence) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("relation", "ETL");
        edge.put("from", from);
        edge.put("to", to);
        putIfNotNull(edge, "transformExpr", transformExpr);
        edge.put("dependencyType", "DIRECT");
        edge.put("maskingApplied", masked);
        edge.put("confidence", confidence);
        return edge;
    }

    // ── 计划 ────────────────────────────────────────────────────────────

    private String validateEngine(Map<String, Object> config, CompileResult.Collector collector) {
        String engine = EtlConfig.str(config, "engine", "DUCKDB").toUpperCase(Locale.ROOT);
        if (!ENGINES.contains(engine)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "engine",
                    "执行引擎无效: " + engine, "可选值: DUCKDB / SEATUNNEL");
            return "DUCKDB";
        }
        if ("SEATUNNEL".equals(engine)) {
            collector.warn(CompileStage.PLAN_OPTIMIZATION, "engine",
                    "SEATUNNEL 引擎目前只支持把计划翻译成 HOCON,不会真正执行",
                    "该管道发布后执行会失败;需要执行请改回 DUCKDB(SeaTunnel 执行属于 Phase 3 的非目标)");
        }
        return engine;
    }

    private Map<String, Object> buildPlan(EtlPipelineIr ir, Graph graph, List<String> order, Acc acc,
                                          String engine, String jobId,
                                          CompileResult.Collector collector) {
        // 只保留对 Sink 有贡献的节点:孤立分支既不执行也不产生血缘
        Set<String> contributing = new HashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        queue.add(acc.sinkNodeId);
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (contributing.add(id)) {
                graph.incoming.get(id).forEach(e -> queue.add(e.from()));
            }
        }
        List<String> executionOrder = order.stream().filter(contributing::contains).toList();
        for (String id : order) {
            if (!contributing.contains(id)) {
                collector.warn(CompileStage.PHYSICAL_PLAN, "nodes." + id,
                        "节点不在任何通往目标节点的路径上,已从执行计划中剔除",
                        "把它连到目标节点,或删除它");
            }
        }

        List<Object> steps = new ArrayList<>();
        executionOrder.forEach(id -> steps.add(acc.steps.get(id)));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", "ETL_PIPELINE");
        putIfNotNull(plan, "jobId", jobId);
        plan.put("engine", engine);
        plan.put("irVersion", ir.schemaVersion());
        plan.put("ir", ir.toMap());
        plan.put("errorPolicy", ir.errorPolicy().toMap());
        plan.put("badRowPolicy", badRowPolicy(ir, contributing, acc, collector));
        plan.put("executionOrder", executionOrder);
        plan.put("duckdbSteps", steps);
        plan.put("lineageEdges", acc.lineage);
        plan.put("watermarkSpecs", acc.watermarks.stream()
                .filter(w -> contributing.contains(String.valueOf(w.get("nodeId")))).toList());
        plan.put("sinkSpec", acc.sinkSpec);
        plan.put("codesets", acc.codesets.stream()
                .filter(c -> contributing.contains(String.valueOf(c.get("nodeId")))).toList());
        return plan;
    }

    /**
     * 坏行处理的执行期口径。
     *
     * <p>{@code captureValues}:只有每个贡献节点的输出列都能静态推断时,才允许死信保留行内容 ——
     * 列集合未知意味着编译器无法保证已把所有 L3/L4 列识别出来,此时只记行号与原因。
     * {@code maskColumns}:行内容里必须掩码的列名(任一节点输出里出现过 L3/L4 级别的列)。
     */
    private Map<String, Object> badRowPolicy(EtlPipelineIr ir, Set<String> contributing, Acc acc,
                                             CompileResult.Collector collector) {
        boolean captureValues = true;
        Set<String> maskColumns = new java.util.TreeSet<>();
        for (Map.Entry<String, Out> e : acc.outs.entrySet()) {
            if (!contributing.contains(e.getKey()) || e.getValue() == null) {
                continue;
            }
            Out out = e.getValue();
            if (!out.known()) {
                captureValues = false;
            }
            carryOf(out).forEach((name, level) -> {
                if (isHigh(level)) {
                    maskColumns.add(name);
                }
            });
        }
        EtlErrorPolicy policy = ir.errorPolicy();
        if (policy.routesToDeadLetter() && !captureValues) {
            collector.warn(CompileStage.PLAN_OPTIMIZATION, "errorPolicy",
                    "管道里有无法静态推断列集合的节点,死信只记录行号与错误类别,不保留行内容",
                    "在自定义 SQL / 无结构快照的源之后补一个 SELECT 声明列,或先浏览源表获取结构快照");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", policy.mode());
        putIfNotNull(map, "maxBadRows", policy.maxBadRows());
        putIfNotNull(map, "maxBadRatio", policy.maxBadRatio());
        map.put("deadLetter", policy.routesToDeadLetter());
        map.put("captureValues", captureValues);
        map.put("maskColumns", new ArrayList<>(maskColumns));
        return map;
    }

    // ── 通用小工具 ──────────────────────────────────────────────────────

    /** 对象存储引用:fileId,或 dataSourceId + 相对 path。禁止绝对路径与 .. —— 管道不得读写服务器本地文件。 */
    private String requireObjectRef(Map<String, Object> cfg, String loc, String label,
                                    Map<String, Object> out, CompileResult.Collector collector) {
        String fileId = EtlConfig.str(cfg, "fileId");
        String ds = EtlConfig.str(cfg, "dataSourceId");
        String path = EtlConfig.str(cfg, "path");
        if (fileId != null) {
            out.put("fileId", fileId);
            return fileId;
        }
        if (ds == null || path == null) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc,
                    label + "必须指定 fileId,或 dataSourceId + path");
            return null;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (path.startsWith("/") || path.startsWith("\\") || path.contains("..")
                || lower.startsWith("file:") || lower.matches("^[a-z]:.*")) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, loc + ".path",
                    label + "路径不合法: " + path, "只允许对象存储数据源内的相对路径,不能含 .. 或绝对路径");
            return null;
        }
        out.put("dataSourceId", ds);
        out.put("path", path);
        return ds + ":" + path;
    }

    private boolean requireExpression(CompileResult.Collector collector, String location,
                                      String expr, String label) {
        if (expr == null) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, location, label + "未填写");
            return false;
        }
        List<String> problems = EtlSqlGuard.validateExpression(expr);
        for (String problem : problems) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, location, label + "不合法: " + problem);
        }
        return problems.isEmpty();
    }

    /** 上游列已知时校验列存在;未知时放行。 */
    private void requireColumn(CompileResult.Collector collector, Out input, String column, String location) {
        if (input != null && input.known() && !input.cols().containsKey(column)) {
            collector.error(CompileStage.SCHEMA_VALIDATION, location,
                    "上游没有字段「%s」".formatted(column),
                    "可用字段: " + String.join(", ", input.cols().keySet()));
        }
    }

    private static List<String[]> selectItems(Object raw) {
        List<String[]> items = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m && m.get("name") != null) {
                    Object as = m.get("as");
                    items.add(new String[]{m.get("name").toString(),
                            as == null || as.toString().isBlank() ? null : as.toString()});
                } else if (item != null && !(item instanceof Map<?, ?>) && !item.toString().isBlank()) {
                    items.add(new String[]{item.toString(), null});
                }
            }
        }
        return items;
    }

    private static LinkedHashMap<String, Col> colsOf(Out out) {
        return out.known() ? new LinkedHashMap<>(out.cols()) : new LinkedHashMap<>();
    }

    private static Map<String, String> carryOf(Out out) {
        if (out.known()) {
            Map<String, String> carry = new LinkedHashMap<>();
            out.cols().forEach((name, col) -> {
                if (col.level() != null) {
                    carry.put(name, col.level());
                }
            });
            return carry;
        }
        return out.carry();
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> result = new LinkedHashSet<>(a);
        result.addAll(b);
        return result;
    }

    private static String normalizeLevel(String level) {
        return level == null || level.isBlank() ? null : level.toUpperCase(Locale.ROOT);
    }

    private static boolean isHigh(String level) {
        return "L3".equals(level) || "L4".equals(level);
    }

    private static String maxLevel(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
