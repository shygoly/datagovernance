package com.datagov.app.insight;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.app.insight.entity.AgentControlPolicy;
import com.datagov.app.insight.mapper.AgentControlPolicyMapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.service.AuditService;
import com.datagov.governance.service.AuditService.AuditEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 智能体控制面服务（Phase B / b3，闭合 OWASP ASI08 / ASI10）。
 *
 * <p>负责：
 * <ol>
 *   <li>全局与租户级 Kill Switch 急停校验及在途会话中止通知；</li>
 *   <li>租户级每日轮次配额熔断；</li>
 *   <li>单轮最大工具调用深度与连续错误上限下发；</li>
 *   <li>本地数据源 rootPath 允许目录前缀白名单校验。</li>
 * </ol>
 */
@Service
public class AgentControlPlaneService {

    private static final Logger log = LoggerFactory.getLogger(AgentControlPlaneService.class);

    public static final int DEFAULT_MAX_TOOL_CALLS = 12;
    public static final int DEFAULT_MAX_CONSECUTIVE_ERRORS = 2;
    public static final int DEFAULT_DAILY_TURN_QUOTA = 200;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final AgentControlPolicyMapper policyMapper;
    private final AuditService auditService;

    /** key = workspaceId + ":" + YYYY-MM-DD */
    private final ConcurrentHashMap<String, AtomicInteger> dailyTurnCounters = new ConcurrentHashMap<>();

    @Value("${dg.insight.agent-base-url:http://127.0.0.1:8090}")
    private String agentBaseUrl = "http://127.0.0.1:8090";

    @Value("${dg.insight.agent-token:}")
    private String agentToken = "";

    @Value("${dg.insight.local-root-allowlist:}")
    private String localRootAllowlist = "";

    public AgentControlPlaneService(AgentControlPolicyMapper policyMapper,
                                    AuditService auditService) {
        this.policyMapper = policyMapper;
        this.auditService = auditService;
    }

    public record EffectivePolicy(
            String workspaceId,
            boolean killSwitchEnabled,
            String killSwitchReason,
            int maxToolCallsPerTurn,
            int maxConsecutiveErrors,
            int dailyTurnQuota,
            int usedTurnsToday,
            List<String> localRootAllowlist
    ) {
    }

    public record PolicyUpdateCommand(
            Boolean killSwitchEnabled,
            String killSwitchReason,
            Integer maxToolCallsPerTurn,
            Integer maxConsecutiveErrors,
            Integer dailyTurnQuota
    ) {
    }

    public EffectivePolicy resolveEffectivePolicy(String workspaceId) {
        AgentControlPolicy global = findByWorkspace(AgentControlPolicy.GLOBAL_WORKSPACE);
        AgentControlPolicy ws = (workspaceId == null || workspaceId.isBlank()
                || AgentControlPolicy.GLOBAL_WORKSPACE.equals(workspaceId))
                ? null : findByWorkspace(workspaceId);

        boolean globalKill = global != null && Boolean.TRUE.equals(global.getKillSwitchEnabled());
        boolean wsKill = ws != null && Boolean.TRUE.equals(ws.getKillSwitchEnabled());
        boolean kill = globalKill || wsKill;
        String reason = globalKill
                ? nonBlankOr(global.getKillSwitchReason(), "平台管理员已开启全局智能体急停开关")
                : (wsKill ? nonBlankOr(ws.getKillSwitchReason(), "当前空间已开启智能体急停开关") : null);

        int maxTools = positiveOr(ws != null ? ws.getMaxToolCallsPerTurn() : null,
                positiveOr(global != null ? global.getMaxToolCallsPerTurn() : null, DEFAULT_MAX_TOOL_CALLS));
        int maxErrs = positiveOr(ws != null ? ws.getMaxConsecutiveErrors() : null,
                positiveOr(global != null ? global.getMaxConsecutiveErrors() : null, DEFAULT_MAX_CONSECUTIVE_ERRORS));
        int quota = positiveOr(ws != null ? ws.getDailyTurnQuota() : null,
                positiveOr(global != null ? global.getDailyTurnQuota() : null, DEFAULT_DAILY_TURN_QUOTA));

        int used = workspaceId == null ? 0 : currentUsage(workspaceId);
        return new EffectivePolicy(
                workspaceId,
                kill,
                reason,
                maxTools,
                maxErrs,
                quota,
                used,
                parsedAllowlist()
        );
    }

    public EffectivePolicy requireAgentEnabled(String workspaceId) {
        EffectivePolicy policy = resolveEffectivePolicy(workspaceId);
        if (policy.killSwitchEnabled()) {
            throw new BizException(ErrorCode.GOV_AGENT_DISABLED,
                    policy.killSwitchReason() != null ? policy.killSwitchReason() : "智能体已被控制面急停");
        }
        return policy;
    }

    public EffectivePolicy requireAndConsumeTurn(String workspaceId) {
        EffectivePolicy policy = requireAgentEnabled(workspaceId);
        String key = counterKey(workspaceId);
        int used = dailyTurnCounters.computeIfAbsent(key, ignored -> new AtomicInteger(0)).incrementAndGet();
        if (used > policy.dailyTurnQuota()) {
            dailyTurnCounters.get(key).decrementAndGet();
            auditService.record(new AuditEntry(
                    "CIRCUIT_BREAKER", "AGENT_QUOTA", workspaceId, workspaceId, "GOVERNANCE",
                    false, ErrorCode.GOV_AGENT_QUOTA_EXCEEDED.code(),
                    "触发每日智能体轮次配额熔断（上限 " + policy.dailyTurnQuota() + "）", "agent-control"));
            throw new BizException(ErrorCode.GOV_AGENT_QUOTA_EXCEEDED,
                    "当前空间今日智能体对话轮次已达上限（" + policy.dailyTurnQuota() + "）");
        }
        return new EffectivePolicy(
                policy.workspaceId(),
                policy.killSwitchEnabled(),
                policy.killSwitchReason(),
                policy.maxToolCallsPerTurn(),
                policy.maxConsecutiveErrors(),
                policy.dailyTurnQuota(),
                used,
                policy.localRootAllowlist()
        );
    }

    public EffectivePolicy updatePolicy(String targetWorkspaceId, PolicyUpdateCommand cmd) {
        Caller caller = WorkspaceContext.require();
        String scope = (targetWorkspaceId == null || targetWorkspaceId.isBlank())
                ? caller.workspaceId() : targetWorkspaceId.trim();
        if (AgentControlPolicy.GLOBAL_WORKSPACE.equals(scope) && !caller.platformAdmin()) {
            throw new BizException(ErrorCode.PLT_FORBIDDEN, "仅平台管理员可修改全局智能体控制面策略");
        }
        if (cmd == null) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "策略内容必填");
        }
        if (cmd.maxToolCallsPerTurn() != null && (cmd.maxToolCallsPerTurn() < 1 || cmd.maxToolCallsPerTurn() > 50)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "maxToolCallsPerTurn 必须在 1–50 之间");
        }
        if (cmd.maxConsecutiveErrors() != null && (cmd.maxConsecutiveErrors() < 1 || cmd.maxConsecutiveErrors() > 10)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "maxConsecutiveErrors 必须在 1–10 之间");
        }
        if (cmd.dailyTurnQuota() != null && (cmd.dailyTurnQuota() < 1 || cmd.dailyTurnQuota() > 100_000)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "dailyTurnQuota 必须在 1–100000 之间");
        }

        AgentControlPolicy existing = findByWorkspace(scope);
        boolean insert = existing == null;
        AgentControlPolicy entity = insert ? new AgentControlPolicy() : existing;
        if (insert) {
            entity.setId(Ids.of("agp"));
            entity.setWorkspaceId(scope);
            entity.setKillSwitchEnabled(false);
            entity.setMaxToolCallsPerTurn(DEFAULT_MAX_TOOL_CALLS);
            entity.setMaxConsecutiveErrors(DEFAULT_MAX_CONSECUTIVE_ERRORS);
            entity.setDailyTurnQuota(DEFAULT_DAILY_TURN_QUOTA);
        }
        if (cmd.killSwitchEnabled() != null) {
            entity.setKillSwitchEnabled(cmd.killSwitchEnabled());
        }
        if (cmd.killSwitchReason() != null) {
            entity.setKillSwitchReason(cmd.killSwitchReason().trim());
        }
        if (cmd.maxToolCallsPerTurn() != null) {
            entity.setMaxToolCallsPerTurn(cmd.maxToolCallsPerTurn());
        }
        if (cmd.maxConsecutiveErrors() != null) {
            entity.setMaxConsecutiveErrors(cmd.maxConsecutiveErrors());
        }
        if (cmd.dailyTurnQuota() != null) {
            entity.setDailyTurnQuota(cmd.dailyTurnQuota());
        }
        entity.setUpdatedBy(caller.userId());
        entity.setUpdatedAt(Instant.now());

        if (insert) {
            policyMapper.insert(entity);
        } else {
            policyMapper.updateById(entity);
        }

        auditService.record(new AuditEntry(
                AuditService.UPDATE, "AGENT_CONTROL_POLICY", entity.getId(), scope, "GOVERNANCE",
                true, null,
                "更新智能体控制面策略：killSwitch=" + entity.getKillSwitchEnabled()
                        + ", maxTools=" + entity.getMaxToolCallsPerTurn()
                        + ", quota=" + entity.getDailyTurnQuota(),
                "agent-control"));

        if (Boolean.TRUE.equals(entity.getKillSwitchEnabled())) {
            notifyAbortActiveRuns(AgentControlPolicy.GLOBAL_WORKSPACE.equals(scope) ? null : scope);
        }
        return resolveEffectivePolicy(AgentControlPolicy.GLOBAL_WORKSPACE.equals(scope)
                ? caller.workspaceId() : scope);
    }

    public EffectivePolicy setKillSwitch(String targetWorkspaceId, boolean enabled, String reason) {
        return updatePolicy(targetWorkspaceId, new PolicyUpdateCommand(
                enabled,
                reason == null ? (enabled ? "管理员手动触发急停" : "") : reason,
                null,
                null,
                null
        ));
    }

    /**
     * 校验本地/NFS 数据源路径：必须是规范绝对路径、非根目录、无路径穿越，且满足允许前缀白名单。
     */
    public String requireSafeRootPath(String rootPath) {
        String raw = rootPath == null ? "" : rootPath.trim();
        if (!raw.startsWith("/") || raw.equals("/")) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "rootPath 必须是后端机器上的绝对目录，且不能是根目录 /");
        }
        if (raw.contains("..")) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "rootPath 不允许包含路径穿越片段 ..");
        }
        Path normalized = Path.of(raw).normalize();
        String normStr = normalized.toString();
        if (normStr.equals("/")) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "rootPath 规范化后不能是根目录 /");
        }
        List<String> allowlist = parsedAllowlist();
        if (!allowlist.isEmpty()) {
            boolean matched = allowlist.stream().anyMatch(prefix -> {
                Path p = Path.of(prefix).normalize();
                return normalized.startsWith(p);
            });
            if (!matched) {
                throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                        "rootPath 不在允许的本地目录白名单内: " + String.join(", ", allowlist));
            }
        }
        return normStr;
    }

    public List<String> parsedAllowlist() {
        if (localRootAllowlist == null || localRootAllowlist.isBlank()) {
            return List.of();
        }
        return Arrays.stream(localRootAllowlist.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && s.startsWith("/"))
                .toList();
    }

    private AgentControlPolicy findByWorkspace(String workspaceId) {
        return policyMapper.selectOne(new LambdaQueryWrapper<AgentControlPolicy>()
                .eq(AgentControlPolicy::getWorkspaceId, workspaceId)
                .last("LIMIT 1"));
    }

    private int currentUsage(String workspaceId) {
        AtomicInteger counter = dailyTurnCounters.get(counterKey(workspaceId));
        return counter == null ? 0 : counter.get();
    }

    private static String counterKey(String workspaceId) {
        return workspaceId + ":" + LocalDate.now(ZoneOffset.UTC);
    }

    private static int positiveOr(Integer value, int fallback) {
        return (value != null && value > 0) ? value : fallback;
    }

    private static String nonBlankOr(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private void notifyAbortActiveRuns(String workspaceId) {
        if (agentBaseUrl == null || agentBaseUrl.isBlank()) {
            return;
        }
        try {
            String body = workspaceId == null
                    ? "{}"
                    : "{\"workspaceId\":\"" + workspaceId.replace("\"", "") + "\"}";
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(agentBaseUrl.replaceAll("/+$", "") + "/control/abort-all"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (agentToken != null && !agentToken.isBlank()) {
                builder.header("X-Agent-Token", agentToken);
            }
            HTTP.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception ex) {
            log.warn("通知 agent-service 中止在途会话失败（不影响控制面阻断生效）: {}", ex.getMessage());
        }
    }
}
