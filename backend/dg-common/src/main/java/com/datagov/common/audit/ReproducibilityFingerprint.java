package com.datagov.common.audit;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * C8 可复现审计指纹（对齐 docs/analysis-report.md §10 / ARCHITECTURE §1）。
 *
 * <p>Insight / ClinicalOperator / AnalysisReport / OperationalReportRun 共用同一 schema。
 */
public record ReproducibilityFingerprint(
        int schemaVersion,
        List<Map<String, String>> datasourceSnapshots,
        List<Map<String, String>> semanticObjectVersions,
        List<Map<String, String>> standardVersions,
        List<Map<String, String>> operatorDigests,
        String sqlDigest,
        String gateDigest,
        List<Map<String, String>> delegationChain,
        Map<String, Object> truncation,
        String compiledAt
) {
    public ReproducibilityFingerprint {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1");
        }
        datasourceSnapshots = datasourceSnapshots == null ? List.of() : List.copyOf(datasourceSnapshots);
        semanticObjectVersions = semanticObjectVersions == null ? List.of() : List.copyOf(semanticObjectVersions);
        standardVersions = standardVersions == null ? List.of() : List.copyOf(standardVersions);
        operatorDigests = operatorDigests == null ? List.of() : List.copyOf(operatorDigests);
        delegationChain = delegationChain == null ? List.of() : List.copyOf(delegationChain);
        truncation = truncation == null ? Map.of() : Map.copyOf(truncation);
        sqlDigest = Objects.requireNonNullElse(sqlDigest, "");
        gateDigest = Objects.requireNonNullElse(gateDigest, "");
        compiledAt = Objects.requireNonNullElse(compiledAt, "");
    }

    /** 契约完整性：缺关键字段视为无效（W4 门禁）。 */
    public void requireComplete() {
        if (sqlDigest.isBlank()) {
            throw new IllegalArgumentException("reproducibility_fingerprint.sqlDigest required");
        }
        if (gateDigest.isBlank()) {
            throw new IllegalArgumentException("reproducibility_fingerprint.gateDigest required");
        }
        if (compiledAt.isBlank()) {
            throw new IllegalArgumentException("reproducibility_fingerprint.compiledAt required");
        }
        if (!truncation.containsKey("truncated") && !truncation.containsKey("maxRows")) {
            throw new IllegalArgumentException("reproducibility_fingerprint.truncation required");
        }
    }
}
