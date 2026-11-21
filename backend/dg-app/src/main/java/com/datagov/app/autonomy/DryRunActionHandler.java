package com.datagov.app.autonomy;

import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.runtime.autonomy.FlowActivity;
import com.datagov.runtime.autonomy.FlowActivityRegistry;
import com.datagov.runtime.autonomy.FlowStepContext;
import com.datagov.runtime.autonomy.FlowStepHandler;
import com.datagov.runtime.autonomy.StepResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DryRunActionHandler implements FlowStepHandler {

    private final AutonomyLookup lookup;
    private final FlowActivityRegistry activities;

    public DryRunActionHandler(AutonomyLookup lookup, FlowActivityRegistry activities) {
        this.lookup = lookup;
        this.activities = activities;
    }

    @Override
    public String kind() {
        return "DRY_RUN_ACTION";
    }

    @Override
    public StepResult execute(FlowStepContext ctx) {
        String actionTypeRef = AutonomyStepSupport.actionTypeRef(ctx);
        SemanticObject actionType = lookup.requirePublished(ctx.workspaceId(), actionTypeRef, "ACTION_TYPE");
        Map<String, Object> spec = lookup.spec(actionType);
        String activityRef = lookup.activityRef(spec);
        String actionCode = lookup.actionCode(spec);
        Map<String, Object> params = AutonomyStepSupport.params(ctx);
        Map<String, Object> envelope = AutonomyStepSupport.map(spec.get("paramEnvelope"));
        ActionParameterValidator.Result envelopeCheck = ActionParameterValidator.validate(envelope, params);

        Map<String, Object> dryRun;
        boolean ready;
        try {
            if (!envelopeCheck.valid()) {
                throw new IllegalArgumentException("参数包络校验失败: " + envelopeCheck.errors());
            }
            FlowActivity activity = activities.require(activityRef);
            dryRun = activity.dryRun(new FlowActivity.FlowActivityContext(
                    ctx.workspaceId(), ctx.instanceId(), actionTypeRef, actionCode,
                    params, "dry-run", ctx.runMode(), ctx.initiator()));
            ready = !Boolean.FALSE.equals(dryRun.get("ready"));
        } catch (RuntimeException ex) {
            dryRun = Map.of("ready", false, "error", ex.getMessage() == null ? "dry-run failed" : ex.getMessage());
            ready = false;
        }

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("actionTypeRef", actionTypeRef);
        patch.put("actionCode", actionCode);
        patch.put("activityRef", activityRef);
        patch.put("authLevel", lookup.authLevel(spec));
        patch.put("actionParams", params);
        patch.put("dryRunReady", ready);
        patch.put("dryRunResult", dryRun);
        patch.put("paramEnvelope", envelope);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("actionTypeRef", actionTypeRef);
        output.put("actionCode", actionCode);
        output.put("activityRef", activityRef);
        output.put("ready", ready);
        output.put("dryRun", dryRun);
        output.put("runMode", ctx.runMode());

        if (!ready && !"SHADOW".equals(ctx.runMode())) {
            return StepResult.failed("Dry-Run 预演未通过，拒绝执行副作用动作: " + dryRun);
        }
        return StepResult.next(patch, output);
    }
}
