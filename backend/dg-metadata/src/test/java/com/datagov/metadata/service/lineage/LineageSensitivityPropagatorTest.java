package com.datagov.metadata.service.lineage;

import com.datagov.metadata.entity.LineageEntities.LineageEdge;
import com.datagov.metadata.entity.LineageEntities.LineageNode;
import com.datagov.metadata.entity.SensitivityEntities.ColumnSensitivity;
import com.datagov.metadata.mapper.ColumnSensitivityMapper;
import com.datagov.metadata.mapper.LineageEdgeMapper;
import com.datagov.metadata.mapper.LineageJobMapper;
import com.datagov.metadata.mapper.LineageNodeMapper;
import com.datagov.metadata.service.lineage.LineageSensitivityPropagator.BlockedEvidence;
import com.datagov.metadata.service.lineage.LineageSensitivityPropagator.PropagationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("血缘敏感标签传播")
class LineageSensitivityPropagatorTest {

    private static final String WS = "ws_alpha";

    private ColumnSensitivityMapper sensitivityMapper;
    private InMemoryPropagator propagator;

    @BeforeEach
    void setUp() {
        sensitivityMapper = mock(ColumnSensitivityMapper.class);
        propagator = new InMemoryPropagator(sensitivityMapper);
    }

    /** 数据访问换成内存图，保留与 DB 过滤等价的语义(ACTIVE + DIRECT、CONFIRMED + L3/L4)。 */
    static final class InMemoryPropagator extends LineageSensitivityPropagator {
        final Map<String, LineageNode> nodes = new LinkedHashMap<>();
        final List<LineageEdge> edges = new ArrayList<>();
        final List<ColumnSensitivity> labels = new ArrayList<>();

        InMemoryPropagator(ColumnSensitivityMapper sensitivityMapper) {
            super(mock(LineageNodeMapper.class), mock(LineageEdgeMapper.class),
                    mock(LineageJobMapper.class), sensitivityMapper, new ObjectMapper());
        }

        @Override
        List<LineageNode> nodesOfColumn(String ws, String columnId) {
            return nodes.values().stream().filter(n -> columnId.equals(n.getCatalogColumnId())).toList();
        }

        @Override
        Map<String, LineageNode> loadNodes(String ws, Collection<String> ids) {
            Map<String, LineageNode> result = new LinkedHashMap<>();
            ids.forEach(id -> {
                if (nodes.containsKey(id)) {
                    result.put(id, nodes.get(id));
                }
            });
            return result;
        }

        @Override
        List<LineageEdge> activeDirectEdgesFrom(String ws, Collection<String> fromNodeIds) {
            return edges.stream()
                    .filter(e -> "ACTIVE".equals(e.getStatus()))
                    .filter(e -> "DIRECT".equals(e.getDependencyType()))
                    .filter(e -> fromNodeIds.contains(e.getFromNodeId()))
                    .toList();
        }

        @Override
        Map<String, List<ColumnSensitivity>> confirmedPropagatingLabels(String ws, Collection<String> columnIds) {
            Map<String, List<ColumnSensitivity>> result = new LinkedHashMap<>();
            labels.stream()
                    .filter(l -> "CONFIRMED".equals(l.getStatus()))
                    .filter(l -> Set.of("L3", "L4").contains(l.getLevel()))
                    .filter(l -> columnIds.contains(l.getColumnId()))
                    .forEach(l -> result.computeIfAbsent(l.getColumnId(), k -> new ArrayList<>()).add(l));
            return result;
        }

        @Override
        Map<String, ColumnSensitivity> existingLabels(String ws, String category, Collection<String> columnIds) {
            Map<String, ColumnSensitivity> result = new LinkedHashMap<>();
            labels.stream()
                    .filter(l -> category.equals(l.getCategory()) && columnIds.contains(l.getColumnId()))
                    .forEach(l -> result.put(l.getColumnId(), l));
            return result;
        }

        @Override
        Map<String, String> jobNames(String ws, Collection<String> jobIds) {
            Map<String, String> result = new LinkedHashMap<>();
            jobIds.forEach(id -> result.put(id, "job-" + id));
            return result;
        }

        InMemoryPropagator node(String id) {
            LineageNode node = new LineageNode();
            node.setId(id);
            node.setWorkspaceId(WS);
            node.setNodeType("COLUMN");
            node.setQualifiedName("db.t." + id);
            node.setCatalogDatasetId("ds_" + id);
            node.setCatalogColumnId("col_" + id);
            nodes.put(id, node);
            return this;
        }

        InMemoryPropagator edge(String id, String from, String to, String expr, boolean masked) {
            return edge(id, from, to, expr, masked, "DIRECT", "ACTIVE", 1.0);
        }

        InMemoryPropagator edge(String id, String from, String to, String expr, boolean masked,
                                String dependency, String status, double confidence) {
            LineageEdge edge = new LineageEdge();
            edge.setId(id);
            edge.setWorkspaceId(WS);
            edge.setFromNodeId(from);
            edge.setToNodeId(to);
            edge.setRelation("DERIVE");
            edge.setTransformExpr(expr);
            edge.setMaskingApplied(masked);
            edge.setDependencyType(dependency);
            edge.setStatus(status);
            edge.setConfidence(confidence);
            edge.setJobId("j1");
            edges.add(edge);
            return this;
        }

        ColumnSensitivity label(String columnNodeId, String status, String level, String category,
                                String detectedBy) {
            ColumnSensitivity label = new ColumnSensitivity();
            label.setId("sens_" + columnNodeId + "_" + status);
            label.setWorkspaceId(WS);
            label.setDatasetId("ds_" + columnNodeId);
            label.setColumnId("col_" + columnNodeId);
            label.setCategory(category);
            label.setLevel(level);
            label.setStatus(status);
            label.setDetectedBy(detectedBy);
            labels.add(label);
            return label;
        }
    }

    private List<ColumnSensitivity> inserted() {
        ArgumentCaptor<ColumnSensitivity> captor = ArgumentCaptor.forClass(ColumnSensitivity.class);
        verify(sensitivityMapper, org.mockito.Mockito.atLeast(0)).insert(captor.capture());
        return captor.getAllValues();
    }

    @Nested
    @DisplayName("传播")
    class Propagate {

        @Test
        @DisplayName("CONFIRMED L4 沿 ACTIVE+DIRECT 边逐跳生成 SUGGESTED(detected_by=LINEAGE)")
        void confirmedLabelPropagatesDownstream() {
            propagator.node("a").node("b").node("c")
                    .edge("e1", "a", "b", "phone", false, "DIRECT", "ACTIVE", 0.9)
                    .edge("e2", "b", "c", "phone", false, "DIRECT", "ACTIVE", 0.9);
            propagator.label("a", "CONFIRMED", "L4", "PII", "MANUAL");

            PropagationResult result = propagator.propagateFromConfirmedLabel(WS, "col_a");

            assertThat(result.created()).isEqualTo(2);
            assertThat(result.blocked()).isZero();
            List<ColumnSensitivity> created = inserted();
            assertThat(created).hasSize(2);
            ColumnSensitivity first = created.get(0);
            assertThat(first.getColumnId()).isEqualTo("col_b");
            assertThat(first.getDatasetId()).isEqualTo("ds_b");
            assertThat(first.getStatus()).isEqualTo("SUGGESTED");
            assertThat(first.getDetectedBy()).isEqualTo("LINEAGE");
            assertThat(first.getCategory()).isEqualTo("PII");
            assertThat(first.getLevel()).isEqualTo("L4");
            assertThat(first.getWorkspaceId()).isEqualTo(WS);
            assertThat(first.getConfidence()).isEqualTo(0.765); // 0.85 × 0.9
            assertThat(first.getEvidenceJson()).contains("\"origin\":\"LINEAGE\"", "col_a", "e1", "job-j1");
            ColumnSensitivity second = created.get(1);
            assertThat(second.getColumnId()).isEqualTo("col_c");
            assertThat(second.getConfidence()).isEqualTo(0.6885); // 0.85 × 0.9 × 0.9
            assertThat(second.getEvidenceJson()).contains("e1", "e2").contains("\"hops\":2");
        }

        @Test
        @DisplayName("最多传播 5 跳")
        void stopsAfterFiveHops() {
            for (String id : List.of("n0", "n1", "n2", "n3", "n4", "n5", "n6", "n7")) {
                propagator.node(id);
            }
            for (int i = 0; i < 7; i++) {
                propagator.edge("e" + i, "n" + i, "n" + (i + 1), "c", false);
            }
            propagator.label("n0", "CONFIRMED", "L3", "PII", "MANUAL");

            PropagationResult result = propagator.propagateFromConfirmedLabel(WS, "col_n0");

            assertThat(result.created()).isEqualTo(LineageSensitivityPropagator.MAX_HOPS);
            assertThat(inserted()).extracting(ColumnSensitivity::getColumnId)
                    .containsExactly("col_n1", "col_n2", "col_n3", "col_n4", "col_n5");
        }

        @Test
        @DisplayName("环路不会死循环，也不会重复建议")
        void cyclesTerminate() {
            propagator.node("a").node("b")
                    .edge("e1", "a", "b", "x", false)
                    .edge("e2", "b", "a", "x", false);
            propagator.label("a", "CONFIRMED", "L4", "PII", "MANUAL");

            PropagationResult result = propagator.propagateFromConfirmedLabel(WS, "col_a");

            assertThat(result.created()).isEqualTo(1);
        }

        @Test
        @DisplayName("L1/L2 以及未 CONFIRMED 的标签不传播")
        void onlyConfirmedL3L4() {
            propagator.node("a").node("b").edge("e1", "a", "b", "x", false);
            propagator.label("a", "CONFIRMED", "L2", "PII", "MANUAL");
            propagator.label("a", "SUGGESTED", "L4", "FINANCIAL", "RULE");

            PropagationResult result = propagator.propagateFromConfirmedLabel(WS, "col_a");

            assertThat(result.created()).isZero();
            verify(sensitivityMapper, never()).insert(any(ColumnSensitivity.class));
        }

        @Test
        @DisplayName("INDIRECT / PLANNED 边不是传播通道")
        void indirectAndPlannedEdgesAreIgnored() {
            propagator.node("a").node("b").node("c")
                    .edge("e1", "a", "b", "WHERE a>1", false, "INDIRECT", "ACTIVE", 1.0)
                    .edge("e2", "a", "c", "a", false, "DIRECT", "PLANNED", 1.0);
            propagator.label("a", "CONFIRMED", "L4", "PII", "MANUAL");

            assertThat(propagator.propagateFromConfirmedLabel(WS, "col_a").created()).isZero();
        }

        @Test
        @DisplayName("名为 SHARE_COUNT 之类的普通表达式不会被误判为哈希")
        void ordinaryExpressionsAreNotBlocked() {
            propagator.node("a").node("b")
                    .edge("e1", "a", "b", "COALESCE(share_count, 0) + sharpness", false);
            propagator.label("a", "CONFIRMED", "L4", "PII", "MANUAL");

            PropagationResult result = propagator.propagateFromConfirmedLabel(WS, "col_a");

            assertThat(result.created()).isEqualTo(1);
            assertThat(result.blocked()).isZero();
        }

        @Test
        @DisplayName("目标列已 CONFIRMED 不被改写；REJECTED 在该列截止；规则建议不被改写")
        void respectsHumanAndRuleLabels() {
            propagator.node("a").node("b").node("c").node("d").node("e")
                    .edge("e1", "a", "b", "x", false)
                    .edge("e2", "b", "c", "x", false)
                    .edge("e3", "a", "d", "x", false)
                    .edge("e4", "d", "e", "x", false);
            propagator.label("a", "CONFIRMED", "L4", "PII", "MANUAL");
            propagator.label("b", "CONFIRMED", "L3", "PII", "MANUAL");   // 已确认：不改写，但继续向下游
            propagator.label("d", "REJECTED", "L3", "PII", "MANUAL");    // 人工判定不敏感：到此截止
            ColumnSensitivity rule = propagator.label("c", "SUGGESTED", "L3", "PII", "RULE");

            propagator.propagateFromConfirmedLabel(WS, "col_a");

            // b 已确认、c 是规则建议、d 被拒绝、e 被截断 —— 没有任何新行或改写。
            verify(sensitivityMapper, never()).insert(any(ColumnSensitivity.class));
            verify(sensitivityMapper, never()).updateById(any(ColumnSensitivity.class));
            assertThat(rule.getDetectedBy()).isEqualTo("RULE");
        }

        @Test
        @DisplayName("重复传播幂等：证据不变时不重复写")
        void repeatedPropagationIsIdempotent() {
            propagator.node("a").node("b").edge("e1", "a", "b", "x", false);
            propagator.label("a", "CONFIRMED", "L4", "PII", "MANUAL");

            propagator.propagateFromConfirmedLabel(WS, "col_a");
            // 把第一次插入的标签放回“库”里，模拟第二次读取
            propagator.labels.add(inserted().get(0));
            PropagationResult second = propagator.propagateFromConfirmedLabel(WS, "col_a");

            assertThat(second.created()).isZero();
            assertThat(second.updated()).isZero();
            verify(sensitivityMapper, times(1)).insert(any(ColumnSensitivity.class));
            verify(sensitivityMapper, never()).updateById(any(ColumnSensitivity.class));
        }

        @Test
        @DisplayName("新 ACTIVE+DIRECT 边只触发该边作为第一跳的传播")
        void newEdgeTriggersOnlyThatEdge() {
            propagator.node("a").node("b").node("x")
                    .edge("e_new", "a", "b", "phone", false)
                    .edge("e_old", "a", "x", "phone", false);
            propagator.label("a", "CONFIRMED", "L4", "PII", "MANUAL");
            LineageEdge newEdge = propagator.edges.get(0);

            PropagationResult result = propagator.propagateFromEdges(WS, List.of(newEdge));

            assertThat(result.created()).isEqualTo(1);
            assertThat(inserted()).extracting(ColumnSensitivity::getColumnId).containsExactly("col_b");
        }

        @Test
        @DisplayName("上游没有 CONFIRMED L3/L4 标签时，新边不触发任何写入")
        void newEdgeWithoutSourceLabel() {
            propagator.node("a").node("b").edge("e1", "a", "b", "phone", false);
            propagator.label("a", "SUGGESTED", "L4", "PII", "RULE");

            PropagationResult result = propagator.propagateFromEdges(WS, List.of(propagator.edges.get(0)));

            assertThat(result.created()).isZero();
            verify(sensitivityMapper, never()).insert(any(ColumnSensitivity.class));
        }

        @Test
        @DisplayName("目标未对齐 Catalog：不写标签，但继续向下游传播")
        void unalignedTargetIsTransparent() {
            propagator.node("a").node("b").node("c")
                    .edge("e1", "a", "b", "x", false)
                    .edge("e2", "b", "c", "x", false);
            propagator.nodes.get("b").setCatalogColumnId(null);
            propagator.nodes.get("b").setCatalogDatasetId(null);
            propagator.label("a", "CONFIRMED", "L4", "PII", "MANUAL");

            PropagationResult result = propagator.propagateFromConfirmedLabel(WS, "col_a");

            assertThat(result.created()).isEqualTo(1);
            assertThat(inserted()).extracting(ColumnSensitivity::getColumnId).containsExactly("col_c");
        }
    }

    @Nested
    @DisplayName("脱敏阻断")
    class Blocking {

        @Test
        @DisplayName("masking_applied=true 阻断该分支并留下证据，其它分支照常传播")
        void maskingAppliedBlocks() {
            propagator.node("a").node("b").node("c").node("d")
                    .edge("e_masked", "a", "b", "phone", true)
                    .edge("e_plain", "a", "c", "phone", false)
                    .edge("e_after_mask", "b", "d", "phone", false);
            propagator.edges.get(0).setMaskingEvidence("mask_phone()");
            propagator.label("a", "CONFIRMED", "L4", "PII", "MANUAL");

            PropagationResult result = propagator.propagateFromConfirmedLabel(WS, "col_a");

            assertThat(result.created()).isEqualTo(1);
            assertThat(inserted()).extracting(ColumnSensitivity::getColumnId).containsExactly("col_c");
            assertThat(result.blocked()).isEqualTo(1);
            BlockedEvidence evidence = result.blockedEvidence().get(0);
            assertThat(evidence.edgeId()).isEqualTo("e_masked");
            assertThat(evidence.sourceColumnId()).isEqualTo("col_a");
            assertThat(evidence.category()).isEqualTo("PII");
            assertThat(evidence.reason()).contains("blockedByMasking", "masking_applied", "mask_phone()");
        }

        @Test
        @DisplayName("transform_expr 命中 MASK/MD5/SHA/HASH/ENCRYPT/DESENSITIZE 即阻断")
        void transformExprBlocks() {
            List<String> blockedExprs = List.of(
                    "MASK_PHONE(phone)", "md5(phone)", "SHA256(phone)", "sha1(phone)", "SHA(phone)",
                    "hash(phone)", "AES_ENCRYPT(phone, k)", "desensitize(phone)", "xxhash64(phone)");
            for (String expr : blockedExprs) {
                LineageEdge edge = new LineageEdge();
                edge.setTransformExpr(expr);
                edge.setMaskingApplied(false);
                assertThat(LineageSensitivityPropagator.blockReason(edge))
                        .as(expr).startsWith("blockedByMasking");
            }
            for (String expr : java.util.Arrays.asList("phone", "UPPER(phone)", "COALESCE(share_count,0)", "CONCAT(a, b)", null)) {
                LineageEdge edge = new LineageEdge();
                edge.setTransformExpr(expr);
                assertThat(LineageSensitivityPropagator.blockReason(edge)).as(String.valueOf(expr)).isNull();
            }
        }

        @Test
        @DisplayName("表达式阻断：下游不产生建议，并记录命中的函数")
        void transformExprBlocksPropagation() {
            propagator.node("a").node("b").node("c")
                    .edge("e1", "a", "b", "MD5(phone)", false)
                    .edge("e2", "b", "c", "phone_hash", false);
            propagator.label("a", "CONFIRMED", "L4", "PII", "MANUAL");

            PropagationResult result = propagator.propagateFromConfirmedLabel(WS, "col_a");

            assertThat(result.created()).isZero();
            verify(sensitivityMapper, never()).insert(any(ColumnSensitivity.class));
            assertThat(result.blockedEvidence()).hasSize(1);
            assertThat(result.blockedEvidence().get(0).reason()).contains("MD5");
        }

        @Test
        @DisplayName("同一目标一条路径被脱敏、另一条路径明文直连：仍然传播")
        void maskedPathDoesNotShadowPlainPath() {
            propagator.node("a").node("b").node("t")
                    .edge("e_masked", "a", "t", "SHA256(x)", false)
                    .edge("e_plain", "a", "t", "x", false)
                    .edge("e_other", "a", "b", "x", false);
            propagator.label("a", "CONFIRMED", "L4", "PII", "MANUAL");

            PropagationResult result = propagator.propagateFromConfirmedLabel(WS, "col_a");

            assertThat(result.created()).isEqualTo(2);
            assertThat(inserted()).extracting(ColumnSensitivity::getColumnId).contains("col_t");
            assertThat(result.blocked()).isEqualTo(1);
        }
    }
}
