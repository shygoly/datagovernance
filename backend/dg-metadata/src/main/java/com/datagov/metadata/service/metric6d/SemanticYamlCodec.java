package com.datagov.metadata.service.metric6d;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 语义本体 YAML 短名解析 + 显式 ref 块 + short/full 双模式导出。
 */
@Service
public class SemanticYamlCodec {

    public enum ResolveStatus { RESOLVED, UNRESOLVED_REF, AMBIGUOUS_REF }

    public record ResolveResult(
            ResolveStatus status,
            String shortName,
            String canonicalId,
            String canonicalCode,
            List<Candidate> candidates
    ) {
        public ResolveResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    public record Candidate(String id, String code, String name, String objectType, int editDistance) {
    }

    public record ParsedMetric(
            String code,
            String name,
            String standardRef,
            Map<String, Object> spec,
            List<String> dimensionRefs,
            List<String> metricRefs
    ) {
    }

    public record ImportDocument(
            String model,
            List<ParsedMetric> metrics,
            List<Map<String, Object>> dimensions
    ) {
    }

    private final ObjectMapper json;
    private final Yaml yaml;

    public SemanticYamlCodec() {
        this(new ObjectMapper());
    }

    @Autowired
    public SemanticYamlCodec(ObjectMapper json) {
        this.json = json == null ? new ObjectMapper() : json;
        this.yaml = new Yaml();
    }

    public ResolveResult resolveRef(String shortName, List<CatalogEntry> catalog) {
        if (shortName == null || shortName.isBlank()) {
            return new ResolveResult(ResolveStatus.UNRESOLVED_REF, shortName, null, null, List.of());
        }
        String needle = shortName.trim();
        List<CatalogEntry> exact = catalog.stream()
                .filter(e -> needle.equalsIgnoreCase(e.code())
                        || needle.equalsIgnoreCase(localName(e.code()))
                        || needle.equalsIgnoreCase(e.id()))
                .toList();
        if (exact.size() == 1) {
            CatalogEntry hit = exact.get(0);
            return new ResolveResult(ResolveStatus.RESOLVED, shortName, hit.id(), hit.code(), List.of());
        }
        if (exact.size() > 1) {
            return new ResolveResult(ResolveStatus.AMBIGUOUS_REF, shortName, null, null,
                    exact.stream().map(e -> new Candidate(e.id(), e.code(), e.name(), e.objectType(), 0)).toList());
        }
        List<Candidate> fuzzy = catalog.stream()
                .map(e -> new Candidate(e.id(), e.code(), e.name(), e.objectType(),
                        Math.min(editDistance(needle.toLowerCase(Locale.ROOT), localName(e.code()).toLowerCase(Locale.ROOT)),
                                editDistance(needle.toLowerCase(Locale.ROOT), e.code().toLowerCase(Locale.ROOT)))))
                .sorted(Comparator.comparingInt(Candidate::editDistance))
                .limit(5)
                .filter(c -> c.editDistance() <= Math.max(2, needle.length() / 3))
                .toList();
        return new ResolveResult(ResolveStatus.UNRESOLVED_REF, shortName, null, null, fuzzy);
    }

    @SuppressWarnings("unchecked")
    public ImportDocument parse(String yamlOrJson) {
        if (yamlOrJson == null || yamlOrJson.isBlank()) {
            throw new IllegalArgumentException("YAML 内容为空");
        }
        Object root;
        String trimmed = yamlOrJson.trim();
        try {
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                root = json.readValue(trimmed, Object.class);
            } else {
                root = yaml.load(trimmed);
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("YAML/JSON 解析失败: " + ex.getMessage(), ex);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("根节点必须是 mapping");
        }
        String model = stringOrNull(map.get("model"));
        List<ParsedMetric> metrics = new ArrayList<>();
        Object metricsRaw = map.get("metrics");
        if (metricsRaw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                String code = stringOrNull(m.get("code"));
                if (code == null) {
                    code = stringOrNull(m.get("id"));
                }
                Map<String, Object> spec = m.get("spec") instanceof Map<?, ?> s
                        ? castMap(s) : castMap(m);
                List<String> dimRefs = extractRefs(m.get("dimensions"), spec.get("lod"));
                List<String> metricRefs = extractMetricRefs(spec.get("composition"));
                // 显式 ref 块优先
                if (m.get("ref") instanceof Map<?, ?> ref) {
                    Object dims = ref.get("dimensions");
                    if (dims instanceof List<?> dl) {
                        dimRefs = dl.stream().map(String::valueOf).toList();
                    }
                    Object mets = ref.get("metrics");
                    if (mets instanceof List<?> ml) {
                        metricRefs = ml.stream().map(String::valueOf).toList();
                    }
                }
                metrics.add(new ParsedMetric(
                        code,
                        stringOrNull(m.get("name")),
                        stringOrNull(m.get("standardRef")),
                        spec,
                        dimRefs,
                        metricRefs));
            }
        }
        List<Map<String, Object>> dimensions = new ArrayList<>();
        Object dimsRaw = map.get("dimensions");
        if (dimsRaw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    dimensions.add(castMap(m));
                } else if (item != null) {
                    dimensions.add(Map.of("code", String.valueOf(item)));
                }
            }
        }
        return new ImportDocument(model, metrics, dimensions);
    }

    /**
     * 解析导入文档中的全部短名引用；任一 UNRESOLVED/AMBIGUOUS 则抛出。
     */
    public Map<String, String> resolveAllOrThrow(ImportDocument doc, List<CatalogEntry> catalog) {
        Map<String, String> resolved = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (ParsedMetric m : doc.metrics()) {
            for (String ref : concat(m.dimensionRefs(), m.metricRefs())) {
                ResolveResult r = resolveRef(ref, catalog);
                if (r.status() == ResolveStatus.RESOLVED) {
                    resolved.put(ref, r.canonicalCode());
                } else {
                    errors.add(r.status() + ":" + ref + " candidates="
                            + r.candidates().stream().map(Candidate::code).collect(Collectors.joining(",")));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("引用解析失败: " + String.join("; ", errors));
        }
        return resolved;
    }

    public String export(List<CatalogEntry> metrics,
                         List<CatalogEntry> dimensions,
                         String mode) {
        boolean full = mode != null && mode.equalsIgnoreCase("full");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", "default");
        root.put("mode", full ? "full" : "short");
        List<Map<String, Object>> dimOut = new ArrayList<>();
        for (CatalogEntry d : dimensions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", full ? d.code() : localName(d.code()));
            if (full) {
                row.put("id", d.id());
            }
            row.put("name", d.name());
            row.put("objectType", d.objectType());
            dimOut.add(row);
        }
        List<Map<String, Object>> metricOut = new ArrayList<>();
        for (CatalogEntry m : metrics) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", full ? m.code() : localName(m.code()));
            if (full) {
                row.put("id", m.id());
            }
            row.put("name", m.name());
            row.put("objectType", m.objectType());
            if (m.spec() != null && !m.spec().isEmpty()) {
                row.put("spec", m.spec());
            }
            metricOut.add(row);
        }
        root.put("dimensions", dimOut);
        root.put("metrics", metricOut);
        return yaml.dump(root);
    }

    public record CatalogEntry(
            String id,
            String code,
            String name,
            String objectType,
            Map<String, Object> spec
    ) {
        public CatalogEntry {
            spec = spec == null ? Map.of() : Map.copyOf(spec);
        }
    }

    private static List<String> extractRefs(Object dimensionsField, Object lod) {
        List<String> out = new ArrayList<>();
        if (dimensionsField instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        }
        if (lod instanceof Map<?, ?> m && m.get("dimensions") instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }

    private static List<String> extractMetricRefs(Object composition) {
        List<String> out = new ArrayList<>();
        if (!(composition instanceof Map<?, ?> m)) {
            return out;
        }
        Object comps = m.get("components");
        if (comps instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> c && c.get("metric") != null) {
                    out.add(String.valueOf(c.get("metric")));
                } else if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }

    private static String localName(String code) {
        if (code == null) {
            return "";
        }
        int idx = code.lastIndexOf('.');
        return idx >= 0 ? code.substring(idx + 1) : code;
    }

    private static int editDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String stringOrNull(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        if (a != null) {
            out.addAll(a);
        }
        if (b != null) {
            out.addAll(b);
        }
        return out;
    }
}
