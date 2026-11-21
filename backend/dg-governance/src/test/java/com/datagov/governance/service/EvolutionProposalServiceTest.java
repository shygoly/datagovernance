package com.datagov.governance.service;

import com.datagov.common.error.BizException;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.entity.GovernedFact;
import com.datagov.governance.mapper.GovernedFactMapper;
import com.datagov.governance.service.EvolutionProposalService.EvolutionProposalResult;
import com.datagov.metadata.event.DatasetProfileCompletedEvent;
import com.datagov.metadata.event.DatasetProfileCompletedEvent.AnomalyEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvolutionProposalServiceTest {

    @TempDir
    Path tempDir;

    private GovernedFactMapper factMapper;
    private GovernedFactService factService;
    private AuditService auditService;
    private EvolutionProposalService service;
    private final List<GovernedFact> db = new ArrayList<>();

    @BeforeEach
    void setUp() {
        db.clear();
        factMapper = mock(GovernedFactMapper.class);
        auditService = mock(AuditService.class);
        factService = new GovernedFactService(factMapper, mock(AnalysisReportService.class), auditService);
        service = new EvolutionProposalService(factService, auditService, new ObjectMapper());

        when(factMapper.insert(any(GovernedFact.class))).thenAnswer(inv -> {
            GovernedFact f = inv.getArgument(0);
            db.add(f);
            return 1;
        });
        when(factMapper.selectById(any())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return db.stream().filter(f -> id.equals(f.getId())).findFirst().orElse(null);
        });
        when(factMapper.updateById(any(GovernedFact.class))).thenAnswer(inv -> 1);

        WorkspaceContext.set(new Caller("usr_gov", "reviewer", "ws_1", false, Set.of("governance:knowledge:review")));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("C9 Scenario 3：剖析异常生成 Staging PROPOSED 提案 + research/ 草稿，驳回后写入账本并抑制重复提案")
    void profileAnomalyStagesProposalAndRejectionSuppressesResubmission() throws Exception {
        Path researchDir = tempDir.resolve("research");
        Path maintenanceLog = tempDir.resolve("maintenance-log.md");
        Files.writeString(maintenanceLog, "| 日期 | 提案 | 裁决 | 理由 |\n|---|---|---|---|\n");
        service.configureStagingPaths(researchDir.toString(), maintenanceLog.toString());

        when(factMapper.selectCount(any())).thenReturn(0L);

        DatasetProfileCompletedEvent event = new DatasetProfileCompletedEvent(
                "dpr_1", "ws_1", "cd_1", "lab_orders", "public.lab_orders",
                "ANOMALIES_DETECTED", 1000L, false, "pat_outlier_lab_value_001",
                List.of(new AnomalyEvidence(
                        "THRESHOLD_OUTLIER", "lab_value", 47L, 0.047d,
                        "STD_LAB_RANGE_V1", "字段 lab_value 检出 47 条离群极值",
                        Map.of("ruleType", "THRESHOLD_RANGE", "column", "lab_value"))),
                List.of("STD_LAB_RANGE_V1"),
                "{\"schemaVersion\":1}",
                "usr_alice",
                Instant.now()
        );

        // 1. 首次剖析异常 -> 写入 Staging PROPOSED 与 research/ 草稿
        EvolutionProposalResult first = service.handleProfileAnomaly(event);
        assertThat(first.suppressed()).isFalse();
        assertThat(first.proposal()).isNotNull();
        assertThat(first.proposal().getState()).isEqualTo(GovernedFactService.STATE_PROPOSED);
        assertThat(first.proposal().getPatternFingerprint()).isEqualTo("pat_outlier_lab_value_001");
        assertThat(first.researchDraftPath()).isNotNull();
        assertThat(Files.exists(Path.of(first.researchDraftPath()))).isTrue();
        assertThat(Files.readString(Path.of(first.researchDraftPath())))
                .contains("pat_outlier_lab_value_001")
                .contains("PROPOSED");

        // 2. 治理员驳回 -> 写入 rejectionReason 并追加 maintenance-log.md
        GovernedFact rejected = service.rejectProposal(first.proposal().getId(), "历史实验批次特殊极值，无需调整标准");
        assertThat(rejected.getState()).isEqualTo(GovernedFactService.STATE_REJECTED);
        assertThat(rejected.getRejectionReason()).contains("无需调整标准");
        assertThat(Files.readString(maintenanceLog))
                .contains("pat_outlier_lab_value_001")
                .contains("REJECTED");

        // 3. 后续相同异常再次触发 -> 命中驳回账本被抑制，不再生成重复提案
        EvolutionProposalResult second = service.handleProfileAnomaly(event);
        assertThat(second.suppressed()).isTrue();
        assertThat(second.suppressionReason()).isEqualTo("MATCHED_REJECTION_LEDGER");
        assertThat(second.proposal()).isNull();
        assertThat(db).hasSize(1);
    }

    @Test
    @DisplayName("C9：Curator 物理禁止指向 knowledge/articles/ 权威目录")
    void curatorForbidsArticlesDirectory() {
        assertThatThrownBy(() -> service.configureStagingPaths("/repo/knowledge/articles", ""))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("articles/");
    }
}
