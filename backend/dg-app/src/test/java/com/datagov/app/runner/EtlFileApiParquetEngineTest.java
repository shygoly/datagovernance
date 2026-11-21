package com.datagov.app.runner;

import com.datagov.control.compile.CompileResult;
import com.datagov.control.compile.EtlPipelineCompiler;
import com.datagov.control.compile.JobCompiler;
import com.datagov.control.domain.JobType;
import com.datagov.control.entity.ControlEntities.JobDefinition;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceType;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.mapper.DataSourceMapper;
import com.datagov.metadata.service.ConnectionConfigAssembler;
import com.datagov.runtime.engine.JobRunner;
import com.datagov.runtime.etl.EtlRunSummary;
import com.datagov.runtime.spi.ExecutionEngine;
import org.duckdb.DuckDBConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SOURCE_FILE / SOURCE_API / SINK_PARQUET 的端到端验证:<b>编译器真实产出的计划</b> →
 * {@link DefaultDuckDbEtlEngine}。文件源 / 目标走真实的 {@link DefaultEtlObjectStore}(LOCAL 数据源指向临时目录),
 * 接口源用桩 {@link EtlApiFetcher}(网络层另有 SSRF 校验测试)。
 */
@DisplayName("ETL 文件源 · 接口源 · Parquet 目标")
class EtlFileApiParquetEngineTest {

    private static final String WS = "ws_test";

    @TempDir
    Path root;

    private DuckDBConnection sinkDb;
    private EtlPipelineEngine engine;
    private DefaultDuckDbEtlEngine duck;
    private List<Map<String, Object>> apiRows = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        sinkDb = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        exec(sinkDb, "CREATE TABLE people (id BIGINT, name VARCHAR, score DOUBLE)");

        DataSourceMapper mapper = mock(DataSourceMapper.class);
        DataSourceEntity files = new DataSourceEntity();
        files.setId("files");
        files.setWorkspaceId(WS);
        files.setType(DataSourceType.LOCAL);
        when(mapper.selectById("files")).thenReturn(files);
        DataSourceEntity foreign = new DataSourceEntity();
        foreign.setId("foreign");
        foreign.setWorkspaceId("ws_other");
        foreign.setType(DataSourceType.LOCAL);
        when(mapper.selectById("foreign")).thenReturn(foreign);
        ConnectionConfigAssembler assembler = mock(ConnectionConfigAssembler.class);
        when(assembler.assemble(any(DataSourceEntity.class), any())).thenAnswer(inv ->
                ConnectionConfig.builder().database(root.toString()).build());
        DefaultEtlObjectStore store = new DefaultEtlObjectStore(mapper, assembler, List.of());

        EtlApiFetcher fetcher = (ws, url, apiConfigId, recordsPath, maxRows) -> apiRows;

        EtlJdbcGateway gateway = new EtlJdbcGateway() {
            @Override
            public Connection open(String ws, String ds, boolean readOnly) throws SQLException {
                return sinkDb.duplicate();
            }

            @Override
            public String qualifiedTable(String ws, String ds, String db, String schema, String table) {
                return quote(ws, ds, table);
            }

            @Override
            public String quote(String ws, String ds, String identifier) {
                return "\"" + identifier + "\"";
            }

            @Override
            public String insertSql(String ws, String ds, String table, List<String> columns,
                                    String writeMode, List<String> primaryKeys) {
                return "INSERT INTO " + table + " (" + String.join(", ",
                        columns.stream().map(c -> "\"" + c + "\"").toList()) + ") VALUES ("
                        + String.join(", ", columns.stream().map(c -> "?").toList()) + ")";
            }
        };

        ObjectProvider<EtlWatermarkService> noWatermarks = mock(ObjectProvider.class);
        ObjectProvider<EtlCodesetResolver> noCodesets = mock(ObjectProvider.class);
        ObjectProvider<EtlObjectStore> stores = mock(ObjectProvider.class);
        when(stores.getIfAvailable()).thenReturn(store);
        ObjectProvider<EtlApiFetcher> fetchers = mock(ObjectProvider.class);
        when(fetchers.getIfAvailable()).thenReturn(fetcher);
        engine = new EtlPipelineEngine(gateway, noWatermarks, noCodesets, stores, fetchers);
        duck = new DefaultDuckDbEtlEngine(engine, mock(ObjectProvider.class), mock(ObjectProvider.class));
        storeUnderTest = store;
    }

    private DefaultEtlObjectStore storeUnderTest;

    @AfterEach
    void tearDown() throws Exception {
        sinkDb.close();
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static Map<String, Object> node(String id, String kind, Map<String, Object> config) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("kind", kind);
        n.put("config", config);
        return n;
    }

    private static final class StubMetadata implements JobCompiler.MetadataLookup {
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
            cols.put("name", "STRING");
            cols.put("score", "DOUBLE");
            return cols;
        }

        @Override
        public DataSourceType dataSourceType(String workspaceId, String dataSourceId) {
            return "files".equals(dataSourceId) ? DataSourceType.LOCAL : DataSourceType.POSTGRESQL;
        }
    }

    private Map<String, Object> compile(Map<String, Object> source, String sourceKind,
                                        Map<String, Object> sink, String sinkKind) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("schemaVersion", "etl.ir/v1");
        cfg.put("nodes", List.of(node("src", sourceKind, source), node("sink", sinkKind, sink)));
        cfg.put("edges", List.of(Map.of("from", "src", "to", "sink")));
        JobDefinition definition = new JobDefinition();
        definition.setId("job_f");
        definition.setWorkspaceId(WS);
        definition.setJobType(JobType.ETL_PIPELINE);
        CompileResult result = new EtlPipelineCompiler().compile(
                new JobCompiler.CompileContext(definition, cfg, new StubMetadata()));
        assertThat(result.succeeded()).as(result.diagnostics().toString()).isTrue();
        return result.physicalPlan();
    }

    private JobRunner.RunContext context(Map<String, Object> plan) {
        return new JobRunner.RunContext("exec_f", "att_f", WS, plan, () -> false,
                (ExecutionEngine.EngineMetric m) -> { }, () -> { });
    }

    private void writeFile(String relative, String content) throws Exception {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static List<String> rows(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    sb.append(i > 1 ? ":" : "").append(rs.getObject(i));
                }
                out.add(sb.toString());
            }
        }
        return out;
    }

    private static final Map<String, Object> JDBC_SINK = Map.of("dataSourceId", "dst", "table", "people");

    // ── SOURCE_FILE ─────────────────────────────────────────────────────

    @Test
    @DisplayName("FILE CSV → JDBC:从数据源根目录下的相对路径读取,带表头与自定义分隔符")
    void csvFileToJdbc() throws Exception {
        writeFile("in/people.csv", "id;name;score\n1;alice;1.5\n2;bob;2.5\n3;\"carol;x\";3\n");
        Map<String, Object> plan = compile(Map.of("format", "CSV", "dataSourceId", "files",
                "path", "in/people.csv", "options", Map.of("delimiter", ";", "header", true)),
                "SOURCE_FILE", JDBC_SINK, "SINK_JDBC");

        EtlRunSummary summary = duck.execute(context(plan));

        assertThat(summary.rowsRead()).isEqualTo(3);
        assertThat(summary.rowsWritten()).isEqualTo(3);
        assertThat(rows(sinkDb, "SELECT * FROM people ORDER BY id"))
                .containsExactly("1:alice:1.5", "2:bob:2.5", "3:carol;x:3.0");
    }

    @Test
    @DisplayName("FILE JSON / NDJSON / PARQUET 都能读入")
    void otherFileFormats() throws Exception {
        writeFile("in/p.json", "[{\"id\":1,\"name\":\"a\",\"score\":1.0},{\"id\":2,\"name\":\"b\",\"score\":2.0}]");
        duck.execute(context(compile(Map.of("format", "JSON", "dataSourceId", "files", "path", "in/p.json"),
                "SOURCE_FILE", JDBC_SINK, "SINK_JDBC")));
        assertThat(rows(sinkDb, "SELECT * FROM people ORDER BY id")).containsExactly("1:a:1.0", "2:b:2.0");

        exec(sinkDb, "DELETE FROM people");
        writeFile("in/p.ndjson", "{\"id\":3,\"name\":\"c\",\"score\":3.0}\n{\"id\":4,\"name\":\"d\",\"score\":4.0}\n");
        duck.execute(context(compile(Map.of("format", "NDJSON", "dataSourceId", "files", "path", "in/p.ndjson"),
                "SOURCE_FILE", JDBC_SINK, "SINK_JDBC")));
        assertThat(rows(sinkDb, "SELECT * FROM people ORDER BY id")).containsExactly("3:c:3.0", "4:d:4.0");

        exec(sinkDb, "DELETE FROM people");
        try (Connection tmp = DriverManager.getConnection("jdbc:duckdb:")) {
            Files.createDirectories(root.resolve("in"));
            exec(tmp, "COPY (SELECT 5::BIGINT AS id, 'e' AS name, 5.0::DOUBLE AS score) TO '"
                    + root.resolve("in/p.parquet") + "' (FORMAT PARQUET)");
        }
        duck.execute(context(compile(Map.of("format", "PARQUET", "dataSourceId", "files", "path", "in/p.parquet"),
                "SOURCE_FILE", JDBC_SINK, "SINK_JDBC")));
        assertThat(rows(sinkDb, "SELECT * FROM people")).containsExactly("5:e:5.0");
    }

    @Test
    @DisplayName("文件读入发生在锁定之前:会话建立后 read_csv 读本地文件被 enable_external_access=false 拦截")
    void externalAccessLockedAfterFileExtract() throws Exception {
        writeFile("in/people.csv", "id,name,score\n1,a,1\n");
        writeFile("secret.csv", "x\n1\n");
        EtlPipelineEngine.Plan plan = engine.parse(compile(Map.of("format", "CSV", "dataSourceId", "files",
                "path", "in/people.csv"), "SOURCE_FILE", JDBC_SINK, "SINK_JDBC"));

        try (EtlPipelineEngine.Session session = engine.open(WS, plan, EtlPipelineEngine.Limits.execution(),
                () -> false, null);
             Statement st = session.duck().createStatement()) {
            assertThat(session.rowsRead()).isEqualTo(1);
            assertThatThrownBy(() -> st.executeQuery("SELECT * FROM read_csv('" + root.resolve("secret.csv") + "')"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("SET enable_external_access = true"))
                    .isInstanceOf(SQLException.class);
        }
        // 抽取用的临时文件已清理
        try (var tmp = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            assertThat(tmp.filter(p -> p.getFileName().toString().startsWith("dg-etl-src-"))
                    .filter(p -> Files.exists(p.resolve("source.csv")))).isEmpty();
        }
    }

    @Test
    @DisplayName("预览对文件源按 maxSourceRows 封顶")
    void previewCapsFileRows() throws Exception {
        StringBuilder csv = new StringBuilder("id,name,score\n");
        for (int i = 1; i <= 50; i++) {
            csv.append(i).append(",n").append(i).append(',').append(i).append('\n');
        }
        writeFile("in/big.csv", csv.toString());
        EtlPipelineEngine.Plan plan = engine.parse(compile(Map.of("format", "CSV", "dataSourceId", "files",
                "path", "in/big.csv"), "SOURCE_FILE", JDBC_SINK, "SINK_JDBC"));
        EtlPipelineEngine.PreviewResult preview = engine.preview(WS, plan, "src", 100,
                EtlPipelineEngine.Limits.preview(10, "512MB", 15));
        assertThat(preview.rows()).hasSize(10);
    }

    // ── 对象存储的安全边界 ──────────────────────────────────────────────

    @Test
    @DisplayName("对象存储:越界路径 / 绝对路径 / scheme / 跨空间数据源 / fileId 一律拒绝")
    void objectStoreRejectsUnsafeRefs() throws Exception {
        writeFile("in/ok.csv", "id\n1\n");
        Path dir = Files.createTempDirectory(root, "scratch");
        for (String bad : List.of("../etc/passwd", "/etc/passwd", "in/../../x", "file:///etc/passwd",
                "C:\\\\win\\\\x", "in\\\\ok.csv", "s3://bucket/key")) {
            assertThatThrownBy(() -> storeUnderTest.fetch(WS,
                    new EtlObjectStore.ObjectRef(null, "files", bad), "CSV", Map.of(), dir))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> storeUnderTest.fetch(WS,
                new EtlObjectStore.ObjectRef(null, "foreign", "in/ok.csv"), "CSV", Map.of(), dir))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("数据源不存在");
        assertThatThrownBy(() -> storeUnderTest.fetch(WS,
                new EtlObjectStore.ObjectRef("file_1", null, null), "CSV", Map.of(), dir))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fileId");
        assertThatThrownBy(() -> storeUnderTest.put(WS,
                new EtlObjectStore.ObjectRef(null, "files", "../out.parquet"), dir.resolve("x"), "SNAPPY"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("对象存储:符号链接指向根目录之外时拒绝读取")
    void objectStoreRejectsSymlinkEscape() throws Exception {
        Path outside = Files.createTempFile("dg-outside-", ".csv");
        try {
            Files.writeString(outside, "id\n1\n");
            Files.createDirectories(root.resolve("in"));
            Files.createSymbolicLink(root.resolve("in/link.csv"), outside);
            Path dir = Files.createTempDirectory(root, "scratch");
            assertThatThrownBy(() -> storeUnderTest.fetch(WS,
                    new EtlObjectStore.ObjectRef(null, "files", "in/link.csv"), "CSV", Map.of(), dir))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("越出");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    // ── SOURCE_API ──────────────────────────────────────────────────────

    @Test
    @DisplayName("API → JDBC:JSON 数组按行入表,类型从值推断,缺失键为 NULL")
    void apiToJdbc() throws Exception {
        apiRows.add(new LinkedHashMap<>(Map.of("id", 1L, "name", "a", "score", 1.5)));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("id", 2L);
        second.put("name", null);
        second.put("score", 2L);   // 整数与小数混合 → DOUBLE
        apiRows.add(second);

        EtlRunSummary summary = duck.execute(context(compile(Map.of("url", "https://example.com/api",
                "recordsPath", "data.items"), "SOURCE_API", JDBC_SINK, "SINK_JDBC")));

        assertThat(summary.rowsRead()).isEqualTo(2);
        assertThat(rows(sinkDb, "SELECT * FROM people ORDER BY id")).containsExactly("1:a:1.5", "2:null:2.0");
    }

    @Test
    @DisplayName("API 返回 0 条记录:明确报错,而不是留下无列的表")
    void apiEmptyResultFails() {
        assertThatThrownBy(() -> duck.execute(context(compile(Map.of("url", "https://example.com/api"),
                "SOURCE_API", JDBC_SINK, "SINK_JDBC"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("0 条");
    }

    @Test
    @DisplayName("API 取数的 SSRF 校验:回环 / 元数据 / 私网 / 非 http 地址被拒绝")
    void apiFetcherBlocksInternalHosts() {
        DefaultEtlApiFetcher fetcher = new DefaultEtlApiFetcher(mock(DataSourceMapper.class),
                mock(ConnectionConfigAssembler.class), new com.fasterxml.jackson.databind.ObjectMapper(), false);
        for (String url : List.of("http://127.0.0.1:8080/x", "http://localhost/x", "http://169.254.169.254/latest",
                "http://10.0.0.5/x", "http://192.168.1.1/x", "http://[::1]/x", "ftp://example.com/x")) {
            assertThatThrownBy(() -> fetcher.fetch(WS, url, null, null, 0)).as(url)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // 允许私网后,回环 / 元数据仍然拒绝(此处不真正发请求:失败原因必须是校验而非连接)
        DefaultEtlApiFetcher lenient = new DefaultEtlApiFetcher(mock(DataSourceMapper.class),
                mock(ConnectionConfigAssembler.class), new com.fasterxml.jackson.databind.ObjectMapper(), true);
        assertThatThrownBy(() -> lenient.fetch(WS, "http://169.254.169.254/x", null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lenient.fetch(WS, "http://127.0.0.1/x", null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── SINK_PARQUET ────────────────────────────────────────────────────

    @Test
    @DisplayName("CSV → PARQUET:写出到数据源根目录下的相对路径,可被 DuckDB 读回,压缩编码生效")
    void csvToParquet() throws Exception {
        writeFile("in/people.csv", "id,name,score\n1,alice,1.5\n2,bob,2.5\n3,carol,3.5\n");
        Map<String, Object> plan = compile(Map.of("format", "CSV", "dataSourceId", "files",
                        "path", "in/people.csv"), "SOURCE_FILE",
                Map.of("dataSourceId", "files", "path", "out/people.parquet", "compression", "ZSTD"),
                "SINK_PARQUET");

        EtlRunSummary summary = duck.execute(context(plan));

        assertThat(summary.rowsRead()).isEqualTo(3);
        assertThat(summary.rowsWritten()).isEqualTo(3);
        Path out = root.resolve("out/people.parquet");
        assertThat(out).exists();
        try (Connection read = DriverManager.getConnection("jdbc:duckdb:")) {
            assertThat(rows(read, "SELECT id, name, score FROM read_parquet('" + out + "') ORDER BY id"))
                    .containsExactly("1:alice:1.5", "2:bob:2.5", "3:carol:3.5");
            assertThat(rows(read, "SELECT DISTINCT compression FROM parquet_metadata('" + out + "')"))
                    .containsExactly("ZSTD");
        }
        // 没有遗留的暂存文件
        try (var files = Files.list(out.getParent())) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("people.parquet");
        }
    }

    @Test
    @DisplayName("PARQUET 目标 OVERWRITE:重跑覆盖旧文件;JDBC 源 → PARQUET 也可用")
    void parquetOverwriteFromJdbc() throws Exception {
        exec(sinkDb, "INSERT INTO people VALUES (1,'a',1.0),(2,'b',2.0)");
        Map<String, Object> plan = compile(Map.of("dataSourceId", "src", "table", "people"), "SOURCE_JDBC",
                Map.of("dataSourceId", "files", "path", "lake/people.parquet"), "SINK_PARQUET");

        duck.execute(context(plan));
        exec(sinkDb, "INSERT INTO people VALUES (3,'c',3.0)");
        EtlRunSummary second = duck.execute(context(plan));

        assertThat(second.rowsWritten()).isEqualTo(3);
        try (Connection read = DriverManager.getConnection("jdbc:duckdb:")) {
            assertThat(rows(read, "SELECT count(*) FROM read_parquet('" + root.resolve("lake/people.parquet") + "')"))
                    .containsExactly("3");
        }
    }

    @Test
    @DisplayName("PARQUET 目标:partitionBy / 非 OVERWRITE 在解析阶段明确拒绝")
    void parquetUnsupportedOptionsRejected() throws Exception {
        writeFile("in/people.csv", "id,name,score\n1,a,1\n");
        Map<String, Object> source = Map.of("format", "CSV", "dataSourceId", "files", "path", "in/people.csv");
        Map<String, Object> partitioned = compile(source, "SOURCE_FILE",
                Map.of("dataSourceId", "files", "path", "out/p.parquet", "partitionBy", List.of("name")),
                "SINK_PARQUET");
        assertThatThrownBy(() -> engine.parse(partitioned)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partitionBy");

        Map<String, Object> append = compile(source, "SOURCE_FILE",
                Map.of("dataSourceId", "files", "path", "out/p.parquet", "writeMode", "APPEND"), "SINK_PARQUET");
        assertThatThrownBy(() -> engine.parse(append)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OVERWRITE");
    }

    @Test
    @DisplayName("没有对象存储实现时,文件源 / Parquet 目标明确报错")
    @SuppressWarnings("unchecked")
    void missingStoreFails() throws Exception {
        writeFile("in/people.csv", "id,name,score\n1,a,1\n");
        ObjectProvider<EtlObjectStore> none = mock(ObjectProvider.class);
        EtlPipelineEngine bare = new EtlPipelineEngine(mock(EtlJdbcGateway.class), mock(ObjectProvider.class),
                mock(ObjectProvider.class), none, mock(ObjectProvider.class));
        Map<String, Object> plan = compile(Map.of("format", "CSV", "dataSourceId", "files",
                "path", "in/people.csv"), "SOURCE_FILE", JDBC_SINK, "SINK_JDBC");
        assertThatThrownBy(() -> bare.open(WS, bare.parse(plan), EtlPipelineEngine.Limits.execution(),
                () -> false, null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("对象存储");
    }
}
