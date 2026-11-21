package com.datagov.common.clinical;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cohort/Criterion 结构校验：禁止裸 SQL，限制算子集合。
 */
public final class CohortSpecValidator {

    private static final Pattern SQL_SMELL = Pattern.compile(
            "(?i)\\b(select|insert|update|delete|drop|alter|union|join|from|where)\\b|;|--|/\\*");

    private CohortSpecValidator() {
    }

    public static void validate(CohortSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("CohortSpec 不能为空");
        }
        if (spec.code() == null || spec.code().isBlank()) {
            throw new IllegalArgumentException("cohort.code 必填");
        }
        if (spec.anchor() == null || blank(spec.anchor().event()) || blank(spec.anchor().timeAttr())) {
            throw new IllegalArgumentException("cohort.anchor.event / timeAttr 必填");
        }
        if (spec.window() == null || blank(spec.window().startOffset()) || blank(spec.window().endOffset())) {
            throw new IllegalArgumentException("cohort.window.startOffset / endOffset 必填（ISO-8601 时长）");
        }
        rejectSql(spec.code(), "code");
        rejectSql(spec.name(), "name");
        rejectSql(spec.memberGrain(), "memberGrain");
        rejectSql(spec.anchor().event(), "anchor.event");
        rejectSql(spec.anchor().timeAttr(), "anchor.timeAttr");
        for (CriterionNode n : spec.include()) {
            validateNode(n, "include");
        }
        for (CriterionNode n : spec.exclude()) {
            validateNode(n, "exclude");
        }
    }

    private static void validateNode(CriterionNode node, String path) {
        if (node == null) {
            throw new IllegalArgumentException(path + " 节点不能为空");
        }
        if (!CriterionNode.ALLOWED_TYPES.contains(node.type())) {
            throw new IllegalArgumentException(path + " 非法 type=" + node.type()
                    + "；允许: " + CriterionNode.ALLOWED_TYPES);
        }
        rejectSql(node.event(), path + ".event");
        rejectSql(node.drugCodeSet(), path + ".drugCodeSet");
        rejectSql(node.relativeTo(), path + ".relativeTo");
        for (String w : node.window()) {
            rejectSql(w, path + ".window");
        }
        for (var e : node.attrs().entrySet()) {
            rejectSql(e.getKey(), path + ".attrs.key");
            rejectSql(e.getValue(), path + ".attrs.value");
        }
        String childPath = path + "." + node.type().toLowerCase(Locale.ROOT);
        List<CriterionNode> children = node.children() == null ? List.of() : node.children();
        for (int i = 0; i < children.size(); i++) {
            validateNode(children.get(i), childPath + "[" + i + "]");
        }
        if (List.of("AND", "OR", "NOT").contains(node.type()) && children.isEmpty()) {
            throw new IllegalArgumentException(path + " 逻辑节点必须包含 children");
        }
        if (List.of("EXISTS", "NOT_EXISTS", "FIRST", "LAST", "TIME_WITHIN").contains(node.type())
                && blank(node.event())) {
            throw new IllegalArgumentException(path + " 事件节点必须指定 event");
        }
    }

    private static void rejectSql(String value, String field) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (SQL_SMELL.matcher(value).find()) {
            throw new IllegalArgumentException(
                    "禁止在 Cohort/Criterion 中内嵌 SQL 片段: " + field);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 收集叶子事件 code，供编译器绑定物理表。 */
    public static List<String> collectEvents(CohortSpec spec) {
        List<String> out = new ArrayList<>();
        out.add(spec.anchor().event());
        walk(spec.include(), out);
        walk(spec.exclude(), out);
        return out.stream().distinct().toList();
    }

    private static void walk(List<CriterionNode> nodes, List<String> out) {
        for (CriterionNode n : nodes) {
            if (n.event() != null && !n.event().isBlank()) {
                out.add(n.event());
            }
            walk(n.children(), out);
        }
    }
}
