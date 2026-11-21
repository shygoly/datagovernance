package com.datagov.app.autonomy;

import com.datagov.control.service.JobDefinitionService;
import com.datagov.runtime.autonomy.FlowActivity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class JobPauseActivity implements FlowActivity {

    private final JobDefinitionService jobDefinitionService;

    public JobPauseActivity(JobDefinitionService jobDefinitionService) {
        this.jobDefinitionService = jobDefinitionService;
    }

    @Override
    public String ref() {
        return "control.job.pause";
    }

    @Override
    public Map<String, Object> dryRun(FlowActivityContext ctx) {
        String jobId = str(ctx.params().get("jobId"));
        Map<String, Object> out = new LinkedHashMap<>();
        if (jobId.isBlank()) {
            out.put("ready", false);
            out.put("error", "缺少 jobId");
            return out;
        }
        try {
            var job = jobDefinitionService.get(jobId);
            out.put("ready", true);
            out.put("jobId", jobId);
            out.put("currentStatus", job.status());
            out.put("effect", "PAUSE_SCHEDULE");
            return out;
        } catch (RuntimeException ex) {
            out.put("ready", false);
            out.put("error", ex.getMessage());
            return out;
        }
    }

    @Override
    public Map<String, Object> execute(FlowActivityContext ctx) {
        String jobId = require(ctx.params().get("jobId"), "jobId");
        var view = jobDefinitionService.pauseSchedule(jobId);
        return Map.of("jobId", jobId, "status", view.status(), "reason",
                str(ctx.params().get("reason")));
    }

    @Override
    public Map<String, Object> compensate(FlowActivityContext ctx) {
        String jobId = require(ctx.params().get("jobId"), "jobId");
        var view = jobDefinitionService.resumeSchedule(jobId);
        return Map.of("compensated", true, "action", "RESUME_SYNC_JOB",
                "jobId", jobId, "status", view.status());
    }

    private static String require(Object raw, String field) {
        String value = str(raw);
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少参数 " + field);
        }
        return value;
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
