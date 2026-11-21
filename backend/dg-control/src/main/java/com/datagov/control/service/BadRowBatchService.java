package com.datagov.control.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.id.Ids;
import com.datagov.control.entity.EtlEntities.EtlBadRowBatch;
import com.datagov.control.mapper.EtlBadRowBatchMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * ETL 脏数据批次摘要(表 {@code etl_bad_row_batch},V49)。
 *
 * <p>这里<b>只存摘要</b>:坏行数、死信存储引用、错误类别样例。调用方负责保证
 * {@code sampleErrorJson} 不含 L3/L4 明文(C5)—— 本服务不解析、不放大它,
 * 但会截断过长内容,避免一个失控的错误消息撑爆行。
 *
 * <p>一次 Execution 的一个节点至多一行({@code record} 对同一 (execution, node) 是追加语义,
 * 不去重:重试的新 Attempt 会产生新的 Execution 内记录,而界面按创建时间展示)。
 */
@Service
public class BadRowBatchService {

    private static final Logger log = LoggerFactory.getLogger(BadRowBatchService.class);

    /** sample_error_json 是 TEXT,但摘要不该大;超出即截断并在尾部标注。 */
    static final int MAX_SAMPLE_CHARS = 8_000;

    private final EtlBadRowBatchMapper mapper;

    public BadRowBatchService(EtlBadRowBatchMapper mapper) {
        this.mapper = mapper;
    }

    public EtlBadRowBatch record(String workspaceId, String jobId, String executionId, String nodeId,
                                 long badRowCount, String storageRef, String sampleErrorJson) {
        EtlBadRowBatch row = new EtlBadRowBatch();
        row.setId(Ids.of("ebr"));
        row.setWorkspaceId(workspaceId);
        row.setJobId(jobId == null ? "" : jobId);
        row.setExecutionId(executionId);
        row.setNodeId(nodeId);
        row.setBadRowCount(badRowCount);
        row.setStorageRef(storageRef);
        row.setSampleErrorJson(truncate(sampleErrorJson));
        row.setCreatedAt(Instant.now());
        mapper.insert(row);
        log.info("ETL 脏数据摘要已记录 ws={} execution={} node={} 坏行={} storageRef={}",
                workspaceId, executionId, nodeId, badRowCount, storageRef);
        return row;
    }

    public List<EtlBadRowBatch> listByExecution(String workspaceId, String executionId) {
        return mapper.selectList(new LambdaQueryWrapper<EtlBadRowBatch>()
                .eq(EtlBadRowBatch::getWorkspaceId, workspaceId)
                .eq(EtlBadRowBatch::getExecutionId, executionId)
                .orderByAsc(EtlBadRowBatch::getCreatedAt));
    }

    public List<EtlBadRowBatch> listByJob(String workspaceId, String jobId, int limit) {
        return mapper.selectList(new LambdaQueryWrapper<EtlBadRowBatch>()
                .eq(EtlBadRowBatch::getWorkspaceId, workspaceId)
                .eq(EtlBadRowBatch::getJobId, jobId)
                .orderByDesc(EtlBadRowBatch::getCreatedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 500))));
    }

    static String truncate(String json) {
        if (json == null || json.length() <= MAX_SAMPLE_CHARS) {
            return json;
        }
        return json.substring(0, MAX_SAMPLE_CHARS) + "…[truncated]";
    }
}
