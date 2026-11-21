package com.datagov.runtime.clinical;

import com.datagov.common.audit.ReproducibilityFingerprint;
import com.datagov.common.clinical.ClinicalOperator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将候选事件按可配置窗口对齐到 T₀ 锚点（MATCHED / AMBIGUOUS / MISSING）。
 *
 * <p>输入行 {@code row_kind=ANCHOR|CANDIDATE}；不感知物理表名（C1/行业插件化）。
 */
public final class AlignToAnchorV1Operator implements ClinicalOperator {

    public static final String ID = "align_to_anchor_v1";
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
        return Map.of("type", "object", "required", List.of("row_kind", "patient_ref"));
    }

    @Override
    public Map<String, Object> outputSchema() {
        return Map.of("type", "object", "required", List.of("status", "patient_ref", "anchor_id", "session_seq", "role"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public OperatorResult execute(OperatorRequest request) {
        Map<String, Object> windows = castMap(request.params().get("windows"));
        if (windows.isEmpty()) {
            // 默认：基线 [-30,0) 天；随访 [28,56] 天
            windows = Map.of(
                    "BASELINE_IMAGING", Map.of("startDays", -30, "endDays", 0),
                    "BASELINE_LAB", Map.of("startDays", -14, "endDays", 0),
                    "FOLLOWUP", Map.of("startDays", 28, "endDays", 56));
        }

        List<Map<String, Object>> anchors = new ArrayList<>();
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> row : request.rows()) {
            String kind = str(row.get("row_kind"));
            if ("ANCHOR".equalsIgnoreCase(kind)) {
                anchors.add(row);
            } else if ("CANDIDATE".equalsIgnoreCase(kind)) {
                candidates.add(row);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        StringBuilder digest = new StringBuilder(ID).append('@').append(VERSION);
        for (Map<String, Object> anchor : anchors) {
            String patient = str(anchor.get("patient_ref"));
            String anchorId = str(anchor.get("anchor_id"));
            int sessionSeq = intVal(anchor.get("session_seq"), 1);
            Instant t0 = parseTs(anchor.get("anchor_ts"));
            if (patient.isBlank() || anchorId.isBlank() || t0 == null) {
                continue;
            }
            for (Map.Entry<String, Object> winEntry : windows.entrySet()) {
                String role = winEntry.getKey();
                Map<String, Object> win = castMap(winEntry.getValue());
                int startDays = intVal(win.get("startDays"), 0);
                int endDays = intVal(win.get("endDays"), 0);

                List<Map<String, Object>> hits = new ArrayList<>();
                for (Map<String, Object> cand : candidates) {
                    if (!patient.equals(str(cand.get("patient_ref")))) {
                        continue;
                    }
                    String candRole = str(cand.get("role"));
                    if (!role.equalsIgnoreCase(candRole) && !candRole.isBlank()) {
                        // 若候选声明了 role，必须匹配；未声明则按窗口试配
                        continue;
                    }
                    if (candRole.isBlank()) {
                        // 无 role 时仅当窗口角色与 params.defaultRole 或全部尝试——此处要求显式 role
                        continue;
                    }
                    Instant ets = parseTs(cand.get("event_ts"));
                    if (ets == null) {
                        continue;
                    }
                    long days = ChronoUnit.DAYS.between(t0.truncatedTo(ChronoUnit.DAYS), ets.truncatedTo(ChronoUnit.DAYS));
                    boolean inWindow = days >= startDays && days <= endDays;
                    if (inWindow) {
                        hits.add(cand);
                    }
                }

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("patient_ref", patient);
                row.put("anchor_id", anchorId);
                row.put("session_seq", sessionSeq);
                row.put("role", role);
                row.put("window_label", startDays + ".." + endDays + "d");
                if (hits.isEmpty()) {
                    row.put("status", "MISSING");
                    row.put("candidate_ref", null);
                    row.put("event_ts", null);
                } else if (hits.size() > 1) {
                    row.put("status", "AMBIGUOUS");
                    row.put("candidate_ref", null);
                    row.put("event_ts", null);
                    row.put("candidate_count", hits.size());
                } else {
                    Map<String, Object> hit = hits.getFirst();
                    row.put("status", "MATCHED");
                    row.put("candidate_ref", str(hit.get("event_id")));
                    row.put("event_ts", str(hit.get("event_ts")));
                }
                out.add(row);
                digest.append('|').append(patient).append(':').append(anchorId)
                        .append(':').append(role).append('=').append(row.get("status"));
            }
        }

        String opDigest = sha256(digest.toString());
        ReproducibilityFingerprint fp = new ReproducibilityFingerprint(
                1,
                List.of(),
                List.of(),
                List.of(Map.of("standardId", "STD_CHANCE_HCC_QC_V1", "version", "bound")),
                List.of(Map.of("operatorId", ID, "version", VERSION, "digest", opDigest)),
                opDigest,
                "align-windows-v1",
                List.of(),
                Map.of("truncated", false, "maxRows", out.size()),
                Instant.now().toString());
        return new OperatorResult(ID, VERSION, opDigest, out, fp);
    }

    private static Map<String, Object> castMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static int intVal(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? fallback : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Instant parseTs(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Instant i) {
            return i;
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (Exception ignored) {
            try {
                return Instant.parse(s + "T00:00:00Z");
            } catch (Exception e) {
                return null;
            }
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
