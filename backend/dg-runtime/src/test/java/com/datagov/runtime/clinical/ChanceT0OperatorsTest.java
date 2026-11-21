package com.datagov.runtime.clinical;

import com.datagov.common.clinical.ClinicalOperator.OperatorRequest;
import com.datagov.common.clinical.ClinicalOperator.OperatorResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChanceT0OperatorsTest {

    @Test
    @DisplayName("多程切分按时间赋予 session_seq")
    void splitAssignsSessionSeq() {
        var op = new SplitTreatmentCyclesV1Operator();
        OperatorResult r = op.execute(new OperatorRequest("ws", List.of(
                Map.of("patient_ref", "synthea:SYN-P-001", "anchor_id", "a2",
                        "anchor_ts", "2023-06-01T08:00:00Z"),
                Map.of("patient_ref", "synthea:SYN-P-001", "anchor_id", "a1",
                        "anchor_ts", "2023-01-10T08:00:00Z")
        ), Map.of()));
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().get(0).get("session_seq")).isEqualTo(1);
        assertThat(r.rows().get(0).get("anchor_id")).isEqualTo("a1");
        assertThat(r.rows().get(1).get("session_seq")).isEqualTo(2);
    }

    @Test
    @DisplayName("对齐：随访落入窗口 MATCHED，串程候选不匹配错误锚点")
    void alignMatchesFollowupWindow() {
        var split = new SplitTreatmentCyclesV1Operator();
        OperatorResult cycles = split.execute(new OperatorRequest("ws", List.of(
                Map.of("patient_ref", "p1", "anchor_id", "t1", "anchor_ts", "2023-01-10T00:00:00Z"),
                Map.of("patient_ref", "p1", "anchor_id", "t2", "anchor_ts", "2023-04-20T00:00:00Z")
        ), Map.of()));

        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Map<String, Object> a : cycles.rows()) {
            Map<String, Object> r = new java.util.LinkedHashMap<>(a);
            r.put("row_kind", "ANCHOR");
            rows.add(r);
        }
        rows.add(Map.of(
                "row_kind", "CANDIDATE",
                "patient_ref", "p1",
                "event_id", "fup1",
                "role", "FOLLOWUP",
                "event_ts", "2023-02-15T00:00:00Z")); // ~36d after t1
        rows.add(Map.of(
                "row_kind", "CANDIDATE",
                "patient_ref", "p1",
                "event_id", "fup2",
                "role", "FOLLOWUP",
                "event_ts", "2023-05-25T00:00:00Z")); // ~35d after t2

        var align = new AlignToAnchorV1Operator();
        OperatorResult out = align.execute(new OperatorRequest("ws", rows, Map.of(
                "windows", Map.of("FOLLOWUP", Map.of("startDays", 28, "endDays", 56)))));

        long matched = out.rows().stream().filter(r -> "MATCHED".equals(r.get("status"))).count();
        assertThat(matched).isEqualTo(2);
        Map<String, Object> m1 = out.rows().stream()
                .filter(r -> "t1".equals(r.get("anchor_id")) && "MATCHED".equals(r.get("status")))
                .findFirst().orElseThrow();
        assertThat(m1.get("candidate_ref")).isEqualTo("fup1");
        Map<String, Object> m2 = out.rows().stream()
                .filter(r -> "t2".equals(r.get("anchor_id")) && "MATCHED".equals(r.get("status")))
                .findFirst().orElseThrow();
        assertThat(m2.get("candidate_ref")).isEqualTo("fup2");
    }

    @Test
    @DisplayName("CNLC 与 PVTT 冲突 / 时序倒挂 / 单位混用")
    void hccQcRules() {
        var op = new HccClinicalQcV1Operator();
        OperatorResult r = op.execute(new OperatorRequest("ws", List.of(
                Map.of(
                        "patient_ref", "p1",
                        "cnlc_stage", "IIb",
                        "pvtt_grade", "Vp3",
                        "extrahepatic_mets", false,
                        "tumor_count", 2,
                        "max_diameter_cm", 48,
                        "diameter_unit", "cm",
                        "t0", "2023-01-10",
                        "visit_date", "2022-12-01"
                )
        ), Map.of()));
        assertThat(r.rows().stream().map(x -> x.get("rule_id")))
                .contains("CNLC_CONFLICT", "TEMPORAL_FOLLOWUP_BEFORE_T0", "UNIT_DIAMETER_MIXED");
    }
}
