package com.datagov.metadata.service.metric6d;

import com.datagov.metadata.service.metric6d.MetricProbeRunner.ProbeCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetricProbeRunnerTest {

    @Test
    @DisplayName("生成 6 类探针 SMQ")
    void buildsSixProbes() {
        MetricProbeRunner runner = new MetricProbeRunner();
        List<ProbeCase> probes = runner.buildProbes("metric.icu.readmit_rate", "attr.org.dept");
        assertThat(probes).hasSize(6);
        assertThat(probes).extracting(p -> p.kind().name())
                .containsExactly(
                        "FANOUT_PROBE", "ADDITIVITY_PROBE", "SEMI_ADDITIVE_PROBE",
                        "NULL_DIM_PROBE", "DIV_ZERO_PROBE", "MAGNITUDE_PROBE");
        assertThat(probes.get(0).spec().metrics()).containsExactly("metric.icu.readmit_rate");
        assertThat(probes.get(0).spec().dimensions()).containsExactly("attr.org.dept");
    }
}
