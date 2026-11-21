package com.datagov.app.autonomy;

import com.datagov.control.autonomy.ArbiterService;
import com.datagov.control.autonomy.ArbiterService.InvariantResult;
import com.datagov.runtime.autonomy.FlowStepContext;
import com.datagov.runtime.autonomy.FlowStepHandler;
import com.datagov.runtime.autonomy.StepResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CheckInvariantHandler implements FlowStepHandler {

    private final ArbiterService arbiterService;

    public CheckInvariantHandler(ArbiterService arbiterService) {
        this.arbiterService = arbiterService;
    }

    @Override
    public String kind() {
        return "CHECK_INVARIANT";
    }

    @Override
    public StepResult execute(FlowStepContext ctx) {
        List<InvariantResult> results = arbiterService.checkInvariants(
                ctx.workspaceId(), ctx.plan(), ctx.metrics(), ctx.triggerSnapshot());
        List<InvariantResult> blocked = results.stream()
                .filter(r -> "HARD_BLOCK".equals(r.severity()) && !"PASS".equals(r.status()))
                .toList();
        Map<String, Object> output = Map.of("invariants", results);
        if (!blocked.isEmpty()) {
            String reason = blocked.stream()
                    .map(r -> r.code() + "=" + r.status() + "(" + r.detail() + ")")
                    .collect(Collectors.joining("; "));
            return StepResult.failed("硬不变量未通过: " + reason);
        }
        return StepResult.next(Map.of("invariantResults", results), output);
    }
}
