package com.datagov.app.autonomy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.app.insight.entity.AgentActionProposal;
import com.datagov.app.insight.mapper.AgentActionProposalMapper;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.Caller;
import com.datagov.governance.service.AuditService;
import com.datagov.governance.service.AuditService.AuditEntry;
import com.datagov.runtime.autonomy.FlowStep;
import com.datagov.runtime.autonomy.FlowStepContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 L2/L3 自治动作写入 V32 的宿主确认提案表（P1-9 / C7）。
 *
 * <p>只创建提案，不执行动作；确认必须由具备 {@code autonomy:flow:approve}
 * 的真实用户在宿主端点完成，随后才向 DurableFlowEngine 发送 APPROVED 信号。
 */
@Component
public class AutonomyApprovalGateway {

    public static final String ACTION_TYPE = "AUTONOMY_FLOW_APPROVAL";
    public static final String REQUIRED_PERMISSION = "autonomy:flow:approve";

    private final AgentActionProposalMapper proposalMapper;
    private final AuditService auditService;
    private final ObjectMapper json;

    public AutonomyApprovalGateway(AgentActionProposalMapper proposalMapper,
                                   AuditService auditService,
                                   ObjectMapper json) {
        this.proposalMapper = proposalMapper;
        this.auditService = auditService;
        this.json = json;
    }

    public String createOrReuse(FlowStepContext ctx, String actionTypeRef, String reason) {
        String existing = findExisting(ctx.workspaceId(), ctx.instanceId(), ctx.step().id());
        if (existing != null) {
            return existing;
        }
        Caller initiator = ctx.initiator();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", ctx.instanceId());
        payload.put("flowId", ctx.plan().flowId());
        payload.put("flowCode", ctx.plan().flowCode());
        payload.put("flowVersion", ctx.plan().flowVersion());
        payload.put("stepId", ctx.step().id());
        payload.put("actionTypeRef", actionTypeRef);
        payload.put("runMode", ctx.runMode());
        payload.put("reason", reason);
        String canonical = write(payload);
        String digest = sha256(ACTION_TYPE + ":" + canonical);
        Instant now = Instant.now();
        int timeout = ctx.step().timeoutSec() == null || ctx.step().timeoutSec() <= 0
                ? 14_400 : ctx.step().timeoutSec();

        AgentActionProposal proposal = new AgentActionProposal();
        proposal.setId(Ids.of("agpr"));
        proposal.setWorkspaceId(ctx.workspaceId());
        proposal.setUserId(initiator == null ? "system" : initiator.userId());
        proposal.setUsername(initiator == null ? "system" : initiator.username());
        proposal.setActionType(ACTION_TYPE);
        proposal.setRequiredPerm(REQUIRED_PERMISSION);
        proposal.setTitle("自治流程审批：" + ctx.plan().flowCode());
        proposal.setSummary("流程 " + ctx.plan().flowCode() + " 请求执行 " + actionTypeRef
                + "；" + (reason == null ? "" : reason));
        proposal.setPayloadJson(canonical);
        proposal.setPayloadDigest(digest);
        proposal.setRiskLevel("HIGH");
        proposal.setStatus(AgentActionProposalStatus.PENDING);
        proposal.setExpiresAt(now.plusSeconds(timeout));
        proposal.setCreatedAt(now);
        proposal.setUpdatedAt(now);
        proposalMapper.insert(proposal);

        auditService.record(new AuditEntry(
                AuditService.CREATE, "AUTONOMY_APPROVAL", proposal.getId(), proposal.getTitle(),
                "GOVERNANCE", true, null,
                "创建 L2 自治动作审批提案：" + proposal.getSummary(), "autonomy-flow"));
        return proposal.getId();
    }

    private String findExisting(String workspaceId, String instanceId, String stepId) {
        List<AgentActionProposal> pending = proposalMapper.selectList(
                new LambdaQueryWrapper<AgentActionProposal>()
                        .eq(AgentActionProposal::getWorkspaceId, workspaceId)
                        .eq(AgentActionProposal::getActionType, ACTION_TYPE)
                        .eq(AgentActionProposal::getStatus, AgentActionProposalStatus.PENDING)
                        .orderByDesc(AgentActionProposal::getCreatedAt)
                        .last("LIMIT 50"));
        for (AgentActionProposal proposal : pending) {
            Map<String, Object> data = readMap(proposal.getPayloadJson());
            if (instanceId.equals(str(data.get("instanceId")))
                    && stepId.equals(str(data.get("stepId")))) {
                return proposal.getId();
            }
        }
        return null;
    }

    public static final class AgentActionProposalStatus {
        public static final String PENDING = "PENDING";

        private AgentActionProposalStatus() {
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化自治审批载荷失败", ex);
        }
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }
}
