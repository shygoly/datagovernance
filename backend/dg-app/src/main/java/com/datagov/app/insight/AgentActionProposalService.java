package com.datagov.app.insight;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.app.insight.entity.AgentActionProposal;
import com.datagov.app.insight.mapper.AgentActionProposalMapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.DataSourceType;
import com.datagov.governance.service.AuditService;
import com.datagov.governance.service.AuditService.AuditEntry;
import com.datagov.metadata.dto.DataSourceUpsertCommand;
import com.datagov.metadata.dto.DataSourceView;
import com.datagov.metadata.service.CatalogAssetService;
import com.datagov.metadata.service.CatalogAssetService.RefreshResult;
import com.datagov.metadata.service.CatalogAssetService.SourceRequest;
import com.datagov.metadata.service.CatalogAssetService.SourceView;
import com.datagov.metadata.service.DataSourceService;
import com.datagov.platform.service.PermissionService;
import com.datagov.platform.service.WorkspaceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 写操作宿主独立确认服务（Phase B / b2，闭合 OWASP ASI09）。
 *
 * <p>核心不变量：
 * <ol>
 *   <li>Agent 写工具仅能创建 {@code PENDING} 提案，绝不直接修改平台数据；</li>
 *   <li>确认执行必须由原发起人携真实平台 JWT 在宿主确认 UI 发起；</li>
 *   <li>执行瞬间二次核验（Recheck at Execution）：核验发起人一致、未过期、载荷 SHA-256 摘要一致、
 *       实时空间成员资格与目标权限仍然有效，随后才执行真实写动作并落不可变审计。</li>
 * </ol>
 */
@Service
public class AgentActionProposalService {

    public static final String ACTION_CREATE_LOCAL_DATASOURCE = "CREATE_LOCAL_DATASOURCE";
    public static final String ACTION_UPDATE_LOCAL_DATASOURCE = "UPDATE_LOCAL_DATASOURCE";
    public static final String ACTION_CREATE_PARQUET_CATALOG = "CREATE_PARQUET_CATALOG";
    public static final String ACTION_REFRESH_PARQUET_CATALOG = "REFRESH_PARQUET_CATALOG";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_EXECUTED = "EXECUTED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    private final AgentActionProposalMapper mapper;
    private final AgentControlPlaneService controlPlane;
    private final DataSourceService dataSourceService;
    private final CatalogAssetService catalogAssetService;
    private final WorkspaceService workspaceService;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AgentActionProposalService(AgentActionProposalMapper mapper,
                                      AgentControlPlaneService controlPlane,
                                      DataSourceService dataSourceService,
                                      CatalogAssetService catalogAssetService,
                                      WorkspaceService workspaceService,
                                      PermissionService permissionService,
                                      AuditService auditService,
                                      ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.controlPlane = controlPlane;
        this.dataSourceService = dataSourceService;
        this.catalogAssetService = catalogAssetService;
        this.workspaceService = workspaceService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public record CreateProposalCommand(
            String conversationId,
            String actionType,
            Map<String, Object> payload
    ) {
    }

    public record ProposalView(
            String id,
            String workspaceId,
            String userId,
            String username,
            String conversationId,
            String actionType,
            String requiredPermission,
            String title,
            String summary,
            Map<String, Object> payload,
            String payloadDigest,
            String riskLevel,
            String status,
            Map<String, Object> result,
            String errorMessage,
            Instant expiresAt,
            String decidedBy,
            Instant decidedAt,
            String decisionNote,
            Instant createdAt
    ) {
    }

    public ProposalView createProposal(CreateProposalCommand cmd) {
        Caller caller = WorkspaceContext.require();
        controlPlane.requireAgentEnabled(caller.workspaceId());
        if (cmd == null || blank(cmd.actionType()) || cmd.payload() == null) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "actionType 与 payload 必填");
        }
        String actionType = cmd.actionType().trim().toUpperCase();
        ActionSpec spec = validateAndNormalize(actionType, cmd.payload());
        if (!caller.hasPermission(spec.requiredPermission())) {
            throw new BizException(ErrorCode.PLT_FORBIDDEN, "缺少权限: " + spec.requiredPermission());
        }

        String canonicalJson = toCanonicalJson(spec.normalizedPayload());
        String digest = sha256Hex(actionType + ":" + canonicalJson);
        Instant now = Instant.now();

        AgentActionProposal entity = new AgentActionProposal();
        entity.setId(Ids.of("agpr"));
        entity.setWorkspaceId(caller.workspaceId());
        entity.setUserId(caller.userId());
        entity.setUsername(caller.username());
        entity.setConversationId(cmd.conversationId());
        entity.setActionType(actionType);
        entity.setRequiredPerm(spec.requiredPermission());
        entity.setTitle(spec.title());
        entity.setSummary(spec.summary());
        entity.setPayloadJson(canonicalJson);
        entity.setPayloadDigest(digest);
        entity.setRiskLevel(spec.riskLevel());
        entity.setStatus(STATUS_PENDING);
        entity.setExpiresAt(now.plus(DEFAULT_TTL));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);

        auditService.record(new AuditEntry(
                AuditService.CREATE, "AGENT_PROPOSAL", entity.getId(), spec.title(), "GOVERNANCE",
                true, null, "生成待确认提案：" + spec.summary(), "agent"));

        return toView(entity);
    }

    public List<ProposalView> listMyProposals(String conversationId, String status) {
        Caller caller = WorkspaceContext.require();
        LambdaQueryWrapper<AgentActionProposal> query = new LambdaQueryWrapper<AgentActionProposal>()
                .eq(AgentActionProposal::getWorkspaceId, caller.workspaceId())
                .eq(AgentActionProposal::getUserId, caller.userId())
                .orderByDesc(AgentActionProposal::getCreatedAt)
                .last("LIMIT 50");
        if (!blank(conversationId)) {
            query.eq(AgentActionProposal::getConversationId, conversationId.trim());
        }
        if (!blank(status)) {
            query.eq(AgentActionProposal::getStatus, status.trim().toUpperCase());
        }
        Instant now = Instant.now();
        return mapper.selectList(query).stream()
                .map(p -> expireIfNeeded(p, now))
                .map(this::toView)
                .toList();
    }

    /**
     * 宿主确认执行（Recheck at Execution）：仅允许原发起人在有效期内确认，且执行前重算权限与摘要。
     */
    public ProposalView confirmProposal(String proposalId, String expectedDigest, String note) {
        Caller caller = WorkspaceContext.require();
        controlPlane.requireAgentEnabled(caller.workspaceId());

        AgentActionProposal proposal = loadOwnedProposal(proposalId, caller);
        Instant now = Instant.now();
        proposal = expireIfNeeded(proposal, now);
        if (!STATUS_PENDING.equals(proposal.getStatus())) {
            throw new BizException(ErrorCode.GOV_PROPOSAL_STATE,
                    "提案当前状态为 " + proposal.getStatus() + "，不可重复确认或执行");
        }
        if (!blank(expectedDigest) && !expectedDigest.trim().equals(proposal.getPayloadDigest())) {
            throw new BizException(ErrorCode.SYS_CONFLICT, "提案载荷摘要不匹配，拒绝执行");
        }

        // 执行瞬间二次校验成员资格与权限
        workspaceService.requireAccess(caller.userId(), caller.workspaceId());
        Set<String> livePermissions = permissionService.resolvePermissions(caller.userId(), caller.workspaceId());
        Caller liveCaller = new Caller(caller.userId(), caller.username(), caller.workspaceId(),
                caller.platformAdmin(), livePermissions);
        if (!liveCaller.hasPermission(proposal.getRequiredPerm())) {
            throw new BizException(ErrorCode.PLT_FORBIDDEN,
                    "确认瞬间权限复核失败，缺少权限: " + proposal.getRequiredPerm());
        }

        Map<String, Object> payload = parseJsonMap(proposal.getPayloadJson());
        try {
            Map<String, Object> execResult = executeAction(proposal.getActionType(), payload);
            proposal.setStatus(STATUS_EXECUTED);
            proposal.setResultJson(toCanonicalJson(execResult));
            proposal.setDecidedBy(caller.userId());
            proposal.setDecidedAt(now);
            proposal.setDecisionNote(note);
            proposal.setUpdatedAt(now);
            mapper.updateById(proposal);

            auditService.record(new AuditEntry(
                    AuditService.EXECUTE, "AGENT_PROPOSAL", proposal.getId(), proposal.getTitle(), "GOVERNANCE",
                    true, null, "宿主确认并执行提案：" + proposal.getSummary(), "host-consent"));
            return toView(proposal);
        } catch (BizException ex) {
            markFailed(proposal, caller.userId(), now, note, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            markFailed(proposal, caller.userId(), now, note, ex.getMessage());
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "执行提案失败: " + ex.getMessage());
        }
    }

    public ProposalView rejectProposal(String proposalId, String note) {
        Caller caller = WorkspaceContext.require();
        AgentActionProposal proposal = loadOwnedProposal(proposalId, caller);
        Instant now = Instant.now();
        proposal = expireIfNeeded(proposal, now);
        if (!STATUS_PENDING.equals(proposal.getStatus())) {
            throw new BizException(ErrorCode.GOV_PROPOSAL_STATE,
                    "提案当前状态为 " + proposal.getStatus() + "，无法拒绝");
        }
        proposal.setStatus(STATUS_REJECTED);
        proposal.setDecidedBy(caller.userId());
        proposal.setDecidedAt(now);
        proposal.setDecisionNote(blank(note) ? "用户在宿主确认界面拒绝" : note.trim());
        proposal.setUpdatedAt(now);
        mapper.updateById(proposal);

        auditService.record(new AuditEntry(
                AuditService.UPDATE, "AGENT_PROPOSAL", proposal.getId(), proposal.getTitle(), "GOVERNANCE",
                true, null, "用户拒绝执行提案：" + proposal.getDecisionNote(), "host-consent"));
        return toView(proposal);
    }

    private Map<String, Object> executeAction(String actionType, Map<String, Object> payload) {
        return switch (actionType) {
            case ACTION_CREATE_LOCAL_DATASOURCE -> {
                String name = str(payload.get("name"));
                String rootPath = controlPlane.requireSafeRootPath(str(payload.get("rootPath")));
                String desc = str(payload.get("description"));
                DataSourceView view = dataSourceService.create(new DataSourceUpsertCommand(
                        name, DataSourceType.LOCAL, desc, null, null, null, rootPath,
                        null, Map.of(), List.of(), null, null, null, 10000, 30000, null));
                Map<String, Object> out = new LinkedHashMap<>();
                if (view != null) {
                    out.put("id", view.id());
                    out.put("name", view.name());
                    out.put("type", view.type() == null ? "LOCAL" : view.type().name());
                    out.put("status", view.status());
                    out.put("rootPath", view.databaseName());
                }
                yield out;
            }
            case ACTION_UPDATE_LOCAL_DATASOURCE -> {
                String id = str(payload.get("dataSourceId"));
                DataSourceView current = dataSourceService.get(id);
                String name = blank(str(payload.get("name"))) ? current.name() : str(payload.get("name"));
                String rootPath = blank(str(payload.get("rootPath")))
                        ? current.databaseName()
                        : controlPlane.requireSafeRootPath(str(payload.get("rootPath")));
                String desc = payload.get("description") == null
                        ? current.description() : str(payload.get("description"));
                DataSourceView view = dataSourceService.update(id, new DataSourceUpsertCommand(
                        name, DataSourceType.LOCAL, desc, null, null, null, rootPath,
                        null, Map.of(), List.of(), null, null, current.credentialId(),
                        10000, 30000, null));
                Map<String, Object> out = new LinkedHashMap<>();
                if (view != null) {
                    out.put("id", view.id());
                    out.put("name", view.name());
                    out.put("status", view.status());
                    out.put("rootPath", view.databaseName());
                }
                yield out;
            }
            case ACTION_CREATE_PARQUET_CATALOG -> {
                String name = str(payload.get("name"));
                String dataSourceId = str(payload.get("dataSourceId"));
                String rootPath = str(payload.get("rootPath"));
                Map<String, Object> config = new LinkedHashMap<>();
                if (!blank(rootPath)) {
                    config.put("rootPath", rootPath);
                }
                SourceView view = catalogAssetService.createSource(new SourceRequest(
                        name, "PARQUET_DIRECTORY", dataSourceId, toCanonicalJson(config),
                        "由 Agent 提案并经宿主确认创建"));
                Map<String, Object> out = new LinkedHashMap<>();
                if (view != null) {
                    out.put("id", view.id());
                    out.put("name", view.name());
                    out.put("sourceType", view.sourceType());
                    out.put("dataSourceId", view.dataSourceId());
                }
                yield out;
            }
            case ACTION_REFRESH_PARQUET_CATALOG -> {
                String sourceId = str(payload.get("sourceId"));
                RefreshResult result = catalogAssetService.refresh(sourceId);
                Map<String, Object> out = new LinkedHashMap<>();
                if (result != null) {
                    out.put("runId", result.runId());
                    out.put("status", result.status());
                    out.put("namespaces", result.namespaces());
                    out.put("datasets", result.datasets());
                    out.put("columns", result.columns());
                    out.put("durationMillis", result.durationMillis());
                }
                yield out;
            }
            default -> throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "不支持的提案动作类型: " + actionType);
        };
    }

    private record ActionSpec(
            String requiredPermission,
            String title,
            String summary,
            String riskLevel,
            Map<String, Object> normalizedPayload
    ) {
    }

    private ActionSpec validateAndNormalize(String actionType, Map<String, Object> raw) {
        return switch (actionType) {
            case ACTION_CREATE_LOCAL_DATASOURCE -> {
                String name = requireNonBlank(str(raw.get("name")), "name 必填");
                String rootPath = controlPlane.requireSafeRootPath(str(raw.get("rootPath")));
                Map<String, Object> norm = new LinkedHashMap<>();
                norm.put("name", name);
                norm.put("rootPath", rootPath);
                if (!blank(str(raw.get("description")))) {
                    norm.put("description", str(raw.get("description")));
                }
                yield new ActionSpec(
                        "metadata:datasource:create",
                        "创建本地/NFS Parquet 数据源",
                        "新建 LOCAL 数据源「" + name + "」，指向后端目录 " + rootPath,
                        "MEDIUM",
                        norm);
            }
            case ACTION_UPDATE_LOCAL_DATASOURCE -> {
                String id = requireNonBlank(str(raw.get("dataSourceId")), "dataSourceId 必填");
                Map<String, Object> norm = new LinkedHashMap<>();
                norm.put("dataSourceId", id);
                if (!blank(str(raw.get("name")))) {
                    norm.put("name", str(raw.get("name")));
                }
                if (!blank(str(raw.get("rootPath")))) {
                    norm.put("rootPath", controlPlane.requireSafeRootPath(str(raw.get("rootPath"))));
                }
                if (raw.get("description") != null) {
                    norm.put("description", str(raw.get("description")));
                }
                yield new ActionSpec(
                        "metadata:datasource:update",
                        "修改本地/NFS 数据源配置",
                        "修改数据源 " + id + " 配置",
                        "MEDIUM",
                        norm);
            }
            case ACTION_CREATE_PARQUET_CATALOG -> {
                String name = requireNonBlank(str(raw.get("name")), "name 必填");
                String dsId = requireNonBlank(str(raw.get("dataSourceId")), "dataSourceId 必填");
                Map<String, Object> norm = new LinkedHashMap<>();
                norm.put("name", name);
                norm.put("dataSourceId", dsId);
                if (!blank(str(raw.get("rootPath")))) {
                    norm.put("rootPath", str(raw.get("rootPath")));
                }
                yield new ActionSpec(
                        "metadata:asset:manage",
                        "创建 Parquet 元数据目录来源",
                        "为数据源 " + dsId + " 创建 PARQUET_DIRECTORY 目录来源「" + name + "」",
                        "LOW",
                        norm);
            }
            case ACTION_REFRESH_PARQUET_CATALOG -> {
                String sourceId = requireNonBlank(str(raw.get("sourceId")), "sourceId 必填");
                Map<String, Object> norm = new LinkedHashMap<>();
                norm.put("sourceId", sourceId);
                yield new ActionSpec(
                        "metadata:asset:refresh",
                        "采集 Parquet 目录元数据",
                        "扫描目录来源 " + sourceId + " 并写入平台元数据（只读元数据，不复制数据）",
                        "LOW",
                        norm);
            }
            default -> throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "不支持的提案动作类型: " + actionType);
        };
    }

    private AgentActionProposal loadOwnedProposal(String proposalId, Caller caller) {
        AgentActionProposal proposal = mapper.selectById(proposalId);
        if (proposal == null || !caller.workspaceId().equals(proposal.getWorkspaceId())) {
            throw new BizException(ErrorCode.GOV_PROPOSAL_NOT_FOUND, "操作提案不存在");
        }
        if (!caller.userId().equals(proposal.getUserId())) {
            throw new BizException(ErrorCode.PLT_FORBIDDEN, "只能由原发起人确认或拒绝自己的操作提案");
        }
        return proposal;
    }

    private AgentActionProposal expireIfNeeded(AgentActionProposal proposal, Instant now) {
        if (STATUS_PENDING.equals(proposal.getStatus())
                && proposal.getExpiresAt() != null
                && proposal.getExpiresAt().isBefore(now)) {
            proposal.setStatus(STATUS_EXPIRED);
            proposal.setUpdatedAt(now);
            mapper.updateById(proposal);
        }
        return proposal;
    }

    private void markFailed(AgentActionProposal proposal, String userId, Instant now,
                            String note, String error) {
        proposal.setStatus(STATUS_FAILED);
        proposal.setDecidedBy(userId);
        proposal.setDecidedAt(now);
        proposal.setDecisionNote(note);
        proposal.setErrorMessage(error != null && error.length() > 500 ? error.substring(0, 500) : error);
        proposal.setUpdatedAt(now);
        mapper.updateById(proposal);
        auditService.record(new AuditEntry(
                AuditService.EXECUTE, "AGENT_PROPOSAL", proposal.getId(), proposal.getTitle(), "GOVERNANCE",
                false, ErrorCode.SYS_INTERNAL_ERROR.code(),
                "宿主确认执行失败：" + proposal.getErrorMessage(), "host-consent"));
    }

    private ProposalView toView(AgentActionProposal e) {
        return new ProposalView(
                e.getId(),
                e.getWorkspaceId(),
                e.getUserId(),
                e.getUsername(),
                e.getConversationId(),
                e.getActionType(),
                e.getRequiredPerm(),
                e.getTitle(),
                e.getSummary(),
                parseJsonMap(e.getPayloadJson()),
                e.getPayloadDigest(),
                e.getRiskLevel(),
                e.getStatus(),
                e.getResultJson() == null ? null : parseJsonMap(e.getResultJson()),
                e.getErrorMessage(),
                e.getExpiresAt(),
                e.getDecidedBy(),
                e.getDecidedAt(),
                e.getDecisionNote(),
                e.getCreatedAt()
        );
    }

    private String toCanonicalJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "序列化提案载荷失败");
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (blank(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static String requireNonBlank(String value, String message) {
        if (blank(value)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, message);
        }
        return value.trim();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
