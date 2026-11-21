package com.datagov.metadata.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 确定性质量剖析完成事件（C1 / C9 解耦契约）。
 *
 * <p>仅携带聚合统计证据与模式指纹，严禁夹带任何 L3/L4 原始明细行（C5/C9）。
 * Governance Space 订阅此事件以异步生成 Staging 演进提案，不反向向 Control 发出任何命令。
 */
public record DatasetProfileCompletedEvent(
        String profileRunId,
        String workspaceId,
        String datasetId,
        String datasetName,
        String datasetQualifiedName,
        String verdict,
        long totalRows,
        boolean truncated,
        String patternFingerprint,
        List<AnomalyEvidence> anomalies,
        List<String> standardRefs,
        String reproducibilityFingerprint,
        String triggeredBy,
        Instant occurredAt
) {
    public record AnomalyEvidence(
            String dimension,
            String columnName,
            long violationCount,
            double violationRatio,
            String standardRef,
            String summary,
            Map<String, Object> suggestedRuleSpec
    ) {
    }
}
