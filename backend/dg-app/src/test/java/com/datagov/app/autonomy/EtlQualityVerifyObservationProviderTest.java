package com.datagov.app.autonomy;

import com.datagov.control.event.ControlEvents;
import com.datagov.metadata.event.QualityEvents;
import com.datagov.runtime.autonomy.FlowVerifyObservationProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EtlQualityVerifyObservationProviderTest {

    private final EtlQualityVerifyObservationProvider provider = new EtlQualityVerifyObservationProvider();

    @Test
    @DisplayName("ETL 完成事件按 jobId 提供 after-metrics；早于实例开始的事件被忽略")
    void etlObservationRespectsStartTime() {
        Instant started = Instant.now();
        provider.onEtlCompleted(new ControlEvents.EtlExecutionCompleted(
                "ws1", "job_1", "exec_1", "DUCKDB", "SUCCEEDED",
                100, 99, 1, 0.01, Map.of(), "SKIP_BAD_ROWS", started.plusSeconds(1)));

        var fresh = provider.observe(context("job_1", started));
        assertThat(fresh).isPresent();
        assertThat(fresh.get().metrics()).containsEntry("badrowrate", 0.01);
        assertThat(fresh.get().source()).isEqualTo("ETL_EXECUTION");

        var stale = provider.observe(context("job_1", started.plusSeconds(10)));
        assertThat(stale).isEmpty();
    }

    @Test
    @DisplayName("质检完成事件按 datasetId 提供 passRate/failed")
    void qualityObservation() {
        Instant started = Instant.now();
        provider.onQualityCompleted(new QualityEvents.QualityRunCompleted(
                "ws1", "rule_1", "ds_1", null, "NOT_NULL", "HIGH",
                "SUCCEEDED", false, 3L, Map.of(), started.plusSeconds(1)));

        var result = provider.observe(context("missing", started, "ds_1"));
        assertThat(result).isPresent();
        assertThat(result.get().metrics())
                .containsEntry("passRate", 0.0)
                .containsEntry("failed", true)
                .containsEntry("quality_pass_rate", 0.0);
        assertThat(result.get().source()).isEqualTo("QUALITY_RUN");
    }

    private static FlowVerifyObservationProvider.Context context(String jobId, Instant started) {
        return context(jobId, started, null);
    }

    private static FlowVerifyObservationProvider.Context context(String jobId, Instant started,
                                                                 String datasetId) {
        return new FlowVerifyObservationProvider.Context(
                "ws1", "afi_1", "afl_1", "guard", "atr_1", "target_1",
                datasetId == null ? Map.of("jobId", jobId)
                        : Map.of("jobId", jobId, "datasetId", datasetId),
                Map.of(), started, started.plusSeconds(5));
    }
}
