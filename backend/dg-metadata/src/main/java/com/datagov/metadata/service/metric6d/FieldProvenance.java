package com.datagov.metadata.service.metric6d;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 字段级 Provenance（metric-6d §2.2）。
 *
 * <p>来源优先级：{@code db_constraint > knowledge_graph > historical_sql > llm_inference}。
 * {@code llm_inference}（除纯描述类）强制 {@code requiresHumanReview=true}。
 */
public record FieldProvenance(
        String source,
        String ref,
        String evidence,
        String confidence,
        boolean requiresHumanReview
) {
    public static final Set<String> SOURCES = Set.of(
            "db_constraint", "knowledge_graph", "historical_sql", "llm_inference", "human");

    public static final Map<String, Integer> PRIORITY = Map.of(
            "db_constraint", 4,
            "knowledge_graph", 3,
            "historical_sql", 2,
            "llm_inference", 1,
            "human", 5);

    public FieldProvenance {
        source = source == null || source.isBlank() ? "llm_inference" : source.trim().toLowerCase(Locale.ROOT);
        confidence = confidence == null || confidence.isBlank()
                ? "MEDIUM"
                : confidence.trim().toUpperCase(Locale.ROOT);
        if ("llm_inference".equals(source)) {
            requiresHumanReview = true;
        }
    }

    public static FieldProvenance of(String source, String ref, String evidence, String confidence) {
        boolean review = source != null && source.toLowerCase(Locale.ROOT).contains("llm");
        return new FieldProvenance(source, ref, evidence, confidence, review);
    }

    public static FieldProvenance fromMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return of("llm_inference", null, null, "MEDIUM");
        }
        String source = str(raw.get("source"));
        if (source == null) {
            source = str(raw.get("kind"));
        }
        Boolean review = raw.get("requires_human_review") instanceof Boolean b ? b : null;
        FieldProvenance base = of(source, str(raw.get("ref")), str(raw.get("evidence")), str(raw.get("confidence")));
        if (review != null && Boolean.TRUE.equals(review)) {
            return new FieldProvenance(base.source(), base.ref(), base.evidence(), base.confidence(), true);
        }
        return base;
    }

    public Map<String, Object> toMap() {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("source", source);
        if (ref != null && !ref.isBlank()) {
            m.put("ref", ref);
        }
        if (evidence != null && !evidence.isBlank()) {
            m.put("evidence", evidence);
        }
        m.put("confidence", confidence);
        m.put("requires_human_review", requiresHumanReview);
        return Map.copyOf(m);
    }

    public int priorityRank() {
        return PRIORITY.getOrDefault(source, 0);
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isBlank() ? null : s;
    }
}
