package com.datagov.runtime.autonomy;

import java.util.List;
import java.util.Map;

/**
 * FlowCompiler 的编译产物，也是 {@link DurableFlowEngine} 唯一接受的执行契约。
 *
 * <p>它只包含“如何编排”与已解析的引用，不包含业务实体；Runtime 不需要理解
 * Target / ActionType 的语义，具体求值由装配层注册的 {@link FlowStepHandler} 完成。
 */
public record CompiledFlowPlan(
        String flowId,
        String flowCode,
        int flowVersion,
        String runMode,
        String entityKeyExpr,
        int cooldownSec,
        String arbiterPolicyRef,
        List<FlowStep> steps,
        String planJson,
        String contentDigest,
        Map<String, Object> refs
) {
    public CompiledFlowPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
        refs = refs == null ? Map.of() : Map.copyOf(refs);
    }
}
