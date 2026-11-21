package com.datagov.app.autonomy;

import com.datagov.common.tenant.Caller;
import com.datagov.control.autonomy.ActionRateLimitService;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.runtime.autonomy.CompiledFlowPlan;
import com.datagov.runtime.autonomy.FlowActivityRegistry;
import com.datagov.runtime.autonomy.FlowStep;
import com.datagov.runtime.autonomy.FlowStepContext;
import com.datagov.runtime.autonomy.StepOutcome;
import com.datagov.runtime.autonomy.StepResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvokeActivityHandlerTest {

    @Test
    @DisplayName("maxExecutionsPerDay 超限时拒绝执行副作用 Activity")
    void rejectsWhenDailyCapExceeded() {
        AutonomyLookup lookup = mock(AutonomyLookup.class);
        FlowActivityRegistry activities = mock(FlowActivityRegistry.class);
        ActionRateLimitService rateLimits = mock(ActionRateLimitService.class);
        SemanticObject action = new SemanticObject();
        action.setId("sem_action");
        action.setCode("action_pause");
        action.setSpecJson("{}");
        when(lookup.requirePublished(anyString(), anyString(), anyString())).thenReturn(action);
        when(lookup.spec(any())).thenReturn(Map.of(
                "actionCode", "PAUSE_SYNC_JOB",
                "authLevel", "L1_AUTO",
                "paramEnvelope", Map.of("maxExecutionsPerDay", 1)));
        when(lookup.activityRef(any())).thenReturn("control.job.pause");
        when(lookup.actionCode(any())).thenReturn("PAUSE_SYNC_JOB");
        when(rateLimits.tryConsume("ws1", "PAUSE_SYNC_JOB", 1)).thenReturn(false);

        InvokeActivityHandler handler = new InvokeActivityHandler(lookup, activities, rateLimits);
        StepResult result = handler.execute(context());

        assertThat(result.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(result.reason()).contains("每日执行上限");
        verify(activities, never()).require(anyString());
    }

    private static FlowStepContext context() {
        FlowStep step = new FlowStep("s6", "INVOKE_ACTIVITY", List.of(), null,
                "action_pause", false, null, null, "action_resume", null, null, Map.of());
        CompiledFlowPlan plan = new CompiledFlowPlan("afl_1", "guard", 1, "L1_AUTO",
                "trigger.jobId", 60, "arbiter", List.of(step), "{}", "d".repeat(64), Map.of());
        return new FlowStepContext("afi_1", "ws1", plan, step,
                Map.of("triggerSnapshot", Map.of("jobId", "job_1"),
                        "actionParams", Map.of("jobId", "job_1")),
                Map.of("jobId", "job_1"), Map.of(), "L1_AUTO",
                new Caller("u1", "tester", "ws1", false, Set.of("autonomy:flow:approve")), null);
    }
}
