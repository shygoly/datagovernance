package com.datagov.metadata.service.lineage;

import com.datagov.common.error.BizException;
import com.datagov.metadata.service.LineageService;
import com.datagov.metadata.service.LineageService.EdgeView;
import com.datagov.metadata.service.LineageService.GraphView;
import com.datagov.metadata.service.LineageService.NodeView;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 只读血缘工具的结果整形（column-lineage-e2e P3-3：{@code lineage_trace_column} /
 * {@code lineage_impact_analysis}）。
 *
 * <p>本服务<b>不做任何鉴权或遍历</b>：遍历与逐跳 ACL(C5) 全部由 {@link LineageService} 完成，
 * 调用者身份由 dg-app 的内部端点按 {@code userId + workspaceId} 实时还原到 {@code WorkspaceContext}
 * 之后才会走到这里——所以 Agent 看到的永远不会比该用户在界面上看到的更多。
 *
 * <p>这里只负责把图压成对模型友好的紧凑结构，并落实 C8：<b>证据不足时返回 {@code INCONCLUSIVE}</b>
 * 并给出原因码，而不是把「没查到」说成「没有依赖」。
 */
@Service
public class LineageAgentToolService {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_INCONCLUSIVE = "INCONCLUSIVE";

    public static final String REASON_NODE_NOT_FOUND = "NODE_NOT_FOUND";
    public static final String REASON_ROOT_RESTRICTED = "ROOT_RESTRICTED";
    public static final String REASON_NO_LINEAGE_EVIDENCE = "NO_LINEAGE_EVIDENCE";
    public static final String REASON_NO_DOWNSTREAM_EVIDENCE = "NO_DOWNSTREAM_EVIDENCE";
    public static final String REASON_ONLY_PLANNED_EVIDENCE = "ONLY_PLANNED_EVIDENCE";

    static final int MAX_NODES = 100;
    static final int MAX_EDGES = 200;
    private static final int DEFAULT_TRACE_DEPTH = 3;
    private static final int DEFAULT_IMPACT_DEPTH = 5;

    /** @param side UPSTREAM / DOWNSTREAM；level 为距起点的跳数（起点自身不出现在列表里） */
    public record TraceNode(String id, String nodeType, String qualifiedName, String displayName,
                            String catalogDatasetId, String catalogColumnId, String side, int level) {
    }

    public record TraceEdge(String fromNodeId, String toNodeId, String relation, String dependencyType,
                            String status, String transformExpr, String jobName, String executionId,
                            boolean maskingApplied) {
    }

    public record TraceResult(String status, String reason, String message, String columnId,
                              String direction, int depth, boolean includePlanned,
                              TraceNode root, List<TraceNode> nodes, List<TraceEdge> edges,
                              int restrictedCount, boolean truncatedByAcl, boolean truncated) {
    }

    public record ImpactItem(String id, int level, String nodeType, String qualifiedName,
                             String displayName, String catalogDatasetId, String catalogColumnId,
                             String refType, String refId) {
    }

    public record ImpactSummary(int downstreamColumns, int downstreamTables, int metrics,
                                int reports, int qualityRules) {
    }

    public record ImpactResult(String status, String reason, String message, String targetId,
                               String targetKind, int depth, boolean includePlanned,
                               boolean includeGovernance, ImpactSummary summary,
                               List<ImpactItem> items, int restrictedCount, boolean truncatedByAcl,
                               boolean truncated) {
    }

    private final LineageService lineageService;

    public LineageAgentToolService(LineageService lineageService) {
        this.lineageService = lineageService;
    }

    // ── lineage_trace_column ─────────────────────────────────────────────

    public TraceResult traceColumn(String columnId, String direction, Integer depth, Boolean includePlanned) {
        String dir = normalizeDirection(direction);
        int levels = clampDepth(depth, DEFAULT_TRACE_DEPTH);
        boolean planned = Boolean.TRUE.equals(includePlanned);
        if (blank(columnId)) {
            throw new BizException(com.datagov.common.error.ErrorCode.SYS_VALIDATION_FAILED, "columnId 必填");
        }
        NodeView root = resolveRoot(columnId.trim());
        if (root == null) {
            return traceInconclusive(REASON_NODE_NOT_FOUND,
                    "该字段尚未建立血缘节点，无法给出溯源结论；这不代表它没有上下游。",
                    columnId, dir, levels, planned, 0, false);
        }
        if (LineageService.RESTRICTED_NODE_TYPE.equals(root.nodeType())) {
            return traceInconclusive(REASON_ROOT_RESTRICTED,
                    "当前用户无权查看该字段所属资产，按最小权限原则不返回血缘。",
                    columnId, dir, levels, planned, 1, true);
        }

        GraphView up = "downstream".equals(dir) ? null : lineageService.upstream(columnId, levels, planned);
        GraphView down = "upstream".equals(dir) ? null : lineageService.downstream(columnId, levels, planned);

        Map<String, EdgeView> edgeById = new LinkedHashMap<>();
        // 两个方向各自截断的受限分支互不重叠，直接相加。
        int restricted = 0;
        for (GraphView view : new GraphView[]{up, down}) {
            if (view == null) {
                continue;
            }
            view.edges().forEach(e -> edgeById.putIfAbsent(e.id(), e));
            restricted += view.restrictedCount();
        }
        boolean truncatedByAcl = restricted > 0;

        List<TraceNode> nodes = new ArrayList<>();
        if (up != null) {
            nodes.addAll(levelled(up, root.id(), true));
        }
        if (down != null) {
            nodes.addAll(levelled(down, root.id(), false));
        }
        nodes.sort(Comparator.comparing(TraceNode::side).thenComparingInt(TraceNode::level)
                .thenComparing(n -> n.qualifiedName() == null ? "" : n.qualifiedName()));

        List<EdgeView> evidence = edgeById.values().stream()
                .filter(e -> !LineageService.RESTRICTED_NODE_TYPE.equals(e.relation())).toList();
        boolean anyActive = evidence.stream().anyMatch(e -> "ACTIVE".equals(e.status()));

        boolean truncated = nodes.size() > MAX_NODES || edgeById.size() > MAX_EDGES;
        List<TraceNode> cappedNodes = nodes.stream().limit(MAX_NODES).toList();
        List<TraceEdge> cappedEdges = edgeById.values().stream().limit(MAX_EDGES)
                .map(LineageAgentToolService::toTraceEdge).toList();

        TraceNode rootView = new TraceNode(root.id(), root.nodeType(), root.qualifiedName(),
                root.displayName(), root.catalogDatasetId(), root.catalogColumnId(), "CENTER", 0);

        if (evidence.isEmpty() && restricted == 0) {
            return new TraceResult(STATUS_INCONCLUSIVE, REASON_NO_LINEAGE_EVIDENCE,
                    "未采集到该字段在所查方向上的血缘证据；未采集不等于没有依赖，请勿据此下结论。",
                    columnId, dir, levels, planned, rootView, List.of(), List.of(), 0, false, false);
        }
        if (!evidence.isEmpty() && !anyActive) {
            return new TraceResult(STATUS_INCONCLUSIVE, REASON_ONLY_PLANNED_EVIDENCE,
                    "仅有编译/发布阶段的 PLANNED 预估血缘，尚无执行确认证据，不能作为归因依据。",
                    columnId, dir, levels, planned, rootView, cappedNodes, cappedEdges,
                    restricted, truncatedByAcl, truncated);
        }
        return new TraceResult(STATUS_OK, null,
                truncatedByAcl ? "已按最小权限截断 " + restricted + " 个受限分支。" : null,
                columnId, dir, levels, planned, rootView, cappedNodes, cappedEdges,
                restricted, truncatedByAcl, truncated);
    }

    // ── lineage_impact_analysis ─────────────────────────────────────────

    /**
     * @param columnId 字段 ID（与 datasetId 二选一；都给时按字段）
     */
    public ImpactResult impactAnalysis(String columnId, String datasetId, Integer depth,
                                       Boolean includePlanned, Boolean includeGovernance) {
        int levels = clampDepth(depth, DEFAULT_IMPACT_DEPTH);
        boolean planned = Boolean.TRUE.equals(includePlanned);
        boolean governance = includeGovernance == null || includeGovernance;
        boolean byColumn = !blank(columnId);
        if (!byColumn && blank(datasetId)) {
            throw new BizException(com.datagov.common.error.ErrorCode.SYS_VALIDATION_FAILED,
                    "columnId 或 datasetId 至少提供一个");
        }
        String targetId = byColumn ? columnId.trim() : datasetId.trim();
        String kind = byColumn ? "COLUMN" : "DATASET";

        GraphView view;
        String rootNodeId;
        if (byColumn) {
            NodeView root = resolveRoot(targetId);
            if (root == null) {
                return impactInconclusive(REASON_NODE_NOT_FOUND,
                        "该字段尚未建立血缘节点，无法评估影响面；这不代表改动没有影响。",
                        targetId, kind, levels, planned, governance, 0, false);
            }
            if (LineageService.RESTRICTED_NODE_TYPE.equals(root.nodeType())) {
                return impactInconclusive(REASON_ROOT_RESTRICTED,
                        "当前用户无权查看该字段所属资产，按最小权限原则不返回影响面。",
                        targetId, kind, levels, planned, governance, 1, true);
            }
            view = lineageService.impact(targetId, levels, planned, governance);
            rootNodeId = root.id();
        } else {
            view = lineageService.datasetGraph(targetId, "downstream", levels, planned, governance);
            rootNodeId = targetId;
            if (view.truncatedByAcl() && view.nodes().stream()
                    .allMatch(n -> LineageService.RESTRICTED_NODE_TYPE.equals(n.nodeType()))) {
                return impactInconclusive(REASON_ROOT_RESTRICTED,
                        "当前用户无权查看该数据集，按最小权限原则不返回影响面。",
                        targetId, kind, levels, planned, governance, view.restrictedCount(), true);
            }
        }

        Map<String, Integer> levelOf = bfsLevels(view, rootNodeId);
        List<ImpactItem> all = new ArrayList<>();
        Set<String> tables = new LinkedHashSet<>();
        int columns = 0;
        int metrics = 0;
        int reports = 0;
        int rules = 0;
        for (NodeView node : view.nodes()) {
            if (node.id().equals(rootNodeId)) {
                continue;
            }
            String type = node.nodeType();
            if (LineageService.RESTRICTED_NODE_TYPE.equals(type)) {
                continue; // 受限占位只计数（restrictedCount），不进清单，避免暴露存在性之外的信息。
            }
            switch (type) {
                case "COLUMN" -> {
                    columns++;
                    if (!blank(node.catalogDatasetId())) {
                        tables.add(node.catalogDatasetId());
                    }
                }
                case "DATASET" -> tables.add(node.id());
                case SemanticLineageBridge.METRIC -> metrics++;
                case SemanticLineageBridge.REPORT -> reports++;
                case SemanticLineageBridge.QUALITY_RULE -> rules++;
                default -> {
                }
            }
            all.add(new ImpactItem(node.id(), levelOf.getOrDefault(node.id(), 0), type,
                    node.qualifiedName(), node.displayName(), node.catalogDatasetId(),
                    node.catalogColumnId(), node.externalRefType(), node.externalRefId()));
        }
        all.sort(Comparator.comparingInt(ImpactItem::level)
                .thenComparing(ImpactItem::nodeType)
                .thenComparing(i -> i.qualifiedName() == null ? "" : i.qualifiedName()));
        boolean truncated = all.size() > MAX_NODES;
        List<ImpactItem> items = all.stream().limit(MAX_NODES).toList();
        ImpactSummary summary = new ImpactSummary(columns, tables.size(), metrics, reports, rules);

        boolean lineageEdges = view.edges().stream().anyMatch(e ->
                !LineageService.RESTRICTED_NODE_TYPE.equals(e.relation())
                        && !LineageService.GOV_RELATION.equals(e.relation()));
        boolean activeEvidence = view.edges().stream().anyMatch(e ->
                !LineageService.RESTRICTED_NODE_TYPE.equals(e.relation())
                        && "ACTIVE".equals(e.status()));
        boolean consumers = metrics + reports + rules > 0;
        int restricted = view.restrictedCount();

        if (!activeEvidence && !consumers) {
            if (lineageEdges) {
                return new ImpactResult(STATUS_INCONCLUSIVE, REASON_ONLY_PLANNED_EVIDENCE,
                        "仅有 PLANNED 预估下游，尚无执行确认证据，不能作为影响评估依据。",
                        targetId, kind, levels, planned, governance, summary, items,
                        restricted, view.truncatedByAcl(), truncated);
            }
            if (restricted == 0) {
                return new ImpactResult(STATUS_INCONCLUSIVE, REASON_NO_DOWNSTREAM_EVIDENCE,
                        "未采集到下游血缘或治理消费方；未采集不等于没有影响，请勿据此宣称“无影响”。",
                        targetId, kind, levels, planned, governance, summary, List.of(),
                        0, false, false);
            }
        }
        return new ImpactResult(STATUS_OK, null,
                view.truncatedByAcl() ? "已按最小权限截断 " + restricted + " 个受限分支，影响面可能不完整。" : null,
                targetId, kind, levels, planned, governance, summary, items,
                restricted, view.truncatedByAcl(), truncated);
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    /** 起点解析；通过 {@link LineageService#getNode} 获得与界面完全一致的 ACL 行为（受限节点回占位）。 */
    private NodeView resolveRoot(String columnId) {
        try {
            return lineageService.getNode(columnId);
        } catch (BizException ex) {
            return null;
        }
    }

    private static List<TraceNode> levelled(GraphView view, String rootId, boolean upstream) {
        Map<String, Integer> levels = bfsLevels(view, rootId, upstream);
        List<TraceNode> result = new ArrayList<>();
        for (NodeView node : view.nodes()) {
            if (node.id().equals(rootId) || LineageService.RESTRICTED_NODE_TYPE.equals(node.nodeType())) {
                continue;
            }
            result.add(new TraceNode(node.id(), node.nodeType(), node.qualifiedName(), node.displayName(),
                    node.catalogDatasetId(), node.catalogColumnId(),
                    upstream ? "UPSTREAM" : "DOWNSTREAM", levels.getOrDefault(node.id(), 0)));
        }
        return result;
    }

    private static Map<String, Integer> bfsLevels(GraphView view, String rootId) {
        return bfsLevels(view, rootId, false);
    }

    private static Map<String, Integer> bfsLevels(GraphView view, String rootId, boolean upstream) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (EdgeView edge : view.edges()) {
            String key = upstream ? edge.toNodeId() : edge.fromNodeId();
            String value = upstream ? edge.fromNodeId() : edge.toNodeId();
            adjacency.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        Map<String, Integer> levels = new HashMap<>();
        levels.put(rootId, 0);
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (!levels.containsKey(next)) {
                    levels.put(next, levels.get(current) + 1);
                    queue.add(next);
                }
            }
        }
        return levels;
    }

    private static TraceEdge toTraceEdge(EdgeView e) {
        return new TraceEdge(e.fromNodeId(), e.toNodeId(), e.relation(), e.dependencyType(), e.status(),
                e.transformExpr(), e.jobName(), e.executionId(), Boolean.TRUE.equals(e.maskingApplied()));
    }

    private static TraceResult traceInconclusive(String reason, String message, String columnId,
                                                 String direction, int depth, boolean planned,
                                                 int restricted, boolean truncatedByAcl) {
        return new TraceResult(STATUS_INCONCLUSIVE, reason, message, columnId, direction, depth, planned,
                null, List.of(), List.of(), restricted, truncatedByAcl, false);
    }

    private static ImpactResult impactInconclusive(String reason, String message, String targetId,
                                                   String kind, int depth, boolean planned,
                                                   boolean governance, int restricted,
                                                   boolean truncatedByAcl) {
        return new ImpactResult(STATUS_INCONCLUSIVE, reason, message, targetId, kind, depth, planned,
                governance, new ImpactSummary(0, 0, 0, 0, 0), List.of(), restricted, truncatedByAcl, false);
    }

    private static String normalizeDirection(String direction) {
        String dir = blank(direction) ? "both" : direction.trim().toLowerCase();
        if (!"upstream".equals(dir) && !"downstream".equals(dir) && !"both".equals(dir)) {
            throw new BizException(com.datagov.common.error.ErrorCode.SYS_VALIDATION_FAILED,
                    "direction 只能是 upstream / downstream / both");
        }
        return dir;
    }

    private static int clampDepth(Integer depth, int fallback) {
        int value = depth == null ? fallback : depth;
        return Math.max(1, Math.min(value, 5));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
