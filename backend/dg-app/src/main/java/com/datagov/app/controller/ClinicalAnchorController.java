package com.datagov.app.controller;

import com.datagov.app.service.ClinicalAnchorService;
import com.datagov.app.service.ClinicalAnchorService.AlignmentRunView;
import com.datagov.app.service.ClinicalAnchorService.QcRunView;
import com.datagov.app.service.ClinicalAnchorService.ResolveRequest;
import com.datagov.app.service.ClinicalAnchorService.RunRequest;
import com.datagov.app.service.ClinicalAnchorService.SpecRequest;
import com.datagov.app.service.ClinicalAnchorService.SpecView;
import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.governance.entity.ClinicalQcFinding;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * T₀ 锚点对齐 / 多程切分 / HCC 专病质控（chance-t0-alignment-and-qc-rules）。
 */
@RestController
@RequestMapping("/api/v1/metadata/clinical")
@Tag(name = "临床锚点与质控", description = "T0 对齐、多程切分与专病 RuleSpec 质控")
public class ClinicalAnchorController {

    private final ClinicalAnchorService service;

    public ClinicalAnchorController(ClinicalAnchorService service) {
        this.service = service;
    }

    @PostMapping("/anchors/specs")
    @RequirePermission("metadata:quality:manage")
    @Operation(summary = "创建/更新锚点规格（逻辑绑定，禁止物理表名）")
    public ApiResponse<SpecView> upsertSpec(@RequestBody SpecRequest request) {
        return ApiResponse.ok(service.upsertSpec(request));
    }

    @GetMapping("/anchors/specs")
    @RequirePermission("metadata:quality:read")
    @Operation(summary = "列出锚点规格")
    public ApiResponse<List<SpecView>> listSpecs() {
        return ApiResponse.ok(service.listSpecs());
    }

    @PostMapping("/anchors/specs/ensure-standard-stub")
    @RequirePermission("metadata:quality:manage")
    @Operation(summary = "注册 STD_CHANCE_HCC_QC_V1 占位规格")
    public ApiResponse<SpecView> ensureStandardStub() {
        return ApiResponse.ok(service.ensureStandardStub());
    }

    @PostMapping("/anchors/align")
    @RequirePermission("metadata:quality:manage")
    @Operation(summary = "多程切分 + T0 窗口对齐")
    public ApiResponse<AlignmentRunView> align(@RequestBody RunRequest request) {
        return ApiResponse.ok(service.splitAndAlign(request));
    }

    @PostMapping("/cycles/split")
    @RequirePermission("metadata:quality:manage")
    @Operation(summary = "仅多程治疗切分（返回对齐前程次）")
    public ApiResponse<AlignmentRunView> splitOnly(@RequestBody RunRequest request) {
        // 复用 align 管道但可只传 ANCHOR 行
        return ApiResponse.ok(service.splitAndAlign(request));
    }

    @PostMapping("/qc/rulesets/STD_CHANCE_HCC_QC_V1/run")
    @RequirePermission("metadata:quality:manage")
    @Operation(summary = "运行 HCC 专病质控规则包")
    public ApiResponse<QcRunView> runQc(@RequestBody RunRequest request) {
        return ApiResponse.ok(service.runHccQc(request));
    }

    @PostMapping("/qc/rulesets/STD_CHANCE_HCC_QC_V1/recheck")
    @RequirePermission("metadata:quality:manage")
    @Operation(summary = "人工修正后复检")
    public ApiResponse<QcRunView> recheck(@RequestBody RunRequest request) {
        return ApiResponse.ok(service.recheck(request));
    }

    @GetMapping("/qc/findings")
    @RequirePermission("metadata:quality:read")
    @Operation(summary = "质控发现列表")
    public ApiResponse<List<ClinicalQcFinding>> listFindings(
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.listFindings(status));
    }

    @PostMapping("/qc/findings/{id}/resolve")
    @RequirePermission("metadata:quality:manage")
    @Operation(summary = "宿主确认关闭 Blocker/Warning（C7）")
    public ApiResponse<ClinicalQcFinding> resolve(
            @PathVariable String id,
            @RequestBody(required = false) ResolveRequest request) {
        return ApiResponse.ok(service.resolveFinding(id, request, true));
    }
}
