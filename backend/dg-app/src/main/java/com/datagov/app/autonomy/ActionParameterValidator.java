package com.datagov.app.autonomy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ActionType.paramEnvelope 的执行期校验（P1-4 / C7）。
 *
 * <p>编译期只能校验“包络存在”，真正参数来自触发快照，必须在 Dry-Run 与执行前
 * 各校验一次：否则审批通过的是 jobId=A，执行时可能被改成 jobId=B。
 */
final class ActionParameterValidator {

    private ActionParameterValidator() {
    }

    record Result(boolean valid, List<String> errors) {
        static Result ok() {
            return new Result(true, List.of());
        }
    }

    static Result validate(Map<String, Object> envelope, Map<String, Object> params) {
        if (envelope == null || envelope.isEmpty()) {
            return Result.ok();
        }
        List<String> errors = new ArrayList<>();
        List<Object> required = list(envelope.get("required"));
        for (Object field : required) {
            String key = String.valueOf(field);
            Object value = params.get(key);
            if (value == null || String.valueOf(value).isBlank()) {
                errors.add("缺少必填参数 " + key);
            }
        }
        Map<String, Object> properties = map(envelope.get("properties"));
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            Object raw = params.get(entry.getKey());
            if (raw == null) {
                continue;
            }
            Map<String, Object> schema = map(entry.getValue());
            String type = str(schema.get("type"));
            if (!type.isBlank() && !matchesType(type, raw)) {
                errors.add("参数 " + entry.getKey() + " 类型应为 " + type);
                continue;
            }
            Double numeric = number(raw);
            Double minimum = number(schema.get("minimum"));
            Double maximum = number(schema.get("maximum"));
            if (numeric != null && minimum != null && numeric < minimum) {
                errors.add("参数 " + entry.getKey() + " 小于最小值 " + minimum);
            }
            if (numeric != null && maximum != null && numeric > maximum) {
                errors.add("参数 " + entry.getKey() + " 大于最大值 " + maximum);
            }
            List<Object> allowed = list(schema.get("enum"));
            if (!allowed.isEmpty() && allowed.stream().noneMatch(v -> String.valueOf(v).equals(String.valueOf(raw)))) {
                errors.add("参数 " + entry.getKey() + " 不在白名单 " + allowed);
            }
            String pattern = str(schema.get("pattern"));
            if (!pattern.isBlank() && !Pattern.compile(pattern).matcher(String.valueOf(raw)).matches()) {
                errors.add("参数 " + entry.getKey() + " 不匹配 pattern");
            }
        }
        return new Result(errors.isEmpty(), List.copyOf(errors));
    }

    private static boolean matchesType(String type, Object raw) {
        return switch (type) {
            case "string" -> raw instanceof String;
            case "integer" -> raw instanceof Integer || raw instanceof Long
                    || (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()));
            case "number" -> raw instanceof Number;
            case "boolean" -> raw instanceof Boolean;
            case "array" -> raw instanceof List<?>;
            case "object" -> raw instanceof Map<?, ?>;
            default -> true;
        };
    }

    private static Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static List<Object> list(Object raw) {
        return raw instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    private static Double number(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s) {
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
