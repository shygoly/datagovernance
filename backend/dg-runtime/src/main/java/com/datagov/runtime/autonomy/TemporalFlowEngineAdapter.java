package com.datagov.runtime.autonomy;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.Caller;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * 可选分布式工作流引擎适配器（data-driven-ops-profile P3-1）。
 *
 * <p>这是一个<b>显式的 SPI 占位实现</b>：只有配置
 * {@code dg.autonomy.engine=temporal} 时才会替换默认 PG 状态机。它故意在
 * start 时 fail-fast，而不是悄悄退回 PG —— 运维以为切到了 Temporal、实际仍在
 * 单库状态机，是比“切换失败”更危险的静默错误。
 *
 * <p>真正的 Temporal SDK 接入（Workflow/Activity 映射、信号命名、Timer 对账）
 * 不属于本变更的非目标；保留同一 {@link CompiledFlowPlan} 契约，后续可零改
 * FlowSpec IR 替换。
 */
@Service
@ConditionalOnProperty(name = "dg.autonomy.engine", havingValue = "temporal")
public class TemporalFlowEngineAdapter implements DurableFlowEngine {

    private static final String MESSAGE =
            "Temporal 适配器尚未接入运行时：请设置 dg.autonomy.engine=pg，或完成 Temporal SDK 插件装配";

    @Override
    public String startInstance(CompiledFlowPlan plan, FlowTriggerContext trigger, Caller initiator) {
        throw new BizException(ErrorCode.RTM_FLOW_STATE, MESSAGE);
    }

    @Override
    public void sendSignal(String instanceId, String signalName, Map<String, Object> payload, Caller actor) {
        throw new BizException(ErrorCode.RTM_FLOW_STATE, MESSAGE);
    }

    @Override
    public void cancelInstance(String instanceId, String reason, Caller actor) {
        throw new BizException(ErrorCode.RTM_FLOW_STATE, MESSAGE);
    }

    @Override
    public FlowInstanceView inspect(String instanceId) {
        throw new BizException(ErrorCode.RTM_FLOW_STATE, MESSAGE);
    }

    @Override
    public int sweepDueInstances(Instant now, int batchSize) {
        throw new BizException(ErrorCode.RTM_FLOW_STATE, MESSAGE);
    }

    @Override
    public int cancelActive(String workspaceId, String reason, Caller actor) {
        throw new BizException(ErrorCode.RTM_FLOW_STATE, MESSAGE);
    }
}
