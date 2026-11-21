package com.datagov.runtime.autonomy;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * T+1 回检观测源 SPI（data-driven-ops-profile P2-1）。
 *
 * <p>引擎在 WAITING_VERIFY 到期时调用所有 Provider，把真实完成事件/指标源
 * 投影成 after-metrics。没有 Provider 或没有观测时返回 empty，由
 * DelayedVerifyHandler 明确落 INCONCLUSIVE（C8），绝不猜测。
 */
public interface FlowVerifyObservationProvider {

    String name();

    Optional<Observation> observe(Context context);

    record Context(
            String workspaceId,
            String instanceId,
            String flowId,
            String flowCode,
            String triggerId,
            String verifyTargetRef,
            Map<String, Object> triggerSnapshot,
            Map<String, Object> beforeMetrics,
            Instant startedAt,
            Instant dueAt
    ) {
        public Context {
            triggerSnapshot = triggerSnapshot == null ? Map.of() : Map.copyOf(triggerSnapshot);
            beforeMetrics = beforeMetrics == null ? Map.of() : Map.copyOf(beforeMetrics);
        }
    }

    record Observation(Map<String, Object> metrics, String source, Map<String, Object> detail) {
        public Observation {
            metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
            detail = detail == null ? Map.of() : Map.copyOf(detail);
        }
    }
}
