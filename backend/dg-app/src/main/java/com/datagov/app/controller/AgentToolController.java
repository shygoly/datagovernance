package com.datagov.app.controller;

import com.datagov.app.insight.AgentActionProposalService;
import com.datagov.app.insight.AgentActionProposalService.CreateProposalCommand;
import com.datagov.app.insight.AgentActionProposalService.ProposalView;
import com.datagov.app.insight.AgentControlPlaneService;
import com.datagov.app.insight.InsightQueryEngine;
import com.datagov.app.insight.InsightQueryService;
import com.datagov.app.insight.InsightQueryService.Answer;
import com.datagov.app.insight.InsightQueryService.AskRequest;
import com.datagov.app.insight.NativeInsightEngine;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.ConnectivityResult;
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
import com.datagov.metadata.service.SemanticOntologyService;
import com.datagov.metadata.service.SemanticOntologyService.ObjectView;
import com.datagov.metadata.service.SemanticOntologyService.SubgraphResult;
import com.datagov.metadata.service.SemanticOntologyService.SubgraphSearchRequest;
import com.datagov.metadata.service.SemanticQueryCompiler.CompiledSemanticQuery;
import com.datagov.metadata.service.SemanticQueryCompiler.SemanticQuerySpec;
import com.datagov.platform.service.PermissionService;
import com.datagov.platform.service.WorkspaceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Pi agent 服务的内部工具端点。
 *
 * <p>Node Agent 不持有平台 JWT，也不直接连数据库；它用独立服务令牌调用这里，
 * 由本控制器把 workspace/user 还原成 {@link WorkspaceContext}，再复用平台的
 * 问数、脱敏与审计链路。端点只监听本机（由部署层保证），并额外校验服务令牌。
 *
 * <p>本地/NFS 接入类工具需要写操作。它们不是“共享的 agent 超级账号”：
 * Node 会把对话发起人的 userId 带下来，这里每次都按 userId 重新解析权限，
 * 所以普通用户无法借 Agent 绕过数据源创建、目录采集的授权。
 */
@RestController
@RequestMapping("/api/v1/internal/agent")
@Tag(name = "Agent 内部工具", description = "仅供本地 Pi agent 服务调用")
public class AgentToolController {

    private final InsightQueryService queryService;
    private final DataSourceService dataSourceService;
    private final CatalogAssetService catalogAssetService;
    private final PermissionService permissionService;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final AgentActionProposalService proposalService;
    private final AgentControlPlaneService controlPlane;
    private final SemanticOntologyService semanticService;
    private final NativeInsightEngine nativeEngine;

    @Value("${dg.insight.agent-token:}")
    private String agentToken;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentToolController(InsightQueryService queryService,
                               DataSourceService dataSourceService,
                               CatalogAssetService catalogAssetService,
                               PermissionService permissionService,
                               WorkspaceService workspaceService,
                               AuditService auditService,
                               ObjectMapper objectMapper,
                               AgentActionProposalService proposalService,
                               AgentControlPlaneService controlPlane,
                               SemanticOntologyService semanticService,
                               NativeInsightEngine nativeEngine) {
        this.queryService = queryService;
        this.dataSourceService = dataSourceService;
        this.catalogAssetService = catalogAssetService;
        this.permissionService = permissionService;
        this.workspaceService = workspaceService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.proposalService = proposalService;
        this.controlPlane = controlPlane;
        this.semanticService = semanticService;
        this.nativeEngine = nativeEngine;
    }

    /** 向后兼容旧单测构造器。 */
    public AgentToolController(InsightQueryService queryService,
                               DataSourceService dataSourceService,
                               CatalogAssetService catalogAssetService,
                               PermissionService permissionService,
                               WorkspaceService workspaceService,
                               AuditService auditService,
                               ObjectMapper objectMapper) {
        this(queryService, dataSourceService, catalogAssetService, permissionService,
                workspaceService, auditService, objectMapper, null, null, null, null);
    }

    public record AskToolRequest(String workspaceId, String username, String question,
                                 Long dataSetId) {
    }

    public record LocalDataSourceRequest(String name, String rootPath, String description) {
    }

    public record UpdateLocalDataSourceRequest(String name, String rootPath, String description) {
    }

    public record ParquetCatalogRequest(String name, String dataSourceId, String rootPath) {
    }

    // ── 问数工具 ────────────────────────────────────────────────────────

    @GetMapping("/datasets")
    @Operation(summary = "Agent 工具：可用语义主题")
    public ApiResponse<List<InsightQueryEngine.DatasetOption>> datasets(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestHeader(value = "X-Agent-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Agent-Username", required = false) String headerUsername,
            @RequestHeader(value = "X-Agent-Workspace-Id", required = false) String headerWorkspaceId,
            @RequestHeader(value = "X-Agent-Admin", defaultValue = "false") boolean platformAdmin,
            @RequestParam(required = false) String workspaceId,
            @RequestParam(required = false) String username) {
        String effectiveWorkspaceId = resolveWorkspaceId(headerWorkspaceId, workspaceId);
        String effectiveUsername = !blank(headerUsername) ? headerUsername : username;
        return ApiResponse.ok(withCallerAny(token, userId, effectiveUsername, effectiveWorkspaceId,
                platformAdmin, Set.of("insight:ask", "insight:read"), queryService::datasets));
    }

    @PostMapping("/semantic/subgraph")
    @Operation(summary = "Agent 工具：受控 k-Hop 逐跳鉴权语义子图检索（C5）")
    public ApiResponse<SubgraphResult> searchSemanticSubgraph(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestHeader(value = "X-Agent-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Agent-Username", required = false) String username,
            @RequestHeader(value = "X-Agent-Workspace-Id", required = false) String workspaceId,
            @RequestHeader(value = "X-Agent-Admin", defaultValue = "false") boolean platformAdmin,
            @RequestBody SubgraphSearchRequest request) {
        if (semanticService == null) {
            throw new BizException(ErrorCode.GOV_INSIGHT_NOT_CONFIGURED, "语义本体服务未装配");
        }
        return ApiResponse.ok(withCallerAny(token, userId, username, workspaceId, platformAdmin,
                Set.of("insight:ask", "metadata:semantic:read"),
                () -> semanticService.searchSubgraph(request)));
    }

    @GetMapping("/semantic/objects/{id}")
    @Operation(summary = "Agent 工具：查看单个语义对象详情（只读）")
    public ApiResponse<ObjectView> getSemanticObject(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestHeader(value = "X-Agent-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Agent-Username", required = false) String username,
            @RequestHeader(value = "X-Agent-Workspace-Id", required = false) String workspaceId,
            @RequestHeader(value = "X-Agent-Admin", defaultValue = "false") boolean platformAdmin,
            @PathVariable String id) {
        if (semanticService == null) {
            throw new BizException(ErrorCode.GOV_INSIGHT_NOT_CONFIGURED, "语义本体服务未装配");
        }
        return ApiResponse.ok(withCallerAny(token, userId, username, workspaceId, platformAdmin,
                Set.of("insight:ask", "metadata:semantic:read"),
                () -> semanticService.getObject(id)));
    }

    @PostMapping("/semantic/compile")
    @Operation(summary = "Agent 工具：确定性编译 SemanticQuerySpec（只读预览，不执行）")
    public ApiResponse<CompiledSemanticQuery> compileSemantic(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestHeader(value = "X-Agent-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Agent-Username", required = false) String username,
            @RequestHeader(value = "X-Agent-Workspace-Id", required = false) String workspaceId,
            @RequestHeader(value = "X-Agent-Admin", defaultValue = "false") boolean platformAdmin,
            @RequestBody SemanticQuerySpec spec) {
        if (nativeEngine == null) {
            throw new BizException(ErrorCode.GOV_INSIGHT_NOT_CONFIGURED, "原生语义编译引擎未装配");
        }
        return ApiResponse.ok(withCaller(token, userId, username, workspaceId, platformAdmin,
                "insight:ask", () -> nativeEngine.compilePreview(spec)));
    }

    @PostMapping("/semantic/query")
    @Operation(summary = "Agent 工具：按结构化 SemanticQuerySpec 执行确定性语义问数")
    public ApiResponse<Answer> executeSemanticQuery(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestHeader(value = "X-Agent-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Agent-Username", required = false) String username,
            @RequestHeader(value = "X-Agent-Workspace-Id", required = false) String workspaceId,
            @RequestHeader(value = "X-Agent-Admin", defaultValue = "false") boolean platformAdmin,
            @RequestBody SemanticQuerySpec spec) {
        return ApiResponse.ok(withCaller(token, userId, username, workspaceId, platformAdmin,
                "insight:ask", () -> queryService.ask(new AskRequest("结构化语义查询", null, null, spec))));
    }

    @PostMapping("/ask")
    @Operation(summary = "Agent 工具：自然语言数据查询")
    public ApiResponse<Answer> ask(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestHeader(value = "X-Agent-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Agent-Username", required = false) String headerUsername,
            @RequestHeader(value = "X-Agent-Workspace-Id", required = false) String headerWorkspaceId,
            @RequestHeader(value = "X-Agent-Admin", defaultValue = "false") boolean platformAdmin,
            @RequestBody AskToolRequest request) {
        if (request == null || blank(request.question())) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "question 必填");
        }
        String effectiveWorkspaceId = resolveWorkspaceId(headerWorkspaceId, request.workspaceId());
        String effectiveUsername = !blank(headerUsername) ? headerUsername : request.username();
        return ApiResponse.ok(withCaller(token, userId, effectiveUsername, effectiveWorkspaceId,
                platformAdmin, "insight:ask",
                () -> queryService.ask(new AskRequest(request.question(),
                        request.dataSetId(), null))));
    }

    // ── 写操作宿主确认提案端点（Phase B / b2，闭合 ASI09）──────────────────

    @PostMapping("/proposals")
    @Operation(summary = "Agent 工具：生成待宿主确认的写操作提案（不直接落库执行）")
    public ApiResponse<ProposalView> createProposal(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestHeader(value = "X-Agent-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Agent-Username", required = false) String username,
            @RequestHeader(value = "X-Agent-Workspace-Id", required = false) String workspaceId,
            @RequestHeader(value = "X-Agent-Admin", defaultValue = "false") boolean platformAdmin,
            @RequestBody CreateProposalCommand request) {
        if (proposalService == null) {
            throw new BizException(ErrorCode.GOV_INSIGHT_NOT_CONFIGURED, "提案服务未装配");
        }
        return withCallerAny(token, userId, username, workspaceId, platformAdmin,
                Set.of("insight:ask", "metadata:datasource:create", "metadata:datasource:update",
                        "metadata:asset:manage", "metadata:asset:refresh"),
                () -> ApiResponse.ok(proposalService.createProposal(request)));
    }

    // ── 本地/NFS Parquet 接入工具 ───────────────────────────────────────

    @PostMapping("/onboarding/local-datasource")
    @Operation(summary = "Agent 工具：创建本地/NFS 数据源")
    public ApiResponse<DataSourceView> createLocalDataSource(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestHeader(value = "X-Agent-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Agent-Username", required = false) String username,
            @RequestHeader(value = "X-Agent-Workspace-Id", required = false) String workspaceId,
            @RequestHeader(value = "X-Agent-Admin", defaultValue = "false") boolean platformAdmin,
            @RequestBody LocalDataSourceRequest request) {
        if (request == null || blank(request.name()) || blank(request.rootPath())) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "name / rootPath 必填");
        }
        String rootPath = requireSafeRootPath(request.rootPath());
        return withCaller(token, userId, username, workspaceId, platformAdmin,
                "metadata:datasource:create",
                () -> {
                    DataSourceView view = dataSourceService.create(localCommand(
                            request.name().trim(), rootPath, request.description(), null));
                    audit(AuditService.CREATE, "DATASOURCE",
                            view == null ? null : view.id(),
                            view == null ? request.name() : view.name(),
                            "Agent 创建本地/NFS 数据源");
                    return ApiResponse.ok(view);
                });
    }

    @PutMapping("/onboarding/local-datasource/{id}")
    @Operation(summary = "Agent 工具：修改本地/NFS 数据源")
    public ApiResponse<DataSourceView> updateLocalDataSource(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestHeader(value = "X-Agent-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Agent-Username", required = false) String username,
            @RequestHeader(value = "X-Agent-Workspace-Id", required = false) String workspaceId,
            @RequestHeader(value = "X-Agent-Admin", defaultValue = "false") boolean platformAdmin,
            @PathVariable String id,
            @RequestBody UpdateLocalDataSourceRequest request) {
        return withCaller(token, userId, username, workspaceId, platformAdmin,
                "metadata:datasource:update", () -> {
                    DataSourceView existing = dataSourceService.get(id);
                    if (existing.type() != DataSourceType.LOCAL) {
                        throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                                "该数据源不是本地/NFS 类型，不能按本地目录修改");
                    }
                    String name = blank(request == null ? null : request.name())
                            ? existing.name() : request.name().trim();
                    String rootPath = blank(request == null ? null : request.rootPath())
                            ? existing.databaseName() : requireSafeRootPath(request.rootPath());
                    String description = request == null || request.description() == null
                            ? existing.description() : request.description();
                    DataSourceView view = dataSourceService.update(id,
                            localCommand(name, rootPath, description, existing.credentialId()));
                    audit(AuditService.UPDATE, "DATASOURCE", id, name,
                            "Agent 修改本地/NFS 数据源");
                    return ApiResponse.ok(view);
                });
    }

    @PostMapping("/onboarding/local-datasource/{id}/test")
    @Operation(summary = "Agent 工具：测试本地/NFS 数据源")
    public ApiResponse<ConnectivityResult> testLocalDataSource(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestHeader(value = "X-Agent-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Agent-Username", required = false) String username,
            @RequestHeader(value = "X-Agent-Workspace-Id", required = false) String workspaceId,
            @RequestHeader(value = "X-Agent-Admin", defaultValue = "false") boolean platformAdmin,
            @PathVariable String id) {
        return withCaller(token, userId, username, workspaceId, platformAdmin,
                "metadata:datasource:test",
                () -> {
                    ConnectivityResult result = dataSourceService.testConnection(id, false);
                    audit(AuditService.EXECUTE, "DATASOURCE", id, null,
                            "Agent 测试数据源连接");
                    return ApiResponse.ok(result);
                });
    }

    @GetMapping("/onboarding/catalog-sources")
    @Operation(summary = "Agent 工具：目录来源列表")
    public ApiResponse<List<SourceView>> catalogSources(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestHeader(value = "X-Agent-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Agent-Username", required = false) String username,
            @RequestHeader(value = "X-Agent-Workspace-Id", required = false) String workspaceId,
            @RequestHeader(value = "X-Agent-Admin", defaultValue = "false") boolean platformAdmin) {
        return withCaller(token, userId, username, workspaceId, platformAdmin,
                "metadata:asset:read",
                () -> ApiResponse.ok(catalogAssetService.listSources()));
    }

    @PostMapping("/onboarding/parquet-catalog")
    @Operation(summary = "Agent 工具：创建 Parquet 目录来源")
    public ApiResponse<SourceView> createParquetCatalog(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestHeader(value = "X-Agent-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Agent-Username", required = false) String username,
            @RequestHeader(value = "X-Agent-Workspace-Id", required = false) String workspaceId,
            @RequestHeader(value = "X-Agent-Admin", defaultValue = "false") boolean platformAdmin,
            @RequestBody ParquetCatalogRequest request) {
        if (request == null || blank(request.name()) || blank(request.dataSourceId())) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "name / dataSourceId 必填");
        }
        String configJson = null;
        if (!blank(request.rootPath())) {
            String rootPath = requireSafeRootPath(request.rootPath());
            try {
                configJson = objectMapper.writeValueAsString(Map.of("rootPath", rootPath));
            } catch (JsonProcessingException e) {
                throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                        "rootPath 配置无法序列化", e.getMessage(), e);
            }
        }
        String finalConfigJson = configJson;
        return withCaller(token, userId, username, workspaceId, platformAdmin,
                "metadata:asset:manage",
                () -> {
                    SourceView view = catalogAssetService.createSource(
                            new SourceRequest(request.name().trim(), "PARQUET_DIRECTORY",
                                    request.dataSourceId().trim(), finalConfigJson, null));
                    audit(AuditService.CREATE, "CATALOG_SOURCE",
                            view == null ? null : view.id(),
                            view == null ? request.name() : view.name(),
                            "Agent 创建 Parquet 目录来源");
                    return ApiResponse.ok(view);
                });
    }

    @PostMapping("/onboarding/parquet-catalog/{id}/refresh")
    @Operation(summary = "Agent 工具：采集 Parquet 目录")
    public ApiResponse<RefreshResult> refreshParquetCatalog(
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestHeader(value = "X-Agent-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Agent-Username", required = false) String username,
            @RequestHeader(value = "X-Agent-Workspace-Id", required = false) String workspaceId,
            @RequestHeader(value = "X-Agent-Admin", defaultValue = "false") boolean platformAdmin,
            @PathVariable String id) {
        return withCaller(token, userId, username, workspaceId, platformAdmin,
                "metadata:asset:refresh",
                () -> {
                    RefreshResult result = catalogAssetService.refresh(id);
                    audit(AuditService.EXECUTE, "CATALOG_SOURCE", id, null,
                            "Agent 采集 Parquet 目录");
                    return ApiResponse.ok(result);
                });
    }

    // ── 身份与上下文 ────────────────────────────────────────────────────

    private void requireToken(String token) {
        if (agentToken == null || agentToken.isBlank()) {
            throw new BizException(ErrorCode.GOV_INSIGHT_NOT_CONFIGURED,
                    "未配置 dg.insight.agent-token，Agent 工具端点不可用");
        }
        if (token == null || !agentToken.equals(token)) {
            throw new BizException(ErrorCode.PLT_FORBIDDEN, "Agent 服务令牌无效");
        }
    }

    /**
     * 所有 Agent 工具端点均按真实用户实时重算成员资格与权限：Node 传来的 userId 只作为
     * “是谁发起的对话”，不作为“他有什么权限”的证据。严禁构造合成服务账号 Caller（CONSTITUTION C4）。
     */
    private <T> T withCaller(String token, String userId, String username, String workspaceId,
                             boolean platformAdmin, String requiredPermission,
                             Supplier<T> action) {
        return withCallerAny(token, userId, username, workspaceId, platformAdmin,
                Set.of(requiredPermission), action);
    }

    private <T> T withCallerAny(String token, String userId, String username, String workspaceId,
                                boolean platformAdmin, Set<String> acceptedPermissions,
                                Supplier<T> action) {
        requireToken(token);
        if (blank(userId) || blank(username) || blank(workspaceId)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "Agent 工具缺少用户上下文（userId/username/workspaceId）");
        }
        if (controlPlane != null) {
            controlPlane.requireAgentEnabled(workspaceId);
        }
        workspaceService.requireAccess(userId, workspaceId);
        Set<String> permissions = permissionService.resolvePermissions(userId, workspaceId);
        Caller caller = new Caller(userId, username, workspaceId, platformAdmin, permissions);
        boolean allowed = acceptedPermissions.stream().anyMatch(caller::hasPermission);
        if (!allowed) {
            throw new BizException(ErrorCode.PLT_FORBIDDEN,
                    "缺少权限: " + String.join(" 或 ", acceptedPermissions));
        }
        return withCaller(caller, action);
    }

    private static String resolveWorkspaceId(String headerWorkspaceId, String payloadWorkspaceId) {
        if (!blank(headerWorkspaceId) && !blank(payloadWorkspaceId)
                && !headerWorkspaceId.trim().equals(payloadWorkspaceId.trim())) {
            throw new BizException(ErrorCode.PLT_FORBIDDEN, "请求头与载荷的 workspaceId 不一致");
        }
        return !blank(headerWorkspaceId) ? headerWorkspaceId.trim()
                : (payloadWorkspaceId == null ? null : payloadWorkspaceId.trim());
    }

    private <T> T withCaller(Caller caller, Supplier<T> action) {
        Caller previous = WorkspaceContext.get();
        WorkspaceContext.set(caller);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                WorkspaceContext.clear();
            } else {
                WorkspaceContext.set(previous);
            }
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private static DataSourceUpsertCommand localCommand(String name, String rootPath,
                                                        String description, String credentialId) {
        return new DataSourceUpsertCommand(
                name, DataSourceType.LOCAL, description, null,
                null, null, rootPath, null, Map.of(), List.of(),
                null, null, credentialId, 10000, 30000, null);
    }

    /**
     * Agent 可以创建本地数据源，但不应该被一句话带去扫描整个文件系统。
     * 这里只做最低限度的边界：绝对路径、非根目录。真正的目录白名单属于部署策略。
     */
    private String requireSafeRootPath(String rootPath) {
        if (controlPlane != null) {
            return controlPlane.requireSafeRootPath(rootPath);
        }
        String value = rootPath == null ? "" : rootPath.trim();
        if (!value.startsWith("/") || value.equals("/") || value.contains("..")) {
            throw new BizException(ErrorCode.MTD_CONFIG_INVALID,
                    "rootPath 必须是后端机器上的绝对目录，且不能是根目录 / 或包含 ..");
        }
        return value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 内部端点不在 AuditInterceptor 的路径映射里，而且控制器返回前就恢复了
     * WorkspaceContext，所以这里显式记一笔。AuditService 自己会吞掉写失败。
     */
    private void audit(String action, String resourceType, String resourceId,
                       String resourceName, String summary) {
        auditService.record(new AuditEntry(
                action, resourceType, resourceId, resourceName, "METADATA",
                true, null, summary, "agent"));
    }
}
