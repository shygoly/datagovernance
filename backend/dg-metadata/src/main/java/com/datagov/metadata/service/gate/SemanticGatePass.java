package com.datagov.metadata.service.gate;

/**
 * 门禁通过结果：有效行数上限、截断标记与可纳入指纹的 gateDigest。
 */
public record SemanticGatePass(
        SemanticExecutionMode executionMode,
        int effectiveLimit,
        boolean truncated,
        String gateDigest
) {
}
