package com.datagov.runtime.autonomy;

import java.util.List;
import java.util.Map;

/**
 * 触发器求值后交给 Arbiter / 引擎的只读上下文。
 *
 * <p>snapshot 是触发瞬间的聚合快照（计数、比例、阈值），不是业务明细（C5）。
 */
public record FlowTriggerContext(
        String workspaceId,
        String triggerId,
        String entityKey,
        Map<String, Object> snapshot,
        Map<String, Object> metrics,
        int priority,
        List<String> competingFlowCodes
) {
    public FlowTriggerContext {
        snapshot = snapshot == null ? Map.of() : Map.copyOf(snapshot);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        competingFlowCodes = competingFlowCodes == null ? List.of() : List.copyOf(competingFlowCodes);
    }
}
