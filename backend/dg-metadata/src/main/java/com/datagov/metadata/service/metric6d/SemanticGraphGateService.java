package com.datagov.metadata.service.metric6d;

import com.datagov.metadata.entity.SemanticEntities.SemanticLink;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 对象图五道硬闸门 G1–G5 + 删除保护（openspec: metric-6d-ontology-verification · W2）。
 */
@Service
public class SemanticGraphGateService {

    private static final Set<String> ADDITIVITY = Set.of("additive", "semi_additive", "non_additive");
    private static final Set<String> LOD = Set.of("NONE", "FIXED", "INCLUDE", "EXCLUDE");
    private static final Set<String> METRIC_TYPES = Set.of("atomic", "ratio", "derived", "cumulative");
    private static final Set<String> REF_LINK_TYPES = Set.of(
            "DIMENSION_REF", "METRIC_REF", "COLUMN_REF", "CALENDAR_REF", "DERIVED_FROM");

    private final ObjectMapper json;

    public SemanticGraphGateService() {
        this(new ObjectMapper());
    }

    @Autowired
    public SemanticGraphGateService(ObjectMapper json) {
        this.json = json == null ? new ObjectMapper() : json;
    }

    /** G1：六维 Spec 结构合法性 */
    public Metric6DSpec validateSchema(String specJson) {
        Map<String, Object> raw = readMap(specJson);
        Metric6DSpec.Additivity additivity = parseAdditivity(raw.get("additivity"));
        Metric6DSpec.Lod lod = parseLod(raw.get("lod"));
        Metric6DSpec.Composition composition = parseComposition(raw.get("composition"));
        Metric6DSpec.FilterInteraction filters = parseFilters(raw.get("filter_interaction"));
        Metric6DSpec.TimeCalendar calendar = parseCalendar(raw.get("time_calendar"));
        Metric6DSpec.GraphLocality locality = parseLocality(raw.get("graph_locality"));

        if (additivity != null && "semi_additive".equalsIgnoreCase(additivity.kind())) {
            if (additivity.windowGroupings() == null || additivity.windowGroupings().isEmpty()) {
                fail("G1", "semi_additive 必须配置 window_groupings", List.of("additivity.window_groupings"));
            }
        }
        if (additivity != null && !"semi_additive".equalsIgnoreCase(additivity.kind())) {
            if ((additivity.nonAdditiveDim() != null && !additivity.nonAdditiveDim().isEmpty())
                    || (additivity.windowGroupings() != null && !additivity.windowGroupings().isEmpty())) {
                fail("G1", "non_additive_dim / window_groupings 仅允许出现在 semi_additive", List.of("additivity"));
            }
        }
        if (composition != null && "ratio".equalsIgnoreCase(composition.metricType())) {
            String expr = composition.expression() == null ? "" : composition.expression();
            if (!expr.toUpperCase(Locale.ROOT).contains("NULLIF")) {
                fail("G1", "ratio 度量必须显式 NULLIF 防除零", List.of("composition.expression"));
            }
        }
        if (containsForbiddenSecurityKeys(raw)) {
            fail("G1", "禁止在度量 Spec 中写入安全/租户/RLS 字段", List.of("security"));
        }
        return new Metric6DSpec(additivity, lod, filters, calendar, composition, locality);
    }

    /** G2：引用完整性（refs 必须全部命中已知对象 id/code） */
    public void validateRefs(Set<String> referencedCodesOrIds, Set<String> existingCodesOrIds) {
        Set<String> existing = lowerSet(existingCodesOrIds);
        List<String> missing = new ArrayList<>();
        for (String ref : referencedCodesOrIds) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            if (!existing.contains(ref.toLowerCase(Locale.ROOT))) {
                missing.add(ref);
            }
        }
        if (!missing.isEmpty()) {
            fail("G2", "UNRESOLVED_REF：引用未解析到已存在节点", missing);
        }
    }

    /** G3：类型匹配（LOD 只能引 DIMENSION/ATTRIBUTE；组件引 METRIC/COLUMN） */
    public void validateTypes(Metric6DSpec spec, Map<String, String> codeToType) {
        if (spec == null) {
            return;
        }
        Map<String, String> types = new HashMap<>();
        codeToType.forEach((k, v) -> types.put(k.toLowerCase(Locale.ROOT), v == null ? "" : v.toUpperCase(Locale.ROOT)));
        if (spec.lod() != null && spec.lod().dimensions() != null) {
            for (String d : spec.lod().dimensions()) {
                String t = types.getOrDefault(d.toLowerCase(Locale.ROOT), "");
                if (!Set.of("DIMENSION", "ATTRIBUTE").contains(t)) {
                    fail("G3", "LOD 维度引用类型非法: " + d + " -> " + t, List.of(d));
                }
            }
        }
        if (spec.composition() != null && spec.composition().components() != null) {
            for (Map.Entry<String, Map<String, Object>> e : spec.composition().components().entrySet()) {
                Object metricRef = e.getValue().get("metric_ref");
                Object columnRef = e.getValue().get("column_ref");
                if (metricRef != null) {
                    String t = types.getOrDefault(String.valueOf(metricRef).toLowerCase(Locale.ROOT), "");
                    if (!t.isBlank() && !"METRIC".equals(t)) {
                        fail("G3", "components.metric_ref 类型必须为 METRIC: " + metricRef,
                                List.of(String.valueOf(metricRef)));
                    }
                }
                if (columnRef != null) {
                    String t = types.getOrDefault(String.valueOf(columnRef).toLowerCase(Locale.ROOT), "");
                    if (!t.isBlank() && !"COLUMN".equals(t) && !"ATTRIBUTE".equals(t)) {
                        fail("G3", "components.column_ref 类型非法: " + columnRef, List.of(String.valueOf(columnRef)));
                    }
                }
            }
        }
    }

    /** G4：复合度量依赖 DAG 无环（沿 METRIC_REF / DERIVED_FROM） */
    public void validateDag(List<SemanticLink> links) {
        Map<String, List<String>> adj = new HashMap<>();
        for (SemanticLink link : links) {
            if (link == null || link.getLinkType() == null) {
                continue;
            }
            String lt = link.getLinkType().toUpperCase(Locale.ROOT);
            if (!REF_LINK_TYPES.contains(lt) && !"DERIVED_FROM".equals(lt) && !"METRIC_REF".equals(lt)) {
                continue;
            }
            if (!"METRIC_REF".equals(lt) && !"DERIVED_FROM".equals(lt)) {
                continue;
            }
            adj.computeIfAbsent(link.getFromObjectId(), k -> new ArrayList<>()).add(link.getToObjectId());
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        for (String node : adj.keySet()) {
            if (dfsCycle(node, adj, visiting, visited, stack)) {
                fail("G4", "复合度量依赖存在环", new ArrayList<>(stack));
            }
        }
    }

    /** G5：目标 digest 变化 → 引用方标 STALE_REVIEW */
    public List<SemanticObject> markStaleOnDigestChange(SemanticObject changed,
                                                       List<SemanticLink> inboundLinks,
                                                       Map<String, SemanticObject> objectsById) {
        List<SemanticObject> stale = new ArrayList<>();
        if (changed == null || changed.getContentDigest() == null) {
            return stale;
        }
        for (SemanticLink link : inboundLinks) {
            if (link == null || !Objects.equals(link.getToObjectId(), changed.getId())) {
                continue;
            }
            String snap = link.getTargetContentHash();
            if (snap != null && !snap.isBlank() && !snap.equals(changed.getContentDigest())) {
                SemanticObject referrer = objectsById.get(link.getFromObjectId());
                if (referrer != null) {
                    referrer.setStatus("STALE_REVIEW");
                    stale.add(referrer);
                }
            }
        }
        return stale;
    }

    /** 删除保护：存在入边引用则拒绝 */
    public void assertDeletable(String objectId, List<SemanticLink> inboundActiveLinks) {
        List<String> referrers = new ArrayList<>();
        for (SemanticLink link : inboundActiveLinks) {
            if (link == null) {
                continue;
            }
            if (Objects.equals(objectId, link.getToObjectId())) {
                referrers.add(link.getFromObjectId());
            }
        }
        if (!referrers.isEmpty()) {
            fail("DELETE_PROTECTION", "对象仍被引用，禁止删除", referrers);
        }
    }

    private boolean dfsCycle(String node,
                             Map<String, List<String>> adj,
                             Set<String> visiting,
                             Set<String> visited,
                             Deque<String> stack) {
        if (visited.contains(node)) {
            return false;
        }
        if (visiting.contains(node)) {
            stack.addLast(node);
            return true;
        }
        visiting.add(node);
        stack.addLast(node);
        for (String next : adj.getOrDefault(node, List.of())) {
            if (dfsCycle(next, adj, visiting, visited, stack)) {
                return true;
            }
        }
        stack.removeLast();
        visiting.remove(node);
        visited.add(node);
        return false;
    }

    private Metric6DSpec.Additivity parseAdditivity(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        String kind = str(m.get("kind"));
        if (kind != null && !ADDITIVITY.contains(kind.toLowerCase(Locale.ROOT))) {
            fail("G1", "非法 additivity.kind: " + kind, List.of(kind));
        }
        return new Metric6DSpec.Additivity(
                kind == null ? null : kind.toLowerCase(Locale.ROOT),
                toStringList(m.get("non_additive_dim")),
                toStringList(m.get("window_groupings")),
                str(m.get("aggregation_op")));
    }

    private Metric6DSpec.Lod parseLod(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return new Metric6DSpec.Lod("NONE", List.of());
        }
        String type = str(m.get("type"));
        if (type == null) {
            type = "NONE";
        }
        if (!LOD.contains(type.toUpperCase(Locale.ROOT))) {
            fail("G1", "非法 lod.type: " + type, List.of(type));
        }
        return new Metric6DSpec.Lod(type.toUpperCase(Locale.ROOT), toStringList(m.get("dimensions")));
    }

    private Metric6DSpec.Composition parseComposition(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        String metricType = str(m.get("metric_type"));
        if (metricType != null && !METRIC_TYPES.contains(metricType.toLowerCase(Locale.ROOT))) {
            fail("G1", "非法 composition.metric_type: " + metricType, List.of(metricType));
        }
        Map<String, Map<String, Object>> components = new HashMap<>();
        Object comps = m.get("components");
        if (comps instanceof Map<?, ?> cm) {
            for (Map.Entry<?, ?> e : cm.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> vm) {
                    Map<String, Object> copy = new HashMap<>();
                    vm.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    components.put(String.valueOf(e.getKey()), copy);
                }
            }
        }
        return new Metric6DSpec.Composition(
                metricType == null ? null : metricType.toLowerCase(Locale.ROOT),
                str(m.get("expression")),
                components);
    }

    private Metric6DSpec.FilterInteraction parseFilters(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        List<Map<String, Object>> defaults = new ArrayList<>();
        Object df = m.get("default_filters");
        if (df instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> fm) {
                    Map<String, Object> copy = new HashMap<>();
                    fm.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    defaults.add(copy);
                }
            }
        }
        return new Metric6DSpec.FilterInteraction(defaults, toStringList(m.get("ignore_dimensions")), str(m.get("filter_stage")));
    }

    private Metric6DSpec.TimeCalendar parseCalendar(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        return new Metric6DSpec.TimeCalendar(str(m.get("calendar_ref")), str(m.get("time_grain")), str(m.get("period_transform")));
    }

    private Metric6DSpec.GraphLocality parseLocality(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        return new Metric6DSpec.GraphLocality(
                castMapList(m.get("entity_keys")),
                castMapList(m.get("join_cardinalities")),
                m.get("max_hop") instanceof Number n ? n.intValue() : null);
    }

    private boolean containsForbiddenSecurityKeys(Map<String, Object> raw) {
        String blob = raw.toString().toLowerCase(Locale.ROOT);
        return blob.contains("\"rls\"") || blob.contains("row_level_security")
                || blob.contains("tenant_column") || blob.contains("security_policy");
    }

    private List<Map<String, Object>> castMapList(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> copy = new HashMap<>();
                    m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    out.add(copy);
                }
            }
        }
        return out;
    }

    private Map<String, Object> readMap(String specJson) {
        if (specJson == null || specJson.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(specJson, new TypeReference<>() {
            });
        } catch (Exception ex) {
            fail("G1", "Spec JSON 无法解析", List.of(ex.getMessage() == null ? "parse_error" : ex.getMessage()));
            return Map.of();
        }
    }

    private static List<String> toStringList(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object v : list) {
            if (v != null) {
                out.add(String.valueOf(v));
            }
        }
        return out;
    }

    private static Set<String> lowerSet(Set<String> in) {
        Set<String> out = new LinkedHashSet<>();
        if (in != null) {
            for (String s : in) {
                if (s != null) {
                    out.add(s.toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isBlank() ? null : s;
    }

    private static void fail(String gate, String message, List<String> details) {
        throw new SemanticGraphGateException(gate, message, details);
    }
}
