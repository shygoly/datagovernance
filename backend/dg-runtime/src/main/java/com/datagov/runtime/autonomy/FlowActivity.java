package com.datagov.runtime.autonomy;

import com.datagov.common.tenant.Caller;

import java.util.Map;

/**
 * 无状态原子 Activity SPI。实现放在装配层，因为暂停同步任务 / 发起质检 /
 * 生成运营报表分别属于 Control / Metadata / Governance，Runtime 不能反向依赖它们。
 *
 * <p>每个 Activity 必须同时提供 dryRun 与 compensate：没有预演就不能进入
 * SHADOW，没有补偿就不能进入 L1_AUTO（FlowCompiler 会在编译期拦截）。
 */
public interface FlowActivity {

    String ref();

    Map<String, Object> dryRun(FlowActivityContext context);

    Map<String, Object> execute(FlowActivityContext context);

    default Map<String, Object> compensate(FlowActivityContext context) {
        return Map.of("compensated", false, "reason", "activity declares no compensation");
    }

    record FlowActivityContext(
            String workspaceId,
            String instanceId,
            String actionTypeRef,
            String actionCode,
            Map<String, Object> params,
            String reason,
            String runMode,
            Caller initiator
    ) {
        public FlowActivityContext {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }
}
