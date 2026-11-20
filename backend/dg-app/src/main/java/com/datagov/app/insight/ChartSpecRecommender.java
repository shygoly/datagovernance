package com.datagov.app.insight;

import com.datagov.app.insight.InsightQueryEngine.InsightColumn;
import com.datagov.metadata.service.SemanticOntologyService.ObjectView;
import com.datagov.metadata.service.SemanticQueryCompiler.SemanticQuerySpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 确定性图表推荐器（遵循 CONSTITUTION C3/C7：声明式白名单 ChartSpec，严禁 LLM 生成可执行前端代码）。
 *
 * <p>依据列语义角色、维度层级（{@code DIMENSION.spec.hierarchy}）、行数、指标个数与度量单位（{@code METRIC.spec.unit}），
 * 推导默认图型、允许切换的白名单图型、是否启用双 Y 轴以及合法的确定性下钻路径。
 */
@Component
public class ChartSpecRecommender {

    private static final Pattern DATE_LABEL = Pattern.compile("^\\d{4}[-/]\\d{2}([-/]\\d{2})?.*$");

    public record DrilldownOption(
            String sourceDimensionCode,
            String sourceField,
            String nextDimensionCode,
            String nextDimensionName,
            String actionLabel
    ) {
    }

    public record ChartSpec(
            String chartType,
            String xField,
            String seriesField,
            List<String> yFields,
            Map<String, String> units,
            boolean dualAxis,
            String defaultSort,
            Integer topN,
            List<String> supportedTypes,
            List<DrilldownOption> drilldowns
    ) {
    }

    public ChartSpec recommend(String state,
                               String question,
                               List<InsightColumn> columns,
                               List<Map<String, Object>> rows,
                               SemanticQuerySpec executedSpec,
                               List<ObjectView> semanticObjects) {
        if ("METADATA".equalsIgnoreCase(state)
                || "INCONCLUSIVE".equalsIgnoreCase(state)
                || "CLARIFY_NEEDED".equalsIgnoreCase(state)) {
            return tableOnly();
        }
        List<InsightColumn> cols = columns == null ? List.of() : columns;
        List<Map<String, Object>> data = rows == null ? List.of() : rows;
        if (cols.isEmpty()) {
            return tableOnly();
        }

        // 区分维度列与数值指标列
        List<InsightColumn> metrics = new ArrayList<>();
        List<InsightColumn> dims = new ArrayList<>();
        for (InsightColumn col : cols) {
            String key = fieldKey(col);
            boolean isNumType = col.type() != null && (
                    col.type().toUpperCase(Locale.ROOT).contains("NUM")
                            || col.type().toUpperCase(Locale.ROOT).contains("INT")
                            || col.type().toUpperCase(Locale.ROOT).contains("DEC")
                            || col.type().toUpperCase(Locale.ROOT).contains("DOUBLE")
                            || col.type().toUpperCase(Locale.ROOT).contains("FLOAT")
                            || col.type().toUpperCase(Locale.ROOT).contains("BIGINT"));
            boolean looksNumData = !data.isEmpty() && data.stream()
                    .anyMatch(r -> isNumeric(r.get(key)));
            if (isNumType || looksNumData) {
                metrics.add(col);
            } else {
                dims.add(col);
            }
        }

        if (metrics.isEmpty()) {
            return tableOnly();
        }

        Map<String, ObjectView> objByCode = new LinkedHashMap<>();
        Map<String, ObjectView> objByAlias = new LinkedHashMap<>();
        if (semanticObjects != null) {
            for (ObjectView o : semanticObjects) {
                if (o.code() != null) {
                    objByCode.put(o.code().toLowerCase(Locale.ROOT), o);
                    objByAlias.put(sanitizeAlias(o.code()), o);
                }
            }
        }

        List<String> yFields = metrics.stream().map(ChartSpecRecommender::fieldKey).toList();
        Map<String, String> units = new LinkedHashMap<>();
        Set<String> distinctUnits = new LinkedHashSet<>();
        for (InsightColumn m : metrics) {
            String key = fieldKey(m);
            ObjectView obj = objByAlias.get(key.toLowerCase(Locale.ROOT));
            String unit = obj != null && obj.spec() != null && obj.spec().get("unit") != null
                    ? String.valueOf(obj.spec().get("unit")).trim()
                    : "";
            if (!unit.isBlank()) {
                units.put(key, unit);
                distinctUnits.add(unit);
            }
        }

        // 启用不同量纲自动双 Y 轴（例如 元 vs %）
        boolean dualAxis = yFields.size() >= 2 && distinctUnits.size() >= 2;
        List<DrilldownOption> drilldowns = deriveDrilldowns(executedSpec, objByCode);

        // 1 行指标结果 → KPI 指标卡
        if (data.size() == 1 && dims.isEmpty() && yFields.size() <= 4) {
            return new ChartSpec("kpi", null, null, yFields, units, false,
                    "NONE", null, List.of("kpi", "table"), drilldowns);
        }

        String q = question == null ? "" : question;

        // 0 维度 × >=2 指标 × >=3 行 → 散点图
        if (dims.isEmpty() && yFields.size() >= 2 && data.size() >= 3) {
            return new ChartSpec("scatter", yFields.get(0), null, yFields, units, false,
                    "NONE", 200, List.of("scatter", "bar", "table"), drilldowns);
        }

        String xField = dims.isEmpty() ? null : fieldKey(dims.get(0));
        String seriesField = dims.size() >= 2 ? fieldKey(dims.get(1)) : null;

        boolean isTime = xField != null && isTimeDimension(xField, data, objByAlias);

        // 1 时间维度 → 折线图（默认支持缩放与面积图切换）
        if (isTime && seriesField == null) {
            return new ChartSpec("line", xField, null, yFields, units, dualAxis,
                    "NONE", null, List.of("line", "area", "bar", "table"), drilldowns);
        }

        // 2 维度 → 堆叠柱图 / 多系列折线
        if (seriesField != null) {
            String type = isTime ? "line" : "stacked_bar";
            return new ChartSpec(type, xField, seriesField, List.of(yFields.get(0)), units, false,
                    isTime ? "NONE" : "DESC", 20,
                    List.of("stacked_bar", "bar", "line", "table"), drilldowns);
        }

        // 1 分类维度
        if (q.contains("漏斗") || q.contains("转化") || q.contains("留存")) {
            return new ChartSpec("funnel", xField, null, List.of(yFields.get(0)), units, false,
                    "DESC", 15, List.of("funnel", "bar", "horizontal_bar", "table"), drilldowns);
        }

        boolean allNonNegative = data.stream().allMatch(r -> toDouble(r.get(yFields.get(0))) >= 0);
        if (yFields.size() == 1 && data.size() >= 2 && data.size() <= 8 && allNonNegative
                && (q.contains("占比") || q.contains("比例") || q.contains("构成") || q.contains("份额"))) {
            return new ChartSpec("pie", xField, null, yFields, units, false,
                    "DESC", 12, List.of("pie", "bar", "horizontal_bar", "table"), drilldowns);
        }

        String defaultType = data.size() > 8 ? "horizontal_bar" : "bar";
        List<String> supported = new ArrayList<>(List.of("bar", "horizontal_bar", "line", "area"));
        if (yFields.size() == 1 && data.size() <= 12 && allNonNegative) {
            supported.add("pie");
        }
        supported.add("funnel");
        supported.add("table");

        return new ChartSpec(defaultType, xField, null, yFields, units, dualAxis,
                "DESC", 20, supported, drilldowns);
    }

    private List<DrilldownOption> deriveDrilldowns(SemanticQuerySpec spec, Map<String, ObjectView> objByCode) {
        if (spec == null || spec.dimensions() == null || spec.dimensions().isEmpty() || objByCode.isEmpty()) {
            return List.of();
        }
        List<DrilldownOption> options = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String dimCode : spec.dimensions()) {
            String dimLower = dimCode.toLowerCase(Locale.ROOT);
            for (ObjectView obj : objByCode.values()) {
                if (!"DIMENSION".equalsIgnoreCase(obj.objectType()) || obj.spec() == null) {
                    continue;
                }
                if (!(obj.spec().get("hierarchy") instanceof List<?> hierarchy) || hierarchy.size() < 2) {
                    continue;
                }
                for (int i = 0; i < hierarchy.size() - 1; i++) {
                    String cur = String.valueOf(hierarchy.get(i)).trim();
                    if (cur.equalsIgnoreCase(dimLower)) {
                        String nextCode = String.valueOf(hierarchy.get(i + 1)).trim();
                        ObjectView nextObj = objByCode.get(nextCode.toLowerCase(Locale.ROOT));
                        String nextName = nextObj != null ? nextObj.name() : nextCode;
                        String key = dimLower + "->" + nextCode.toLowerCase(Locale.ROOT);
                        if (seen.add(key)) {
                            options.add(new DrilldownOption(
                                    dimCode, sanitizeAlias(dimCode), nextCode, nextName,
                                    "下钻至 " + nextName));
                        }
                    }
                }
            }
        }
        return options;
    }

    private boolean isTimeDimension(String xField, List<Map<String, Object>> data, Map<String, ObjectView> objByAlias) {
        ObjectView obj = objByAlias.get(xField.toLowerCase(Locale.ROOT));
        if (obj != null && obj.spec() != null && Boolean.TRUE.equals(obj.spec().get("isTimeDimension"))) {
            return true;
        }
        String lower = xField.toLowerCase(Locale.ROOT);
        if (lower.contains("date") || lower.contains("month") || lower.contains("day") || lower.contains("time")) {
            return true;
        }
        return !data.isEmpty() && data.stream()
                .map(r -> r.get(xField))
                .filter(v -> v != null && !String.valueOf(v).isBlank())
                .allMatch(v -> DATE_LABEL.matcher(String.valueOf(v).trim()).matches());
    }

    private static ChartSpec tableOnly() {
        return new ChartSpec("table", null, null, List.of(), Map.of(), false,
                "NONE", null, List.of("table"), List.of());
    }

    private static String fieldKey(InsightColumn col) {
        return col.bizName() != null && !col.bizName().isBlank() ? col.bizName() : col.name();
    }

    private static String sanitizeAlias(String code) {
        return code.replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(Locale.ROOT);
    }

    private static boolean isNumeric(Object value) {
        if (value == null) return false;
        if (value instanceof Number) return true;
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) return false;
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return 0.0;
        }
    }
}
