package com.datagov.metadata.service.metric6d;

import java.util.List;
import java.util.Map;

/**
 * 度量六维正交 Spec 的解析视图（G1 Schema 校验入口）。
 */
public record Metric6DSpec(
        Additivity additivity,
        Lod lod,
        FilterInteraction filterInteraction,
        TimeCalendar timeCalendar,
        Composition composition,
        GraphLocality graphLocality
) {
    public record Additivity(
            String kind,
            List<String> nonAdditiveDim,
            List<String> windowGroupings,
            String aggregationOp
    ) {
    }

    public record Lod(String type, List<String> dimensions) {
    }

    public record FilterInteraction(
            List<Map<String, Object>> defaultFilters,
            List<String> ignoreDimensions,
            String filterStage
    ) {
    }

    public record TimeCalendar(String calendarRef, String timeGrain, String periodTransform) {
    }

    public record Composition(
            String metricType,
            String expression,
            Map<String, Map<String, Object>> components
    ) {
    }

    public record GraphLocality(
            List<Map<String, Object>> entityKeys,
            List<Map<String, Object>> joinCardinalities,
            Integer maxHop
    ) {
    }
}
