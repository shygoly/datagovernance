package com.datagov.app.runner;

import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.control.event.ControlEvents;
import com.datagov.governance.service.AuditService;
import com.datagov.governance.service.AuditService.AuditEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

/**
 * 水位线重置 → 审计({@code gv_audit_record})。
 *
 * <p>{@code WatermarkService.reset} 在 Control Space 里发 {@link ControlEvents.EtlWatermarkReset},
 * 但 Control 不能(也不该)依赖 Governance 的审计服务;只有装配层能把两边接起来。
 * 监听事件而不是在 Controller 里补一行审计,是为了让「任何入口」的重置都留痕,
 * 并且能带上重置前后的值 —— Controller 拿不到「重置前」。
 *
 * <p>事务提交后才记:重置回滚了就不该留下「已重置」的记录。审计写入自身是独立事务,
 * 失败只记日志,不影响重置。
 */
@Component
public class EtlWatermarkAuditListener {

    private static final Logger log = LoggerFactory.getLogger(EtlWatermarkAuditListener.class);

    static final String RESOURCE_TYPE = "ETL_WATERMARK";

    private final AuditService auditService;

    public EtlWatermarkAuditListener(AuditService auditService) {
        this.auditService = auditService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWatermarkReset(ControlEvents.EtlWatermarkReset event) {
        try {
            Caller current = WorkspaceContext.get();
            Caller caller = current != null && event.workspaceId().equals(current.workspaceId())
                    ? current
                    : new Caller(event.operatorId() == null ? "system" : event.operatorId(),
                            event.operatorId() == null ? "system" : event.operatorId(),
                            event.workspaceId(), false, Set.of());
            AuditEntry entry = AuditEntry.of(AuditService.UPDATE, RESOURCE_TYPE,
                            event.jobId() + ":" + event.sourceNodeId())
                    .withSpace("CONTROL")
                    .withRequest("RESET ETL watermark job=" + event.jobId() + " node=" + event.sourceNodeId())
                    .withDetail("reason=" + event.note() + "; previous=" + event.previousValue()
                            + "; new=" + event.newValue());
            WorkspaceContext.callAs(caller, () -> {
                auditService.record(entry);
                return null;
            });
        } catch (RuntimeException e) {
            log.error("水位线重置审计失败 job={} node={}", event.jobId(), event.sourceNodeId(), e);
        }
    }
}
