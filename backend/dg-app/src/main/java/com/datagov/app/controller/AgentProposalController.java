package com.datagov.app.controller;

import com.datagov.app.insight.AgentActionProposalService;
import com.datagov.app.insight.AgentActionProposalService.ProposalView;
import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
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
 * Agent 写操作宿主独立确认端点（Phase B / b2，闭合 OWASP ASI09）。
 *
 * <p>必须由前端宿主应用携带用户真实平台 JWT 调用；执行瞬间二次核验身份、权限与载荷摘要。
 */
@RestController
@RequestMapping("/api/v1/insight/agent/proposals")
@Tag(name = "Agent 操作提案确认", description = "宿主独立确认视图后端端点（防 ASI09）")
public class AgentProposalController {

    private final AgentActionProposalService proposalService;

    public AgentProposalController(AgentActionProposalService proposalService) {
        this.proposalService = proposalService;
    }

    public record ConfirmRequest(String payloadDigest, String note) {
    }

    public record RejectRequest(String note) {
    }

    @GetMapping
    @RequirePermission("insight:ask")
    @Operation(summary = "查询当前用户的 Agent 操作提案")
    public ApiResponse<List<ProposalView>> list(
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(proposalService.listMyProposals(conversationId, status));
    }

    @PostMapping("/{id}/confirm")
    @RequirePermission("insight:ask")
    @Operation(summary = "宿主独立确认并执行提案（执行瞬间二次校验权限与摘要）")
    public ApiResponse<ProposalView> confirm(
            @PathVariable String id,
            @RequestBody(required = false) ConfirmRequest request) {
        String digest = request == null ? null : request.payloadDigest();
        String note = request == null ? null : request.note();
        return ApiResponse.ok(proposalService.confirmProposal(id, digest, note));
    }

    @PostMapping("/{id}/reject")
    @RequirePermission("insight:ask")
    @Operation(summary = "宿主独立拒绝提案")
    public ApiResponse<ProposalView> reject(
            @PathVariable String id,
            @RequestBody(required = false) RejectRequest request) {
        String note = request == null ? null : request.note();
        return ApiResponse.ok(proposalService.rejectProposal(id, note));
    }
}
