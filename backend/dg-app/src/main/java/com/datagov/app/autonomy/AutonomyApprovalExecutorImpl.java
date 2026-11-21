package com.datagov.app.autonomy;

import com.datagov.app.insight.AutonomyApprovalExecutor;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.runtime.autonomy.DurableFlowEngine;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 宿主确认后的真实执行：只发送 APPROVED 信号，动作本身仍由引擎的步骤处理器执行。
 * 这样“审批”与“执行”之间没有第二条绕过 FlowSpec 的路径。
 */
@Component
public class AutonomyApprovalExecutorImpl implements AutonomyApprovalExecutor {

    private final DurableFlowEngine flowEngine;

    public AutonomyApprovalExecutorImpl(DurableFlowEngine flowEngine) {
        this.flowEngine = flowEngine;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> payload) {
        String instanceId = payload == null ? null : String.valueOf(payload.get("instanceId"));
        if (instanceId == null || instanceId.isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "审批载荷缺少 instanceId");
        }
        flowEngine.sendSignal(instanceId, "APPROVED", payload, WorkspaceContext.require());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceId", instanceId);
        out.put("status", flowEngine.inspect(instanceId).status());
        out.put("signal", "APPROVED");
        return out;
    }
}
