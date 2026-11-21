package com.datagov.app.autonomy;

import com.datagov.app.insight.AgentControlPlaneService;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.control.autonomy.ArbiterService;
import com.datagov.control.autonomy.FlowDefinitionService;
import com.datagov.control.autonomy.TriggerDefinitionService;
import com.datagov.control.autonomy.TriggerEvaluationService;
import com.datagov.control.entity.AutonomyControlEntities.TriggerDefinition;
import com.datagov.governance.service.AuditService;
import com.datagov.governance.service.DecisionTraceService;
import com.datagov.runtime.autonomy.CompiledFlowPlan;
import com.datagov.runtime.autonomy.DurableFlowEngine;
import com.datagov.runtime.autonomy.FlowStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutonomyFlowServiceTest {

    private TriggerDefinitionService triggerService;
    private TriggerEvaluationService evaluationService;
    private FlowDefinitionService flowService;
    private ArbiterService arbiter;
    private DecisionTraceService traceService;
    private DurableFlowEngine flowEngine;
    private AutonomyFlowService service;

    @BeforeEach
    void setUp() {
        triggerService = mock(TriggerDefinitionService.class);
        evaluationService = mock(TriggerEvaluationService.class);
        flowService = mock(FlowDefinitionService.class);
        arbiter = mock(ArbiterService.class);
        traceService = mock(DecisionTraceService.class);
        flowEngine = mock(DurableFlowEngine.class);
        AgentControlPlaneService controlPlane = mock(AgentControlPlaneService.class);
        when(controlPlane.resolveEffectivePolicy(anyString()))
                .thenReturn(new AgentControlPlaneService.EffectivePolicy(
                        "ws1", false, null, 12, 2, 200, 0, List.of()));
        service = new AutonomyFlowService(triggerService, evaluationService, flowService,
                arbiter, traceService, flowEngine, mock(AuditService.class), controlPlane);
        WorkspaceContext.set(new Caller("u1", "tester", "ws1", false, Set.of()));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("触发命中 → 裁决通过 → 启动实例 → trace 绑定，完整编排顺序")
    void firesFlowAndStartsInstance() {
        TriggerDefinition trigger = new TriggerDefinition();
        trigger.setId("atr_1");
        trigger.setWorkspaceId("ws1");
        trigger.setCode("etl_guard");
        trigger.setName("ETL guard");
        trigger.setFlowId("afl_1");
        trigger.setPriority(100);
        when(triggerService.require("ws1", "atr_1")).thenReturn(trigger);
        when(evaluationService.evaluate(eq(trigger), any(), any()))
                .thenReturn(new TriggerEvaluationService.Evaluation(true, "ETL_ANOMALY",
                        "ETL_ANOMALY", Map.of("jobId", "job_1"), Map.of("badRowRate", 0.2)));
        when(flowService.findCompiledPlan("afl_1")).thenReturn(plan());
        when(arbiter.competingFlowCodes(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(arbiter.decide(anyString(), any(), any()))
                .thenReturn(new ArbiterService.ArbiterDecision(ArbiterService.APPROVED,
                        "ALL_GATES_PASSED", Map.of(), List.of(), false));
        when(traceService.start(any())).thenReturn("atrc_1");
        when(flowEngine.startInstance(any(), any(), any())).thenReturn("afi_1");

        var result = service.evaluateAndFire("atr_1", Map.of("jobId", "job_1"));

        assertThat(result.fired()).isTrue();
        assertThat(result.instanceId()).isEqualTo("afi_1");
        assertThat(result.traceId()).isEqualTo("atrc_1");
        assertThat(result.reason()).isEqualTo("INSTANCE_STARTED");
        verify(traceService).updateArbiter(eq("ws1"), eq("atrc_1"), any());
        verify(triggerService).markFired(eq("atr_1"), any());
        verify(flowEngine).startInstance(any(), any(), any());
    }

    @Test
    @DisplayName("Arbiter 否决时不启动实例，trace 明确 INCONCLUSIVE")
    void rejectedDoesNotStart() {
        TriggerDefinition trigger = new TriggerDefinition();
        trigger.setId("atr_1");
        trigger.setWorkspaceId("ws1");
        trigger.setCode("etl_guard");
        trigger.setName("ETL guard");
        trigger.setFlowId("afl_1");
        when(triggerService.require("ws1", "atr_1")).thenReturn(trigger);
        when(evaluationService.evaluate(eq(trigger), any(), any()))
                .thenReturn(new TriggerEvaluationService.Evaluation(true, "ETL_ANOMALY",
                        "ETL_ANOMALY", Map.of("jobId", "job_1"), Map.of("badRowRate", 0.2)));
        when(flowService.findCompiledPlan("afl_1")).thenReturn(plan());
        when(arbiter.decide(anyString(), any(), any()))
                .thenReturn(new ArbiterService.ArbiterDecision(ArbiterService.REJECTED,
                        "INVARIANT_VIOLATED", Map.of(), List.of(), false));
        when(traceService.start(any())).thenReturn("atrc_2");

        var result = service.evaluateAndFire("atr_1", Map.of("jobId", "job_1"));

        assertThat(result.instanceId()).isNull();
        assertThat(result.reason()).isEqualTo("ARBITER_REJECTED");
        verify(traceService).updateVerify(eq("ws1"), eq("atrc_2"),
                eq(DecisionTraceService.INCONCLUSIVE), any());
        verify(flowEngine, org.mockito.Mockito.never()).startInstance(any(), any(), any());
    }


    @Test
    @DisplayName("C5：触发快照进入 trace 前剥离行内容与明文 PII")
    void sanitizesTriggerSnapshot() {
        TriggerDefinition trigger = new TriggerDefinition();
        trigger.setId("atr_1");
        trigger.setWorkspaceId("ws1");
        trigger.setCode("etl_guard");
        trigger.setName("ETL guard");
        trigger.setFlowId("afl_1");
        when(triggerService.require("ws1", "atr_1")).thenReturn(trigger);
        when(evaluationService.evaluate(eq(trigger), any(), any()))
                .thenReturn(new TriggerEvaluationService.Evaluation(true, "ETL_ANOMALY",
                        "ETL_ANOMALY", Map.of("jobId", "job_1"), Map.of("badRowRate", 0.2)));
        when(flowService.findCompiledPlan("afl_1")).thenReturn(plan());
        when(arbiter.competingFlowCodes(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(arbiter.decide(anyString(), any(), any()))
                .thenReturn(new ArbiterService.ArbiterDecision(ArbiterService.APPROVED,
                        "ALL_GATES_PASSED", Map.of(), List.of(), false));
        when(traceService.start(any())).thenReturn("atrc_3");
        when(flowEngine.startInstance(any(), any(), any())).thenReturn("afi_3");

        service.evaluateAndFire("atr_1", Map.of(
                "jobId", "job_1",
                "phone", "13812345678",
                "rows", List.of(Map.of("phone", "13812345678"))));

        var captor = org.mockito.ArgumentCaptor.forClass(DecisionTraceService.StartCommand.class);
        verify(traceService).start(captor.capture());
        assertThat(captor.getValue().triggerSnapshot()).doesNotContainKey("rows");
        assertThat(String.valueOf(captor.getValue().triggerSnapshot().get("phone")))
                .isEqualTo("[REDACTED]");
    }

    private static CompiledFlowPlan plan() {
        return new CompiledFlowPlan("afl_1", "etl_guard", 1, "SHADOW",
                "trigger.jobId", 3600, "arbiter_default",
                List.of(new FlowStep("s1", "CHECK_INVARIANT", List.of(), "target_core",
                        null, false, null, null, null, null, null, Map.of())),
                "{}", "d".repeat(64), Map.of());
    }
}
