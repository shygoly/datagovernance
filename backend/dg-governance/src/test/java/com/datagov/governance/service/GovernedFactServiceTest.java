package com.datagov.governance.service;

import com.datagov.common.error.BizException;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.domain.ReportConclusion;
import com.datagov.governance.domain.ReportState;
import com.datagov.governance.domain.ReportSubjectType;
import com.datagov.governance.entity.GovernedFact;
import com.datagov.governance.mapper.GovernedFactMapper;
import com.datagov.governance.service.AnalysisReportService.LatestView;
import com.datagov.governance.service.AnalysisReportService.ReportView;
import com.datagov.governance.service.GovernedFactService.ProposeFactCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernedFactServiceTest {

    private final Map<String, GovernedFact> store = new HashMap<>();
    private GovernedFactMapper mapper;
    private AnalysisReportService reportService;
    private GovernedFactService service;

    @BeforeEach
    void setUp() {
        store.clear();
        mapper = mock(GovernedFactMapper.class);
        doAnswer(inv -> {
            GovernedFact f = inv.getArgument(0);
            store.put(f.getId(), f);
            return 1;
        }).when(mapper).insert(any(GovernedFact.class));
        doAnswer(inv -> {
            GovernedFact f = inv.getArgument(0);
            store.put(f.getId(), f);
            return 1;
        }).when(mapper).updateById(any(GovernedFact.class));
        when(mapper.selectById(any())).thenAnswer(inv -> store.get(inv.getArgument(0)));

        reportService = mock(AnalysisReportService.class);
        service = new GovernedFactService(mapper, reportService, mock(AuditService.class));

        WorkspaceContext.set(new Caller("usr_1", "alice", "ws_1", false,
                Set.of("governance:knowledge:read", "governance:knowledge:review")));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("C9 / ASI06：缺少外部反馈信号或夹带 L3 手机号明文时拒绝入库")
    void proposeRejectsMissingFeedbackAndRawPii() {
        assertThatThrownBy(() -> service.propose(new ProposeFactCommand(
                "table:orders", "glossary", "口径", "{\"def\":\"sum\"}",
                "attribution", "", "ref:1", 1.0, null, null)))
                .isInstanceOf(BizException.class);

        assertThatThrownBy(() -> service.propose(new ProposeFactCommand(
                "table:patients", "case", "泄露样例", "{\"phone\":\"13812345678\"}",
                "attribution", "SQL_VERIFIED", "sql:hash1", 1.0, null, null)))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("A0.1 / A0.3：晋升取代旧版本写 validTo，回滚时一键恢复前驱权威版本")
    void bitemporalSupersedeAndRollbackRestoresPriorVersion() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-06-01T00:00:00Z");

        GovernedFact v1 = service.propose(new ProposeFactCommand(
                "metric:visit_count", "glossary", "门诊量口径 v1", "{\"rule\":\"status=DONE\"}",
                "human_register", "HUMAN_CORRECTION", "doc:v1", 1.0, null, t1));
        assertThat(v1.getState()).isEqualTo(GovernedFactService.STATE_PROPOSED);
        service.approve(v1.getId(), "首版确认");
        assertThat(store.get(v1.getId()).getState()).isEqualTo(GovernedFactService.STATE_APPROVED);

        GovernedFact v2 = service.propose(new ProposeFactCommand(
                "metric:visit_count", "glossary", "门诊量口径 v2", "{\"rule\":\"status IN (DONE,PAID)\"}",
                "human_register", "HUMAN_CORRECTION", "doc:v2", 1.0, v1.getId(), t2));
        assertThat(v2.getVersion()).isEqualTo(2);
        service.approve(v2.getId(), "口径升级");

        // v1 转 SUPERSEDED 且保留历史 validTo = t2
        assertThat(store.get(v1.getId()).getState()).isEqualTo(GovernedFactService.STATE_SUPERSEDED);
        assertThat(store.get(v1.getId()).getValidTo()).isEqualTo(t2);
        assertThat(store.get(v2.getId()).getState()).isEqualTo(GovernedFactService.STATE_APPROVED);

        // 回滚 v2 后，v2 转 ROLLED_BACK 且 v1 恢复 APPROVED + validTo=null
        service.rollback(v2.getId(), "发现统计重复，紧急回滚");
        assertThat(store.get(v2.getId()).getState()).isEqualTo(GovernedFactService.STATE_ROLLED_BACK);
        assertThat(store.get(v1.getId()).getState()).isEqualTo(GovernedFactService.STATE_APPROVED);
        assertThat(store.get(v1.getId()).getValidTo()).isNull();
    }

    @Test
    @DisplayName("C8 / C9：仅允许从 CONFIRMED 分析报告提炼案例，拒绝 INCONCLUSIVE 报告")
    void distillRejectsInconclusiveAndAcceptsConfirmedReport() {
        LatestView incLatest = new LatestView(
                "rv_1", 1, null, "CURRENT", "证据不足", ReportConclusion.INCONCLUSIVE,
                "日志截断", "L1", "digest", Instant.now(), Instant.now());
        ReportView incReport = new ReportView(
                "rep_inc", ReportSubjectType.EXECUTION, "exec_1", "任务失败分析",
                ReportState.PUBLISHED, 1, "corr_1", "usr_1", Instant.now(), null, null,
                incLatest, List.of());
        when(reportService.get("rep_inc")).thenReturn(incReport);

        assertThatThrownBy(() -> service.distillFromAnalysisReport("rep_inc", null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode().code())
                        .isEqualTo("GOV_FACT_STATE"));

        LatestView okLatest = new LatestView(
                "rv_2", 1, null, "CURRENT", "确认上游删列", ReportConclusion.CONFIRMED,
                null, "L1", "digest", Instant.now(), Instant.now());
        ReportView okReport = new ReportView(
                "rep_ok", ReportSubjectType.EXECUTION, "exec_2", "上游字段缺失根因",
                ReportState.PUBLISHED, 1, "corr_2", "usr_1", Instant.now(), null, null,
                okLatest, List.of());
        when(reportService.get("rep_ok")).thenReturn(okReport);

        GovernedFact proposed = service.distillFromAnalysisReport("rep_ok", null);
        assertThat(proposed.getState()).isEqualTo(GovernedFactService.STATE_PROPOSED);
        assertThat(proposed.getFactType()).isEqualTo("case");
        assertThat(proposed.getFeedbackSource()).isEqualTo("ANALYSIS_REPORT_CONFIRMED");
    }
}
