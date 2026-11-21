package com.datagov.app.runner;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 死信行本体的存放处(errorPolicy=ROUTE_TO_DEAD_LETTER)。
 *
 * <p>行本体只在这里,摘要表只存它的引用。写入前行内容里的 L3/L4 列已被引擎掩码为 {@code ***};
 * 编译器无法确认列集合时({@code captureValues=false})行里根本没有值,只有行号与错误类别。
 */
public interface EtlDeadLetterStore {

    /**
     * @param rows 每个元素形如 {@code {ordinal?, category, sqlState?, values?}}
     * @return 不透明的存储引用,原样写进 {@code etl_bad_row_batch.storage_ref}
     */
    String write(String workspaceId, String executionId, String nodeId, List<Map<String, Object>> rows)
            throws IOException;
}
