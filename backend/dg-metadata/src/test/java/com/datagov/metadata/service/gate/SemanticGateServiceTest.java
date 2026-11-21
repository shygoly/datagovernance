package com.datagov.metadata.service.gate;

import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.service.SemanticQueryCompiler.FilterClause;
import com.datagov.metadata.service.SemanticQueryCompiler.SemanticQuerySpec;
import com.datagov.metadata.service.SemanticQueryCompiler.TimeRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SemanticGate 统一语义门禁（W1）")
class SemanticGateServiceTest {

    private SemanticGateService gate;

    @BeforeEach
    void setUp() {
        gate = new SemanticGateService();
    }

    @ParameterizedTest
    @EnumSource(SemanticExecutionMode.class)
    @DisplayName("三条执行路径对 STANDARD_MISSING 返回同一 error_code")
    void standardMissingSameCodeAcrossModes(SemanticExecutionMode mode) {
        SemanticObject metric = metric("m1", "metric.gmv", null, "{}");
        SemanticGateRequest req = baseRequest(mode, List.of(metric), List.of(),
                new SemanticQuerySpec(null, List.of("metric.gmv"), List.of(), List.of(), null, null, 10),
                Set.of());

        assertThatThrownBy(() -> gate.evaluate(req))
                .isInstanceOf(CompileRejectException.class)
                .satisfies(ex -> {
                    CompileReject reject = ((CompileRejectException) ex).reject();
                    assertThat(reject.errorCode()).isEqualTo(SemanticCompileErrorCode.STANDARD_MISSING.name());
                    assertThat(reject.repairHint()).isNotBlank();
                    assertThat(reject.location()).contains("metrics");
                });
    }

    @ParameterizedTest
    @EnumSource(SemanticExecutionMode.class)
    @DisplayName("三条执行路径对 DIMENSION_NOT_ALLOWED 返回同一 error_code")
    void dimensionNotAllowedSameCodeAcrossModes(SemanticExecutionMode mode) {
        SemanticObject metric = metric("m1", "metric.gmv", "STD-1",
                "{\"allowedDimensions\":[\"attr.region\"]}");
        SemanticObject badDim = dim("d1", "attr.other");
        SemanticGateRequest req = baseRequest(mode, List.of(metric), List.of(badDim),
                new SemanticQuerySpec(null, List.of("metric.gmv"), List.of("attr.other"), List.of(), null, null, 10),
                Set.of());

        assertThatThrownBy(() -> gate.evaluate(req))
                .isInstanceOf(CompileRejectException.class)
                .satisfies(ex -> {
                    CompileReject reject = ((CompileRejectException) ex).reject();
                    assertThat(reject.errorCode()).isEqualTo(SemanticCompileErrorCode.DIMENSION_NOT_ALLOWED.name());
                    assertThat(reject.allowed()).contains("attr.region");
                });
    }

    @Test
    @DisplayName("GRAIN_MISMATCH：请求 grain 不在度量 allowedGrains 内")
    void grainMismatch() {
        SemanticObject metric = metric("m1", "metric.gmv", "STD-1",
                "{\"allowedGrains\":[\"day\",\"month\"]}");
        SemanticGateRequest req = baseRequest(SemanticExecutionMode.SINGLE, List.of(metric), List.of(),
                new SemanticQuerySpec(null, List.of("metric.gmv"), List.of(), List.of(), null, null, 10),
                Set.of()).withGrain("week");

        assertThatThrownBy(() -> gate.evaluate(req))
                .isInstanceOf(CompileRejectException.class)
                .satisfies(ex -> {
                    CompileReject reject = ((CompileRejectException) ex).reject();
                    assertThat(reject.errorCode()).isEqualTo(SemanticCompileErrorCode.GRAIN_MISMATCH.name());
                    assertThat(reject.allowed()).containsExactlyInAnyOrder("day", "month");
                });
    }

    @Test
    @DisplayName("TIME_RANGE_NOT_NORMALIZED：拒绝相对口语时间窗")
    void rejectsRelativeTimeRange() {
        SemanticObject metric = metric("m1", "metric.gmv", "STD-1", "{}");
        SemanticGateRequest req = baseRequest(SemanticExecutionMode.SINGLE, List.of(metric), List.of(),
                new SemanticQuerySpec(null, List.of("metric.gmv"), List.of(), List.of(),
                        new TimeRange("dim.date", "上个季度", "今天"), null, 10),
                Set.of());

        assertThatThrownBy(() -> gate.evaluate(req))
                .isInstanceOf(CompileRejectException.class)
                .satisfies(ex -> assertThat(((CompileRejectException) ex).reject().errorCode())
                        .isEqualTo(SemanticCompileErrorCode.TIME_RANGE_NOT_NORMALIZED.name()));
    }

    @Test
    @DisplayName("DATASET_ACL_DENIED：引用未授权数据集时拒绝")
    void datasetAclDenied() {
        SemanticObject metric = metric("m1", "metric.gmv", "STD-1", "{}");
        SemanticGateRequest req = baseRequest(SemanticExecutionMode.SINGLE, List.of(metric), List.of(),
                new SemanticQuerySpec(null, List.of("metric.gmv"), List.of(), List.of(), null, null, 10),
                Set.of("ds_allowed"))
                .withRequiredDatasetIds(Set.of("ds_secret"));

        assertThatThrownBy(() -> gate.evaluate(req))
                .isInstanceOf(CompileRejectException.class)
                .satisfies(ex -> assertThat(((CompileRejectException) ex).reject().errorCode())
                        .isEqualTo(SemanticCompileErrorCode.DATASET_ACL_DENIED.name()));
    }

    @Test
    @DisplayName("通过门禁：截断 limit 并标记 truncated，生成 gateDigest")
    void passWithTruncation() {
        SemanticObject metric = metric("m1", "metric.gmv", "STD-1", "{}");
        SemanticGateRequest req = baseRequest(SemanticExecutionMode.COHORT_UNION, List.of(metric), List.of(),
                new SemanticQuerySpec(null, List.of("metric.gmv"), List.of(),
                        List.of(new FilterClause("attr.x", "=", List.of("1"))), null, null, 5000),
                Set.of())
                .withMaxRows(200)
                .withRequiredDatasetIds(Set.of());

        SemanticGatePass pass = gate.evaluate(req);
        assertThat(pass.effectiveLimit()).isEqualTo(200);
        assertThat(pass.truncated()).isTrue();
        assertThat(pass.gateDigest()).isNotBlank();
        assertThat(pass.executionMode()).isEqualTo(SemanticExecutionMode.COHORT_UNION);
    }

    @Test
    @DisplayName("EMPTY_QUERY：metrics 与 dimensions 皆空")
    void emptyQuery() {
        SemanticGateRequest req = baseRequest(SemanticExecutionMode.SINGLE, List.of(), List.of(),
                new SemanticQuerySpec(null, List.of(), List.of(), List.of(), null, null, 10),
                Set.of());

        assertThatThrownBy(() -> gate.evaluate(req))
                .isInstanceOf(CompileRejectException.class)
                .satisfies(ex -> assertThat(((CompileRejectException) ex).reject().errorCode())
                        .isEqualTo(SemanticCompileErrorCode.EMPTY_QUERY.name()));
    }

    private static SemanticGateRequest baseRequest(SemanticExecutionMode mode,
                                                   List<SemanticObject> metrics,
                                                   List<SemanticObject> dimensions,
                                                   SemanticQuerySpec spec,
                                                   Set<String> allowedDatasets) {
        return new SemanticGateRequest(
                "ws_1",
                mode,
                spec,
                metrics,
                dimensions,
                null,
                allowedDatasets,
                200,
                Set.of());
    }

    private static SemanticObject metric(String id, String code, String standardRef, String specJson) {
        SemanticObject o = new SemanticObject();
        o.setId(id);
        o.setCode(code);
        o.setObjectType("METRIC");
        o.setStatus("PUBLISHED");
        o.setStandardRef(standardRef);
        o.setSpecJson(specJson);
        return o;
    }

    private static SemanticObject dim(String id, String code) {
        SemanticObject o = new SemanticObject();
        o.setId(id);
        o.setCode(code);
        o.setObjectType("ATTRIBUTE");
        o.setStatus("PUBLISHED");
        o.setSpecJson("{}");
        return o;
    }
}
