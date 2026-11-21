package com.datagov.control.autonomy;

import com.datagov.control.entity.AutonomyControlEntities.TriggerDefinition;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.mapper.SemanticObjectMapper;
import com.datagov.runtime.autonomy.CompiledFlowPlan;
import com.datagov.runtime.autonomy.EntityLockService;
import com.datagov.runtime.autonomy.FlowStep;
import com.datagov.runtime.autonomy.FlowTriggerContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArbiterServiceTest {

    private SemanticObjectMapper mapper;
    private EntityLockService locks;
    private TriggerDefinitionService triggerService;
    private ArbiterService arbiter;
    private SemanticObject target;

    @BeforeEach
    void setUp() {
        mapper = mock(SemanticObjectMapper.class);
        locks = mock(EntityLockService.class);
        triggerService = mock(TriggerDefinitionService.class);
        arbiter = new ArbiterService(mapper, locks, new ObjectMapper(), triggerService);
        target = new SemanticObject();
        target.setId("sem_target");
        target.setCode("target_core");
        target.setObjectType("TARGET");
        target.setStatus("PUBLISHED");
        target.setSpecJson("""
                {"targetEntityRef":"entity_sync","invariants":[
                  {"code":"INV_FRESH","metricRef":"freshness","operator":"LTE","threshold":300,"severity":"HARD_BLOCK"}
                ]}
                """);
        when(mapper.selectOne(any())).thenReturn(target);
        when(locks.check(anyString(), anyString()))
                .thenReturn(new EntityLockService.LockState(false, null, null));
    }

    @Test
    @DisplayName("硬不变量满足时放行")
    void approvesWhenInvariantPasses() {
        var decision = arbiter.decide("ws1", plan(), ctx(Map.of("freshness", 120), List.of()));

        assertThat(decision.status()).isEqualTo(ArbiterService.APPROVED);
        assertThat(decision.invariants()).extracting("status").containsExactly("PASS");
    }

    @Test
    @DisplayName("硬不变量违反时一票否决")
    void rejectsWhenInvariantViolated() {
        var decision = arbiter.decide("ws1", plan(), ctx(Map.of("freshness", 999), List.of()));

        assertThat(decision.status()).isEqualTo(ArbiterService.REJECTED);
        assertThat(decision.reasonCode()).isEqualTo("INVARIANT_VIOLATED");
    }

    @Test
    @DisplayName("指标缺失按 C8 视为 INCONCLUSIVE 并否决")
    void rejectsInconclusive() {
        var decision = arbiter.decide("ws1", plan(), ctx(Map.of(), List.of()));

        assertThat(decision.status()).isEqualTo(ArbiterService.REJECTED);
        assertThat(decision.invariants()).extracting("status").containsExactly("INCONCLUSIVE");
    }

    @Test
    @DisplayName("实体在途锁/冷却期抑制")
    void suppressesWhenLocked() {
        when(locks.check(anyString(), anyString()))
                .thenReturn(new EntityLockService.LockState(true, "afi_other", null));

        var decision = arbiter.decide("ws1", plan(), ctx(Map.of("freshness", 120), List.of()));

        assertThat(decision.status()).isEqualTo(ArbiterService.SUPPRESSED);
        assertThat(decision.reasonCode()).isEqualTo("IN_FLIGHT_OR_COOLDOWN");
    }

    @Test
    @DisplayName("多策略冲突且无规则覆盖时升级 L2")
    void escalatesConflictToL2() {
        var decision = arbiter.decide("ws1", plan(),
                ctx(Map.of("freshness", 120), List.of("other_flow")));

        assertThat(decision.status()).isEqualTo(ArbiterService.ESCALATE_L2);
        assertThat(decision.requireL2()).isTrue();
    }


    @Test
    @DisplayName("无 fallback 时按 priority 确定性裁决，低优先级被抑制")
    void lowerPriorityIsSuppressed() {
        TriggerDefinition current = new TriggerDefinition();
        current.setId("atr_1");
        current.setCode("current");
        current.setSourceType("ETL_ANOMALY");
        current.setPriority(100);
        TriggerDefinition better = new TriggerDefinition();
        better.setId("atr_2");
        better.setCode("better");
        better.setSourceType("ETL_ANOMALY");
        better.setPriority(10);
        when(triggerService.require("ws1", "atr_1")).thenReturn(current);
        when(triggerService.listEnabledBySource("ws1", "ETL_ANOMALY"))
                .thenReturn(List.of(current, better));

        var decision = arbiter.decide("ws1", plan(false),
                ctx(Map.of("freshness", 120), List.of("other_flow")));

        assertThat(decision.status()).isEqualTo(ArbiterService.SUPPRESSED);
        assertThat(decision.reasonCode()).isEqualTo("LOWER_PRIORITY");
    }

    @Test
    @DisplayName("引擎持有自己的锁时再次裁决不会误判冲突")
    void ownLockIsNotConflict() {
        when(locks.check(anyString(), anyString()))
                .thenReturn(new EntityLockService.LockState(true, "afi_self", null));

        var decision = arbiter.decide("ws1", plan(),
                ctx(Map.of("freshness", 120), List.of()), "afi_self");

        assertThat(decision.status()).isEqualTo(ArbiterService.APPROVED);
    }

    private static CompiledFlowPlan plan() {
        return plan(true);
    }

    private static CompiledFlowPlan plan(boolean fallbackToL2) {
        return new CompiledFlowPlan("afl_1", "guard", 1, "SHADOW",
                "trigger.jobId", 3600, "arbiter_default",
                List.of(
                        new FlowStep("s1", "CHECK_INVARIANT", List.of(), "target_core",
                                null, false, null, null, null, null, null, Map.of()),
                        new FlowStep("s2", "ARBITER_DECIDE", List.of(), null,
                                null, fallbackToL2, null, null, null, null, null, Map.of())),
                "{}", "d".repeat(64), Map.of());
    }

    private static FlowTriggerContext ctx(Map<String, Object> metrics, List<String> competing) {
        return new FlowTriggerContext("ws1", "atr_1", "job_1",
                Map.of("entityKey", "job_1"), metrics, 100, competing);
    }
}
