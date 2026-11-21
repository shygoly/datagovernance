package com.datagov.control.event;

import com.datagov.control.domain.JobType;

import java.time.Instant;
import java.util.Map;

/**
 * Control Space 对外发布的领域事件(对应 {@code docs/space-contracts/control.yaml}
 * 的 {@code events_produced})。
 *
 * <p>与 Runtime 一样走进程内 Spring 事件总线。消费方在 dg-app 装配层
 * (例如列级血缘的 {@code LineageEventListener}),因此 dg-control 不需要知道
 * 血缘、也不依赖任何消费方 —— 事件只陈述事实。
 */
public final class ControlEvents {

    private ControlEvents() {
    }

    /**
     * 任务定义编译成功(含发布前的强制重编译)。
     *
     * <p>事件<b>自足</b>:携带编译产物 {@code physicalPlan} 与定义配置 {@code config}
     * 的只读快照,消费方不必回头调用 Control 的服务(那样会把消费方拉进 Control 的事务)。
     *
     * @param definitionVersion 产出该计划的定义版本
     */
    public record CompileSucceeded(
            String workspaceId, String jobDefinitionId, String jobName, JobType jobType,
            int definitionVersion, Map<String, Object> physicalPlan, Map<String, Object> config,
            Instant occurredAt) {

        public CompileSucceeded {
            physicalPlan = physicalPlan == null ? Map.of() : physicalPlan;
            config = config == null ? Map.of() : config;
        }
    }

    /**
     * ETL 水位线被人工重置。水位线决定下一次增量抽取从哪里开始,
     * 重置会导致数据重抽或漏抽,必须留痕;审计由 dg-app 装配层的监听器落库。
     */
    public record EtlWatermarkReset(
            String workspaceId, String jobId, String sourceNodeId,
            String previousValue, String newValue, String note, String operatorId,
            Instant occurredAt) {
    }

    /**
     * 一次 ETL 管道执行结束(成功,或因坏行超阈值而失败)。
     *
     * <p>这是 {@code data-driven-ops-profile} 自治 Trigger 订阅「数据管道异常」的事实源:
     * 行数与脏数据率在 Runtime 的 {@code ExecutionFinished} 里没有,只有 ETL Runner 知道。
     * 事件<b>自足且不含任何行内容</b>:只有计数、比例与节点 id(C5)。
     * 由 dg-app 装配层的 Runner 发布;发布方不依赖任何消费方。
     *
     * @param status             SUCCEEDED | FAILED_BAD_ROW_LIMIT(坏行超出 errorPolicy 阈值)
     * @param badRows            被跳过 / 路由的坏行总数(含码表 BAD_ROW 未命中)
     * @param badRowRate         badRows / (进入 Sink 的行 + 码表阶段剔除的行),0~1;无行时为 0
     * @param codesetMisses      码表映射节点 id → 未命中行数(与 onMiss 策略无关,一律统计)
     * @param errorPolicyMode    本次生效的 errorPolicy.mode
     */
    public record EtlExecutionCompleted(
            String workspaceId, String jobId, String executionId, String engine, String status,
            long rowsRead, long rowsWritten, long badRows, double badRowRate,
            Map<String, Long> codesetMisses, String errorPolicyMode, Instant occurredAt) {

        public static final String SUCCEEDED = "SUCCEEDED";
        public static final String FAILED_BAD_ROW_LIMIT = "FAILED_BAD_ROW_LIMIT";

        public EtlExecutionCompleted {
            codesetMisses = codesetMisses == null ? Map.of() : Map.copyOf(codesetMisses);
        }
    }
}
