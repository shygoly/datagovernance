package com.datagov.app.autonomy;

import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.runtime.autonomy.FlowStepContext;
import com.datagov.runtime.autonomy.FlowStepHandler;
import com.datagov.runtime.autonomy.StepResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WAIT_HUMAN_SIGNAL：SHADOW / L0 / 非冲突的 L1 自动通过；L2/L3 或冲突升级
 * 必须落 V32 提案并由宿主确认后唤醒（P1-9）。
 */
@Component
public class ApprovalSignalHandler implements FlowStepHandler {

    private final AutonomyLookup lookup;
    private final AutonomyApprovalGateway approvalGateway;

    public ApprovalSignalHandler(AutonomyLookup lookup, AutonomyApprovalGateway approvalGateway) {
        this.lookup = lookup;
        this.approvalGateway = approvalGateway;
    }

    @Override
    public String kind() {
        return "WAIT_HUMAN_SIGNAL";
    }

    @Override
    public StepResult execute(FlowStepContext ctx) {
        if ("SHADOW".equals(ctx.runMode())) {
            return StepResult.next(Map.of("approvalSkipped", "SHADOW"),
                    Map.of("shadowSkipped", true, "reason", "SHADOW 模式不产生副作用"));
        }
        String actionTypeRef = AutonomyStepSupport.actionTypeRef(ctx);
        if (actionTypeRef == null || actionTypeRef.isBlank()) {
            return StepResult.next();
        }
        SemanticObject actionType = lookup.requirePublished(ctx.workspaceId(), actionTypeRef, "ACTION_TYPE");
        Map<String, Object> spec = lookup.spec(actionType);
        String authLevel = AutonomyStepSupport.str(ctx.checkpoint().get("authLevel"));
        if (authLevel.isBlank()) {
            authLevel = lookup.authLevel(spec);
        }
        boolean requireL2 = Boolean.TRUE.equals(ctx.checkpoint().get("requireL2"));
        boolean skip = "L0_ADVISORY".equals(authLevel)
                || ("L1_AUTO".equals(authLevel) && !requireL2);
        if (skip) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("approvalSkipped", authLevel);
            output.put("reason", "授权等级不需要人工确认");
            return StepResult.next(Map.of("approvalSkipped", authLevel), output);
        }

        Object existing = ctx.checkpoint().get("proposalId");
        if (existing != null && !String.valueOf(existing).isBlank()) {
            return StepResult.waitApproval(Map.of("proposalId", String.valueOf(existing)),
                    "等待宿主确认提案 " + existing);
        }
        String reason = AutonomyStepSupport.str(
                AutonomyStepSupport.map(ctx.checkpoint().get("triggerSnapshot")).get("reason"));
        String proposalId = approvalGateway.createOrReuse(ctx, actionTypeRef,
                reason.isBlank() ? "自治流程请求执行 " + actionTypeRef : reason);
        return StepResult.waitApproval(Map.of("proposalId", proposalId, "authLevel", authLevel),
                "等待宿主确认提案 " + proposalId);
    }

    @Override
    public StepResult resume(FlowStepContext ctx) {
        if ("APPROVED".equalsIgnoreCase(ctx.signal()) || "VERIFY_DUE".equalsIgnoreCase(ctx.signal())) {
            return StepResult.next(Map.of("approved", true),
                    Map.of("approved", true, "signal", ctx.signal()));
        }
        return StepResult.failed("审批信号未被接受: " + ctx.signal());
    }
}
