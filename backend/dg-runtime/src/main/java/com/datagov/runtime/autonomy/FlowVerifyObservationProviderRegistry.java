package com.datagov.runtime.autonomy;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 多观测源合并：后注册的 Provider 不覆盖已有指标，只补充缺失键。
 * 这样 ETL / 质检 / 外部指标源可以同时存在，证据来源记入 detail。
 */
@Component
public class FlowVerifyObservationProviderRegistry {

    private final List<FlowVerifyObservationProvider> providers;

    public FlowVerifyObservationProviderRegistry(List<FlowVerifyObservationProvider> providers) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    public Optional<FlowVerifyObservationProvider.Observation> observe(
            FlowVerifyObservationProvider.Context context) {
        Map<String, Object> merged = new LinkedHashMap<>();
        StringBuilder sources = new StringBuilder();
        Map<String, Object> detail = new LinkedHashMap<>();
        for (FlowVerifyObservationProvider provider : providers) {
            Optional<FlowVerifyObservationProvider.Observation> observation;
            try {
                observation = provider.observe(context);
            } catch (RuntimeException ex) {
                detail.put(provider.name() + ".error", ex.getMessage());
                continue;
            }
            if (observation.isEmpty()) {
                continue;
            }
            observation.get().metrics().forEach(merged::putIfAbsent);
            if (sources.length() > 0) {
                sources.append(",");
            }
            sources.append(observation.get().source());
            detail.putAll(observation.get().detail());
        }
        if (merged.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new FlowVerifyObservationProvider.Observation(
                merged, sources.toString(), detail));
    }

    public List<FlowVerifyObservationProvider> providers() {
        return providers;
    }
}
