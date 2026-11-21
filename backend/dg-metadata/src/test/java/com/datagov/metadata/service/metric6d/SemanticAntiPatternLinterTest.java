package com.datagov.metadata.service.metric6d;

import com.datagov.metadata.service.metric6d.Metric6DSpec.Additivity;
import com.datagov.metadata.service.metric6d.Metric6DSpec.Composition;
import com.datagov.metadata.service.metric6d.SemanticAntiPatternLinter.Finding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticAntiPatternLinterTest {

    private final SemanticAntiPatternLinter linter = new SemanticAntiPatternLinter();

    @Test
    @DisplayName("AP002/AP006 命中；干净 Spec 通过")
    void detectsAndPasses() {
        Metric6DSpec badRatio = new Metric6DSpec(
                new Additivity("additive", List.of(), List.of(), null),
                null, null, null,
                new Composition("ratio", "amount / qty", Map.of()),
                null);
        List<Finding> findings = linter.lint(badRatio, Map.of(), null);
        assertThat(findings).extracting(Finding::code)
                .contains("AP002_RATIO_MISSING_NULLIF", "AP006_ADDITIVE_ON_NON_ADDITIVE_OP");

        Metric6DSpec clean = new Metric6DSpec(
                new Additivity("additive", List.of(), List.of(), null),
                null, null, null,
                new Composition("atomic", "SUM(amount)", Map.of()),
                null);
        assertThat(linter.lint(clean, Map.of(), null)).isEmpty();
        linter.assertClean(clean, Map.of(), null);

        assertThatThrownBy(() -> linter.assertClean(badRatio, Map.of(), null))
                .isInstanceOf(SemanticGraphGateException.class);
    }
}
