package com.datagov.app.service;

import com.datagov.common.audit.ReproducibilityFingerprint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CHANCE 专病三看板聚合（消费 synthea_hcc 专病表；不镜像 MIMIC 通用表）。
 */
@Service
public class ChanceAnalyticsService {

    @Value("${dg.chance.synthea.jdbc-url:jdbc:postgresql://127.0.0.1:5432/synthea}")
    private String jdbcUrl;

    @Value("${dg.chance.synthea.user:mac}")
    private String jdbcUser;

    @Value("${dg.chance.synthea.password:}")
    private String jdbcPassword;

    public record BoardResponse(
            Map<String, Object> data,
            List<String> warnings,
            ReproducibilityFingerprint reproducibilityFingerprint
    ) {
    }

    public BoardResponse cohortBaseline() {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> data = new LinkedHashMap<>();
        try (Connection c = open(); Statement st = c.createStatement()) {
            List<Map<String, Object>> funnel = List.of(
                    Map.of("label", "合成队列登记", "count", scalar(st, "SELECT count(*) FROM synthea_hcc.cohort_patient")),
                    Map.of("label", "有 HCC 诊断", "count", scalar(st, "SELECT count(*) FROM synthea_hcc.hcc_diagnosis")),
                    Map.of("label", "至少 1 次介入", "count",
                            scalar(st, "SELECT count(DISTINCT patient_id) FROM synthea_hcc.tace_session")),
                    Map.of("label", "有随访评估", "count",
                            scalar(st, "SELECT count(DISTINCT patient_id) FROM synthea_hcc.followup_mrecist"))
            );
            data.put("funnel", funnel);
            data.put("cnlcDistribution", groupCount(st,
                    "SELECT hcc_cnlc_stage AS k, count(*) AS c FROM synthea_hcc.hcc_diagnosis GROUP BY 1 ORDER BY 2 DESC"));
            data.put("childPughDistribution", groupCount(st,
                    "SELECT hcc_child_pugh_class AS k, count(*) AS c FROM synthea_hcc.hcc_diagnosis GROUP BY 1"));
            data.put("etiologyDistribution", groupCount(st,
                    "SELECT hcc_etiology AS k, count(*) AS c FROM synthea_hcc.hcc_diagnosis GROUP BY 1"));
        } catch (Exception e) {
            warnings.add("UNALIGNED_OR_UNREACHABLE: " + e.getMessage());
            data.put("funnel", List.of());
        }
        return board(data, warnings, "cohort-baseline");
    }

    public BoardResponse treatmentPathway() {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> data = new LinkedHashMap<>();
        try (Connection c = open(); Statement st = c.createStatement()) {
            data.put("modalityShare", groupCount(st,
                    "SELECT modality AS k, count(*) AS c FROM synthea_hcc.tace_session GROUP BY 1"));
            data.put("regimenShare", groupCount(st,
                    "SELECT COALESCE(systemic_regimen,'None') AS k, count(*) AS c FROM synthea_hcc.tace_session GROUP BY 1"));
            // Sankey 边：session_seq -> modality（按程次）
            List<Map<String, Object>> links = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT session_seq, modality, count(*) AS c FROM synthea_hcc.tace_session GROUP BY 1,2 ORDER BY 1,2")) {
                while (rs.next()) {
                    links.add(Map.of(
                            "source", "seq_" + rs.getInt(1),
                            "target", rs.getString(2),
                            "value", rs.getInt(3)));
                }
            }
            data.put("sankeyLinks", links);
            data.put("note", "程次节点以 session_seq 区分，避免多程串归因");
        } catch (Exception e) {
            warnings.add("UNALIGNED_OR_UNREACHABLE: " + e.getMessage());
        }
        return board(data, warnings, "treatment-pathway");
    }

    public BoardResponse survival(String endpoint) {
        String ep = endpoint == null || endpoint.isBlank() ? "OS" : endpoint.trim().toUpperCase();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("endpoint", ep);
        try (Connection c = open(); Statement st = c.createStatement()) {
            int alive = scalar(st, "SELECT count(*) FROM synthea_hcc.cohort_patient WHERE survival_status='Alive'");
            int dead = scalar(st, "SELECT count(*) FROM synthea_hcc.cohort_patient WHERE survival_status='Dead'");
            int lost = scalar(st, "SELECT count(*) FROM synthea_hcc.cohort_patient WHERE survival_status='Lost_to_FollowUp'");
            data.put("riskSet", Map.of("alive", alive, "dead", dead, "lost", lost, "n", alive + dead + lost));
            // 简化 KM 阶梯：按月粗分（MVP；完整寿命表可接 analysis-hardening）
            List<Map<String, Object>> steps = new ArrayList<>();
            double surv = 1.0;
            int atRisk = alive + dead + lost;
            for (int m = 0; m <= 24; m += 3) {
                steps.add(Map.of("timeMonths", m, "survival", round(surv), "atRisk", Math.max(atRisk, 0)));
                if (dead > 0 && atRisk > 0) {
                    double hazard = Math.min(0.08, dead / (double) Math.max(atRisk, 1) / 8.0);
                    surv = Math.max(0.0, surv * (1.0 - hazard));
                    atRisk = (int) Math.round(atRisk * (1.0 - hazard * 0.5));
                }
            }
            data.put("kmSteps", steps);
            data.put("orr", groupCount(st,
                    "SELECT mrecist_response AS k, count(*) AS c FROM synthea_hcc.followup_mrecist GROUP BY 1"));
            warnings.add("KM_MVP: 阶梯为确定性粗粒度近似，非完整寿命表；正式科研请接 ClinicalStatsRuntime 生存算子");
        } catch (Exception e) {
            warnings.add("UNALIGNED_OR_UNREACHABLE: " + e.getMessage());
        }
        return board(data, warnings, "survival-" + ep);
    }

    private BoardResponse board(Map<String, Object> data, List<String> warnings, String gate) {
        String digest = Integer.toHexString(data.hashCode());
        ReproducibilityFingerprint fp = new ReproducibilityFingerprint(
                1, List.of(Map.of("datasource", "synthea", "schema", "synthea_hcc")),
                List.of(), List.of(Map.of("standardId", "STD_CHANCE_HCC_QC_V1", "version", "1.0.0")),
                List.of(Map.of("operatorId", "chance_board_v1", "digest", digest)),
                digest, gate, List.of(), Map.of("truncated", false, "maxRows", 10000),
                Instant.now().toString());
        return new BoardResponse(data, List.copyOf(warnings), fp);
    }

    private Connection open() throws Exception {
        if (jdbcPassword == null || jdbcPassword.isBlank()) {
            return DriverManager.getConnection(jdbcUrl, jdbcUser, (String) null);
        }
        return DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
    }

    private static int scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static List<Map<String, Object>> groupCount(Statement st, String sql) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(Map.of("key", String.valueOf(rs.getObject(1)), "count", rs.getInt(2)));
            }
        }
        return rows;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
