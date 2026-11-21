package com.datagov.governance.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shadow / T+1 效果回检（P2-2 / D5）。
 *
 * <p>只比较 Target.objectives 声明的指标，不比较“看起来相关”的指标。
 * 任一目标指标缺失就返回 INCONCLUSIVE（C8），绝不把缺失当 0。
 */
@Service
public class ShadowEvaluator {

    public record ObjectiveResult(
            String metricRef,
            String direction,
            Double threshold,
            Double before,
            Double after,
            String status,
            String detail
    ) {
    }

    public record Evaluation(
            String status,
            List<ObjectiveResult> objectives,
            String reason
    ) {
        public Evaluation {
            objectives = objectives == null ? List.of() : List.copyOf(objectives);
        }
    }

    public Evaluation evaluate(Map<String, Object> targetSpec,
                               Map<String, Object> before,
                               Map<String, Object> after) {
        if (targetSpec == null || before == null || after == null) {
            return new Evaluation(DecisionTraceService.INCONCLUSIVE, List.of(),
                    "缺少 Target 或前后观测数据");
        }
        List<Map<String, Object>> objectives = mapList(targetSpec.get("objectives"));
        if (objectives.isEmpty()) {
            return new Evaluation(DecisionTraceService.INCONCLUSIVE, List.of(),
                    "Target 没有声明 objectives");
        }
        List<ObjectiveResult> results = new ArrayList<>();
        for (Map<String, Object> objective : objectives) {
            String metricRef = str(objective.get("metricRef"));
            String direction = str(objective.get("direction")).toUpperCase(Locale.ROOT);
            Double threshold = number(objective.get("threshold"));
            Double b = number(before.get(metricRef));
            Double a = number(after.get(metricRef));
            if (b == null || a == null) {
                results.add(new ObjectiveResult(metricRef, direction, threshold, b, a,
                        DecisionTraceService.INCONCLUSIVE, "before/after 缺少指标 " + metricRef));
                continue;
            }
            double epsilon = Math.max(1e-9, Math.abs(b) * 0.01);
            String status;
            String detail;
            if ("MAXIMIZE".equals(direction)) {
                if (threshold != null && a < threshold) {
                    status = DecisionTraceService.DEGRADED;
                    detail = "回检值 " + a + " 低于目标阈值 " + threshold;
                } else if (a > b + epsilon) {
                    status = DecisionTraceService.IMPROVED;
                    detail = "提升 " + (a - b);
                } else if (a < b - epsilon) {
                    status = DecisionTraceService.DEGRADED;
                    detail = "下降 " + (b - a);
                } else {
                    status = DecisionTraceService.NO_EFFECT;
                    detail = "变化在噪声阈值内";
                }
            } else if ("MINIMIZE".equals(direction)) {
                if (threshold != null && a > threshold) {
                    status = DecisionTraceService.DEGRADED;
                    detail = "回检值 " + a + " 高于目标阈值 " + threshold;
                } else if (a < b - epsilon) {
                    status = DecisionTraceService.IMPROVED;
                    detail = "下降 " + (b - a);
                } else if (a > b + epsilon) {
                    status = DecisionTraceService.DEGRADED;
                    detail = "上升 " + (a - b);
                } else {
                    status = DecisionTraceService.NO_EFFECT;
                    detail = "变化在噪声阈值内";
                }
            } else {
                status = DecisionTraceService.INCONCLUSIVE;
                detail = "未知 direction: " + direction;
            }
            results.add(new ObjectiveResult(metricRef, direction, threshold, b, a, status, detail));
        }
        String overall;
        if (results.stream().anyMatch(r -> DecisionTraceService.DEGRADED.equals(r.status()))) {
            overall = DecisionTraceService.DEGRADED;
        } else if (results.stream().allMatch(r -> DecisionTraceService.INCONCLUSIVE.equals(r.status()))) {
            overall = DecisionTraceService.INCONCLUSIVE;
        } else if (results.stream().anyMatch(r -> DecisionTraceService.IMPROVED.equals(r.status()))) {
            overall = DecisionTraceService.IMPROVED;
        } else {
            overall = DecisionTraceService.NO_EFFECT;
        }
        return new Evaluation(overall, results, "按 Target.objectives 逐项比较");
    }

    private static List<Map<String, Object>> mapList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> typed = new LinkedHashMap<>();
                map.forEach((k, v) -> typed.put(String.valueOf(k), v));
                out.add(typed);
            }
        }
        return out;
    }

    private static Double number(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
