package com.datagov.app.autonomy;

import com.datagov.control.autonomy.ActionRateLimitService;
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
public class InvokeActivityHandler implements FlowStepHandler {

    private final AutonomyLookup lookup;
    private final FlowActivityRegistry activities;
    private final ActionRateLimitService rateLimits;

    public InvokeActivityHandler(AutonomyLookup lookup, FlowActivityRegistry activities,
                                 ActionRateLimitService rateLimits) {
        this.lookup = lookup;
        this.activities = activities;
        this.rateLimits = rateLimits;
    }

    @Override
    public String kind() {
        return "INVOKE_ACTIVITY";
    }

    @Override
    public StepResult execute(FlowStepContext ctx) {
        String actionTypeRef = AutonomyStepSupport.actionTypeRef(ctx);
        SemanticObject actionType = lookup.requirePublished(ctx.workspaceId(), actionTypeRef, "ACTION_TYPE");
        Map<String, Object> spec = lookup.spec(actionType);
        String activityRef = lookup.activityRef(spec);
        String actionCode = lookup.actionCode(spec);

        String authLevel = lookup.authLevel(spec);
        if ("SHADOW".equals(ctx.runMode()) || "L0_ADVISORY".equals(authLevel)) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("activityRef", activityRef);
            output.put("actionCode", actionCode);
            output.put("shadowSkipped", true);
            output.put("advisory", "L0_ADVISORY".equals(authLevel));
            output.put("reason", "SHADOW".equals(ctx.runMode())
                    ? "SHADOW 模式只做 Dry-Run，不调用副作用 Activity"
                    : "L0_ADVISORY 只提供建议，不执行副作用");
            return StepResult.next(Map.of("actionExecuted", false), output);
        }

        Map<String, Object> params = AutonomyStepSupport.map(ctx.checkpoint().get("actionParams"));
        if (params.isEmpty()) {
            params = AutonomyStepSupport.params(ctx);
        }
        Map<String, Object> envelope = AutonomyStepSupport.map(spec.get("paramEnvelope"));
        ActionParameterValidator.Result envelopeCheck = ActionParameterValidator.validate(envelope, params);
        if (!envelopeCheck.valid()) {
            return StepResult.failed("参数包络校验失败: " + envelopeCheck.errors());
        }
        int maxPerDay = intValue(envelope.get("maxExecutionsPerDay"), 0);
        boolean consumed = false;
        if (maxPerDay > 0) {
            consumed = rateLimits.tryConsume(ctx.workspaceId(), actionCode, maxPerDay);
            if (!consumed) {
                return StepResult.failed("ActionType " + actionCode
                        + " 超过每日执行上限 " + maxPerDay + "（paramEnvelope.maxExecutionsPerDay）");
            }
        }
        try {
            FlowActivity activity = activities.require(activityRef);
            Map<String, Object> result = activity.execute(new FlowActivity.FlowActivityContext(
                    ctx.workspaceId(), ctx.instanceId(), actionTypeRef, actionCode,
                    params, "execute", ctx.runMode(), ctx.initiator()));
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("activityRef", activityRef);
            output.put("actionCode", actionCode);
            output.put("paramsDigest", Integer.toHexString(String.valueOf(params).hashCode()));
            output.put("result", result);
            output.put("actionExecuted", true);
            output.put("rateLimitConsumed", consumed);
            return StepResult.next(Map.of("actionExecuted", true, "activityResult", output), output);
        } catch (RuntimeException ex) {
            if (consumed) {
                rateLimits.release(ctx.workspaceId(), actionCode);
            }
            return StepResult.failed("Activity " + activityRef + " 执行失败: "
                    + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
    }

    @Override
    public Map<String, Object> compensate(FlowStepContext ctx) {
        String actionTypeRef = AutonomyStepSupport.actionTypeRef(ctx);
        SemanticObject actionType = lookup.requirePublished(ctx.workspaceId(), actionTypeRef, "ACTION_TYPE");
        Map<String, Object> spec = lookup.spec(actionType);
        String activityRef = lookup.activityRef(spec);
        Map<String, Object> params = AutonomyStepSupport.map(ctx.checkpoint().get("actionParams"));
        FlowActivity activity = activities.require(activityRef);
        return activity.compensate(new FlowActivity.FlowActivityContext(
                ctx.workspaceId(), ctx.instanceId(), actionTypeRef, lookup.actionCode(spec),
                params, "compensate", ctx.runMode(), ctx.initiator()));
    }

    private static int intValue(Object raw, int fallback) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
