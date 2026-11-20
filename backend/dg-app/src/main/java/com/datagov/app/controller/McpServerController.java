package com.datagov.app.controller;

import com.datagov.app.insight.AgentActionProposalService;
import com.datagov.app.insight.AgentActionProposalService.CreateProposalCommand;
import com.datagov.app.insight.AgentControlPlaneService;
import com.datagov.app.insight.GenAiTelemetry;
import com.datagov.app.insight.InsightQueryService;
import com.datagov.app.insight.InsightQueryService.AskRequest;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.service.CatalogAssetService;
import com.datagov.metadata.service.SemanticOntologyService;
import com.datagov.metadata.service.SemanticOntologyService.SubgraphSearchRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 受治理的 MCP（Model Context Protocol 2026-07-28 无状态 JSON-RPC 2.0）服务端点（Phase C / c1）。
 *
 * <p>核心原则：
 * <ul>
 *   <li>完全复用平台 {@code AuthInterceptor} + {@link WorkspaceContext} 身份与权限体系，不建第二套安全轨道；</li>
 *   <li>只读能力经 {@link InsightQueryService} / {@link CatalogAssetService} 受控执行并自动脱敏；</li>
 *   <li>写能力（{@code propose_local_onboarding}）仅生成 {@code PENDING} 宿主确认提案，严禁直接落库（ASI09）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/mcp")
@Tag(name = "MCP 协议服务端点", description = "Model Context Protocol (2026-07-28) JSON-RPC 2.0 入口")
public class McpServerController {

    public static final String PROTOCOL_VERSION = "2026-07-28";

    private final InsightQueryService queryService;
    private final CatalogAssetService catalogAssetService;
    private final AgentActionProposalService proposalService;
    private final AgentControlPlaneService controlPlane;
    private final GenAiTelemetry telemetry;
    private final SemanticOntologyService semanticService;

    @org.springframework.beans.factory.annotation.Autowired
    public McpServerController(InsightQueryService queryService,
                               CatalogAssetService catalogAssetService,
                               AgentActionProposalService proposalService,
                               AgentControlPlaneService controlPlane,
                               GenAiTelemetry telemetry,
                               SemanticOntologyService semanticService) {
        this.queryService = queryService;
        this.catalogAssetService = catalogAssetService;
        this.proposalService = proposalService;
        this.controlPlane = controlPlane;
        this.telemetry = telemetry;
        this.semanticService = semanticService;
    }

    /** 兼容既有单元测试构造器。 */
    public McpServerController(InsightQueryService queryService,
                               CatalogAssetService catalogAssetService,
                               AgentActionProposalService proposalService,
                               AgentControlPlaneService controlPlane,
                               GenAiTelemetry telemetry) {
        this(queryService, catalogAssetService, proposalService, controlPlane, telemetry, null);
    }

    public record JsonRpcRequest(String jsonrpc, Object id, String method, Map<String, Object> params) {
    }

    @PostMapping
    @Operation(summary = "MCP JSON-RPC 2.0 调用入口（initialize / tools/list / tools/call / resources/list / resources/read）")
    public Map<String, Object> handle(@RequestBody JsonRpcRequest request) {
        Caller caller = WorkspaceContext.require();
        controlPlane.requireAgentEnabled(caller.workspaceId());
        Object id = request == null ? null : request.id();
        String method = request == null || request.method() == null ? "" : request.method().trim();
        Map<String, Object> params = request == null || request.params() == null
                ? Map.of() : request.params();

        long started = System.currentTimeMillis();
        try {
            Object result = switch (method) {
                case "initialize" -> handleInitialize();
                case "tools/list" -> handleToolsList();
                case "tools/call" -> handleToolsCall(caller, params);
                case "resources/list" -> handleResourcesList();
                case "resources/read" -> handleResourcesRead(caller, params);
                default -> throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                        "不支持的 MCP 方法: " + method);
            };
            telemetry.recordSpan("execute_tool", caller.workspaceId(), caller.userId(),
                    "mcp:" + method, System.currentTimeMillis() - started, true, null, null);
            return okRpc(id, result);
        } catch (BizException ex) {
            telemetry.recordSpan("execute_tool", caller.workspaceId(), caller.userId(),
                    "mcp:" + method, System.currentTimeMillis() - started, false, null, null);
            return errorRpc(id, -32000 - ex.errorCode().httpStatus(), ex.getMessage(), ex.errorCode().code());
        }
    }

    private Map<String, Object> handleInitialize() {
        return Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "serverInfo", Map.of(
                        "name", "datagovernance-mcp",
                        "version", "1.0.0"
                ),
                "capabilities", Map.of(
                        "tools", Map.of("listChanged", false),
                        "resources", Map.of("subscribe", false, "listChanged", false)
                )
        );
    }

    private Map<String, Object> handleToolsList() {
        return Map.of("tools", List.of(
                Map.of(
                        "name", "list_datasets",
                        "description", "列出当前 Workspace 可用的受治理语义数据集（只读）",
                        "inputSchema", Map.of("type", "object", "properties", Map.of())
                ),
                Map.of(
                        "name", "ask_data",
                        "description", "经平台 Query Gateway 发起自然语言问数（只读 + 自动脱敏 + 审计）",
                        "inputSchema", Map.of(
                                "type", "object",
                                "required", List.of("question"),
                                "properties", Map.of(
                                        "question", Map.of("type", "string"),
                                        "dataSetId", Map.of("type", "integer")
                                )
                        )
                ),
                Map.of(
                        "name", "search_semantic_subgraph",
                        "description", "检索当前 Workspace 已发布的语义对象与知识图谱 k-Hop 子图（逐跳 ACL 剪枝，只读）",
                        "inputSchema", Map.of(
                                "type", "object",
                                "required", List.of("query"),
                                "properties", Map.of(
                                        "query", Map.of("type", "string"),
                                        "maxHops", Map.of("type", "integer")
                                )
                        )
                ),
                Map.of(
                        "name", "propose_local_onboarding",
                        "description", "生成创建本地 Parquet 数据源的待宿主确认提案（不直接落库，防 ASI09）",
                        "inputSchema", Map.of(
                                "type", "object",
                                "required", List.of("name", "rootPath"),
                                "properties", Map.of(
                                        "name", Map.of("type", "string"),
                                        "rootPath", Map.of("type", "string"),
                                        "description", Map.of("type", "string")
                                )
                        )
                )
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleToolsCall(Caller caller, Map<String, Object> params) {
        String name = String.valueOf(params.getOrDefault("name", "")).trim();
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        return switch (name) {
            case "list_datasets" -> {
                requireAnyPerm(caller, "insight:ask", "insight:read");
                yield Map.of("content", List.of(Map.of("type", "json", "data", queryService.datasets())));
            }
            case "ask_data" -> {
                requireAnyPerm(caller, "insight:ask");
                String question = String.valueOf(args.getOrDefault("question", "")).trim();
                if (question.isEmpty()) {
                    throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "question 必填");
                }
                Long dataSetId = args.get("dataSetId") instanceof Number n ? n.longValue() : null;
                yield Map.of("content", List.of(Map.of(
                        "type", "json",
                        "data", queryService.ask(new AskRequest(question, dataSetId, null))
                )));
            }
            case "search_semantic_subgraph" -> {
                requireAnyPerm(caller, "insight:ask", "metadata:semantic:read");
                if (semanticService == null) {
                    throw new BizException(ErrorCode.GOV_INSIGHT_NOT_CONFIGURED, "语义本体服务未装配");
                }
                String query = String.valueOf(args.getOrDefault("query", "")).trim();
                Integer maxHops = args.get("maxHops") instanceof Number n ? n.intValue() : 2;
                yield Map.of("content", List.of(Map.of(
                        "type", "json",
                        "data", semanticService.searchSubgraph(new SubgraphSearchRequest(query, maxHops, 6, null, false))
                )));
            }
            case "propose_local_onboarding" -> {
                requireAnyPerm(caller, "metadata:datasource:create");
                var proposal = proposalService.createProposal(new CreateProposalCommand(
                        "mcp",
                        AgentActionProposalService.ACTION_CREATE_LOCAL_DATASOURCE,
                        args
                ));
                yield Map.of("content", List.of(Map.of(
                        "type", "json",
                        "pendingConsent", true,
                        "data", proposal
                )));
            }
            default -> throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "未知 MCP 工具: " + name);
        };
    }

    private Map<String, Object> handleResourcesList() {
        return Map.of("resources", List.of(
                Map.of(
                        "uri", "dg://catalog/sources",
                        "name", "Workspace Catalog Sources",
                        "description", "当前空间已注册的数据目录来源列表",
                        "mimeType", "application/json"
                ),
                Map.of(
                        "uri", "dg://semantic/objects",
                        "name", "Published Semantic Ontology Objects",
                        "description", "当前空间已发布的语义本体对象（ENTITY/ATTRIBUTE/DIMENSION/METRIC/CONCEPT）",
                        "mimeType", "application/json"
                )
        ));
    }

    private Map<String, Object> handleResourcesRead(Caller caller, Map<String, Object> params) {
        String uri = String.valueOf(params.getOrDefault("uri", "")).trim();
        if ("dg://catalog/sources".equals(uri)) {
            requireAnyPerm(caller, "metadata:asset:read", "metadata:catalog:read", "insight:ask");
            return Map.of("contents", List.of(Map.of(
                    "uri", uri,
                    "mimeType", "application/json",
                    "data", catalogAssetService.listSources()
            )));
        }
        if ("dg://semantic/objects".equals(uri)) {
            requireAnyPerm(caller, "metadata:semantic:read", "insight:ask");
            if (semanticService == null) {
                throw new BizException(ErrorCode.GOV_INSIGHT_NOT_CONFIGURED, "语义本体服务未装配");
            }
            return Map.of("contents", List.of(Map.of(
                    "uri", uri,
                    "mimeType", "application/json",
                    "data", semanticService.listObjects(1, 200, null, null, null, "PUBLISHED")
            )));
        }
        throw new BizException(ErrorCode.SYS_NOT_FOUND, "未知 MCP 资源 URI: " + uri);
    }

    private static void requireAnyPerm(Caller caller, String... perms) {
        for (String p : perms) {
            if (caller.hasPermission(p)) {
                return;
            }
        }
        throw new BizException(ErrorCode.PLT_FORBIDDEN, "缺少权限: " + String.join(" 或 ", perms));
    }

    private static Map<String, Object> okRpc(Object id, Object result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("result", result);
        return out;
    }

    private static Map<String, Object> errorRpc(Object id, int code, String message, String bizCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("error", Map.of(
                "code", code,
                "message", message == null ? "MCP 调用失败" : message,
                "data", Map.of("errorCode", bizCode)
        ));
        return out;
    }
}
