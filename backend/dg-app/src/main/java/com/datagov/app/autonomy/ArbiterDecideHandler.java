package com.datagov.app.autonomy;

import com.datagov.control.autonomy.ArbiterService;
import com.datagov.control.autonomy.ArbiterService.ArbiterDecision;
import com.datagov.runtime.autonomy.FlowStepContext;
import com.datagov.runtime.autonomy.FlowStepHandler;
import com.datagov.runtime.autonomy.FlowTriggerContext;
import com.datagov.runtime.autonomy.StepResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ArbiterDecideHandler implements FlowStepHandler {

    private final ArbiterService arbiterService;

    public ArbiterDecideHandler(ArbiterService arbiterService) {
        this.arbiterService = arbiterService;
    }

    @Override
    public String kind() {
        return "ARBITER_DECIDE";
    }

    @Override
    public StepResult execute(FlowStepContext ctx) {
        Map<String, Object> snapshot = AutonomyStepSupport.map(ctx.checkpoint().get("triggerSnapshot"));
        FlowTriggerContext trigger = new FlowTriggerContext(
                ctx.workspaceId(),
                AutonomyStepSupport.str(snapshot.get("triggerId")),
                AutonomyStepSupport.str(snapshot.get("entityKey")),
                snapshot,
                ctx.metrics(),
                100,
                stringList(snapshot.get("competingFlowCodes")));
        ArbiterDecision decision = arbiterService.decide(
                ctx.workspaceId(), ctx.plan(), trigger, ctx.instanceId());
        Map<String, Object> output = Map.of(
                "status", decision.status(),
                "reasonCode", decision.reasonCode(),
                "detail", decision.detail(),
                "stepId", ctx.step().id());
        return switch (decision.status()) {
            case ArbiterService.REJECTED -> StepResult.failed("Arbiter 否决: " + decision.reasonCode());
            case ArbiterService.SUPPRESSED -> StepResult.completed(Map.of(
                    "suppressed", true, "reasonCode", decision.reasonCode(), "arbiter", output));
            case ArbiterService.ESCALATE_L2 -> StepResult.next(
                    Map.of("requireL2", true, "arbiterDecision", output), output);
            default -> StepResult.next(Map.of("arbiterDecision", output), output);
        };
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    }
}
