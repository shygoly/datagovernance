package com.datagov.app.autonomy;

import com.datagov.metadata.service.QualityService;
import com.datagov.runtime.autonomy.FlowActivity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class QualityRunActivity implements FlowActivity {

    private final QualityService qualityService;

    public QualityRunActivity(QualityService qualityService) {
        this.qualityService = qualityService;
    }

    @Override
    public String ref() {
        return "metadata.quality.run";
    }

    @Override
    public Map<String, Object> dryRun(FlowActivityContext ctx) {
        String datasetId = str(ctx.params().get("datasetId"));
        String ruleId = str(ctx.params().get("ruleId"));
        Map<String, Object> out = new LinkedHashMap<>();
        if (datasetId.isBlank() && ruleId.isBlank()) {
            out.put("ready", false);
            out.put("error", "缺少 datasetId 或 ruleId");
            return out;
        }
        if (!datasetId.isBlank()) {
            out.put("rules", qualityService.listRules(1, 100, datasetId, null).records());
        }
        out.put("ready", true);
        out.put("effect", "RUN_QUALITY_RULES");
        return out;
    }

    @Override
    public Map<String, Object> execute(FlowActivityContext ctx) {
        return AutonomyExecutionGuard.run(() -> {
            String datasetId = str(ctx.params().get("datasetId"));
            String ruleId = str(ctx.params().get("ruleId"));
            if (!ruleId.isBlank()) {
                return Map.of("ruleId", ruleId, "run", qualityService.runRule(ruleId));
            }
            if (datasetId.isBlank()) {
                throw new IllegalArgumentException("缺少 datasetId 或 ruleId");
            }
            List<QualityService.RunView> runs = qualityService.runDataset(datasetId);
            return Map.of("datasetId", datasetId, "runs", runs);
        });
    }

    @Override
    public Map<String, Object> compensate(FlowActivityContext ctx) {
        return Map.of("compensated", false, "reason", "质检为只读扫描，无副作用需补偿");
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
