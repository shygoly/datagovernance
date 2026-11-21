package com.datagov.metadata.service.metric6d;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FieldProvenanceTest {

    @Test
    @DisplayName("llm_inference 强制 requiresHumanReview，且优先级低于 db_constraint")
    void llmForcesReviewAndPriority() {
        FieldProvenance llm = FieldProvenance.of("llm_inference", null, "guess", "HIGH");
        assertThat(llm.requiresHumanReview()).isTrue();
        assertThat(llm.confidence()).isEqualTo("HIGH");

        FieldProvenance db = FieldProvenance.fromMap(Map.of(
                "source", "db_constraint",
                "ref", "orders_pkey",
                "confidence", "high"));
        assertThat(db.requiresHumanReview()).isFalse();
        assertThat(db.priorityRank()).isGreaterThan(llm.priorityRank());
    }

    @Test
    @DisplayName("ProvenancePolicy 从嵌套 FieldProvenance 检测人审")
    void policyCollectsNested() {
        ProvenancePolicy.Evaluation ev = ProvenancePolicy.evaluate(
                null,
                """
                {"additivity":{"kind":"additive","provenance":{"source":"llm_inference","confidence":"MEDIUM"}}}
                """);
        assertThat(ev.requiresHumanReview()).isTrue();
    }
}
