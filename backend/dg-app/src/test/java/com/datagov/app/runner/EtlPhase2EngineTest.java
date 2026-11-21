package com.datagov.app.runner;

import com.datagov.control.compile.CompileResult;
import com.datagov.control.compile.EtlPipelineCompiler;
import com.datagov.control.compile.JobCompiler;
import com.datagov.control.domain.JobType;
import com.datagov.control.entity.ControlEntities.JobDefinition;
import com.datagov.control.event.ControlEvents;
import com.datagov.data.spi.DataSourceType;
import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.engine.JobRunner;
import com.datagov.runtime.etl.EtlBadRowLimitExceededException;
import com.datagov.runtime.etl.EtlEngineRegistry;
import com.datagov.runtime.etl.EtlExecutionEngine;
import com.datagov.runtime.etl.SeaTunnelEngineAdapter;
import com.datagov.runtime.spi.ExecutionEngine;
import org.duckdb.DuckDBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * lightweight-etl-pipeline Phase 2 / 3 的端到端验证:
 * <b>编译器真实产出的计划</b> → 引擎(DuckDB 内存库充当源端与目标端)→ 码表映射、坏行策略、
 * 摘要落库、死信、{@code EtlExecutionCompleted} 事件,以及引擎 SPI / SeaTunnel 桩。
 *
 * <p>用编译产物而不是手写计划,是因为引擎与编译器之间的计划形状此前没有任何测试连起来过
 * (见 {@code EtlPipelineEngine#fromCompiledPlan})。
 */
@DisplayName("ETL Phase 2/3:码表映射 · 坏行策略 · 事件 · 引擎 SPI")
class EtlPhase2EngineTest {

    private static final String WS = "ws_test";

    private DuckDBConnection sourceDb;
    private DuckDBConnection sinkDb;
    private EtlPipelineEngine engine;
    private EtlPipelineRunner runner;
    private ApplicationEventPublisher publisher;

    private final List<BadRowRecord> badRows = new ArrayList<>();
    private final List<DeadLetterWrite> deadLetters = new ArrayList<>();
    private final AtomicInteger unsafeMarks = new AtomicInteger();

    private record BadRowRecord(String jobId, String executionId, String nodeId, long count,
                                String storageRef, String sampleJson) {
    }

    private record DeadLetterWrite(String nodeId, List<Map<String, Object>> rows) {
    }

    private final class DuckGateway implements EtlJdbcGateway {
        @Override
        public Connection open(String workspaceId, String dataSourceId, boolean readOnly) throws SQLException {
            return ("src".equals(dataSourceId) ? sourceDb : sinkDb).duplicate();
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
            return ("UPSERT".equals(writeMode) ? "INSERT OR REPLACE INTO " : "INSERT INTO ")
                    + table + " (" + cols + ") VALUES (" + marks + ")";
        }
    }

    /** 码表:M/male → M,F/female → F(CANONICAL_VALUE);DISPLAY_LABEL 时输出中文标签。 */
    private static final EtlCodesetResolver GENDER = (ws, id, mapTo, ignoreCase) -> {
        boolean label = "DISPLAY_LABEL".equals(mapTo);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("M", label ? "男" : "M");
        m.put("male", label ? "男" : "M");
        m.put("F", label ? "女" : "F");
        m.put("female", label ? "女" : "F");
        if (ignoreCase) {
            Map<String, String> lower = new LinkedHashMap<>();
            m.forEach((k, v) -> lower.putIfAbsent(k.toLowerCase(), v));
            return lower;
        }
        return m;
    };

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        sourceDb = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        sinkDb = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        exec(sourceDb, "CREATE TABLE patients (id INTEGER, gender VARCHAR, name VARCHAR)");
        exec(sourceDb, "INSERT INTO patients VALUES (1,'M','a'),(2,'female','b'),(3,'x','c'),(4,NULL,'d')");
        exec(sinkDb, "CREATE TABLE patients_std (id INTEGER PRIMARY KEY, gender VARCHAR, name VARCHAR)");

        ObjectProvider<EtlWatermarkService> noWatermarks = mock(ObjectProvider.class);
        ObjectProvider<EtlCodesetResolver> codesets = mock(ObjectProvider.class);
        when(codesets.getIfAvailable()).thenReturn(GENDER);
        ObjectProvider<EtlBadRowStore> store = mock(ObjectProvider.class);
        when(store.getIfAvailable()).thenReturn((ws, jobId, exec, node, count, ref, json) ->
                badRows.add(new BadRowRecord(jobId, exec, node, count, ref, json)));
        ObjectProvider<EtlDeadLetterStore> dead = mock(ObjectProvider.class);
        EtlDeadLetterStore deadStore = (ws, exec, node, rows) -> {
            deadLetters.add(new DeadLetterWrite(node, List.copyOf(rows)));
            return "mem://" + exec + "/" + node;
        };
        when(dead.getIfAvailable()).thenReturn(deadStore);

        engine = new EtlPipelineEngine(new DuckGateway(), noWatermarks, codesets);
        publisher = mock(ApplicationEventPublisher.class);
        runner = new EtlPipelineRunner(new EtlEngineRegistry(List.of(
                new DefaultDuckDbEtlEngine(engine, store, dead), new SeaTunnelEngineAdapter())), publisher);
    }

    @AfterEach
    void tearDown() throws Exception {
        sourceDb.close();
        sinkDb.close();
    }

    // ── 夹具:真实编译 ──────────────────────────────────────────────────

    private static final class StubMetadata implements JobCompiler.MetadataLookup {
        Map<String, String> sensitive = Map.of();

        @Override
        public boolean isDataSourceAvailable(String workspaceId, String dataSourceId) {
            return true;
        }

        @Override
        public String dataSourceName(String workspaceId, String dataSourceId) {
            return dataSourceId;
        }

        @Override
        public Map<String, String> tableColumns(String ws, String ds, String db, String schema, String table) {
            Map<String, String> cols = new LinkedHashMap<>();
            cols.put("id", "LONG");
            cols.put("gender", "STRING");
            cols.put("name", "STRING");
            return cols;
        }

        @Override
        public DataSourceType dataSourceType(String workspaceId, String dataSourceId) {
            return DataSourceType.POSTGRESQL;
        }

        @Override
        public Map<String, String> sensitiveColumnLevels(String ws, String ds, String db, String schema, String table) {
            return "patients".equals(table) ? sensitive : Map.of();
        }

        @Override
        public Optional<JobCompiler.CodesetInfo> codeset(String workspaceId, String ref) {
            return Optional.of(new JobCompiler.CodesetInfo("cs_gender_id", "gender", "性别", "PUBLISHED", 4));
        }
    }

    private static Map<String, Object> node(String id, String kind, Map<String, Object> config) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("kind", kind);
        n.put("config", config);
        return n;
    }

    /** 编译 src → [cs 码表映射 gender] → sink;lookup 为 null 则 src → sink 直连。 */
    private Map<String, Object> compile(Map<String, Object> lookupExtra, Map<String, Object> policy,
                                        Map<String, String> sensitive) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        nodes.add(node("src", "SOURCE_JDBC", Map.of("dataSourceId", "src", "table", "patients")));
        String last = "src";
        if (lookupExtra != null) {
            Map<String, Object> lookup = new LinkedHashMap<>();
            lookup.put("codesetRef", "gender");
            lookup.put("column", "gender");
            lookup.putAll(lookupExtra);
            nodes.add(node("cs", "TRANSFORM_CODESET_LOOKUP", lookup));
            edges.add(Map.of("from", "src", "to", "cs"));
            last = "cs";
        }
        nodes.add(node("sink", "SINK_JDBC", Map.of("dataSourceId", "dst", "table", "patients_std")));
        edges.add(Map.of("from", last, "to", "sink"));
        cfg.put("schemaVersion", "etl.ir/v1");
        cfg.put("nodes", nodes);
        cfg.put("edges", edges);
        if (policy != null) {
            cfg.put("errorPolicy", policy);
        }

        StubMetadata metadata = new StubMetadata();
        metadata.sensitive = sensitive == null ? Map.of() : sensitive;
        JobDefinition definition = new JobDefinition();
        definition.setId("job_1");
        definition.setWorkspaceId(WS);
        definition.setJobType(JobType.ETL_PIPELINE);
        CompileResult result = new EtlPipelineCompiler().compile(
                new JobCompiler.CompileContext(definition, cfg, metadata));
        assertThat(result.succeeded()).as(result.diagnostics().toString()).isTrue();
        return result.physicalPlan();
    }

    private JobRunner.RunContext context(Map<String, Object> plan) {
        return new JobRunner.RunContext("exec_1", "att_1", WS, plan, () -> false,
                (ExecutionEngine.EngineMetric m) -> { }, unsafeMarks::incrementAndGet);
    }

    private ControlEvents.EtlExecutionCompleted publishedEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        return (ControlEvents.EtlExecutionCompleted) captor.getValue();
    }

    private List<String> sink() throws SQLException {
        return rows(sinkDb, "SELECT id || ':' || coalesce(gender, 'NULL') || ':' || name FROM patients_std ORDER BY id");
    }

    // ── 编译产物能被引擎直接执行 ────────────────────────────────────────

    @Test
    @DisplayName("onMiss=KEEP_ORIGINAL:命中的标准化(同义词归一),未命中保留原值,NULL 不算未命中,并统计未命中")
    void codesetKeepOriginal() throws Exception {
        runner.run(context(compile(Map.of(), null, null)));

        assertThat(sink()).containsExactly("1:M:a", "2:F:b", "3:x:c", "4:NULL:d");
        ControlEvents.EtlExecutionCompleted event = publishedEvent();
        assertThat(event.status()).isEqualTo("SUCCEEDED");
        assertThat(event.jobId()).isEqualTo("job_1");
        assertThat(event.engine()).isEqualTo("DUCKDB");
        assertThat(event.rowsRead()).isEqualTo(4);
        assertThat(event.rowsWritten()).isEqualTo(4);
        assertThat(event.badRows()).isZero();
        assertThat(event.badRowRate()).isZero();
        assertThat(event.codesetMisses()).containsEntry("cs", 1L);
        assertThat(badRows).isEmpty();
    }

    @Test
    @DisplayName("onMiss=NULL / DEFAULT:未命中值置空 / 取默认值;mapTo=DISPLAY_LABEL 输出标准标签")
    void codesetOnMissVariantsAndLabels() throws Exception {
        runner.run(context(compile(Map.of("onMiss", "NULL"), null, null)));
        assertThat(sink()).containsExactly("1:M:a", "2:F:b", "3:NULL:c", "4:NULL:d");

        exec(sinkDb, "DELETE FROM patients_std");
        runner.run(context(compile(Map.of("onMiss", "DEFAULT", "defaultValue", "UNKNOWN"), null, null)));
        // NULL 输入不是未命中:保持 NULL,不会被默认值覆盖
        assertThat(sink()).containsExactly("1:M:a", "2:F:b", "3:UNKNOWN:c", "4:NULL:d");

        exec(sinkDb, "DELETE FROM patients_std");
        runner.run(context(compile(Map.of("mapTo", "DISPLAY_LABEL"), null, null)));
        assertThat(sink()).containsExactly("1:男:a", "2:女:b", "3:x:c", "4:NULL:d");
    }

    @Test
    @DisplayName("ignoreCase:键与查表值都按小写比较")
    void codesetIgnoreCase() throws Exception {
        exec(sourceDb, "UPDATE patients SET gender = 'MALE' WHERE id = 1");
        runner.run(context(compile(Map.of("ignoreCase", true), null, null)));
        assertThat(sink()).containsExactly("1:M:a", "2:F:b", "3:x:c", "4:NULL:d");
    }

    @Test
    @DisplayName("预览:码表映射节点的输出与正式执行同一语义")
    void previewShowsCodesetResult() throws Exception {
        EtlPipelineEngine.Plan plan = engine.parse(compile(Map.of(), null, null));
        EtlPipelineEngine.PreviewResult preview = engine.preview(WS, plan, "cs", 100,
                EtlPipelineEngine.Limits.preview(1000, "512MB", 15));
        assertThat(preview.rows()).extracting(r -> r.get("id") + ":" + r.get("gender"))
                .containsExactly("1:M", "2:F", "3:x", "4:null");
        assertThat(rows(sinkDb, "SELECT * FROM patients_std")).isEmpty();
    }

    // ── 码表 BAD_ROW × errorPolicy ──────────────────────────────────────

    @Test
    @DisplayName("BAD_ROW + SKIP_BAD_ROWS:未命中行移出主流,摘要入库(带非敏感列的未命中代码样例),事件带脏数据率")
    void badRowSkipPersistsSummary() throws Exception {
        runner.run(context(compile(Map.of("onMiss", "BAD_ROW"), Map.of("mode", "SKIP_BAD_ROWS"), null)));

        assertThat(sink()).containsExactly("1:M:a", "2:F:b", "4:NULL:d");
        assertThat(badRows).hasSize(1);
        BadRowRecord rec = badRows.get(0);
        assertThat(rec.nodeId()).isEqualTo("cs");
        assertThat(rec.count()).isEqualTo(1);
        assertThat(rec.jobId()).isEqualTo("job_1");
        assertThat(rec.storageRef()).as("SKIP 不保留行本体").isNull();
        assertThat(rec.sampleJson()).contains("CODESET_MISS").contains("\"distinctMissedCodes\":[\"x\"]");
        assertThat(deadLetters).isEmpty();

        ControlEvents.EtlExecutionCompleted event = publishedEvent();
        assertThat(event.badRows()).isEqualTo(1);
        // 分母 = 进入 Sink 的 3 行 + 码表阶段剔除的 1 行
        assertThat(event.badRowRate()).isEqualTo(0.25);
        assertThat(event.errorPolicyMode()).isEqualTo("SKIP_BAD_ROWS");
        assertThat(event.rowsWritten()).isEqualTo(3);
    }

    @Test
    @DisplayName("C5:列有 L3 敏感标签时,摘要里没有任何未命中代码值;ROUTE_TO_DEAD_LETTER 的行本体里该列被掩码")
    void sensitiveColumnNeverLeaksIntoSummary() throws Exception {
        runner.run(context(compile(Map.of("onMiss", "BAD_ROW"), Map.of("mode", "ROUTE_TO_DEAD_LETTER"),
                Map.of("gender", "L3"))));

        BadRowRecord rec = badRows.get(0);
        assertThat(rec.sampleJson()).doesNotContain("distinctMissedCodes").doesNotContain("\"x\"");
        assertThat(rec.storageRef()).isEqualTo("mem://exec_1/cs");

        assertThat(deadLetters).hasSize(1);
        Map<String, Object> deadRow = deadLetters.get(0).rows().get(0);
        assertThat(deadRow).containsEntry("category", "CODESET_MISS");
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) deadRow.get("values");
        assertThat(values).containsEntry("gender", "***").containsEntry("name", "c").containsEntry("id", 3L);
    }

    @Test
    @DisplayName("BAD_ROW + FAIL_FAST:写 Sink 之前整体失败,Sink 为空,仍留下摘要并发出 FAILED_BAD_ROW_LIMIT 事件")
    void badRowFailFast() throws Exception {
        assertThatThrownBy(() -> runner.run(context(compile(Map.of("onMiss", "BAD_ROW"), null, null))))
                .isInstanceOf(EtlBadRowLimitExceededException.class)
                .hasMessageContaining("FAIL_FAST");

        assertThat(rows(sinkDb, "SELECT * FROM patients_std")).isEmpty();
        assertThat(badRows).hasSize(1);
        assertThat(publishedEvent().status()).isEqualTo("FAILED_BAD_ROW_LIMIT");
        assertThat(runner.classify(new EtlBadRowLimitExceededException("x", null))).isEqualTo("ETL_BAD_ROW_LIMIT");
    }

    @Test
    @DisplayName("maxBadRows:超出绝对数阈值失败;maxBadRatio:处理完后按比例失败(此时已提交的行保留)")
    void thresholds() throws Exception {
        assertThatThrownBy(() -> runner.run(context(compile(Map.of("onMiss", "BAD_ROW"),
                Map.of("mode", "SKIP_BAD_ROWS", "maxBadRows", 0), null))))
                .isInstanceOf(EtlBadRowLimitExceededException.class).hasMessageContaining("maxBadRows");
        assertThat(rows(sinkDb, "SELECT * FROM patients_std")).isEmpty();

        // 1 / (3+1) = 0.25 > 0.1
        assertThatThrownBy(() -> runner.run(context(compile(Map.of("onMiss", "BAD_ROW"),
                Map.of("mode", "SKIP_BAD_ROWS", "maxBadRatio", 0.1), null))))
                .isInstanceOf(EtlBadRowLimitExceededException.class).hasMessageContaining("maxBadRatio");
        assertThat(sink()).as("比例阈值只能在处理完后判定,已提交的行不回滚").hasSize(3);
    }

    // ── Sink 坏行 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("FAIL_FAST(缺省):目标端主键冲突原样失败,不产生摘要、不发事件")
    void sinkFailFast() throws Exception {
        exec(sinkDb, "INSERT INTO patients_std VALUES (3, 'OLD', 'old')");
        assertThatThrownBy(() -> runner.run(context(compile(null, null, null)))).isInstanceOf(SQLException.class);
        assertThat(badRows).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("SKIP_BAD_ROWS:批次失败后逐行重放,只丢冲突的那一行;摘要里的错误消息不回显违规值")
    void sinkSkipBadRows() throws Exception {
        exec(sinkDb, "INSERT INTO patients_std VALUES (3, 'OLD', 'old')");

        runner.run(context(compile(null, Map.of("mode", "SKIP_BAD_ROWS"), null)));

        assertThat(sink()).containsExactly("1:M:a", "2:female:b", "3:OLD:old", "4:NULL:d");
        assertThat(badRows).hasSize(1);
        BadRowRecord rec = badRows.get(0);
        assertThat(rec.nodeId()).isEqualTo("sink");
        assertThat(rec.count()).isEqualTo(1);
        assertThat(rec.sampleJson()).contains("\"ordinal\":2").contains("\"category\"")
                .doesNotContain("id: 3").doesNotContain("\"c\"");
        // APPEND:行级提交后重投会写重,必须声明
        assertThat(unsafeMarks.get()).isPositive();
        assertThat(publishedEvent().badRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("ROUTE_TO_DEAD_LETTER:行本体进死信(摘要只存引用),无敏感列时保留全部值")
    void sinkRouteToDeadLetter() throws Exception {
        exec(sinkDb, "INSERT INTO patients_std VALUES (3, 'OLD', 'old')");

        runner.run(context(compile(null, Map.of("mode", "ROUTE_TO_DEAD_LETTER"), Map.of("name", "L4"))));

        assertThat(badRows.get(0).storageRef()).isEqualTo("mem://exec_1/sink");
        assertThat(badRows.get(0).sampleJson()).doesNotContain("\"c\"");
        assertThat(deadLetters).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) deadLetters.get(0).rows().get(0).get("values");
        assertThat(values).containsEntry("id", 3L).containsEntry("gender", "x").containsEntry("name", "***");
    }

    @Test
    @DisplayName("Sink 坏行超过 maxBadRows:失败并发出 FAILED_BAD_ROW_LIMIT")
    void sinkBadRowLimit() throws Exception {
        exec(sinkDb, "INSERT INTO patients_std VALUES (3, 'OLD', 'old')");
        assertThatThrownBy(() -> runner.run(context(compile(null,
                Map.of("mode", "SKIP_BAD_ROWS", "maxBadRows", 0), null))))
                .isInstanceOf(EtlBadRowLimitExceededException.class);
        assertThat(badRows).hasSize(1);
        assertThat(publishedEvent().status()).isEqualTo("FAILED_BAD_ROW_LIMIT");
    }

    // ── 编译产物适配 ────────────────────────────────────────────────────

    @Test
    @DisplayName("引擎不支持的源 / 目标类型在解析阶段就被明确拒绝")
    void unsupportedNodeTypesRejectedAtParse() {
        Map<String, Object> plan = new LinkedHashMap<>(compile(null, null, null));
        plan.put("duckdbSteps", List.of(Map.of("nodeId", "src", "kind", "SOURCE_KAFKA", "step", "LOAD",
                "source", Map.of("type", "KAFKA", "topic", "t"))));
        assertThatThrownBy(() -> engine.parse(plan)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KAFKA");

        plan.put("duckdbSteps", List.of(Map.of("nodeId", "sink", "kind", "SINK_KAFKA", "step", "SINK",
                "sink", Map.of("type", "KAFKA"), "input", "src")));
        assertThatThrownBy(() -> engine.parse(plan)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KAFKA");
    }

    // ── Phase 3:引擎 SPI ───────────────────────────────────────────────

    @Test
    @DisplayName("注册表:缺省 DUCKDB;未知引擎明确报错,不静默回落")
    void registry() {
        EtlEngineRegistry registry = new EtlEngineRegistry(List.of(
                new DefaultDuckDbEtlEngine(engine, mock(ObjectProvider.class), mock(ObjectProvider.class)),
                new SeaTunnelEngineAdapter()));
        assertThat(registry.resolve(null).engineId()).isEqualTo("DUCKDB");
        assertThat(registry.resolve("seatunnel").engineId()).isEqualTo("SEATUNNEL");
        assertThatThrownBy(() -> registry.resolve("SPARK")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPARK");
    }

    @Test
    @DisplayName("SEATUNNEL 引擎:Runner 选中它,执行抛 UnsupportedOperationException 且说明如何改回 DUCKDB")
    void seaTunnelDoesNotExecute() {
        Map<String, Object> plan = new LinkedHashMap<>(compile(Map.of(), null, null));
        plan.put("engine", "SEATUNNEL");
        assertThatThrownBy(() -> runner.run(context(plan)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("DUCKDB").hasMessageContaining("HOCON");
        assertThat(rows(sinkDbSafe(), "SELECT * FROM patients_std")).isEmpty();
        assertThat(JobRefType.ETL_PIPELINE).isEqualTo(runner.jobRefType());
    }

    @Test
    @DisplayName("SeaTunnel 翻译:HOCON 草稿含 Jdbc 源 / Sql 转换 / Jdbc 目标,凭据只有占位,码表节点与水位线有告警")
    void seaTunnelTranslation() {
        Map<String, Object> plan = compile(Map.of(), null, null);
        SeaTunnelEngineAdapter.Translation t = new SeaTunnelEngineAdapter().translate(plan);

        assertThat(t.hocon()).contains("source {", "transform {", "sink {")
                .contains("plugin_output = \"src\"").contains("table = \"patients_std\"")
                .contains("url = ${DG_DS_src_URL}").contains("password = ${DG_DS_dst_PASSWORD}")
                .contains("UNSUPPORTED node=cs");
        assertThat(t.hocon()).doesNotContain("secret");
        assertThat(t.warnings()).anyMatch(w -> w.contains("码表映射"));

        // 普通转换链:SQL 去掉 CREATE VIEW 前缀,DuckDB 方言被标注
        Map<String, Object> chain = compileFilter();
        SeaTunnelEngineAdapter.Translation f = new SeaTunnelEngineAdapter().translate(chain);
        assertThat(f.hocon()).contains("plugin_input = \"src\"").contains("query = \"SELECT * FROM \\\"src\\\" WHERE (id > 1)\"");
        assertThat(f.warnings()).noneMatch(w -> w.contains("DuckDB 方言"));
    }

    private Map<String, Object> compileFilter() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("schemaVersion", "etl.ir/v1");
        cfg.put("nodes", List.of(
                node("src", "SOURCE_JDBC", Map.of("dataSourceId", "src", "table", "patients")),
                node("flt", "TRANSFORM_FILTER", Map.of("condition", "id > 1")),
                node("sink", "SINK_JDBC", Map.of("dataSourceId", "dst", "table", "patients_std"))));
        cfg.put("edges", List.of(Map.of("from", "src", "to", "flt"), Map.of("from", "flt", "to", "sink")));
        JobDefinition definition = new JobDefinition();
        definition.setId("job_2");
        definition.setWorkspaceId(WS);
        definition.setJobType(JobType.ETL_PIPELINE);
        CompileResult result = new EtlPipelineCompiler().compile(
                new JobCompiler.CompileContext(definition, cfg, new StubMetadata()));
        assertThat(result.succeeded()).as(result.diagnostics().toString()).isTrue();
        return result.physicalPlan();
    }

    @Test
    @DisplayName("SeaTunnel 翻译拒绝手写形状的计划")
    void seaTunnelRejectsNonCompiledPlan() {
        assertThatThrownBy(() -> new SeaTunnelEngineAdapter().translate(Map.of("nodes", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(EtlExecutionEngine.SEATUNNEL).isEqualTo(new SeaTunnelEngineAdapter().engineId());
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private Connection sinkDbSafe() {
        return sinkDb;
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static List<String> rows(Connection conn, String sql) {
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
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    @SuppressWarnings("unused")
    private static void unusedIo() throws IOException {
        // 保留 IOException 导入给 EtlDeadLetterStore lambda 的受检异常签名
    }
}
