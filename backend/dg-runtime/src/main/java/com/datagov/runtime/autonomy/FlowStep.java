package com.datagov.runtime.autonomy;

import java.util.List;
import java.util.Map;

/**
 * FlowSpec IR 的一个步骤（已通过 FlowCompiler 静态校验）。
 *
 * <p>字段保持扁平而不是任意 JSON，是为了让 Runtime 能持久化 current_step_id、
 * 审批超时与延迟回检，而不必理解每种步骤的业务参数。
 */
public record FlowStep(
        String id,
        String kind,
        List<String> metrics,
        String targetRef,
        String actionTypeRef,
        boolean fallbackToL2,
        Integer timeoutSec,
        String skipWhen,
        String compensateWith,
        Integer delaySec,
        String verifyTargetRef,
        Map<String, Object> params
) {
    public FlowStep {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
