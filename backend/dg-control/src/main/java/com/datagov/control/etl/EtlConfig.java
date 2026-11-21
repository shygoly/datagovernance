package com.datagov.control.etl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 读取节点 config 的小工具:宽进(Jackson 解出来的松散类型),不抛异常。 */
public final class EtlConfig {

    private EtlConfig() {
    }

    public static String str(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    public static String str(Map<String, Object> config, String key, String fallback) {
        String value = str(config, key);
        return value == null ? fallback : value;
    }

    public static List<String> strings(Map<String, Object> config, String key) {
        Object raw = config.get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !item.toString().isBlank()) {
                result.add(item.toString());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, Object> config, String key) {
        Object raw = config.get(key);
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        ((Map<Object, Object>) map).forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    /** 对象数组;非对象元素被丢弃。 */
    public static List<Map<String, Object>> maps(Map<String, Object> config, String key) {
        Object raw = config.get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> copy = new LinkedHashMap<>();
                m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                result.add(copy);
            }
        }
        return result;
    }

    public static Long longVal(Map<String, Object> config, String key) {
        Object raw = config.get(key);
        if (raw == null) {
            return null;
        }
        try {
            return raw instanceof Number n ? n.longValue() : Long.parseLong(raw.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 布尔值:接受 Boolean 与 "true"/"false" 字符串;缺失或无法识别返回 fallback。 */
    public static boolean bool(Map<String, Object> config, String key, boolean fallback) {
        Object raw = config.get(key);
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw != null) {
            String text = raw.toString().trim();
            if (text.equalsIgnoreCase("true")) {
                return true;
            }
            if (text.equalsIgnoreCase("false")) {
                return false;
            }
        }
        return fallback;
    }
}
