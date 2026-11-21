package com.datagov.runtime.autonomy;

import java.util.Map;

/**
 * 单个 Flow 步骤的执行结果。checkpointPatch 会合并进实例 checkpoint，
 * 让等待审批 / 等待 T+1 回检的实例在进程重启后仍能从原处继续。
 */
public record StepResult(
        StepOutcome outcome,
        Map<String, Object> checkpointPatch,
        Map<String, Object> output,
        String reason
) {
    public StepResult {
        checkpointPatch = checkpointPatch == null ? Map.of() : Map.copyOf(checkpointPatch);
        output = output == null ? Map.of() : Map.copyOf(output);
    }

    public static StepResult next() {
        return new StepResult(StepOutcome.CONTINUE, Map.of(), Map.of(), null);
    }

    public static StepResult next(Map<String, Object> patch, Map<String, Object> output) {
        return new StepResult(StepOutcome.CONTINUE, patch, output, null);
    }

    public static StepResult waitApproval(Map<String, Object> patch, String reason) {
        return new StepResult(StepOutcome.WAIT_APPROVAL, patch, Map.of(), reason);
    }

    public static StepResult waitVerify(Map<String, Object> patch, String reason) {
        return new StepResult(StepOutcome.WAIT_VERIFY, patch, Map.of(), reason);
    }

    public static StepResult completed(Map<String, Object> output) {
        return new StepResult(StepOutcome.COMPLETED, Map.of(), output, null);
    }

    public static StepResult shadowCompleted(Map<String, Object> output) {
        return new StepResult(StepOutcome.SHADOW_COMPLETED, Map.of(), output, null);
    }

    public static StepResult failed(String reason) {
        return new StepResult(StepOutcome.FAILED, Map.of(), Map.of(), reason);
    }
}
