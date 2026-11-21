package com.datagov.app.lineage;

import com.datagov.governance.domain.ReportState;
import com.datagov.governance.domain.ReportSubjectType;
import com.datagov.governance.entity.GovernanceEntities.AnalysisReport;
import com.datagov.governance.mapper.AnalysisReportMapper;
import com.datagov.metadata.service.lineage.GovernanceConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("分析报告 → 血缘治理消费方提供者（P3-1）")
class AnalysisReportConsumerProviderTest {

    private final AnalysisReportMapper mapper = mock(AnalysisReportMapper.class);
    private final AnalysisReportConsumerProvider provider = new AnalysisReportConsumerProvider(mapper);

    @Test
    @DisplayName("声明与报告列表接口一致的读权限，类型为 REPORT")
    void declaresPermissionAndType() {
        assertThat(provider.requiredPermission()).isEqualTo("governance:analysis:read");
        assertThat(provider.refType()).isEqualTo("REPORT");
    }

    @Test
    @DisplayName("把以数据集为主体的已发布报告映射为 REPORT 消费方，绑定到该数据集")
    void mapsReportsToConsumers() {
        AnalysisReport report = new AnalysisReport();
        report.setId("rpt_1");
        report.setWorkspaceId("ws_1");
        report.setSubjectType(ReportSubjectType.DATASET);
        report.setSubjectId("ds_dws");
        report.setTitle("月度经营分析");
        report.setState(ReportState.PUBLISHED);
        report.setLatestVersion(3);
        when(mapper.selectList(any())).thenReturn(List.of(report));

        List<GovernanceConsumer> consumers = provider.findConsumers("ws_1", Set.of("ds_dws"));

        assertThat(consumers).hasSize(1);
        GovernanceConsumer consumer = consumers.get(0);
        assertThat(consumer.refType()).isEqualTo("REPORT");
        assertThat(consumer.refId()).isEqualTo("rpt_1");
        assertThat(consumer.displayName()).isEqualTo("月度经营分析");
        assertThat(consumer.datasetId()).isEqualTo("ds_dws");
        assertThat(consumer.columnId()).isNull();
        assertThat(consumer.detail()).contains("v3");
    }

    @Test
    @DisplayName("没有数据集时不查库")
    void emptyDatasetsDoNotQuery() {
        assertThat(provider.findConsumers("ws_1", Set.of())).isEmpty();
        verify(mapper, never()).selectList(any());
    }
}
