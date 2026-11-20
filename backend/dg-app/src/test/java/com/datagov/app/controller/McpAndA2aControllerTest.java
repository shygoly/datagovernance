package com.datagov.app.controller;

import com.datagov.app.controller.McpServerController.JsonRpcRequest;
import com.datagov.app.insight.AgentActionProposalService;
import com.datagov.app.insight.AgentActionProposalService.ProposalView;
import com.datagov.app.insight.AgentControlPlaneService;
import com.datagov.app.insight.GenAiTelemetry;
import com.datagov.app.insight.InsightQueryService;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.service.CatalogAssetService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpAndA2aControllerTest {

    private InsightQueryService queryService;
    private CatalogAssetService catalogAssetService;
    private AgentActionProposalService proposalService;
    private AgentControlPlaneService controlPlane;
    private GenAiTelemetry telemetry;
    private McpServerController mcpController;
    private AgentCardController cardController;

    @BeforeEach
    void setUp() {
        queryService = mock(InsightQueryService.class);
        catalogAssetService = mock(CatalogAssetService.class);
        proposalService = mock(AgentActionProposalService.class);
        controlPlane = mock(AgentControlPlaneService.class);
        telemetry = new GenAiTelemetry();
        mcpController = new McpServerController(
                queryService, catalogAssetService, proposalService, controlPlane, telemetry);
        cardController = new AgentCardController();

        WorkspaceContext.set(new Caller("usr_1", "alice", "ws_1", false,
                Set.of("insight:ask", "metadata:datasource:create")));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("C1：MCP initialize 与 tools/list 返回 2026-07-28 规约与受治理工具列表")
    @SuppressWarnings("unchecked")
    void mcpInitializeAndListTools() {
        Map<String, Object> init = mcpController.handle(
                new JsonRpcRequest("2.0", 1, "initialize", Map.of()));
        Map<String, Object> result = (Map<String, Object>) init.get("result");
        assertThat(result.get("protocolVersion")).isEqualTo(McpServerController.PROTOCOL_VERSION);

        Map<String, Object> list = mcpController.handle(
                new JsonRpcRequest("2.0", 2, "tools/list", Map.of()));
        Map<String, Object> listRes = (Map<String, Object>) list.get("result");
        assertThat((List<?>) listRes.get("tools")).hasSize(4);
    }

    @Test
    @DisplayName("C1：MCP 写工具只生成 PENDING 宿主确认提案，无权限调用被拒")
    @SuppressWarnings("unchecked")
    void mcpWriteToolCreatesPendingProposalAndEnforcesPermission() {
        when(proposalService.createProposal(any())).thenReturn(new ProposalView(
                "agpr_1", "ws_1", "usr_1", "alice", "mcp",
                AgentActionProposalService.ACTION_CREATE_LOCAL_DATASOURCE,
                "metadata:datasource:create", "创建本地数据源", "摘要",
                Map.of("name", "lake", "rootPath", "/data/lake"),
                "digest", "MEDIUM", "PENDING", null, null,
                Instant.now().plusSeconds(900), null, null, null, Instant.now()));

        Map<String, Object> resp = mcpController.handle(new JsonRpcRequest(
                "2.0", 3, "tools/call",
                Map.of("name", "propose_local_onboarding",
                        "arguments", Map.of("name", "lake", "rootPath", "/data/lake"))));
        assertThat(resp).containsKey("result");

        // 切换为无写权限用户
        WorkspaceContext.set(new Caller("usr_2", "viewer", "ws_1", false, Set.of("insight:read")));
        Map<String, Object> denied = mcpController.handle(new JsonRpcRequest(
                "2.0", 4, "tools/call",
                Map.of("name", "propose_local_onboarding",
                        "arguments", Map.of("name", "lake", "rootPath", "/data/lake"))));
        assertThat(denied).containsKey("error");
        Map<String, Object> err = (Map<String, Object>) denied.get("error");
        assertThat(((Map<String, Object>) err.get("data")).get("errorCode")).isEqualTo("PLT_FORBIDDEN");
    }

    @Test
    @DisplayName("C1：A2A Agent Card 返回 v1.0 发现元数据，且 OTel 默认不采集原始 Prompt")
    @SuppressWarnings("unchecked")
    void a2aCardAndOtelNoRawPromptByDefault() {
        Map<String, Object> card = cardController.agentCard();
        assertThat(card.get("protocolVersion")).isEqualTo("1.0");
        assertThat(card.get("mcpEndpoint")).isEqualTo("/api/v1/mcp");

        assertThat(telemetry.isCaptureContent()).isFalse();
        Map<String, Object> span = telemetry.recordSpan(
                "chat", "ws_1", "usr_1", null, 42L, true,
                "患者敏感原始提示词", "敏感回复");
        Map<String, Object> attrs = (Map<String, Object>) span.get("attributes");
        assertThat(attrs.get(GenAiTelemetry.ATTR_SEMCONV_VERSION)).isEqualTo("1.42.0");
        assertThat(attrs).doesNotContainKey(GenAiTelemetry.ATTR_INPUT_MESSAGES);
        assertThat(attrs).doesNotContainKey(GenAiTelemetry.ATTR_OUTPUT_MESSAGES);
    }
}
