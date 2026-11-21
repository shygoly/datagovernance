package com.datagov.app.autonomy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActionParameterValidatorTest {

    private static final Map<String, Object> ENVELOPE = Map.of(
            "required", List.of("jobId"),
            "properties", Map.of(
                    "jobId", Map.of("type", "string"),
                    "maxPauseMinutes", Map.of("type", "integer", "minimum", 5, "maximum", 1440),
                    "mode", Map.of("type", "string", "enum", List.of("SAFE", "FAST"))));

    @Test
    @DisplayName("必填、数值界与白名单全部通过")
    void acceptsValidParams() {
        var result = ActionParameterValidator.validate(ENVELOPE,
                Map.of("jobId", "job_1", "maxPauseMinutes", 30, "mode", "SAFE"));
        assertThat(result.valid()).isTrue();
    }

    @Test
    @DisplayName("缺必填、越界与白名单外参数被拒绝")
    void rejectsInvalidParams() {
        var missing = ActionParameterValidator.validate(ENVELOPE, Map.of());
        assertThat(missing.valid()).isFalse();
        assertThat(missing.errors()).anyMatch(e -> e.contains("jobId"));

        var outOfRange = ActionParameterValidator.validate(ENVELOPE,
                Map.of("jobId", "job_1", "maxPauseMinutes", 9999));
        assertThat(outOfRange.valid()).isFalse();

        var notAllowed = ActionParameterValidator.validate(ENVELOPE,
                Map.of("jobId", "job_1", "mode", "DANGEROUS"));
        assertThat(notAllowed.valid()).isFalse();
    }
}
