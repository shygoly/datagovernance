package com.datagov.app.autonomy;

import com.datagov.control.event.ControlEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ETL 异常 → TriggerSpec 运行时（lightweight-etl-pipeline P2-3c）。
 *
 * <p>替换原先只写审计的桩：事件现在进入统一自治编排，由配置的 ETL_ANOMALY
 * 触发器决定是否启动 Flow。原 {@code EtlOpsTriggerBridge} 仍保留审计兜底，
 * 但不再是唯一动作。
 */
@Component
public class AutonomyEtlAnomalyListener {

    private static final Logger log = LoggerFactory.getLogger(AutonomyEtlAnomalyListener.class);

    private final AutonomyFlowService flowService;

    public AutonomyEtlAnomalyListener(AutonomyFlowService flowService) {
        this.flowService = flowService;
    }

    @EventListener
    public void onEtlExecutionCompleted(ControlEvents.EtlExecutionCompleted event) {
        if (AutonomyExecutionGuard.active()) {
            return;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", event.status());
        snapshot.put("jobId", event.jobId());
        snapshot.put("executionId", event.executionId());
        snapshot.put("rowsRead", event.rowsRead());
        snapshot.put("rowsWritten", event.rowsWritten());
        snapshot.put("badRows", event.badRows());
        snapshot.put("badRowRate", event.badRowRate());
        snapshot.put("codesetMisses", event.codesetMisses());
        snapshot.put("errorPolicyMode", event.errorPolicyMode());
        snapshot.put("metrics", Map.of(
                "badRowRate", event.badRowRate(),
                "badRows", event.badRows(),
                "rowsRead", event.rowsRead(),
                "rowsWritten", event.rowsWritten()));
        try {
            flowService.fireEventSource(event.workspaceId(), "ETL_ANOMALY", snapshot);
        } catch (RuntimeException ex) {
            log.error("ETL 自治触发器执行失败 execution={}", event.executionId(), ex);
        }
    }
}
