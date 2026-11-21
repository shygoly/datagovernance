package com.datagov.governance.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShadowEvaluatorTest {

    private final ShadowEvaluator evaluator = new ShadowEvaluator();

    @Test
    @DisplayName("MINIMIZE 指标下降为 IMPROVED")
    void minimizeImproved() {
        var result = evaluator.evaluate(target("MINIMIZE", 300),
                Map.of("freshness", 600), Map.of("freshness", 120));

        assertThat(result.status()).isEqualTo(DecisionTraceService.IMPROVED);
        assertThat(result.objectives()).hasSize(1);
    }

    @Test
    @DisplayName("MAXIMIZE 指标低于阈值判 DEGRADED")
    void maximizeBelowThreshold() {
        var result = evaluator.evaluate(target("MAXIMIZE", 0.99),
                Map.of("passRate", 0.98), Map.of("passRate", 0.90));

        assertThat(result.status()).isEqualTo(DecisionTraceService.DEGRADED);
    }

    @Test
    @DisplayName("缺少 after 指标必须 INCONCLUSIVE")
    void missingAfterIsInconclusive() {
        var result = evaluator.evaluate(target("MAXIMIZE", 0.99),
                Map.of("passRate", 0.98), Map.of());

        assertThat(result.status()).isEqualTo(DecisionTraceService.INCONCLUSIVE);
    }

    @Test
    @DisplayName("变化在噪声阈值内为 NO_EFFECT")
    void noEffect() {
        var result = evaluator.evaluate(target("MAXIMIZE", 0.9),
                Map.of("passRate", 0.95), Map.of("passRate", 0.951));

        assertThat(result.status()).isEqualTo(DecisionTraceService.NO_EFFECT);
    }

    private static Map<String, Object> target(String direction, double threshold) {
        String metric = direction.equals("MINIMIZE") ? "freshness" : "passRate";
        return Map.of("objectives", List.of(Map.of(
                "metricRef", metric,
                "direction", direction,
                "threshold", threshold,
                "weight", 1)));
    }
}
