package com.datagov.control.compile;

import com.datagov.control.domain.JobType;
import com.datagov.control.entity.ControlEntities.JobDefinition;
import com.datagov.data.spi.DataSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EtlPipelineCompilerTest {

    private final EtlPipelineCompiler compiler = new EtlPipelineCompiler();

    // ── 测试夹具 ────────────────────────────────────────────────────────

    /** 内存版 Metadata:ds_src.orders 与 ds_dst.orders_out 两张表。 */
    private static class StubMetadata implements JobCompiler.MetadataLookup {
        Map<String, String> sensitive = Map.of();
        boolean available = true;
        /** codeset ref(id 或 code)→ 信息;默认空 = 码表不存在。 */
        final Map<String, JobCompiler.CodesetInfo> codesets = new LinkedHashMap<>();

        @Override
        public boolean isDataSourceAvailable(String workspaceId, String dataSourceId) {
            return available;
        }

        @Override
        public String dataSourceName(String workspaceId, String dataSourceId) {
            return dataSourceId;
        }

        @Override
        public Map<String, String> tableColumns(String ws, String ds, String db, String schema, String table) {
            Map<String, String> cols = new LinkedHashMap<>();
            if ("orders".equals(table)) {
                cols.put("id", "LONG");
                cols.put("phone", "STRING");
                cols.put("amount", "DECIMAL");
                cols.put("updated_at", "TIMESTAMP");
            } else if ("orders_out".equals(table)) {
                cols.put("id", "LONG");
                cols.put("phone", "STRING");
                cols.put("amount", "DECIMAL");
                cols.put("updated_at", "TIMESTAMP");
            }
            return cols;
        }

        @Override
        public DataSourceType dataSourceType(String workspaceId, String dataSourceId) {
            return DataSourceType.POSTGRESQL;
        }

        @Override
        public Map<String, String> sensitiveColumnLevels(String ws, String ds, String db, String schema, String table) {
            return "orders".equals(table) ? sensitive : Map.of();
        }

        @Override
        public java.util.Optional<JobCompiler.CodesetInfo> codeset(String workspaceId, String ref) {
            return java.util.Optional.ofNullable(codesets.get(ref));
        }
    }

    private CompileResult compile(Map<String, Object> config, StubMetadata metadata) {
        JobDefinition definition = new JobDefinition();
        definition.setId("job_1");
        definition.setWorkspaceId("ws_1");
        definition.setJobType(JobType.ETL_PIPELINE);
        return compiler.compile(new JobCompiler.CompileContext(definition, config, metadata));
    }

    private CompileResult compile(Map<String, Object> config) {
        return compile(config, new StubMetadata());
    }

    private static Map<String, Object> node(String id, String kind, Map<String, Object> config) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("kind", kind);
        node.put("config", config);
        return node;
    }

    private static Map<String, Object> edge(String from, String to) {
        return Map.of("from", from, "to", to);
    }

    private static Map<String, Object> ir(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, Object> ir = new LinkedHashMap<>();
        ir.put("schemaVersion", "etl.ir/v1");
        ir.put("nodes", nodes);
        ir.put("edges", edges);
        return ir;
    }

    private static Map<String, Object> srcJdbc() {
        return node("src", "SOURCE_JDBC", Map.of("dataSourceId", "ds_src", "table", "orders"));
    }

    private static Map<String, Object> sinkJdbc() {
        return node("sink", "SINK_JDBC", Map.of("dataSourceId", "ds_dst", "table", "orders_out"));
    }

    private static String messages(CompileResult result) {
        return result.diagnostics().stream()
                .map(d -> d.severity() + "@" + d.location() + ": " + d.message())
                .reduce("", (a, b) -> a + "\n" + b);
    }

    // ── 快乐路径 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SOURCE_JDBC → FILTER → SINK_JDBC")
    class HappyPath {

        private CompileResult result() {
            return compile(ir(
                    List.of(srcJdbc(),
                            node("flt", "TRANSFORM_FILTER", Map.of("condition", "amount > 100")),
                            sinkJdbc()),
                    List.of(edge("src", "flt"), edge("flt", "sink"))));
        }

        @Test
        @DisplayName("编译通过,产出 LOAD / SQL / SINK 三步,按拓扑序")
        @SuppressWarnings("unchecked")
        void compilesToThreeSteps() {
            CompileResult result = result();
            assertThat(result.succeeded()).as(messages(result)).isTrue();
            assertThat(result.errors()).isEmpty();

            Map<String, Object> plan = result.physicalPlan();
            assertThat(plan.get("kind")).isEqualTo("ETL_PIPELINE");
            assertThat((List<String>) plan.get("executionOrder")).containsExactly("src", "flt", "sink");

            List<Map<String, Object>> steps = (List<Map<String, Object>>) plan.get("duckdbSteps");
            assertThat(steps).extracting(s -> s.get("step")).containsExactly("LOAD", "SQL", "SINK");
            assertThat((String) steps.get(1).get("sql"))
                    .isEqualTo("CREATE OR REPLACE TEMP VIEW \"flt\" AS SELECT * FROM \"src\" WHERE (amount > 100)");
            assertThat(((Map<String, Object>) steps.get(0).get("source")).get("table")).isEqualTo("orders");
        }

        @Test
        @DisplayName("sinkSpec 带数据源 ID 与写入模式,计划里没有任何凭据")
        @SuppressWarnings("unchecked")
        void sinkSpec() {
            Map<String, Object> sink = (Map<String, Object>) result().physicalPlan().get("sinkSpec");
            assertThat(sink).containsEntry("type", "JDBC")
                    .containsEntry("dataSourceId", "ds_dst")
                    .containsEntry("table", "orders_out")
                    .containsEntry("writeMode", "APPEND")
                    .containsEntry("batchSize", 1000);
            assertThat(result().physicalPlan().toString().toLowerCase()).doesNotContain("password");
        }

        @Test
        @DisplayName("上游列已知 → 逐列血缘边,来源是源表同名列")
        @SuppressWarnings("unchecked")
        void columnLineage() {
            List<Map<String, Object>> edges =
                    (List<Map<String, Object>>) result().physicalPlan().get("lineageEdges");
            assertThat(edges).hasSize(4);
            Map<String, Object> first = edges.get(0);
            assertThat(((Map<String, Object>) first.get("from"))).containsEntry("table", "orders")
                    .containsEntry("column", "id");
            assertThat(((Map<String, Object>) first.get("to"))).containsEntry("table", "orders_out")
                    .containsEntry("column", "id");
            assertThat(first).containsEntry("confidence", 1.0).containsEntry("maskingApplied", false);
        }

        @Test
        @DisplayName("增量配置 → watermarkSpecs,类型从列类型推断")
        @SuppressWarnings("unchecked")
        void watermarkSpec() {
            Map<String, Object> src = node("src", "SOURCE_JDBC", Map.of("dataSourceId", "ds_src",
                    "table", "orders", "incremental", Map.of("column", "updated_at")));
            CompileResult result = compile(ir(List.of(src, sinkJdbc()), List.of(edge("src", "sink"))));
            assertThat(result.succeeded()).as(messages(result)).isTrue();
            List<Map<String, Object>> specs =
                    (List<Map<String, Object>>) result.physicalPlan().get("watermarkSpecs");
            assertThat(specs).hasSize(1);
            assertThat(specs.get(0)).containsEntry("nodeId", "src")
                    .containsEntry("column", "updated_at").containsEntry("type", "TIMESTAMP");
        }

        @Test
        @DisplayName("顶层未知键给 WARNING 而不是失败")
        void unknownTopLevelKeyWarns() {
            Map<String, Object> config = ir(List.of(srcJdbc(), sinkJdbc()), List.of(edge("src", "sink")));
            config.put("schemaVerison", "typo");
            CompileResult result = compile(config);
            assertThat(result.succeeded()).isTrue();
            assertThat(result.warnings()).anyMatch(d -> d.message().contains("schemaVerison"));
        }
    }

    // ── 图校验 ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DAG 校验")
    class Dag {

        @Test
        @DisplayName("环被检出并定位到涉及的节点")
        void cycleDetected() {
            CompileResult result = compile(ir(
                    List.of(srcJdbc(),
                            node("a", "TRANSFORM_FILTER", Map.of("condition", "id > 0")),
                            node("b", "TRANSFORM_FILTER", Map.of("condition", "id > 1")),
                            sinkJdbc()),
                    List.of(edge("src", "a"), edge("a", "b"), edge("b", "a"), edge("b", "sink"))));

            assertThat(result.succeeded()).isFalse();
            assertThat(result.errors()).anyMatch(d -> d.stage() == CompileStage.DAG_VALIDATION
                    && d.message().contains("环") && d.message().contains("a") && d.message().contains("b"));
        }

        @Test
        @DisplayName("纯环(无多余入度)同样检出")
        void pureCycle() {
            CompileResult result = compile(ir(
                    List.of(srcJdbc(),
                            node("a", "TRANSFORM_DEDUP", Map.of("keys", List.of("id"))),
                            node("b", "TRANSFORM_DEDUP", Map.of("keys", List.of("id"))),
                            sinkJdbc()),
                    List.of(edge("a", "b"), edge("b", "a"), edge("src", "sink"))));
            assertThat(result.errors()).anyMatch(d -> d.message().contains("环"));
        }

        @Test
        @DisplayName("边引用不存在的节点")
        void danglingEdge() {
            CompileResult result = compile(ir(List.of(srcJdbc(), sinkJdbc()),
                    List.of(edge("src", "sink"), edge("src", "ghost"))));
            assertThat(result.succeeded()).isFalse();
            assertThat(result.errors()).anyMatch(d -> d.message().contains("ghost"));
        }

        @Test
        @DisplayName("未知节点种类被拒绝")
        void unknownKind() {
            CompileResult result = compile(ir(
                    List.of(srcJdbc(), node("x", "TRANSFORM_MAGIC", Map.of()), sinkJdbc()),
                    List.of(edge("src", "x"), edge("x", "sink"))));
            assertThat(result.errors()).anyMatch(d -> d.message().contains("TRANSFORM_MAGIC"));
        }

        @Test
        @DisplayName("没有 Sink / 多个 Sink 都不行")
        void sinkCount() {
            CompileResult none = compile(ir(List.of(srcJdbc()), List.of()));
            assertThat(none.errors()).anyMatch(d -> d.message().contains("没有目标节点"));

            Map<String, Object> sink2 = node("sink2", "SINK_JDBC",
                    Map.of("dataSourceId", "ds_dst", "table", "orders_out"));
            CompileResult two = compile(ir(List.of(srcJdbc(), sinkJdbc(), sink2),
                    List.of(edge("src", "sink"), edge("src", "sink2"))));
            assertThat(two.errors()).anyMatch(d -> d.message().contains("只支持一个目标节点"));
        }

        @Test
        @DisplayName("非法节点 id 被拒绝")
        void badNodeId() {
            CompileResult result = compile(ir(
                    List.of(node("bad id\"; DROP", "SOURCE_JDBC", Map.of()), sinkJdbc()), List.of()));
            assertThat(result.succeeded()).isFalse();
        }
    }

    // ── 自定义 SQL ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("TRANSFORM_CUSTOM_SQL 护栏")
    class CustomSql {

        private CompileResult withSql(String sql) {
            return compile(ir(
                    List.of(srcJdbc(), node("q", "TRANSFORM_CUSTOM_SQL", Map.of("sql", sql)), sinkJdbc()),
                    List.of(edge("src", "q"), edge("q", "sink"))));
        }

        @Test
        @DisplayName("合法 SELECT 用占位符引用上游,被改写成带引号的视图名")
        @SuppressWarnings("unchecked")
        void accepts() {
            CompileResult result = withSql("SELECT id, amount * 2 AS doubled FROM {{src}} WHERE amount >= 10");
            assertThat(result.succeeded()).as(messages(result)).isTrue();
            List<Map<String, Object>> steps =
                    (List<Map<String, Object>>) result.physicalPlan().get("duckdbSteps");
            assertThat((String) steps.get(1).get("sql")).isEqualTo(
                    "CREATE OR REPLACE TEMP VIEW \"q\" AS SELECT id, amount * 2 AS doubled FROM \"src\" WHERE amount >= 10");
        }

        @Test
        @DisplayName("WITH + CTE + 末尾分号可以通过")
        void acceptsCte() {
            CompileResult result = withSql(
                    "WITH big AS (SELECT * FROM {{src}} WHERE amount > 1) SELECT id FROM big;");
            assertThat(result.succeeded()).as(messages(result)).isTrue();
        }

        @Test
        @DisplayName("COPY / ATTACH / INSTALL / LOAD / PRAGMA 被拒")
        void rejectsDangerousStatements() {
            for (String sql : List.of(
                    "COPY (SELECT * FROM {{src}}) TO '/tmp/x.csv'",
                    "ATTACH '/tmp/other.db' AS o",
                    "INSTALL httpfs",
                    "LOAD httpfs",
                    "PRAGMA database_list",
                    "SELECT * FROM {{src}}; LOAD httpfs",
                    "WITH x AS (SELECT * FROM {{src}}) INSERT INTO t SELECT * FROM x")) {
                CompileResult result = withSql(sql);
                assertThat(result.succeeded()).as(sql).isFalse();
                assertThat(result.errors()).as(sql).allMatch(d -> d.location().endsWith(".sql"));
            }
        }

        @Test
        @DisplayName("DML / DDL 被拒")
        void rejectsDmlDdl() {
            for (String sql : List.of(
                    "DELETE FROM {{src}}",
                    "UPDATE {{src}} SET amount = 0",
                    "DROP TABLE orders",
                    "CREATE TABLE t AS SELECT * FROM {{src}}",
                    "ALTER TABLE orders ADD COLUMN x INT",
                    "TRUNCATE orders")) {
                assertThat(withSql(sql).succeeded()).as(sql).isFalse();
            }
        }

        @Test
        @DisplayName("读本地文件的各种写法都被拒(FROM '路径'、read_csv、FROM \"路径\"、glob)")
        void rejectsFileAccess() {
            for (String sql : List.of(
                    "SELECT * FROM '/etc/passwd'",
                    "SELECT * FROM {{src}} a, '/etc/passwd' b",
                    "SELECT * FROM read_csv('/etc/passwd')",
                    "SELECT * FROM {{src}} JOIN read_parquet('x.parquet') USING (id)",
                    "SELECT * FROM \"/etc/passwd\"",
                    "SELECT * FROM parquet_scan('x')",
                    "SELECT * FROM glob('/*')",
                    "SELECT * FROM duckdb_settings()",
                    "SELECT (SELECT count(*) FROM '/etc/passwd') FROM {{src}}")) {
                assertThat(withSql(sql).succeeded()).as(sql).isFalse();
            }
        }

        @Test
        @DisplayName("没有 {{nodeId}} 占位符 / 引用非上游节点 → 拒绝")
        void requiresPlaceholders() {
            assertThat(withSql("SELECT 1").errors())
                    .anyMatch(d -> d.message().contains("没有通过 {{节点id}}"));
            assertThat(withSql("SELECT * FROM orders").succeeded()).isFalse();
            assertThat(withSql("SELECT * FROM {{sink}}").errors())
                    .anyMatch(d -> d.message().contains("不是本节点的上游"));
        }

        @Test
        @DisplayName("注释与多语句被拒;字符串里的关键字不误杀")
        void commentsAndStrings() {
            assertThat(withSql("SELECT * FROM {{src}} -- hi").succeeded()).isFalse();
            assertThat(withSql("SELECT * FROM {{src}} /* x */").succeeded()).isFalse();
            assertThat(withSql("SELECT * FROM {{src}}; SELECT 1").succeeded()).isFalse();
            assertThat(withSql("SELECT * FROM {{src}} WHERE phone = 'DROP TABLE; -- x'").succeeded())
                    .isTrue();
            assertThat(withSql("SELECT \"load\" FROM {{src}}").succeeded()).isTrue();
        }
    }

    // ── 敏感字段 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("敏感字段")
    class Sensitivity {

        @Test
        @DisplayName("L3 字段未脱敏流入 Sink → WARNING(不阻断)")
        void warnsWhenUnmasked() {
            StubMetadata metadata = new StubMetadata();
            metadata.sensitive = Map.of("phone", "L3");
            CompileResult result = compile(ir(List.of(srcJdbc(), sinkJdbc()),
                    List.of(edge("src", "sink"))), metadata);
            assertThat(result.succeeded()).isTrue();
            assertThat(result.warnings()).anyMatch(d -> d.location().equals("nodes.sink.phone")
                    && d.message().contains("L3"));
        }

        @Test
        @DisplayName("经 CLEANSE_MASK 脱敏后不再提醒,且血缘边标记 maskingApplied")
        @SuppressWarnings("unchecked")
        void maskedNoWarning() {
            StubMetadata metadata = new StubMetadata();
            metadata.sensitive = Map.of("phone", "L4");
            Map<String, Object> mask = node("mask", "TRANSFORM_CLEANSE_MASK", Map.of("rules",
                    List.of(Map.of("column", "phone", "action", "MASK_PARTIAL"))));
            CompileResult result = compile(ir(List.of(srcJdbc(), mask, sinkJdbc()),
                    List.of(edge("src", "mask"), edge("mask", "sink"))), metadata);
            assertThat(result.succeeded()).as(messages(result)).isTrue();
            assertThat(result.warnings()).noneMatch(d -> d.message().contains("敏感"));

            List<Map<String, Object>> edges = (List<Map<String, Object>>) result.physicalPlan().get("lineageEdges");
            Map<String, Object> phoneEdge = edges.stream()
                    .filter(e -> "phone".equals(((Map<String, Object>) e.get("to")).get("column")))
                    .findFirst().orElseThrow();
            assertThat(phoneEdge).containsEntry("maskingApplied", true);
        }

        @Test
        @DisplayName("自定义 SQL 之后敏感级别按列名粗粒度携带")
        void customSqlCarriesByName() {
            StubMetadata metadata = new StubMetadata();
            metadata.sensitive = Map.of("phone", "L3");
            Map<String, Object> sql = node("q", "TRANSFORM_CUSTOM_SQL",
                    Map.of("sql", "SELECT id, phone FROM {{src}}"));
            CompileResult result = compile(ir(List.of(srcJdbc(), sql, sinkJdbc()),
                    List.of(edge("src", "q"), edge("q", "sink"))), metadata);
            assertThat(result.succeeded()).as(messages(result)).isTrue();
            assertThat(result.warnings()).anyMatch(d -> d.message().contains("phone"));
        }
    }

    // ── 其它节点 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("其它转换节点")
    class OtherTransforms {

        @Test
        @DisplayName("SELECT / DERIVE / DEDUP / AGGREGATE 链路可编译")
        void chain() {
            CompileResult result = compile(ir(
                    List.of(srcJdbc(),
                            node("sel", "TRANSFORM_SELECT", Map.of("columns", List.of("id", "amount",
                                    Map.of("name", "phone", "as", "mobile")))),
                            node("drv", "TRANSFORM_DERIVE", Map.of("derivations",
                                    List.of(Map.of("name", "amount2", "expression", "amount * 2")))),
                            node("dd", "TRANSFORM_DEDUP", Map.of("keys", List.of("id"), "orderBy", "amount")),
                            node("agg", "TRANSFORM_AGGREGATE", Map.of("groupBy", List.of("id"),
                                    "aggregations", List.of(Map.of("func", "SUM", "column", "amount2", "as", "total")))),
                            node("out", "SINK_PARQUET", Map.of("fileId", "file_1"))),
                    List.of(edge("src", "sel"), edge("sel", "drv"), edge("drv", "dd"),
                            edge("dd", "agg"), edge("agg", "out"))));
            assertThat(result.succeeded()).as(messages(result)).isTrue();
        }

        @Test
        @DisplayName("引用上游不存在的列 → 带定位的错误")
        void unknownColumn() {
            CompileResult result = compile(ir(
                    List.of(srcJdbc(), node("sel", "TRANSFORM_SELECT", Map.of("columns", List.of("nope"))),
                            sinkJdbc()),
                    List.of(edge("src", "sel"), edge("sel", "sink"))));
            assertThat(result.succeeded()).isFalse();
            assertThat(result.errors()).anyMatch(d -> d.location().equals("nodes.sel.columns.nope"));
        }

        @Test
        @DisplayName("过滤条件里夹带子查询 / 分号被拒")
        void filterInjection() {
            for (String cond : List.of("id IN (SELECT id FROM '/etc/passwd')", "1=1; DROP TABLE x",
                    "id > 0 -- x")) {
                CompileResult result = compile(ir(
                        List.of(srcJdbc(), node("f", "TRANSFORM_FILTER", Map.of("condition", cond)), sinkJdbc()),
                        List.of(edge("src", "f"), edge("f", "sink"))));
                assertThat(result.succeeded()).as(cond).isFalse();
            }
        }

        @Test
        @DisplayName("JOIN 两路输入 + 异名连接键(ON 模式),右侧先裁剪避免重名")
        @SuppressWarnings("unchecked")
        void join() {
            Map<String, Object> src2 = node("src2", "SOURCE_JDBC",
                    Map.of("dataSourceId", "ds_src", "table", "orders"));
            Map<String, Object> pick = node("pick", "TRANSFORM_SELECT",
                    Map.of("columns", List.of(Map.of("name", "id", "as", "ref_id"))));
            Map<String, Object> join = node("j", "TRANSFORM_JOIN", Map.of("joinType", "LEFT",
                    "on", List.of(Map.of("left", "id", "right", "ref_id"))));
            Map<String, Object> trim = node("trim", "TRANSFORM_SELECT",
                    Map.of("columns", List.of("id", "phone", "amount", "updated_at")));
            CompileResult result = compile(ir(List.of(srcJdbc(), src2, pick, join, trim, sinkJdbc()),
                    List.of(edge("src", "j"), edge("src2", "pick"), edge("pick", "j"),
                            edge("j", "trim"), edge("trim", "sink"))));
            assertThat(result.succeeded()).as(messages(result)).isTrue();
            List<Map<String, Object>> steps =
                    (List<Map<String, Object>>) result.physicalPlan().get("duckdbSteps");
            assertThat(steps).anySatisfy(s -> assertThat((String) s.get("sql"))
                    .contains("LEFT JOIN \"pick\" AS r ON l.\"id\" = r.\"ref_id\""));
        }

        @Test
        @DisplayName("JOIN 两侧重名列 → 右侧自动改名并给 WARNING")
        void joinDuplicateColumnsWarn() {
            Map<String, Object> src2 = node("src2", "SOURCE_JDBC",
                    Map.of("dataSourceId", "ds_src", "table", "orders"));
            Map<String, Object> join = node("j", "TRANSFORM_JOIN", Map.of(
                    "on", List.of(Map.of("left", "id", "right", "id"))));
            Map<String, Object> trim = node("trim", "TRANSFORM_SELECT",
                    Map.of("columns", List.of("id", "phone", "amount", "updated_at")));
            CompileResult result = compile(ir(List.of(srcJdbc(), src2, join, trim, sinkJdbc()),
                    List.of(edge("src", "j"), edge("src2", "j"), edge("j", "trim"), edge("trim", "sink"))));
            assertThat(result.succeeded()).as(messages(result)).isTrue();
            assertThat(result.warnings()).anyMatch(d -> d.message().contains("phone_r"));
        }

        @Test
        @DisplayName("CODESET_LOOKUP 引用不存在的码表:依赖校验失败(C2),不再是「尚未实现」")
        void codesetMustExist() {
            CompileResult result = compile(ir(
                    List.of(srcJdbc(), node("cs", "TRANSFORM_CODESET_LOOKUP",
                            Map.of("codesetRef", "gender", "column", "phone")), sinkJdbc()),
                    List.of(edge("src", "cs"), edge("cs", "sink"))));
            assertThat(result.succeeded()).isFalse();
            assertThat(result.errors()).anyMatch(d -> d.stage() == CompileStage.DEPENDENCY_VALIDATION
                    && d.message().contains("不存在"));
        }

        @Test
        @DisplayName("数据源不可用 → 依赖校验失败")
        void dataSourceUnavailable() {
            StubMetadata metadata = new StubMetadata();
            metadata.available = false;
            CompileResult result = compile(ir(List.of(srcJdbc(), sinkJdbc()),
                    List.of(edge("src", "sink"))), metadata);
            assertThat(result.succeeded()).isFalse();
            assertThat(result.errors()).anyMatch(d -> d.stage() == CompileStage.DEPENDENCY_VALIDATION);
        }

        @Test
        @DisplayName("SINK_PARQUET 路径不得是绝对路径或含 ..")
        void parquetPath() {
            Map<String, Object> out = node("out", "SINK_PARQUET",
                    Map.of("dataSourceId", "ds_obj", "path", "../../etc/x.parquet"));
            CompileResult result = compile(ir(List.of(srcJdbc(), out), List.of(edge("src", "out"))));
            assertThat(result.succeeded()).isFalse();
        }
    }

    // ── Phase 2:码表映射 / 坏行策略 / 引擎 ───────────────────────────────

    @Nested
    @DisplayName("TRANSFORM_CODESET_LOOKUP(P2-2)")
    class CodesetLookup {

        private StubMetadata metadataWith(String status, long entries) {
            StubMetadata metadata = new StubMetadata();
            JobCompiler.CodesetInfo info = new JobCompiler.CodesetInfo("cs_gender_id", "gender", "性别", status, entries);
            metadata.codesets.put("gender", info);
            metadata.codesets.put("cs_gender_id", info);
            return metadata;
        }

        private Map<String, Object> lookup(Map<String, Object> extra) {
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("codesetRef", "gender");
            cfg.put("column", "phone");
            cfg.putAll(extra);
            return node("cs", "TRANSFORM_CODESET_LOOKUP", cfg);
        }

        private CompileResult compileLookup(Map<String, Object> extra, StubMetadata metadata) {
            return compile(ir(List.of(srcJdbc(), lookup(extra), sinkJdbc()),
                    List.of(edge("src", "cs"), edge("cs", "sink"))), metadata);
        }

        @Test
        @DisplayName("编译通过:绑定码表 id,左连接码表临时表原地替换,条目不进计划")
        @SuppressWarnings("unchecked")
        void happyPath() {
            CompileResult result = compileLookup(Map.of(), metadataWith("PUBLISHED", 5));
            assertThat(result.succeeded()).as(messages(result)).isTrue();

            Map<String, Object> plan = result.physicalPlan();
            List<Map<String, Object>> steps = (List<Map<String, Object>>) plan.get("duckdbSteps");
            Map<String, Object> step = steps.get(1);
            assertThat((String) step.get("sql")).isEqualTo(
                    "CREATE OR REPLACE TEMP VIEW \"cs\" AS SELECT t.* REPLACE "
                            + "(coalesce(cs.\"std\", CAST(t.\"phone\" AS VARCHAR)) AS \"phone\") "
                            + "FROM \"src\" AS t LEFT JOIN \"__dg_cs_cs\" AS cs "
                            + "ON CAST(t.\"phone\" AS VARCHAR) = cs.\"code\"");

            List<Map<String, Object>> codesets = (List<Map<String, Object>>) plan.get("codesets");
            assertThat(codesets).hasSize(1);
            assertThat(codesets.get(0)).containsEntry("codesetId", "cs_gender_id")
                    .containsEntry("codesetCode", "gender").containsEntry("table", "__dg_cs_cs")
                    .containsEntry("mapTo", "CANONICAL_VALUE").containsEntry("onMiss", "KEEP_ORIGINAL")
                    .containsEntry("inputNodeId", "src").containsEntry("column", "phone");
            assertThat(step.get("codeset")).isEqualTo(codesets.get(0));
            assertThat(plan).containsEntry("jobId", "job_1").containsEntry("engine", "DUCKDB");
        }

        @Test
        @DisplayName("血缘:码表映射列带 CODESET 表达式,来源仍追溯到源列")
        @SuppressWarnings("unchecked")
        void lineageCarriesCodesetExpression() {
            CompileResult result = compileLookup(Map.of(), metadataWith("PUBLISHED", 5));
            List<Map<String, Object>> edges =
                    (List<Map<String, Object>>) result.physicalPlan().get("lineageEdges");
            Map<String, Object> phone = edges.stream()
                    .filter(e -> "phone".equals(((Map<String, Object>) e.get("to")).get("column")))
                    .findFirst().orElseThrow();
            assertThat(phone.get("transformExpr")).isEqualTo("CODESET(gender, CANONICAL_VALUE)(phone)");
            assertThat(((Map<String, Object>) phone.get("from"))).containsEntry("column", "phone");
        }

        @Test
        @DisplayName("码表不存在 / 已废弃:编译失败;未发布:告警;空字典:告警")
        void existenceAndStatus() {
            CompileResult missing = compileLookup(Map.of("codesetRef", "nope"), metadataWith("PUBLISHED", 5));
            assertThat(missing.succeeded()).isFalse();
            assertThat(missing.errors()).anyMatch(d -> d.location().endsWith(".codesetRef"));

            CompileResult retired = compileLookup(Map.of(), metadataWith("DEPRECATED", 5));
            assertThat(retired.succeeded()).isFalse();
            assertThat(retired.errors()).anyMatch(d -> d.message().contains("DEPRECATED"));

            CompileResult draft = compileLookup(Map.of(), metadataWith("DRAFT", 5));
            assertThat(draft.succeeded()).as(messages(draft)).isTrue();
            assertThat(draft.warnings()).anyMatch(d -> d.message().contains("尚未发布"));

            CompileResult empty = compileLookup(Map.of(), metadataWith("PUBLISHED", 0));
            assertThat(empty.succeeded()).isTrue();
            assertThat(empty.warnings()).anyMatch(d -> d.message().contains("值字典为空"));

            // -1 = 条目数未知(其它空间的 GLOBAL 码表),不能误报为空
            CompileResult unknownCount = compileLookup(Map.of(), metadataWith("PUBLISHED", -1));
            assertThat(unknownCount.warnings()).noneMatch(d -> d.message().contains("值字典为空"));
        }

        @Test
        @DisplayName("没有 Lookup 实现的 Metadata(默认 empty):一律判码表不存在")
        void defaultLookupHasNoCodesets() {
            CompileResult result = compileLookup(Map.of(), new StubMetadata());
            assertThat(result.succeeded()).isFalse();
        }

        @Test
        @DisplayName("配置校验:必填项、枚举、DEFAULT 需 defaultValue、列必须存在、输出列不得冲突")
        void configValidation() {
            StubMetadata m = metadataWith("PUBLISHED", 5);
            assertThat(compile(ir(List.of(srcJdbc(), node("cs", "TRANSFORM_CODESET_LOOKUP", Map.of()), sinkJdbc()),
                    List.of(edge("src", "cs"), edge("cs", "sink"))), m).errors())
                    .anyMatch(d -> d.location().endsWith(".codesetRef"))
                    .anyMatch(d -> d.location().endsWith(".column"));

            assertThat(compileLookup(Map.of("mapTo", "WHATEVER"), m).errors())
                    .anyMatch(d -> d.location().endsWith(".mapTo"));
            assertThat(compileLookup(Map.of("onMiss", "EXPLODE"), m).errors())
                    .anyMatch(d -> d.location().endsWith(".onMiss"));
            assertThat(compileLookup(Map.of("onMiss", "DEFAULT"), m).errors())
                    .anyMatch(d -> d.location().endsWith(".defaultValue"));
            assertThat(compileLookup(Map.of("column", "no_such_col"), m).errors())
                    .anyMatch(d -> d.location().endsWith(".column") && d.message().contains("no_such_col"));
            assertThat(compileLookup(Map.of("outputColumn", "amount"), m).errors())
                    .anyMatch(d -> d.location().endsWith(".outputColumn"));
        }

        @Test
        @DisplayName("outputColumn 不同名:新增列并保留原列;onMiss 决定 SQL 形状")
        @SuppressWarnings("unchecked")
        void outputColumnAndOnMissShapes() {
            StubMetadata m = metadataWith("PUBLISHED", 5);

            // 新增的 phone_std 不在目标表里:Sink 必须显式列映射,否则恒等映射会被编译器指出「目标表没有该字段」
            Map<String, Object> mappedSink = node("sink", "SINK_JDBC", Map.of("dataSourceId", "ds_dst",
                    "table", "orders_out", "columnMapping",
                    Map.of("id", "id", "phone_std", "phone", "amount", "amount", "updated_at", "updated_at")));
            CompileResult added = compile(ir(
                    List.of(srcJdbc(), lookup(Map.of("outputColumn", "phone_std", "ignoreCase", true)), mappedSink),
                    List.of(edge("src", "cs"), edge("cs", "sink"))), m);
            assertThat(added.succeeded()).as(messages(added)).isTrue();
            String addedSql = (String) ((Map<String, Object>) ((List<?>) added.physicalPlan().get("duckdbSteps")).get(1)).get("sql");
            assertThat(addedSql).contains("SELECT t.*, coalesce(cs.\"std\", CAST(t.\"phone\" AS VARCHAR)) AS \"phone_std\"")
                    .contains("lower(CAST(t.\"phone\" AS VARCHAR)) = cs.\"code\"");

            String nullSql = sqlOf(compileLookup(Map.of("onMiss", "NULL"), m));
            assertThat(nullSql).contains("REPLACE (cs.\"std\" AS \"phone\")").doesNotContain("WHERE");

            String defSql = sqlOf(compileLookup(Map.of("onMiss", "DEFAULT", "defaultValue", "UNK'N"), m));
            assertThat(defSql).contains("CASE WHEN t.\"phone\" IS NULL THEN NULL ELSE coalesce(cs.\"std\", 'UNK''N') END");

            String badSql = sqlOf(compileLookup(Map.of("onMiss", "BAD_ROW"), m));
            assertThat(badSql).contains("WHERE (t.\"phone\" IS NULL OR cs.\"code\" IS NOT NULL)");
        }

        @SuppressWarnings("unchecked")
        private String sqlOf(CompileResult result) {
            assertThat(result.succeeded()).as(messages(result)).isTrue();
            return (String) ((Map<String, Object>) ((List<?>) result.physicalPlan().get("duckdbSteps")).get(1)).get("sql");
        }

        @Test
        @DisplayName("onMiss=BAD_ROW + FAIL_FAST:警告「任一未命中即整次失败」")
        void badRowWithFailFastWarns() {
            CompileResult result = compileLookup(Map.of("onMiss", "BAD_ROW"), metadataWith("PUBLISHED", 5));
            assertThat(result.warnings()).anyMatch(d -> d.message().contains("FAIL_FAST"));
        }

        @Test
        @DisplayName("未命中样例开关:列无敏感标签才允许 sampleValues(C5)")
        @SuppressWarnings("unchecked")
        void sampleValuesOnlyForNonSensitiveColumns() {
            StubMetadata plain = metadataWith("PUBLISHED", 5);
            Map<String, Object> plainSpec = ((List<Map<String, Object>>)
                    compileLookup(Map.of(), plain).physicalPlan().get("codesets")).get(0);
            assertThat(plainSpec).containsEntry("sampleValues", true);

            StubMetadata sensitive = metadataWith("PUBLISHED", 5);
            sensitive.sensitive = Map.of("phone", "L3");
            CompileResult result = compileLookup(Map.of(), sensitive);
            assertThat(result.succeeded()).as(messages(result)).isTrue();
            assertThat(((List<Map<String, Object>>) result.physicalPlan().get("codesets")).get(0))
                    .containsEntry("sampleValues", false);
        }
    }

    @Nested
    @DisplayName("errorPolicy / badRowPolicy / engine(P2-1、P3-1)")
    class PolicyAndEngine {

        private Map<String, Object> withPolicy(Map<String, Object> policy) {
            Map<String, Object> config = ir(List.of(srcJdbc(), sinkJdbc()), List.of(edge("src", "sink")));
            config.put("errorPolicy", policy);
            return config;
        }

        @Test
        @DisplayName("三种模式都被接受;SKIP 是 SKIP_BAD_ROWS 的别名;未知模式报错")
        @SuppressWarnings("unchecked")
        void modes() {
            for (String mode : List.of("FAIL_FAST", "SKIP_BAD_ROWS", "ROUTE_TO_DEAD_LETTER", "skip")) {
                CompileResult result = compile(withPolicy(Map.of("mode", mode)));
                assertThat(result.succeeded()).as(mode + messages(result)).isTrue();
            }
            Map<String, Object> plan = compile(withPolicy(Map.of("mode", "SKIP"))).physicalPlan();
            assertThat(((Map<String, Object>) plan.get("errorPolicy"))).containsEntry("mode", "SKIP_BAD_ROWS");

            CompileResult bad = compile(withPolicy(Map.of("mode", "IGNORE_EVERYTHING")));
            assertThat(bad.succeeded()).isFalse();
            assertThat(bad.errors()).anyMatch(d -> d.location().equals("errorPolicy.mode")
                    && d.hint().contains("ROUTE_TO_DEAD_LETTER"));
        }

        @Test
        @DisplayName("阈值:负数 / 越界被拒绝;合法值进入 badRowPolicy")
        @SuppressWarnings("unchecked")
        void thresholds() {
            assertThat(compile(withPolicy(Map.of("mode", "SKIP_BAD_ROWS", "maxBadRows", -1))).succeeded()).isFalse();
            assertThat(compile(withPolicy(Map.of("mode", "SKIP_BAD_ROWS", "maxBadRatio", 1.5))).succeeded()).isFalse();

            Map<String, Object> plan = compile(withPolicy(
                    Map.of("mode", "ROUTE_TO_DEAD_LETTER", "maxBadRows", 100, "maxBadRatio", 0.01))).physicalPlan();
            Map<String, Object> policy = (Map<String, Object>) plan.get("badRowPolicy");
            assertThat(policy).containsEntry("mode", "ROUTE_TO_DEAD_LETTER").containsEntry("maxBadRows", 100L)
                    .containsEntry("maxBadRatio", 0.01).containsEntry("deadLetter", true);
        }

        @Test
        @DisplayName("列集合全部可推断 → captureValues=true;maskColumns 含 L3/L4 列")
        @SuppressWarnings("unchecked")
        void captureValuesAndMaskColumns() {
            StubMetadata metadata = new StubMetadata();
            metadata.sensitive = Map.of("phone", "L3", "amount", "L1");
            Map<String, Object> plan = compile(withPolicy(Map.of("mode", "ROUTE_TO_DEAD_LETTER")), metadata)
                    .physicalPlan();
            Map<String, Object> policy = (Map<String, Object>) plan.get("badRowPolicy");
            assertThat(policy).containsEntry("captureValues", true);
            assertThat((List<String>) policy.get("maskColumns")).containsExactly("phone");
        }

        @Test
        @DisplayName("上游列无法推断 + ROUTE_TO_DEAD_LETTER:captureValues=false 并警告")
        @SuppressWarnings("unchecked")
        void unknownColumnsDisableValueCapture() {
            Map<String, Object> custom = node("c", "TRANSFORM_CUSTOM_SQL", Map.of("sql", "SELECT * FROM {{src}}"));
            Map<String, Object> config = ir(List.of(srcJdbc(), custom, sinkJdbc()),
                    List.of(edge("src", "c"), edge("c", "sink")));
            config.put("errorPolicy", Map.of("mode", "ROUTE_TO_DEAD_LETTER"));
            CompileResult result = compile(config);
            assertThat(result.succeeded()).as(messages(result)).isTrue();
            assertThat(((Map<String, Object>) result.physicalPlan().get("badRowPolicy")))
                    .containsEntry("captureValues", false);
            assertThat(result.warnings()).anyMatch(d -> d.message().contains("不保留行内容"));
        }

        @Test
        @DisplayName("engine:缺省 DUCKDB;SEATUNNEL 通过但警告「只翻译不执行」;未知引擎报错")
        void engine() {
            Map<String, Object> config = ir(List.of(srcJdbc(), sinkJdbc()), List.of(edge("src", "sink")));
            assertThat(compile(config).physicalPlan()).containsEntry("engine", "DUCKDB");

            config.put("engine", "seatunnel");
            CompileResult st = compile(config);
            assertThat(st.succeeded()).isTrue();
            assertThat(st.physicalPlan()).containsEntry("engine", "SEATUNNEL");
            assertThat(st.warnings()).anyMatch(d -> d.message().contains("不会真正执行"));
            // 已知键:不应触发「未知键」告警
            assertThat(st.warnings()).noneMatch(d -> d.message().contains("不认识"));

            config.put("engine", "SPARK");
            assertThat(compile(config).succeeded()).isFalse();
        }
    }
}
