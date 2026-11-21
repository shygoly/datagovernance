package com.datagov.runtime.etl;

import java.util.Map;

/**
 * 一次 ETL 执行的统计摘要。只有计数与比例,不含任何行内容。
 *
 * @param badRows       被跳过 / 路由的坏行总数(含码表 BAD_ROW 未命中)
 * @param badRowRate    badRows / (进入 Sink 的行 + 码表阶段剔除的行);无行时为 0
 * @param codesetMisses 码表映射节点 id → 未命中行数(与 onMiss 策略无关)
 */
public record EtlRunSummary(String engine, long rowsRead, long rowsWritten, long badRows,
                            double badRowRate, Map<String, Long> codesetMisses,
                            String errorPolicyMode) {

    public EtlRunSummary {
        codesetMisses = codesetMisses == null ? Map.of() : Map.copyOf(codesetMisses);
    }

    public static double rate(long badRows, long totalRows) {
        return totalRows <= 0 ? 0.0 : Math.min(1.0, (double) badRows / totalRows);
    }
}
