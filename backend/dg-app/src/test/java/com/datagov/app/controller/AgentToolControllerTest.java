package com.datagov.app.controller;

import com.datagov.app.controller.AgentToolController.LocalDataSourceRequest;
import com.datagov.app.insight.InsightQueryService;
import com.datagov.common.error.BizException;
import com.datagov.data.spi.DataSourceType;
import com.datagov.governance.service.AuditService;
import com.datagov.metadata.dto.DataSourceUpsertCommand;
import com.datagov.metadata.service.CatalogAssetService;
import com.datagov.metadata.service.DataSourceService;
import com.datagov.platform.service.PermissionService;
import com.datagov.platform.service.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 写工具的第一道闸：权限必须按真实用户重新解析，不能因为 Node 说
 * “这是 admin” 就直接放行。
 */
class AgentToolControllerTest {

    private static final String TOKEN = "test-agent-token";
    private static final String USER_ID = "usr_1";
    private static final String USERNAME = "alice";
    private static final String WORKSPACE_ID = "ws_1";

    private InsightQueryService queryService;
    private DataSourceService dataSourceService;
    private CatalogAssetService catalogAssetService;
    private PermissionService permissionService;
    private WorkspaceService workspaceService;
    private AgentToolController controller;

    @BeforeEach
    void setUp() {
        queryService = mock(InsightQueryService.class);
        dataSourceService = mock(DataSourceService.class);
        catalogAssetService = mock(CatalogAssetService.class);
        permissionService = mock(PermissionService.class);
        workspaceService = mock(WorkspaceService.class);
        controller = new AgentToolController(
                queryService,
                dataSourceService,
                catalogAssetService,
                permissionService,
                workspaceService,
                mock(AuditService.class),
                new ObjectMapper());
        ReflectionTestUtils.setField(controller, "agentToken", TOKEN);
    }

    @Test
    @DisplayName("没有 datasource:create 权限时拒绝，不落库")
    void createRejectsUserWithoutPermission() {
        when(permissionService.resolvePermissions(USER_ID, WORKSPACE_ID))
                .thenReturn(Set.of("insight:ask"));

        assertThatThrownBy(() -> controller.createLocalDataSource(
                TOKEN, USER_ID, USERNAME, WORKSPACE_ID, false,
                new LocalDataSourceRequest("local-lake", "/data/lakehouse", null)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("PLT_FORBIDDEN"));

        verify(dataSourceService, never()).create(any());
    }

    @Test
    @DisplayName("有权限时把 Agent 参数转成 LOCAL 数据源命令")
    void createBuildsLocalCommand() {
        when(permissionService.resolvePermissions(USER_ID, WORKSPACE_ID))
                .thenReturn(Set.of("metadata:datasource:create"));
        when(dataSourceService.create(any())).thenReturn(null);

        controller.createLocalDataSource(TOKEN, USER_ID, USERNAME, WORKSPACE_ID, false,
                new LocalDataSourceRequest("local-lake", "/data/lakehouse", "演示目录"));

        ArgumentCaptor<DataSourceUpsertCommand> captor =
                ArgumentCaptor.forClass(DataSourceUpsertCommand.class);
        verify(dataSourceService).create(captor.capture());
        DataSourceUpsertCommand command = captor.getValue();
        assertThat(command.name()).isEqualTo("local-lake");
        assertThat(command.type()).isEqualTo(DataSourceType.LOCAL);
        assertThat(command.databaseName()).isEqualTo("/data/lakehouse");
        assertThat(command.description()).isEqualTo("演示目录");
    }

    @Test
    @DisplayName("rootPath 是根目录时直接拒绝，避免误扫整个文件系统")
    void createRejectsRootPath() {
        assertThatThrownBy(() -> controller.createLocalDataSource(
                TOKEN, USER_ID, USERNAME, WORKSPACE_ID, false,
                new LocalDataSourceRequest("local-lake", "/", null)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("MTD_CONFIG_INVALID"));
    }

    @Test
    @DisplayName("ASI03：/datasets 缺少 userId 时拒绝，不再构造合成服务账号 Caller")
    void datasetsRejectsMissingUserId() {
        assertThatThrownBy(() -> controller.datasets(
                TOKEN, null, USERNAME, WORKSPACE_ID, false, WORKSPACE_ID, USERNAME))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("SYS_VALIDATION_FAILED"));

        verify(queryService, never()).datasets();
    }

    @Test
    @DisplayName("ASI03：/ask 无 insight:ask 权限时拒绝")
    void askRejectsWithoutPermission() {
        when(permissionService.resolvePermissions(USER_ID, WORKSPACE_ID))
                .thenReturn(Set.of("metadata:catalog:read"));

        assertThatThrownBy(() -> controller.ask(
                TOKEN, USER_ID, USERNAME, WORKSPACE_ID, false,
                new AgentToolController.AskToolRequest(WORKSPACE_ID, USERNAME, "今天有多少订单", 1L)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("PLT_FORBIDDEN"));

        verify(workspaceService).requireAccess(USER_ID, WORKSPACE_ID);
        verify(queryService, never()).ask(any());
    }

    @Test
    @DisplayName("ASI03：/datasets 头与参数 workspaceId 不一致时拒绝越权")
    void datasetsRejectsWorkspaceMismatch() {
        assertThatThrownBy(() -> controller.datasets(
                TOKEN, USER_ID, USERNAME, WORKSPACE_ID, false, "ws_other", USERNAME))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("PLT_FORBIDDEN"));
    }

    @Test
    @DisplayName("ASI03：/ask 合法成员实时鉴权后放行")
    void askSucceedsWithRealUserPermission() {
        when(permissionService.resolvePermissions(USER_ID, WORKSPACE_ID))
                .thenReturn(Set.of("insight:ask"));
        when(queryService.ask(any())).thenReturn(null);

        controller.ask(
                TOKEN, USER_ID, USERNAME, WORKSPACE_ID, false,
                new AgentToolController.AskToolRequest(WORKSPACE_ID, USERNAME, "今天有多少订单", 1L));

        verify(workspaceService).requireAccess(USER_ID, WORKSPACE_ID);
        verify(permissionService).resolvePermissions(USER_ID, WORKSPACE_ID);
        verify(queryService).ask(any());
    }

    @Test
    @DisplayName("C5：含 L3 敏感字段的数据集禁止路由至 EXTERNAL 外部模型，SELF_HOSTED 放行")
    void modelRoutingGuardrailBlocksExternalForL3AndAllowsSelfHosted() {
        var sensitivityMapper = mock(com.datagov.metadata.mapper.ColumnSensitivityMapper.class);
        var l3 = new com.datagov.metadata.entity.SensitivityEntities.ColumnSensitivity();
        l3.setDatasetId("cd_patient");
        l3.setLevel("L3");
        l3.setStatus("CONFIRMED");
        when(sensitivityMapper.selectList(any())).thenReturn(java.util.List.of(l3));

        AgentToolController guarded = new AgentToolController(
                queryService, dataSourceService, catalogAssetService,
                permissionService, workspaceService, mock(AuditService.class), new ObjectMapper(),
                null, null, null, null, null, null, null, null, sensitivityMapper);
        ReflectionTestUtils.setField(guarded, "agentToken", TOKEN);
        when(permissionService.resolvePermissions(USER_ID, WORKSPACE_ID))
                .thenReturn(Set.of("insight:ask"));

        assertThatThrownBy(() -> guarded.checkModelRouting(
                TOKEN, USER_ID, USERNAME, WORKSPACE_ID, false, "EXTERNAL",
                new AgentToolController.ModelRoutingCheckRequest("cd_patient", "EXTERNAL", false)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("GOV_MODEL_ROUTING_BLOCKED"));

        var allowed = guarded.checkModelRouting(
                TOKEN, USER_ID, USERNAME, WORKSPACE_ID, false, "SELF_HOSTED",
                new AgentToolController.ModelRoutingCheckRequest("cd_patient", "SELF_HOSTED", true));
        assertThat(allowed.data().allowed()).isTrue();
        assertThat(allowed.data().maxSensitivity()).isEqualTo("L3");
    }

    // ── 只读血缘工具（column-lineage-e2e P3-3）──────────────────────────

    @Test
    @DisplayName("P3-3：lineage_trace_column 无 metadata:lineage:read 权限时拒绝，不触达血缘服务")
    void lineageTraceRejectsWithoutPermission() {
        var lineageTools = mock(com.datagov.metadata.service.lineage.LineageAgentToolService.class);
        ReflectionTestUtils.setField(controller, "lineageAgentService", lineageTools);
        when(permissionService.resolvePermissions(USER_ID, WORKSPACE_ID))
                .thenReturn(Set.of("insight:ask"));

        assertThatThrownBy(() -> controller.traceLineageColumn(
                TOKEN, USER_ID, USERNAME, WORKSPACE_ID, false, "col_1", "both", 3, false))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("PLT_FORBIDDEN"));

        verify(workspaceService).requireAccess(USER_ID, WORKSPACE_ID);
        verify(lineageTools, never()).traceColumn(any(), any(), any(), any());
    }

    @Test
    @DisplayName("P3-3：lineage_impact_analysis 缺少 userId 上下文时拒绝（C4，无合成服务账号）")
    void lineageImpactRejectsMissingUserId() {
        var lineageTools = mock(com.datagov.metadata.service.lineage.LineageAgentToolService.class);
        ReflectionTestUtils.setField(controller, "lineageAgentService", lineageTools);

        assertThatThrownBy(() -> controller.lineageImpactAnalysis(
                TOKEN, null, USERNAME, WORKSPACE_ID, false, "col_1", null, 5, false, true))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("SYS_VALIDATION_FAILED"));

        verify(lineageTools, never()).impactAnalysis(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("P3-3：合法成员调用时，血缘服务在该用户的 WorkspaceContext 下执行，结束后上下文被清理")
    void lineageToolsRunUnderRealUserContext() {
        var lineageTools = mock(com.datagov.metadata.service.lineage.LineageAgentToolService.class);
        ReflectionTestUtils.setField(controller, "lineageAgentService", lineageTools);
        when(permissionService.resolvePermissions(USER_ID, WORKSPACE_ID))
                .thenReturn(Set.of("metadata:lineage:read"));
        var seen = new java.util.concurrent.atomic.AtomicReference<com.datagov.common.tenant.Caller>();
        when(lineageTools.traceColumn(any(), any(), any(), any())).thenAnswer(invocation -> {
            seen.set(com.datagov.common.tenant.WorkspaceContext.require());
            return new com.datagov.metadata.service.lineage.LineageAgentToolService.TraceResult(
                    "INCONCLUSIVE", "NO_LINEAGE_EVIDENCE", null, "col_1", "both", 3, false,
                    null, java.util.List.of(), java.util.List.of(), 0, false, false);
        });

        var response = controller.traceLineageColumn(
                TOKEN, USER_ID, USERNAME, WORKSPACE_ID, false, "col_1", "both", 3, false);

        assertThat(response.data().status()).isEqualTo("INCONCLUSIVE");
        assertThat(seen.get().userId()).isEqualTo(USER_ID);
        assertThat(seen.get().workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(seen.get().hasPermission("metadata:lineage:read")).isTrue();
        assertThat(com.datagov.common.tenant.WorkspaceContext.get()).isNull();
    }

    @Test
    @DisplayName("P3-3：Hop-by-Hop ACL 端到端——有血缘读权限但无 metadata:asset:read 的用户拿到 INCONCLUSIVE，不泄露任何名称")
    void lineageImpactAppliesHopByHopAclForRealUser() {
        var nodeMapper = mock(com.datagov.metadata.mapper.LineageNodeMapper.class);
        var datasetMapper = mock(com.datagov.metadata.mapper.CatalogDatasetMapper.class);
        var node = new com.datagov.metadata.entity.LineageEntities.LineageNode();
        node.setId("lin_secret");
        node.setWorkspaceId(WORKSPACE_ID);
        node.setNodeType("COLUMN");
        node.setQualifiedName("hr.payroll.salary");
        node.setCatalogDatasetId("ds_secret");
        node.setCatalogColumnId("col_secret");
        when(nodeMapper.selectList(any())).thenReturn(java.util.List.of(node));
        when(datasetMapper.selectList(any())).thenReturn(java.util.List.of());
        var lineage = new com.datagov.metadata.service.LineageService(
                nodeMapper, mock(com.datagov.metadata.mapper.LineageJobMapper.class),
                mock(com.datagov.metadata.mapper.LineageEdgeMapper.class), datasetMapper,
                new com.datagov.app.lineage.DatasetPermissionLineageAclEvaluator(datasetMapper), null, null);
        ReflectionTestUtils.setField(controller, "lineageAgentService",
                new com.datagov.metadata.service.lineage.LineageAgentToolService(lineage));
        when(permissionService.resolvePermissions(USER_ID, WORKSPACE_ID))
                .thenReturn(Set.of("metadata:lineage:read"));

        var response = controller.lineageImpactAnalysis(
                TOKEN, USER_ID, USERNAME, WORKSPACE_ID, false, "col_secret", null, 5, false, true);

        assertThat(response.data().status()).isEqualTo("INCONCLUSIVE");
        assertThat(response.data().reason()).isEqualTo("ROOT_RESTRICTED");
        assertThat(response.data().toString()).doesNotContain("payroll", "salary", "ds_secret");
    }
}
