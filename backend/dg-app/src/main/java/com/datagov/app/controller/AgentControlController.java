package com.datagov.app.controller;

import com.datagov.app.insight.AgentControlPlaneService;
import com.datagov.app.insight.AgentControlPlaneService.EffectivePolicy;
import com.datagov.app.insight.AgentControlPlaneService.PolicyUpdateCommand;
import com.datagov.app.insight.entity.AgentControlPolicy;
import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.tenant.WorkspaceContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能体控制面管理端点（Phase B / b3，闭合 OWASP ASI08 / ASI10）。
 */
@RestController
@RequestMapping("/api/v1/governance/agent-control")
@Tag(name = "智能体控制面", description = "配额熔断、调用深度上限与 Kill Switch 急停开关")
public class AgentControlController {

    private final AgentControlPlaneService controlPlane;

    public AgentControlController(AgentControlPlaneService controlPlane) {
        this.controlPlane = controlPlane;
    }

    public record KillSwitchRequest(boolean enabled, String reason, boolean globalScope) {
    }

    @GetMapping
    @RequirePermission("governance:agent:manage")
    @Operation(summary = "查询当前空间生效的智能体控制面策略")
    public ApiResponse<EffectivePolicy> get() {
        String workspaceId = WorkspaceContext.require().workspaceId();
        return ApiResponse.ok(controlPlane.resolveEffectivePolicy(workspaceId));
    }

    @PutMapping
    @RequirePermission("governance:agent:manage")
    @Operation(summary = "更新智能体控制面策略（深度上限 / 错误熔断 / 每日配额）")
    public ApiResponse<EffectivePolicy> update(
            @RequestParam(defaultValue = "false") boolean globalScope,
            @RequestBody PolicyUpdateCommand command) {
        String scope = globalScope
                ? AgentControlPolicy.GLOBAL_WORKSPACE
                : WorkspaceContext.require().workspaceId();
        return ApiResponse.ok(controlPlane.updatePolicy(scope, command));
    }

    @PostMapping("/kill-switch")
    @RequirePermission("governance:agent:manage")
    @Operation(summary = "一键切换智能体急停开关（开启后立即拒绝新消息并中止在途会话）")
    public ApiResponse<EffectivePolicy> killSwitch(@RequestBody KillSwitchRequest request) {
        String scope = (request != null && request.globalScope())
                ? AgentControlPolicy.GLOBAL_WORKSPACE
                : WorkspaceContext.require().workspaceId();
        boolean enabled = request != null && request.enabled();
        String reason = request == null ? null : request.reason();
        return ApiResponse.ok(controlPlane.setKillSwitch(scope, enabled, reason));
    }
}
