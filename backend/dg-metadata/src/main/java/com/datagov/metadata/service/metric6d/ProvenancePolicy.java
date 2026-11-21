package com.datagov.metadata.service.metric6d;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Provenance 强制策略：llm_inference → requires_human_review；安全字段禁 LLM 写入。
 */
public final class ProvenancePolicy {

    private static final Set<String> FORBIDDEN_LLM_KEYS = Set.of(
            "rls", "row_level_security", "tenant_id", "workspace_id", "security",
            "acl", "password", "secret", "credential");

    private static final ObjectMapper JSON = new ObjectMapper();

    private ProvenancePolicy() {
    }

    public record Evaluation(boolean requiresHumanReview, List<String> blockedFields) {
        public Evaluation {
            blockedFields = blockedFields == null ? List.of() : List.copyOf(blockedFields);
        }
    }

    public static Evaluation evaluate(String provenanceJson, String specJson) {
        boolean requiresReview = false;
        List<String> blocked = new java.util.ArrayList<>();
        Map<String, Object> provenance = read(provenanceJson);
        if (containsLlmInference(provenance)) {
            requiresReview = true;
        }
        // 嵌套 FieldProvenance：任一 llm_inference 强制人审
        for (FieldProvenance fp : collectFieldProvenances(provenance)) {
            if (fp.requiresHumanReview()) {
                requiresReview = true;
            }
        }
        Map<String, Object> spec = read(specJson);
        for (FieldProvenance fp : collectFieldProvenances(spec)) {
            if (fp.requiresHumanReview()) {
                requiresReview = true;
            }
        }
        collectForbidden(spec, "", blocked);
        return new Evaluation(requiresReview, blocked);
    }

    /** 从嵌套 map 中抽取所有 FieldProvenance 节点。 */
    public static List<FieldProvenance> collectFieldProvenances(Map<String, Object> root) {
        List<FieldProvenance> out = new java.util.ArrayList<>();
        walkProvenance(root, out);
        return List.copyOf(out);
    }

    private static void walkProvenance(Object node, List<FieldProvenance> out) {
        if (node instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            if (cast.containsKey("source") || cast.containsKey("confidence")
                    || cast.containsKey("requires_human_review")) {
                // 形如 provenance: { source, confidence, ... }
                if (cast.get("source") != null || cast.get("kind") != null) {
                    out.add(FieldProvenance.fromMap(cast));
                }
            }
            Object nested = cast.get("provenance");
            if (nested instanceof Map<?, ?> pm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> pcast = (Map<String, Object>) pm;
                out.add(FieldProvenance.fromMap(pcast));
            }
            for (Object v : cast.values()) {
                walkProvenance(v, out);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                walkProvenance(item, out);
            }
        }
    }

    public static void assertWritableByLlm(String specJson) {
        Evaluation ev = evaluate(null, specJson);
        if (!ev.blockedFields().isEmpty()) {
            throw new SemanticGraphGateException(
                    "PROVENANCE_SECURITY",
                    "禁止 LLM 写入安全/租户/RLS 字段: " + String.join(",", ev.blockedFields()),
                    ev.blockedFields());
        }
    }

    private static boolean containsLlmInference(Map<String, Object> node) {
        if (node == null || node.isEmpty()) {
            return false;
        }
        for (var e : node.entrySet()) {
            if ("source".equalsIgnoreCase(e.getKey()) || "kind".equalsIgnoreCase(e.getKey())
                    || "provenance".equalsIgnoreCase(e.getKey())) {
                if (e.getValue() != null
                        && String.valueOf(e.getValue()).toLowerCase(Locale.ROOT).contains("llm_inference")) {
                    return true;
                }
            }
            if (e.getValue() instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) m;
                if (containsLlmInference(cast)) {
                    return true;
                }
            }
            if (e.getValue() instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> cast = (Map<String, Object>) m;
                        if (containsLlmInference(cast)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static void collectForbidden(Map<String, Object> node, String path, List<String> blocked) {
        if (node == null) {
            return;
        }
        for (var e : node.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
            String childPath = path.isEmpty() ? key : path + "." + key;
            if (FORBIDDEN_LLM_KEYS.contains(key)) {
                blocked.add(childPath);
            }
            if (e.getValue() instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) m;
                collectForbidden(cast, childPath, blocked);
            }
        }
    }

    private static Map<String, Object> read(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
