package com.datagov.app.insight;

import com.datagov.app.insight.InsightQueryEngine.DatasetOption;
import com.datagov.app.insight.InsightQueryEngine.EngineAskResult;
import com.datagov.app.insight.InsightQueryEngine.InsightColumn;
import com.datagov.app.insight.entity.InsightQueryLog;
import com.datagov.app.insight.mapper.InsightQueryLogMapper;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.service.AccessRequestService;
import com.datagov.governance.service.AccessRequestService.GrantCoverage;
import com.datagov.governance.service.AuditService;
import com.datagov.governance.service.AuditService.AuditEntry;
import com.datagov.metadata.service.SensitivityService;
import com.datagov.metadata.service.SensitivityService.SensitiveColumnView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("原生问数网关脱敏、临时 grant 与语义目录")
class InsightQueryServiceGrantTest {

    private static final String WORKSPACE = "ws_alpha";

    private InsightQueryEngine engine;
    private InsightQueryLogMapper logMapper;
    private SensitivityService sensitivityService;
    private AccessRequestService accessRequestService;
    private AuditService auditService;
    private InsightQueryService service;

    @BeforeEach
    void setUp() {
        engine = mock(InsightQueryEngine.class);
        logMapper = mock(InsightQueryLogMapper.class);
        sensitivityService = mock(SensitivityService.class);
        accessRequestService = mock(AccessRequestService.class);
        auditService = mock(AuditService.class);
        InsightProperties properties = new InsightProperties();
        properties.setDefaultDatasetId(4);
        properties.setMaxRows(200);
        service = new InsightQueryService(engine, properties, logMapper, sensitivityService,
                accessRequestService, auditService);
        WorkspaceContext.set(new Caller("usr_alice", "alice", WORKSPACE, false,
                Set.of("insight:ask")));
        when(engine.engineId()).thenReturn("native");
        when(engine.ask(anyString(), any(), any()))
                .thenReturn(new EngineAskResult("q1", "SUCCESS",
                        "SELECT phone, id_card FROM customer",
                        List.of(new InsightColumn("phone", "phone", "string"),
                                new InsightColumn("id_card", "id_card", "string")),
                        List.of(Map.of("phone", "13800000000", "id_card", "110101199001011234")),
                        "1 行", null, List.of("attr.customer.phone@v1#abc"), "fp123", List.of(), List.of()));
        when(sensitivityService.sensitiveColumnNames()).thenReturn(List.of(
                new SensitiveColumnView("phone", "PII", "L2"),
                new SensitiveColumnView("id_card", "PII", "L4")));
        when(accessRequestService.activeGrants(WORKSPACE, "alice")).thenReturn(List.of(
                new GrantCoverage("agrant_1", "PII", "L3", Instant.now().plusSeconds(3600))));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("PII/L3 grant 放行 L2，L4 仍脱敏，并在历史/审计中记录 grant 与审计指纹")
    void grantCoversOnlyWithinLevelAndCategory() {
        InsightQueryService.Answer answer = service.ask(
                new InsightQueryService.AskRequest("查客户联系方式和证件号", 4L, 5));

        assertThat(answer.maskedColumns()).contains("id_card");
        assertThat(answer.maskedColumns()).doesNotContain("phone");
        assertThat(answer.grantIds()).containsExactly("agrant_1");
        assertThat(answer.auditFingerprint()).isEqualTo("fp123");

        ArgumentCaptor<InsightQueryLog> logCaptor = ArgumentCaptor.forClass(InsightQueryLog.class);
        verify(logMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getMaskedColumns()).contains("id_card");
        assertThat(logCaptor.getValue().getMaskedColumns()).doesNotContain("phone");
        assertThat(logCaptor.getValue().getGrantIds()).isEqualTo("agrant_1");

        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().detail()).contains("grants=[agrant_1]");
        assertThat(auditCaptor.getValue().detail()).contains("fingerprint=fp123");
    }

    @Test
    @DisplayName("“这个数据域有哪些表”走语义主题元数据回答，不触发 SQL 执行")
    void answersCatalogQuestionFromDatasetDomain() {
        when(engine.datasets()).thenReturn(List.of(
                new DatasetOption(4, "销售数据集", "销售分析"),
                new DatasetOption(5, "订单明细数据集", "销售分析"),
                new DatasetOption(36, "销售汇总数据集", "销售分析-StarRocks")));

        InsightQueryService.Answer answer = service.ask(
                new InsightQueryService.AskRequest("这个数据域有哪些表", 4L, 5));

        assertThat(answer.state()).isEqualTo("METADATA");
        assertThat(answer.rowCount()).isEqualTo(2);
        assertThat(answer.columns()).extracting(InsightColumn::bizName)
                .containsExactly("domain", "dataset", "datasetId");
        assertThat(answer.summary()).contains("销售分析");
        verify(engine, never()).ask(anyString(), any(), any());

        ArgumentCaptor<InsightQueryLog> logCaptor = ArgumentCaptor.forClass(InsightQueryLog.class);
        verify(logMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getQueryState()).isEqualTo("METADATA");
        assertThat(logCaptor.getValue().getSucceeded()).isTrue();
        assertThat(logCaptor.getValue().getRowCount()).isEqualTo(2);

        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().detail()).contains("metadata=true");
    }
}
