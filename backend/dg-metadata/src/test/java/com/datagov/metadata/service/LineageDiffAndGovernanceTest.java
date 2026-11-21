package com.datagov.metadata.service;

import com.datagov.common.error.BizException;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.LineageEntities.LineageEdge;
import com.datagov.metadata.entity.LineageEntities.LineageJob;
import com.datagov.metadata.entity.LineageEntities.LineageNode;
import com.datagov.metadata.entity.QualityEntities.QualityRule;
import com.datagov.metadata.entity.SemanticEntities.SemanticMapping;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.LineageEdgeMapper;
import com.datagov.metadata.mapper.LineageJobMapper;
import com.datagov.metadata.mapper.LineageNodeMapper;
import com.datagov.metadata.mapper.QualityRuleMapper;
import com.datagov.metadata.mapper.SemanticMappingMapper;
import com.datagov.metadata.mapper.SemanticObjectMapper;
import com.datagov.metadata.service.LineageService.EdgeInput;
import com.datagov.metadata.service.LineageService.GraphView;
import com.datagov.metadata.service.LineageService.JobDiffView;
import com.datagov.metadata.service.LineageService.JobRequest;
import com.datagov.metadata.service.LineageService.NodeRef;
import com.datagov.metadata.service.lineage.GovernanceConsumer;
import com.datagov.metadata.service.lineage.GovernanceConsumerProvider;
import com.datagov.metadata.service.lineage.LineageAclEvaluator;
import com.datagov.metadata.service.lineage.LineageAgentToolService;
import com.datagov.metadata.service.lineage.LineageAgentToolService.ImpactResult;
import com.datagov.metadata.service.lineage.LineageAgentToolService.TraceResult;
import com.datagov.metadata.service.lineage.SemanticLineageBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** column-lineage-e2e Phase 3：治理消费方投影（P3-1）、历史 Diff（P3-2）、Agent 工具结果整形（P3-3）。 */
@DisplayName("血缘 Phase 3：治理投影 / 历史 Diff / Agent 工具")
class LineageDiffAndGovernanceTest {

    private static final String WORKSPACE = "ws_alpha";
    private static final Instant R = Instant.parse("2026-11-01T10:00:00Z");

    private LineageNodeMapper nodeMapper;
    private LineageJobMapper jobMapper;
    private LineageEdgeMapper edgeMapper;
    private CatalogDatasetMapper datasetMapper;
    private SemanticMappingMapper mappingMapper;
    private SemanticObjectMapper objectMapper;
    private QualityRuleMapper ruleMapper;

    @BeforeEach
    void setUp() {
        nodeMapper = mock(LineageNodeMapper.class);
        jobMapper = mock(LineageJobMapper.class);
        edgeMapper = mock(LineageEdgeMapper.class);
        datasetMapper = mock(CatalogDatasetMapper.class);
        mappingMapper = mock(SemanticMappingMapper.class);
        objectMapper = mock(SemanticObjectMapper.class);
        ruleMapper = mock(QualityRuleMapper.class);
        when(mappingMapper.selectList(any())).thenReturn(List.of());
        when(objectMapper.selectList(any())).thenReturn(List.of());
        when(ruleMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.insert(any(LineageJob.class))).thenReturn(1);
        when(jobMapper.updateById(any(LineageJob.class))).thenReturn(1);
        when(nodeMapper.insert(any(LineageNode.class))).thenReturn(1);
        when(edgeMapper.insert(any(LineageEdge.class))).thenReturn(1);
        when(edgeMapper.updateById(any(LineageEdge.class))).thenReturn(1);
        caller(Set.of("metadata:lineage:read", "metadata:semantic:read", "metadata:quality:read",
                "governance:analysis:read"));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    private static void caller(Set<String> permissions) {
        WorkspaceContext.set(new Caller("usr_1", "alice", WORKSPACE, false, permissions));
    }

    private LineageService service(Set<String> viewableDatasets, GovernanceConsumerProvider... providers) {
        LineageAclEvaluator evaluator = (caller, ids) -> ids.stream()
                .filter(viewableDatasets::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        SemanticLineageBridge bridge = new SemanticLineageBridge(
                mappingMapper, objectMapper, ruleMapper, List.of(providers));
        return new LineageService(nodeMapper, jobMapper, edgeMapper, datasetMapper, evaluator, null, bridge);
    }

    // ── P3-1 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("P3-1 SemanticLineageBridge")
    class Governance {

        private LineageNode start;
        private LineageNode target;

        private void givenStartToTarget() {
            start = column("lin_start", "ds_ods", "ods.orders.amount");
            target = column("lin_target", "ds_dws", "dws.summary.total_amt");
            // findNode 起点；第一跳下游节点
            when(nodeMapper.selectList(any())).thenReturn(List.of(start), List.of(target));
            when(edgeMapper.selectList(any())).thenReturn(List.of(edge("line_1", start.getId(), target.getId())));
        }

        private void givenMetricOnTarget() {
            SemanticMapping mapping = new SemanticMapping();
            mapping.setId("map_1");
            mapping.setWorkspaceId(WORKSPACE);
            mapping.setObjectId("obj_gmv");
            mapping.setMappingRole("MEASURE");
            mapping.setCatalogDatasetId("ds_dws");
            mapping.setCatalogColumnId(target.getCatalogColumnId());
            mapping.setStatus("PUBLISHED");
            when(mappingMapper.selectList(any())).thenReturn(List.of(mapping));
            SemanticObject gmv = new SemanticObject();
            gmv.setId("obj_gmv");
            gmv.setCode("gmv");
            gmv.setName("成交额");
            gmv.setObjectType("METRIC");
            gmv.setStatus("PUBLISHED");
            when(objectMapper.selectList(any())).thenReturn(List.of(gmv));
        }

        private QualityRule ruleOn(String datasetId, String columnId) {
            QualityRule rule = new QualityRule();
            rule.setId("qr_1");
            rule.setWorkspaceId(WORKSPACE);
            rule.setDatasetId(datasetId);
            rule.setColumnId(columnId);
            rule.setRuleType("NOT_NULL");
            rule.setSeverity("HIGH");
            rule.setEnabled(true);
            return rule;
        }

        private GovernanceConsumerProvider reportProvider(String datasetId) {
            return new GovernanceConsumerProvider() {
                @Override
                public String refType() {
                    return SemanticLineageBridge.REPORT;
                }

                @Override
                public String requiredPermission() {
                    return "governance:analysis:read";
                }

                @Override
                public List<GovernanceConsumer> findConsumers(String workspaceId, java.util.Collection<String> ids) {
                    return ids.contains(datasetId)
                            ? List.of(new GovernanceConsumer("REPORT", "rpt_1", "report:rpt_1",
                            "月度经营分析", "PUBLISHED", datasetId, null, "分析报告 v2"))
                            : List.of();
                }
            };
        }

        @Test
        @DisplayName("includeGovernance=true：指标/报告/质检规则作为下游节点并入，边为 CONSUMED_BY")
        void projectsMetricReportAndRule() {
            givenStartToTarget();
            givenMetricOnTarget();
            when(ruleMapper.selectList(any())).thenReturn(List.of(ruleOn("ds_dws", target.getCatalogColumnId())));

            GraphView graph = service(Set.of("ds_ods", "ds_dws"), reportProvider("ds_dws"))
                    .impact(start.getId(), 5, false, true);

            assertThat(graph.nodes()).extracting(LineageService.NodeView::nodeType)
                    .containsExactlyInAnyOrder("COLUMN", "COLUMN", "METRIC", "REPORT", "QUALITY_RULE");
            LineageService.NodeView metric = graph.nodes().stream()
                    .filter(n -> "METRIC".equals(n.nodeType())).findFirst().orElseThrow();
            assertThat(metric.id()).isEqualTo("gov:METRIC:obj_gmv");
            assertThat(metric.externalRefType()).isEqualTo("METRIC");
            assertThat(metric.externalRefId()).isEqualTo("obj_gmv");
            assertThat(metric.displayName()).isEqualTo("成交额");
            List<LineageService.EdgeView> govEdges = graph.edges().stream()
                    .filter(e -> "CONSUMED_BY".equals(e.relation())).toList();
            assertThat(govEdges).hasSize(3);
            assertThat(govEdges).allSatisfy(e -> {
                assertThat(e.fromNodeId()).isEqualTo(target.getId());
                assertThat(e.status()).isEqualTo("ACTIVE");
                assertThat(e.origin()).isEqualTo("SEMANTIC_BRIDGE");
            });
        }

        @Test
        @DisplayName("默认（includeGovernance=false）不投影，行为与 Phase 2 一致")
        void defaultDoesNotProject() {
            givenStartToTarget();
            givenMetricOnTarget();

            GraphView graph = service(Set.of("ds_ods", "ds_dws")).impact(start.getId(), 5);

            assertThat(graph.nodes()).extracting(LineageService.NodeView::nodeType).containsOnly("COLUMN");
            verify(mappingMapper, org.mockito.Mockito.never()).selectList(any());
        }

        @Test
        @DisplayName("列级锚点只接同一列或整表绑定的消费方，不串到同表其它列")
        void columnAnchorDoesNotLeakToSiblingColumns() {
            givenStartToTarget();
            // 规则绑定在 dws 表的另一个字段上
            when(ruleMapper.selectList(any())).thenReturn(List.of(ruleOn("ds_dws", "col_someone_else")));

            GraphView graph = service(Set.of("ds_ods", "ds_dws")).impact(start.getId(), 5, false, true);

            assertThat(graph.nodes()).extracting(LineageService.NodeView::nodeType).containsOnly("COLUMN");

            // 整表规则（columnId 为空）则挂到该表的列节点上
            when(ruleMapper.selectList(any())).thenReturn(List.of(ruleOn("ds_dws", null)));
            when(nodeMapper.selectList(any())).thenReturn(List.of(start), List.of(target));
            when(edgeMapper.selectList(any())).thenReturn(List.of(edge("line_1", start.getId(), target.getId())));
            GraphView withTableRule = service(Set.of("ds_ods", "ds_dws")).impact(start.getId(), 5, false, true);
            assertThat(withTableRule.nodes()).extracting(LineageService.NodeView::nodeType)
                    .contains("QUALITY_RULE");
        }

        @Test
        @DisplayName("调用者无 semantic:read / quality:read / analysis:read 时对应消费方被跳过")
        void permissionsGateEachConsumerType() {
            givenStartToTarget();
            givenMetricOnTarget();
            when(ruleMapper.selectList(any())).thenReturn(List.of(ruleOn("ds_dws", target.getCatalogColumnId())));
            caller(Set.of("metadata:lineage:read")); // 只有血缘读权限

            GraphView graph = service(Set.of("ds_ods", "ds_dws"), reportProvider("ds_dws"))
                    .impact(start.getId(), 5, false, true);

            assertThat(graph.nodes()).extracting(LineageService.NodeView::nodeType).containsOnly("COLUMN");
        }

        @Test
        @DisplayName("受限数据集不是锚点：其上的指标/规则不会经消费方泄露")
        void restrictedDatasetConsumersAreNotProjected() {
            LineageNode secret = column("lin_secret", "ds_secret", "hr.payroll.salary");
            start = column("lin_start", "ds_ods", "ods.orders.amount");
            when(nodeMapper.selectList(any())).thenReturn(List.of(start), List.of(secret));
            when(edgeMapper.selectList(any())).thenReturn(List.of(edge("line_1", start.getId(), secret.getId())));
            when(ruleMapper.selectList(any())).thenReturn(List.of(ruleOn("ds_secret", secret.getCatalogColumnId())));

            GraphView graph = service(Set.of("ds_ods"), reportProvider("ds_secret"))
                    .impact(start.getId(), 5, false, true);

            assertThat(graph.restrictedCount()).isEqualTo(1);
            assertThat(graph.nodes()).extracting(LineageService.NodeView::nodeType)
                    .containsExactlyInAnyOrder("COLUMN", "RESTRICTED");
            assertThat(graph.toString()).doesNotContain("qr_1", "rpt_1", "月度经营分析", "ds_secret", "salary");
        }

        @Test
        @DisplayName("提供者抛错只丢这一类消费方，血缘图本身照常返回")
        void providerFailureIsIsolated() {
            givenStartToTarget();
            givenMetricOnTarget();
            GovernanceConsumerProvider broken = new GovernanceConsumerProvider() {
                @Override
                public String refType() {
                    return "REPORT";
                }

                @Override
                public String requiredPermission() {
                    return "governance:analysis:read";
                }

                @Override
                public List<GovernanceConsumer> findConsumers(String workspaceId, java.util.Collection<String> ids) {
                    throw new IllegalStateException("boom");
                }
            };

            GraphView graph = service(Set.of("ds_ods", "ds_dws"), broken).impact(start.getId(), 5, false, true);

            assertThat(graph.nodes()).extracting(LineageService.NodeView::nodeType)
                    .containsExactlyInAnyOrder("COLUMN", "COLUMN", "METRIC");
        }

        @Test
        @DisplayName("表级图 downstream：数据集节点挂上治理消费方；upstream 方向不投影")
        void datasetGraphProjectsOnlyDownstream() {
            CatalogDataset ods = dataset("ds_ods", "ods/orders");
            CatalogDataset dws = dataset("ds_dws", "dws/summary");
            start = column("lin_start", "ds_ods", "ods.orders.amount");
            target = column("lin_target", "ds_dws", "dws.summary.total_amt");
            when(datasetMapper.selectOne(any())).thenReturn(ods);
            when(nodeMapper.selectList(any())).thenReturn(List.of(start), List.of(start, target));
            when(edgeMapper.selectList(any())).thenReturn(List.of(edge("line_1", start.getId(), target.getId())));
            when(datasetMapper.selectList(any())).thenReturn(List.of(ods, dws));
            when(ruleMapper.selectList(any())).thenReturn(List.of(ruleOn("ds_dws", null)));

            GraphView down = service(Set.of("ds_ods", "ds_dws")).datasetGraph("ds_ods", "downstream", 2, false, true);
            assertThat(down.nodes()).extracting(LineageService.NodeView::nodeType).contains("QUALITY_RULE");
            LineageService.EdgeView govEdge = down.edges().stream()
                    .filter(e -> "CONSUMED_BY".equals(e.relation())).findFirst().orElseThrow();
            assertThat(govEdge.fromNodeId()).isEqualTo("ds_dws");

            when(nodeMapper.selectList(any())).thenReturn(List.of(start), List.of(start, target));
            GraphView up = service(Set.of("ds_ods", "ds_dws")).datasetGraph("ds_ods", "upstream", 2, false, true);
            assertThat(up.nodes()).extracting(LineageService.NodeView::nodeType).doesNotContain("QUALITY_RULE");
        }
    }

    // ── P3-2 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("P3-2 历史 Diff")
    class Diff {

        private final LineageNode a = column("lin_a", "ds_ods", "ods.t.a");
        private final LineageNode b = column("lin_b", "ds_dws", "dws.t.b");
        private final LineageNode c = column("lin_c", "ds_dws", "dws.t.c");

        private LineageJob givenJob() {
            LineageJob job = new LineageJob();
            job.setId("linj_1");
            job.setWorkspaceId(WORKSPACE);
            job.setName("订单宽表");
            job.setJobType("BATCH");
            when(jobMapper.selectOne(any())).thenReturn(job);
            return job;
        }

        private LineageEdge active(String id, LineageNode from, LineageNode to, String relation, String expr,
                                   Instant introducedAt) {
            LineageEdge e = edge(id, from.getId(), to.getId());
            e.setJobId("linj_1");
            e.setRelation(relation);
            e.setTransformExpr(expr);
            e.setIntroducedAt(introducedAt);
            return e;
        }

        private LineageEdge superseded(String id, LineageNode from, LineageNode to, String relation,
                                       String expr, Instant at) {
            LineageEdge e = active(id, from, to, relation, expr, R.minusSeconds(86_400));
            e.setStatus("SUPERSEDED");
            e.setSupersededAt(at);
            return e;
        }

        private LineageService diffService(Set<String> viewable) {
            return service(viewable);
        }

        @Test
        @DisplayName("added / removed / modifiedTransform 同批识别，旧批次与未变边不混入")
        void classifiesLatestBatch() {
            givenJob();
            LineageEdge removed = superseded("line_removed", a, c, "COPY", "a", R);
            LineageEdge oldRemoved = superseded("line_old", a, b, "COPY", "a", R.minusSeconds(3 * 86_400));
            LineageEdge added = active("line_added", a, b, "COPY", "a", R);
            LineageEdge unchanged = active("line_same", b, c, "COPY", "b", R.minusSeconds(86_400));
            LineageEdge rewritten = active("line_rewritten", c, a, "TRANSFORM", "SUM(COALESCE(x,0))",
                    R.minusSeconds(86_400));
            rewritten.setPreviousTransformExpr("SUM(x)");
            rewritten.setPreviousDependencyType("DIRECT");
            rewritten.setPreviousChangedAt(R);
            when(edgeMapper.selectList(any())).thenReturn(List.of(removed, oldRemoved, added, unchanged, rewritten));
            when(nodeMapper.selectList(any())).thenReturn(List.of(a, b, c));

            JobDiffView diff = diffService(Set.of("ds_ods", "ds_dws")).jobDiff("linj_1");

            assertThat(diff.hasBaseline()).isTrue();
            assertThat(diff.baselineAt()).isEqualTo(R);
            assertThat(diff.activeEdges()).isEqualTo(3);
            assertThat(diff.added()).extracting(LineageService.DiffItem::edgeId).containsExactly("line_added");
            assertThat(diff.removed()).extracting(LineageService.DiffItem::edgeId).containsExactly("line_removed");
            assertThat(diff.modifiedTransform()).hasSize(1);
            LineageService.DiffItem modified = diff.modifiedTransform().get(0);
            assertThat(modified.previousTransformExpr()).isEqualTo("SUM(x)");
            assertThat(modified.transformExpr()).isEqualTo("SUM(COALESCE(x,0))");
            assertThat(modified.fromName()).isEqualTo("dws.t.c");
            assertThat(diff.restrictedCount()).isZero();
        }

        @Test
        @DisplayName("relation 变化（旧边下线 + 新边上线）按同一对 from→to 配成修改")
        void relationChangeIsPairedAsModification() {
            givenJob();
            LineageEdge old = superseded("line_old", a, b, "COPY", "a", R);
            LineageEdge now = active("line_new", a, b, "TRANSFORM", "UPPER(a)", R);
            when(edgeMapper.selectList(any())).thenReturn(List.of(old, now));
            when(nodeMapper.selectList(any())).thenReturn(List.of(a, b));

            JobDiffView diff = diffService(Set.of("ds_ods", "ds_dws")).jobDiff("linj_1");

            assertThat(diff.added()).isEmpty();
            assertThat(diff.removed()).isEmpty();
            assertThat(diff.modifiedTransform()).hasSize(1);
            LineageService.DiffItem item = diff.modifiedTransform().get(0);
            assertThat(item.previousRelation()).isEqualTo("COPY");
            assertThat(item.relation()).isEqualTo("TRANSFORM");
            assertThat(item.previousTransformExpr()).isEqualTo("a");
        }

        @Test
        @DisplayName("没有历史改动：hasBaseline=false，不把全部 ACTIVE 边伪装成新增")
        void noBaselineReturnsEmptyLists() {
            givenJob();
            when(edgeMapper.selectList(any())).thenReturn(List.of(
                    active("line_1", a, b, "COPY", "a", R), active("line_2", b, c, "COPY", "b", R)));

            JobDiffView diff = diffService(Set.of("ds_ods", "ds_dws")).jobDiff("linj_1");

            assertThat(diff.hasBaseline()).isFalse();
            assertThat(diff.baselineAt()).isNull();
            assertThat(diff.activeEdges()).isEqualTo(2);
            assertThat(diff.added()).isEmpty();
            assertThat(diff.removed()).isEmpty();
            assertThat(diff.modifiedTransform()).isEmpty();
        }

        @Test
        @DisplayName("已被后续上报复活的 SUPERSEDED 边不算 removed")
        void revivedEdgeIsNotRemoved() {
            givenJob();
            LineageEdge revived = active("line_1", a, b, "COPY", "a", R.plusSeconds(60));
            when(edgeMapper.selectList(any())).thenReturn(List.of(revived,
                    superseded("line_ghost", a, b, "COPY", "a", R)));
            when(nodeMapper.selectList(any())).thenReturn(List.of(a, b));

            JobDiffView diff = diffService(Set.of("ds_ods", "ds_dws")).jobDiff("linj_1");

            assertThat(diff.removed()).isEmpty();
        }

        @Test
        @DisplayName("C5：触及受限数据集的行以 [受限资产] 占位，隐藏表达式与真实 ID")
        void restrictedRowsAreMasked() {
            givenJob();
            LineageNode secret = column("lin_secret", "ds_secret", "hr.payroll.salary");
            LineageEdge removed = superseded("line_secret_edge", secret, b, "TRANSFORM",
                    "SELECT salary FROM hr.payroll", R);
            when(edgeMapper.selectList(any())).thenReturn(List.of(removed));
            when(nodeMapper.selectList(any())).thenReturn(List.of(secret, b));

            JobDiffView diff = diffService(Set.of("ds_dws")).jobDiff("linj_1");

            assertThat(diff.restrictedCount()).isEqualTo(1);
            assertThat(diff.truncatedByAcl()).isTrue();
            LineageService.DiffItem item = diff.removed().get(0);
            assertThat(item.restricted()).isTrue();
            assertThat(item.fromName()).isEqualTo("[受限资产]");
            assertThat(item.toName()).isEqualTo("dws.t.b");
            assertThat(item.transformExpr()).isNull();
            assertThat(diff.toString()).doesNotContain("payroll", "salary", "ds_secret", "lin_secret",
                    "line_secret_edge");
        }

        @Test
        @DisplayName("作业不存在（或不在当前 workspace）：404")
        void unknownJobIsNotFound() {
            when(jobMapper.selectOne(any())).thenReturn(null);
            assertThatThrownBy(() -> diffService(Set.of()).jobDiff("linj_nope"))
                    .isInstanceOf(BizException.class);
        }

        @Test
        @DisplayName("上报写入：表达式被改写时保留旧值；新边带批次时间；下线边与新边共用同一批次")
        void recordJobTracksRevision() {
            LineageService plain = new LineageService(nodeMapper, jobMapper, edgeMapper, datasetMapper);
            LineageJob job = givenJob();
            LineageNode from = node("lin_from", "COLUMN", "ods.orders.id");
            LineageNode to = node("lin_to", "COLUMN", "dwd.orders.id");
            LineageNode third = node("lin_third", "COLUMN", "dwd.orders.old");
            when(nodeMapper.selectOne(any())).thenReturn(from, to);
            LineageEdge existing = edge("line_1", from.getId(), to.getId());
            existing.setJobId(job.getId());
            existing.setTransformExpr("id");
            LineageEdge stale = edge("line_stale", from.getId(), third.getId());
            stale.setJobId(job.getId());
            when(edgeMapper.selectList(any())).thenReturn(List.of(existing, stale));

            plain.recordJob(new JobRequest("订单宽表", "BATCH", "job_1", "a".repeat(64), List.of(
                    new EdgeInput(new NodeRef("COLUMN", "ods.orders.id", "ds_ods", "col_1", "id"),
                            new NodeRef("COLUMN", "dwd.orders.id", "ds_dwd", "col_2", "id"),
                            "DERIVE", "CAST(id AS BIGINT)", 1.0, "AUTO"))));

            assertThat(existing.getPreviousTransformExpr()).isEqualTo("id");
            assertThat(existing.getTransformExpr()).isEqualTo("CAST(id AS BIGINT)");
            assertThat(existing.getPreviousChangedAt()).isNotNull();
            assertThat(stale.getStatus()).isEqualTo("SUPERSEDED");
            assertThat(stale.getSupersededAt()).isEqualTo(existing.getPreviousChangedAt());

            ArgumentCaptor<LineageEdge> captor = ArgumentCaptor.forClass(LineageEdge.class);
            verify(edgeMapper, atLeastOnce()).updateById(captor.capture());
        }
    }

    // ── P3-3 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("P3-3 Agent 工具结果整形")
    class AgentTools {

        private LineageAgentToolService tools(Set<String> viewable, GovernanceConsumerProvider... providers) {
            return new LineageAgentToolService(service(viewable, providers));
        }

        @Test
        @DisplayName("无下游证据：影响评估返回 INCONCLUSIVE，而不是“无影响”")
        void impactWithoutEvidenceIsInconclusive() {
            LineageNode start = column("lin_start", "ds_ods", "ods.orders.amount");
            when(nodeMapper.selectList(any())).thenReturn(List.of(start));
            when(edgeMapper.selectList(any())).thenReturn(List.of());

            ImpactResult result = tools(Set.of("ds_ods")).impactAnalysis("lin_start", null, 5, false, true);

            assertThat(result.status()).isEqualTo("INCONCLUSIVE");
            assertThat(result.reason()).isEqualTo(LineageAgentToolService.REASON_NO_DOWNSTREAM_EVIDENCE);
            assertThat(result.items()).isEmpty();
        }

        @Test
        @DisplayName("影响评估汇总下游字段/表与指标；证据为 ACTIVE 时 OK")
        void impactSummarizesDownstream() {
            LineageNode start = column("lin_start", "ds_ods", "ods.orders.amount");
            LineageNode target = column("lin_target", "ds_dws", "dws.summary.total_amt");
            when(nodeMapper.selectList(any())).thenReturn(List.of(start), List.of(start), List.of(target));
            when(edgeMapper.selectList(any())).thenReturn(List.of(edge("line_1", start.getId(), target.getId())));
            QualityRule rule = new QualityRule();
            rule.setId("qr_9");
            rule.setDatasetId("ds_dws");
            rule.setColumnId(target.getCatalogColumnId());
            rule.setRuleType("UNIQUE");
            rule.setEnabled(true);
            when(ruleMapper.selectList(any())).thenReturn(List.of(rule));

            ImpactResult result = tools(Set.of("ds_ods", "ds_dws")).impactAnalysis("lin_start", null, 5, false, true);

            assertThat(result.status()).isEqualTo("OK");
            assertThat(result.summary().downstreamColumns()).isEqualTo(1);
            assertThat(result.summary().downstreamTables()).isEqualTo(1);
            assertThat(result.summary().qualityRules()).isEqualTo(1);
            assertThat(result.items()).extracting(LineageAgentToolService.ImpactItem::nodeType)
                    .containsExactly("COLUMN", "QUALITY_RULE");
            assertThat(result.items().get(0).level()).isEqualTo(1);
            assertThat(result.items().get(1).level()).isEqualTo(2);
        }

        @Test
        @DisplayName("起点受限：INCONCLUSIVE/ROOT_RESTRICTED，不返回任何名称")
        void restrictedRootIsInconclusive() {
            LineageNode secret = column("lin_secret", "ds_secret", "hr.payroll.salary");
            when(nodeMapper.selectList(any())).thenReturn(List.of(secret));

            TraceResult trace = tools(Set.of()).traceColumn("lin_secret", "both", 3, false);
            ImpactResult impact = tools(Set.of()).impactAnalysis("lin_secret", null, 5, false, true);

            assertThat(trace.status()).isEqualTo("INCONCLUSIVE");
            assertThat(trace.reason()).isEqualTo(LineageAgentToolService.REASON_ROOT_RESTRICTED);
            assertThat(impact.reason()).isEqualTo(LineageAgentToolService.REASON_ROOT_RESTRICTED);
            assertThat(trace.toString() + impact).doesNotContain("payroll", "salary", "ds_secret");
        }

        @Test
        @DisplayName("溯源：上游逐跳 ACL 截断，受限分支只计数；仅 PLANNED 证据为 INCONCLUSIVE")
        void traceAppliesAclAndPlannedGate() {
            LineageNode target = column("lin_t", "ds_ok", "dwd.t.c");
            LineageNode secret = column("lin_s", "ds_secret", "hr.s.c");
            LineageNode mid = column("lin_mid", "ds_ok2", "ods.mid.c");
            when(nodeMapper.selectList(any())).thenReturn(List.of(target), List.of(target), List.of(mid, secret));
            when(edgeMapper.selectList(any())).thenReturn(
                    List.of(edge("line_1", mid.getId(), target.getId()), edge("line_2", secret.getId(), target.getId())),
                    List.of());

            TraceResult result = tools(Set.of("ds_ok", "ds_ok2")).traceColumn("lin_t", "upstream", 3, false);

            assertThat(result.status()).isEqualTo("OK");
            assertThat(result.restrictedCount()).isEqualTo(1);
            assertThat(result.truncatedByAcl()).isTrue();
            assertThat(result.nodes()).extracting(LineageAgentToolService.TraceNode::qualifiedName)
                    .containsExactly("ods.mid.c");
            assertThat(result.toString()).doesNotContain("hr.s.c", "ds_secret", "lin_s");
        }

        @Test
        @DisplayName("溯源：节点从未建立血缘 → INCONCLUSIVE/NODE_NOT_FOUND")
        void unknownNodeIsInconclusive() {
            when(nodeMapper.selectList(any())).thenReturn(List.of());

            TraceResult result = tools(Set.of()).traceColumn("col_unknown", "both", 3, false);

            assertThat(result.status()).isEqualTo("INCONCLUSIVE");
            assertThat(result.reason()).isEqualTo(LineageAgentToolService.REASON_NODE_NOT_FOUND);
        }

        @Test
        @DisplayName("参数校验：缺 columnId / 非法 direction / impact 缺目标")
        void validatesArguments() {
            assertThatThrownBy(() -> tools(Set.of()).traceColumn(" ", "both", 3, false))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> tools(Set.of()).traceColumn("x", "sideways", 3, false))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> tools(Set.of()).impactAnalysis(null, null, 3, false, true))
                    .isInstanceOf(BizException.class);
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static CatalogDataset dataset(String id, String qualifiedName) {
        CatalogDataset dataset = new CatalogDataset();
        dataset.setId(id);
        dataset.setWorkspaceId(WORKSPACE);
        dataset.setName(qualifiedName.substring(qualifiedName.lastIndexOf('/') + 1));
        dataset.setQualifiedName(qualifiedName);
        return dataset;
    }

    private static LineageNode column(String id, String datasetId, String qualifiedName) {
        LineageNode node = node(id, "COLUMN", qualifiedName);
        node.setCatalogDatasetId(datasetId);
        node.setCatalogColumnId("col_" + id);
        return node;
    }

    private static LineageNode node(String id, String type, String qualifiedName) {
        LineageNode node = new LineageNode();
        node.setId(id);
        node.setWorkspaceId(WORKSPACE);
        node.setNodeType(type);
        node.setQualifiedName(qualifiedName);
        node.setDisplayName(qualifiedName);
        node.setCreatedAt(Instant.now());
        return node;
    }

    private static LineageEdge edge(String id, String from, String to) {
        LineageEdge edge = new LineageEdge();
        edge.setId(id);
        edge.setWorkspaceId(WORKSPACE);
        edge.setFromNodeId(from);
        edge.setToNodeId(to);
        edge.setRelation("DERIVE");
        edge.setStatus("ACTIVE");
        return edge;
    }
}
