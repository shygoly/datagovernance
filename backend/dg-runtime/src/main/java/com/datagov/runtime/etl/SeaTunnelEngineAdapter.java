package com.datagov.runtime.etl;

import com.datagov.runtime.engine.JobRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Apache SeaTunnel 适配器桩(lightweight-etl-pipeline P3-1)。
 *
 * <p><b>非目标(Non-goal):</b>本版<b>不</b>向 SeaTunnel 集群提交作业,{@link #execute} 一律抛
 * {@link UnsupportedOperationException}。原因:提交需要集群连接、凭据下发、作业状态回收与取消语义,
 * 这些属于 Phase 3 之后的独立变更;现在能交付、也能测试的只有「IR → HOCON 文本」的翻译,
 * 它让评审者能看到同一份 {@code etl.ir/v1} 在 SeaTunnel 上会是什么样子,也让后续对接有可对照的基线。
 *
 * <p>翻译的诚实边界(以 {@link Translation#warnings()} 逐条返回,而不是静默产出一份看似可用的配置):
 * <ul>
 *   <li><b>凭据与连接串从不内联</b>:{@code url / driver / user / password} 用 HOCON 变量替换
 *       {@code ${DG_DS_<数据源id>_URL}} 等占位,由部署侧注入;</li>
 *   <li>转换步骤的 SQL 是 <b>DuckDB 方言</b>(如 {@code REPLACE / EXCLUDE / QUALIFY});
 *       命中这些关键字的节点会被标注并告警 —— SeaTunnel 的 Sql 转换基于 Calcite,不保证等价;</li>
 *   <li>码表映射节点依赖执行会话里的内存码表,无法翻译,只留 UNSUPPORTED 注释与告警;</li>
 *   <li>增量水位线、坏行路由(errorPolicy)不翻译 —— 它们由平台 Runner 承载;</li>
 *   <li>同一类型的插件出现多次(多个 Jdbc 源)时,HOCON 同名键会被合并,需要拆作业或手工改写。</li>
 * </ul>
 * 未经任何 SeaTunnel 运行时验证:输出是「结构合理的草稿」,不是「可直接提交的作业」。
 */
@Component
public class SeaTunnelEngineAdapter implements EtlExecutionEngine {

    static final String NOT_SUPPORTED_MESSAGE =
            "SeaTunnel 引擎暂不支持执行:当前版本只提供 etl.ir/v1 → HOCON 的翻译"
                    + "(SeaTunnelEngineAdapter#translate),不会向 SeaTunnel 集群提交作业。"
                    + "请把管道的 engine 改回 DUCKDB;SeaTunnel 的提交与状态回收属于 Phase 3 之后的独立变更。";

    private static final Pattern VIEW_PREFIX = Pattern.compile(
            "^\\s*CREATE\\s+OR\\s+REPLACE\\s+TEMP\\s+VIEW\\s+\"(?:[^\"]|\"\")+\"\\s+AS\\s+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DUCKDB_DIALECT = Pattern.compile(
            "\\b(REPLACE|EXCLUDE|RENAME)\\s*\\(|\\bQUALIFY\\b", Pattern.CASE_INSENSITIVE);

    /** 翻译结果:HOCON 文本 + 逐条告警。 */
    public record Translation(String hocon, List<String> warnings) {
    }

    @Override
    public String engineId() {
        return SEATUNNEL;
    }

    @Override
    public EtlRunSummary execute(JobRunner.RunContext context) {
        throw new UnsupportedOperationException(NOT_SUPPORTED_MESSAGE);
    }

    @Override
    public String classify(Exception e) {
        return "SYS_INTERNAL_ERROR";
    }

    /**
     * 把编译后的物理计划(见 {@code EtlPipelineCompiler})翻译为 SeaTunnel HOCON。
     *
     * @throws IllegalArgumentException 计划不是编译产物,或含无法翻译的源 / 目标类型
     *                                  (文件 / 接口源、Parquet 目标)
     */
    public Translation translate(Map<String, Object> plan) {
        if (plan == null || !(plan.get("duckdbSteps") instanceof List<?> steps) || steps.isEmpty()) {
            throw new IllegalArgumentException("不是编译后的 ETL 物理计划:缺少 duckdbSteps");
        }
        Map<String, List<String>> upstream = upstreamOf(plan);
        List<String> warnings = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        List<String> transforms = new ArrayList<>();
        List<String> sinks = new ArrayList<>();
        Map<String, Integer> pluginCounts = new LinkedHashMap<>();

        for (Object raw : steps) {
            if (!(raw instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> step = castMap(m);
            String nodeId = String.valueOf(step.get("nodeId"));
            switch (String.valueOf(step.get("step"))) {
                case "LOAD" -> {
                    pluginCounts.merge("source.Jdbc", 1, Integer::sum);
                    sources.add(jdbcSource(nodeId, castMap(step.get("source")), warnings));
                }
                case "SQL" -> {
                    if (step.get("codeset") != null) {
                        warnings.add("节点 " + nodeId + "(码表映射)无法翻译:码表是执行会话内的内存表,"
                                + "SeaTunnel 侧需先把码表物化为维表再改写为 JOIN");
                        transforms.add("    # UNSUPPORTED node=" + nodeId
                                + " kind=TRANSFORM_CODESET_LOOKUP — 需手工改写\n");
                        continue;
                    }
                    pluginCounts.merge("transform.Sql", 1, Integer::sum);
                    transforms.add(sqlTransform(nodeId, String.valueOf(step.get("sql")),
                            upstream.getOrDefault(nodeId, List.of()), warnings));
                }
                case "SINK" -> {
                    pluginCounts.merge("sink.Jdbc", 1, Integer::sum);
                    sinks.add(jdbcSink(nodeId, String.valueOf(step.get("input")),
                            castMap(step.get("sink")), warnings));
                }
                default -> warnings.add("节点 " + nodeId + " 的步骤类型未知,已跳过: " + step.get("step"));
            }
        }
        pluginCounts.forEach((plugin, count) -> {
            if (count > 1) {
                warnings.add("插件 " + plugin + " 出现 " + count + " 次:HOCON 同名键会合并,"
                        + "SeaTunnel 提交前需要拆成多个作业或手工改写");
            }
        });
        if (plan.get("watermarkSpecs") instanceof List<?> marks && !marks.isEmpty()) {
            warnings.add("增量水位线不翻译:SeaTunnel 作业按全量读取;水位线仍由平台 Runner 维护");
        }

        StringBuilder out = new StringBuilder();
        out.append("# 由 etl.ir/v1 翻译生成(SeaTunnelEngineAdapter)。草稿:未经 SeaTunnel 运行时验证,\n");
        out.append("# 连接信息以 ${DG_DS_<数据源id>_*} 占位,凭据不在此文件内。\n");
        out.append("env {\n  job.mode = \"BATCH\"\n  parallelism = 1\n}\n\n");
        out.append("source {\n").append(String.join("", sources)).append("}\n\n");
        out.append("transform {\n").append(String.join("", transforms)).append("}\n\n");
        out.append("sink {\n").append(String.join("", sinks)).append("}\n");
        return new Translation(out.toString(), List.copyOf(warnings));
    }

    // ── 各段 ────────────────────────────────────────────────────────────

    private String jdbcSource(String nodeId, Map<String, Object> source, List<String> warnings) {
        if (!"JDBC".equals(String.valueOf(source.get("type")))) {
            throw new IllegalArgumentException("节点 " + nodeId + " 的源类型 " + source.get("type")
                    + " 暂不支持翻译为 SeaTunnel(仅 JDBC)");
        }
        String ds = String.valueOf(source.get("dataSourceId"));
        StringBuilder query = new StringBuilder("SELECT ");
        if (source.get("columns") instanceof List<?> cols && !cols.isEmpty()) {
            query.append(String.join(", ", cols.stream().map(String::valueOf).toList()));
        } else {
            query.append("*");
        }
        query.append(" FROM ").append(qualified(source));
        if (source.get("whereClause") != null) {
            query.append(" WHERE (").append(source.get("whereClause")).append(")");
        }
        StringBuilder sb = new StringBuilder("  Jdbc {\n");
        sb.append("    plugin_output = ").append(quote(nodeId)).append("\n");
        connection(sb, ds);
        sb.append("    query = ").append(quote(query.toString())).append("\n  }\n");
        return sb.toString();
    }

    private String sqlTransform(String nodeId, String viewSql, List<String> inputs, List<String> warnings) {
        String query = VIEW_PREFIX.matcher(viewSql).replaceFirst("");
        StringBuilder sb = new StringBuilder();
        if (DUCKDB_DIALECT.matcher(query).find()) {
            warnings.add("节点 " + nodeId + " 的 SQL 含 DuckDB 方言(REPLACE / EXCLUDE / RENAME / QUALIFY),"
                    + "SeaTunnel Sql 转换不保证等价,需人工核对");
            sb.append("    # DIALECT-WARNING node=").append(nodeId).append(" — DuckDB 方言,需人工核对\n");
        }
        if (inputs.size() > 1) {
            warnings.add("节点 " + nodeId + " 有 " + inputs.size() + " 路输入(JOIN / 多表自定义 SQL):"
                    + "SeaTunnel 的 Sql 转换只接受单个 plugin_input,需改写");
        }
        sb.append("  Sql {\n");
        sb.append("    plugin_input = ").append(quote(inputs.isEmpty() ? "" : inputs.get(0))).append("\n");
        sb.append("    plugin_output = ").append(quote(nodeId)).append("\n");
        sb.append("    query = ").append(quote(query)).append("\n  }\n");
        return sb.toString();
    }

    private String jdbcSink(String nodeId, String input, Map<String, Object> sink, List<String> warnings) {
        if (!"JDBC".equals(String.valueOf(sink.get("type")))) {
            throw new IllegalArgumentException("节点 " + nodeId + " 的目标类型 " + sink.get("type")
                    + " 暂不支持翻译为 SeaTunnel(仅 JDBC)");
        }
        String ds = String.valueOf(sink.get("dataSourceId"));
        String mode = String.valueOf(sink.get("writeMode")).toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder("  Jdbc {\n");
        sb.append("    plugin_input = ").append(quote(input)).append("\n");
        connection(sb, ds);
        if (sink.get("database") != null) {
            sb.append("    database = ").append(quote(String.valueOf(sink.get("database")))).append("\n");
        }
        sb.append("    table = ").append(quote(qualified(sink))).append("\n");
        sb.append("    generate_sink_sql = true\n");
        sb.append("    batch_size = ").append(sink.getOrDefault("batchSize", 1000)).append("\n");
        switch (mode) {
            case "OVERWRITE" -> sb.append("    data_save_mode = \"DROP_DATA\"\n");
            case "UPSERT" -> {
                sb.append("    data_save_mode = \"APPEND_DATA\"\n    enable_upsert = true\n");
                if (sink.get("primaryKeys") instanceof List<?> keys && !keys.isEmpty()) {
                    sb.append("    primary_keys = [")
                            .append(String.join(", ", keys.stream().map(k -> quote(String.valueOf(k))).toList()))
                            .append("]\n");
                }
            }
            default -> sb.append("    data_save_mode = \"APPEND_DATA\"\n");
        }
        if (sink.get("columnMapping") instanceof Map<?, ?> mapping
                && mapping.entrySet().stream().anyMatch(e -> !String.valueOf(e.getKey())
                .equals(String.valueOf(e.getValue())))) {
            warnings.add("目标节点 " + nodeId + " 的列映射含改名:SeaTunnel Jdbc Sink 按列名同名写入,"
                    + "需在 Sql 转换里 AS 改名");
        }
        if (sink.get("errorPolicy") instanceof Map<?, ?> policy
                && !"FAIL_FAST".equals(String.valueOf(policy.get("mode")))) {
            warnings.add("errorPolicy=" + policy.get("mode") + " 不翻译:坏行跳过 / 死信由平台 Runner 承载,"
                    + "SeaTunnel 作业遇坏行会按其自身策略失败");
        }
        return sb.append("  }\n").toString();
    }

    private static void connection(StringBuilder sb, String dataSourceId) {
        String key = dataSourceId.replaceAll("[^A-Za-z0-9_]", "_");
        sb.append("    url = ${DG_DS_").append(key).append("_URL}\n");
        sb.append("    driver = ${DG_DS_").append(key).append("_DRIVER}\n");
        sb.append("    user = ${DG_DS_").append(key).append("_USER}\n");
        sb.append("    password = ${DG_DS_").append(key).append("_PASSWORD}\n");
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    private static String qualified(Map<String, Object> spec) {
        List<String> parts = new ArrayList<>();
        if (spec.get("schema") != null) {
            parts.add(String.valueOf(spec.get("schema")));
        }
        parts.add(String.valueOf(spec.get("table")));
        return String.join(".", parts);
    }

    private static Map<String, List<String>> upstreamOf(Map<String, Object> plan) {
        Map<String, List<String>> upstream = new LinkedHashMap<>();
        if (plan.get("ir") instanceof Map<?, ?> ir && ir.get("edges") instanceof List<?> edges) {
            for (Object e : edges) {
                if (e instanceof Map<?, ?> edge && edge.get("from") != null && edge.get("to") != null) {
                    upstream.computeIfAbsent(String.valueOf(edge.get("to")), k -> new ArrayList<>())
                            .add(String.valueOf(edge.get("from")));
                }
            }
        }
        return upstream;
    }

    /** HOCON 带引号字符串:转义反斜杠、引号与换行。 */
    static String quote(String value) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
        return "\"" + escaped + "\"";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object raw) {
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
