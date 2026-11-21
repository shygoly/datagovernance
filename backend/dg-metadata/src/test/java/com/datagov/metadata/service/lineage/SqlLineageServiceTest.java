package com.datagov.metadata.service.lineage;

import com.datagov.common.error.BizException;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.DataSourceType;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.mapper.DataSourceMapper;
import com.datagov.metadata.service.LineageCatalogResolver;
import com.datagov.metadata.service.LineageService;
import com.datagov.metadata.service.LineageService.JobRequest;
import com.datagov.metadata.service.LineageService.JobResult;
import com.datagov.metadata.service.LineageService.NodeRef;
import com.datagov.metadata.service.lineage.SqlLineageParserClient.ColumnEdge;
import com.datagov.metadata.service.lineage.SqlLineageParserClient.Diagnostic;
import com.datagov.metadata.service.lineage.SqlLineageParserClient.ParseRequest;
import com.datagov.metadata.service.lineage.SqlLineageParserClient.ParseResponse;
import com.datagov.metadata.service.lineage.SqlLineageParserClient.StatementLineage;
import com.datagov.metadata.service.lineage.SqlLineageService.ImportRequest;
import com.datagov.metadata.service.lineage.SqlLineageService.ImportView;
import com.datagov.metadata.service.lineage.SqlLineageService.PreviewRequest;
import com.datagov.metadata.service.lineage.SqlLineageService.PreviewView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SQL 血缘编排(预览 / 导入 / 视图)")
class SqlLineageServiceTest {

    private static final String WS = "ws_alpha";

    private SqlLineageParserClient client;
    private FakeResolver resolver;
    private LineageService lineageService;
    private DataSourceMapper dataSourceMapper;
    private SqlLineageService service;

    @BeforeEach
    void setUp() {
        client = mock(SqlLineageParserClient.class);
        resolver = new FakeResolver();
        lineageService = mock(LineageService.class);
        dataSourceMapper = mock(DataSourceMapper.class);
        service = new SqlLineageService(client, resolver, lineageService, dataSourceMapper);
        WorkspaceContext.set(new Caller("usr_1", "alice", WS, false, Set.of()));
        when(lineageService.recordJob(any())).thenReturn(new JobResult("linj_1", 2));
        when(lineageService.recordPlannedJob(any())).thenReturn(new JobResult("linj_1", 2));
        when(lineageService.recordJobParseOutcome(any())).thenReturn(new JobResult("linj_1", 0));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    private static ParseResponse okResponse() {
        return new ParseResponse("OK", "hash1", List.of(new StatementLineage("dws.t", List.of(
                new ColumnEdge("ods.s", "x", "dws.t", "a", "DIRECT", "COPY", null, false),
                new ColumnEdge("ods.s", "y", "dws.t", "b", "DIRECT", "TRANSFORM", "SUM(y)", false),
                new ColumnEdge("ods.s", "y", "dws.t", "b", "DIRECT", "TRANSFORM", "SUM(y)", false),
                new ColumnEdge("ods.s", "phone", "dws.t", "c", "DIRECT", "TRANSFORM", "MD5(phone)", true),
                new ColumnEdge("ods.s", "st", "dws.t", "a", "INDIRECT", "FILTER", "WHERE st = 1", false)),
                List.of())), List.of());
    }

    private static ParseResponse inconclusive() {
        return new ParseResponse("INCONCLUSIVE", "hash2", List.of(), List.of(
                new Diagnostic("STAR_WITHOUT_SCHEMA", "INCONCLUSIVE", "SELECT * cannot be expanded", 0)));
    }

    // ── 预览 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("预览：OK 时映射边(去重)、保留 INDIRECT/脱敏标记并标出未绑定 Catalog 的表，且不落库")
    void previewMapsEdgesWithoutWriting() {
        resolver.bind("ods.s", "ds_1", "cols");
        when(client.parseColumn(any())).thenReturn(okResponse());

        PreviewView view = service.preview(new PreviewRequest("postgres", "INSERT ...", null, null,
                null, null));

        assertThat(view.status()).isEqualTo("OK");
        assertThat(view.sqlHash()).isEqualTo("hash1");
        assertThat(view.edges()).hasSize(4);          // 重复的 SUM(y) 边被去重
        assertThat(view.edges().get(0).fromQualifiedName()).isEqualTo("ods.s.x");
        assertThat(view.edges().get(0).fromCatalogBound()).isTrue();
        assertThat(view.edges().get(0).toCatalogBound()).isFalse();
        assertThat(view.edges()).anySatisfy(e -> {
            assertThat(e.maskingApplied()).isTrue();
            assertThat(e.transformExpr()).isEqualTo("MD5(phone)");
        });
        assertThat(view.edges()).anySatisfy(e -> {
            assertThat(e.dependencyType()).isEqualTo("INDIRECT");
            assertThat(e.relation()).isEqualTo("FILTER");
        });
        assertThat(view.unresolvedTables()).containsExactly("dws.t");
        verify(lineageService, never()).recordJob(any());
        verify(lineageService, never()).recordJobParseOutcome(any());
    }

    @Test
    @DisplayName("预览：INCONCLUSIVE 原样返回诊断，没有任何边")
    void previewInconclusive() {
        when(client.parseColumn(any())).thenReturn(inconclusive());

        PreviewView view = service.preview(new PreviewRequest("postgres", "INSERT ... SELECT *",
                null, null, null, null));

        assertThat(view.status()).isEqualTo("INCONCLUSIVE");
        assertThat(view.edges()).isEmpty();
        assertThat(view.diagnostics()).extracting(Diagnostic::code).containsExactly("STAR_WITHOUT_SCHEMA");
    }

    @Test
    @DisplayName("预览：sidecar 不可达(客户端降级)同样得到 INCONCLUSIVE，不抛异常")
    void previewParserUnavailable() {
        when(client.parseColumn(any())).thenReturn(ParseResponse.inconclusive(
                SqlLineageParserClient.CODE_PARSER_UNAVAILABLE, "无法连接血缘解析服务"));

        PreviewView view = service.preview(new PreviewRequest("postgres", "SELECT 1", null, null,
                null, null));

        assertThat(view.status()).isEqualTo("INCONCLUSIVE");
        assertThat(view.edges()).isEmpty();
    }

    @Test
    @DisplayName("SQL 为空直接 400")
    void blankSqlRejected() {
        assertThatThrownBy(() -> service.preview(new PreviewRequest("postgres", " ", null, null,
                null, null))).isInstanceOf(BizException.class);
    }

    // ── 导入 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("导入：OK 时按 ACTIVE 写边，作业类型 SQL_IMPORT")
    void importWritesActiveEdges() {
        when(client.parseColumn(any())).thenReturn(okResponse());

        ImportView view = service.importSql(new ImportRequest("订单汇总", "postgres",
                "INSERT ...", null, null, null, null));

        assertThat(view.imported()).isTrue();
        assertThat(view.jobId()).isEqualTo("linj_1");
        ArgumentCaptor<JobRequest> captor = ArgumentCaptor.forClass(JobRequest.class);
        verify(lineageService).recordJob(captor.capture());
        JobRequest request = captor.getValue();
        assertThat(request.name()).isEqualTo("订单汇总");
        assertThat(request.jobType()).isEqualTo("SQL_IMPORT");
        assertThat(request.sqlHash()).isEqualTo("hash1");
        assertThat(request.parseStatus()).isEqualTo("OK");
        assertThat(request.edges()).hasSize(4);
        assertThat(request.edges()).anySatisfy(e -> {
            assertThat(e.maskingApplied()).isTrue();
            assertThat(e.maskingEvidence()).isNotBlank();
        });
        verify(lineageService, never()).recordJobParseOutcome(any());
    }

    @Test
    @DisplayName("导入(C8)：INCONCLUSIVE 不写任何边，只登记作业解析状态与原因")
    void importInconclusiveWritesNoEdges() {
        when(client.parseColumn(any())).thenReturn(inconclusive());

        ImportView view = service.importSql(new ImportRequest("job", "postgres",
                "INSERT ... SELECT *", null, null, null, null));

        assertThat(view.imported()).isFalse();
        assertThat(view.status()).isEqualTo("INCONCLUSIVE");
        verify(lineageService, never()).recordJob(any());
        ArgumentCaptor<JobRequest> captor = ArgumentCaptor.forClass(JobRequest.class);
        verify(lineageService).recordJobParseOutcome(captor.capture());
        assertThat(captor.getValue().parseStatus()).isEqualTo("INCONCLUSIVE");
        assertThat(captor.getValue().parseMessage()).contains("STAR_WITHOUT_SCHEMA");
        assertThat(captor.getValue().edges()).isEmpty();
    }

    @Test
    @DisplayName("导入：缺少 jobName 被拒")
    void importRequiresJobName() {
        assertThatThrownBy(() -> service.importSql(new ImportRequest(" ", "postgres", "SELECT 1",
                null, null, null, null))).isInstanceOf(BizException.class);
    }

    // ── 方言 / catalogSchema ────────────────────────────────────────────

    @Test
    @DisplayName("dialect 缺省时按数据源类型推断；无法推断时 INCONCLUSIVE 且不请求 sidecar")
    void dialectInference() {
        DataSourceEntity ds = new DataSourceEntity();
        ds.setId("ds_pg");
        ds.setWorkspaceId(WS);
        ds.setType(DataSourceType.POSTGRESQL);
        when(dataSourceMapper.selectById("ds_pg")).thenReturn(ds);
        DataSourceEntity dameng = new DataSourceEntity();
        dameng.setId("ds_dm");
        dameng.setWorkspaceId(WS);
        dameng.setType(DataSourceType.DAMENG);
        when(dataSourceMapper.selectById("ds_dm")).thenReturn(dameng);
        when(client.parseColumn(any())).thenReturn(okResponse());

        service.preview(new PreviewRequest(null, "INSERT ...", "ds_pg", null, null, null));
        ArgumentCaptor<ParseRequest> captor = ArgumentCaptor.forClass(ParseRequest.class);
        verify(client).parseColumn(captor.capture());
        assertThat(captor.getValue().dialect()).isEqualTo("postgres");

        PreviewView unknown = service.preview(new PreviewRequest(null, "INSERT ...", "ds_dm", null,
                null, null));
        assertThat(unknown.status()).isEqualTo("INCONCLUSIVE");
        assertThat(unknown.diagnostics().get(0).code()).isEqualTo("DIALECT_UNKNOWN");
        verify(client, org.mockito.Mockito.times(1)).parseColumn(any());
    }

    @Test
    @DisplayName("别的 Workspace 的数据源不会被用来推断方言")
    void foreignDataSourceIgnored() {
        DataSourceEntity foreign = new DataSourceEntity();
        foreign.setId("ds_x");
        foreign.setWorkspaceId("ws_other");
        foreign.setType(DataSourceType.MYSQL);
        when(dataSourceMapper.selectById("ds_x")).thenReturn(foreign);

        PreviewView view = service.preview(new PreviewRequest(null, "SELECT 1", "ds_x", null, null,
                null));

        assertThat(view.diagnostics().get(0).code()).isEqualTo("DIALECT_UNKNOWN");
        verify(client, never()).parseColumn(any());
    }

    @Test
    @DisplayName("未给 catalogSchema 时按 FROM/JOIN/INTO 粗扫表名，仅带上 Catalog 里解析到的表")
    void assemblesCatalogSchema() {
        resolver.bind("ods.orders", "ds_orders", "id", "amount");
        when(client.parseColumn(any())).thenReturn(okResponse());

        service.preview(new PreviewRequest("postgres",
                "INSERT INTO dws.t SELECT o.id FROM ods.orders o JOIN ods.unknown u ON u.id = o.id",
                null, null, null, null));

        ArgumentCaptor<ParseRequest> captor = ArgumentCaptor.forClass(ParseRequest.class);
        verify(client).parseColumn(captor.capture());
        Map<String, List<String>> catalog = captor.getValue().catalogSchema();
        assertThat(catalog).containsOnlyKeys("ods.orders");
        assertThat(catalog.get("ods.orders")).containsExactly("id", "amount");
    }

    @Test
    @DisplayName("调用方显式给的 catalogSchema 优先，不再自动组装")
    void explicitCatalogSchemaWins() {
        resolver.bind("ods.orders", "ds_orders", "id");
        when(client.parseColumn(any())).thenReturn(okResponse());
        Map<String, List<String>> explicit = new LinkedHashMap<>();
        explicit.put("ods.orders", List.of("only_this"));

        service.preview(new PreviewRequest("postgres", "SELECT * FROM ods.orders", null, null, null,
                explicit));

        ArgumentCaptor<ParseRequest> captor = ArgumentCaptor.forClass(ParseRequest.class);
        verify(client).parseColumn(captor.capture());
        assertThat(captor.getValue().catalogSchema().get("ods.orders")).containsExactly("only_this");
    }

    // ── 视图 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("视图：补全为 CREATE VIEW 并以 jobType=VIEW 按 ACTIVE 写入")
    void recordViewWritesActive() {
        DataSourceEntity ds = new DataSourceEntity();
        ds.setId("ds_pg");
        ds.setWorkspaceId(WS);
        ds.setType(DataSourceType.POSTGRESQL);
        when(dataSourceMapper.selectById("ds_pg")).thenReturn(ds);
        when(client.parseColumn(any())).thenReturn(okResponse());

        SqlLineageService.Outcome outcome = service.recordView("catds_1", "db.public.v_orders",
                "public.v_orders", " SELECT id FROM ods.orders; ", "ds_pg", "db", "public");

        assertThat(outcome.written()).isTrue();
        ArgumentCaptor<ParseRequest> parse = ArgumentCaptor.forClass(ParseRequest.class);
        verify(client).parseColumn(parse.capture());
        assertThat(parse.getValue().sql()).isEqualTo("CREATE VIEW public.v_orders AS SELECT id FROM ods.orders");
        ArgumentCaptor<JobRequest> request = ArgumentCaptor.forClass(JobRequest.class);
        verify(lineageService).recordJob(request.capture());
        assertThat(request.getValue().jobType()).isEqualTo("VIEW");
        assertThat(request.getValue().name()).isEqualTo("db.public.v_orders");
        assertThat(request.getValue().sourceRef()).isEqualTo("catalog:catds_1");
    }

    @Test
    @DisplayName("asCreateView：已是完整 DDL 的定义原样保留；SELECT / WITH / 括号开头才补全")
    void asCreateView() {
        assertThat(SqlLineageService.asCreateView("v", "WITH c AS (SELECT 1) SELECT * FROM c"))
                .startsWith("CREATE VIEW v AS WITH");
        assertThat(SqlLineageService.asCreateView("v", "(SELECT 1)")).startsWith("CREATE VIEW v AS (");
        assertThat(SqlLineageService.asCreateView("v", "CREATE OR REPLACE VIEW v AS SELECT 1"))
                .isEqualTo("CREATE OR REPLACE VIEW v AS SELECT 1");
    }

    @Test
    @DisplayName("dialectOf 只映射语义一致的类型，不近似映射达梦 / Db2 / 人大金仓")
    void dialectMapping() {
        assertThat(SqlLineageService.dialectOf(DataSourceType.POSTGRESQL)).isEqualTo("postgres");
        assertThat(SqlLineageService.dialectOf(DataSourceType.SQLSERVER)).isEqualTo("tsql");
        assertThat(SqlLineageService.dialectOf(DataSourceType.STARROCKS)).isEqualTo("starrocks");
        assertThat(SqlLineageService.dialectOf(DataSourceType.DAMENG)).isNull();
        assertThat(SqlLineageService.dialectOf(DataSourceType.KINGBASE)).isNull();
        assertThat(SqlLineageService.dialectOf(null)).isNull();
    }

    // ── 替身 ────────────────────────────────────────────────────────────

    /** 不依赖 Mockito 的 Catalog 替身：按 {@code schema.table} 预置数据集与列。 */
    private static final class FakeResolver extends LineageCatalogResolver {
        final Map<String, String> datasets = new LinkedHashMap<>();
        final Map<String, List<String>> columns = new LinkedHashMap<>();

        FakeResolver() {
            super(null, null, null);
        }

        void bind(String key, String datasetId, String... cols) {
            datasets.put(key, datasetId);
            columns.put(datasetId, List.of(cols));
        }

        @Override
        public Optional<CatalogDataset> resolveDataset(String workspaceId, String datasourceId,
                                                       String database, String schema, String table) {
            String id = datasets.get(schema == null ? table : schema + "." + table);
            if (id == null) {
                return Optional.empty();
            }
            CatalogDataset dataset = new CatalogDataset();
            dataset.setId(id);
            return Optional.of(dataset);
        }

        @Override
        public List<String> listColumnNames(String catalogDatasetId) {
            return columns.getOrDefault(catalogDatasetId, List.of());
        }

        @Override
        public NodeRef resolveNodeRef(String workspaceId, String datasourceId, String database,
                                      String schema, String table, String columnName) {
            String key = schema == null ? table : schema + "." + table;
            String datasetId = datasets.get(key);
            return new NodeRef("COLUMN", key + "." + columnName, datasetId,
                    datasetId == null ? null : datasetId + "." + columnName, columnName);
        }
    }
}
