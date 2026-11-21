package com.datagov.app.controller;

import com.datagov.app.autonomy.AutonomyFlowService;
import com.datagov.app.autonomy.AutonomyFlowService.FireResult;
import com.datagov.app.insight.AgentActionProposalService;
import com.datagov.app.insight.AgentActionProposalService.ProposalView;
import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.api.PageResult;
import com.datagov.control.autonomy.FlowCompiler;
import com.datagov.control.autonomy.FlowDefinitionService;
import com.datagov.control.autonomy.FlowDefinitionService.FlowView;
import com.datagov.control.autonomy.FlowDefinitionService.SaveRequest;
import com.datagov.control.autonomy.FlowDefinitionService.VersionView;
import com.datagov.control.autonomy.FlowSpecParser;
import com.datagov.control.autonomy.TriggerDefinitionService;
import com.datagov.control.autonomy.TriggerDefinitionService.TriggerView;
import com.datagov.control.autonomy.TriggerDefinitionService.UpsertRequest;
import com.datagov.control.autonomy.TriggerEvaluationService;
import com.datagov.control.autonomy.TriggerEvaluationService.Evaluation;
import com.datagov.control.entity.AutonomyControlEntities.TriggerDefinition;
import com.datagov.governance.service.DecisionTraceService;
import com.datagov.governance.service.DecisionTraceService.TraceView;
import com.datagov.runtime.autonomy.DurableFlowEngine;
import com.datagov.runtime.autonomy.FlowInstanceView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 数据自治（Autonomy / Ops）控制与证据端点（data-driven-ops-profile P1-11）。
 *
 * <p>审批动作复用 V32 提案表与宿主确认模型，但使用独立的
 * {@code autonomy:flow:approve} 权限；没有第二条绕过 FlowSpec 的执行通道。
 */
@RestController
@RequestMapping("/api/v1/autonomy")
@Tag(name = "数据自治", description = "触发器、FlowSpec IR、灰度流程、决策轨迹与急停")
public class AutonomyController {

    private final TriggerDefinitionService triggerService;
    private final TriggerEvaluationService triggerEvaluationService;
    private final FlowDefinitionService flowService;
    private final FlowSpecParser flowSpecParser;
    private final FlowCompiler flowCompiler;
    private final AutonomyFlowService autonomyFlowService;
    private final DurableFlowEngine flowEngine;
    private final DecisionTraceService traceService;
    private final AgentActionProposalService proposalService;

    public AutonomyController(TriggerDefinitionService triggerService,
                              TriggerEvaluationService triggerEvaluationService,
                              FlowDefinitionService flowService,
                              FlowSpecParser flowSpecParser,
                              FlowCompiler flowCompiler,
                              AutonomyFlowService autonomyFlowService,
                              DurableFlowEngine flowEngine,
                              DecisionTraceService traceService,
                              AgentActionProposalService proposalService) {
        this.triggerService = triggerService;
        this.triggerEvaluationService = triggerEvaluationService;
        this.flowService = flowService;
        this.flowSpecParser = flowSpecParser;
        this.flowCompiler = flowCompiler;
        this.autonomyFlowService = autonomyFlowService;
        this.flowEngine = flowEngine;
        this.traceService = traceService;
        this.proposalService = proposalService;
    }

    public record PromoteRequest(String targetMode, String note) {
    }

    public record SignalRequest(String signal, Map<String, Object> payload) {
    }

    public record ConfirmRequest(String payloadDigest, String note) {
    }

    public record RejectRequest(String note) {
    }

    public record CompileRequest(Map<String, Object> spec) {
    }

    // ── 触发器 ─────────────────────────────────────────────────────────

    @GetMapping("/triggers")
    @RequirePermission("autonomy:trigger:read")
    @Operation(summary = "分页查询自治触发器")
    public ApiResponse<PageResult<TriggerView>> listTriggers(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) Boolean enabled) {
        return ApiResponse.ok(triggerService.list(page, size, keyword, sourceType, enabled));
    }

    @GetMapping("/triggers/{id}")
    @RequirePermission("autonomy:trigger:read")
    public ApiResponse<TriggerView> getTrigger(@PathVariable String id) {
        return ApiResponse.ok(triggerService.get(id));
    }

    @PostMapping("/triggers")
    @RequirePermission("autonomy:trigger:manage")
    public ApiResponse<TriggerView> createTrigger(@RequestBody UpsertRequest request) {
        return ApiResponse.ok(triggerService.create(request));
    }

    @PutMapping("/triggers/{id}")
    @RequirePermission("autonomy:trigger:manage")
    public ApiResponse<TriggerView> updateTrigger(@PathVariable String id,
                                                  @RequestBody UpsertRequest request) {
        return ApiResponse.ok(triggerService.update(id, request));
    }

    @DeleteMapping("/triggers/{id}")
    @RequirePermission("autonomy:trigger:manage")
    public ApiResponse<Void> deleteTrigger(@PathVariable String id) {
        triggerService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/triggers/{id}/test")
    @RequirePermission("autonomy:trigger:read")
    @Operation(summary = "只求值不执行：返回触发器在给定快照下是否命中")
    public ApiResponse<Evaluation> testTrigger(@PathVariable String id,
                                               @RequestBody(required = false) Map<String, Object> snapshot) {
        TriggerDefinition trigger = triggerService.require(
                com.datagov.common.tenant.WorkspaceContext.requireWorkspaceId(), id);
        return ApiResponse.ok(triggerEvaluationService.evaluate(trigger, snapshot, Instant.now()));
    }

    @PostMapping("/triggers/{id}/fire")
    @RequirePermission("autonomy:trigger:manage")
    @Operation(summary = "求值并启动自治流程（测试/手动补偿入口）")
    public ApiResponse<FireResult> fireTrigger(@PathVariable String id,
                                               @RequestBody(required = false) Map<String, Object> snapshot) {
        return ApiResponse.ok(autonomyFlowService.evaluateAndFire(id, snapshot));
    }

    // ── 流程 ───────────────────────────────────────────────────────────

    @GetMapping("/flows")
    @RequirePermission("autonomy:flow:read")
    public ApiResponse<PageResult<FlowView>> listFlows(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String runMode) {
        return ApiResponse.ok(flowService.list(page, size, keyword, status, runMode));
    }

    @GetMapping("/flows/{id}")
    @RequirePermission("autonomy:flow:read")
    public ApiResponse<FlowView> getFlow(@PathVariable String id) {
        return ApiResponse.ok(flowService.get(id));
    }

    @GetMapping("/flows/{id}/versions")
    @RequirePermission("autonomy:flow:read")
    public ApiResponse<List<VersionView>> listVersions(@PathVariable String id) {
        return ApiResponse.ok(flowService.versions(id));
    }

    @PostMapping("/flows/compile")
    @RequirePermission("autonomy:flow:manage")
    @Operation(summary = "静态编译 FlowSpec IR，返回诊断但不落库")
    public ApiResponse<FlowCompiler.FlowCompileResult> compile(@RequestBody CompileRequest request) {
        String ws = com.datagov.common.tenant.WorkspaceContext.requireWorkspaceId();
        var spec = flowSpecParser.parse(request == null ? null : request.spec());
        return ApiResponse.ok(flowCompiler.compile(ws, spec, "preview", 1, spec.runMode()));
    }

    @PostMapping("/flows")
    @RequirePermission("autonomy:flow:manage")
    public ApiResponse<FlowView> saveFlow(@RequestBody SaveRequest request) {
        return ApiResponse.ok(flowService.save(request));
    }

    @PostMapping("/flows/{id}/publish")
    @RequirePermission("autonomy:flow:publish")
    public ApiResponse<FlowView> publishFlow(@PathVariable String id) {
        return ApiResponse.ok(flowService.publish(id));
    }

    @PostMapping("/flows/{id}/deprecate")
    @RequirePermission("autonomy:flow:publish")
    public ApiResponse<FlowView> deprecateFlow(@PathVariable String id) {
        return ApiResponse.ok(flowService.deprecate(id));
    }

    @PostMapping("/flows/{id}/dry-run")
    @RequirePermission("autonomy:flow:manage")
    @Operation(summary = "手动 Dry-Run：强制以 SHADOW 启动实例，零副作用")
    public ApiResponse<FireResult> dryRun(@PathVariable String id,
                                          @RequestBody(required = false) Map<String, Object> snapshot) {
        return ApiResponse.ok(autonomyFlowService.dryRun(id, snapshot));
    }

    @PostMapping("/flows/{id}/promote")
    @RequirePermission("autonomy:flow:publish")
    @Operation(summary = "受控灰度晋升：SHADOW → L2_ASSISTED → L1_AUTO")
    public ApiResponse<FlowView> promote(@PathVariable String id,
                                         @RequestBody PromoteRequest request) {
        return ApiResponse.ok(autonomyFlowService.promote(
                id, request == null ? null : request.targetMode(),
                request == null ? null : request.note()));
    }

    @PostMapping("/flows/kill-switch")
    @RequirePermission("autonomy:flow:publish")
    @Operation(summary = "一键将所有流程降级回 SHADOW 并取消在途实例")
    public ApiResponse<Map<String, Object>> killSwitch(@RequestBody(required = false) PromoteRequest request) {
        return ApiResponse.ok(autonomyFlowService.killSwitch(request == null ? null : request.note()));
    }

    // ── 实例 ───────────────────────────────────────────────────────────

    @GetMapping("/instances/{id}")
    @RequirePermission("autonomy:flow:read")
    public ApiResponse<FlowInstanceView> getInstance(@PathVariable String id) {
        return ApiResponse.ok(flowEngine.inspect(id));
    }

    @PostMapping("/instances/{id}/signal")
    @RequirePermission("autonomy:flow:approve")
    @Operation(summary = "发送审批/回检信号；回检信号需携带 metrics")
    public ApiResponse<FlowInstanceView> signal(@PathVariable String id,
                                                @RequestBody SignalRequest request) {
        autonomyFlowService.signal(id, request == null ? null : request.signal(),
                request == null ? Map.of() : request.payload());
        return ApiResponse.ok(flowEngine.inspect(id));
    }

    @PostMapping("/instances/{id}/cancel")
    @RequirePermission("autonomy:flow:manage")
    public ApiResponse<FlowInstanceView> cancel(@PathVariable String id,
                                                @RequestBody(required = false) RejectRequest request) {
        autonomyFlowService.cancel(id, request == null ? "manual cancel" : request.note());
        return ApiResponse.ok(flowEngine.inspect(id));
    }

    // ── 审批 ───────────────────────────────────────────────────────────

    @GetMapping("/approvals")
    @RequirePermission("autonomy:flow:approve")
    public ApiResponse<List<ProposalView>> approvals() {
        return ApiResponse.ok(proposalService.listAutonomyPending());
    }

    @PostMapping("/approvals/{id}/confirm")
    @RequirePermission("autonomy:flow:approve")
    public ApiResponse<ProposalView> confirm(@PathVariable String id,
                                             @RequestBody(required = false) ConfirmRequest request) {
        return ApiResponse.ok(proposalService.confirmAutonomyProposal(
                id, request == null ? null : request.payloadDigest(),
                request == null ? null : request.note()));
    }

    @PostMapping("/approvals/{id}/reject")
    @RequirePermission("autonomy:flow:approve")
    public ApiResponse<ProposalView> reject(@PathVariable String id,
                                            @RequestBody(required = false) RejectRequest request) {
        return ApiResponse.ok(proposalService.rejectAutonomyProposal(
                id, request == null ? null : request.note()));
    }

    // ── 决策轨迹 ───────────────────────────────────────────────────────

    @GetMapping("/traces")
    @RequirePermission("autonomy:trace:read")
    public ApiResponse<PageResult<TraceView>> listTraces(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String verifyStatus,
            @RequestParam(required = false) String runMode,
            @RequestParam(required = false) String flowId) {
        return ApiResponse.ok(traceService.list(page, size, verifyStatus, runMode, flowId));
    }

    @GetMapping("/traces/{traceId}")
    @RequirePermission("autonomy:trace:read")
    public ApiResponse<TraceView> getTrace(@PathVariable String traceId) {
        String ws = com.datagov.common.tenant.WorkspaceContext.requireWorkspaceId();
        return ApiResponse.ok(traceService.getByTrace(ws, traceId));
    }

    @GetMapping("/traces/by-instance/{instanceId}")
    @RequirePermission("autonomy:trace:read")
    public ApiResponse<TraceView> getTraceByInstance(@PathVariable String instanceId) {
        String ws = com.datagov.common.tenant.WorkspaceContext.requireWorkspaceId();
        return ApiResponse.ok(traceService.getByInstance(ws, instanceId));
    }
}
