package com.datagov.runtime.autonomy;

import com.datagov.common.tenant.Caller;

import java.util.Map;

/**
 * Handler 可见的执行上下文。signal 仅在审批/回检唤醒时非空。
 */
public record FlowStepContext(
        String instanceId,
        String workspaceId,
        CompiledFlowPlan plan,
        FlowStep step,
        Map<String, Object> checkpoint,
        Map<String, Object> triggerSnapshot,
        Map<String, Object> metrics,
        String runMode,
        Caller initiator,
        String signal
) {
    public FlowStepContext {
        checkpoint = checkpoint == null ? Map.of() : Map.copyOf(checkpoint);
        triggerSnapshot = triggerSnapshot == null ? Map.of() : Map.copyOf(triggerSnapshot);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }
}
