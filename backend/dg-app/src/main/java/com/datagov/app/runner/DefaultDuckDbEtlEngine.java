package com.datagov.app.runner;

import com.datagov.runtime.engine.JobRunner;
import com.datagov.runtime.etl.EtlExecutionEngine;
import com.datagov.runtime.etl.EtlRunSummary;
import com.datagov.runtime.spi.ExecutionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * 内嵌 DuckDB 的 {@link EtlExecutionEngine}(P3-1:原 {@code EtlPipelineRunner} 的执行逻辑搬到这里)。
 *
 * <p>流程:解析计划 → 开隔离 DuckDB → 抽取 SOURCE_JDBC、装载码表 → 锁定外部访问 → 建转换视图 →
 * 统计码表未命中(并按 errorPolicy 处理 BAD_ROW)→ 经 SINK_JDBC 写目标端(坏行按策略跳过 / 路由)→
 * 整条成功后推进水位线。无论成败,{@code finally} 里把坏行台账落成 {@code etl_bad_row_batch} 摘要
 * (死信模式下先把行本体写入死信存储)—— 失败的那次执行恰恰最需要它。
 *
 * <p>放在 dg-app 的原因与 {@link OfflineSyncRunner} 相同:它同时需要 Metadata 的数据源定义与
 * Data 的连接装配,只有装配层能同时看见两边。
 *
 * <p>已知取舍:水位线按「抽取到的最大值」推进,被跳过 / 路由的坏行不会在下一次增量里重来 ——
 * 它们只存在于摘要与死信里,修复后需手工重放(或重置水位线)。
 */
@Component
public class DefaultDuckDbEtlEngine implements EtlExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultDuckDbEtlEngine.class);

    private final EtlPipelineEngine engine;
    private final ObjectProvider<EtlBadRowStore> badRowStore;
    private final ObjectProvider<EtlDeadLetterStore> deadLetterStore;

    public DefaultDuckDbEtlEngine(EtlPipelineEngine engine,
                                  ObjectProvider<EtlBadRowStore> badRowStore,
                                  ObjectProvider<EtlDeadLetterStore> deadLetterStore) {
        this.engine = engine;
        this.badRowStore = badRowStore;
        this.deadLetterStore = deadLetterStore;
    }

    @Override
    public String engineId() {
        return DUCKDB;
    }

    @Override
    public EtlRunSummary execute(JobRunner.RunContext context) throws Exception {
        EtlPipelineEngine.Plan plan = engine.parse(context.plan());
        List<EtlPipelineEngine.Node> sinks = plan.nodes().stream()
                .filter(n -> EtlPipelineEngine.SINK_JDBC.equals(n.type())
                        || EtlPipelineEngine.SINK_PARQUET.equals(n.type()))
                .toList();
        if (sinks.size() != 1) {
            // 多 Sink 需要事务边界与部分失败语义,不在当前范围
            throw new IllegalArgumentException("要求恰好一个 Sink 节点(SINK_JDBC 或 SINK_PARQUET),实际 " + sinks.size());
        }
        log.info("ETL 管道开始 execution={} 节点{}个 步骤{}个 码表节点{}个 errorPolicy={}",
                context.executionId(), plan.nodes().size(), plan.steps().size(),
                plan.codesets().size(), plan.badRowPolicy().mode());

        try (EtlPipelineEngine.Session session = engine.open(context.workspaceId(), plan,
                EtlPipelineEngine.Limits.execution(), context.canceled(),
                read -> context.progress().accept(new ExecutionEngine.EngineMetric(read, 0, 0)))) {
            try {
                engine.analyzeCodesets(session);
                context.throwIfCanceled();
                long written = engine.writeSink(context.workspaceId(), session, sinks.get(0), context);
                // 只有 Sink 全部提交后才推进水位线:先推进再失败,下次增量会漏掉这批数据
                engine.advanceWatermarks(context.workspaceId(), session, context.executionId());
                EtlRunSummary summary = engine.summary(session, plan.badRowPolicy());
                log.info("ETL 管道完成 execution={} 读{}行 写{}行 坏行{} 坏行率{}",
                        context.executionId(), summary.rowsRead(), written, summary.badRows(),
                        summary.badRowRate());
                return summary;
            } finally {
                persistBadRows(context, plan, session);
            }
        }
    }

    @Override
    public String classify(Exception e) {
        if (e instanceof com.datagov.runtime.etl.EtlBadRowLimitExceededException) {
            return "ETL_BAD_ROW_LIMIT";
        }
        return e instanceof SQLException ? "DAT_QUERY_FAILED" : "SYS_INTERNAL_ERROR";
    }

    /**
     * 台账 → 摘要表(+ 死信)。持久化失败只记日志:这一步是 finally,抛出会盖掉真正的失败原因。
     */
    private void persistBadRows(JobRunner.RunContext context, EtlPipelineEngine.Plan plan,
                                EtlPipelineEngine.Session session) {
        EtlBadRowLedger ledger = session.ledger();
        if (ledger.total() == 0) {
            return;
        }
        EtlBadRowStore store = badRowStore.getIfAvailable();
        EtlDeadLetterStore deadLetters = deadLetterStore.getIfAvailable();
        for (EtlBadRowLedger.Entry entry : ledger.entries()) {
            String storageRef = null;
            if (plan.badRowPolicy().deadLetter()) {
                if (deadLetters == null) {
                    log.error("ETL 死信存储不可用,坏行本体丢失 execution={} node={}",
                            context.executionId(), entry.nodeId());
                } else {
                    try {
                        storageRef = deadLetters.write(context.workspaceId(), context.executionId(),
                                entry.nodeId(), entry.bodyRows());
                    } catch (IOException | RuntimeException e) {
                        log.error("ETL 死信写入失败,摘要仍会保留 execution={} node={}",
                                context.executionId(), entry.nodeId(), e);
                    }
                }
            }
            if (store == null) {
                log.error("ETL 坏行摘要存储不可用 execution={} node={} 坏行={}",
                        context.executionId(), entry.nodeId(), entry.count());
                continue;
            }
            try {
                store.record(context.workspaceId(), plan.jobId(), context.executionId(), entry.nodeId(),
                        entry.count(), storageRef, ledger.sampleJson(entry, plan.badRowPolicy().mode()));
            } catch (RuntimeException e) {
                log.error("ETL 坏行摘要落库失败 execution={} node={} 坏行={}",
                        context.executionId(), entry.nodeId(), entry.count(), e);
            }
        }
    }
}
