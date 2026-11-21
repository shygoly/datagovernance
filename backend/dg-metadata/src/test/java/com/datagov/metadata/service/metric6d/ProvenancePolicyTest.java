package com.datagov.metadata.service.metric6d;

import com.datagov.metadata.service.metric6d.ProvenancePolicy.Evaluation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProvenancePolicyTest {

    @Test
    @DisplayName("llm_inference 触发人审；安全字段拦截")
    void llmInferenceAndSecurity() {
        Evaluation ev = ProvenancePolicy.evaluate(
                "{\"fields\":[{\"source\":\"llm_inference\"}]}",
                "{\"additivity\":{\"kind\":\"additive\"}}");
        assertThat(ev.requiresHumanReview()).isTrue();
        assertThat(ev.blockedFields()).isEmpty();

        assertThatThrownBy(() -> ProvenancePolicy.assertWritableByLlm(
                "{\"security\":{\"rls\":\"tenant_id = 1\"}}"))
                .isInstanceOf(SemanticGraphGateException.class)
                .hasMessageContaining("安全");
    }
}
