package com.datagov.app.runner;

import com.datagov.control.event.ControlEvents;
import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.engine.JobRunner;
import com.datagov.runtime.etl.EtlBadRowLimitExceededException;
import com.datagov.runtime.etl.EtlEngineRegistry;
import com.datagov.runtime.etl.EtlExecutionEngine;
import com.datagov.runtime.etl.EtlRunSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 轻量级 ETL 管道的 {@link JobRunner}。
 *
 * <p>自己不再含执行逻辑:按计划里的 {@code engine} 字段(缺省 DUCKDB)经 {@link EtlEngineRegistry}
 * 选 {@link EtlExecutionEngine} 去跑(P3-1),然后把统计发布成 {@link ControlEvents.EtlExecutionCompleted}
 * (P2-3:行数 + 脏数据率,供 data-driven-ops 的自治 Trigger 订阅)。
 *
 * <p>事件只在拿得到 ETL 统计时发布:成功,或坏行超阈值失败。其它失败(连不上库、SQL 错误)
 * 由 Runtime 的 {@code ExecutionFinished} 覆盖,没有 ETL 特有的统计可发。
 */
@Component
public class EtlPipelineRunner implements JobRunner {

    private static final Logger log = LoggerFactory.getLogger(EtlPipelineRunner.class);

    private final EtlEngineRegistry engines;
    private final ApplicationEventPublisher events;

    public EtlPipelineRunner(EtlEngineRegistry engines, ApplicationEventPublisher events) {
        this.engines = engines;
        this.events = events;
    }

    @Override
    public JobRefType jobRefType() {
        return JobRefType.ETL_PIPELINE;
    }

    @Override
    public RunResult run(RunContext context) throws Exception {
        Object engineId = context.plan().get("engine");
        EtlExecutionEngine engine = engines.resolve(engineId == null ? null : engineId.toString());
        try {
            EtlRunSummary summary = engine.execute(context);
            publish(context, summary, ControlEvents.EtlExecutionCompleted.SUCCEEDED);
            return RunResult.of(summary.rowsRead(), summary.rowsWritten(), 0);
        } catch (EtlBadRowLimitExceededException e) {
            publish(context, e.summary(), ControlEvents.EtlExecutionCompleted.FAILED_BAD_ROW_LIMIT);
            throw e;
        }
    }

    @Override
    public String classify(Exception e) {
        if (e instanceof EtlBadRowLimitExceededException) {
            return "ETL_BAD_ROW_LIMIT";
        }
        return e instanceof java.sql.SQLException ? "DAT_QUERY_FAILED" : "SYS_INTERNAL_ERROR";
    }

    private void publish(RunContext context, EtlRunSummary summary, String status) {
        if (summary == null) {
            return;
        }
        Object jobId = context.plan().get("jobId");
        try {
            events.publishEvent(new ControlEvents.EtlExecutionCompleted(
                    context.workspaceId(), jobId == null ? null : jobId.toString(), context.executionId(),
                    summary.engine(), status, summary.rowsRead(), summary.rowsWritten(), summary.badRows(),
                    summary.badRowRate(), summary.codesetMisses(), summary.errorPolicyMode(), Instant.now()));
        } catch (RuntimeException e) {
            // 监听器的失败不应让一次已经写完的管道变成失败
            log.error("发布 EtlExecutionCompleted 失败 execution={}", context.executionId(), e);
        }
    }
}
