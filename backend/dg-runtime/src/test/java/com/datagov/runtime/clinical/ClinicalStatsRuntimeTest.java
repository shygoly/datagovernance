package com.datagov.runtime.clinical;

import com.datagov.common.clinical.ClinicalOperator.OperatorRequest;
import com.datagov.common.clinical.ClinicalOperator.OperatorResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClinicalStatsRuntimeTest {

    private final ClinicalStatsRuntime runtime = new ClinicalStatsRuntime();

    @Test
    @DisplayName("sofa_total_v1 确定性求和并附带 operatorDigest / fingerprint")
    void sofaTotalDeterministic() {
        OperatorResult result = runtime.execute(SofaTotalV1Operator.ID, SofaTotalV1Operator.VERSION,
                new OperatorRequest("ws1", List.of(
                        Map.of("member_id", "e1",
                                "respiration", 2, "coagulation", 1, "liver", 0,
                                "cardiovascular", 1, "cns", 0, "renal", 2),
                        Map.of("member_id", "e2",
                                "respiration", 0, "coagulation", 0, "liver", 0,
                                "cardiovascular", 0, "cns", 0, "renal", 0)
                ), Map.of()));

        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).get("sofa_total")).isEqualTo(6);
        assertThat(result.rows().get(1).get("sofa_total")).isEqualTo(0);
        assertThat(result.operatorDigest()).hasSize(64);
        assertThat(result.fingerprint()).isNotNull();
        result.fingerprint().requireComplete();

        OperatorResult again = runtime.execute(SofaTotalV1Operator.ID, null,
                new OperatorRequest("ws1", List.of(
                        Map.of("member_id", "e1",
                                "respiration", 2, "coagulation", 1, "liver", 0,
                                "cardiovascular", 1, "cns", 0, "renal", 2)
                ), Map.of()));
        assertThat(again.rows().get(0).get("sofa_total")).isEqualTo(6);
        assertThat(again.operatorDigest()).isEqualTo(
                runtime.execute(SofaTotalV1Operator.ID, SofaTotalV1Operator.VERSION,
                        new OperatorRequest("ws1", List.of(
                                Map.of("member_id", "e1",
                                        "respiration", 2, "coagulation", 1, "liver", 0,
                                        "cardiovascular", 1, "cns", 0, "renal", 2)
                        ), Map.of())).operatorDigest());
    }

    @Test
    @DisplayName("未登记算子拒绝；非法分项拒绝")
    void unknownOperatorAndInvalidScore() {
        assertThatThrownBy(() -> runtime.execute("invented_score", "1.0.0",
                new OperatorRequest("ws", List.of(), Map.of())))
                .hasMessageContaining("未登记");

        assertThatThrownBy(() -> runtime.execute(SofaTotalV1Operator.ID, null,
                new OperatorRequest("ws", List.of(Map.of("member_id", "e1", "respiration", 9)), Map.of())))
                .hasMessageContaining("0..4");
    }
}
