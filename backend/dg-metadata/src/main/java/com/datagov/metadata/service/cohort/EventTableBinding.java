package com.datagov.metadata.service.cohort;

/**
 * 事件事实表物理绑定（v0：由调用方/目录提供，不内嵌在 Criterion）。
 */
public record EventTableBinding(
        String eventCode,
        String tableName,
        String memberColumn,
        String timeColumn
) {
}
