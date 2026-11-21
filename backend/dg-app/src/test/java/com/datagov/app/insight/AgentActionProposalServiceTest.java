package com.datagov.app.insight;

import com.datagov.app.insight.AgentActionProposalService.CreateProposalCommand;
import com.datagov.app.insight.AgentActionProposalService.ProposalView;
import com.datagov.app.insight.entity.AgentActionProposal;
import com.datagov.app.insight.mapper.AgentActionProposalMapper;
import com.datagov.common.error.BizException;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.service.AuditService;
import com.datagov.metadata.service.CatalogAssetService;
import com.datagov.metadata.service.DataSourceService;
import com.datagov.platform.service.PermissionService;
import com.datagov.platform.service.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentActionProposalServiceTest {

    private final Map<String, AgentActionProposal> store = new HashMap<>();
    private AgentActionProposalMapper mapper;
    private AgentControlPlaneService controlPlane;
    private DataSourceService dataSourceService;
    private CatalogAssetService catalogAssetService;
    private WorkspaceService workspaceService;
    private PermissionService permissionService;
    private AgentActionProposalService service;

    @BeforeEach
    void setUp() {
        store.clear();
        mapper = mock(AgentActionProposalMapper.class);
        doAnswer(inv -> {
            AgentActionProposal p = inv.getArgument(0);
            store.put(p.getId(), p);
            return 1;
        }).when(mapper).insert(any(AgentActionProposal.class));
        doAnswer(inv -> {
            AgentActionProposal p = inv.getArgument(0);
            store.put(p.getId(), p);
            return 1;
        }).when(mapper).updateById(any(AgentActionProposal.class));
        when(mapper.selectById(any())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        when(mapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(store.values()));

        controlPlane = mock(AgentControlPlaneService.class);
        when(controlPlane.requireSafeRootPath(any())).thenAnswer(inv -> inv.getArgument(0));
        dataSourceService = mock(DataSourceService.class);
        catalogAssetService = mock(CatalogAssetService.class);
        workspaceService = mock(WorkspaceService.class);
        permissionService = mock(PermissionService.class);

        service = new AgentActionProposalService(
                mapper, controlPlane, dataSourceService, catalogAssetService,
                workspaceService, permissionService, mock(AuditService.class), new ObjectMapper());

        WorkspaceContext.set(new Caller("usr_alice", "alice", "ws_1", false,
                Set.of("insight:ask", "metadata:datasource:create")));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("P1-9：自治审批跨用户可见，确认后只向 DurableFlowEngine 发信号")
    void autonomyApprovalWakesFlow() {
        AutonomyApprovalExecutor executor = mock(AutonomyApprovalExecutor.class);
        when(executor.execute(any())).thenReturn(Map.of("instanceId", "afi_1", "status", "RUNNING"));
        service.setAutonomyApprovalExecutor(executor);

        WorkspaceContext.set(new Caller("usr_approver", "approver", "ws_1", false,
                Set.of("autonomy:flow:approve")));
        when(permissionService.resolvePermissions("usr_approver", "ws_1"))
                .thenReturn(Set.of("autonomy:flow:approve"));
        AgentActionProposal proposal = new AgentActionProposal();
        proposal.setId("agpr_auto");
        proposal.setWorkspaceId("ws_1");
        proposal.setUserId("usr_alice");
        proposal.setUsername("alice");
        proposal.setActionType(AgentActionProposalService.ACTION_AUTONOMY_FLOW_APPROVAL);
        proposal.setRequiredPerm(AgentActionProposalService.PERM_AUTONOMY_APPROVE);
        proposal.setTitle("自治流程审批");
        proposal.setSummary("请求暂停同步任务");
        proposal.setPayloadJson("{\"instanceId\":\"afi_1\",\"stepId\":\"s5\"}");
        proposal.setPayloadDigest("a".repeat(64));
        proposal.setRiskLevel("HIGH");
        proposal.setStatus(AgentActionProposalService.STATUS_PENDING);
        proposal.setExpiresAt(Instant.now().plusSeconds(3600));
        proposal.setCreatedAt(Instant.now());
        proposal.setUpdatedAt(Instant.now());
        store.put(proposal.getId(), proposal);

        assertThat(service.listAutonomyPending()).hasSize(1);
        ProposalView view = service.confirmAutonomyProposal(proposal.getId(), proposal.getPayloadDigest(), "同意");
        assertThat(view.status()).isEqualTo(AgentActionProposalService.STATUS_EXECUTED);
        verify(executor).execute(any());
    }

    @Test
    @DisplayName("ASI09：创建提案只落 PENDING 状态，绝不直接调用 DataSourceService 落库")
    void createProposalDoesNotMutateDataSource() {
        ProposalView view = service.createProposal(new CreateProposalCommand(
                "conv_1",
                AgentActionProposalService.ACTION_CREATE_LOCAL_DATASOURCE,
                Map.of("name", "lake-demo", "rootPath", "/data/lakehouse")));

        assertThat(view.status()).isEqualTo(AgentActionProposalService.STATUS_PENDING);
        assertThat(view.payloadDigest()).hasSize(64);
        verify(dataSourceService, never()).create(any());
    }

    @Test
    @DisplayName("ASI09：非原发起人无法确认他人提案，篡改摘要同样被拒绝")
    void confirmRejectsOtherUserAndTamperedDigest() {
        ProposalView created = service.createProposal(new CreateProposalCommand(
                "conv_1",
                AgentActionProposalService.ACTION_CREATE_LOCAL_DATASOURCE,
                Map.of("name", "lake-demo", "rootPath", "/data/lakehouse")));

        // 切换成同空间另一用户
        WorkspaceContext.set(new Caller("usr_bob", "bob", "ws_1", false,
                Set.of("insight:ask", "metadata:datasource:create")));
        assertThatThrownBy(() -> service.confirmProposal(created.id(), created.payloadDigest(), null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("PLT_FORBIDDEN"));

        // 切回原发起人但摘要不匹配
        WorkspaceContext.set(new Caller("usr_alice", "alice", "ws_1", false,
                Set.of("insight:ask", "metadata:datasource:create")));
        assertThatThrownBy(() -> service.confirmProposal(created.id(), "deadbeef", null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("SYS_CONFLICT"));
        verify(dataSourceService, never()).create(any());
    }

    @Test
    @DisplayName("ASI09：执行瞬间二次校验权限（Recheck at Execution），权限被收回则拒绝执行")
    void confirmRechecksLivePermissionsAndPreventsReplay() {
        ProposalView created = service.createProposal(new CreateProposalCommand(
                "conv_1",
                AgentActionProposalService.ACTION_CREATE_LOCAL_DATASOURCE,
                Map.of("name", "lake-demo", "rootPath", "/data/lakehouse")));

        // 模拟确认前权限已被管理员撤销
        when(permissionService.resolvePermissions("usr_alice", "ws_1"))
                .thenReturn(Set.of("insight:ask"));
        assertThatThrownBy(() -> service.confirmProposal(created.id(), created.payloadDigest(), "确认"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("PLT_FORBIDDEN"));
        verify(dataSourceService, never()).create(any());

        // 恢复权限后确认成功
        when(permissionService.resolvePermissions("usr_alice", "ws_1"))
                .thenReturn(Set.of("insight:ask", "metadata:datasource:create"));
        ProposalView executed = service.confirmProposal(created.id(), created.payloadDigest(), "核对无误");
        assertThat(executed.status()).isEqualTo(AgentActionProposalService.STATUS_EXECUTED);
        verify(dataSourceService).create(any());

        // 重放再次确认被状态机拒绝
        assertThatThrownBy(() -> service.confirmProposal(created.id(), created.payloadDigest(), "重放"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("GOV_PROPOSAL_STATE"));
    }

    @Test
    @DisplayName("ASI09：已过期的提案自动转 EXPIRED 并拒绝执行")
    void expiredProposalRejected() {
        ProposalView created = service.createProposal(new CreateProposalCommand(
                "conv_1",
                AgentActionProposalService.ACTION_CREATE_LOCAL_DATASOURCE,
                Map.of("name", "lake-exp", "rootPath", "/data/lakehouse")));
        store.get(created.id()).setExpiresAt(Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> service.confirmProposal(created.id(), created.payloadDigest(), null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("GOV_PROPOSAL_STATE"));
    }
}
