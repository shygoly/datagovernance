package com.datagov.control.autonomy;

import com.datagov.control.entity.AutonomyControlEntities.TriggerDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 声明式触发器求值（P1-6）。
 *
 * <p>所有比较都是确定性数值/枚举比较，不调用 LLM。证据不足时显式返回
 * INCONCLUSIVE，而不是把缺失指标当成 0（C8）。
 */
@Service
public class TriggerEvaluationService {

    private final ObjectMapper json;

    public TriggerEvaluationService(ObjectMapper json) {
        this.json = json;
    }

    public record Evaluation(
            boolean fired,
            String reason,
            String sourceType,
            Map<String, Object> snapshot,
            Map<String, Object> metrics
    ) {
        public Evaluation {
            snapshot = snapshot == null ? Map.of() : Map.copyOf(snapshot);
            metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        }
    }

    public Evaluation evaluate(TriggerDefinition trigger, Map<String, Object> snapshot, Instant now) {
        if (trigger == null || !Boolean.TRUE.equals(trigger.getEnabled())) {
            return new Evaluation(false, "DISABLED", trigger == null ? null : trigger.getSourceType(),
                    snapshot, Map.of());
        }
        int debounce = trigger.getDebounceSec() == null ? 0 : trigger.getDebounceSec();
        if (debounce > 0 && trigger.getLastFiredAt() != null
                && trigger.getLastFiredAt().plus(Duration.ofSeconds(debounce)).isAfter(now)) {
            return new Evaluation(false, "DEBOUNCED", trigger.getSourceType(), snapshot, Map.of());
        }
        Map<String, Object> condition = readMap(trigger.getConditionJson());
        Map<String, Object> metrics = extractMetrics(snapshot);
        return switch (trigger.getSourceType()) {
            case "METRIC_THRESHOLD" -> metric(condition, metrics, snapshot);
            case "QUALITY_FAILURE" -> quality(condition, metrics, snapshot);
            case "ETL_ANOMALY" -> etl(condition, metrics, snapshot);
            case "DATA_ARRIVAL" -> arrival(condition, metrics, snapshot);
            default -> new Evaluation(false, "UNSUPPORTED_SOURCE", trigger.getSourceType(), snapshot, metrics);
        };
    }

    private Evaluation metric(Map<String, Object> condition, Map<String, Object> metrics,
                              Map<String, Object> snapshot) {
        String ref = nonBlank(str(condition.get("metricRef")), str(condition.get("metric")));
        Double actual = number(metrics.get(ref));
        if (actual == null) {
            actual = number(snapshot.get(ref));
        }
        Double threshold = number(condition.get("threshold"));
        if (actual == null || threshold == null) {
            return new Evaluation(false, "INCONCLUSIVE", "METRIC_THRESHOLD", snapshot, metrics);
        }
        String op = str(condition.get("operator")).toUpperCase(Locale.ROOT);
        boolean fired = compare(actual, threshold, op);
        Map<String, Object> merged = new LinkedHashMap<>(metrics);
        merged.put(ref, actual);
        merged.put("threshold", threshold);
        merged.put("operator", op);
        return new Evaluation(fired, fired ? "THRESHOLD_MET" : "THRESHOLD_NOT_MET",
                "METRIC_THRESHOLD", snapshot, merged);
    }

    private Evaluation quality(Map<String, Object> condition, Map<String, Object> metrics,
                               Map<String, Object> snapshot) {
        Double passRate = number(metrics.get("passRate"));
        if (passRate == null) {
            passRate = number(snapshot.get("passRate"));
        }
        Boolean failed = bool(metrics.get("failed"));
        Double maxPassRate = number(condition.get("maxPassRate"));
        boolean fired;
        if (failed != null && Boolean.TRUE.equals(condition.get("failed"))) {
            fired = failed;
        } else if (passRate != null && maxPassRate != null) {
            fired = passRate <= maxPassRate;
        } else {
            return new Evaluation(false, "INCONCLUSIVE", "QUALITY_FAILURE", snapshot, metrics);
        }
        Map<String, Object> merged = new LinkedHashMap<>(metrics);
        if (passRate != null) {
            merged.put("passRate", passRate);
        }
        return new Evaluation(fired, fired ? "QUALITY_FAILURE" : "QUALITY_OK",
                "QUALITY_FAILURE", snapshot, merged);
    }

    private Evaluation etl(Map<String, Object> condition, Map<String, Object> metrics,
                           Map<String, Object> snapshot) {
        String status = str(snapshot.get("status"));
        List<String> statuses = stringList(condition.get("statuses"));
        Double rate = number(metrics.get("badRowRate"));
        Double threshold = number(condition.get("badRowRateAtLeast"));
        boolean statusHit = !statuses.isEmpty() && statuses.contains(status);
        boolean rateHit = rate != null && threshold != null && rate >= threshold;
        if (statuses.isEmpty() && threshold == null) {
            return new Evaluation(false, "INVALID_CONDITION", "ETL_ANOMALY", snapshot, metrics);
        }
        if (!statuses.isEmpty() && status.isBlank()) {
            return new Evaluation(false, "INCONCLUSIVE", "ETL_ANOMALY", snapshot, metrics);
        }
        if (threshold != null && rate == null) {
            return new Evaluation(false, "INCONCLUSIVE", "ETL_ANOMALY", snapshot, metrics);
        }
        return new Evaluation(statusHit || rateHit, statusHit || rateHit ? "ETL_ANOMALY" : "ETL_OK",
                "ETL_ANOMALY", snapshot, metrics);
    }

    private Evaluation arrival(Map<String, Object> condition, Map<String, Object> metrics,
                               Map<String, Object> snapshot) {
        Double delay = number(metrics.get("delaySec"));
        if (delay == null) {
            delay = number(snapshot.get("delaySec"));
        }
        Double max = number(condition.get("maxDelaySec"));
        if (delay == null || max == null) {
            return new Evaluation(false, "INCONCLUSIVE", "DATA_ARRIVAL", snapshot, metrics);
        }
        Map<String, Object> merged = new LinkedHashMap<>(metrics);
        merged.put("delaySec", delay);
        return new Evaluation(delay > max, delay > max ? "ARRIVAL_LATE" : "ARRIVAL_OK",
                "DATA_ARRIVAL", snapshot, merged);
    }

    public static boolean compare(double actual, double threshold, String operator) {
        return switch (operator == null ? "" : operator.toUpperCase(Locale.ROOT)) {
            case "LT" -> actual < threshold;
            case "LTE" -> actual <= threshold;
            case "GT" -> actual > threshold;
            case "GTE" -> actual >= threshold;
            case "EQ" -> Double.compare(actual, threshold) == 0;
            case "NE" -> Double.compare(actual, threshold) != 0;
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMetrics(Map<String, Object> snapshot) {
        Object raw = snapshot == null ? null : snapshot.get("metrics");
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                out.add(String.valueOf(item));
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

    private static Boolean bool(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return null;
    }

    private static String nonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
