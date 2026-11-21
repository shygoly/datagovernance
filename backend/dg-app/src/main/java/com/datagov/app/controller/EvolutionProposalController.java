package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.governance.entity.GovernedFact;
import com.datagov.governance.service.EvolutionProposalService;
import com.datagov.governance.service.EvolutionProposalService.CreateEvolutionProposalRequest;
import com.datagov.governance.service.EvolutionProposalService.EvolutionProposalResult;
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
 * 受控演进提案端点（FR-04 / governed-agent-evolution-mvp）。
 *
 * <p>封装 Staging 提案列表、提交、人工审校晋升（双时间态）、驳回防重提与一键回滚。
 */
@RestController
@RequestMapping("/api/v1/governance/evolution/proposals")
@Tag(name = "受控演进提案", description = "Staging 提案列表、双时间态晋升、驳回防重提与回滚（C9）")
public class EvolutionProposalController {

    private final EvolutionProposalService evolutionService;

    public EvolutionProposalController(EvolutionProposalService evolutionService) {
        this.evolutionService = evolutionService;
    }

    public record DecisionRequest(String note, String reason) {
        public String effectiveText() {
            if (reason != null && !reason.isBlank()) {
                return reason;
            }
            return note;
        }
    }

    @GetMapping
    @RequirePermission("governance:knowledge:read")
    @Operation(summary = "查询演进提案列表（默认 PROPOSED 暂存态）")
    public ApiResponse<List<GovernedFact>> list(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String assetRef) {
        return ApiResponse.ok(evolutionService.listProposals(state, assetRef));
    }

    @PostMapping
    @RequirePermission("governance:knowledge:review")
    @Operation(summary = "创建 Staging 演进提案（命中历史驳回指纹自动抑制）")
    public ApiResponse<EvolutionProposalResult> create(@RequestBody CreateEvolutionProposalRequest request) {
        return ApiResponse.ok(evolutionService.createProposal(request));
    }

    @PostMapping("/{id}/approve")
    @RequirePermission("governance:knowledge:review")
    @Operation(summary = "审校通过并双时间态晋升演进提案")
    public ApiResponse<GovernedFact> approve(@PathVariable String id,
                                             @RequestBody(required = false) DecisionRequest request) {
        return ApiResponse.ok(evolutionService.approveProposal(
                id, request == null ? null : request.effectiveText()));
    }

    @PostMapping("/{id}/reject")
    @RequirePermission("governance:knowledge:review")
    @Operation(summary = "驳回演进提案并写入防重提账本")
    public ApiResponse<GovernedFact> reject(@PathVariable String id,
                                            @RequestBody DecisionRequest request) {
        return ApiResponse.ok(evolutionService.rejectProposal(
                id, request == null ? null : request.effectiveText()));
    }

    @PostMapping("/{id}/rollback")
    @RequirePermission("governance:knowledge:review")
    @Operation(summary = "一键回滚已晋升演进提案并恢复前驱版本")
    public ApiResponse<GovernedFact> rollback(@PathVariable String id,
                                              @RequestBody DecisionRequest request) {
        return ApiResponse.ok(evolutionService.rollbackProposal(
                id, request == null ? null : request.effectiveText()));
    }
}
