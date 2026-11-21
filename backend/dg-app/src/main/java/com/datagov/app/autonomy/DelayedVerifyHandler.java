package com.datagov.app.autonomy;

import com.datagov.governance.service.DecisionTraceService;
import com.datagov.governance.service.ShadowEvaluator;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.runtime.autonomy.FlowStepContext;
import com.datagov.runtime.autonomy.FlowStepHandler;
import com.datagov.runtime.autonomy.StepResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DelayedVerifyHandler implements FlowStepHandler {

    private final AutonomyLookup lookup;
    private final ShadowEvaluator shadowEvaluator;

    public DelayedVerifyHandler(AutonomyLookup lookup, ShadowEvaluator shadowEvaluator) {
        this.lookup = lookup;
        this.shadowEvaluator = shadowEvaluator;
    }

    @Override
    public String kind() {
        return "DELAYED_VERIFY";
    }

    @Override
    public StepResult execute(FlowStepContext ctx) {
        Map<String, Object> before = AutonomyStepSupport.map(ctx.checkpoint().get("capturedContext"));
        if (before.isEmpty()) {
            before = ctx.metrics();
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("verifyTargetRef", ctx.step().verifyTargetRef());
        patch.put("verifyBefore", before);
        patch.put("verifyDelaySec", ctx.step().delaySec());
        return StepResult.waitVerify(patch,
                "等待 T+" + ctx.step().delaySec() + " 秒后的观测数据");
    }

    @Override
    public StepResult resume(FlowStepContext ctx) {
        Map<String, Object> payload = AutonomyStepSupport.map(ctx.checkpoint().get("signalPayload"));
        Map<String, Object> after = AutonomyStepSupport.map(payload.get("metrics"));
        if (after.isEmpty()) {
            after = AutonomyStepSupport.map(ctx.checkpoint().get("verifyAfter"));
        }
        Map<String, Object> before = AutonomyStepSupport.map(ctx.checkpoint().get("verifyBefore"));
        if (before.isEmpty()) {
            before = ctx.metrics();
        }
        String targetRef = ctx.step().verifyTargetRef();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("before", before);
        output.put("after", after);
        output.put("targetRef", targetRef);

        if (after.isEmpty()) {
            output.put("verifyStatus", DecisionTraceService.INCONCLUSIVE);
            output.put("reason", "未提交 T+1 观测数据");
            return finish(ctx, output);
        }
        SemanticObject target = lookup.requirePublished(ctx.workspaceId(), targetRef, "TARGET");
        ShadowEvaluator.Evaluation evaluation = shadowEvaluator.evaluate(
                lookup.spec(target), before, after);
        output.put("verifyStatus", evaluation.status());
        output.put("evaluation", evaluation);
        return finish(ctx, output);
    }

    private StepResult finish(FlowStepContext ctx, Map<String, Object> output) {
        if ("SHADOW".equals(ctx.runMode())) {
            return StepResult.shadowCompleted(output);
        }
        return StepResult.completed(output);
    }
}
