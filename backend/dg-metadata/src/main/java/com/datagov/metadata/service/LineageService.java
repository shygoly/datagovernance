package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
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
import com.datagov.metadata.service.lineage.LineageAclEvaluator;
import com.datagov.metadata.service.lineage.LineageSensitivityPropagator;
import com.datagov.metadata.service.lineage.GovernanceConsumer;
import com.datagov.metadata.service.lineage.SemanticLineageBridge;
import com.datagov.metadata.service.lineage.SemanticLineageBridge.ConsumerAnchor;
import com.datagov.metadata.service.lineage.SemanticLineageBridge.ConsumerLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 字段级血缘(P10.4)。
 *
 * <p>边是“谁从谁派生”的事实,不删除;SQL 变化时旧边标 SUPERSEDED。
 * 同一作业的同一组 from/to/relation 复用时原地更新,避免撞唯一索引。
 *
 * <p>1000 张表的规模用 BFS + PG 邻接表足够;不引入图数据库。
 */
@Service
public class LineageService {

    private static final int MAX_DEPTH = 5;
    private static final String STATUS_PLANNED = "PLANNED";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";

    private static final Logger log = LoggerFactory.getLogger(LineageService.class);

    /** 受限占位节点的固定展示文案：不得带任何真实表名/列名。 */
    public static final String RESTRICTED_NODE_TYPE = "RESTRICTED";
    public static final String RESTRICTED_LABEL = "[受限资产]";

    private final LineageNodeMapper nodeMapper;
    private final LineageJobMapper jobMapper;
    private final LineageEdgeMapper edgeMapper;
    private final CatalogDatasetMapper datasetMapper;
    private final LineageAclEvaluator aclEvaluator;
    private final LineageSensitivityPropagator sensitivityPropagator;
    private final SemanticLineageBridge governanceBridge;
    private final ColumnSensitivityMapper sensitivityMapper;

    /** 仅供单元测试：无 ACL（放行一切）、无敏感传播。生产装配走下面带 ACL 的构造器。 */
    public LineageService(LineageNodeMapper nodeMapper, LineageJobMapper jobMapper,
                          LineageEdgeMapper edgeMapper, CatalogDatasetMapper datasetMapper) {
        this(nodeMapper, jobMapper, edgeMapper, datasetMapper, LineageAclEvaluator.allowAll(), null, null, null);
    }

    /** 兼容既有测试构造器：未装配治理消费方桥（includeGovernance 请求被忽略）。 */
    public LineageService(LineageNodeMapper nodeMapper, LineageJobMapper jobMapper,
                          LineageEdgeMapper edgeMapper, CatalogDatasetMapper datasetMapper,
                          LineageAclEvaluator aclEvaluator,
                          LineageSensitivityPropagator sensitivityPropagator) {
        this(nodeMapper, jobMapper, edgeMapper, datasetMapper, aclEvaluator, sensitivityPropagator, null, null);
    }

    /** 兼容既有测试构造器：未装配敏感标签 mapper（图 API 不返回敏感徽标）。 */
    public LineageService(LineageNodeMapper nodeMapper, LineageJobMapper jobMapper,
                          LineageEdgeMapper edgeMapper, CatalogDatasetMapper datasetMapper,
                          LineageAclEvaluator aclEvaluator,
                          LineageSensitivityPropagator sensitivityPropagator,
                          SemanticLineageBridge governanceBridge) {
        this(nodeMapper, jobMapper, edgeMapper, datasetMapper, aclEvaluator, sensitivityPropagator,
                governanceBridge, null);
    }

    @Autowired
    public LineageService(LineageNodeMapper nodeMapper, LineageJobMapper jobMapper,
                          LineageEdgeMapper edgeMapper, CatalogDatasetMapper datasetMapper,
                          LineageAclEvaluator aclEvaluator,
                          LineageSensitivityPropagator sensitivityPropagator,
                          SemanticLineageBridge governanceBridge,
                          ColumnSensitivityMapper sensitivityMapper) {
        this.governanceBridge = governanceBridge;
        this.nodeMapper = nodeMapper;
        this.jobMapper = jobMapper;
        this.edgeMapper = edgeMapper;
        this.datasetMapper = datasetMapper;
        this.aclEvaluator = Objects.requireNonNull(aclEvaluator, "aclEvaluator");
        this.sensitivityPropagator = sensitivityPropagator;
        this.sensitivityMapper = sensitivityMapper;
    }

    public record NodeRef(String nodeType, String qualifiedName,
                          String catalogDatasetId, String catalogColumnId,
                          String displayName) {
    }

    public record EdgeInput(NodeRef from, NodeRef to, String relation,
                            String transformExpr, Double confidence, String origin,
                            String dependencyType, Boolean maskingApplied, String maskingEvidence) {
        public EdgeInput(NodeRef from, NodeRef to, String relation,
                         String transformExpr, Double confidence, String origin) {
            this(from, to, relation, transformExpr, confidence, origin, "DIRECT", false, null);
        }
    }

    public record JobRequest(String name, String jobType, String sourceRef,
                             String sqlHash, List<EdgeInput> edges,
                             String parseStatus, String parseMessage) {
        public JobRequest(String name, String jobType, String sourceRef,
                          String sqlHash, List<EdgeInput> edges) {
            this(name, jobType, sourceRef, sqlHash, edges, "OK", null);
        }
    }

    public record JobResult(String jobId, int edges) {
    }

    public record NodeView(String id, String nodeType, String qualifiedName,
                           String catalogDatasetId, String catalogColumnId,
                           String displayName, String externalRefType, String externalRefId,
                           String sensitivityLevel) {
        static NodeView from(LineageNode node) {
            return new NodeView(node.getId(), node.getNodeType(), node.getQualifiedName(),
                    node.getCatalogDatasetId(), node.getCatalogColumnId(), node.getDisplayName(),
                    node.getExternalRefType(), node.getExternalRefId(), null);
        }
    }

    public record EdgeView(String id, String fromNodeId, String toNodeId, String relation,
                           String transformExpr, Double confidence, String origin, String status,
                           String jobId, String jobName, String jobType, String executionId,
                           String dependencyType, Boolean maskingApplied, String maskingEvidence,
                           Instant discoveredAt) {
        static EdgeView from(LineageEdge edge) {
            return from(edge, null);
        }

        static EdgeView from(LineageEdge edge, LineageJob job) {
            return new EdgeView(edge.getId(), edge.getFromNodeId(), edge.getToNodeId(),
                    edge.getRelation(), edge.getTransformExpr(), edge.getConfidence(),
                    edge.getOrigin(), edge.getStatus(),
                    edge.getJobId(),
                    job == null ? null : job.getName(),
                    job == null ? null : job.getJobType(),
                    edge.getExecutionId(),
                    edge.getDependencyType() == null ? "DIRECT" : edge.getDependencyType(),
                    Boolean.TRUE.equals(edge.getMaskingApplied()),
                    edge.getMaskingEvidence(),
                    edge.getDiscoveredAt());
        }
    }

    public record GraphView(List<NodeView> nodes, List<EdgeView> edges,
                            int unattributedEdges, int restrictedCount, boolean truncatedByAcl) {
        public GraphView(List<NodeView> nodes, List<EdgeView> edges) {
            this(nodes, edges, 0, 0, false);
        }

        public GraphView(List<NodeView> nodes, List<EdgeView> edges, int unattributedEdges) {
            this(nodes, edges, unattributedEdges, 0, false);
        }
    }

    public record OverviewDataset(String datasetId, String name, String qualifiedName,
                                  int upstreamEdges, int downstreamEdges, Instant lastDiscoveredAt) {
    }

    public record OverviewJob(String jobId, String name, String jobType, String parseStatus,
                              String parseMessage, Instant lastSeenAt, int activeEdges, int plannedEdges) {
    }

    /** 字段级上下游活跃边数（catalogColumnId → 计数）；供前端字段下拉徽标与默认选中有边字段。 */
    public record ColumnEdgeCount(int upstreamEdges, int downstreamEdges) {
    }

    public record OverviewView(int datasetsWithLineage, int activeColumnEdges, int activeJobs,
                               int pendingSensitivitySuggestions,
                               List<OverviewDataset> recentDatasets, List<OverviewJob> recentJobs,
                               Map<String, ColumnEdgeCount> columnEdgeCounts) {
        public OverviewView {
            columnEdgeCounts = columnEdgeCounts == null ? Map.of() : Map.copyOf(columnEdgeCounts);
        }
    }

    /**
     * 历史 Diff 的一行（P3-2）。触及受限数据集的行只保留连通性：名称为 {@code [受限资产]}，
     * 表达式一律隐藏，ID 哈希化。
     *
     * @param previousRelation / previousDependencyType / previousTransformExpr 仅 {@code modifiedTransform} 行有值：改写前的内容
     */
    public record DiffItem(String edgeId, String fromNodeId, String fromName,
                           String toNodeId, String toName, String relation, String dependencyType,
                           String transformExpr, String previousRelation, String previousDependencyType,
                           String previousTransformExpr, boolean restricted) {
    }

    /**
     * 同一作业「当前 ACTIVE」与「最近一批改动」的对比。
     *
     * <p>没有任何历史改动（从未有边被 SUPERSEDED 或被改写）时 {@code hasBaseline=false}，三个列表为空——
     * 不把整批 ACTIVE 边伪装成「新增」。
     */
    public record JobDiffView(String jobId, String jobName, String jobType, boolean hasBaseline,
                              Instant baselineAt, int activeEdges,
                              List<DiffItem> added, List<DiffItem> removed,
                              List<DiffItem> modifiedTransform,
                              int restrictedCount, boolean truncatedByAcl) {
    }

    // ── 写入 ────────────────────────────────────────────────────────────

    /**
     * 记录一次作业的列级血缘。重复上报同一作业时,旧边会被复用或标 SUPERSEDED,
     * 不会产生重复边。
     */
    @Transactional
    public JobResult recordJob(JobRequest request) {
        return recordJobInternal(request, STATUS_ACTIVE, null);
    }

    /** 人工补录一条边。同一 from/to/relation 复用时原地更新。 */
    @Transactional
    public EdgeView recordManualEdge(EdgeInput input) {
        validateEdge(input);
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        LineageNode from = upsertNode(workspaceId, input.from());
        LineageNode to = upsertNode(workspaceId, input.to());
        String relation = relation(input.relation());

        LineageEdge edge = edgeMapper.selectOne(new LambdaQueryWrapper<LineageEdge>()
                .eq(LineageEdge::getWorkspaceId, workspaceId)
                .eq(LineageEdge::getFromNodeId, from.getId())
                .eq(LineageEdge::getToNodeId, to.getId())
                .eq(LineageEdge::getRelation, relation)
                .isNull(LineageEdge::getJobId));
        if (edge == null) {
            edge = new LineageEdge();
            edge.setId(Ids.of("line"));
            edge.setWorkspaceId(workspaceId);
            edge.setFromNodeId(from.getId());
            edge.setToNodeId(to.getId());
            edge.setRelation(relation);
            edge.setDiscoveredAt(Instant.now());
            applyEdge(edge, input, null, STATUS_ACTIVE, null);
            edge.setOrigin("MANUAL");
            edgeMapper.insert(edge);
        } else {
            applyEdge(edge, input, null, STATUS_ACTIVE, null);
            edge.setOrigin("MANUAL");
            edgeMapper.updateById(edge);
        }
        propagateSensitivity(workspaceId, List.of(edge));
        return EdgeView.from(edge);
    }

    // ── 查询 ────────────────────────────────────────────────────────────

    public GraphView upstream(String nodeId, int depth) {
        return upstream(nodeId, depth, false);
    }

    /** @param includePlanned true 时除 ACTIVE 外同时返回 PLANNED（编译/发布预估）边 */
    public GraphView upstream(String nodeId, int depth, boolean includePlanned) {
        return graph(nodeId, true, depth, includePlanned);
    }

    public GraphView downstream(String nodeId, int depth) {
        return downstream(nodeId, depth, false);
    }

    public GraphView downstream(String nodeId, int depth, boolean includePlanned) {
        return downstream(nodeId, depth, includePlanned, false);
    }

    /**
     * @param includeGovernance true 时把下游的治理消费方（指标/分析报告/质检规则）作为
     *                          {@code METRIC / REPORT / QUALITY_RULE} 节点并入结果（P3-1）
     */
    public GraphView downstream(String nodeId, int depth, boolean includePlanned,
                                boolean includeGovernance) {
        return attachGovernance(graph(nodeId, false, depth, includePlanned), includeGovernance);
    }

    /** 影响分析就是下游遍历;单独命名让调用方读得懂意图。 */
    public GraphView impact(String nodeId, int depth) {
        return impact(nodeId, depth, false);
    }

    public GraphView impact(String nodeId, int depth, boolean includePlanned) {
        return impact(nodeId, depth, includePlanned, false);
    }

    public GraphView impact(String nodeId, int depth, boolean includePlanned, boolean includeGovernance) {
        return downstream(nodeId, depth, includePlanned, includeGovernance);
    }

    public GraphView datasetGraph(String datasetId, String direction, int depth) {
        return datasetGraph(datasetId, direction, depth, false);
    }

    /**
     * 表级血缘：列级事实按 catalogDatasetId 聚合。
     *
     * <p>表级视图不落新表：起点数据集的列节点先做 BFS，访问到的列级节点
     * 再按数据集分组，列级边按 fromDataset→toDataset 计数。缺少数据集归属
     * 的历史边会被跳过，避免表级图出现悬空节点。
     *
     * <p>C5 逐跳 ACL：BFS 每一跳都校验目标数据集；无权数据集不继续向更深处遍历，
     * 并以 {@code RESTRICTED} 占位节点出现在聚合结果里，不暴露表名。
     */
    public GraphView datasetGraph(String datasetId, String direction, int depth,
                                  boolean includePlanned) {
        return datasetGraph(datasetId, direction, depth, includePlanned, false);
    }

    /**
     * @param includeGovernance 仅对 downstream / both 生效：治理消费方是下游，upstream 方向没有消费方
     */
    public GraphView datasetGraph(String datasetId, String direction, int depth,
                                  boolean includePlanned, boolean includeGovernance) {
        GraphView base = datasetGraphBase(datasetId, direction, depth, includePlanned);
        if (!includeGovernance || governanceBridge == null) {
            return base;
        }
        String dir = direction == null || direction.isBlank() ? "both" : direction.trim().toLowerCase();
        if ("upstream".equals(dir)) {
            return base;
        }
        boolean hasRoot = base.nodes().stream()
                .anyMatch(node -> "DATASET".equals(node.nodeType()) && datasetId.equals(node.id()));
        if (!hasRoot) {
            if (base.truncatedByAcl()) {
                // 起点数据集本身无权：只有占位，没有任何可挂接消费方的可见锚点。
                return base;
            }
            // 起点可见但尚无列级血缘边：仍要能看到它自己的治理消费方。
            String workspaceId = WorkspaceContext.requireWorkspaceId();
            CatalogDataset root = datasetMapper.selectOne(new LambdaQueryWrapper<CatalogDataset>()
                    .eq(CatalogDataset::getId, datasetId)
                    .eq(CatalogDataset::getWorkspaceId, workspaceId));
            if (root == null) {
                return base;
            }
            List<NodeView> nodes = new ArrayList<>(base.nodes());
            nodes.add(new NodeView(root.getId(), "DATASET", root.getQualifiedName(),
                    root.getId(), null, root.getName(), null, null, null));
            base = enrichSensitivity(new GraphView(nodes, base.edges(), base.unattributedEdges(),
                    base.restrictedCount(), base.truncatedByAcl()));
        }
        return attachGovernance(base, true);
    }

    private GraphView datasetGraphBase(String datasetId, String direction, int depth,
                                       boolean includePlanned) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        CatalogDataset dataset = datasetMapper.selectOne(new LambdaQueryWrapper<CatalogDataset>()
                .eq(CatalogDataset::getId, datasetId)
                .eq(CatalogDataset::getWorkspaceId, workspaceId));
        if (dataset == null) {
            throw BizException.notFound(ErrorCode.MTD_CATALOG_ASSET_NOT_FOUND, datasetId);
        }

        String dir = direction == null || direction.isBlank()
                ? "both" : direction.trim().toLowerCase();
        boolean both = "both".equals(dir);
        boolean upstream = "upstream".equals(dir);
        boolean downstream = "downstream".equals(dir);
        if (!both && !upstream && !downstream) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "表级血缘方向只能是 upstream / downstream / both");
        }
        int levels = Math.max(1, Math.min(depth, MAX_DEPTH));

        AclGate gate = new AclGate(workspaceId, WorkspaceContext.require());
        if (gate.datasetRestricted(datasetId)) {
            // 起点数据集本身无权：只回一个占位，不返回任何列级事实。
            return restrictedOnly(gate.placeholderId(datasetId));
        }

        List<LineageNode> seeds = nodeMapper.selectList(new LambdaQueryWrapper<LineageNode>()
                .eq(LineageNode::getWorkspaceId, workspaceId)
                .eq(LineageNode::getNodeType, "COLUMN")
                .eq(LineageNode::getCatalogDatasetId, datasetId));
        if (seeds.isEmpty()) {
            return new GraphView(List.of(), List.of());
        }

        Set<String> seedIds = seeds.stream().map(LineageNode::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> nodeIds = new LinkedHashSet<>(seedIds);
        Map<String, LineageEdge> columnEdges = new LinkedHashMap<>();
        if (upstream || both) {
            collectColumnEdges(workspaceId, seedIds, true, levels, includePlanned, gate,
                    new LinkedHashSet<>(seedIds), nodeIds, columnEdges);
        }
        if (downstream || both) {
            collectColumnEdges(workspaceId, seedIds, false, levels, includePlanned, gate,
                    new LinkedHashSet<>(seedIds), nodeIds, columnEdges);
        }
        if (columnEdges.isEmpty()) {
            return new GraphView(List.of(), List.of());
        }

        Map<String, LineageNode> nodesById = nodeMapper.selectList(
                        new LambdaQueryWrapper<LineageNode>()
                                .eq(LineageNode::getWorkspaceId, workspaceId)
                                .in(LineageNode::getId, nodeIds)).stream()
                .collect(java.util.stream.Collectors.toMap(LineageNode::getId, node -> node));

        // 聚合键里的数据集 ID：受限数据集一律换成占位 ID，原 ID 不再出现在结果里。
        Set<String> datasetIds = new LinkedHashSet<>();
        datasetIds.add(datasetId);
        Set<String> restrictedPlaceholders = new LinkedHashSet<>();
        Map<String, DatasetEdgeAgg> edgeCounts = new LinkedHashMap<>();
        int unattributedEdges = 0;
        for (LineageEdge edge : columnEdges.values()) {
            LineageNode from = nodesById.get(edge.getFromNodeId());
            LineageNode to = nodesById.get(edge.getToNodeId());
            if (from == null || to == null) {
                unattributedEdges++;
                continue;
            }
            String fromDatasetId = from.getCatalogDatasetId();
            String toDatasetId = to.getCatalogDatasetId();
            if (fromDatasetId == null || fromDatasetId.isBlank()
                    || toDatasetId == null || toDatasetId.isBlank()) {
                unattributedEdges++;
                continue;
            }
            if (fromDatasetId.equals(toDatasetId)) {
                continue;
            }
            String fromKey = datasetKey(gate, fromDatasetId, datasetIds, restrictedPlaceholders);
            String toKey = datasetKey(gate, toDatasetId, datasetIds, restrictedPlaceholders);
            edgeCounts.computeIfAbsent(fromKey + "\u0000" + toKey, k -> new DatasetEdgeAgg())
                    .add(STATUS_ACTIVE.equals(edge.getStatus()));
        }
        if (edgeCounts.isEmpty()) {
            return new GraphView(List.of(), List.of(), unattributedEdges);
        }

        Map<String, CatalogDataset> datasetsById = datasetMapper.selectList(
                        new LambdaQueryWrapper<CatalogDataset>()
                                .eq(CatalogDataset::getWorkspaceId, workspaceId)
                                .in(CatalogDataset::getId, datasetIds)).stream()
                .collect(java.util.stream.Collectors.toMap(CatalogDataset::getId, value -> value));

        List<NodeView> resultNodes = new ArrayList<>(datasetIds.stream()
                .map(datasetsById::get)
                .filter(Objects::nonNull)
                .map(value -> new NodeView(value.getId(), "DATASET", value.getQualifiedName(),
                        value.getId(), null, value.getName(), null, null, null))
                .toList());
        resultNodes.addAll(restrictedPlaceholders.stream().map(LineageService::restrictedNodeView).toList());

        List<EdgeView> resultEdges = edgeCounts.entrySet().stream()
                .filter(entry -> {
                    String[] pair = entry.getKey().split("\u0000", 2);
                    return isKnownDatasetKey(pair[0], datasetsById, restrictedPlaceholders)
                            && isKnownDatasetKey(pair[1], datasetsById, restrictedPlaceholders);
                })
                .map(entry -> {
                    String[] pair = entry.getKey().split("\u0000", 2);
                    boolean restricted = restrictedPlaceholders.contains(pair[0])
                            || restrictedPlaceholders.contains(pair[1]);
                    String status = entry.getValue().hasActive ? STATUS_ACTIVE : STATUS_PLANNED;
                    // 触及受限数据集的聚合边不披露列级边条数（也是一种侧信道）。
                    String text = restricted ? RESTRICTED_LABEL : entry.getValue().count + " 条列级血缘";
                    return new EdgeView("dsedge:" + pair[0] + "->" + pair[1],
                            pair[0], pair[1], restricted ? RESTRICTED_NODE_TYPE : "DERIVE",
                            text, null, "AGGREGATED", status,
                            null, null, null, null, "DIRECT", false, null, null);
                })
                .toList();

        return enrichSensitivity(new GraphView(resultNodes, resultEdges, unattributedEdges,
                restrictedPlaceholders.size(), !restrictedPlaceholders.isEmpty()));
    }

    private static final class DatasetEdgeAgg {
        int count;
        boolean hasActive;

        void add(boolean active) {
            count++;
            hasActive |= active;
        }
    }

    /**
     * 展开表级聚合边背后的列级事实：{@code fromDataset → toDataset} 之间的列级边清单。
     * 对应前端 {@code dsedge:{from}->{to}} 点击后的明细。
     */
    public GraphView columnEdgesBetweenDatasets(String fromDatasetId, String toDatasetId,
                                                boolean includePlanned) {
        if (fromDatasetId == null || fromDatasetId.isBlank()
                || toDatasetId == null || toDatasetId.isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "fromDatasetId / toDatasetId 不能为空");
        }
        if (fromDatasetId.equals(toDatasetId)) {
            return new GraphView(List.of(), List.of());
        }
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        AclGate gate = new AclGate(workspaceId, WorkspaceContext.require());
        if (gate.datasetRestricted(fromDatasetId) || gate.datasetRestricted(toDatasetId)) {
            return new GraphView(List.of(), List.of(), 0, 1, true);
        }
        ensureDatasetExists(workspaceId, fromDatasetId);
        ensureDatasetExists(workspaceId, toDatasetId);

        List<LineageNode> fromNodes = nodeMapper.selectList(new LambdaQueryWrapper<LineageNode>()
                .eq(LineageNode::getWorkspaceId, workspaceId)
                .eq(LineageNode::getNodeType, "COLUMN")
                .eq(LineageNode::getCatalogDatasetId, fromDatasetId));
        List<LineageNode> toNodes = nodeMapper.selectList(new LambdaQueryWrapper<LineageNode>()
                .eq(LineageNode::getWorkspaceId, workspaceId)
                .eq(LineageNode::getNodeType, "COLUMN")
                .eq(LineageNode::getCatalogDatasetId, toDatasetId));
        if (fromNodes.isEmpty() || toNodes.isEmpty()) {
            return new GraphView(List.of(), List.of());
        }
        Set<String> fromIds = fromNodes.stream().map(LineageNode::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> toIds = toNodes.stream().map(LineageNode::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        LambdaQueryWrapper<LineageEdge> q = new LambdaQueryWrapper<LineageEdge>()
                .eq(LineageEdge::getWorkspaceId, workspaceId)
                .in(LineageEdge::getFromNodeId, fromIds)
                .in(LineageEdge::getToNodeId, toIds);
        if (includePlanned) {
            q.in(LineageEdge::getStatus, List.of(STATUS_ACTIVE, STATUS_PLANNED));
        } else {
            q.eq(LineageEdge::getStatus, STATUS_ACTIVE);
        }
        List<LineageEdge> edges = edgeMapper.selectList(q);
        if (edges.isEmpty()) {
            return new GraphView(List.of(), List.of());
        }

        Set<String> usedNodeIds = new LinkedHashSet<>();
        for (LineageEdge edge : edges) {
            usedNodeIds.add(edge.getFromNodeId());
            usedNodeIds.add(edge.getToNodeId());
        }
        Map<String, LineageNode> nodesById = new LinkedHashMap<>();
        for (LineageNode node : fromNodes) {
            if (usedNodeIds.contains(node.getId())) {
                nodesById.put(node.getId(), node);
            }
        }
        for (LineageNode node : toNodes) {
            if (usedNodeIds.contains(node.getId())) {
                nodesById.put(node.getId(), node);
            }
        }

        Set<String> jobIds = edges.stream().map(LineageEdge::getJobId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Map<String, LineageJob> jobsById = jobIds.isEmpty() ? Map.of()
                : jobMapper.selectList(new LambdaQueryWrapper<LineageJob>()
                        .eq(LineageJob::getWorkspaceId, workspaceId)
                        .in(LineageJob::getId, jobIds)).stream()
                .collect(java.util.stream.Collectors.toMap(LineageJob::getId, j -> j, (a, b) -> a));

        List<NodeView> nodeViews = nodesById.values().stream().map(NodeView::from).toList();
        List<EdgeView> edgeViews = edges.stream()
                .map(e -> EdgeView.from(e, e.getJobId() == null ? null : jobsById.get(e.getJobId())))
                .toList();
        return enrichSensitivity(new GraphView(nodeViews, edgeViews));
    }

    private void ensureDatasetExists(String workspaceId, String datasetId) {
        CatalogDataset dataset = datasetMapper.selectOne(new LambdaQueryWrapper<CatalogDataset>()
                .eq(CatalogDataset::getId, datasetId)
                .eq(CatalogDataset::getWorkspaceId, workspaceId));
        if (dataset == null) {
            throw BizException.notFound(ErrorCode.MTD_CATALOG_ASSET_NOT_FOUND, datasetId);
        }
    }

    private static String datasetKey(AclGate gate, String datasetId, Set<String> datasetIds,
                                     Set<String> restrictedPlaceholders) {
        if (gate.datasetRestricted(datasetId)) {
            String placeholder = gate.placeholderId(datasetId);
            restrictedPlaceholders.add(placeholder);
            return placeholder;
        }
        datasetIds.add(datasetId);
        return datasetId;
    }

    private static boolean isKnownDatasetKey(String key, Map<String, CatalogDataset> datasetsById,
                                             Set<String> restrictedPlaceholders) {
        return datasetsById.containsKey(key) || restrictedPlaceholders.contains(key);
    }

    /**
     * 作业发布/编译后写入 PLANNED 预估血缘。同一作业再次发布时复用边键，旧 ACTIVE/PLANNED
     * 中未再出现的边标 SUPERSEDED。
     */
    @Transactional
    public JobResult recordPlannedJob(JobRequest request) {
        return recordJobInternal(request, STATUS_PLANNED, null);
    }

    /**
     * ExecutionSucceeded 后将作业血缘晋升为 ACTIVE，并绑定 execution_id。
     * 若尚无 PLANNED 边（直接成功上报），则按 ACTIVE 写入。
     */
    @Transactional
    public JobResult promoteJobExecution(JobRequest request, String executionId) {
        if (executionId == null || executionId.isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "executionId 不能为空");
        }
        JobResult result = recordJobInternal(request, STATUS_ACTIVE, executionId.trim());
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String jobType = request.jobType() == null || request.jobType().isBlank()
                ? "MANUAL" : request.jobType().trim().toUpperCase();
        LineageJob job = jobMapper.selectOne(new LambdaQueryWrapper<LineageJob>()
                .eq(LineageJob::getWorkspaceId, workspaceId)
                .eq(LineageJob::getJobType, jobType)
                .eq(LineageJob::getName, request.name().trim()));
        if (job != null) {
            job.setLastExecutionId(executionId.trim());
            job.setParseStatus(blankTo(request.parseStatus(), "OK"));
            job.setParseMessage(request.parseMessage());
            jobMapper.updateById(job);
        }
        return result;
    }

    /**
     * 只登记作业的解析结论、不写边(C8)。
     *
     * <p>SQL 解析得到 {@code INCONCLUSIVE / ERROR} 时没有可信的边可写，但工作台仍要能看到
     * 「这个作业的血缘未能确定」及原因，而不是让它静默消失。已有的边保持原状。
     */
    @Transactional
    public JobResult recordJobParseOutcome(JobRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "血缘作业名称不能为空");
        }
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String jobType = request.jobType() == null || request.jobType().isBlank()
                ? "MANUAL" : request.jobType().trim().toUpperCase();
        LineageJob job = upsertJob(workspaceId, request, jobType);
        job.setParseStatus(blankTo(request.parseStatus(), "INCONCLUSIVE"));
        job.setParseMessage(request.parseMessage());
        jobMapper.updateById(job);
        return new JobResult(job.getId(), 0);
    }

    /** 血缘工作台概览：KPI + 最近有血缘的数据集/作业。 */
    public OverviewView overview() {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        List<LineageEdge> activeEdges = edgeMapper.selectList(new LambdaQueryWrapper<LineageEdge>()
                .eq(LineageEdge::getWorkspaceId, workspaceId)
                .eq(LineageEdge::getStatus, STATUS_ACTIVE));
        List<LineageEdge> plannedEdges = edgeMapper.selectList(new LambdaQueryWrapper<LineageEdge>()
                .eq(LineageEdge::getWorkspaceId, workspaceId)
                .eq(LineageEdge::getStatus, STATUS_PLANNED));

        Set<String> nodeIds = new LinkedHashSet<>();
        for (LineageEdge e : activeEdges) {
            nodeIds.add(e.getFromNodeId());
            nodeIds.add(e.getToNodeId());
        }
        Map<String, LineageNode> nodesById = nodeIds.isEmpty() ? Map.of()
                : nodeMapper.selectList(new LambdaQueryWrapper<LineageNode>()
                        .eq(LineageNode::getWorkspaceId, workspaceId)
                        .in(LineageNode::getId, nodeIds)).stream()
                .collect(java.util.stream.Collectors.toMap(LineageNode::getId, n -> n, (a, b) -> a));

        Map<String, int[]> datasetStats = new LinkedHashMap<>(); // [up, down]
        Map<String, Instant> datasetLast = new LinkedHashMap<>();
        Map<String, int[]> columnStats = new LinkedHashMap<>(); // catalogColumnId → [up, down]
        for (LineageEdge e : activeEdges) {
            LineageNode from = nodesById.get(e.getFromNodeId());
            LineageNode to = nodesById.get(e.getToNodeId());
            if (to != null && to.getCatalogDatasetId() != null) {
                datasetStats.computeIfAbsent(to.getCatalogDatasetId(), k -> new int[2])[0]++;
                datasetLast.merge(to.getCatalogDatasetId(), e.getDiscoveredAt() == null ? Instant.EPOCH : e.getDiscoveredAt(),
                        (a, b) -> a.isAfter(b) ? a : b);
            }
            if (from != null && from.getCatalogDatasetId() != null) {
                datasetStats.computeIfAbsent(from.getCatalogDatasetId(), k -> new int[2])[1]++;
                datasetLast.merge(from.getCatalogDatasetId(), e.getDiscoveredAt() == null ? Instant.EPOCH : e.getDiscoveredAt(),
                        (a, b) -> a.isAfter(b) ? a : b);
            }
            // 字段徽标：to 侧计上游入边，from 侧计下游出边
            if (to != null && to.getCatalogColumnId() != null) {
                columnStats.computeIfAbsent(to.getCatalogColumnId(), k -> new int[2])[0]++;
            }
            if (from != null && from.getCatalogColumnId() != null) {
                columnStats.computeIfAbsent(from.getCatalogColumnId(), k -> new int[2])[1]++;
            }
        }
        Map<String, ColumnEdgeCount> columnEdgeCounts = new LinkedHashMap<>();
        columnStats.forEach((id, st) -> columnEdgeCounts.put(id, new ColumnEdgeCount(st[0], st[1])));

        List<OverviewDataset> recentDatasets = List.of();
        if (!datasetStats.isEmpty()) {
            Map<String, CatalogDataset> dsMap = datasetMapper.selectList(new LambdaQueryWrapper<CatalogDataset>()
                            .eq(CatalogDataset::getWorkspaceId, workspaceId)
                            .in(CatalogDataset::getId, datasetStats.keySet())).stream()
                    .collect(java.util.stream.Collectors.toMap(CatalogDataset::getId, d -> d, (a, b) -> a));
            recentDatasets = datasetStats.entrySet().stream()
                    .sorted((a, b) -> datasetLast.getOrDefault(b.getKey(), Instant.EPOCH)
                            .compareTo(datasetLast.getOrDefault(a.getKey(), Instant.EPOCH)))
                    .limit(20)
                    .map(e -> {
                        CatalogDataset d = dsMap.get(e.getKey());
                        int[] st = e.getValue();
                        return new OverviewDataset(e.getKey(),
                                d == null ? e.getKey() : d.getName(),
                                d == null ? e.getKey() : d.getQualifiedName(),
                                st[0], st[1],
                                datasetLast.get(e.getKey()));
                    })
                    .toList();
        }

        List<LineageJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<LineageJob>()
                .eq(LineageJob::getWorkspaceId, workspaceId)
                .orderByDesc(LineageJob::getLastSeenAt)
                .last("LIMIT 20"));
        Map<String, long[]> jobEdgeCounts = new HashMap<>();
        for (LineageEdge e : activeEdges) {
            if (e.getJobId() != null) {
                jobEdgeCounts.computeIfAbsent(e.getJobId(), k -> new long[2])[0]++;
            }
        }
        for (LineageEdge e : plannedEdges) {
            if (e.getJobId() != null) {
                jobEdgeCounts.computeIfAbsent(e.getJobId(), k -> new long[2])[1]++;
            }
        }
        List<OverviewJob> recentJobs = jobs.stream()
                .map(j -> {
                    long[] c = jobEdgeCounts.getOrDefault(j.getId(), new long[2]);
                    return new OverviewJob(j.getId(), j.getName(), j.getJobType(),
                            blankTo(j.getParseStatus(), "OK"), j.getParseMessage(),
                            j.getLastSeenAt(), (int) c[0], (int) c[1]);
                })
                .toList();

        long activeJobs = jobs.stream()
                .filter(j -> jobEdgeCounts.getOrDefault(j.getId(), new long[2])[0] > 0)
                .count();

        return new OverviewView(
                datasetStats.size(),
                activeEdges.size(),
                (int) activeJobs,
                0,
                recentDatasets,
                recentJobs,
                columnEdgeCounts);
    }

    /** 边详情（含作业信息）。 */
    public EdgeView getEdge(String edgeId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        LineageEdge edge = edgeMapper.selectOne(new LambdaQueryWrapper<LineageEdge>()
                .eq(LineageEdge::getId, edgeId)
                .eq(LineageEdge::getWorkspaceId, workspaceId));
        if (edge == null) {
            throw BizException.notFound(ErrorCode.MTD_LINEAGE_NODE_NOT_FOUND, edgeId);
        }
        LineageJob job = edge.getJobId() == null ? null : jobMapper.selectById(edge.getJobId());
        return EdgeView.from(edge, job);
    }

    private JobResult recordJobInternal(JobRequest request, String status, String executionId) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "血缘作业名称不能为空");
        }
        if (request.edges() == null || request.edges().isEmpty()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "血缘边不能为空");
        }
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String jobType = request.jobType() == null || request.jobType().isBlank()
                ? "MANUAL" : request.jobType().trim().toUpperCase();
        LineageJob job = upsertJob(workspaceId, request, jobType);
        job.setParseStatus(blankTo(request.parseStatus(), "OK"));
        job.setParseMessage(request.parseMessage());
        if (executionId != null) {
            job.setLastExecutionId(executionId);
        }
        jobMapper.updateById(job);

        Map<String, LineageEdge> existing = new HashMap<>();
        for (LineageEdge edge : edgeMapper.selectList(new LambdaQueryWrapper<LineageEdge>()
                .eq(LineageEdge::getWorkspaceId, workspaceId)
                .eq(LineageEdge::getJobId, job.getId()))) {
            existing.put(key(edge.getFromNodeId(), edge.getToNodeId(), edge.getRelation()), edge);
        }

        // 同一次上报内的所有变化（引入/复活/改写/下线）共用一个批次时间，供历史 Diff 识别「同一批」。
        Instant runAt = Instant.now();
        Set<String> seen = new LinkedHashSet<>();
        List<LineageEdge> written = new ArrayList<>();
        int count = 0;
        for (EdgeInput input : request.edges()) {
            validateEdge(input);
            LineageNode from = upsertNode(workspaceId, input.from());
            LineageNode to = upsertNode(workspaceId, input.to());
            String relation = relation(input.relation());
            String edgeKey = key(from.getId(), to.getId(), relation);
            if (!seen.add(edgeKey)) {
                continue;
            }
            LineageEdge edge = existing.remove(edgeKey);
            if (edge == null) {
                edge = new LineageEdge();
                edge.setId(Ids.of("line"));
                edge.setWorkspaceId(workspaceId);
                edge.setFromNodeId(from.getId());
                edge.setToNodeId(to.getId());
                edge.setRelation(relation);
                edge.setJobId(job.getId());
                edge.setDiscoveredAt(Instant.now());
                applyEdge(edge, input, request.sqlHash(), status, executionId);
                edge.setIntroducedAt(runAt);
                edgeMapper.insert(edge);
                written.add(edge);
            } else if (STATUS_PLANNED.equals(status) && STATUS_ACTIVE.equals(edge.getStatus())
                    && sameEvidence(edge, input, request.sqlHash())) {
                // 重新编译/发布但这条边没有变化：它已被一次真实执行证实(ACTIVE)，
                // 不能因为再编译一次就把它降回「预估」，否则图谱会在两次执行之间凭空缺边。
                // 边变了(表达式/脱敏/SQL 哈希)才回到 PLANNED，等下一次执行重新证实。
            } else {
                trackRevision(edge, input, runAt);
                applyEdge(edge, input, request.sqlHash(), status, executionId);
                edgeMapper.updateById(edge);
                written.add(edge);
            }
            count++;
        }

        for (LineageEdge stale : existing.values()) {
            if (STATUS_ACTIVE.equals(stale.getStatus()) || STATUS_PLANNED.equals(stale.getStatus())) {
                stale.setStatus(STATUS_SUPERSEDED);
                stale.setSupersededAt(runAt);
                edgeMapper.updateById(stale);
            }
        }
        if (STATUS_ACTIVE.equals(status)) {
            propagateSensitivity(workspaceId, written);
        }
        return new JobResult(job.getId(), count);
    }

    /**
     * 新的 ACTIVE + DIRECT 边写入后，若上游列已有 CONFIRMED 的 L3/L4 标签，则沿血缘向下游生成
     * SUGGESTED 建议(P2-5)。传播是血缘写入的衍生动作：它失败只记日志，不能让血缘事实本身写不进去。
     */
    private void propagateSensitivity(String workspaceId, List<LineageEdge> edges) {
        if (sensitivityPropagator == null || edges == null || edges.isEmpty()) {
            return;
        }
        List<LineageEdge> direct = edges.stream()
                .filter(e -> STATUS_ACTIVE.equals(e.getStatus()))
                .filter(e -> e.getDependencyType() == null || "DIRECT".equals(e.getDependencyType()))
                .toList();
        if (direct.isEmpty()) {
            return;
        }
        try {
            sensitivityPropagator.propagateFromEdges(workspaceId, direct);
        } catch (RuntimeException ex) {
            log.warn("血缘敏感标签传播失败 workspace={} edges={}", workspaceId, direct.size(), ex);
        }
    }

    /**
     * 原地更新前登记版本信息（P3-2）：
     * 复活 SUPERSEDED 边视为「重新引入」；仍在用的边若转换表达式/依赖类型被改写，则保留改写前的值。
     */
    private static void trackRevision(LineageEdge edge, EdgeInput input, Instant runAt) {
        if (STATUS_SUPERSEDED.equals(edge.getStatus())) {
            edge.setIntroducedAt(runAt);
            edge.setPreviousTransformExpr(null);
            edge.setPreviousDependencyType(null);
            edge.setPreviousChangedAt(null);
            return;
        }
        String incomingDependency = input.dependencyType() == null || input.dependencyType().isBlank()
                ? "DIRECT" : input.dependencyType().trim().toUpperCase();
        boolean transformDiffers = !Objects.equals(edge.getTransformExpr(), input.transformExpr());
        boolean dependencyDiffers = !depOf(edge.getDependencyType()).equals(incomingDependency);
        if (transformDiffers || dependencyDiffers) {
            edge.setPreviousTransformExpr(edge.getTransformExpr());
            edge.setPreviousDependencyType(depOf(edge.getDependencyType()));
            edge.setPreviousChangedAt(runAt);
        }
    }

    private static boolean sameEvidence(LineageEdge edge, EdgeInput input, String sqlHash) {
        String dependency = input.dependencyType() == null || input.dependencyType().isBlank()
                ? "DIRECT" : input.dependencyType().trim().toUpperCase();
        String edgeDependency = edge.getDependencyType() == null ? "DIRECT" : edge.getDependencyType();
        return Objects.equals(edge.getTransformExpr(), input.transformExpr())
                && dependency.equals(edgeDependency)
                && Boolean.TRUE.equals(edge.getMaskingApplied()) == Boolean.TRUE.equals(input.maskingApplied())
                && Objects.equals(edge.getMaskingEvidence(), input.maskingEvidence())
                && Objects.equals(edge.getSqlHash(), sqlHash);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private void collectColumnEdges(String workspaceId, Set<String> seedIds,
                                    boolean upstream, int levels, boolean includePlanned,
                                    AclGate gate,
                                    Set<String> visited, Set<String> nodeIds,
                                    Map<String, LineageEdge> edges) {
        Set<String> frontier = new LinkedHashSet<>(seedIds);
        Set<String> restricted = new LinkedHashSet<>();
        for (int level = 0; level < levels && !frontier.isEmpty(); level++) {
            List<LineageEdge> found = edgeMapper.selectList(new LambdaQueryWrapper<LineageEdge>()
                    .eq(LineageEdge::getWorkspaceId, workspaceId)
                    .in(LineageEdge::getStatus, edgeStatuses(includePlanned))
                    .in(upstream ? LineageEdge::getToNodeId : LineageEdge::getFromNodeId,
                            frontier));
            Set<String> unseen = new LinkedHashSet<>();
            for (LineageEdge edge : found) {
                String other = upstream ? edge.getFromNodeId() : edge.getToNodeId();
                if (!visited.contains(other) && !restricted.contains(other)) {
                    unseen.add(other);
                }
            }
            // 逐跳 ACL：先判定这一层新碰到的节点，受限的不进入下一层 frontier。
            restricted.addAll(gate.restrictedNodeIds(loadNodes(workspaceId, unseen).values()));
            Set<String> next = new LinkedHashSet<>();
            for (LineageEdge edge : found) {
                edges.putIfAbsent(edge.getId(), edge);
                String other = upstream ? edge.getFromNodeId() : edge.getToNodeId();
                nodeIds.add(other);
                if (!restricted.contains(other) && visited.add(other)) {
                    next.add(other);
                }
            }
            frontier = next;
        }
    }

    public NodeView getNode(String nodeId) {
        // 与图查询一致：调用方可能持有 catalogColumnId，而不一定是血缘节点 ID。
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        LineageNode node = findNode(workspaceId, nodeId);
        if (node == null) {
            throw BizException.notFound(ErrorCode.MTD_LINEAGE_NODE_NOT_FOUND, nodeId);
        }
        // 直接取节点同样受 ACL 约束：受限数据集的字段节点不得因为换了入口就泄露名称。
        AclGate gate = new AclGate(workspaceId, WorkspaceContext.require());
        if (!gate.restrictedNodeIds(List.of(node)).isEmpty()) {
            return restrictedNodeView(gate.placeholderId(node.getId()));
        }
        return enrichNodeView(NodeView.from(node));
    }

    private GraphView graph(String nodeId, boolean upstream, int depth, boolean includePlanned) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        // 调用方通常持有 catalogColumnId(资产详情页就是如此);血缘节点 ID 只在
        // 血缘内部使用。先按任一标识解析,找不到说明该字段还没有血缘,返回空图。
        LineageNode start = findNode(workspaceId, nodeId);
        if (start == null) {
            return new GraphView(List.of(), List.of());
        }
        nodeId = start.getId();
        int levels = Math.max(1, Math.min(depth, MAX_DEPTH));

        AclGate gate = new AclGate(workspaceId, WorkspaceContext.require());
        if (!gate.restrictedNodeIds(List.of(start)).isEmpty()) {
            return restrictedOnly(gate.placeholderId(start.getId()));
        }

        Set<String> visitedNodes = new LinkedHashSet<>();
        visitedNodes.add(nodeId);
        Set<String> frontier = Set.of(nodeId);
        Map<String, LineageNode> viewableNodes = new LinkedHashMap<>();
        viewableNodes.put(nodeId, start);
        // 受限节点 ID → 占位 ID；受限节点永远不进入 frontier。
        Map<String, String> restrictedPlaceholders = new LinkedHashMap<>();
        Map<String, LineageEdge> edges = new LinkedHashMap<>();
        Map<String, LineageEdge> restrictedEdges = new LinkedHashMap<>();

        for (int level = 0; level < levels && !frontier.isEmpty(); level++) {
            List<LineageEdge> found = edgeMapper.selectList(new LambdaQueryWrapper<LineageEdge>()
                    .eq(LineageEdge::getWorkspaceId, workspaceId)
                    .in(LineageEdge::getStatus, edgeStatuses(includePlanned))
                    .in(upstream ? LineageEdge::getToNodeId : LineageEdge::getFromNodeId,
                            frontier));
            Set<String> unseen = new LinkedHashSet<>();
            for (LineageEdge edge : found) {
                String other = upstream ? edge.getFromNodeId() : edge.getToNodeId();
                if (!viewableNodes.containsKey(other) && !restrictedPlaceholders.containsKey(other)) {
                    unseen.add(other);
                }
            }
            Map<String, LineageNode> loaded = loadNodes(workspaceId, unseen);
            Set<String> restricted = gate.restrictedNodeIds(loaded.values());
            for (LineageNode node : loaded.values()) {
                if (restricted.contains(node.getId())) {
                    restrictedPlaceholders.put(node.getId(), gate.placeholderId(node.getId()));
                } else {
                    viewableNodes.put(node.getId(), node);
                }
            }
            Set<String> next = new LinkedHashSet<>();
            for (LineageEdge edge : found) {
                String other = upstream ? edge.getFromNodeId() : edge.getToNodeId();
                if (restrictedPlaceholders.containsKey(other)) {
                    restrictedEdges.put(edge.getId(), edge);
                    continue;
                }
                edges.put(edge.getId(), edge);
                if (visitedNodes.add(other)) {
                    next.add(other);
                }
            }
            frontier = next;
        }

        List<NodeView> nodeViews = new ArrayList<>(viewableNodes.values().stream()
                .map(NodeView::from).toList());
        Set<String> placeholderIds = new LinkedHashSet<>(restrictedPlaceholders.values());
        placeholderIds.forEach(id -> nodeViews.add(restrictedNodeView(id)));

        Map<String, LineageJob> jobs = loadJobs(workspaceId, edges.values());
        List<EdgeView> edgeViews = new ArrayList<>(edges.values().stream()
                .map(e -> EdgeView.from(e, e.getJobId() == null ? null : jobs.get(e.getJobId())))
                .toList());
        for (LineageEdge edge : restrictedEdges.values()) {
            edgeViews.add(restrictedEdge(gate, edge, restrictedPlaceholders));
        }
        return enrichSensitivity(new GraphView(nodeViews, edgeViews, 0, placeholderIds.size(),
                !placeholderIds.isEmpty()));
    }

    // ── 敏感徽标(P2-2) ─────────────────────────────────────────────────

    private static final String SENSITIVITY_CONFIRMED = "CONFIRMED";

    private GraphView enrichSensitivity(GraphView view) {
        if (sensitivityMapper == null || view == null || view.nodes().isEmpty()) {
            return view;
        }
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        Set<String> columnIds = new LinkedHashSet<>();
        Set<String> datasetIds = new LinkedHashSet<>();
        for (NodeView node : view.nodes()) {
            if (RESTRICTED_NODE_TYPE.equals(node.nodeType()) || isGovernanceNodeType(node.nodeType())) {
                continue;
            }
            if (node.catalogColumnId() != null && !node.catalogColumnId().isBlank()) {
                columnIds.add(node.catalogColumnId());
            }
            if (node.catalogDatasetId() != null && !node.catalogDatasetId().isBlank()
                    && ("DATASET".equals(node.nodeType()) || "COLUMN".equals(node.nodeType()))) {
                datasetIds.add(node.catalogDatasetId());
            }
        }
        if (columnIds.isEmpty() && datasetIds.isEmpty()) {
            return view;
        }
        Map<String, String> columnLevels = maxConfirmedLevels(workspaceId, columnIds, true);
        Map<String, String> datasetLevels = maxConfirmedLevels(workspaceId, datasetIds, false);
        List<NodeView> nodes = view.nodes().stream().map(node -> enrichNodeView(node, columnLevels, datasetLevels))
                .toList();
        return new GraphView(nodes, view.edges(), view.unattributedEdges(), view.restrictedCount(),
                view.truncatedByAcl());
    }

    private NodeView enrichNodeView(NodeView node) {
        if (sensitivityMapper == null || node == null || RESTRICTED_NODE_TYPE.equals(node.nodeType())
                || isGovernanceNodeType(node.nodeType())) {
            return node;
        }
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        Set<String> columnIds = node.catalogColumnId() == null || node.catalogColumnId().isBlank()
                ? Set.of() : Set.of(node.catalogColumnId());
        Set<String> datasetIds = node.catalogDatasetId() == null || node.catalogDatasetId().isBlank()
                ? Set.of() : Set.of(node.catalogDatasetId());
        return enrichNodeView(node,
                maxConfirmedLevels(workspaceId, columnIds, true),
                maxConfirmedLevels(workspaceId, datasetIds, false));
    }

    private static NodeView enrichNodeView(NodeView node, Map<String, String> columnLevels,
                                           Map<String, String> datasetLevels) {
        String level = null;
        if ("COLUMN".equals(node.nodeType()) && node.catalogColumnId() != null) {
            level = columnLevels.get(node.catalogColumnId());
        } else if ("DATASET".equals(node.nodeType()) && node.catalogDatasetId() != null) {
            level = datasetLevels.get(node.catalogDatasetId());
        }
        if (level == null || level.equals(node.sensitivityLevel())) {
            return node;
        }
        return new NodeView(node.id(), node.nodeType(), node.qualifiedName(), node.catalogDatasetId(),
                node.catalogColumnId(), node.displayName(), node.externalRefType(), node.externalRefId(), level);
    }

    private Map<String, String> maxConfirmedLevels(String workspaceId, Collection<String> ids, boolean byColumn) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<ColumnSensitivity> query = new LambdaQueryWrapper<ColumnSensitivity>()
                .eq(ColumnSensitivity::getWorkspaceId, workspaceId)
                .eq(ColumnSensitivity::getStatus, SENSITIVITY_CONFIRMED);
        if (byColumn) {
            query.in(ColumnSensitivity::getColumnId, ids);
        } else {
            query.in(ColumnSensitivity::getDatasetId, ids);
        }
        List<ColumnSensitivity> labels = sensitivityMapper.selectList(query);
        Map<String, Integer> ranks = new HashMap<>();
        Map<String, String> levels = new HashMap<>();
        for (ColumnSensitivity label : labels) {
            String key = byColumn ? label.getColumnId() : label.getDatasetId();
            if (key == null || key.isBlank()) {
                continue;
            }
            int rank = sensitivityRank(label.getLevel());
            int prev = ranks.getOrDefault(key, 0);
            if (rank > prev) {
                ranks.put(key, rank);
                levels.put(key, formatLevel(rank));
            }
        }
        return levels;
    }

    private static int sensitivityRank(String level) {
        if (level == null || level.isBlank()) {
            return 0;
        }
        return switch (level.trim().toUpperCase()) {
            case "L4" -> 4;
            case "L3" -> 3;
            case "L2" -> 2;
            case "L1" -> 1;
            default -> 0;
        };
    }

    private static String formatLevel(int rank) {
        if (rank <= 0) {
            return null;
        }
        return "L" + rank;
    }

    private static boolean isGovernanceNodeType(String nodeType) {
        return nodeType != null && (nodeType.equals("METRIC") || nodeType.equals("REPORT")
                || nodeType.equals("QUALITY_RULE"));
    }

    // ── 历史 Diff(P3-2) ─────────────────────────────────────────────────

    /** 同一批上报内的边带同一个 runAt；这里留一点容差吸收数据库时间精度截断与历史数据。 */
    private static final long BATCH_TOLERANCE_MILLIS = 500;

    /**
     * 作业血缘历史 Diff：当前 ACTIVE 边 vs 最近一批改动（SUPERSEDED 批次 / 表达式改写批次）。
     *
     * <ul>
     *   <li>{@code removed}：最近一批被标 SUPERSEDED 且当前没有复活的边；</li>
     *   <li>{@code added}：最近一批引入的 ACTIVE 边；</li>
     *   <li>{@code modifiedTransform}：同一对 from→to 的转换表达式/依赖类型/关系被改写——
     *       既包含原地改写（读 previous_*），也包含 relation 变了导致旧边下线、新边上线的配对。</li>
     * </ul>
     * 逐跳 ACL(C5)：任一端点属于无权数据集，该行名称替换为 [受限资产]，表达式隐藏。
     */
    public JobDiffView jobDiff(String jobId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        LineageJob job = jobMapper.selectOne(new LambdaQueryWrapper<LineageJob>()
                .eq(LineageJob::getId, jobId)
                .eq(LineageJob::getWorkspaceId, workspaceId));
        if (job == null) {
            throw BizException.notFound(ErrorCode.MTD_LINEAGE_NODE_NOT_FOUND, jobId);
        }
        List<LineageEdge> edges = edgeMapper.selectList(new LambdaQueryWrapper<LineageEdge>()
                .eq(LineageEdge::getWorkspaceId, workspaceId)
                .eq(LineageEdge::getJobId, job.getId()));
        List<LineageEdge> active = edges.stream().filter(e -> STATUS_ACTIVE.equals(e.getStatus())).toList();
        List<LineageEdge> superseded = edges.stream()
                .filter(e -> STATUS_SUPERSEDED.equals(e.getStatus()) && e.getSupersededAt() != null).toList();

        Instant baseline = null;
        for (LineageEdge e : superseded) {
            baseline = later(baseline, e.getSupersededAt());
        }
        for (LineageEdge e : active) {
            if (transformChanged(e)) {
                baseline = later(baseline, e.getPreviousChangedAt());
            }
        }
        if (baseline == null) {
            return new JobDiffView(job.getId(), job.getName(), job.getJobType(), false, null,
                    active.size(), List.of(), List.of(), List.of(), 0, false);
        }
        final Instant batch = baseline;

        Set<String> activeKeys = new LinkedHashSet<>();
        active.forEach(e -> activeKeys.add(key(e.getFromNodeId(), e.getToNodeId(), e.getRelation())));
        List<LineageEdge> removed = new ArrayList<>(superseded.stream()
                .filter(e -> sameBatch(e.getSupersededAt(), batch))
                .filter(e -> !activeKeys.contains(key(e.getFromNodeId(), e.getToNodeId(), e.getRelation())))
                .toList());
        List<LineageEdge> added = new ArrayList<>(active.stream()
                .filter(e -> sameBatch(e.getIntroducedAt(), batch)).toList());
        List<LineageEdge> rewritten = active.stream()
                .filter(e -> transformChanged(e) && sameBatch(e.getPreviousChangedAt(), batch)).toList();

        // relation 变化：旧边下线 + 新边上线，同一对 from→to 配成一条「修改」。
        record Pair(LineageEdge now, LineageEdge before) {
        }
        List<Pair> paired = new ArrayList<>();
        for (LineageEdge a : new ArrayList<>(added)) {
            for (LineageEdge r : removed) {
                if (a.getFromNodeId().equals(r.getFromNodeId()) && a.getToNodeId().equals(r.getToNodeId())) {
                    paired.add(new Pair(a, r));
                    added.remove(a);
                    removed.remove(r);
                    break;
                }
            }
        }
        // 原地改写的边在本批次可能同时是 introducedAt（复活）——只算「修改」，不重复算「新增」。
        Set<String> rewrittenIds = new LinkedHashSet<>();
        rewritten.forEach(e -> rewrittenIds.add(e.getId()));
        added.removeIf(e -> rewrittenIds.contains(e.getId()));

        Set<String> nodeIds = new LinkedHashSet<>();
        for (LineageEdge e : concat(added, removed, rewritten)) {
            nodeIds.add(e.getFromNodeId());
            nodeIds.add(e.getToNodeId());
        }
        paired.forEach(p -> {
            nodeIds.add(p.now().getFromNodeId());
            nodeIds.add(p.now().getToNodeId());
        });
        Map<String, LineageNode> nodes = loadNodes(workspaceId, nodeIds);
        AclGate gate = new AclGate(workspaceId, WorkspaceContext.require());
        Set<String> restrictedNodes = gate.restrictedNodeIds(nodes.values());
        int[] restrictedRows = {0};

        java.util.function.BiFunction<LineageEdge, LineageEdge, DiffItem> toItem = (edge, before) -> {
            LineageNode from = nodes.get(edge.getFromNodeId());
            LineageNode to = nodes.get(edge.getToNodeId());
            boolean restricted = from == null || to == null
                    || restrictedNodes.contains(from.getId()) || restrictedNodes.contains(to.getId());
            if (restricted) {
                restrictedRows[0]++;
                return new DiffItem(gate.opaqueId("restricted-edge:", edge.getId()),
                        restrictedNodes.contains(edge.getFromNodeId())
                                ? gate.placeholderId(edge.getFromNodeId()) : edge.getFromNodeId(),
                        restrictedNodes.contains(edge.getFromNodeId()) ? RESTRICTED_LABEL : nameOf(from),
                        restrictedNodes.contains(edge.getToNodeId())
                                ? gate.placeholderId(edge.getToNodeId()) : edge.getToNodeId(),
                        restrictedNodes.contains(edge.getToNodeId()) ? RESTRICTED_LABEL : nameOf(to),
                        RESTRICTED_NODE_TYPE, null, null, null, null, null, true);
            }
            String dep = depOf(edge.getDependencyType());
            if (before != null) {
                return new DiffItem(edge.getId(), edge.getFromNodeId(), nameOf(from),
                        edge.getToNodeId(), nameOf(to), edge.getRelation(), dep, edge.getTransformExpr(),
                        before.getRelation(), depOf(before.getDependencyType()), before.getTransformExpr(), false);
            }
            return new DiffItem(edge.getId(), edge.getFromNodeId(), nameOf(from),
                    edge.getToNodeId(), nameOf(to), edge.getRelation(), dep, edge.getTransformExpr(),
                    null, null, null, false);
        };

        List<DiffItem> addedItems = added.stream().map(e -> toItem.apply(e, null)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<DiffItem> removedItems = removed.stream().map(e -> toItem.apply(e, null)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<DiffItem> modifiedItems = new ArrayList<>();
        for (LineageEdge e : rewritten) {
            LineageEdge snapshot = new LineageEdge();
            snapshot.setRelation(e.getRelation());
            snapshot.setTransformExpr(e.getPreviousTransformExpr());
            snapshot.setDependencyType(e.getPreviousDependencyType());
            modifiedItems.add(toItem.apply(e, snapshot));
        }
        for (Pair p : paired) {
            modifiedItems.add(toItem.apply(p.now(), p.before()));
        }
        java.util.Comparator<DiffItem> order = java.util.Comparator
                .comparing((DiffItem i) -> Objects.toString(i.fromName(), ""))
                .thenComparing(i -> Objects.toString(i.toName(), ""))
                .thenComparing(i -> Objects.toString(i.relation(), ""));
        addedItems.sort(order);
        removedItems.sort(order);
        modifiedItems.sort(order);
        return new JobDiffView(job.getId(), job.getName(), job.getJobType(), true, batch,
                active.size(), addedItems, removedItems, modifiedItems,
                restrictedRows[0], restrictedRows[0] > 0);
    }

    private static boolean transformChanged(LineageEdge edge) {
        if (edge.getPreviousChangedAt() == null) {
            return false;
        }
        return !Objects.equals(edge.getPreviousTransformExpr(), edge.getTransformExpr())
                || !depOf(edge.getPreviousDependencyType()).equals(depOf(edge.getDependencyType()));
    }

    private static boolean sameBatch(Instant value, Instant batch) {
        return value != null && Math.abs(Duration.between(value, batch).toMillis()) <= BATCH_TOLERANCE_MILLIS;
    }

    private static Instant later(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    private static String depOf(String value) {
        return value == null || value.isBlank() ? "DIRECT" : value;
    }

    private static String nameOf(LineageNode node) {
        if (node == null) {
            return null;
        }
        return node.getQualifiedName() == null ? node.getDisplayName() : node.getQualifiedName();
    }

    @SafeVarargs
    private static List<LineageEdge> concat(List<LineageEdge>... lists) {
        List<LineageEdge> all = new ArrayList<>();
        for (List<LineageEdge> list : lists) {
            all.addAll(list);
        }
        return all;
    }

    // ── 治理消费方投影(P3-1) ───────────────────────────────────────────

    public static final String GOV_NODE_PREFIX = "gov:";
    public static final String GOV_RELATION = "CONSUMED_BY";
    public static final String GOV_ORIGIN = "SEMANTIC_BRIDGE";

    /**
     * 把图里可见节点的下游治理消费方（指标/报告/质检规则）并入图。
     *
     * <p>锚点只取图中<b>已通过逐跳 ACL</b>的 COLUMN / DATASET 节点（RESTRICTED 占位不是锚点），
     * 因此受限资产不会经「消费方」泄露。消费方节点是动态投影的虚拟节点（ID 以 {@code gov:} 开头），
     * 不写入血缘表；连到它们的边 relation 为 {@code CONSUMED_BY}、status 恒为 ACTIVE。
     */
    private GraphView attachGovernance(GraphView view, boolean includeGovernance) {
        if (!includeGovernance || governanceBridge == null || view == null || view.nodes().isEmpty()) {
            return view;
        }
        List<ConsumerAnchor> anchors = new ArrayList<>();
        for (NodeView node : view.nodes()) {
            if ("COLUMN".equals(node.nodeType())) {
                anchors.add(new ConsumerAnchor(node.id(), node.catalogDatasetId(), node.catalogColumnId()));
            } else if ("DATASET".equals(node.nodeType())) {
                anchors.add(new ConsumerAnchor(node.id(), node.catalogDatasetId(), null));
            }
        }
        if (anchors.isEmpty()) {
            return view;
        }
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        List<ConsumerLink> links = governanceBridge.project(WorkspaceContext.require(), workspaceId, anchors);
        if (links.isEmpty()) {
            return view;
        }
        Map<String, NodeView> govNodes = new LinkedHashMap<>();
        List<EdgeView> govEdges = new ArrayList<>();
        for (ConsumerLink link : links) {
            GovernanceConsumer consumer = link.consumer();
            String govId = GOV_NODE_PREFIX + consumer.refType() + ":" + consumer.refId();
            govNodes.putIfAbsent(govId, new NodeView(govId, consumer.refType(), consumer.qualifiedName(),
                    consumer.datasetId(), consumer.columnId(), consumer.displayName(),
                    consumer.refType(), consumer.refId(), null));
            govEdges.add(new EdgeView("govedge:" + link.anchorNodeId() + "->" + govId,
                    link.anchorNodeId(), govId, GOV_RELATION, consumer.detail(), null, GOV_ORIGIN,
                    STATUS_ACTIVE, null, null, null, null, "DIRECT", false, null, null));
        }
        List<NodeView> nodes = new ArrayList<>(view.nodes());
        nodes.addAll(govNodes.values());
        List<EdgeView> edges = new ArrayList<>(view.edges());
        edges.addAll(govEdges);
        return new GraphView(nodes, edges, view.unattributedEdges(), view.restrictedCount(),
                view.truncatedByAcl());
    }

    // ── 逐跳 ACL(C5) ──────────────────────────────────────────────────

    /**
     * 一次图查询内的 ACL 判定上下文：同一数据集只问一次评估器，且不跨请求缓存。
     *
     * <p>没有 {@code catalogDatasetId} 的节点(文件/API 端点、尚未与 Catalog 对齐的历史节点)
     * 不属于任何数据集资产，没有可校验的对象，按可见处理。
     */
    private final class AclGate {
        private final String workspaceId;
        private final Caller caller;
        private final Map<String, Boolean> decisions = new HashMap<>();

        AclGate(String workspaceId, Caller caller) {
            this.workspaceId = workspaceId;
            this.caller = caller;
        }

        Set<String> restrictedNodeIds(Collection<LineageNode> nodes) {
            decide(nodes.stream().map(LineageNode::getCatalogDatasetId).toList());
            Set<String> restricted = new LinkedHashSet<>();
            for (LineageNode node : nodes) {
                String datasetId = node.getCatalogDatasetId();
                if (datasetId != null && !datasetId.isBlank()
                        && !Boolean.TRUE.equals(decisions.get(datasetId))) {
                    restricted.add(node.getId());
                }
            }
            return restricted;
        }

        boolean datasetRestricted(String datasetId) {
            if (datasetId == null || datasetId.isBlank()) {
                return false;
            }
            decide(List.of(datasetId));
            return !Boolean.TRUE.equals(decisions.get(datasetId));
        }

        private void decide(Collection<String> datasetIds) {
            Set<String> pending = new LinkedHashSet<>();
            for (String id : datasetIds) {
                if (id != null && !id.isBlank() && !decisions.containsKey(id)) {
                    pending.add(id);
                }
            }
            if (pending.isEmpty()) {
                return;
            }
            Set<String> viewable = aclEvaluator.viewableDatasetIds(caller, pending);
            for (String id : pending) {
                decisions.put(id, viewable != null && viewable.contains(id));
            }
        }

        /** 稳定哈希占位 ID：同一资产同一 workspace 恒定，但无法反推出原 ID。 */
        String placeholderId(String rawId) {
            return opaqueId("restricted:", rawId);
        }

        String opaqueId(String prefix, String rawId) {
            return prefix + sha256Hex(workspaceId + ":" + rawId).substring(0, 12);
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static NodeView restrictedNodeView(String placeholderId) {
        return new NodeView(placeholderId, RESTRICTED_NODE_TYPE, RESTRICTED_LABEL,
                null, null, RESTRICTED_LABEL, null, null, null);
    }

    private static GraphView restrictedOnly(String placeholderId) {
        return new GraphView(List.of(restrictedNodeView(placeholderId)), List.of(), 0, 1, true);
    }

    /**
     * 触及受限节点的边：只保留连通性(哪两个节点相连、PLANNED/ACTIVE、DIRECT/INDIRECT)，
     * 隐藏 SQL、作业、执行与置信度，边 ID 也哈希化。
     */
    private static EdgeView restrictedEdge(AclGate gate, LineageEdge edge,
                                           Map<String, String> restrictedPlaceholders) {
        String from = restrictedPlaceholders.getOrDefault(edge.getFromNodeId(), edge.getFromNodeId());
        String to = restrictedPlaceholders.getOrDefault(edge.getToNodeId(), edge.getToNodeId());
        return new EdgeView(gate.opaqueId("restricted-edge:", edge.getId()),
                from, to, RESTRICTED_NODE_TYPE, null, null, null, edge.getStatus(),
                null, null, null, null,
                edge.getDependencyType() == null ? "DIRECT" : edge.getDependencyType(),
                false, null, null);
    }

    private static List<String> edgeStatuses(boolean includePlanned) {
        return includePlanned ? List.of(STATUS_ACTIVE, STATUS_PLANNED) : List.of(STATUS_ACTIVE);
    }

    private Map<String, LineageNode> loadNodes(String workspaceId, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Set<String> wanted = new LinkedHashSet<>(ids);
        Map<String, LineageNode> result = new LinkedHashMap<>();
        for (LineageNode node : nodeMapper.selectList(new LambdaQueryWrapper<LineageNode>()
                .eq(LineageNode::getWorkspaceId, workspaceId)
                .in(LineageNode::getId, wanted))) {
            if (wanted.contains(node.getId())) {
                result.put(node.getId(), node);
            }
        }
        return result;
    }

    private Map<String, LineageJob> loadJobs(String workspaceId, Iterable<LineageEdge> edges) {
        Set<String> jobIds = new LinkedHashSet<>();
        for (LineageEdge e : edges) {
            if (e.getJobId() != null) {
                jobIds.add(e.getJobId());
            }
        }
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        return jobMapper.selectList(new LambdaQueryWrapper<LineageJob>()
                        .eq(LineageJob::getWorkspaceId, workspaceId)
                        .in(LineageJob::getId, jobIds)).stream()
                .collect(java.util.stream.Collectors.toMap(LineageJob::getId, j -> j, (a, b) -> a));
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private LineageJob upsertJob(String workspaceId, JobRequest request, String jobType) {
        LineageJob job = jobMapper.selectOne(new LambdaQueryWrapper<LineageJob>()
                .eq(LineageJob::getWorkspaceId, workspaceId)
                .eq(LineageJob::getJobType, jobType)
                .eq(LineageJob::getName, request.name().trim()));
        Instant now = Instant.now();
        if (job == null) {
            job = new LineageJob();
            job.setId(Ids.of("linj"));
            job.setWorkspaceId(workspaceId);
            job.setJobType(jobType);
            job.setName(request.name().trim());
            job.setCreatedAt(now);
            job.setLastSeenAt(now);
            job.setSourceRef(request.sourceRef());
            job.setSqlHash(request.sqlHash());
            jobMapper.insert(job);
        } else {
            job.setLastSeenAt(now);
            job.setSourceRef(request.sourceRef());
            job.setSqlHash(request.sqlHash());
            jobMapper.updateById(job);
        }
        return job;
    }

    private LineageNode upsertNode(String workspaceId, NodeRef ref) {
        if (ref == null || ref.qualifiedName() == null || ref.qualifiedName().isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "血缘节点 qualifiedName 不能为空");
        }
        String type = ref.nodeType() == null || ref.nodeType().isBlank()
                ? "COLUMN" : ref.nodeType().trim().toUpperCase();
        String qualifiedName = ref.qualifiedName().trim();
        LineageNode node = nodeMapper.selectOne(new LambdaQueryWrapper<LineageNode>()
                .eq(LineageNode::getWorkspaceId, workspaceId)
                .eq(LineageNode::getNodeType, type)
                .eq(LineageNode::getQualifiedName, qualifiedName));
        if (node == null) {
            node = new LineageNode();
            node.setId(Ids.of("lin"));
            node.setWorkspaceId(workspaceId);
            node.setNodeType(type);
            node.setQualifiedName(qualifiedName);
            node.setCreatedAt(Instant.now());
            node.setCatalogDatasetId(ref.catalogDatasetId());
            node.setCatalogColumnId(ref.catalogColumnId());
            node.setDisplayName(ref.displayName() == null ? qualifiedName : ref.displayName());
            nodeMapper.insert(node);
        } else {
            boolean changed = false;
            if (ref.catalogDatasetId() != null
                    && !Objects.equals(ref.catalogDatasetId(), node.getCatalogDatasetId())) {
                node.setCatalogDatasetId(ref.catalogDatasetId());
                changed = true;
            }
            if (ref.catalogColumnId() != null
                    && !Objects.equals(ref.catalogColumnId(), node.getCatalogColumnId())) {
                node.setCatalogColumnId(ref.catalogColumnId());
                changed = true;
            }
            if (ref.displayName() != null && !Objects.equals(ref.displayName(), node.getDisplayName())) {
                node.setDisplayName(ref.displayName());
                changed = true;
            }
            if (changed) {
                nodeMapper.updateById(node);
            }
        }
        return node;
    }

    private LineageNode findNode(String workspaceId, String id) {
        // 历史数据里同一个 catalogColumnId 可能对应多条节点（例如字段被重命名后
        // 作业又上报了一次）。selectOne 会在这种数据上直接抛 TooManyResults，
        // 导致整个血缘页 500；这里明确优先精确的节点 ID，再退到字段 ID。
        List<LineageNode> matches = nodeMapper.selectList(new LambdaQueryWrapper<LineageNode>()
                .eq(LineageNode::getWorkspaceId, workspaceId)
                .and(w -> w.eq(LineageNode::getId, id)
                        .or().eq(LineageNode::getCatalogColumnId, id)));
        return matches.stream()
                .filter(node -> id.equals(node.getId()))
                .findFirst()
                .or(() -> matches.stream()
                        .filter(node -> id.equals(node.getCatalogColumnId()))
                        .findFirst())
                .orElse(null);
    }

    private LineageNode requireNode(String nodeId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        LineageNode node = nodeMapper.selectOne(new LambdaQueryWrapper<LineageNode>()
                .eq(LineageNode::getId, nodeId)
                .eq(LineageNode::getWorkspaceId, workspaceId));
        if (node == null) {
            throw BizException.notFound(ErrorCode.MTD_LINEAGE_NODE_NOT_FOUND, nodeId);
        }
        return node;
    }

    private void validateEdge(EdgeInput input) {
        if (input == null || input.from() == null || input.to() == null) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "血缘边必须同时有 from 与 to");
        }
        String from = input.from().qualifiedName();
        String to = input.to().qualifiedName();
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "血缘节点的 qualifiedName 不能为空");
        }
        if (from.trim().equals(to.trim())) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "血缘边的 from 与 to 不能相同");
        }
    }

    private static void applyEdge(LineageEdge edge, EdgeInput input, String sqlHash,
                                  String status, String executionId) {
        edge.setTransformExpr(input.transformExpr());
        edge.setConfidence(input.confidence() == null ? 1.0 : input.confidence());
        edge.setOrigin(input.origin() == null || input.origin().isBlank()
                ? "AUTO" : input.origin().trim().toUpperCase());
        edge.setSqlHash(sqlHash);
        edge.setDiscoveredAt(Instant.now());
        edge.setStatus(status == null ? STATUS_ACTIVE : status);
        edge.setDependencyType(input.dependencyType() == null || input.dependencyType().isBlank()
                ? "DIRECT" : input.dependencyType().trim().toUpperCase());
        edge.setMaskingApplied(Boolean.TRUE.equals(input.maskingApplied()));
        edge.setMaskingEvidence(input.maskingEvidence());
        if (executionId != null && !executionId.isBlank()) {
            edge.setExecutionId(executionId);
        }
        if (STATUS_SUPERSEDED.equals(status)) {
            edge.setSupersededAt(Instant.now());
        } else {
            edge.setSupersededAt(null);
        }
    }

    private static String relation(String relation) {
        return relation == null || relation.isBlank() ? "DERIVE" : relation.trim().toUpperCase();
    }

    private static String key(String from, String to, String relation) {
        return from + "|" + to + "|" + relation;
    }
}
