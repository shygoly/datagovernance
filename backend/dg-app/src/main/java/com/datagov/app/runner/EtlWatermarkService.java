package com.datagov.app.runner;

import java.util.Optional;

/**
 * 增量水位线读写(表 {@code etl_watermark},V49)。
 *
 * <p>Phase 1 只定义契约,由 {@link EtlPipelineEngine} 以 {@code ObjectProvider} 方式可选注入:
 * 没有实现 Bean 时管道退化为全量抽取、不推进水位线。实现要点(留给后续 WP):
 * 读写需在 {@code app.workspace_id} 的 RLS 上下文内进行,且 {@code advance} 必须是单调的
 * (新值不大于旧值时不回退)。
 */
public interface EtlWatermarkService {

    /** 该管道在某个源节点上的上次水位值(字符串形式),无记录为空。 */
    Optional<String> lastValue(String workspaceId, String jobId, String sourceNodeId);

    /** 推进水位线。只应在整条管道(含 Sink)成功之后调用。 */
    void advance(String workspaceId, String jobId, String sourceNodeId,
                 String column, String type, String newValue, String executionId);
}
