package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.governance.entity.GovernedFact;
import com.datagov.governance.service.GovernedFactService;
import com.datagov.governance.service.GovernedFactService.ProposeFactCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 双时间态治理事实与受控 Learn 闭环端点（Phase C / c2）。
 */
@RestController
@RequestMapping("/api/v1/governance/facts")
@Tag(name = "治理事实与知识沉淀", description = "双时间态 Fact、暂存审校晋升与防投毒回滚（A0.1 / A0.3）")
public class GovernedFactController {

    private final GovernedFactService factService;

    public GovernedFactController(GovernedFactService factService) {
        this.factService = factService;
    }

    public record ReviewNoteRequest(String note) {
    }

    public record DistillReportRequest(String reportId, String assetRef) {
    }

    @GetMapping
    @RequirePermission("governance:knowledge:read")
    @Operation(summary = "查询治理事实（支持 asOf 双时间态历史回溯）")
    public ApiResponse<List<GovernedFact>> query(
            @RequestParam(required = false) String assetRef,
            @RequestParam(required = false) String factType,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Instant asOf) {
        return ApiResponse.ok(factService.query(assetRef, factType, state, asOf));
    }

    @PostMapping("/propose")
    @RequirePermission("governance:knowledge:review")
    @Operation(summary = "提交候选事实至 PROPOSED 暂存区（需外部反馈与证据引用）")
    public ApiResponse<GovernedFact> propose(@RequestBody ProposeFactCommand command) {
        return ApiResponse.ok(factService.propose(command));
    }

    @PostMapping("/distill-report")
    @RequirePermission("governance:knowledge:review")
    @Operation(summary = "从 CONFIRMED 分析报告提炼候选 case 事实入暂存区")
    public ApiResponse<GovernedFact> distillReport(@RequestBody DistillReportRequest request) {
        return ApiResponse.ok(factService.distillFromAnalysisReport(
                request == null ? null : request.reportId(),
                request == null ? null : request.assetRef()));
    }

    @PostMapping("/{id}/approve")
    @RequirePermission("governance:knowledge:review")
    @Operation(summary = "人工审校晋升事实（旧版本自动置 SUPERSEDED + validTo）")
    public ApiResponse<GovernedFact> approve(@PathVariable String id,
                                             @RequestBody(required = false) ReviewNoteRequest request) {
        return ApiResponse.ok(factService.approve(id, request == null ? null : request.note()));
    }

    @PostMapping("/{id}/reject")
    @RequirePermission("governance:knowledge:review")
    @Operation(summary = "拒绝暂存事实并留存理由")
    public ApiResponse<GovernedFact> reject(@PathVariable String id,
                                            @RequestBody ReviewNoteRequest request) {
        return ApiResponse.ok(factService.reject(id, request == null ? null : request.note()));
    }

    @PostMapping("/{id}/rollback")
    @RequirePermission("governance:knowledge:review")
    @Operation(summary = "回滚已发布事实并恢复被取代前驱版本（防 ASI06）")
    public ApiResponse<GovernedFact> rollback(@PathVariable String id,
                                              @RequestBody ReviewNoteRequest request) {
        return ApiResponse.ok(factService.rollback(id, request == null ? null : request.note()));
    }
}
