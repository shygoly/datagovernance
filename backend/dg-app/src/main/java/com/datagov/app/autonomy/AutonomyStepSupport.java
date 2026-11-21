package com.datagov.app.autonomy;

import com.datagov.runtime.autonomy.FlowStepContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 步骤处理器的公共取值逻辑。
 *
 * <p>参数只从触发快照 / 步骤静态参数中解析，审批信号不得携带可修改业务参数的载荷 ——
 * 否则“审批 A”可能被改写成“执行 B”（C7）。
 */
final class AutonomyStepSupport {

    private AutonomyStepSupport() {
    }

    static String actionTypeRef(FlowStepContext ctx) {
        Object fromCheckpoint = ctx.checkpoint().get("actionTypeRef");
        if (fromCheckpoint != null && !String.valueOf(fromCheckpoint).isBlank()) {
            return String.valueOf(fromCheckpoint);
        }
        return ctx.step().actionTypeRef();
    }

    static Map<String, Object> params(FlowStepContext ctx) {
        Map<String, Object> snapshot = map(ctx.checkpoint().get("triggerSnapshot"));
        Map<String, Object> raw = new LinkedHashMap<>();
        Object actionParams = snapshot.get("actionParams");
        if (actionParams instanceof Map<?, ?> map) {
            map.forEach((k, v) -> raw.put(String.valueOf(k), v));
        }
        raw.putAll(ctx.step().params());
        if (raw.isEmpty()) {
            raw.putAll(snapshot);
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        raw.forEach((k, v) -> resolved.put(k, resolve(v, snapshot, ctx.metrics())));
        return resolved;
    }

    static Object resolve(Object value, Map<String, Object> snapshot, Map<String, Object> metrics) {
        if (!(value instanceof String text) || !text.startsWith("${") || !text.endsWith("}")) {
            return value;
        }
        String key = text.substring(2, text.length() - 1).trim();
        if (key.startsWith("trigger.")) {
            key = key.substring("trigger.".length());
        }
        if (metrics.containsKey(key)) {
            return metrics.get(key);
        }
        return snapshot.get(key);
    }

    static Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
