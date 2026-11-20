package com.datagov.app.insight;

import com.datagov.app.insight.entity.AgentControlPolicy;
import com.datagov.app.insight.mapper.AgentControlPolicyMapper;
import com.datagov.common.error.BizException;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentControlPlaneServiceTest {

    private AgentControlPolicyMapper mapper;
    private AuditService auditService;
    private AgentControlPlaneService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AgentControlPolicyMapper.class);
        auditService = mock(AuditService.class);
        service = new AgentControlPlaneService(mapper, auditService);
        ReflectionTestUtils.setField(service, "agentBaseUrl", "");
        WorkspaceContext.set(new Caller("usr_admin", "admin", "ws_1", true,
                Set.of("governance:agent:manage")));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("ASI10：全局或空间 Kill Switch 开启后立即拦截请求")
    void killSwitchBlocksWorkspace() {
        AgentControlPolicy wsPolicy = new AgentControlPolicy();
        wsPolicy.setWorkspaceId("ws_1");
        wsPolicy.setKillSwitchEnabled(true);
        wsPolicy.setKillSwitchReason("紧急演练停用");
        when(mapper.selectOne(any())).thenReturn(null, wsPolicy);

        assertThatThrownBy(() -> service.requireAgentEnabled("ws_1"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("GOV_AGENT_DISABLED"));
    }

    @Test
    @DisplayName("ASI08：超出每日轮次配额后触发 429 熔断")
    void dailyTurnQuotaTriggersCircuitBreaker() {
        AgentControlPolicy global = new AgentControlPolicy();
        global.setWorkspaceId(AgentControlPolicy.GLOBAL_WORKSPACE);
        global.setKillSwitchEnabled(false);
        global.setDailyTurnQuota(2);
        when(mapper.selectOne(any())).thenReturn(global, null, global, null, global, null);

        service.requireAndConsumeTurn("ws_quota");
        service.requireAndConsumeTurn("ws_quota");

        assertThatThrownBy(() -> service.requireAndConsumeTurn("ws_quota"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("GOV_AGENT_QUOTA_EXCEEDED"));
    }

    @Test
    @DisplayName("ASI02：rootPath 禁止根目录、路径穿越及白名单外路径")
    void rootPathAllowlistEnforced() {
        ReflectionTestUtils.setField(service, "localRootAllowlist", "/data/lakehouse,/mnt/nfs");

        assertThat(service.requireSafeRootPath("/data/lakehouse/clinical")).isEqualTo("/data/lakehouse/clinical");

        assertThatThrownBy(() -> service.requireSafeRootPath("/etc/passwd"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("MTD_CONFIG_INVALID"));

        assertThatThrownBy(() -> service.requireSafeRootPath("/data/lakehouse/../../etc"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("MTD_CONFIG_INVALID"));
    }
}
