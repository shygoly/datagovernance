package com.datagov.governance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.common.api.PageResult;
import com.datagov.common.audit.ReproducibilityFingerprint;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.entity.AutonomyTraceEntities.DecisionTrace;
import com.datagov.governance.mapper.AuDecisionTraceMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DecisionTrace 证据链服务（P1-10 / C8）。
 *
 * <p>这不是普通日志：它把“为什么在当时做了这个动作”拆成可机器复核的槽位。
 * 证据不足时 verify_status 明确写 INCONCLUSIVE，而不是留空或猜测。
 */
@Service
public class DecisionTraceService {

    public static final String PENDING = "PENDING";
    public static final String IMPROVED = "IMPROVED";
    public static final String NO_EFFECT = "NO_EFFECT";
    public static final String DEGRADED = "DEGRADED";
    public static final String INCONCLUSIVE = "INCONCLUSIVE";

    private final AuDecisionTraceMapper mapper;
    private final ObjectMapper json;

    public DecisionTraceService(AuDecisionTraceMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public record StartCommand(
            String workspaceId,
            String flowId,
            String triggerId,
            String runMode,
            Map<String, Object> triggerSnapshot,
            String planDigest,
            String actorId
    ) {
    }

    public record TraceView(
            String id,
            String traceId,
            String instanceId,
            String flowId,
            String triggerId,
            String runMode,
            String verifyStatus,
            Map<String, Object> triggerSnapshot,
            Map<String, Object> arbiterDecision,
            Map<String, Object> dryRunResult,
            String proposalId,
            Map<String, Object> activityResult,
            Map<String, Object> verifyResult,
            Map<String, Object> reproducibilityFingerprint,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    @Transactional
    public String start(StartCommand cmd) {
        if (cmd == null || cmd.workspaceId() == null || cmd.workspaceId().isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "workspaceId 必填");
        }
        String traceId = Ids.of("atrc");
        Instant now = Instant.now();
        ReproducibilityFingerprint fp = new ReproducibilityFingerprint(
                1,
                List.of(Map.of("workspaceId", cmd.workspaceId())),
                List.of(Map.of("flowId", nullToEmpty(cmd.flowId()),
                        "planDigest", nullToEmpty(cmd.planDigest()))),
                List.of(),
                List.of(Map.of("operatorId", "FlowCompiler", "version", "1",
                        "digest", nullToEmpty(cmd.planDigest()))),
                nullToEmpty(cmd.planDigest()),
                "arbiter_pending",
                List.of(Map.of("principalType", "USER", "id", nullToEmpty(cmd.actorId()))),
                Map.of("truncated", false, "maxSteps", 0),
                now.toString());
        DecisionTrace trace = new DecisionTrace();
        trace.setId(Ids.of("atr"));
        trace.setWorkspaceId(cmd.workspaceId());
        trace.setTraceId(traceId);
        trace.setFlowId(cmd.flowId());
        trace.setTriggerId(cmd.triggerId());
        trace.setRunMode(cmd.runMode() == null ? "SHADOW" : cmd.runMode());
        trace.setVerifyStatus(PENDING);
        trace.setTriggerSnapshotJson(write(cmd.triggerSnapshot() == null ? Map.of() : cmd.triggerSnapshot()));
        trace.setArbiterDecisionJson("{}");
        trace.setDryRunResultJson("{}");
        trace.setActivityResultJson("{}");
        trace.setVerifyResultJson("{}");
        trace.setReproducibilityFingerprint(write(fp));
        trace.setCreatedAt(now);
        trace.setUpdatedAt(now);
        mapper.insert(trace);
        return traceId;
    }

    @Transactional
    public void bindInstance(String workspaceId, String traceId, String instanceId) {
        DecisionTrace trace = require(workspaceId, traceId);
        trace.setInstanceId(instanceId);
        trace.setUpdatedAt(Instant.now());
        mapper.updateById(trace);
    }

    @Transactional
    public void updateArbiter(String workspaceId, String traceId, Map<String, Object> decision) {
        update(workspaceId, traceId, trace -> trace.setArbiterDecisionJson(write(decision)));
    }

    @Transactional
    public void updateDryRun(String workspaceId, String traceId, Map<String, Object> result) {
        update(workspaceId, traceId, trace -> trace.setDryRunResultJson(write(result)));
    }

    @Transactional
    public void updateProposal(String workspaceId, String traceId, String proposalId) {
        update(workspaceId, traceId, trace -> trace.setProposalId(proposalId));
    }

    @Transactional
    public void updateActivity(String workspaceId, String traceId, Map<String, Object> result) {
        update(workspaceId, traceId, trace -> trace.setActivityResultJson(write(result)));
    }

    @Transactional
    public void updateVerify(String workspaceId, String traceId, String status,
                             Map<String, Object> result) {
        update(workspaceId, traceId, trace -> {
            trace.setVerifyStatus(status == null ? INCONCLUSIVE : status);
            trace.setVerifyResultJson(write(result == null ? Map.of() : result));
        });
    }

    public TraceView getByTrace(String workspaceId, String traceId) {
        return toView(require(workspaceId, traceId));
    }

    public TraceView getByInstance(String workspaceId, String instanceId) {
        DecisionTrace trace = mapper.selectOne(new LambdaQueryWrapper<DecisionTrace>()
                .eq(DecisionTrace::getWorkspaceId, workspaceId)
                .eq(DecisionTrace::getInstanceId, instanceId)
                .orderByDesc(DecisionTrace::getCreatedAt)
                .last("LIMIT 1"));
        if (trace == null) {
            throw new BizException(ErrorCode.GOV_TRACE_NOT_FOUND, "决策轨迹不存在: " + instanceId);
        }
        return toView(trace);
    }

    public PageResult<TraceView> list(long page, long size, String verifyStatus,
                                      String runMode, String flowId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        LambdaQueryWrapper<DecisionTrace> q = new LambdaQueryWrapper<DecisionTrace>()
                .eq(DecisionTrace::getWorkspaceId, ws);
        if (verifyStatus != null && !verifyStatus.isBlank()) {
            q.eq(DecisionTrace::getVerifyStatus, verifyStatus.trim().toUpperCase(Locale.ROOT));
        }
        if (runMode != null && !runMode.isBlank()) {
            q.eq(DecisionTrace::getRunMode, runMode.trim().toUpperCase(Locale.ROOT));
        }
        if (flowId != null && !flowId.isBlank()) {
            q.eq(DecisionTrace::getFlowId, flowId.trim());
        }
        q.orderByDesc(DecisionTrace::getCreatedAt);
        Page<DecisionTrace> p = mapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(200, Math.max(1, size))), q);
        return PageResult.of(p.getRecords().stream().map(this::toView).toList(),
                p.getTotal(), p.getCurrent(), p.getSize());
    }

    public List<TraceView> recentForFlow(String workspaceId, String flowId, String runMode, int limit) {
        return mapper.selectList(new LambdaQueryWrapper<DecisionTrace>()
                        .eq(DecisionTrace::getWorkspaceId, workspaceId)
                        .eq(DecisionTrace::getFlowId, flowId)
                        .eq(runMode != null, DecisionTrace::getRunMode, runMode)
                        .orderByDesc(DecisionTrace::getCreatedAt)
                        .last("LIMIT " + Math.max(1, Math.min(200, limit))))
                .stream().map(this::toView).toList();
    }

    public long countVerified(String workspaceId, String flowId, String runMode, List<String> statuses) {
        return mapper.selectCount(new LambdaQueryWrapper<DecisionTrace>()
                .eq(DecisionTrace::getWorkspaceId, workspaceId)
                .eq(DecisionTrace::getFlowId, flowId)
                .eq(DecisionTrace::getRunMode, runMode)
                .in(DecisionTrace::getVerifyStatus, statuses));
    }

    private void update(String workspaceId, String traceId, java.util.function.Consumer<DecisionTrace> mutator) {
        DecisionTrace trace = require(workspaceId, traceId);
        mutator.accept(trace);
        trace.setUpdatedAt(Instant.now());
        mapper.updateById(trace);
    }

    private DecisionTrace require(String workspaceId, String traceId) {
        Caller caller = WorkspaceContext.get();
        if (caller != null && !caller.platformAdmin()
                && caller.workspaceId() != null && !caller.workspaceId().equals(workspaceId)) {
            throw new BizException(ErrorCode.GOV_TRACE_NOT_FOUND, "决策轨迹不存在: " + traceId);
        }
        DecisionTrace trace = mapper.selectOne(new LambdaQueryWrapper<DecisionTrace>()
                .eq(DecisionTrace::getWorkspaceId, workspaceId)
                .eq(DecisionTrace::getTraceId, traceId)
                .last("LIMIT 1"));
        if (trace == null) {
            throw new BizException(ErrorCode.GOV_TRACE_NOT_FOUND, "决策轨迹不存在: " + traceId);
        }
        return trace;
    }

    private TraceView toView(DecisionTrace e) {
        return new TraceView(e.getId(), e.getTraceId(), e.getInstanceId(), e.getFlowId(),
                e.getTriggerId(), e.getRunMode(), e.getVerifyStatus(),
                readMap(e.getTriggerSnapshotJson()), readMap(e.getArbiterDecisionJson()),
                readMap(e.getDryRunResultJson()), e.getProposalId(),
                readMap(e.getActivityResultJson()), readMap(e.getVerifyResultJson()),
                readMap(e.getReproducibilityFingerprint()), e.getCreatedAt(), e.getUpdatedAt());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "序列化决策证据失败");
        }
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static String nullToEmpty(String raw) {
        return raw == null ? "" : raw;
    }
}
