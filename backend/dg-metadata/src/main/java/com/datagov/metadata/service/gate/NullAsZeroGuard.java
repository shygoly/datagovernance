package com.datagov.metadata.service.gate;

import java.util.List;
import java.util.regex.Pattern;

/**
 * W3 统计正确性：禁止把 NULL 静默变成 0（除非度量/映射显式声明 {@code null_as_zero=true}）。
 */
public final class NullAsZeroGuard {

    private static final Pattern NULL_AS_ZERO = Pattern.compile(
            "(?i)\\b(COALESCE|IFNULL|NVL)\\s*\\([^)]*?\\b0\\s*\\)");

    /**
     * @param sqlFragment 映射 expression_sql 或待发射的度量表达式片段
     * @param nullAsZeroAllowed 来自度量 Spec / 映射元数据的显式许可
     */
    public void assertAllowed(String sqlFragment, boolean nullAsZeroAllowed) {
        if (sqlFragment == null || sqlFragment.isBlank() || nullAsZeroAllowed) {
            return;
        }
        if (NULL_AS_ZERO.matcher(sqlFragment).find()) {
            throw new CompileRejectException(CompileReject.of(
                    SemanticCompileErrorCode.NULL_AS_ZERO_FORBIDDEN,
                    "禁止将 NULL 静默替换为 0（检测到 COALESCE/IFNULL/NVL(..., 0)）",
                    "$.expression",
                    List.of("移除填零", "或在度量 Spec 中设置 null_as_zero=true 并经人审"),
                    "删除 COALESCE/IFNULL/NVL(...,0)；确需填零时在 Spec 声明 null_as_zero=true"));
        }
    }
}
