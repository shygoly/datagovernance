package com.datagov.runtime.autonomy;

/**
 * 步骤处理器 SPI。实现放在装配层（dg-app），因为求值需要同时看到
 * Metadata（Target/ActionType）、Control（Arbiter）与 Governance（Trace）。
 *
 * <p>Runtime 只负责持久化状态机、审批挂起、冷却与补偿顺序，不解释业务语义。
 */
public interface FlowStepHandler {

    String kind();

    StepResult execute(FlowStepContext context);

    /** 审批信号 / T+1 回检唤醒；默认重新执行同一步。 */
    default StepResult resume(FlowStepContext context) {
        return execute(context);
    }

    /**
     * 已执行步骤的逆序补偿。只有真正有副作用的步骤需要覆盖；
     * 默认返回“无需补偿”。
     */
    default java.util.Map<String, Object> compensate(FlowStepContext context) {
        return java.util.Map.of("compensated", false, "reason", "no compensation");
    }
}
