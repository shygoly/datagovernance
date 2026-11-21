package com.datagov.metadata.service.cohort;

import com.datagov.common.clinical.CohortSpec;
import com.datagov.common.clinical.CriterionNode;
import com.datagov.metadata.service.cohort.CohortCompiler.CompiledCohort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CohortCompilerTest {

    private final CohortCompiler compiler = new CohortCompiler();

    @Test
    @DisplayName("锚点 + EXISTS 纳入 / NOT_EXISTS 排除 → 确定性只读成员 SQL")
    void compilesExistsAndExclude() {
        CohortSpec spec = new CohortSpec(
                "icu.ae_within_48h",
                "入ICU后48h不良事件",
                new CohortSpec.Anchor("icu.admission", "admit_time"),
                new CohortSpec.RelativeWindow("PT0S", "PT48H"),
                List.of(new CriterionNode("EXISTS", "ae.event", null, "anchor",
                        List.of("PT0S", "PT48H"), List.of(), Map.of())),
                List.of(new CriterionNode("EXISTS", "palliative.flag", null, "anchor",
                        List.of("PT0S", "PT48H"), List.of(), Map.of())),
                "encounter_id",
                false);

        List<EventTableBinding> bindings = List.of(
                new EventTableBinding("icu.admission", "fact_icu_admit", "encounter_id", "admit_time"),
                new EventTableBinding("ae.event", "fact_ae", "encounter_id", "event_time"),
                new EventTableBinding("palliative.flag", "fact_palliative", "encounter_id", "flag_time"));

        CompiledCohort compiled = compiler.compile(spec, bindings);
        assertThat(compiled.sql())
                .startsWith("SELECT DISTINCT a.encounter_id AS member_id FROM fact_icu_admit a")
                .contains("EXISTS (SELECT 1 FROM fact_ae")
                .contains("NOT (EXISTS (SELECT 1 FROM fact_palliative")
                .doesNotContain("DROP")
                .doesNotContain(";");
        assertThat(compiled.digest()).hasSize(64);
        assertThat(compiled.memberGrain()).isEqualTo("encounter_id");
    }

    @Test
    @DisplayName("缺少事件绑定时失败；Criterion 裸 SQL 在校验阶段失败")
    void missingBindingAndSqlRejected() {
        CohortSpec spec = new CohortSpec(
                "c1", "n",
                new CohortSpec.Anchor("icu.admission", "admit_time"),
                new CohortSpec.RelativeWindow("PT0S", "PT48H"),
                List.of(new CriterionNode("EXISTS", "ae.event", null, null, List.of("PT0S", "PT48H"), List.of(), Map.of())),
                List.of(), "encounter_id", false);

        assertThatThrownBy(() -> compiler.compile(spec, List.of(
                new EventTableBinding("icu.admission", "fact_icu_admit", "encounter_id", "admit_time"))))
                .hasMessageContaining("未绑定");

        CohortSpec evil = new CohortSpec(
                "c2", "n",
                new CohortSpec.Anchor("icu.admission", "admit_time"),
                new CohortSpec.RelativeWindow("PT0S", "PT48H"),
                List.of(new CriterionNode("EXISTS", "ae; select 1", null, null, List.of("PT0S", "PT48H"), List.of(), Map.of())),
                List.of(), "encounter_id", false);
        assertThatThrownBy(() -> compiler.compile(evil, List.of()))
                .hasMessageContaining("禁止");
    }
}
