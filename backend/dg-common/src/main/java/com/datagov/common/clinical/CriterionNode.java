package com.datagov.common.clinical;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 可审校队列条件树节点（禁止内嵌裸 SQL）。
 */
public record CriterionNode(
        String type,
        String event,
        String drugCodeSet,
        String relativeTo,
        List<String> window,
        List<CriterionNode> children,
        Map<String, String> attrs
) {
    public CriterionNode {
        type = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        children = children == null ? List.of() : List.copyOf(children);
        window = window == null ? List.of() : List.copyOf(window);
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
    }

    public static final List<String> ALLOWED_TYPES = List.of(
            "AND", "OR", "NOT", "EXISTS", "NOT_EXISTS", "SEQUENCE", "FIRST", "LAST", "TIME_WITHIN");
}
