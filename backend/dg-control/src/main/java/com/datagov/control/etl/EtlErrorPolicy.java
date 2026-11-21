package com.datagov.control.etl;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 脏数据策略。
 *
 * @param mode        FAIL_FAST:任一坏行即失败;
 *                    SKIP_BAD_ROWS:跳过坏行,只在 etl_bad_row_batch 留计数与错误摘要(不保留行本体);
 *                    ROUTE_TO_DEAD_LETTER:跳过坏行,同时把行本体(敏感列已掩码)路由到死信存储,
 *                    摘要里带 storage_ref
 * @param maxBadRows  容忍模式下允许的坏行上限,超出仍失败;null 表示不限
 * @param maxBadRatio 容忍模式下允许的坏行比例(0~1),null 表示不限
 */
public record EtlErrorPolicy(String mode, Long maxBadRows, Double maxBadRatio) {

    public static final String FAIL_FAST = "FAIL_FAST";
    public static final String SKIP_BAD_ROWS = "SKIP_BAD_ROWS";
    public static final String ROUTE_TO_DEAD_LETTER = "ROUTE_TO_DEAD_LETTER";

    public static final Set<String> MODES = Set.of(FAIL_FAST, SKIP_BAD_ROWS, ROUTE_TO_DEAD_LETTER);

    public static EtlErrorPolicy defaults() {
        return new EtlErrorPolicy(FAIL_FAST, null, null);
    }

    /**
     * 规范化模式名:大小写不敏感,{@code SKIP} 是 {@code SKIP_BAD_ROWS} 的别名
     * (提案里的写法),null / 空白 = FAIL_FAST。无法识别的值原样(大写)返回,交给调用方报错。
     */
    public static String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return FAIL_FAST;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return "SKIP".equals(upper) ? SKIP_BAD_ROWS : upper;
    }

    /** 是否容忍坏行(跳过而不是失败)。 */
    public boolean toleratesBadRows() {
        return SKIP_BAD_ROWS.equals(mode) || ROUTE_TO_DEAD_LETTER.equals(mode);
    }

    public boolean routesToDeadLetter() {
        return ROUTE_TO_DEAD_LETTER.equals(mode);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", mode);
        if (maxBadRows != null) {
            map.put("maxBadRows", maxBadRows);
        }
        if (maxBadRatio != null) {
            map.put("maxBadRatio", maxBadRatio);
        }
        return map;
    }
}
