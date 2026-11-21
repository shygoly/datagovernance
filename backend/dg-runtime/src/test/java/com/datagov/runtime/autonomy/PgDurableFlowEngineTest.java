package com.datagov.runtime.autonomy;

import com.datagov.common.tenant.Caller;
import com.datagov.runtime.entity.AutonomyRuntimeEntities.FlowInstance;
import com.datagov.runtime.mapper.AuFlowInstanceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PgDurableFlowEngineTest {

    private AuFlowInstanceMapper mapper;
    private EntityLockService locks;
    private Map<String, FlowInstance> stored;
    private ObjectMapper json;
    private PgDurableFlowEngine engine;

    @BeforeEach
    void setUp() {
        mapper = mock(AuFlowInstanceMapper.class);
        locks = mock(EntityLockService.class);
        stored = new HashMap<>();
        json = new ObjectMapper();
        when(mapper.insert(any(FlowInstance.class))).thenAnswer(inv -> {
            FlowInstance instance = inv.getArgument(0);
            stored.put(instance.getId(), instance);
            return 1;
        });
        when(mapper.updateById(any(FlowInstance.class))).thenAnswer(inv -> {
            FlowInstance instance = inv.getArgument(0);
            stored.put(instance.getId(), instance);
            return 1;
        });
        when(mapper.selectById(anyString())).thenAnswer(inv -> stored.get(inv.getArgument(0)));
        when(locks.tryAcquire(anyString(), anyString(), anyString(), any())).thenReturn(true);

        List<FlowStepHandler> handlers = List.of(
                handler("INVOKE_ACTIVITY",
                        ctx -> StepResult.next(Map.of("done", true), Map.of("done", true)),
                        ctx -> StepResult.next(), ctx -> Map.of("compensated", true)),
                handler("WAIT",
                        ctx -> StepResult.waitApproval(Map.of("proposalId", "agpr_1"), "wait"),
                        ctx -> StepResult.next(), ctx -> Map.of()),
                handler("FAIL",
                        ctx -> StepResult.failed("boom"), ctx -> StepResult.failed("boom"),
                        ctx -> Map.of()));
        engine = new PgDurableFlowEngine(mapper, locks,
                new FlowStepHandlerRegistry(handlers), json, List.of());
    }

    @Test
    @DisplayName("等待审批的实例在信号后从 checkpoint 继续到完成")
    void waitsForApprovalThenCompletes() {
        String id = engine.startInstance(plan("WAIT"),
                new FlowTriggerContext("ws1", "atr_1", "job_1",
                        Map.of("traceId", "atrc_1", "entityKey", "job_1"), Map.of(), 100, List.of()),
                actor());

        assertThat(engine.inspect(id).status()).isEqualTo("WAITING_APPROVAL");
        assertThat(engine.inspect(id).currentStepId()).isEqualTo("s2");

        engine.sendSignal(id, "APPROVED", Map.of("proposalId", "agpr_1"), actor());

        assertThat(engine.inspect(id).status()).isEqualTo("COMPLETED");
        assertThat(engine.inspect(id).history()).isNotEmpty();
    }

    @Test
    @DisplayName("失败时逆序补偿已执行的 INVOKE_ACTIVITY")
    void compensatesOnFailure() {
        AtomicBoolean compensated = new AtomicBoolean(false);
        List<FlowStepHandler> handlers = List.of(
                new FlowStepHandler() {
                    @Override
                    public String kind() {
                        return "INVOKE_ACTIVITY";
                    }

                    @Override
                    public StepResult execute(FlowStepContext context) {
                        return StepResult.next(Map.of("x", 1), Map.of("x", 1));
                    }

                    @Override
                    public Map<String, Object> compensate(FlowStepContext context) {
                        compensated.set(true);
                        return Map.of("compensated", true);
                    }
                },
                new FlowStepHandler() {
                    @Override
                    public String kind() {
                        return "FAIL";
                    }

                    @Override
                    public StepResult execute(FlowStepContext context) {
                        return StepResult.failed("boom");
                    }
                });
        PgDurableFlowEngine failingEngine = new PgDurableFlowEngine(mapper, locks,
                new FlowStepHandlerRegistry(handlers), json, List.of());

        String id = failingEngine.startInstance(plan("FAIL"),
                new FlowTriggerContext("ws1", "atr_1", "job_1",
                        Map.of("traceId", "atrc_2", "entityKey", "job_1"), Map.of(), 100, List.of()),
                actor());

        assertThat(failingEngine.inspect(id).status()).isEqualTo("FAILED");
        assertThat(compensated).isTrue();
    }

    private static FlowStepHandler handler(
            String kind,
            java.util.function.Function<FlowStepContext, StepResult> execute,
            java.util.function.Function<FlowStepContext, StepResult> resume,
            java.util.function.Function<FlowStepContext, Map<String, Object>> compensate) {
        return new FlowStepHandler() {
            @Override
            public String kind() {
                return kind;
            }

            @Override
            public StepResult execute(FlowStepContext context) {
                return execute.apply(context);
            }

            @Override
            public StepResult resume(FlowStepContext context) {
                return resume.apply(context);
            }

            @Override
            public Map<String, Object> compensate(FlowStepContext context) {
                return compensate.apply(context);
            }
        };
    }

    private CompiledFlowPlan plan(String middleKind) {
        List<FlowStep> steps = new ArrayList<>();
        steps.add(new FlowStep("s1", "INVOKE_ACTIVITY", List.of(), null, "action_pause",
                false, null, null, "action_resume", null, null, Map.of()));
        steps.add(new FlowStep("s2", middleKind, List.of(), null, null,
                false, 3600, null, null, null, null, Map.of()));
        steps.add(new FlowStep("s3", "INVOKE_ACTIVITY", List.of(), null, "action_pause",
                false, null, null, "action_resume", null, null, Map.of()));
        return new CompiledFlowPlan("afl_1", "guard", 1, "L2_ASSISTED",
                "trigger.jobId", 3600, "arbiter_default", steps, "{}", "d".repeat(64), Map.of());
    }

    private static Caller actor() {
        return new Caller("u1", "tester", "ws1", false, Set.of());
    }
}
