package com.datagov.runtime.autonomy;

import com.datagov.common.tenant.Caller;

import java.time.Instant;
import java.util.Map;

/**
 * 持久化工作流引擎 SPI（data-driven-ops-profile D3）。
 *
 * <p>默认实现 {@code PgDurableFlowEngine} 只依赖单 PostgreSQL，不引入外部中间件；
 * Temporal / Restate 适配器通过同一契约替换，不改 FlowSpec IR。
 */
public interface DurableFlowEngine {

    String startInstance(CompiledFlowPlan plan, FlowTriggerContext trigger, Caller initiator);

    void sendSignal(String instanceId, String signalName, Map<String, Object> payload, Caller actor);

    void cancelInstance(String instanceId, String reason, Caller actor);

    FlowInstanceView inspect(String instanceId);

    /** 唤醒到期的 WAITING_VERIFY / 超时的 WAITING_APPROVAL，返回推进的实例数。 */
    int sweepDueInstances(Instant now, int batchSize);

    /** Kill Switch：取消当前空间所有在途实例（含等待审批/回检），返回取消数量。 */
    int cancelActive(String workspaceId, String reason, Caller actor);
}
