package com.datagov.runtime.autonomy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record FlowInstanceView(
        String id,
        String workspaceId,
        String flowId,
        String flowCode,
        int flowVersion,
        String triggerId,
        String traceId,
        String status,
        String currentStepId,
        String entityKey,
        String runMode,
        Map<String, Object> checkpoint,
        List<Map<String, Object>> history,
        Instant wakeAt,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {
    public FlowInstanceView {
        checkpoint = checkpoint == null ? Map.of() : Map.copyOf(checkpoint);
        history = history == null ? List.of() : List.copyOf(history);
    }

    public boolean terminal() {
        return switch (status) {
            case "COMPLETED", "SHADOW_COMPLETED", "FAILED", "CANCELLED" -> true;
            default -> false;
        };
    }
}
