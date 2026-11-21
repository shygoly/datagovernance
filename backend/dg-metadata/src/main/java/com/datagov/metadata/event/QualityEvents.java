package com.datagov.metadata.event;

import java.time.Instant;
import java.util.Map;

/**
 * 质检运行事实事件（供数据自治 Trigger 订阅，data-driven-ops-profile P1-6）。
 *
 * <p>只携带通过/失败、违规计数与指标聚合，不携带任何行内容（C5）。
 */
public final class QualityEvents {

    private QualityEvents() {
    }

    public record QualityRunCompleted(
            String workspaceId,
            String ruleId,
            String datasetId,
            String columnId,
            String ruleType,
            String severity,
            String status,
            boolean passed,
            Long violations,
            Map<String, Object> metric,
            Instant occurredAt
    ) {
        public QualityRunCompleted {
            metric = metric == null ? Map.of() : Map.copyOf(metric);
        }
    }
}
