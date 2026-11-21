package com.datagov.app.autonomy;

import com.datagov.governance.service.DecisionTraceService;
import com.datagov.runtime.autonomy.CompiledFlowPlan;
import com.datagov.runtime.autonomy.FlowInstanceView;
import com.datagov.runtime.autonomy.FlowObserver;
import com.datagov.runtime.autonomy.FlowStep;
import com.datagov.runtime.autonomy.FlowTriggerContext;
import com.datagov.runtime.autonomy.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 把 Runtime 的实例推进投影到 Governance 的 DecisionTrace。
 * Runtime 不认识 Governance，只发观察事件；这是 Space 边界的具体落点。
 */
@Component
public class AutonomyTraceObserver implements FlowObserver {

    private static final Logger log = LoggerFactory.getLogger(AutonomyTraceObserver.class);

    private final DecisionTraceService traceService;

    public AutonomyTraceObserver(DecisionTraceService traceService) {
        this.traceService = traceService;
    }

    @Override
    public void onInstanceStarted(FlowInstanceView instance, CompiledFlowPlan plan,
                                  FlowTriggerContext trigger) {
        safely(instance, () -> traceService.bindInstance(
                instance.workspaceId(), instance.traceId(), instance.id()));
    }

    @Override
    public void onStepCompleted(FlowInstanceView instance, FlowStep step, StepResult result) {
        safely(instance, () -> {
            switch (step.kind()) {
                case "DRY_RUN_ACTION" -> traceService.updateDryRun(
                        instance.workspaceId(), instance.traceId(), result.output());
                case "ARBITER_DECIDE" -> traceService.updateArbiter(
                        instance.workspaceId(), instance.traceId(), result.output());
                case "INVOKE_ACTIVITY" -> traceService.updateActivity(
                        instance.workspaceId(), instance.traceId(), result.output());
                case "DELAYED_VERIFY" -> {
                    String status = String.valueOf(result.output().get("verifyStatus"));
                    if (!"null".equals(status) && !status.isBlank()) {
                        traceService.updateVerify(instance.workspaceId(), instance.traceId(),
                                status, result.output());
                    }
                }
                default -> {
                }
            }
        });
    }

    @Override
    public void onWaiting(FlowInstanceView instance, FlowStep step, StepResult result) {
        safely(instance, () -> {
            Object proposalId = instance.checkpoint().get("proposalId");
            if (proposalId != null && !String.valueOf(proposalId).isBlank()) {
                traceService.updateProposal(instance.workspaceId(), instance.traceId(),
                        String.valueOf(proposalId));
            }
        });
    }

    @Override
    public void onCompleted(FlowInstanceView instance, Map<String, Object> output) {
        safely(instance, () -> {
            String status = output == null ? null : String.valueOf(output.get("verifyStatus"));
            if (status != null && !"null".equals(status) && !status.isBlank()) {
                traceService.updateVerify(instance.workspaceId(), instance.traceId(), status, output);
                return;
            }
            DecisionTraceService.TraceView trace = traceService.getByTrace(
                    instance.workspaceId(), instance.traceId());
            if (DecisionTraceService.PENDING.equals(trace.verifyStatus())) {
                traceService.updateVerify(instance.workspaceId(), instance.traceId(),
                        DecisionTraceService.INCONCLUSIVE,
                        Map.of("reason", "流程结束但未提交 T+1 观测数据"));
            }
        });
    }

    @Override
    public void onFailed(FlowInstanceView instance, String reason) {
        safely(instance, () -> {
            traceService.updateActivity(instance.workspaceId(), instance.traceId(),
                    Map.of("failed", true, "reason", reason == null ? "" : reason));
            DecisionTraceService.TraceView trace = traceService.getByTrace(
                    instance.workspaceId(), instance.traceId());
            if (DecisionTraceService.PENDING.equals(trace.verifyStatus())) {
                traceService.updateVerify(instance.workspaceId(), instance.traceId(),
                        DecisionTraceService.INCONCLUSIVE,
                        Map.of("reason", reason == null ? "流程失败" : reason));
            }
        });
    }

    @Override
    public void onCompensated(FlowInstanceView instance, FlowStep step, Map<String, Object> result) {
        safely(instance, () -> traceService.updateActivity(instance.workspaceId(), instance.traceId(),
                Map.of("compensatedStep", step.id(),
                        "compensation", result == null ? Map.of() : result)));
    }

    private void safely(FlowInstanceView instance, Runnable action) {
        if (instance == null || instance.traceId() == null || instance.traceId().isBlank()) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.warn("更新决策轨迹失败 instance={} trace={}: {}",
                    instance.id(), instance.traceId(), ex.getMessage());
        }
    }
}
