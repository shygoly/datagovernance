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
}
