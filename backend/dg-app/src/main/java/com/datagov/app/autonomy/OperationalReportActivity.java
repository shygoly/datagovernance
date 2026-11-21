package com.datagov.app.autonomy;

import com.datagov.governance.service.OperationalReportService;
import com.datagov.runtime.autonomy.FlowActivity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OperationalReportActivity implements FlowActivity {

    private final OperationalReportService reportService;

    public OperationalReportActivity(OperationalReportService reportService) {
        this.reportService = reportService;
    }

    @Override
    public String ref() {
        return "governance.report.generate";
    }

    @Override
    public Map<String, Object> dryRun(FlowActivityContext ctx) {
        String reportId = str(ctx.params().get("reportId"));
        Map<String, Object> out = new LinkedHashMap<>();
        if (reportId.isBlank()) {
            out.put("ready", false);
            out.put("error", "缺少 reportId");
            return out;
        }
        out.put("ready", true);
        out.put("reportId", reportId);
        out.put("effect", "GENERATE_OPERATIONAL_REPORT");
        return out;
    }

    @Override
    public Map<String, Object> execute(FlowActivityContext ctx) {
        String reportId = str(ctx.params().get("reportId"));
        if (reportId.isBlank()) {
            throw new IllegalArgumentException("缺少 reportId");
        }
        Object params = ctx.params().get("params");
        Map<String, Object> values = params instanceof Map<?, ?> map
                ? map.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        e -> String.valueOf(e.getKey()), Map.Entry::getValue))
                : Map.of();
        var run = reportService.run(reportId, new OperationalReportService.RunRequest(values));
        return Map.of("reportId", reportId, "runId", run.id(), "status", run.status());
    }

    @Override
    public Map<String, Object> compensate(FlowActivityContext ctx) {
        return Map.of("compensated", false, "reason", "报表产物保留用于审计，不做删除补偿");
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
