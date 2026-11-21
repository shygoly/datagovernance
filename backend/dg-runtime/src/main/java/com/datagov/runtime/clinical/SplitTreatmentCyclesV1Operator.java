package com.datagov.runtime.clinical;

import com.datagov.common.audit.ReproducibilityFingerprint;
import com.datagov.common.clinical.ClinicalOperator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 同患者多 T₀ 按时间排序切分程次，输出稳定 {@code session_seq}（从 1 起）。
 */
public final class SplitTreatmentCyclesV1Operator implements ClinicalOperator {

    public static final String ID = "split_treatment_cycles_v1";
    public static final String VERSION = "1.0.0";

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
        return Map.of("type", "object", "required", List.of("patient_ref", "anchor_ts"));
    }

    @Override
    public Map<String, Object> outputSchema() {
        return Map.of("type", "object", "required", List.of("patient_ref", "anchor_id", "anchor_ts", "session_seq"));
    }

    @Override
    public OperatorResult execute(OperatorRequest request) {
        Map<String, List<Map<String, Object>>> byPatient = request.rows().stream()
                .collect(Collectors.groupingBy(r -> String.valueOf(r.getOrDefault("patient_ref", "")).trim()));

        List<Map<String, Object>> out = new ArrayList<>();
        StringBuilder digest = new StringBuilder(ID).append('@').append(VERSION);
        for (Map.Entry<String, List<Map<String, Object>>> e : byPatient.entrySet()) {
            String patient = e.getKey();
            if (patient.isBlank()) {
                continue;
            }
            List<Map<String, Object>> sorted = new ArrayList<>(e.getValue());
            sorted.sort(Comparator.comparing(r -> parseTs(r.get("anchor_ts")), Comparator.nullsLast(Comparator.naturalOrder())));
            int seq = 0;
            for (Map<String, Object> row : sorted) {
                Instant ts = parseTs(row.get("anchor_ts"));
                if (ts == null) {
                    continue;
                }
                seq++;
                String anchorId = String.valueOf(row.getOrDefault("anchor_id", patient + "-T" + seq)).trim();
                Map<String, Object> outRow = new LinkedHashMap<>();
                outRow.put("patient_ref", patient);
                outRow.put("anchor_id", anchorId);
                outRow.put("anchor_ts", ts.toString());
                outRow.put("session_seq", seq);
                if (row.get("anchor_type") != null) {
                    outRow.put("anchor_type", row.get("anchor_type"));
                }
                out.add(outRow);
                digest.append('|').append(patient).append('#').append(seq).append('@').append(ts);
            }
        }

        String opDigest = sha256(digest.toString());
        ReproducibilityFingerprint fp = new ReproducibilityFingerprint(
                1, List.of(), List.of(), List.of(),
                List.of(Map.of("operatorId", ID, "version", VERSION, "digest", opDigest)),
                opDigest, "split-cycles-v1", List.of(),
                Map.of("truncated", false, "maxRows", out.size()),
                Instant.now().toString());
        return new OperatorResult(ID, VERSION, opDigest, out, fp);
    }

    private static Instant parseTs(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Instant i) {
            return i;
        }
        try {
            return Instant.parse(String.valueOf(o).trim());
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
