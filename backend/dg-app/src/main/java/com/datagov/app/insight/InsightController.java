package com.datagov.app.insight;

import com.datagov.app.insight.InsightQueryEngine.DatasetOption;
import com.datagov.app.insight.InsightQueryService.Answer;
import com.datagov.app.insight.InsightQueryService.AskRequest;
import com.datagov.app.insight.InsightQueryService.QueryLogView;
import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.metadata.service.SemanticQueryCompiler.CompiledSemanticQuery;
import com.datagov.metadata.service.SemanticQueryCompiler.SemanticQuerySpec;
import com.datagov.metadata.service.gate.CompileAndValidateResult;
import com.datagov.metadata.service.gate.CompileReject;
import com.datagov.metadata.service.gate.CompileRejectException;
import com.datagov.metadata.service.gate.SemanticCompileErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 智能问数网关（原生语义引擎 · OpenSpec: semantic-ontology-kernel）。
 *
 * <p>前端与 Agent 统一消费本网关，完全解耦并替代外部 SuperSonic 服务；
 * 所有问数均基于 {@code dg-metadata} 语义本体内核确定性编译、脱敏与审计。
 */
@RestController
@RequestMapping("/api/v1/insight")
@Tag(name = "智能问数", description = "基于单一语义真理源的受治理自然语言与结构化问数网关")
public class InsightController {

    private final InsightQueryService queryService;
    private final NativeInsightEngine nativeEngine;
    private final InsightProperties properties;

    public InsightController(InsightQueryService queryService,
                             NativeInsightEngine nativeEngine,
                             InsightProperties properties) {
        this.queryService = queryService;
        this.nativeEngine = nativeEngine;
        this.properties = properties;
    }

    @PostMapping("/ask")
    @RequirePermission("insight:ask")
    @Operation(summary = "自然语言或结构化语义问数",
            description = "经原生语义内核确定性编译执行，按平台敏感标签脱敏并附可复现审计指纹")
    public ApiResponse<Answer> ask(@RequestBody AskRequest request) {
        return ApiResponse.ok(queryService.ask(request));
    }

    @PostMapping("/semantic/compile")
    @RequirePermission("insight:ask")
    @Operation(summary = "确定性语义 SQL 编译预览",
            description = "输入结构化 SemanticQuerySpec，返回确定性只读 SQL、防扇出标记与 SHA-256 审计指纹")
    public ApiResponse<CompiledSemanticQuery> compileSemantic(@RequestBody SemanticQuerySpec spec) {
        return ApiResponse.ok(nativeEngine.compilePreview(spec));
    }

    @PostMapping("/semantic/compile-and-validate")
    @RequirePermission("insight:ask")
    @Operation(summary = "SMQ 干跑校验",
            description = "成功 ok=true + compiled；失败 ok=false + 类型化 CompileReject（error_code/allowed/repair_hint）")
    public ApiResponse<CompileAndValidateResult> compileAndValidate(@RequestBody SemanticQuerySpec spec) {
        try {
            return ApiResponse.ok(CompileAndValidateResult.success(nativeEngine.compilePreview(spec)));
        } catch (CompileRejectException ex) {
            return ApiResponse.ok(CompileAndValidateResult.failure(ex.reject()));
        } catch (BizException ex) {
            return ApiResponse.ok(CompileAndValidateResult.failure(CompileReject.of(
                    SemanticCompileErrorCode.EMPTY_QUERY,
                    ex.getMessage(),
                    "$",
                    java.util.List.of(),
                    "根据 message 修正 SMQ 后重试 compile-and-validate")));
        }
    }

    @PostMapping("/export")
    @RequirePermission("insight:export")
    @Operation(summary = "导出问数结果 CSV")
    public ResponseEntity<byte[]> export(@RequestBody AskRequest request) {
        var file = queryService.export(request);
        String encodedName = java.net.URLEncoder.encode(file.filename(), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.filename()
                                + "\"; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(file.content());
    }

    @PostMapping("/export/xlsx")
    @RequirePermission("insight:export")
    @Operation(summary = "导出问数结果 XLSX")
    public ResponseEntity<byte[]> exportXlsx(@RequestBody AskRequest request) {
        var file = queryService.exportXlsx(request);
        String encodedName = java.net.URLEncoder.encode(file.filename(), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.filename()
                                + "\"; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file.content());
    }

    @GetMapping("/datasets")
    @RequirePermission("insight:ask")
    @Operation(summary = "可选语义主题列表", description = "由 dg-metadata 已发布语义本体自动聚合生成")
    public ApiResponse<List<DatasetOption>> datasets() {
        return ApiResponse.ok(queryService.datasets());
    }

    @GetMapping("/queries")
    @RequirePermission("insight:read")
    @Operation(summary = "问数审计记录")
    public ApiResponse<PageResult<QueryLogView>> queries(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean succeeded,
            @RequestParam(required = false) String username) {
        return ApiResponse.ok(queryService.history(page, size, keyword, succeeded, username));
    }

    @GetMapping("/config")
    @RequirePermission("insight:ask")
    @Operation(summary = "问数能力探测")
    public ApiResponse<Map<String, Object>> config() {
        return ApiResponse.ok(Map.of(
                "enabled", true,
                "engine", properties.getEngine(),
                "dialect", properties.getDialect(),
                "masking", true,
                "maxRows", properties.getMaxRows(),
                "defaultDatasetId", properties.getDefaultDatasetId(),
                "agentId", properties.getAgentId(),
                "message", "问数请求由平台原生语义本体内核确定性编译执行"));
    }
}
