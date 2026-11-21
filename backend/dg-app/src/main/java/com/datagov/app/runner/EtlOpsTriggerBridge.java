package com.datagov.app.runner;

import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.control.event.ControlEvents;
import com.datagov.governance.service.AuditService;
import com.datagov.governance.service.AuditService.AuditEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * P2-3b 的最小自治联动桩:订阅 {@link ControlEvents.EtlExecutionCompleted},发现「数据管道异常」时
 * 留下一条运维可查的记录。
 *
 * <p><b>TriggerSpec 运行时已由 {@link com.datagov.app.autonomy.AutonomyEtlAnomalyListener} 接管</b>
 * (data-driven-ops-profile V50)。本类保留为审计兜底：即使自治触发器未配置或执行失败，
 * 异常仍会以 {@code ANOMALY / ETL_EXECUTION} 留痕。这里只做两件事:
 * <ol>
 *   <li>判定异常:{@code status = FAILED_BAD_ROW_LIMIT},或脏数据率 ≥ {@code dg.etl.ops.bad-row-rate-threshold}
 *       (缺省 0.05)且确有坏行;</li>
 *   <li>记 WARN 日志 + 在 {@code gv_audit_record} 追加一条 {@code ANOMALY / ETL_EXECUTION} 记录
 *       (只含计数与比例,不含任何行内容,C5)。</li>
 * </ol>
 * 它<b>不</b>创建 {@code gv_alert}:告警需要挂在 {@code AlertRule} 上,而现有 TriggerType 里没有「ETL 异常」。
 * 真正的副作用由 FlowSpec 决定；本类只负责“异常事实一定可查”。放在装配层是因为
 * Control(事件方)与 Governance(审计方)互相不可见。
 *
 * <p>同步监听;任何失败只记日志,绝不影响已经写完的管道执行。
 */
@Component
public class EtlOpsTriggerBridge {

    private static final Logger log = LoggerFactory.getLogger(EtlOpsTriggerBridge.class);

    static final String ACTION = "ANOMALY";
    static final String RESOURCE_TYPE = "ETL_EXECUTION";
    static final String CODE_BAD_ROW_LIMIT = "ETL_BAD_ROW_LIMIT";
    static final String CODE_BAD_ROW_RATE_HIGH = "ETL_BAD_ROW_RATE_HIGH";

    private final AuditService auditService;
    private final double badRowRateThreshold;

    public EtlOpsTriggerBridge(AuditService auditService,
                               @Value("${dg.etl.ops.bad-row-rate-threshold:0.05}") double badRowRateThreshold) {
        this.auditService = auditService;
        this.badRowRateThreshold = badRowRateThreshold;
    }

    @EventListener
    public void onEtlExecutionCompleted(ControlEvents.EtlExecutionCompleted event) {
        try {
            String code = anomalyCode(event);
            if (code == null) {
                return;
            }
            String detail = "job=%s status=%s rowsRead=%d rowsWritten=%d badRows=%d badRowRate=%.4f threshold=%.4f policy=%s"
                    .formatted(event.jobId(), event.status(), event.rowsRead(), event.rowsWritten(),
                            event.badRows(), event.badRowRate(), badRowRateThreshold, event.errorPolicyMode());
            log.warn("ETL 管道异常 ws={} execution={} code={} {}", event.workspaceId(), event.executionId(),
                    code, detail);

            Caller system = new Caller("system", "system", event.workspaceId(), true, Set.of());
            AuditEntry entry = AuditEntry.of(ACTION, RESOURCE_TYPE, event.executionId())
                    .withSpace("CONTROL")
                    .failed(code)
                    .withRequest("EtlExecutionCompleted " + event.status())
                    .withDetail(detail);
            WorkspaceContext.callAs(system, () -> {
                auditService.record(entry);
                return null;
            });
        } catch (RuntimeException e) {
            log.error("ETL 异常联动处理失败 execution={}", event.executionId(), e);
        }
    }

    /** 非异常返回 null。 */
    String anomalyCode(ControlEvents.EtlExecutionCompleted event) {
        if (ControlEvents.EtlExecutionCompleted.FAILED_BAD_ROW_LIMIT.equals(event.status())) {
            return CODE_BAD_ROW_LIMIT;
        }
        if (event.badRows() > 0 && event.badRowRate() >= badRowRateThreshold) {
            return CODE_BAD_ROW_RATE_HIGH;
        }
        return null;
    }
}
