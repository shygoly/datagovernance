package com.datagov.control.service;

import com.datagov.control.entity.EtlEntities.EtlBadRowBatch;
import com.datagov.control.mapper.EtlBadRowBatchMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BadRowBatchServiceTest {

    @Test
    @DisplayName("record:只落摘要字段;jobId 为空时存空串(列非空);过长的样例被截断并标注")
    void recordPersistsSummaryOnly() {
        EtlBadRowBatchMapper mapper = mock(EtlBadRowBatchMapper.class);
        BadRowBatchService service = new BadRowBatchService(mapper);

        service.record("ws_1", null, "exec_1", "sink", 12, "local://ws_1/exec_1/sink.ndjson",
                "x".repeat(BadRowBatchService.MAX_SAMPLE_CHARS + 50));

        ArgumentCaptor<EtlBadRowBatch> captor = ArgumentCaptor.forClass(EtlBadRowBatch.class);
        verify(mapper).insert(captor.capture());
        EtlBadRowBatch row = captor.getValue();
        assertThat(row.getId()).startsWith("ebr");
        assertThat(row.getWorkspaceId()).isEqualTo("ws_1");
        assertThat(row.getJobId()).isEmpty();
        assertThat(row.getExecutionId()).isEqualTo("exec_1");
        assertThat(row.getNodeId()).isEqualTo("sink");
        assertThat(row.getBadRowCount()).isEqualTo(12);
        assertThat(row.getStorageRef()).isEqualTo("local://ws_1/exec_1/sink.ndjson");
        assertThat(row.getSampleErrorJson()).endsWith("…[truncated]")
                .hasSize(BadRowBatchService.MAX_SAMPLE_CHARS + "…[truncated]".length());
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("truncate:未超限原样返回,null 安全")
    void truncateNoop() {
        assertThat(BadRowBatchService.truncate(null)).isNull();
        assertThat(BadRowBatchService.truncate("{}")).isEqualTo("{}");
    }
}
