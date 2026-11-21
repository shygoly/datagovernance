package com.datagov.governance.service;

import com.datagov.common.error.BizException;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.entity.GovernanceEntities.OperationalReportDef;
import com.datagov.governance.entity.GovernanceEntities.OperationalReportRun;
import com.datagov.governance.entity.GovernanceEntities.OperationalReportSchedule;
import com.datagov.governance.entity.GovernanceEntities.OperationalReportVersion;
import com.datagov.governance.mapper.OperationalReportDefMapper;
import com.datagov.governance.mapper.OperationalReportRunMapper;
import com.datagov.governance.mapper.OperationalReportScheduleMapper;
import com.datagov.governance.mapper.OperationalReportVersionMapper;
import com.datagov.governance.service.OperationalReportService.PublishRequest;
import com.datagov.governance.service.OperationalReportService.ReportBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationalReportServiceTest {

    @Mock
    private OperationalReportDefMapper defMapper;
    @Mock
    private OperationalReportVersionMapper versionMapper;
    @Mock
    private OperationalReportRunMapper runMapper;
    @Mock
    private OperationalReportScheduleMapper scheduleMapper;

    private OperationalReportService service;

    @BeforeEach
    void setUp() {
        service = new OperationalReportService(defMapper, versionMapper, runMapper, scheduleMapper, new ObjectMapper());
        WorkspaceContext.set(new Caller("u1", "alice", "ws1", false, Set.of()));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("块类型与裸 SQL 校验；合法块可渲染 MD/CSV")
    void validatesAndRenders() {
        assertThatThrownBy(() -> service.validateBlocks(List.of(
                new ReportBlock("RAW_SQL", "x", Map.of("sql", "select 1")))))
                .isInstanceOf(BizException.class);

        assertThatThrownBy(() -> service.validateBlocks(List.of(
                new ReportBlock("STATIC_MD", "t", Map.of("markdown", "select * from patients")))))
                .isInstanceOf(BizException.class);

        List<ReportBlock> blocks = service.validateBlocks(List.of(
                new ReportBlock("STATIC_MD", "概述", Map.of("markdown", "本周 ICU 概况")),
                new ReportBlock("SMQ_BLOCK", "再入院", Map.of("metrics", List.of("metric.icu.readmit_rate")))));

        OperationalReportDef def = new OperationalReportDef();
        def.setCode("weekly.icu");
        def.setName("ICU 周报");
        OperationalReportVersion ver = new OperationalReportVersion();
        ver.setVersion(1);
        ver.setContentDigest("a".repeat(64));

        String md = service.renderMarkdown(def, ver, blocks, Map.of("week", "2026-W46"));
        assertThat(md).contains("# ICU 周报").contains("本周 ICU 概况").contains("SMQ_BLOCK");

        String csv = service.renderCsv(blocks, Map.of("week", "2026-W46"));
        assertThat(csv).contains("STATIC_MD").contains("SMQ_BLOCK");
    }

    @Test
    @DisplayName("首次发布写入 def + version")
    void publishCreatesVersion() {
        when(defMapper.selectByWorkspaceAndCode("ws1", "weekly.icu")).thenReturn(null);
        when(defMapper.insert(any(OperationalReportDef.class))).thenReturn(1);
        when(versionMapper.insert(any(OperationalReportVersion.class))).thenReturn(1);

        var view = service.publish(new PublishRequest(
                "weekly.icu",
                "ICU 周报",
                Map.of("week", Map.of("type", "string")),
                List.of(new ReportBlock("STATIC_MD", "概述", Map.of("markdown", "hello")))));

        assertThat(view.code()).isEqualTo("weekly.icu");
        assertThat(view.latestVersion()).isEqualTo(1);
        assertThat(view.contentDigest()).hasSize(64);

        ArgumentCaptor<OperationalReportDef> defCap = ArgumentCaptor.forClass(OperationalReportDef.class);
        verify(defMapper).insert(defCap.capture());
        assertThat(defCap.getValue().getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("fireDueSchedules：到点调度推进 nextFireAt")
    void fireDueAdvancesNextFire() {
        OperationalReportSchedule due = new OperationalReportSchedule();
        due.setId("opsch_1");
        due.setReportId("oprep_1");
        due.setWorkspaceId("ws1");
        due.setCronExpr("0 0 2 * * *");
        due.setEnabled(true);
        due.setNextFireAt(Instant.parse("2020-01-01T00:00:00Z"));
        due.setRecipientsJson("[]");

        OperationalReportDef def = new OperationalReportDef();
        def.setId("oprep_1");
        def.setWorkspaceId("ws1");
        def.setCode("weekly.icu");
        def.setName("ICU");
        def.setLatestVersion(1);
        def.setStatus("PUBLISHED");

        OperationalReportVersion ver = new OperationalReportVersion();
        ver.setId("opver_1");
        ver.setReportId("oprep_1");
        ver.setWorkspaceId("ws1");
        ver.setVersion(1);
        ver.setBlocksJson("[{\"type\":\"STATIC_MD\",\"title\":\"t\",\"payload\":{\"markdown\":\"x\"}}]");
        ver.setContentDigest("a".repeat(64));

        when(scheduleMapper.selectList(any())).thenReturn(List.of(due));
        when(defMapper.selectById("oprep_1")).thenReturn(def);
        when(versionMapper.selectByReportAndVersion("oprep_1", 1)).thenReturn(ver);
        when(runMapper.insert(org.mockito.ArgumentMatchers.<OperationalReportRun>any())).thenReturn(1);
        when(runMapper.updateById(org.mockito.ArgumentMatchers.<OperationalReportRun>any())).thenReturn(1);
        when(scheduleMapper.updateById(org.mockito.ArgumentMatchers.<OperationalReportSchedule>any())).thenReturn(1);

        int fired = service.fireDueSchedules(Instant.parse("2020-01-01T03:00:00Z"));
        assertThat(fired).isEqualTo(1);

        ArgumentCaptor<OperationalReportSchedule> cap = ArgumentCaptor.forClass(OperationalReportSchedule.class);
        verify(scheduleMapper).updateById(cap.capture());
        assertThat(cap.getValue().getLastFireAt()).isNotNull();
        assertThat(cap.getValue().getNextFireAt()).isAfter(Instant.parse("2020-01-01T03:00:00Z"));
    }
}
