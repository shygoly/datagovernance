package com.datagov.metadata.service.lineage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.id.Ids;
import com.datagov.metadata.entity.LineageEntities.LineageEdge;
import com.datagov.metadata.entity.LineageEntities.LineageJob;
import com.datagov.metadata.entity.LineageEntities.LineageNode;
import com.datagov.metadata.entity.SensitivityEntities.ColumnSensitivity;
import com.datagov.metadata.mapper.ColumnSensitivityMapper;
import com.datagov.metadata.mapper.LineageEdgeMapper;
import com.datagov.metadata.mapper.LineageJobMapper;
import com.datagov.metadata.mapper.LineageNodeMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感标签沿血缘向下游传播(column-lineage-e2e P2-5，设计 D6)。
 *
 * <p><b>触发时机</b>：
 * <ol>
 *   <li>某列的敏感标签被人工复核为 {@code CONFIRMED} 且级别为 L3/L4
 *       ({@link #propagateFromConfirmedLabel})；</li>
 *   <li>产生新的 {@code ACTIVE + DIRECT} 边，而上游列已有 CONFIRMED 的 L3/L4 标签
 *       ({@link #propagateFromEdges})。</li>
 * </ol>
 *
 * <p><b>传播规则</b>：沿 {@code ACTIVE + DIRECT} 边向下游 BFS，最多 {@value #MAX_HOPS} 跳；
 * INDIRECT 边(过滤/关联/分组条件)不携带取值，不是传播通道。
 *
 * <p><b>阻断规则</b>：边 {@code masking_applied = true}，或 {@code transform_expr} 命中
 * MASK / MD5 / SHA / HASH / ENCRYPT / DESENSITIZE 之一，说明高密级取值在这条边上已被不可逆处理，
 * 该分支停止传播，并留下可追溯的阻断证据({@link BlockedEvidence}，同时写日志)。
 *
 * <p><b>落盘规则(C9)</b>：只产出 {@code SUGGESTED}，{@code detected_by = 'LINEAGE'}，进入人工复核；
 * 绝不覆盖人工结论 —— 目标列已有同 category 的 CONFIRMED / REJECTED 标签时不写；
 * 已有非 LINEAGE 来源(规则扫描等)的 SUGGESTED 也不改写。人工 REJECTED 的列视为人工判定
 * 「该列不敏感」，传播在该列截止。
 *
 * <p>所有方法显式接收 {@code workspaceId}，可在没有 {@code WorkspaceContext} 的线程里调用。
 */
@Component
public class LineageSensitivityPropagator {

    private static final Logger log = LoggerFactory.getLogger(LineageSensitivityPropagator.class);

    public static final int MAX_HOPS = 5;
    /** 传播得到的建议置信度基数：0.85 × 路径上各边置信度之积。 */
    public static final double BASE_CONFIDENCE = 0.85;
    public static final String DETECTED_BY = "LINEAGE";

    private static final Set<String> PROPAGATING_LEVELS = Set.of("L3", "L4");
    private static final int MAX_VISITED_NODES = 5000;

    /**
     * 确定性脱敏 / 不可逆哈希函数。{@code SHA} 必须是独立词(可带位数)，否则 {@code SHARE_COUNT}
     * 这种普通列名会被误判为哈希而漏掉传播。
     */
    static final Pattern MASKING_FUNCTION = Pattern.compile(
            "(?i)(MASK|MD5|\\bSHA[_-]?\\d*\\b|HASH|ENCRYPT|DESENSITIZ)");

    private final LineageNodeMapper nodeMapper;
    private final LineageEdgeMapper edgeMapper;
    private final LineageJobMapper jobMapper;
    private final ColumnSensitivityMapper sensitivityMapper;
    private final ObjectMapper objectMapper;

    public LineageSensitivityPropagator(LineageNodeMapper nodeMapper,
                                        LineageEdgeMapper edgeMapper,
                                        LineageJobMapper jobMapper,
                                        ColumnSensitivityMapper sensitivityMapper,
                                        ObjectMapper objectMapper) {
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.jobMapper = jobMapper;
        this.sensitivityMapper = sensitivityMapper;
        this.objectMapper = objectMapper;
    }

    /** 一条被阻断的传播路径证据。 */
    public record BlockedEvidence(String edgeId, String fromNodeId, String toNodeId,
                                  String sourceColumnId, String category, String reason) {
    }

    public record PropagationResult(int created, int updated, int blocked, int visitedNodes,
                                    List<BlockedEvidence> blockedEvidence) {
        static PropagationResult empty() {
            return new PropagationResult(0, 0, 0, 0, List.of());
        }
    }

    // ── 入口 ────────────────────────────────────────────────────────────

    /** 触发 1：某列标签被复核为 CONFIRMED 后调用。非 L3/L4 的标签自动忽略。 */
    public PropagationResult propagateFromConfirmedLabel(String workspaceId, String columnId) {
        if (isBlank(workspaceId) || isBlank(columnId)) {
            return PropagationResult.empty();
        }
        List<ColumnSensitivity> labels = confirmedPropagatingLabels(workspaceId, List.of(columnId))
                .getOrDefault(columnId, List.of());
        if (labels.isEmpty()) {
            return PropagationResult.empty();
        }
        Counter counter = new Counter();
        for (LineageNode start : nodesOfColumn(workspaceId, columnId)) {
            for (ColumnSensitivity label : labels) {
                walk(workspaceId, label, start, null, counter);
            }
        }
        return counter.result();
    }

    /** 触发 2：新的 ACTIVE + DIRECT 边写入后调用；只关心这些边作为第一跳的传播。 */
    public PropagationResult propagateFromEdges(String workspaceId, Collection<LineageEdge> edges) {
        if (isBlank(workspaceId) || edges == null || edges.isEmpty()) {
            return PropagationResult.empty();
        }
        Map<String, Set<String>> firstHopByFrom = new LinkedHashMap<>();
        for (LineageEdge edge : edges) {
            if (edge == null || edge.getFromNodeId() == null) {
                continue;
            }
            firstHopByFrom.computeIfAbsent(edge.getFromNodeId(), k -> new LinkedHashSet<>())
                    .add(edge.getId());
        }
        Map<String, LineageNode> fromNodes = loadNodes(workspaceId, firstHopByFrom.keySet());
        Set<String> columnIds = new LinkedHashSet<>();
        for (LineageNode node : fromNodes.values()) {
            if (!isBlank(node.getCatalogColumnId())) {
                columnIds.add(node.getCatalogColumnId());
            }
        }
        if (columnIds.isEmpty()) {
            return PropagationResult.empty();
        }
        Map<String, List<ColumnSensitivity>> labelsByColumn =
                confirmedPropagatingLabels(workspaceId, columnIds);
        Counter counter = new Counter();
        for (LineageNode from : fromNodes.values()) {
            List<ColumnSensitivity> labels = labelsByColumn.get(from.getCatalogColumnId());
            if (labels == null || labels.isEmpty()) {
                continue;
            }
            for (ColumnSensitivity label : labels) {
                walk(workspaceId, label, from, firstHopByFrom.get(from.getId()), counter);
            }
        }
        return counter.result();
    }

    // ── 传播核心 ────────────────────────────────────────────────────────

    private record Step(String nodeId, List<String> edgeIds, double confidence) {
    }

    private void walk(String workspaceId, ColumnSensitivity label, LineageNode start,
                      Set<String> restrictFirstHop, Counter counter) {
        Set<String> visited = new LinkedHashSet<>();
        visited.add(start.getId());
        Map<String, Step> frontier = new LinkedHashMap<>();
        frontier.put(start.getId(), new Step(start.getId(), List.of(), 1.0));

        for (int hop = 1; hop <= MAX_HOPS && !frontier.isEmpty(); hop++) {
            List<LineageEdge> edges = activeDirectEdgesFrom(workspaceId, frontier.keySet());
            if (hop == 1 && restrictFirstHop != null) {
                edges = edges.stream().filter(e -> restrictFirstHop.contains(e.getId())).toList();
            }
            if (edges.isEmpty()) {
                return;
            }
            Map<String, LineageNode> targets = loadNodes(workspaceId,
                    edges.stream().map(LineageEdge::getToNodeId).collect(
                            java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
            Map<String, ColumnSensitivity> existing = existingLabels(workspaceId, label.getCategory(),
                    targets.values().stream().map(LineageNode::getCatalogColumnId)
                            .filter(id -> !isBlank(id)).toList());
            Map<String, String> jobNames = jobNames(workspaceId,
                    edges.stream().map(LineageEdge::getJobId).filter(id -> !isBlank(id)).toList());

            Map<String, Step> next = new LinkedHashMap<>();
            for (LineageEdge edge : edges) {
                Step parent = frontier.get(edge.getFromNodeId());
                if (parent == null) {
                    continue;
                }
                String blockedBy = blockReason(edge);
                if (blockedBy != null) {
                    counter.blocked(new BlockedEvidence(edge.getId(), edge.getFromNodeId(),
                            edge.getToNodeId(), label.getColumnId(), label.getCategory(), blockedBy));
                    log.info("敏感传播被脱敏阻断 workspace={} category={} sourceColumn={} edge={} reason={}",
                            workspaceId, label.getCategory(), label.getColumnId(), edge.getId(), blockedBy);
                    continue;
                }
                LineageNode to = targets.get(edge.getToNodeId());
                // 先判阻断再判已访问：同一节点可能一条路径被脱敏、另一条路径是明文直连。
                if (to == null || !visited.add(to.getId())) {
                    continue;
                }
                if (visited.size() > MAX_VISITED_NODES) {
                    log.warn("敏感传播访问节点超限，提前终止 workspace={} sourceColumn={}",
                            workspaceId, label.getColumnId());
                    return;
                }
                List<String> edgeIds = new ArrayList<>(parent.edgeIds());
                edgeIds.add(edge.getId());
                double confidence = parent.confidence()
                        * (edge.getConfidence() == null ? 1.0 : clamp(edge.getConfidence()));
                Step step = new Step(to.getId(), edgeIds, confidence);
                counter.visited++;

                boolean keepGoing = suggest(workspaceId, label, to, edge, step, existing,
                        jobNames, counter);
                if (keepGoing) {
                    next.put(to.getId(), step);
                }
            }
            frontier = next;
        }
    }

    /** @return 是否继续沿该节点向更下游传播 */
    private boolean suggest(String workspaceId, ColumnSensitivity label, LineageNode target,
                            LineageEdge viaEdge, Step step, Map<String, ColumnSensitivity> existingByColumn,
                            Map<String, String> jobNames, Counter counter) {
        String columnId = target.getCatalogColumnId();
        String datasetId = target.getCatalogDatasetId();
        if (isBlank(columnId) || isBlank(datasetId)) {
            // 目标尚未与 Catalog 对齐：没有地方挂标签，但数据仍然往下流。
            return true;
        }
        ColumnSensitivity existing = existingByColumn.get(columnId);
        if (existing != null) {
            if ("REJECTED".equals(existing.getStatus())) {
                return false;
            }
            if ("CONFIRMED".equals(existing.getStatus())) {
                return true;
            }
            if (!DETECTED_BY.equals(existing.getDetectedBy())) {
                // 规则扫描等来源的建议自有证据，不被血缘改写。
                return true;
            }
        }

        String level = existing != null && levelRank(existing.getLevel()) > levelRank(label.getLevel())
                ? existing.getLevel() : label.getLevel();
        double confidence = round(BASE_CONFIDENCE * step.confidence());
        String evidence = evidenceJson(label, step, jobNames, viaEdge);

        if (existing == null) {
            ColumnSensitivity value = new ColumnSensitivity();
            value.setId(Ids.of("sens"));
            value.setWorkspaceId(workspaceId);
            value.setDatasetId(datasetId);
            value.setColumnId(columnId);
            value.setCategory(label.getCategory());
            value.setLevel(level);
            value.setConfidence(confidence);
            value.setStatus("SUGGESTED");
            value.setEvidenceJson(evidence);
            value.setDetectedBy(DETECTED_BY);
            value.setDetectedAt(Instant.now());
            sensitivityMapper.insert(value);
            existingByColumn.put(columnId, value);
            counter.created++;
        } else if (!level.equals(existing.getLevel()) || !evidence.equals(existing.getEvidenceJson())) {
            existing.setLevel(level);
            existing.setConfidence(Math.max(confidence,
                    existing.getConfidence() == null ? 0 : existing.getConfidence()));
            existing.setEvidenceJson(evidence);
            existing.setDetectedAt(Instant.now());
            sensitivityMapper.updateById(existing);
            counter.updated++;
        }
        return true;
    }

    // ── 阻断判定 ────────────────────────────────────────────────────────

    /** @return 阻断原因；null 表示这条边可以继续传播 */
    static String blockReason(LineageEdge edge) {
        if (Boolean.TRUE.equals(edge.getMaskingApplied())) {
            String evidence = edge.getMaskingEvidence();
            return "blockedByMasking: masking_applied=true"
                    + (evidence == null || evidence.isBlank() ? "" : " (" + evidence + ")");
        }
        String expr = edge.getTransformExpr();
        if (expr != null && !expr.isBlank()) {
            Matcher matcher = MASKING_FUNCTION.matcher(expr);
            if (matcher.find()) {
                return "blockedByMasking: transform_expr 命中 " + matcher.group(1).toUpperCase();
            }
        }
        return null;
    }

    // ── 数据访问（package-private，便于单测替换）───────────────────────

    List<LineageNode> nodesOfColumn(String workspaceId, String columnId) {
        return nodeMapper.selectList(new LambdaQueryWrapper<LineageNode>()
                .eq(LineageNode::getWorkspaceId, workspaceId)
                .eq(LineageNode::getCatalogColumnId, columnId));
    }

    Map<String, LineageNode> loadNodes(String workspaceId, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<String, LineageNode> result = new LinkedHashMap<>();
        for (LineageNode node : nodeMapper.selectList(new LambdaQueryWrapper<LineageNode>()
                .eq(LineageNode::getWorkspaceId, workspaceId)
                .in(LineageNode::getId, ids))) {
            result.put(node.getId(), node);
        }
        return result;
    }

    List<LineageEdge> activeDirectEdgesFrom(String workspaceId, Collection<String> fromNodeIds) {
        if (fromNodeIds == null || fromNodeIds.isEmpty()) {
            return List.of();
        }
        return edgeMapper.selectList(new LambdaQueryWrapper<LineageEdge>()
                .eq(LineageEdge::getWorkspaceId, workspaceId)
                .eq(LineageEdge::getStatus, "ACTIVE")
                .eq(LineageEdge::getDependencyType, "DIRECT")
                .in(LineageEdge::getFromNodeId, fromNodeIds));
    }

    /** columnId → 该列上 CONFIRMED 且级别 ∈ L3/L4 的标签。 */
    Map<String, List<ColumnSensitivity>> confirmedPropagatingLabels(String workspaceId,
                                                                    Collection<String> columnIds) {
        Map<String, List<ColumnSensitivity>> result = new LinkedHashMap<>();
        if (columnIds == null || columnIds.isEmpty()) {
            return result;
        }
        for (ColumnSensitivity label : sensitivityMapper.selectList(
                new LambdaQueryWrapper<ColumnSensitivity>()
                        .eq(ColumnSensitivity::getWorkspaceId, workspaceId)
                        .eq(ColumnSensitivity::getStatus, "CONFIRMED")
                        .in(ColumnSensitivity::getLevel, PROPAGATING_LEVELS)
                        .in(ColumnSensitivity::getColumnId, columnIds))) {
            if (label.getLevel() != null && PROPAGATING_LEVELS.contains(label.getLevel())) {
                result.computeIfAbsent(label.getColumnId(), k -> new ArrayList<>()).add(label);
            }
        }
        return result;
    }

    Map<String, ColumnSensitivity> existingLabels(String workspaceId, String category,
                                                  Collection<String> columnIds) {
        Map<String, ColumnSensitivity> result = new LinkedHashMap<>();
        if (columnIds == null || columnIds.isEmpty()) {
            return result;
        }
        for (ColumnSensitivity label : sensitivityMapper.selectList(
                new LambdaQueryWrapper<ColumnSensitivity>()
                        .eq(ColumnSensitivity::getWorkspaceId, workspaceId)
                        .eq(ColumnSensitivity::getCategory, category)
                        .in(ColumnSensitivity::getColumnId, columnIds))) {
            result.put(label.getColumnId(), label);
        }
        return result;
    }

    Map<String, String> jobNames(String workspaceId, Collection<String> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (LineageJob job : jobMapper.selectList(new LambdaQueryWrapper<LineageJob>()
                .eq(LineageJob::getWorkspaceId, workspaceId)
                .in(LineageJob::getId, new LinkedHashSet<>(jobIds)))) {
            result.put(job.getId(), job.getName());
        }
        return result;
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    private String evidenceJson(ColumnSensitivity source, Step step, Map<String, String> jobNames,
                                LineageEdge lastEdge) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("origin", "LINEAGE");
        evidence.put("sourceLabelId", source.getId());
        evidence.put("sourceColumnId", source.getColumnId());
        evidence.put("sourceDatasetId", source.getDatasetId());
        evidence.put("sourceLevel", source.getLevel());
        evidence.put("hops", step.edgeIds().size());
        evidence.put("edgeIds", step.edgeIds());
        String jobName = lastEdge.getJobId() == null ? null : jobNames.get(lastEdge.getJobId());
        if (jobName != null) {
            evidence.put("lastJobName", jobName);
        }
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("血缘敏感传播证据序列化失败", e);
        }
    }

    private static final class Counter {
        int created;
        int updated;
        int visited;
        final List<BlockedEvidence> blocked = new ArrayList<>();

        void blocked(BlockedEvidence evidence) {
            blocked.add(evidence);
        }

        PropagationResult result() {
            return new PropagationResult(created, updated, blocked.size(), visited,
                    List.copyOf(blocked));
        }
    }

    private static int levelRank(String level) {
        return switch (level == null ? "" : level.toUpperCase()) {
            case "L4" -> 4;
            case "L3" -> 3;
            case "L2" -> 2;
            case "L1" -> 1;
            default -> 0;
        };
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
