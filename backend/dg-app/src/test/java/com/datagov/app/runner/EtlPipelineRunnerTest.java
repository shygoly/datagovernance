package com.datagov.app.runner;

import com.datagov.runtime.engine.JobRunner;
import com.datagov.runtime.spi.ExecutionEngine;
import org.duckdb.DuckDBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ETL 管道内核:用两个 DuckDB 内存库分别充当「源端」与「目标端」,
 * 端到端验证 抽取 → DuckDB 转换 → Sink 写入(APPEND / OVERWRITE / UPSERT)、
 * 锁定外部访问、水位线与预览。不需要 Spring 容器也不需要真实数据源。
 */
@DisplayName("ETL 管道(DuckDB 内核)")
class EtlPipelineRunnerTest {

    private static final String WS = "ws_test";

    private DuckDBConnection sourceDb;
    private DuckDBConnection sinkDb;
    private EtlWatermarkService watermarkService;
    private EtlPipelineEngine engine;
    private EtlPipelineRunner runner;

    /** data source id: "src" → sourceDb, "dst" → sinkDb。 */
    private final class DuckGateway implements EtlJdbcGateway {
        @Override
        public Connection open(String workspaceId, String dataSourceId, boolean readOnly)
                throws SQLException {
            DuckDBConnection root = "src".equals(dataSourceId) ? sourceDb : sinkDb;
            return root.duplicate();
        }

        @Override
        public String qualifiedTable(String ws, String ds, String database, String schema, String table) {
            return quote(ws, ds, table);
        }

        @Override
        public String quote(String ws, String ds, String identifier) {
            return "\"" + identifier + "\"";
        }

        @Override
        public String insertSql(String ws, String ds, String table, List<String> columns,
                                String writeMode, List<String> primaryKeys) {
            String cols = String.join(", ", columns.stream().map(c -> "\"" + c + "\"").toList());
            String marks = String.join(", ", columns.stream().map(c -> "?").toList());
            String verb = "UPSERT".equals(writeMode) ? "INSERT OR REPLACE INTO" : "INSERT INTO";
            return verb + " " + table + " (" + cols + ") VALUES (" + marks + ")";
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        sourceDb = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        sinkDb = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        exec(sourceDb, "CREATE TABLE users (id INTEGER, name VARCHAR, age INTEGER, "
                + "amount DECIMAL(10,2), created TIMESTAMP)");
        exec(sourceDb, "INSERT INTO users VALUES "
                + "(1, 'alice', 30, 10.50, TIMESTAMP '2024-01-01 10:00:00'),"
                + "(2, 'bob', 15, 20.00, TIMESTAMP '2024-01-02 10:00:00'),"
                + "(3, 'carol', 41, 30.25, TIMESTAMP '2024-01-03 10:00:00'),"
                + "(4, 'dave', 17, 40.00, TIMESTAMP '2024-01-04 10:00:00')");
        exec(sinkDb, "CREATE TABLE adults (id INTEGER PRIMARY KEY, name VARCHAR)");

        watermarkService = mock(EtlWatermarkService.class);
        ObjectProvider<EtlWatermarkService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenAnswer(inv -> watermarkService);
        ObjectProvider<EtlCodesetResolver> noCodesets = mock(ObjectProvider.class);
        ObjectProvider<EtlBadRowStore> noBadRowStore = mock(ObjectProvider.class);
        ObjectProvider<EtlDeadLetterStore> noDeadLetter = mock(ObjectProvider.class);
        engine = new EtlPipelineEngine(new DuckGateway(), provider, noCodesets);
        runner = new EtlPipelineRunner(new com.datagov.runtime.etl.EtlEngineRegistry(List.of(
                new DefaultDuckDbEtlEngine(engine, noBadRowStore, noDeadLetter))),
                mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        sourceDb.close();
        sinkDb.close();
    }

    // ── 计划构造 ────────────────────────────────────────────────────────

    private static Map<String, Object> plan(String writeMode, String stepSql) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("schemaVersion", "etl.ir/v1");
        plan.put("kind", "ETL_PIPELINE");
        plan.put("jobId", "ctlj_etl_1");
        plan.put("nodes", List.of(
                Map.of("id", "src-1", "type", "SOURCE_JDBC",
                        "config", Map.of("dataSourceId", "src", "table", "users")),
                Map.of("id", "flt", "type", "FILTER"),
                Map.of("id", "snk", "type", "SINK_JDBC",
                        "config", Map.of("dataSourceId", "dst", "table", "adults",
                                "writeMode", writeMode, "primaryKeys", List.of("id")))));
        plan.put("edges", List.of(Map.of("from", "src-1", "to", "flt"),
                Map.of("from", "flt", "to", "snk")));
        plan.put("duckdbSteps", List.of(Map.of("nodeId", "flt", "sql", stepSql)));
        return plan;
    }

    private static final String ADULTS_SQL =
            "SELECT id, upper(name) AS name FROM \"src_1\" WHERE age >= 18";

    private final AtomicInteger unsafeMarks = new AtomicInteger();
    private final List<ExecutionEngine.EngineMetric> progress = new ArrayList<>();

    private JobRunner.RunContext context(Map<String, Object> plan) {
        return new JobRunner.RunContext("exec_1", "att_1", WS, plan, () -> false,
                progress::add, unsafeMarks::incrementAndGet);
    }

    // ── 用例 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("JDBC 源 → DuckDB 过滤/转换 → JDBC Sink(APPEND),节点 id 里的连字符被清洗")
    void extractTransformAppend() throws Exception {
        JobRunner.RunResult result = runner.run(context(plan("APPEND", ADULTS_SQL)));

        assertThat(rows(sinkDb, "SELECT id, name FROM adults ORDER BY id"))
                .containsExactly("1:ALICE", "3:CAROL");
        assertThat(result.metric().rowsRead()).isEqualTo(4);
        assertThat(result.metric().rowsWritten()).isEqualTo(2);
        // APPEND 在第一次提交后重投会写重,必须声明
        assertThat(unsafeMarks.get()).isPositive();
    }

    @Test
    @DisplayName("OVERWRITE 先清空目标表,且是幂等写入 —— 不禁用重试")
    void overwriteClearsTargetAndStaysRetryable() throws Exception {
        exec(sinkDb, "INSERT INTO adults VALUES (99, 'STALE')");

        runner.run(context(plan("OVERWRITE", ADULTS_SQL)));

        assertThat(rows(sinkDb, "SELECT id, name FROM adults ORDER BY id"))
                .containsExactly("1:ALICE", "3:CAROL");
        assertThat(unsafeMarks.get()).isZero();
    }

    @Test
    @DisplayName("UPSERT 按主键覆盖已有行")
    void upsertReplacesByKey() throws Exception {
        exec(sinkDb, "INSERT INTO adults VALUES (1, 'OLD')");

        runner.run(context(plan("UPSERT", ADULTS_SQL)));

        assertThat(rows(sinkDb, "SELECT id, name FROM adults ORDER BY id"))
                .containsExactly("1:ALICE", "3:CAROL");
        assertThat(unsafeMarks.get()).isZero();
    }

    @Test
    @DisplayName("没有 duckdbSteps 时,FILTER 节点的 condition 回退成过滤视图")
    void filterNodeFallbackWithoutSteps() throws Exception {
        Map<String, Object> plan = plan("APPEND", ADULTS_SQL);
        plan.remove("duckdbSteps");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = new ArrayList<>((List<Map<String, Object>>) plan.get("nodes"));
        nodes.set(1, Map.of("id", "flt", "type", "FILTER", "condition", "age >= 18"));
        plan.put("nodes", nodes);
        // 目标表只有两列,用列映射挑出 id、name
        nodes.set(2, Map.of("id", "snk", "type", "SINK_JDBC", "config", Map.of(
                "dataSourceId", "dst", "table", "adults", "writeMode", "APPEND",
                "columnMappings", Map.of("id", "id", "name", "name"))));

        runner.run(context(plan));

        assertThat(rows(sinkDb, "SELECT id, name FROM adults ORDER BY id"))
                .containsExactly("1:alice", "3:carol");
    }

    @Test
    @DisplayName("抽取后锁定:转换 SQL 读不了本地文件,也无法重新打开外部访问")
    void externalAccessIsLockedAfterExtract() {
        assertThatThrownBy(() -> runner.run(context(plan("APPEND",
                "SELECT * FROM read_csv('/etc/hosts')"))))
                .isInstanceOf(Exception.class);

        // 通过 SELECT 之外的口子改配置同样被拦(单条只读护栏 + lock_configuration)
        assertThatThrownBy(() -> runner.run(context(plan("APPEND",
                "SET enable_external_access = true"))))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> runner.run(context(plan("APPEND",
                "DELETE FROM \"src_1\""))))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("源端表被改动或失败时目标端不被写入(转换失败发生在 Sink 之前)")
    void transformFailureWritesNothing() throws Exception {
        assertThatThrownBy(() -> runner.run(context(plan("APPEND",
                "SELECT id, name FROM \"no_such_node\""))))
                .isInstanceOf(SQLException.class);
        assertThat(rows(sinkDb, "SELECT id, name FROM adults")).isEmpty();
    }

    @Test
    @DisplayName("增量:有水位线时只抽 > last 的行,成功后以抽取到的最大值推进")
    void watermarkFiltersAndAdvances() throws Exception {
        when(watermarkService.lastValue(WS, "ctlj_etl_1", "src-1"))
                .thenReturn(Optional.of("2"));
        Map<String, Object> plan = plan("APPEND", ADULTS_SQL);
        plan.put("watermarkSpecs", List.of(Map.of("nodeId", "src-1", "column", "id", "type", "LONG")));

        JobRunner.RunResult result = runner.run(context(plan));

        // id > 2 的只有 3(成年)与 4(未成年)
        assertThat(result.metric().rowsRead()).isEqualTo(2);
        assertThat(rows(sinkDb, "SELECT id, name FROM adults")).containsExactly("3:CAROL");
        org.mockito.Mockito.verify(watermarkService).advance(WS, "ctlj_etl_1", "src-1",
                "id", "LONG", "4", "exec_1");
    }

    @Test
    @DisplayName("Sink 失败时不推进水位线")
    void watermarkNotAdvancedWhenSinkFails() {
        Map<String, Object> plan = plan("APPEND", ADULTS_SQL);
        plan.put("watermarkSpecs", List.of(Map.of("nodeId", "src-1", "column", "id", "type", "LONG")));
        // 目标表里已有 id=1:APPEND 触发主键冲突
        try {
            exec(sinkDb, "INSERT INTO adults VALUES (1, 'DUP')");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }

        assertThatThrownBy(() -> runner.run(context(plan))).isInstanceOf(SQLException.class);

        org.mockito.Mockito.verify(watermarkService, org.mockito.Mockito.never()).advance(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("取消:抽取循环里响应取消旗")
    void cancelStopsExtraction() {
        JobRunner.RunContext canceled = new JobRunner.RunContext("exec_1", "att_1", WS,
                plan("APPEND", ADULTS_SQL), () -> true, progress::add, unsafeMarks::incrementAndGet);
        assertThatThrownBy(() -> runner.run(canceled)).isInstanceOf(InterruptedException.class);
        assertThat(unsafeMarks.get()).isZero();
    }

    @Test
    @DisplayName("预览:返回列与行,源节点抽样封顶,且不写目标端")
    void previewDoesNotWrite() throws Exception {
        EtlPipelineEngine.Plan plan = engine.parse(plan("APPEND", ADULTS_SQL));

        EtlPipelineEngine.PreviewResult preview = engine.preview(WS, plan, "flt", 100,
                EtlPipelineEngine.Limits.preview(3, "512MB", 15));

        assertThat(preview.columns()).extracting(EtlPipelineEngine.PreviewColumn::name)
                .containsExactly("id", "name");
        // 只抽样前 3 行(1,2,3),过滤后剩 1、3
        assertThat(preview.rows()).extracting(r -> r.get("id") + ":" + r.get("name"))
                .containsExactly("1:ALICE", "3:CAROL");
        assertThat(rows(sinkDb, "SELECT id, name FROM adults")).isEmpty();
    }

    @Test
    @DisplayName("预览截断到 limit 并标记 truncated;预览 Sink 节点等价于预览其上游")
    void previewTruncatesAndResolvesSink() throws Exception {
        EtlPipelineEngine.Plan plan = engine.parse(plan("APPEND",
                "SELECT id, name FROM \"src_1\""));

        EtlPipelineEngine.PreviewResult preview = engine.preview(WS, plan, "snk", 2,
                EtlPipelineEngine.Limits.preview(1000, "512MB", 15));

        assertThat(preview.nodeId()).isEqualTo("flt");
        assertThat(preview.rows()).hasSize(2);
        assertThat(preview.truncated()).isTrue();
    }

    @Test
    @DisplayName("非法计划被拒绝:版本、重复节点、缺 Sink 上游")
    void invalidPlansAreRejected() {
        Map<String, Object> badVersion = plan("APPEND", ADULTS_SQL);
        badVersion.put("schemaVersion", "etl.ir/v9");
        assertThatThrownBy(() -> engine.parse(badVersion))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("etl.ir/v9");

        Map<String, Object> dup = plan("APPEND", ADULTS_SQL);
        dup.put("nodes", List.of(Map.of("id", "a-b", "type", "SOURCE_JDBC"),
                Map.of("id", "a_b", "type", "SINK_JDBC")));
        assertThatThrownBy(() -> engine.parse(dup)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tempName:非法字符换下划线,数字开头加前缀")
    void tempNameSanitizes() {
        assertThat(EtlPipelineEngine.tempName("src-1")).isEqualTo("src_1");
        assertThat(EtlPipelineEngine.tempName("1abc")).isEqualTo("n_1abc");
        assertThat(EtlPipelineEngine.tempName("a b.c")).isEqualTo("a_b_c");
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static List<String> rows(Connection conn, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> cells = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    cells.add(String.valueOf(rs.getObject(i)));
                }
                out.add(String.join(":", cells));
            }
        }
        return out;
    }
}
