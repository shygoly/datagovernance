package com.datagov.runtime.autonomy;

import java.util.Map;

/**
 * 运行态观察者 SPI。Runtime 不依赖 Governance，装配层用它在实例推进时
 * 写入 {@code au_decision_trace}（C8 证据链）。
 */
public interface FlowObserver {

    default void onInstanceStarted(FlowInstanceView instance, CompiledFlowPlan plan,
                                   FlowTriggerContext trigger) {
    }

    default void onStepCompleted(FlowInstanceView instance, FlowStep step, StepResult result) {
    }

    default void onWaiting(FlowInstanceView instance, FlowStep step, StepResult result) {
    }

    default void onCompleted(FlowInstanceView instance, Map<String, Object> output) {
    }

    default void onFailed(FlowInstanceView instance, String reason) {
    }

    default void onCompensated(FlowInstanceView instance, FlowStep step, Map<String, Object> result) {
    }
}
