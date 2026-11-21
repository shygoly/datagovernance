package com.datagov.control.autonomy;

import com.datagov.control.entity.AutonomyControlEntities.TriggerDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TriggerEvaluationServiceTest {

    private final TriggerEvaluationService service = new TriggerEvaluationService(new ObjectMapper());

    @Test
    @DisplayName("ETL 脏数据率达到阈值即命中")
    void etlRateThreshold() {
        TriggerDefinition trigger = trigger("ETL_ANOMALY",
                "{\"badRowRateAtLeast\":0.05,\"statuses\":[\"FAILED_BAD_ROW_LIMIT\"]}", 300);

        var result = service.evaluate(trigger,
                Map.of("status", "SUCCEEDED", "metrics", Map.of("badRowRate", 0.12)),
                Instant.now());

        assertThat(result.fired()).isTrue();
        assertThat(result.reason()).isEqualTo("ETL_ANOMALY");
    }

    @Test
    @DisplayName("ETL 状态命中同样触发，即使脏数据率不高")
    void etlStatusHit() {
        TriggerDefinition trigger = trigger("ETL_ANOMALY",
                "{\"badRowRateAtLeast\":0.5,\"statuses\":[\"FAILED_BAD_ROW_LIMIT\"]}", 0);

        var result = service.evaluate(trigger,
                Map.of("status", "FAILED_BAD_ROW_LIMIT", "metrics", Map.of("badRowRate", 0.01)),
                Instant.now());

        assertThat(result.fired()).isTrue();
    }

    @Test
    @DisplayName("缺指标时明确 INCONCLUSIVE，不把缺失当 0")
    void missingMetricIsInconclusive() {
        TriggerDefinition trigger = trigger("METRIC_THRESHOLD",
                "{\"metricRef\":\"freshness\",\"operator\":\"GT\",\"threshold\":300}", 0);

        var result = service.evaluate(trigger, Map.of(), Instant.now());

        assertThat(result.fired()).isFalse();
        assertThat(result.reason()).isEqualTo("INCONCLUSIVE");
    }

    @Test
    @DisplayName("防抖窗口内重复命中被 DEBOUNCED 抑制")
    void debounceSuppresses() {
        TriggerDefinition trigger = trigger("ETL_ANOMALY",
                "{\"badRowRateAtLeast\":0.05}", 600);
        trigger.setLastFiredAt(Instant.now().minusSeconds(100));

        var result = service.evaluate(trigger,
                Map.of("metrics", Map.of("badRowRate", 0.2)), Instant.now());

        assertThat(result.fired()).isFalse();
        assertThat(result.reason()).isEqualTo("DEBOUNCED");
    }

    @Test
    @DisplayName("质量失败按 failed 布尔命中")
    void qualityFailure() {
        TriggerDefinition trigger = trigger("QUALITY_FAILURE", "{\"failed\":true}", 0);

        var result = service.evaluate(trigger,
                Map.of("metrics", Map.of("failed", true, "passRate", 0.0)), Instant.now());

        assertThat(result.fired()).isTrue();
        assertThat(result.reason()).isEqualTo("QUALITY_FAILURE");
    }

    private static TriggerDefinition trigger(String sourceType, String condition, int debounce) {
        TriggerDefinition trigger = new TriggerDefinition();
        trigger.setId("atr_1");
        trigger.setWorkspaceId("ws1");
        trigger.setCode("t1");
        trigger.setName("t1");
        trigger.setSourceType(sourceType);
        trigger.setConditionJson(condition);
        trigger.setDebounceSec(debounce);
        trigger.setEnabled(true);
        trigger.setFlowId("afl_1");
        return trigger;
    }
}
