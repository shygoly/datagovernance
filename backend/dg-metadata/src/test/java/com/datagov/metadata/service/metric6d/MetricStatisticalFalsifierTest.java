package com.datagov.metadata.service.metric6d;

import com.datagov.metadata.service.metric6d.MetricStatisticalFalsifier.FalsifyQuery;
import com.datagov.metadata.service.metric6d.MetricStatisticalFalsifier.TableBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetricStatisticalFalsifierTest {

    @Test
    @DisplayName("生成 3 条只读证伪 SQL")
    void buildsThreeReadOnlyQueries() {
        MetricStatisticalFalsifier falsifier = new MetricStatisticalFalsifier();
        List<FalsifyQuery> qs = falsifier.buildQueries(
                new TableBinding("dim_patient", "patient_id", null),
                new TableBinding("fact_encounter", "encounter_id", "patient_id"),
                0.95);
        assertThat(qs).hasSize(3);
        assertThat(qs).extracting(FalsifyQuery::id)
                .containsExactly("PK_UNIQUENESS", "NDV_RATIO_1N", "FK_MATCH_RATE");
        assertThat(qs.get(0).sql()).startsWith("SELECT").doesNotContain(";");
        assertThat(qs.get(2).sql()).contains("LEFT JOIN").contains("fk_match_rate");
    }
}
