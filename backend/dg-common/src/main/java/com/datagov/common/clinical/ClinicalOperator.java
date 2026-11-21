package com.datagov.common.clinical;

import com.datagov.common.audit.ReproducibilityFingerprint;

import java.util.List;
import java.util.Map;

/**
 * 版本化确定性临床/统计算子 SPI（由 ClinicalStatsRuntime 执行，禁止嵌入 SemanticQueryCompiler）。
 */
public interface ClinicalOperator {

    String id();

    String version();

    Map<String, Object> inputSchema();

    Map<String, Object> outputSchema();

    OperatorResult execute(OperatorRequest request);

    record OperatorRequest(
            String workspaceId,
            List<Map<String, Object>> rows,
            Map<String, Object> params
    ) {
        public OperatorRequest {
            rows = rows == null ? List.of() : List.copyOf(rows);
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    record OperatorResult(
            String operatorId,
            String operatorVersion,
            String operatorDigest,
            List<Map<String, Object>> rows,
            ReproducibilityFingerprint fingerprint
    ) {
        public OperatorResult {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }
}
