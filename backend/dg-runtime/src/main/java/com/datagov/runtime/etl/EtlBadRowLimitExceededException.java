package com.datagov.runtime.etl;

/**
 * 坏行数 / 比例超出 errorPolicy 阈值(或 FAIL_FAST 下出现了坏行)。
 *
 * <p>携带失败时刻的统计:Runner 据此发出 {@code EtlExecutionCompleted(status=FAILED_BAD_ROW_LIMIT)},
 * 让自治 Trigger 能订阅「数据管道因脏数据失败」。异常消息本身不含行内容。
 */
public class EtlBadRowLimitExceededException extends RuntimeException {

    private final transient EtlRunSummary summary;

    public EtlBadRowLimitExceededException(String message, EtlRunSummary summary) {
        super(message);
        this.summary = summary;
    }

    public EtlRunSummary summary() {
        return summary;
    }
}
