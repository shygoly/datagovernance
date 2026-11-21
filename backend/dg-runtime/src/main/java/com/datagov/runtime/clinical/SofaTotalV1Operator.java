package com.datagov.runtime.clinical;

import com.datagov.common.audit.ReproducibilityFingerprint;
import com.datagov.common.clinical.ClinicalOperator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确定性 SOFA 总分样例算子（输入已对齐的分项列；证明 ClinicalStatsRuntime 闭环，非域完备）。
 */
public final class SofaTotalV1Operator implements ClinicalOperator {

    public static final String ID = "sofa_total_v1";
    public static final String VERSION = "1.0.0";

    private static final List<String> COMPONENTS = List.of(
            "respiration", "coagulation", "liver", "cardiovascular", "cns", "renal");

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
        return Map.of(
                "type", "object",
                "required", List.of("member_id"),
                "properties", Map.of(
                        "member_id", Map.of("type", "string"),
                        "respiration", Map.of("type", "integer", "minimum", 0, "maximum", 4),
                        "coagulation", Map.of("type", "integer", "minimum", 0, "maximum", 4),
                        "liver", Map.of("type", "integer", "minimum", 0, "maximum", 4),
                        "cardiovascular", Map.of("type", "integer", "minimum", 0, "maximum", 4),
                        "cns", Map.of("type", "integer", "minimum", 0, "maximum", 4),
                        "renal", Map.of("type", "integer", "minimum", 0, "maximum", 4)));
    }

    @Override
    public Map<String, Object> outputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "member_id", Map.of("type", "string"),
                        "sofa_total", Map.of("type", "integer", "minimum", 0, "maximum", 24)));
    }

    @Override
    public OperatorResult execute(OperatorRequest request) {
        List<Map<String, Object>> out = new ArrayList<>();
        StringBuilder digestMaterial = new StringBuilder(ID).append('@').append(VERSION);
        for (Map<String, Object> row : request.rows()) {
            String memberId = String.valueOf(row.getOrDefault("member_id", ""));
            int total = 0;
            for (String c : COMPONENTS) {
                total += clampScore(row.get(c));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("member_id", memberId);
            result.put("sofa_total", total);
            out.add(result);
            digestMaterial.append('|').append(memberId).append('=').append(total);
        }
        String digest = sha256(digestMaterial.toString());
        ReproducibilityFingerprint fp = new ReproducibilityFingerprint(
                1,
                List.of(),
                List.of(),
                List.of(),
                List.of(Map.of("operatorId", ID, "version", VERSION, "digest", digest)),
                digest,
                digest,
                List.of(Map.of("principalType", "SYSTEM", "id", "ClinicalStatsRuntime")),
                Map.of("truncated", false, "maxRows", out.size()),
                Instant.now().toString());
        return new OperatorResult(ID, VERSION, digest, out, fp);
    }

    private static int clampScore(Object raw) {
        if (raw == null) {
            return 0;
        }
        int v;
        if (raw instanceof Number n) {
            v = n.intValue();
        } else {
            v = Integer.parseInt(String.valueOf(raw));
        }
        if (v < 0 || v > 4) {
            throw new IllegalArgumentException("SOFA 分项须在 0..4: " + raw);
        }
        return v;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
