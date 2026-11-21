package com.datagov.runtime.clinical;

import com.datagov.common.audit.ReproducibilityFingerprint;
import com.datagov.common.clinical.ClinicalOperator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 肝癌专病确定性质控（STD_CHANCE_HCC_QC_V1）：CNLC 自洽、时序倒挂、径线单位混用。
 *
 * <p>规则以可插拔 Standard 编码引用；算子内嵌最小推导表，避免硬编码物理表名。
 */
public final class HccClinicalQcV1Operator implements ClinicalOperator {

    public static final String ID = "hcc_clinical_qc_v1";
    public static final String VERSION = "1.0.0";
    public static final String STANDARD_REF = "STD_CHANCE_HCC_QC_V1";

    private static final Set<String> PVTT = Set.of("Vp1", "Vp2", "Vp3", "Vp4");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "required", List.of("patient_ref"));
    }

    @Override
    public Map<String, Object> outputSchema() {
        return Map.of("type", "object", "required", List.of("rule_id", "severity", "status", "message"));
    }

    @Override
    public OperatorResult execute(OperatorRequest request) {
        List<Map<String, Object>> findings = new ArrayList<>();
        StringBuilder digest = new StringBuilder(ID).append('@').append(VERSION).append('#').append(STANDARD_REF);

        for (Map<String, Object> row : request.rows()) {
            String patient = str(row.get("patient_ref"));
            checkCnlc(row, patient, findings, digest);
            checkTemporal(row, patient, findings, digest);
            checkDiameterUnit(row, patient, findings, digest);
        }

        String opDigest = sha256(digest.toString());
        ReproducibilityFingerprint fp = new ReproducibilityFingerprint(
                1, List.of(), List.of(),
                List.of(Map.of("standardId", STANDARD_REF, "version", "1.0.0")),
                List.of(Map.of("operatorId", ID, "version", VERSION, "digest", opDigest)),
                opDigest, "hcc-qc-v1", List.of(),
                Map.of("truncated", false, "maxRows", findings.size()),
                Instant.now().toString());
        return new OperatorResult(ID, VERSION, opDigest, findings, fp);
    }

    private static void checkCnlc(Map<String, Object> row, String patient,
                                  List<Map<String, Object>> findings, StringBuilder digest) {
        String stage = str(row.get("cnlc_stage")).toUpperCase(Locale.ROOT);
        if (stage.isBlank()) {
            return;
        }
        String pvtt = str(row.get("pvtt_grade"));
        boolean ehm = bool(row.get("extrahepatic_mets"));
        String cp = str(row.get("child_pugh")).toUpperCase(Locale.ROOT);
        int tumors = intVal(row.get("tumor_count"), -1);

        String expected = null;
        if ("C".equals(cp)) {
            expected = "IV";
        } else if (ehm) {
            expected = "IIIB";
        } else if (PVTT.contains(pvtt)) {
            expected = "IIIA";
        } else if (tumors >= 4) {
            expected = "IIB";
        }

        if (expected != null && !normalizeStage(stage).equalsIgnoreCase(normalizeStage(expected))) {
            findings.add(finding(patient, row, "CNLC_CONFLICT", "Blocker",
                    "CNLC 分期 " + stage + " 与影像/肝功能证据冲突，推导期望 " + expected));
            digest.append("|CNLC:").append(patient).append('=').append(stage).append("->").append(expected);
        }
    }

    private static void checkTemporal(Map<String, Object> row, String patient,
                                      List<Map<String, Object>> findings, StringBuilder digest) {
        LocalDate t0 = parseDate(row.get("t0"));
        LocalDate visit = parseDate(row.get("visit_date"));
        LocalDate death = parseDate(row.get("death_date"));
        LocalDate discharge = parseDate(row.get("last_discharge_date"));

        if (t0 != null && visit != null && visit.isBefore(t0)) {
            findings.add(finding(patient, row, "TEMPORAL_FOLLOWUP_BEFORE_T0", "Blocker",
                    "随访日期 " + visit + " 早于锚点 T0 " + t0));
            digest.append("|T_FUP:").append(patient);
        }
        if (death != null && discharge != null && death.isBefore(discharge)) {
            findings.add(finding(patient, row, "TEMPORAL_DEATH_BEFORE_DISCHARGE", "Blocker",
                    "死亡日期 " + death + " 早于末次出院 " + discharge));
            digest.append("|T_DEATH:").append(patient);
        }
    }

    private static void checkDiameterUnit(Map<String, Object> row, String patient,
                                          List<Map<String, Object>> findings, StringBuilder digest) {
        String unit = str(row.get("diameter_unit")).toLowerCase(Locale.ROOT);
        Double diameter = dbl(row.get("max_diameter_cm"));
        if (diameter == null) {
            return;
        }
        // 显式 mm，或数值像 mm（>20）却声称/默认 cm
        if ("mm".equals(unit) || diameter > 20.0) {
            findings.add(finding(patient, row, "UNIT_DIAMETER_MIXED", "Warning",
                    "肿瘤径线疑似 mm/cm 混用：value=" + diameter + " unit=" + (unit.isBlank() ? "?" : unit)));
            digest.append("|UNIT:").append(patient).append('=').append(diameter);
        }
    }

    private static Map<String, Object> finding(String patient, Map<String, Object> row,
                                               String ruleId, String severity, String message) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("patient_ref", patient);
        f.put("anchor_ref", row.get("anchor_id"));
        f.put("session_seq", row.get("session_seq"));
        f.put("rule_id", ruleId);
        f.put("severity", severity);
        f.put("status", "OPEN");
        f.put("message", message);
        f.put("ruleset_ref", STANDARD_REF);
        f.put("evidence", Map.of(
                "cnlc_stage", row.get("cnlc_stage"),
                "pvtt_grade", row.get("pvtt_grade"),
                "extrahepatic_mets", row.get("extrahepatic_mets"),
                "tumor_count", row.get("tumor_count"),
                "max_diameter_cm", row.get("max_diameter_cm"),
                "diameter_unit", row.get("diameter_unit"),
                "t0", row.get("t0"),
                "visit_date", row.get("visit_date")));
        return f;
    }

    private static String normalizeStage(String s) {
        String u = s.trim();
        return switch (u.toUpperCase(Locale.ROOT)) {
            case "IIIB" -> "IIIb";
            case "IIIA" -> "IIIa";
            case "IIB" -> "IIb";
            case "IIA" -> "IIa";
            case "IB" -> "Ib";
            case "IA" -> "Ia";
            case "IV" -> "IV";
            default -> u;
        };
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static boolean bool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(str(o)) || "1".equals(str(o));
    }

    private static int intVal(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null || str(o).isBlank() ? fallback : Integer.parseInt(str(o));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Double dbl(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null || str(o).isBlank() ? null : Double.parseDouble(str(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof LocalDate d) {
            return d;
        }
        String s = str(o);
        if (s.isEmpty()) {
            return null;
        }
        try {
            if (s.contains("T")) {
                return Instant.parse(s).atZone(ZoneOffset.UTC).toLocalDate();
            }
            return LocalDate.parse(s.substring(0, Math.min(10, s.length())));
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "digest_error";
        }
    }
}
