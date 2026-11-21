package com.datagov.metadata.service.metric6d;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ③d 反模式规则库 AP001–AP006（确定性，无 LLM）。
 */
@Service
public class SemanticAntiPatternLinter {

    public record Finding(String code, String message, String path) {
    }

    private static final Pattern ROW_LEVEL_DIV = Pattern.compile(
            "(?i)(?<!SUM\\s*\\()(?<!NULLIF\\s*\\()\\b[a-z_][a-z0-9_]*\\s*/\\s*[a-z_][a-z0-9_]*");
    private static final Pattern DATE_TRUNC = Pattern.compile("(?i)DATE_TRUNC\\s*\\(");
    private static final Pattern COUNT_DISTINCT = Pattern.compile("(?i)COUNT\\s*\\(\\s*DISTINCT");
    private static final Pattern AVG = Pattern.compile("(?i)\\bAVG\\s*\\(");

    public List<Finding> lint(Metric6DSpec spec, Map<String, Object> rawSpec, String visibility) {
        List<Finding> out = new ArrayList<>();
        if (spec == null && (rawSpec == null || rawSpec.isEmpty())) {
            return out;
        }
        String metricType = spec != null && spec.composition() != null
                ? spec.composition().metricType() : null;
        String expr = spec != null && spec.composition() != null
                ? nullToEmpty(spec.composition().expression()) : "";
        String addKind = spec != null && spec.additivity() != null
                ? spec.additivity().kind() : null;

        if ("ratio".equalsIgnoreCase(metricType) || expr.contains("/")) {
            if (ROW_LEVEL_DIV.matcher(expr).find()
                    && !expr.toUpperCase(Locale.ROOT).contains("SUM(")) {
                out.add(new Finding("AP001_ROW_LEVEL_RATIO",
                        "ratio 疑似行级 a/b，应使用 SUM(a)/NULLIF(SUM(b),0)",
                        "composition.expression"));
            }
            if (expr.contains("/") && !expr.toUpperCase(Locale.ROOT).contains("NULLIF")) {
                out.add(new Finding("AP002_RATIO_MISSING_NULLIF",
                        "除法分母缺少 NULLIF(...,0)",
                        "composition.expression"));
            }
        }

        if ("semi_additive".equalsIgnoreCase(addKind)) {
            boolean noWindow = spec.additivity().windowGroupings() == null
                    || spec.additivity().windowGroupings().isEmpty();
            boolean noDim = spec.additivity().nonAdditiveDim() == null
                    || spec.additivity().nonAdditiveDim().isEmpty();
            if (noWindow && noDim) {
                out.add(new Finding("AP003_SEMI_ADDITIVE_NO_WINDOW",
                        "semi_additive 缺少 window_groupings / non_additive_dim",
                        "additivity"));
            }
        }

        if (DATE_TRUNC.matcher(expr).find()) {
            out.add(new Finding("AP004_HARDCODED_DATE_TRUNC",
                    "表达式硬编码 DATE_TRUNC，应走 time_calendar",
                    "composition.expression"));
        }

        if (visibility != null && "PRIVATE".equalsIgnoreCase(visibility.trim())) {
            out.add(new Finding("AP005_PRIVATE_DIMENSION",
                    "DIMENSION/切片维不可设为 PRIVATE",
                    "visibility"));
        }

        if ("additive".equalsIgnoreCase(addKind)) {
            if (COUNT_DISTINCT.matcher(expr).find() || AVG.matcher(expr).find()
                    || "ratio".equalsIgnoreCase(metricType)) {
                out.add(new Finding("AP006_ADDITIVE_ON_NON_ADDITIVE_OP",
                        "COUNT DISTINCT / AVG / RATIO 不可标为 additive",
                        "additivity.kind"));
            }
        }
        return out;
    }

    public void assertClean(Metric6DSpec spec, Map<String, Object> rawSpec, String visibility) {
        List<Finding> findings = lint(spec, rawSpec, visibility);
        if (!findings.isEmpty()) {
            Finding first = findings.get(0);
            throw new SemanticGraphGateException(first.code(),
                    first.code() + ": " + first.message(),
                    findings.stream().map(Finding::code).toList());
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
