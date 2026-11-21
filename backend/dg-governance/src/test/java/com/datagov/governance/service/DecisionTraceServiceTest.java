package com.datagov.governance.service;

import com.datagov.governance.entity.AutonomyTraceEntities.DecisionTrace;
import com.datagov.governance.mapper.AuDecisionTraceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecisionTraceServiceTest {

    private AuDecisionTraceMapper mapper;
    private Map<String, DecisionTrace> stored;
    private DecisionTraceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AuDecisionTraceMapper.class);
        stored = new HashMap<>();
        when(mapper.insert(any(DecisionTrace.class))).thenAnswer(inv -> {
            DecisionTrace trace = inv.getArgument(0);
            stored.put(trace.getId(), trace);
            return 1;
        });
        when(mapper.updateById(any(DecisionTrace.class))).thenAnswer(inv -> {
            DecisionTrace trace = inv.getArgument(0);
            stored.put(trace.getId(), trace);
            return 1;
        });
        when(mapper.selectOne(any())).thenAnswer(inv -> {
            DecisionTrace trace = stored.values().stream().findFirst().orElse(null);
            return trace;
        });
        service = new DecisionTraceService(mapper, new ObjectMapper());
    }

    @Test
    @DisplayName("启动 trace 后绑定实例并记录裁决/回检，指纹始终存在")
    void recordsDecisionChain() {
        String traceId = service.start(new DecisionTraceService.StartCommand(
                "ws1", "afl_1", "atr_1", "SHADOW",
                Map.of("entityKey", "job_1", "rows", 100), "d".repeat(64), "u1"));
        service.bindInstance("ws1", traceId, "afi_1");
        service.updateArbiter("ws1", traceId, Map.of("status", "APPROVED"));
        service.updateDryRun("ws1", traceId, Map.of("ready", true));
        service.updateProposal("ws1", traceId, "agpr_1");
        service.updateActivity("ws1", traceId, Map.of("executed", false));
        service.updateVerify("ws1", traceId, DecisionTraceService.INCONCLUSIVE,
                Map.of("reason", "missing T+1"));

        var view = service.getByTrace("ws1", traceId);
        assertThat(view.traceId()).isEqualTo(traceId);
        assertThat(view.instanceId()).isEqualTo("afi_1");
        assertThat(view.arbiterDecision()).containsEntry("status", "APPROVED");
        assertThat(view.dryRunResult()).containsEntry("ready", true);
        assertThat(view.proposalId()).isEqualTo("agpr_1");
        assertThat(view.verifyStatus()).isEqualTo(DecisionTraceService.INCONCLUSIVE);
        assertThat(view.reproducibilityFingerprint()).isNotEmpty();
    }
}
