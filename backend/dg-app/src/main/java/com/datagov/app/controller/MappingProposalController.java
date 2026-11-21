package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.governance.entity.MappingProposal;
import com.datagov.governance.service.MappingProposalService;
import com.datagov.governance.service.MappingProposalService.DecisionRequest;
import com.datagov.governance.service.MappingProposalService.SuggestRequest;
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

@RestController
@RequestMapping("/api/v1/governance/mapping-proposals")
@Tag(name = "映射建议收件箱", description = "AI/启发式字段→本体映射建议与人机审校")
public class MappingProposalController {

    private final MappingProposalService service;

    public MappingProposalController(MappingProposalService service) {
        this.service = service;
    }

    @PostMapping("/suggest")
    @RequirePermission("governance:knowledge:review")
    @Operation(summary = "生成或收录映射建议（进入 PROPOSED 收件箱）")
    public ApiResponse<List<MappingProposal>> suggest(@RequestBody SuggestRequest request) {
        return ApiResponse.ok(service.suggest(request));
    }

    @GetMapping
    @RequirePermission("governance:knowledge:read")
    @Operation(summary = "映射提案列表")
    public ApiResponse<List<MappingProposal>> list(@RequestParam(required = false) String state) {
        return ApiResponse.ok(service.list(state));
    }

    @PostMapping("/{id}/approve")
    @RequirePermission("governance:knowledge:review")
    @Operation(summary = "审校通过映射建议")
    public ApiResponse<MappingProposal> approve(@PathVariable String id,
                                                @RequestBody(required = false) DecisionRequest request) {
        return ApiResponse.ok(service.approve(id, request));
    }

    @PostMapping("/{id}/reject")
    @RequirePermission("governance:knowledge:review")
    @Operation(summary = "驳回映射建议并防重提")
    public ApiResponse<MappingProposal> reject(@PathVariable String id,
                                               @RequestBody DecisionRequest request) {
        return ApiResponse.ok(service.reject(id, request));
    }
}
