package com.datagov.control.autonomy;

import java.util.List;
import java.util.Map;

/**
 * FlowSpec IR（{@code flow.ir/v1}）的强类型快照。
 *
 * <p>这是 Metadata / Control / Runtime / A0 Agent 之间唯一的流程契约。
 * 所有字段都必须可静态校验，禁止在执行期才解析任意脚本。
 */
public record FlowSpec(
        String schemaVersion,
        String flowCode,
        String name,
        String runMode,
        String entityKeyExpr,
        Integer cooldownSec,
        String arbiterPolicyRef,
        List<Step> steps,
        Map<String, Object> metadata
) {
    public FlowSpec {
        steps = steps == null ? List.of() : List.copyOf(steps);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public record Step(
            String id,
            String kind,
            List<String> metrics,
            String targetRef,
            String actionTypeRef,
            Boolean fallbackToL2,
            Integer timeoutSec,
            String skipWhen,
            String compensateWith,
            Integer delaySec,
            String verifyTargetRef,
            Map<String, Object> params
    ) {
        public Step {
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }
}
