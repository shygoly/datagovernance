package com.datagov.runtime.etl;

import com.datagov.runtime.engine.JobRunner;

/**
 * 轻量 ETL 管道的执行引擎 SPI(lightweight-etl-pipeline P3-1)。
 *
 * <p>与 {@code ExecutionEngine}(面向「在哪里跑」:本地 / Flink / K8s)正交:本接口回答的是
 * 「<b>etl.ir/v1 的物理计划由谁来解释</b>」。MVP 的唯一实现是内嵌 DuckDB
 * ({@code DefaultDuckDbEtlEngine},装配在 dg-app,因为它要同时用到数据源定义与连接装配);
 * 预留的第二个是 {@link SeaTunnelEngineAdapter} —— 现阶段只做 IR → HOCON 翻译,不执行。
 *
 * <p>契约:
 * <ul>
 *   <li>输入是 {@link JobRunner.RunContext}(计划、取消旗、进度回调、不可重投声明),
 *       不引入新的上下文类型 —— 引擎只是 {@link com.datagov.runtime.engine.JobRunner} 内部再分一层;</li>
 *   <li>失败走异常(与 JobRunner 一致);坏行超出 errorPolicy 阈值抛
 *       {@link EtlBadRowLimitExceededException},它携带已发生的统计,便于上游发出异常事件;</li>
 *   <li>引擎自己不发 Spring 事件、不碰 Control 的服务 —— 事件由 Runner 依据返回的
 *       {@link EtlRunSummary} 发布。</li>
 * </ul>
 */
public interface EtlExecutionEngine {

    String DUCKDB = "DUCKDB";
    String SEATUNNEL = "SEATUNNEL";

    /** 引擎标识,与计划里的 {@code engine} 字段对应(大写)。 */
    String engineId();

    EtlRunSummary execute(JobRunner.RunContext context) throws Exception;

    /** 把异常归类成平台错误码;默认内部错误。 */
    default String classify(Exception e) {
        return "SYS_INTERNAL_ERROR";
    }
}
