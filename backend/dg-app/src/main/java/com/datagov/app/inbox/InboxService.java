package com.datagov.app.inbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.app.insight.entity.AgentActionProposal;
import com.datagov.app.insight.mapper.AgentActionProposalMapper;
import com.datagov.governance.entity.GovernanceEntities.InboxState;
import com.datagov.governance.mapper.InboxStateMapper;
import com.datagov.runtime.entity.AutonomyRuntimeEntities.FlowInstance;
import com.datagov.runtime.mapper.AuFlowInstanceMapper;
import com.datagov.governance.service.AlertService;
import com.datagov.governance.service.AlertService.AlertView;
import com.datagov.metadata.service.SensitivityService;
import com.datagov.metadata.service.SensitivityService.SensitivityView;
import com.datagov.runtime.domain.ExecutionStatus;
import com.datagov.runtime.dto.ExecutionView;
import com.datagov.runtime.service.ExecutionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 治理收件箱：把现有执行、告警、敏感标签聚合成一个可处理的队列。
 *
 * <p>不复制来源事实。gv_inbox_state 只覆盖“移出/恢复”，来源状态仍由各自
 * 模块维护；这是 P0 “工作台摘要卡”到 P1 “可操作收件箱”的边界。
 */
@Service
public class InboxService {

    private static final int SOURCE_LIMIT = 200;
    private static final String EXECUTION = "EXECUTION";
    private static final String ALERT = "ALERT";
    private static final String SENSITIVITY = "SENSITIVITY";
    private static final String AUTONOMY = "AUTONOMY";
    private static final String AUTONOMY_APPROVAL = "AUTONOMY_APPROVAL";
    private static final String OPEN = "OPEN";
    private static final String DISMISSED = "DISMISSED";

    private final ExecutionService executionService;
    private final AlertService alertService;
    private final SensitivityService sensitivityService;
    private final InboxStateMapper stateMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuFlowInstanceMapper flowInstanceMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AgentActionProposalMapper proposalMapper;

    public InboxService(ExecutionService executionService,
                        AlertService alertService,
                        SensitivityService sensitivityService,
                        InboxStateMapper stateMapper) {
        this.executionService = executionService;
        this.alertService = alertService;
        this.sensitivityService = sensitivityService;
        this.stateMapper = stateMapper;
    }

    public record InboxItemView(String sourceType, String sourceId, String title,
                                String summary, String severity, Instant occurredAt,
                                String sourceState, String state, String route) {
    }

    public record InboxPage(List<InboxItemView> records, long total, long page,
                            long size, boolean truncated) {
    }

    public record InboxStateView(String sourceType, String sourceId, String state) {
    }

    public InboxPage list(long page, long size, String sourceType, String state,
                          String severity, String keyword) {
        Caller caller = WorkspaceContext.require();
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        long safePage = Math.max(1, page);
        long safeSize = Math.min(100, Math.max(1, size));
        String sourceFilter = normalizeSourceFilter(sourceType);
        String stateFilter = normalizeState(state);
        String severityFilter = normalizeSeverity(severity);
        String term = keyword == null ? null : keyword.trim().toLowerCase(Locale.ROOT);

        Map<String, InboxState> stateMap = new HashMap<>();
        for (InboxState value : stateMapper.selectList(new LambdaQueryWrapper<InboxState>()
                .eq(InboxState::getWorkspaceId, workspaceId))) {
            stateMap.put(key(value.getSourceType(), value.getSourceId()), value);
        }

        List<InboxItemView> items = new ArrayList<>();
        boolean truncated = false;

        if ((sourceFilter == null || EXECUTION.equals(sourceFilter))
                && caller.hasPermission("runtime:execution:read")) {
            for (ExecutionStatus status : List.of(ExecutionStatus.FAILED, ExecutionStatus.TIMEOUT)) {
                PageResult<ExecutionView> result = executionService.list(
                        1, SOURCE_LIMIT, null, status, null);
                truncated |= result.total() > result.records().size();
                for (ExecutionView execution : result.records()) {
                    items.add(executionItem(workspaceId, execution, stateMap));
                }
            }
        }

        if ((sourceFilter == null || ALERT.equals(sourceFilter))
                && caller.hasPermission("governance:alert:read")) {
            PageResult<AlertView> result = alertService.listOpen(1, SOURCE_LIMIT);
            truncated |= result.total() > result.records().size();
            for (AlertView alert : result.records()) {
                items.add(alertItem(workspaceId, alert, stateMap));
            }
        }

        if ((sourceFilter == null || SENSITIVITY.equals(sourceFilter))
                && caller.hasPermission("metadata:sensitivity:read")) {
            PageResult<SensitivityView> result = sensitivityService.list(
                    1, SOURCE_LIMIT, null, "SUGGESTED", null);
            truncated |= result.total() > result.records().size();
            for (SensitivityView label : result.records()) {
                items.add(sensitivityItem(workspaceId, label, stateMap));
            }
        }

        if ((sourceFilter == null || AUTONOMY.equals(sourceFilter))
                && caller.hasPermission("autonomy:trace:read")
                && flowInstanceMapper != null) {
            for (FlowInstance instance : flowInstanceMapper.selectList(
                    new LambdaQueryWrapper<FlowInstance>()
                            .eq(FlowInstance::getWorkspaceId, workspaceId)
                            .in(FlowInstance::getStatus, List.of("FAILED", "COMPENSATION_FAILED"))
                            .orderByDesc(FlowInstance::getUpdatedAt)
                            .last("LIMIT " + SOURCE_LIMIT))) {
                String autonomySeverity = "COMPENSATION_FAILED".equals(instance.getStatus())
                        ? "CRITICAL" : "HIGH";
                items.add(item(AUTONOMY, instance.getId(), "自治流程 " + instance.getFlowCode(),
                        instance.getErrorMessage() == null ? instance.getStatus() : instance.getErrorMessage(),
                        autonomySeverity, instance.getFinishedAt(), instance.getStatus(),
                        "/autonomy/traces?instanceId=" + instance.getId(), workspaceId, stateMap));
            }
        }

        if ((sourceFilter == null || AUTONOMY_APPROVAL.equals(sourceFilter))
                && caller.hasPermission("autonomy:flow:approve")
                && proposalMapper != null) {
            Instant now = Instant.now();
            for (AgentActionProposal proposal : proposalMapper.selectList(
                    new LambdaQueryWrapper<AgentActionProposal>()
                            .eq(AgentActionProposal::getWorkspaceId, workspaceId)
                            .eq(AgentActionProposal::getActionType, "AUTONOMY_FLOW_APPROVAL")
                            .eq(AgentActionProposal::getStatus, "PENDING")
                            .gt(AgentActionProposal::getExpiresAt, now)
                            .orderByDesc(AgentActionProposal::getCreatedAt)
                            .last("LIMIT " + SOURCE_LIMIT))) {
                items.add(item(AUTONOMY_APPROVAL, proposal.getId(), proposal.getTitle(),
                        proposal.getSummary(), "HIGH", proposal.getCreatedAt(), "待审批",
                        "/autonomy/flows", workspaceId, stateMap));
            }
        }

        List<InboxItemView> filtered = items.stream()
                .filter(item -> stateFilter == null || stateFilter.equals(item.state()))
                .filter(item -> severityFilter == null || severityFilter.equals(item.severity()))
                .filter(item -> term == null || term.isBlank()
                        || item.title().toLowerCase(Locale.ROOT).contains(term)
                        || item.summary().toLowerCase(Locale.ROOT).contains(term)
                        || item.sourceId().toLowerCase(Locale.ROOT).contains(term))
                .sorted(Comparator
                        .comparingInt((InboxItemView item) -> severityRank(item.severity()))
                        .reversed()
                        .thenComparing(InboxItemView::occurredAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        long fromLong = (safePage - 1) * safeSize;
        int from = (int) Math.min(filtered.size(), Math.max(0, fromLong));
        int to = (int) Math.min(filtered.size(), from + safeSize);
        return new InboxPage(filtered.subList(from, to), filtered.size(),
                safePage, safeSize, truncated);
    }

    @Transactional
    public InboxStateView setState(String sourceType, String sourceId, boolean dismiss) {
        Caller caller = WorkspaceContext.require();
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String type = normalizeSource(sourceType, true);
        if (sourceId == null || sourceId.isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "来源 ID 不能为空");
        }
        requireSourcePermission(caller, type);
        String targetState = dismiss ? DISMISSED : OPEN;

        InboxState existing = stateMapper.selectOne(new LambdaQueryWrapper<InboxState>()
                .eq(InboxState::getWorkspaceId, workspaceId)
                .eq(InboxState::getSourceType, type)
                .eq(InboxState::getSourceId, sourceId.trim()));
        InboxState value = existing == null ? new InboxState() : existing;
        if (existing == null) {
            value.setId(Ids.of("inbox"));
            value.setWorkspaceId(workspaceId);
            value.setSourceType(type);
            value.setSourceId(sourceId.trim());
        }
        value.setState(targetState);
        value.setUpdatedBy(caller.userId());
        value.setUpdatedAt(Instant.now());
        if (existing == null) {
            stateMapper.insert(value);
        } else {
            stateMapper.updateById(value);
        }
        return new InboxStateView(type, sourceId.trim(), targetState);
    }

    private InboxItemView executionItem(String workspaceId, ExecutionView value,
                                        Map<String, InboxState> stateMap) {
        String severity = value.status() == ExecutionStatus.FAILED ? "HIGH" : "MEDIUM";
        String title = value.jobName() == null || value.jobName().isBlank()
                ? "执行 " + value.id() : value.jobName();
        String summary = value.message() == null || value.message().isBlank()
                ? value.statusDisplayName() : value.message();
        return item(EXECUTION, value.id(), title, summary, severity,
                value.submittedAt(), value.statusDisplayName(), "/ops/executions",
                workspaceId, stateMap);
    }

    private InboxItemView alertItem(String workspaceId, AlertView value,
                                    Map<String, InboxState> stateMap) {
        String severity = switch (value.severity() == null ? "" : value.severity()) {
            case "CRITICAL" -> "CRITICAL";
            case "WARNING" -> "MEDIUM";
            default -> "LOW";
        };
        String summary = value.content() == null || value.content().isBlank()
                ? value.ruleName() : value.content();
        return item(ALERT, value.id(), value.title(), summary, severity,
                value.triggeredAt(), value.statusDisplayName(), "/ops/alerts",
                workspaceId, stateMap);
    }

    private InboxItemView sensitivityItem(String workspaceId, SensitivityView value,
                                          Map<String, InboxState> stateMap) {
        String severity = switch (value.level() == null ? "" : value.level()) {
            case "L4" -> "CRITICAL";
            case "L3" -> "HIGH";
            case "L2" -> "MEDIUM";
            default -> "LOW";
        };
        String dataset = value.datasetName() == null
                ? value.datasetQualifiedName() : value.datasetName();
        String summary = (dataset == null ? value.datasetId() : dataset)
                + " · " + value.category() + " · " + value.level();
        String route = "/metadata/sensitivity?datasetId=" + value.datasetId()
                + "&reviewId=" + value.id();
        return item(SENSITIVITY, value.id(), value.columnName(), summary, severity,
                value.detectedAt(), "待复核", route, workspaceId, stateMap);
    }

    private InboxItemView item(String sourceType, String sourceId, String title,
                               String summary, String severity, Instant occurredAt,
                               String sourceState, String route, String workspaceId,
                               Map<String, InboxState> stateMap) {
        InboxState state = stateMap.get(key(sourceType, sourceId));
        return new InboxItemView(sourceType, sourceId,
                title == null ? sourceId : title,
                summary == null ? "" : summary,
                severity, occurredAt, sourceState,
                state == null || state.getState() == null ? OPEN : state.getState(),
                route);
    }

    private static String key(String sourceType, String sourceId) {
        return sourceType + ":" + sourceId;
    }

    private static String normalizeSourceFilter(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (EXECUTION.equals(normalized) || ALERT.equals(normalized)
                || SENSITIVITY.equals(normalized) || AUTONOMY.equals(normalized)
                || AUTONOMY_APPROVAL.equals(normalized)) {
            return normalized;
        }
        throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                "来源类型只能是 EXECUTION / ALERT / SENSITIVITY / AUTONOMY / AUTONOMY_APPROVAL");
    }

    private static String normalizeSource(String value, boolean strict) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.trim())) {
            if (strict) {
                throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                        "来源类型只能是 EXECUTION / ALERT / SENSITIVITY / AUTONOMY / AUTONOMY_APPROVAL");
            }
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (EXECUTION.equals(normalized) || ALERT.equals(normalized)
                || SENSITIVITY.equals(normalized) || AUTONOMY.equals(normalized)
                || AUTONOMY_APPROVAL.equals(normalized)) {
            return normalized;
        }
        if (strict) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "来源类型只能是 EXECUTION / ALERT / SENSITIVITY / AUTONOMY / AUTONOMY_APPROVAL");
        }
        return null;
    }

    private static String normalizeState(String value) {
        if (value == null || value.isBlank()) {
            return OPEN;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("ALL".equals(normalized)) {
            return null;
        }
        if (OPEN.equals(normalized) || DISMISSED.equals(normalized)) {
            return normalized;
        }
        throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                "收件箱状态只能是 OPEN / DISMISSED");
    }

    private static String normalizeSeverity(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (List.of("CRITICAL", "HIGH", "MEDIUM", "LOW").contains(normalized)) {
            return normalized;
        }
        throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                "严重度只能是 CRITICAL / HIGH / MEDIUM / LOW");
    }

    private static int severityRank(String severity) {
        return switch (severity == null ? "" : severity) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private static void requireSourcePermission(Caller caller, String sourceType) {
        String permission = switch (sourceType) {
            case EXECUTION -> "runtime:execution:read";
            case ALERT -> "governance:alert:read";
            case SENSITIVITY -> "metadata:sensitivity:read";
            case AUTONOMY -> "autonomy:trace:read";
            case AUTONOMY_APPROVAL -> "autonomy:flow:approve";
            default -> null;
        };
        if (permission == null || !caller.hasPermission(permission)) {
            throw BizException.forbidden("缺少来源查看权限: " + sourceType);
        }
    }
}
