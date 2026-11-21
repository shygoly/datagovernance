package com.datagov.app.autonomy;

import com.datagov.runtime.autonomy.FlowStepContext;
import com.datagov.runtime.autonomy.FlowStepHandler;
import com.datagov.runtime.autonomy.StepResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class CaptureContextHandler implements FlowStepHandler {

    @Override
    public String kind() {
        return "CAPTURE_CONTEXT";
    }

    @Override
    public StepResult execute(FlowStepContext ctx) {
        Map<String, Object> captured = new LinkedHashMap<>();
        for (String metric : ctx.step().metrics()) {
            if (ctx.metrics().containsKey(metric)) {
                captured.put(metric, ctx.metrics().get(metric));
            } else if (ctx.triggerSnapshot().containsKey(metric)) {
                captured.put(metric, ctx.triggerSnapshot().get(metric));
            }
        }
        captured.put("triggerId", ctx.plan().flowCode());
        captured.put("entityKey", ctx.triggerSnapshot().get("entityKey"));
        return StepResult.next(Map.of("capturedContext", captured), captured);
    }
}
