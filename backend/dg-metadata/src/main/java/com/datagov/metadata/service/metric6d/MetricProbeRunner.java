package com.datagov.metadata.service.metric6d;

import com.datagov.metadata.service.SemanticQueryCompiler.SemanticQuerySpec;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ③c 探针 SMQ 生成器（6 类）；执行与断言由调用方/CI 夹具完成。
 */
@Service
public class MetricProbeRunner {

    public enum ProbeKind {
        FANOUT_PROBE,
        ADDITIVITY_PROBE,
        SEMI_ADDITIVE_PROBE,
        NULL_DIM_PROBE,
        DIV_ZERO_PROBE,
        MAGNITUDE_PROBE
    }

    public record ProbeCase(ProbeKind kind, SemanticQuerySpec spec, String assertion) {
    }

    public List<ProbeCase> buildProbes(String metricCode, String dimensionCode) {
        if (metricCode == null || metricCode.isBlank()) {
            throw new IllegalArgumentException("metricCode 必填");
        }
        String dim = dimensionCode == null || dimensionCode.isBlank() ? null : dimensionCode.trim();
        List<String> dims = dim == null ? List.of() : List.of(dim);
        List<ProbeCase> out = new ArrayList<>();
        out.add(new ProbeCase(ProbeKind.FANOUT_PROBE,
                new SemanticQuerySpec(null, List.of(metricCode), dims, List.of(), null, List.of(), 200),
                "fanout protected total within 1e-6 of base total"));
        out.add(new ProbeCase(ProbeKind.ADDITIVITY_PROBE,
                new SemanticQuerySpec(null, List.of(metricCode), dims, List.of(), null, List.of(), 200, "day", List.of()),
                "day rollup equals week rollup for additive metrics"));
        out.add(new ProbeCase(ProbeKind.SEMI_ADDITIVE_PROBE,
                new SemanticQuerySpec(null, List.of(metricCode), dims, List.of(), null, List.of(), 200),
                "window_groupings latest-per-entity semantics hold"));
        out.add(new ProbeCase(ProbeKind.NULL_DIM_PROBE,
                new SemanticQuerySpec(null, List.of(metricCode), dims, List.of(), null, List.of(), 200),
                "NULL dimension groups do not fan out across facts"));
        out.add(new ProbeCase(ProbeKind.DIV_ZERO_PROBE,
                new SemanticQuerySpec(null, List.of(metricCode), dims, List.of(), null, List.of(), 200),
                "ratio returns NULL not exception when denominator is 0"));
        out.add(new ProbeCase(ProbeKind.MAGNITUDE_PROBE,
                new SemanticQuerySpec(null, List.of(metricCode), dims, List.of(), null, List.of(), 200),
                "value within declared [p05,p95] or type domain"));
        return List.copyOf(out);
    }

    public List<String> kindNames() {
        List<String> names = new ArrayList<>();
        for (ProbeKind k : ProbeKind.values()) {
            names.add(k.name().toLowerCase(Locale.ROOT));
        }
        return names;
    }
}
