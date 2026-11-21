package com.datagov.metadata.service;

import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.LineageEntities.LineageEdge;
import com.datagov.metadata.entity.LineageEntities.LineageJob;
import com.datagov.metadata.entity.LineageEntities.LineageNode;
import com.datagov.metadata.entity.SensitivityEntities.ColumnSensitivity;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.ColumnSensitivityMapper;
import com.datagov.metadata.mapper.LineageEdgeMapper;
import com.datagov.metadata.mapper.LineageJobMapper;
import com.datagov.metadata.mapper.LineageNodeMapper;
import com.datagov.metadata.service.LineageService.EdgeInput;
import com.datagov.metadata.service.LineageService.GraphView;
import com.datagov.metadata.service.LineageService.JobRequest;
import com.datagov.metadata.service.LineageService.JobResult;
import com.datagov.metadata.service.LineageService.NodeRef;
import com.datagov.metadata.service.lineage.LineageAclEvaluator;
import com.datagov.metadata.service.lineage.LineageSensitivityPropagator;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("字段级血缘")
class LineageServiceTest {

    private static final String WORKSPACE = "ws_alpha";
    private static final String USER = "usr_1";

    private LineageNodeMapper nodeMapper;
    private LineageJobMapper jobMapper;
    private LineageEdgeMapper edgeMapper;
    private CatalogDatasetMapper datasetMapper;
    private LineageService service;

    @BeforeEach
    void setUp() {
        nodeMapper = mock(LineageNodeMapper.class);
        jobMapper = mock(LineageJobMapper.class);
        edgeMapper = mock(LineageEdgeMapper.class);
        datasetMapper = mock(CatalogDatasetMapper.class);
        service = new LineageService(nodeMapper, jobMapper, edgeMapper, datasetMapper);
        WorkspaceContext.set(new Caller(USER, "alice", WORKSPACE, false, Set.of()));

        when(jobMapper.insert(any(LineageJob.class))).thenReturn(1);
        when(jobMapper.updateById(any(LineageJob.class))).thenReturn(1);
        when(nodeMapper.insert(any(LineageNode.class))).thenReturn(1);
        when(edgeMapper.insert(any(LineageEdge.class))).thenReturn(1);
        when(edgeMapper.updateById(any(LineageEdge.class))).thenReturn(1);
        when(edgeMapper.selectList(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Nested
    @DisplayName("写入")
    class Write {

        @Test
        @DisplayName("首次上报创建 job 与两个节点")
        void recordFirstJob() {
            JobResult result = service.recordJob(jobRequest());

            assertThat(result.jobId()).startsWith("linj_");
            assertThat(result.edges()).isEqualTo(1);
            verify(jobMapper).insert(any(LineageJob.class));
            verify(nodeMapper, times(2)).insert(any(LineageNode.class));
            verify(edgeMapper).insert(any(LineageEdge.class));
        }

        @Test
        @DisplayName("重复上报同一作业:复用节点与边,不新增边")
        void repeatedJobReusesEdge() {
            LineageJob job = new LineageJob();
            job.setId("linj_1");
            job.setWorkspaceId(WORKSPACE);
            job.setJobType("SQL");
            job.setName("订单宽表");
            when(jobMapper.selectOne(any())).thenReturn(job);

            LineageNode fromNode = node("lin_from", "COLUMN", "ods.orders.id");
            LineageNode toNode = node("lin_to", "COLUMN", "dwd.orders.id");
            when(nodeMapper.selectOne(any())).thenReturn(fromNode, toNode);

            LineageEdge edge = new LineageEdge();
            edge.setId("line_1");
            edge.setWorkspaceId(WORKSPACE);
            edge.setFromNodeId(fromNode.getId());
            edge.setToNodeId(toNode.getId());
            edge.setRelation("DERIVE");
            edge.setJobId(job.getId());
            edge.setStatus("ACTIVE");
            when(edgeMapper.selectList(any())).thenReturn(List.of(edge));

            JobResult result = service.recordJob(jobRequest());

            assertThat(result.edges()).isEqualTo(1);
            verify(edgeMapper, never()).insert(any(LineageEdge.class));
            verify(edgeMapper).updateById(edge);
        }
    }

    @Nested
    @DisplayName("遍历")
    class Graph {

        @Test
        @DisplayName("按字段 ID 解析节点:重复节点不抛 TooManyResults")
        void resolvesCatalogColumnIdWithDuplicateNodes() {
            LineageNode first = node("lin_first", "COLUMN", "dwd.orders.amount");
            first.setCatalogColumnId("col_1");
            LineageNode second = node("lin_second", "COLUMN", "dwd.orders.amount_v2");
            second.setCatalogColumnId("col_1");
            when(nodeMapper.selectList(any())).thenReturn(List.of(first, second));

            assertThat(service.getNode("col_1").id()).isEqualTo("lin_first");
            assertThat(service.getNode("lin_second").id()).isEqualTo("lin_second");
        }

        @Test
        @DisplayName("表级血缘把同一对表的列级边聚合成一条")
        void aggregatesColumnEdgesByDataset() {
            CatalogDataset sourceDataset = dataset("catds_src", "ods/sales/orders");
            CatalogDataset targetDataset = dataset("catds_dst", "dwd/sales/orders");
            LineageNode source = node("lin_src", "COLUMN", "ods/sales/orders.id");
            source.setCatalogDatasetId(sourceDataset.getId());
            source.setCatalogColumnId("col_src");
            LineageNode target = node("lin_dst", "COLUMN", "dwd/sales/orders.id");
            target.setCatalogDatasetId(targetDataset.getId());
            target.setCatalogColumnId("col_dst");
            LineageEdge edge = edge("line_ds", source.getId(), target.getId());

            when(datasetMapper.selectOne(any())).thenReturn(sourceDataset);
            when(nodeMapper.selectList(any())).thenReturn(
                    List.of(source), List.of(source, target));
            when(edgeMapper.selectList(any())).thenReturn(List.of(edge));
            when(datasetMapper.selectList(any())).thenReturn(
                    List.of(sourceDataset, targetDataset));

            GraphView graph = service.datasetGraph(sourceDataset.getId(), "downstream", 1);

            assertThat(graph.nodes()).hasSize(2);
            assertThat(graph.edges()).hasSize(1);
            assertThat(graph.edges().get(0).transformExpr()).isEqualTo("1 条列级血缘");
            assertThat(graph.edges().get(0).origin()).isEqualTo("AGGREGATED");
        }

        @Test
        @DisplayName("表级血缘跳过没有数据集归属的列级边")
        void skipsEdgesWithoutDatasetId() {
            CatalogDataset sourceDataset = dataset("catds_src", "ods/sales/orders");
            LineageNode source = node("lin_src", "COLUMN", "ods/sales/orders.id");
            source.setCatalogDatasetId(sourceDataset.getId());
            LineageNode target = node("lin_dst", "COLUMN", "unknown.column");
            LineageEdge edge = edge("line_ds", source.getId(), target.getId());

            when(datasetMapper.selectOne(any())).thenReturn(sourceDataset);
            when(nodeMapper.selectList(any())).thenReturn(
                    List.of(source), List.of(source, target));
            when(edgeMapper.selectList(any())).thenReturn(List.of(edge));

            GraphView graph = service.datasetGraph(sourceDataset.getId(), "downstream", 1);

            assertThat(graph.nodes()).isEmpty();
            assertThat(graph.edges()).isEmpty();
            assertThat(graph.unattributedEdges()).isEqualTo(1);
        }

        @Test
        @DisplayName("上游遍历返回节点与边")
        void upstream() {
            LineageNode target = node("lin_target", "COLUMN", "dwd.orders.amount");
            LineageNode source = node("lin_source", "COLUMN", "ods.orders.amount");
            when(nodeMapper.selectOne(any())).thenReturn(target);
            when(nodeMapper.selectList(any())).thenReturn(List.of(target, source));

            LineageEdge edge = new LineageEdge();
            edge.setId("line_1");
            edge.setWorkspaceId(WORKSPACE);
            edge.setFromNodeId(source.getId());
            edge.setToNodeId(target.getId());
            edge.setRelation("DERIVE");
            edge.setStatus("ACTIVE");
            when(edgeMapper.selectList(any())).thenReturn(List.of(edge));

            GraphView graph = service.upstream(target.getId(), 3);

            assertThat(graph.nodes()).hasSize(2);
            assertThat(graph.edges()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("逐跳 ACL(C5)")
    class HopByHopAcl {

        private LineageService aclService(Set<String> viewableDatasets) {
            LineageAclEvaluator evaluator = (caller, ids) -> ids.stream()
                    .filter(viewableDatasets::contains)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            return new LineageService(nodeMapper, jobMapper, edgeMapper, datasetMapper, evaluator, null);
        }

        private LineageNode column(String id, String datasetId, String qualifiedName) {
            LineageNode node = node(id, "COLUMN", qualifiedName);
            node.setCatalogDatasetId(datasetId);
            node.setCatalogColumnId("col_" + id);
            return node;
        }

        @Test
        @DisplayName("上游遇到无权数据集：替换为占位节点、截断分支，且不泄露表名/列名/SQL")
        void upstreamTruncatesAtRestrictedDataset() {
            LineageNode target = column("lin_target", "ds_ok", "dwd.orders.amount");
            LineageNode secret = column("lin_secret", "ds_secret", "secret_hr.salary.amount");
            LineageEdge edge = edge("line_secret", secret.getId(), target.getId());
            edge.setTransformExpr("SELECT salary FROM secret_hr.salary");
            edge.setJobId("linj_secret");
            edge.setConfidence(0.9);

            // 第 1 次：findNode 起点；第 2 次：第一跳的上游节点
            when(nodeMapper.selectList(any())).thenReturn(List.of(target), List.of(secret));
            when(edgeMapper.selectList(any())).thenReturn(List.of(edge));

            GraphView graph = aclService(Set.of("ds_ok")).upstream(target.getId(), 5);

            assertThat(graph.restrictedCount()).isEqualTo(1);
            assertThat(graph.truncatedByAcl()).isTrue();
            assertThat(graph.nodes()).hasSize(2);
            LineageService.NodeView placeholder = graph.nodes().stream()
                    .filter(n -> "RESTRICTED".equals(n.nodeType())).findFirst().orElseThrow();
            assertThat(placeholder.id()).startsWith("restricted:").doesNotContain(secret.getId());
            assertThat(placeholder.qualifiedName()).isEqualTo("[受限资产]");
            assertThat(placeholder.displayName()).isEqualTo("[受限资产]");
            assertThat(placeholder.catalogDatasetId()).isNull();
            assertThat(placeholder.catalogColumnId()).isNull();

            assertThat(graph.edges()).hasSize(1);
            LineageService.EdgeView restricted = graph.edges().get(0);
            assertThat(restricted.fromNodeId()).isEqualTo(placeholder.id());
            assertThat(restricted.toNodeId()).isEqualTo(target.getId());
            assertThat(restricted.id()).startsWith("restricted-edge:").doesNotContain(edge.getId());
            assertThat(restricted.transformExpr()).isNull();
            assertThat(restricted.jobId()).isNull();
            assertThat(restricted.confidence()).isNull();

            String everything = graph.toString();
            assertThat(everything).doesNotContain("secret_hr", "salary", "lin_secret", "ds_secret", "linj_secret");
            // 受限节点不进入下一层 frontier：只查询了一次边（起点那一层）。
            verify(edgeMapper, times(1)).selectList(any());
        }

        @Test
        @DisplayName("有权节点继续逐跳遍历；受限分支只截断自己")
        void viewableBranchContinuesWhileRestrictedBranchStops() {
            LineageNode target = column("lin_t", "ds_ok", "dwd.t.c");
            LineageNode mid = column("lin_mid", "ds_ok2", "ods.mid.c");
            LineageNode secret = column("lin_s", "ds_secret", "hr.s.c");
            LineageNode root = column("lin_root", "ds_ok3", "src.root.c");
            LineageEdge e1 = edge("line_1", mid.getId(), target.getId());
            LineageEdge e2 = edge("line_2", secret.getId(), target.getId());
            LineageEdge e3 = edge("line_3", root.getId(), mid.getId());
            when(nodeMapper.selectList(any())).thenReturn(
                    List.of(target), List.of(mid, secret), List.of(root));
            when(edgeMapper.selectList(any())).thenReturn(List.of(e1, e2), List.of(e3), List.of());

            GraphView graph = aclService(Set.of("ds_ok", "ds_ok2", "ds_ok3")).upstream(target.getId(), 5);

            assertThat(graph.restrictedCount()).isEqualTo(1);
            assertThat(graph.nodes()).extracting(LineageService.NodeView::nodeType)
                    .containsOnly("COLUMN", "RESTRICTED");
            assertThat(graph.nodes()).hasSize(4); // target + mid + root + 占位
            assertThat(graph.edges()).hasSize(3);
            // 目标层、mid 层、root 层各查一次；受限节点没有自己的一层。
            verify(edgeMapper, times(3)).selectList(any());
        }

        @Test
        @DisplayName("起点本身无权：只返回一个占位，不返回任何边")
        void restrictedStartReturnsOnlyPlaceholder() {
            LineageNode start = column("lin_start", "ds_secret", "hr.payroll.salary");
            when(nodeMapper.selectList(any())).thenReturn(List.of(start));

            GraphView graph = aclService(Set.of()).downstream(start.getId(), 3);

            assertThat(graph.nodes()).hasSize(1);
            assertThat(graph.nodes().get(0).nodeType()).isEqualTo("RESTRICTED");
            assertThat(graph.edges()).isEmpty();
            assertThat(graph.restrictedCount()).isEqualTo(1);
            assertThat(graph.truncatedByAcl()).isTrue();
            assertThat(graph.toString()).doesNotContain("payroll", "salary", "ds_secret");
            verify(edgeMapper, never()).selectList(any());
        }

        @Test
        @DisplayName("影响分析走同一 ACL，没有绕过路径")
        void impactAppliesAcl() {
            LineageNode start = column("lin_start", "ds_ok", "ods.a.c");
            LineageNode secret = column("lin_s", "ds_secret", "hr.b.c");
            when(nodeMapper.selectList(any())).thenReturn(List.of(start), List.of(secret));
            when(edgeMapper.selectList(any())).thenReturn(List.of(edge("line_1", start.getId(), secret.getId())));

            GraphView graph = aclService(Set.of("ds_ok")).impact(start.getId(), 5);

            assertThat(graph.restrictedCount()).isEqualTo(1);
            assertThat(graph.toString()).doesNotContain("hr.b.c", "ds_secret");
        }

        @Test
        @DisplayName("表级聚合：无权数据集以占位出现，且不披露条数")
        void datasetGraphReplacesRestrictedDatasetWithPlaceholder() {
            CatalogDataset source = dataset("catds_src", "ods/sales/orders");
            LineageNode src = column("lin_src", "catds_src", "ods/sales/orders.id");
            LineageNode secret = column("lin_dst", "catds_secret", "hr/payroll/salary.amount");
            when(datasetMapper.selectOne(any())).thenReturn(source);
            when(nodeMapper.selectList(any())).thenReturn(
                    List.of(src), List.of(secret), List.of(src, secret));
            when(edgeMapper.selectList(any())).thenReturn(List.of(edge("line_1", src.getId(), secret.getId())));
            when(datasetMapper.selectList(any())).thenReturn(List.of(source));

            GraphView graph = aclService(Set.of("catds_src")).datasetGraph("catds_src", "downstream", 2);

            assertThat(graph.restrictedCount()).isEqualTo(1);
            assertThat(graph.truncatedByAcl()).isTrue();
            assertThat(graph.nodes()).hasSize(2);
            assertThat(graph.nodes()).extracting(LineageService.NodeView::nodeType)
                    .containsExactlyInAnyOrder("DATASET", "RESTRICTED");
            assertThat(graph.edges()).hasSize(1);
            assertThat(graph.edges().get(0).transformExpr()).isEqualTo("[受限资产]");
            assertThat(graph.toString()).doesNotContain("payroll", "salary", "catds_secret", "条列级血缘");
            verify(edgeMapper, times(1)).selectList(any());
        }

        @Test
        @DisplayName("表级聚合：起点数据集无权时只回占位")
        void datasetGraphRestrictedStart() {
            CatalogDataset source = dataset("catds_src", "hr/payroll/salary");
            when(datasetMapper.selectOne(any())).thenReturn(source);

            GraphView graph = aclService(Set.of()).datasetGraph("catds_src", "both", 2);

            assertThat(graph.nodes()).hasSize(1);
            assertThat(graph.nodes().get(0).nodeType()).isEqualTo("RESTRICTED");
            assertThat(graph.toString()).doesNotContain("payroll", "salary", "catds_src");
            verify(nodeMapper, never()).selectList(any());
        }

        @Test
        @DisplayName("没有数据集归属的节点不是资产，按可见处理")
        void nodesWithoutDatasetAreVisible() {
            LineageNode start = column("lin_start", "ds_ok", "ods.a.c");
            LineageNode file = node("lin_file", "COLUMN", "s3://bucket/file.csv#name");
            when(nodeMapper.selectList(any())).thenReturn(List.of(start), List.of(file));
            when(edgeMapper.selectList(any())).thenReturn(List.of(edge("line_1", file.getId(), start.getId())));

            GraphView graph = aclService(Set.of("ds_ok")).upstream(start.getId(), 1);

            assertThat(graph.restrictedCount()).isZero();
            assertThat(graph.nodes()).hasSize(2);
        }

        @Test
        @DisplayName("直接取受限字段节点只返回占位")
        void getNodeIsAclProtected() {
            LineageNode secret = column("lin_s", "ds_secret", "hr.payroll.salary");
            when(nodeMapper.selectList(any())).thenReturn(List.of(secret));

            LineageService.NodeView view = aclService(Set.of()).getNode("lin_s");

            assertThat(view.nodeType()).isEqualTo("RESTRICTED");
            assertThat(view.qualifiedName()).isEqualTo("[受限资产]");
        }
    }

    @Nested
    @DisplayName("includePlanned")
    class IncludePlanned {

        @org.junit.jupiter.api.BeforeAll
        static void initLambdaCache() {
            // 让 LambdaQueryWrapper 能在没有 Spring/MyBatis 的单测里解析列名，从而检查真实查询条件。
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(
                            new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                    LineageEdge.class);
        }

        @SuppressWarnings("unchecked")
        private java.util.Collection<Object> queriedValues() {
            ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<LineageEdge>> captor =
                    ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
            verify(edgeMapper, org.mockito.Mockito.atLeastOnce()).selectList(captor.capture());
            com.baomidou.mybatisplus.core.conditions.AbstractWrapper<LineageEdge, ?, ?> wrapper =
                    (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<LineageEdge, ?, ?>)
                            captor.getValue();
            wrapper.getSqlSegment(); // 触发参数物化
            return wrapper.getParamNameValuePairs().values();
        }

        @Test
        @DisplayName("默认只查 ACTIVE")
        void defaultsToActiveOnly() {
            LineageNode start = node("lin_start", "COLUMN", "ods.a.c");
            when(nodeMapper.selectList(any())).thenReturn(List.of(start));

            service.downstream(start.getId(), 2);

            assertThat(queriedValues()).contains("ACTIVE").doesNotContain("PLANNED");
        }

        @Test
        @DisplayName("includePlanned=true 同时查 PLANNED，列级与表级一致")
        void includesPlannedWhenRequested() {
            LineageNode start = node("lin_start", "COLUMN", "ods.a.c");
            when(nodeMapper.selectList(any())).thenReturn(List.of(start));

            service.upstream(start.getId(), 2, true);
            assertThat(queriedValues()).contains("ACTIVE", "PLANNED");
        }

        @Test
        @DisplayName("表级聚合：只含 PLANNED 的数据集对标 PLANNED 状态")
        void datasetGraphMarksPlannedOnlyAggregate() {
            CatalogDataset source = dataset("catds_src", "ods/a");
            CatalogDataset target = dataset("catds_dst", "dwd/b");
            LineageNode src = node("lin_src", "COLUMN", "ods/a.id");
            src.setCatalogDatasetId("catds_src");
            LineageNode dst = node("lin_dst", "COLUMN", "dwd/b.id");
            dst.setCatalogDatasetId("catds_dst");
            LineageEdge planned = edge("line_p", src.getId(), dst.getId());
            planned.setStatus("PLANNED");
            when(datasetMapper.selectOne(any())).thenReturn(source);
            when(nodeMapper.selectList(any())).thenReturn(
                    List.of(src), List.of(dst), List.of(src, dst));
            when(edgeMapper.selectList(any())).thenReturn(List.of(planned));
            when(datasetMapper.selectList(any())).thenReturn(List.of(source, target));

            GraphView graph = service.datasetGraph("catds_src", "downstream", 1, true);

            assertThat(graph.edges()).hasSize(1);
            assertThat(graph.edges().get(0).status()).isEqualTo("PLANNED");
        }
    }

    @Nested
    @DisplayName("敏感标签传播触发")
    class SensitivityTrigger {

        @Test
        @DisplayName("ACTIVE 上报触发传播；PLANNED 不触发")
        void activeTriggersPlannedDoesNot() {
            LineageSensitivityPropagator propagator = mock(LineageSensitivityPropagator.class);
            LineageService withPropagator = new LineageService(nodeMapper, jobMapper, edgeMapper,
                    datasetMapper, LineageAclEvaluator.allowAll(), propagator);

            withPropagator.recordPlannedJob(jobRequest());
            verify(propagator, never()).propagateFromEdges(any(), any());

            withPropagator.recordJob(jobRequest());
            verify(propagator, times(1)).propagateFromEdges(eq(WORKSPACE), any());
        }

        @Test
        @DisplayName("INDIRECT 边不触发传播，传播失败不影响血缘写入")
        void indirectSkippedAndFailuresAreContained() {
            LineageSensitivityPropagator propagator = mock(LineageSensitivityPropagator.class);
            LineageService withPropagator = new LineageService(nodeMapper, jobMapper, edgeMapper,
                    datasetMapper, LineageAclEvaluator.allowAll(), propagator);

            withPropagator.recordJob(new JobRequest("j", "SQL", "s", "h", List.of(new EdgeInput(
                    new NodeRef("COLUMN", "a.b.c", null, null, "c"),
                    new NodeRef("COLUMN", "x.y.z", null, null, "z"),
                    "FILTER", "WHERE c > 1", 1.0, "AUTO", "INDIRECT", false, null))));
            verify(propagator, never()).propagateFromEdges(any(), any());

            org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                    .when(propagator).propagateFromEdges(any(), any());
            JobResult result = withPropagator.recordJob(jobRequest());
            assertThat(result.edges()).isEqualTo(1);
        }
    }

    private static JobRequest jobRequest() {
        return new JobRequest("订单宽表", "SQL", "job_1", "a".repeat(64),
                List.of(new EdgeInput(
                        new NodeRef("COLUMN", "ods.orders.id", "ds_ods", "col_1", "id"),
                        new NodeRef("COLUMN", "dwd.orders.id", "ds_dwd", "col_2", "id"),
                        "DERIVE", "id", 1.0, "AUTO")));
    }

    private static CatalogDataset dataset(String id, String qualifiedName) {
        CatalogDataset dataset = new CatalogDataset();
        dataset.setId(id);
        dataset.setWorkspaceId(WORKSPACE);
        dataset.setName(qualifiedName.substring(qualifiedName.lastIndexOf('/') + 1));
        dataset.setQualifiedName(qualifiedName);
        return dataset;
    }

    @Nested
    @DisplayName("敏感徽标(P2-2)")
    class SensitivityBadge {

        @Test
        @DisplayName("图 API 为列节点填充 CONFIRMED 最高敏感等级")
        void graphAnnotatesMaxConfirmedLevel() {
            ColumnSensitivityMapper sensitivityMapper = mock(ColumnSensitivityMapper.class);
            LineageService withBadges = new LineageService(nodeMapper, jobMapper, edgeMapper, datasetMapper,
                    LineageAclEvaluator.allowAll(), null, null, sensitivityMapper);

            LineageNode target = node("lin_col", "COLUMN", "dwd.orders.amount");
            target.setCatalogDatasetId("ds_orders");
            target.setCatalogColumnId("col_amount");
            when(nodeMapper.selectList(any())).thenReturn(List.of(target));

            ColumnSensitivity confirmed = new ColumnSensitivity();
            confirmed.setWorkspaceId(WORKSPACE);
            confirmed.setDatasetId("ds_orders");
            confirmed.setColumnId("col_amount");
            confirmed.setStatus("CONFIRMED");
            confirmed.setLevel("L4");
            when(sensitivityMapper.selectList(any())).thenReturn(List.of(confirmed));

            GraphView graph = withBadges.upstream(target.getId(), 1);

            assertThat(graph.nodes()).extracting(LineageService.NodeView::sensitivityLevel)
                    .containsExactly("L4");
        }
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
}
