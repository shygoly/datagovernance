package com.datagov.runtime.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按计划里的 {@code engine} 字段挑 {@link EtlExecutionEngine}。缺省 DUCKDB。
 *
 * <p>选择策略与 {@code EngineRegistry} 同样「故意保持愚蠢」:一个标识一个引擎,先注册的赢;
 * 找不到时明确报错,而不是悄悄回落到 DuckDB —— 用户选了 SeaTunnel 却被 DuckDB 跑了,
 * 比失败更糟(结果语义、资源占用都不是他以为的)。
 */
@Component
public class EtlEngineRegistry {

    private static final Logger log = LoggerFactory.getLogger(EtlEngineRegistry.class);

    private final Map<String, EtlExecutionEngine> byId = new LinkedHashMap<>();

    public EtlEngineRegistry(List<EtlExecutionEngine> engines) {
        for (EtlExecutionEngine engine : engines) {
            String id = engine.engineId().toUpperCase(Locale.ROOT);
            EtlExecutionEngine existing = byId.putIfAbsent(id, engine);
            if (existing != null) {
                log.warn("ETL 引擎标识 {} 已由 {} 承接,忽略 {}", id,
                        existing.getClass().getSimpleName(), engine.getClass().getSimpleName());
            }
        }
    }

    /** @param engineId 计划里的 engine 字段;null / 空白 = DUCKDB */
    public EtlExecutionEngine resolve(String engineId) {
        String id = engineId == null || engineId.isBlank()
                ? EtlExecutionEngine.DUCKDB : engineId.trim().toUpperCase(Locale.ROOT);
        EtlExecutionEngine engine = byId.get(id);
        if (engine == null) {
            throw new IllegalArgumentException("没有可用的 ETL 执行引擎: " + id
                    + "(已注册: " + String.join(", ", byId.keySet()) + ")");
        }
        return engine;
    }
}
