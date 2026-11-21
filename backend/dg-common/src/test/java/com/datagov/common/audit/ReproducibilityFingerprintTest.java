package com.datagov.common.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class ReproducibilityFingerprintTest {

    @Test
    @DisplayName("完整指纹通过 requireComplete")
    void completePasses() {
        ReproducibilityFingerprint fp = new ReproducibilityFingerprint(
                1,
                List.of(Map.of("datasetId", "ds1", "snapshotRef", "live")),
                List.of(Map.of("code", "m.gmv", "versionNo", "1", "contentDigest", "abc")),
                List.of(),
                List.of(Map.of("operatorId", "SemanticQueryCompiler", "version", "1", "digest", "x")),
                "a".repeat(64),
                "b".repeat(64),
                List.of(Map.of("principalType", "USER", "id", "u1")),
                Map.of("truncated", false, "maxRows", 200),
                "2026-11-20T00:00:00Z");
        assertThatCode(fp::requireComplete).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("缺 sqlDigest / truncation 时契约失败")
    void incompleteFails() {
        ReproducibilityFingerprint missingSql = new ReproducibilityFingerprint(
                1, List.of(), List.of(), List.of(), List.of(),
                "", "gate", List.of(), Map.of("truncated", false), "2026-11-20T00:00:00Z");
        assertThatThrownBy(missingSql::requireComplete)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sqlDigest");

        ReproducibilityFingerprint missingTrunc = new ReproducibilityFingerprint(
                1, List.of(), List.of(), List.of(), List.of(),
                "sql", "gate", List.of(), Map.of(), "2026-11-20T00:00:00Z");
        assertThatThrownBy(missingTrunc::requireComplete)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("truncation");
    }
}
