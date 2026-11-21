package com.datagov.app.autonomy;

import com.datagov.metadata.event.QualityEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 质检失败事件 → TriggerSpec 运行时（P1-6）。
 *
 * <p>只投影通过/失败、违规数与聚合指标，不落行内容（C5）。
 */
@Component
public class AutonomyQualityListener {

    private static final Logger log = LoggerFactory.getLogger(AutonomyQualityListener.class);

    private final AutonomyFlowService flowService;

    public AutonomyQualityListener(AutonomyFlowService flowService) {
        this.flowService = flowService;
    }

    @EventListener
    public void onQualityRunCompleted(QualityEvents.QualityRunCompleted event) {
        if (AutonomyExecutionGuard.active()) {
            return;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", event.status());
        snapshot.put("ruleId", event.ruleId());
        snapshot.put("datasetId", event.datasetId());
        snapshot.put("passed", event.passed());
        snapshot.put("failed", !event.passed());
        snapshot.put("violations", event.violations());
        snapshot.put("metrics", Map.of(
                "failed", !event.passed(),
                "passRate", event.passed() ? 1.0 : 0.0,
                "violations", event.violations() == null ? 0 : event.violations()));
        try {
            flowService.fireEventSource(event.workspaceId(), "QUALITY_FAILURE", snapshot);
        } catch (RuntimeException ex) {
            log.error("质检自治触发器执行失败 rule={}", event.ruleId(), ex);
        }
    }
}
