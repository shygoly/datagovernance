package com.datagov.app.autonomy;

import com.datagov.control.event.ControlEvents;
import com.datagov.metadata.event.QualityEvents;
import com.datagov.runtime.autonomy.FlowVerifyObservationProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实的 T+1 观测源：订阅 ETL 完成与质检完成事件，按
 * {@code workspace + jobId/datasetId/ruleId} 保留最新聚合指标。
 *
 * <p>只保留计数与比例，不保留行内容（C5）。Provider 只在事件发生时间不早于
 * 当前 Flow 实例开始时间时返回，避免用旧事件“伪造”本次动作后的改善。
 */
@Component
public class EtlQualityVerifyObservationProvider implements FlowVerifyObservationProvider {

    private final Map<String, Entry> etl = new ConcurrentHashMap<>();
    private final Map<String, Entry> quality = new ConcurrentHashMap<>();

    private record Entry(Map<String, Object> metrics, Instant observedAt, String source,
                         Map<String, Object> detail) {
    }

    @Override
    public String name() {
        return "etl-quality-observation";
    }

    @EventListener
    public void onEtlCompleted(ControlEvents.EtlExecutionCompleted event) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("badRowRate", event.badRowRate());
        metrics.put("badrowrate", event.badRowRate());
        metrics.put("badRows", event.badRows());
        metrics.put("rowsRead", event.rowsRead());
        metrics.put("rowsWritten", event.rowsWritten());
        metrics.put("etlStatus", event.status());
        Entry entry = new Entry(metrics, event.occurredAt(), "ETL_EXECUTION",
                Map.of("jobId", event.jobId(), "executionId", event.executionId()));
        etl.put(key(event.workspaceId(), event.jobId()), entry);
        etl.put(key(event.workspaceId(), event.executionId()), entry);
    }

    @EventListener
    public void onQualityCompleted(QualityEvents.QualityRunCompleted event) {
        double passRate = event.passed() ? 1.0 : 0.0;
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("passRate", passRate);
        metrics.put("passrate", passRate);
        metrics.put("quality_pass_rate", passRate);
        metrics.put("failed", !event.passed());
        metrics.put("violations", event.violations() == null ? 0 : event.violations());
        Entry entry = new Entry(metrics, event.occurredAt(), "QUALITY_RUN",
                Map.of("ruleId", event.ruleId(), "datasetId", event.datasetId(),
                        "ruleType", event.ruleType()));
        quality.put(key(event.workspaceId(), event.datasetId()), entry);
        quality.put(key(event.workspaceId(), event.ruleId()), entry);
    }

    @Override
    public Optional<Observation> observe(Context context) {
        Map<String, Object> explicit = map(context.triggerSnapshot().get("verifyAfterMetrics"));
        if (explicit.isEmpty()) {
            explicit = map(context.triggerSnapshot().get("afterMetrics"));
        }
        if (!explicit.isEmpty()) {
            return Optional.of(new Observation(explicit, "TRIGGER_SNAPSHOT",
                    Map.of("reason", "explicit afterMetrics in trigger snapshot")));
        }
        String jobId = str(context.triggerSnapshot().get("jobId"));
        Entry entry = lookup(etl, context.workspaceId(), jobId,
                str(context.triggerSnapshot().get("executionId")));
        if (entry == null) {
            entry = lookup(quality, context.workspaceId(),
                    str(context.triggerSnapshot().get("datasetId")),
                    str(context.triggerSnapshot().get("ruleId")));
        }
        if (entry == null || entry.observedAt() == null) {
            return Optional.empty();
        }
        if (context.startedAt() != null && entry.observedAt().isBefore(context.startedAt())) {
            return Optional.empty();
        }
        return Optional.of(new Observation(entry.metrics(), entry.source(), entry.detail()));
    }

    private Entry lookup(Map<String, Entry> store, String workspaceId, String... keys) {
        for (String key : keys) {
            if (!key.isBlank()) {
                Entry entry = store.get(key(workspaceId, key));
                if (entry != null) {
                    return entry;
                }
            }
        }
        return null;
    }

    private static String key(String workspaceId, String id) {
        return workspaceId + "|" + id;
    }

    private static Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map<?, ?> value)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        value.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }
}
