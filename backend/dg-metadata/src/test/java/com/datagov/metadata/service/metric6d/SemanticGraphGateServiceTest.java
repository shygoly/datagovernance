package com.datagov.metadata.service.metric6d;

import com.datagov.metadata.entity.SemanticEntities.SemanticLink;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Metric 6D 图闸门 G1–G5 + 删除保护")
class SemanticGraphGateServiceTest {

    private SemanticGraphGateService gate;

    @BeforeEach
    void setUp() {
        gate = new SemanticGraphGateService();
    }

    @Test
    @DisplayName("G1：semi_additive 缺 window_groupings 拒绝；ratio 缺 NULLIF 拒绝")
    void g1SchemaRules() {
        assertThatThrownBy(() -> gate.validateSchema("""
                {"additivity":{"kind":"semi_additive","non_additive_dim":["account"]}}
                """))
                .isInstanceOf(SemanticGraphGateException.class)
                .satisfies(ex -> assertThat(((SemanticGraphGateException) ex).gateCode()).isEqualTo("G1"));

        assertThatThrownBy(() -> gate.validateSchema("""
                {"composition":{"metric_type":"ratio","expression":"${a}/${b}"}}
                """))
                .isInstanceOf(SemanticGraphGateException.class);

        assertThatCode(() -> gate.validateSchema("""
                {"additivity":{"kind":"additive"},
                 "composition":{"metric_type":"ratio","expression":"${a}/NULLIF(${b},0)"},
                 "lod":{"type":"NONE","dimensions":[]}}
                """)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("G2：未解析引用拒绝")
    void g2UnresolvedRef() {
        assertThatThrownBy(() -> gate.validateRefs(Set.of("dim.region", "missing.x"), Set.of("dim.region")))
                .isInstanceOf(SemanticGraphGateException.class)
                .satisfies(ex -> {
                    SemanticGraphGateException ge = (SemanticGraphGateException) ex;
                    assertThat(ge.gateCode()).isEqualTo("G2");
                    assertThat(ge.details()).contains("missing.x");
                });
    }

    @Test
    @DisplayName("G3：LOD 引用非 DIMENSION/ATTRIBUTE 拒绝")
    void g3TypeMismatch() {
        Metric6DSpec spec = gate.validateSchema("""
                {"lod":{"type":"INCLUDE","dimensions":["metric.gmv"]},
                 "composition":{"metric_type":"atomic","expression":"SUM(x)"}}
                """);
        assertThatThrownBy(() -> gate.validateTypes(spec, Map.of("metric.gmv", "METRIC")))
                .isInstanceOf(SemanticGraphGateException.class)
                .satisfies(ex -> assertThat(((SemanticGraphGateException) ex).gateCode()).isEqualTo("G3"));
    }

    @Test
    @DisplayName("G4：度量依赖环检测")
    void g4Cycle() {
        SemanticLink a = link("a", "b", "METRIC_REF");
        SemanticLink b = link("b", "a", "DERIVED_FROM");
        assertThatThrownBy(() -> gate.validateDag(List.of(a, b)))
                .isInstanceOf(SemanticGraphGateException.class)
                .satisfies(ex -> assertThat(((SemanticGraphGateException) ex).gateCode()).isEqualTo("G4"));
    }

    @Test
    @DisplayName("G5：目标 digest 变化标记 STALE_REVIEW")
    void g5StaleReview() {
        SemanticObject changed = obj("t1", "digest-new");
        SemanticObject referrer = obj("f1", "digest-old");
        referrer.setStatus("PUBLISHED");
        SemanticLink inbound = link("f1", "t1", "METRIC_REF");
        inbound.setTargetContentHash("digest-old");

        List<SemanticObject> stale = gate.markStaleOnDigestChange(
                changed, List.of(inbound), Map.of("f1", referrer, "t1", changed));
        assertThat(stale).hasSize(1);
        assertThat(referrer.getStatus()).isEqualTo("STALE_REVIEW");
    }

    @Test
    @DisplayName("删除保护：存在入边则拒绝")
    void deleteProtection() {
        assertThatThrownBy(() -> gate.assertDeletable("t1", List.of(link("f1", "t1", "DIMENSION_REF"))))
                .isInstanceOf(SemanticGraphGateException.class)
                .satisfies(ex -> assertThat(((SemanticGraphGateException) ex).gateCode()).isEqualTo("DELETE_PROTECTION"));
        assertThatCode(() -> gate.assertDeletable("t1", List.of())).doesNotThrowAnyException();
    }

    private static SemanticLink link(String from, String to, String type) {
        SemanticLink l = new SemanticLink();
        l.setFromObjectId(from);
        l.setToObjectId(to);
        l.setLinkType(type);
        l.setStatus("PUBLISHED");
        return l;
    }

    private static SemanticObject obj(String id, String digest) {
        SemanticObject o = new SemanticObject();
        o.setId(id);
        o.setContentDigest(digest);
        o.setStatus("PUBLISHED");
        return o;
    }
}
