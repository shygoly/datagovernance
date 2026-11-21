package com.datagov.app.runner;

/**
 * 脏数据批次摘要的落库缝隙(表 {@code etl_bad_row_batch},V49)。
 *
 * <p>摘要 = 坏行数 + 死信存储引用 + 错误类别样例。<b>实现方不得放大内容</b>:
 * 调用方({@link EtlPipelineEngine} / {@link EtlBadRowLedger})已保证 {@code sampleErrorJson}
 * 不含 L3/L4 明文,这里只负责持久化。
 */
public interface EtlBadRowStore {

    void record(String workspaceId, String jobId, String executionId, String nodeId,
                long badRowCount, String storageRef, String sampleErrorJson);
}
