package com.datagov.metadata.service;

import com.datagov.metadata.entity.SemanticEntities.SemanticMapping;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Catalog Grammar 投影（openspec: smq-constrained-compile · Phase 2）。
 *
 * <p>从 PUBLISHED 对象图按 ACL 裁剪后输出可枚举约束（C5 Pre-retrieval），
 * 供 Agent / MCP {@code list_metrics}/{@code list_dimensions}/问数 skill 消费。
 */
@Service
public class SemanticCatalogGrammarService {

    public static final List<String> FILTER_OPS = List.of(
            "=", "!=", "IN", "NOT_IN", ">", ">=", "<", "<=", "LIKE");
    public static final List<String> AT_MODIFIERS = List.of("USING", "SET_GRAIN");
    public static final List<String> DEFAULT_GRAINS = List.of("day", "month", "quarter", "year");

    private final SemanticOntologyService ontologyService;
    private final ObjectMapper objectMapper;

    public SemanticCatalogGrammarService(SemanticOntologyService ontologyService, ObjectMapper objectMapper) {
        this.ontologyService = ontologyService;
        this.objectMapper = objectMapper;
    }

    public record GrammarMetric(
            String code,
            String name,
            String metricType,
            List<String> allowedDimensions,
            List<String> allowedGrains,
            boolean requiresStandard,
            boolean verified,
            boolean productionReady
    ) {
    }

    public record GrammarDimension(
            String code,
            String name,
            String objectType
    ) {
    }

    public record CatalogGrammar(
            List<GrammarMetric> metrics,
            List<GrammarDimension> dimensions,
            List<String> filterOps,
            List<String> atModifiers,
            String workspaceId
    ) {
    }

    /**
     * @param productionOnly 为 true 时仅返回已 verified 的度量（定义态门禁未通过者不可枚举）
     */
    public CatalogGrammar project(String workspaceId,
                                  Set<String> allowedDatasetIds,
                                  boolean productionOnly) {
        String ws = Objects.requireNonNull(workspaceId, "workspaceId");
        List<SemanticObject> published = ontologyService.listPublishedObjectsInternal(ws);
        Map<String, List<SemanticMapping>> mappingsByObj = ontologyService.listWorkspaceMappingsInternal(ws)
                .stream()
                .collect(Collectors.groupingBy(SemanticMapping::getObjectId));

        List<GrammarMetric> metrics = new ArrayList<>();
        List<GrammarDimension> dimensions = new ArrayList<>();

        for (SemanticObject obj : published) {
            if (!isAuthorized(obj, mappingsByObj.getOrDefault(obj.getId(), List.of()), allowedDatasetIds)) {
                continue;
            }
            String type = obj.getObjectType() == null ? "" : obj.getObjectType().toUpperCase(Locale.ROOT);
            if ("METRIC".equals(type)) {
                boolean verified = isVerified(obj.getVerifiedJson());
                if (productionOnly && !verified) {
                    continue;
                }
                Map<String, Object> spec = readSpec(obj.getSpecJson());
                metrics.add(new GrammarMetric(
                        obj.getCode(),
                        obj.getName(),
                        metricTypeOf(spec),
                        allowedDimensionsOf(spec),
                        allowedGrainsOf(spec),
                        obj.getStandardRef() != null && !obj.getStandardRef().isBlank(),
                        verified,
                        verified));
            } else if ("DIMENSION".equals(type) || "ATTRIBUTE".equals(type) || "ENTITY".equals(type)) {
                dimensions.add(new GrammarDimension(obj.getCode(), obj.getName(), type));
            }
        }

        metrics.sort((a, b) -> a.code().compareToIgnoreCase(b.code()));
        dimensions.sort((a, b) -> a.code().compareToIgnoreCase(b.code()));
        return new CatalogGrammar(metrics, dimensions, FILTER_OPS, AT_MODIFIERS, ws);
    }

    private boolean isAuthorized(SemanticObject obj, List<SemanticMapping> mappings, Set<String> allowedDatasets) {
        if (allowedDatasets == null) {
            return true;
        }
        if (mappings == null || mappings.isEmpty()) {
            return true;
        }
        return mappings.stream().anyMatch(m -> allowedDatasets.contains(m.getCatalogDatasetId()));
    }

    private boolean isVerified(String verifiedJson) {
        if (verifiedJson == null || verifiedJson.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> m = objectMapper.readValue(verifiedJson, new TypeReference<>() {
            });
            Object status = m.getOrDefault("status", m.get("overall"));
            if (status != null && "PASS".equalsIgnoreCase(String.valueOf(status))) {
                return true;
            }
            Object gPass = m.get("gatesPass");
            return Boolean.TRUE.equals(gPass) || "true".equalsIgnoreCase(String.valueOf(gPass));
        } catch (Exception ex) {
            return false;
        }
    }

    private Map<String, Object> readSpec(String specJson) {
        if (specJson == null || specJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(specJson, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String metricTypeOf(Map<String, Object> spec) {
        Object composition = spec.get("composition");
        if (composition instanceof Map<?, ?> c) {
            Object t = c.get("metric_type");
            if (t != null && !String.valueOf(t).isBlank()) {
                return String.valueOf(t).toLowerCase(Locale.ROOT);
            }
        }
        Object kind = spec.get("metricKind");
        if (kind != null) {
            return String.valueOf(kind).toLowerCase(Locale.ROOT);
        }
        return "base";
    }

    @SuppressWarnings("unchecked")
    private List<String> allowedDimensionsOf(Map<String, Object> spec) {
        Object raw = spec.get("allowedDimensions");
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
        }
        Object additivity = spec.get("additivity");
        if (additivity instanceof Map<?, ?> a) {
            Object dims = a.get("non_additive_dim");
            if (dims instanceof List<?> list) {
                // Grammar 暴露的是「允许」维；六维非加性维列表是限制，不直接等同 allowed
                return List.of();
            }
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> allowedGrainsOf(Map<String, Object> spec) {
        Object raw = spec.get("allowedGrains");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).map(s -> s.toLowerCase(Locale.ROOT)).toList();
        }
        Object calendar = spec.get("time_calendar");
        if (calendar instanceof Map<?, ?> c) {
            Object grain = c.get("time_grain");
            if (grain != null && !String.valueOf(grain).isBlank()) {
                return List.of(String.valueOf(grain).toLowerCase(Locale.ROOT));
            }
        }
        return DEFAULT_GRAINS;
    }
}
