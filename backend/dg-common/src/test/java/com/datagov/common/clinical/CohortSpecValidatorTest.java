package com.datagov.common.clinical;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CohortSpecValidatorTest {

    @Test
    @DisplayName("合法 CohortSpec 通过；内嵌 SQL 被拒绝")
    void rejectsRawSql() {
        CohortSpec ok = sample("icu.ae_48h", new CriterionNode(
                "EXISTS", "medication.exposure", null, "anchor",
                List.of("PT0S", "PT72H"), List.of(), Map.of()));
        assertThatCode(() -> CohortSpecValidator.validate(ok)).doesNotThrowAnyException();

        CohortSpec evil = sample("x; drop table t", new CriterionNode(
                "EXISTS", "medication.exposure", null, "anchor",
                List.of("PT0S", "PT72H"), List.of(), Map.of()));
        assertThatThrownBy(() -> CohortSpecValidator.validate(evil))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁止");

        CriterionNode sqlEvent = new CriterionNode(
                "EXISTS", "select * from patients", null, "anchor",
                List.of("PT0S", "PT48H"), List.of(), Map.of());
        assertThatThrownBy(() -> CohortSpecValidator.validate(sample("icu.ok", sqlEvent)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁止");
    }

    private static CohortSpec sample(String code, CriterionNode include) {
        return new CohortSpec(
                code,
                "demo",
                new CohortSpec.Anchor("icu.admission", "admit_time"),
                new CohortSpec.RelativeWindow("PT0S", "PT48H"),
                List.of(include),
                List.of(),
                "encounter_id",
                false);
    }
}
