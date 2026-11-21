package com.datagov.governance.service;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.entity.GovernedFact;
import com.datagov.governance.service.AuditService.AuditEntry;
import com.datagov.governance.service.GovernedFactService.ProposeFactCommand;
import com.datagov.metadata.event.DatasetProfileCompletedEvent;
import com.datagov.metadata.event.DatasetProfileCompletedEvent.AnomalyEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确定性证据驱动的受控自演进服务（FR-04 / governed-agent-evolution-mvp）。
 *
 * <p>严格遵循 CONSTITUTION C1 / C5 / C9：
 * <ol>
 *   <li>C1 解耦：仅订阅 {@link DatasetProfileCompletedEvent}，不依赖 {@code dg-control} 也不发 Command；</li>
 *   <li>C9 暂存隔离：仅写入 {@code PROPOSED} 候选事实与 {@code knowledge/research/} 暂存草稿，物理禁止直写 {@code articles/}；</li>
 *   <li>C9 驳回防重提：提提案前检索 {@code gv_governed_fact(state=REJECTED)} 与 {@code maintenance-log.md}，命中指纹即静默抑制；</li>
 *   <li>C5 隐私隔离：仅消费聚合统计证据与规则草案，严禁夹带任何 L3/L4 原始明细行。</li>
 * </ol>
 */
@Service
public class EvolutionProposalService {

    private static final Logger log = LoggerFactory.getLogger(EvolutionProposalService.class);

    private final GovernedFactService factService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** 可选：Staging 研究目录（默认关闭写盘，仅在显式配置或测试注入时写入 research/ 草稿，严禁指向 articles/）。 */
    @Value("${dg.evolution.research-dir:}")
    private String researchDir;

    /** 可选：审校裁决账本路径（knowledge/maintenance-log.md），用于驳回防重提检索与追加。 */
    @Value("${dg.evolution.maintenance-log-path:}")
    private String maintenanceLogPath;

    public EvolutionProposalService(GovernedFactService factService,
                                    AuditService auditService,
                                    ObjectMapper objectMapper) {
        this.factService = factService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** 供测试注入隔离的 Staging 目录与账本路径（物理禁止指向 articles/）。 */
    public void configureStagingPaths(String researchDir, String maintenanceLogPath) {
        if (researchDir != null && researchDir.contains("articles")) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "违反 C9：Curator 仅具 knowledge/research/ Staging 写权限，物理禁写 articles/");
        }
        this.researchDir = researchDir == null ? "" : researchDir;
        this.maintenanceLogPath = maintenanceLogPath == null ? "" : maintenanceLogPath;
    }

    public record CreateEvolutionProposalRequest(
            String assetRef,
            String factType,
            String title,
            String contentJson,
            String feedbackSource,
            String evidenceRef,
            String patternFingerprint,
            Double confidence,
            String supersedesId
    ) {
    }

    public record EvolutionProposalResult(
            boolean suppressed,
            String suppressionReason,
            GovernedFact proposal,
            String researchDraftPath
    ) {
    }

    /**
     * 监听确定性质量剖析完成事件：若检出异常且未命中历史驳回指纹，自动沉淀 Staging 提案。
     */
    @EventListener
    public void onDatasetProfileCompleted(DatasetProfileCompletedEvent event) {
        if (event == null || !"ANOMALIES_DETECTED".equals(event.verdict())) {
            return;
        }
        if (event.anomalies() == null || event.anomalies().isEmpty() || blank(event.patternFingerprint())) {
            return;
        }
        try {
            handleProfileAnomaly(event);
        } catch (Exception ex) {
            // 演进链路与确定性主剖析流严格解耦（C10），异步沉淀失败不阻断剖析主流程
            log.warn("确定性剖析事件触发受控演进提案失败（已隔离）: runId={}, err={}",
                    event.profileRunId(), ex.getMessage());
        }
    }

    public EvolutionProposalResult handleProfileAnomaly(DatasetProfileCompletedEvent event) {
        String workspaceId = event.workspaceId();
        String fingerprint = event.patternFingerprint();

        // 1. 驳回防重提负反馈检查（DB + maintenance-log.md）
        if (isPatternSuppressed(workspaceId, fingerprint)) {
            auditService.record(new AuditEntry(
                    AuditService.CREATE, "EVOLUTION_PROPOSAL", fingerprint,
                    event.datasetName(), "GOVERNANCE", true, null,
                    "命中历史驳回指纹，已抑制重复演进提案（pattern=" + fingerprint + "）",
                    "evolution-suppressed"));
            return new EvolutionProposalResult(true, "MATCHED_REJECTION_LEDGER", null, null);
        }

        // 2. 已有待审或已通过提案时同样跳过，防止重复刷屏
        if (factService.hasActiveProposalForPattern(workspaceId, fingerprint)) {
            return new EvolutionProposalResult(true, "ACTIVE_PROPOSAL_EXISTS", null, null);
        }

        // 3. 构造紧凑增量 Delta（仅聚合统计与候选 RuleSpec，零 L3/L4 明细）
        String assetRef = "dataset:" + event.datasetId();
        String title = "质量剖析演进提案：" + event.datasetName()
                + "（" + event.anomalies().size() + " 项异常）";
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("profileRunId", event.profileRunId());
        delta.put("datasetId", event.datasetId());
        delta.put("datasetName", event.datasetName());
        delta.put("totalRows", event.totalRows());
        delta.put("patternFingerprint", fingerprint);
        delta.put("standardRefs", event.standardRefs());
        delta.put("anomalies", event.anomalies().stream().map(this::toDeltaItem).toList());
        delta.put("reproducibilityFingerprint", event.reproducibilityFingerprint());

        String contentJson = toJson(delta);
        GovernedFact proposed = factService.propose(new ProposeFactCommand(
                assetRef,
                "quality_convention",
                title,
                contentJson,
                "execution_event",
                "EXECUTION_EVENT",
                "profile-run:" + event.profileRunId(),
                0.85d,
                null,
                Instant.now()
        ), fingerprint);

        String draftPath = writeStagingResearchDraft(event, proposed);
        return new EvolutionProposalResult(false, null, proposed, draftPath);
    }

    public List<GovernedFact> listProposals(String state, String assetRef) {
        String targetState = blank(state) ? GovernedFactService.STATE_PROPOSED : state.trim().toUpperCase();
        return factService.query(assetRef, null, targetState, null);
    }

    public EvolutionProposalResult createProposal(CreateEvolutionProposalRequest req) {
        Caller caller = WorkspaceContext.require();
        if (req == null) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "演进提案请求体必填");
        }
        if (!blank(req.patternFingerprint()) && isPatternSuppressed(caller.workspaceId(), req.patternFingerprint())) {
            return new EvolutionProposalResult(true, "MATCHED_REJECTION_LEDGER", null, null);
        }
        GovernedFact fact = factService.propose(new ProposeFactCommand(
                req.assetRef(),
                blank(req.factType()) ? "quality_convention" : req.factType(),
                req.title(),
                req.contentJson(),
                "attribution",
                blank(req.feedbackSource()) ? "EXECUTION_EVENT" : req.feedbackSource(),
                req.evidenceRef(),
                req.confidence(),
                req.supersedesId(),
                Instant.now()
        ), req.patternFingerprint());
        return new EvolutionProposalResult(false, null, fact, null);
    }

    public GovernedFact approveProposal(String id, String note) {
        return factService.approve(id, note);
    }

    public GovernedFact rejectProposal(String id, String reason) {
        GovernedFact rejected = factService.reject(id, reason);
        appendMaintenanceLogRejection(rejected, reason);
        return rejected;
    }

    public GovernedFact rollbackProposal(String id, String reason) {
        return factService.rollback(id, reason);
    }

    public boolean isPatternSuppressed(String workspaceId, String patternFingerprint) {
        if (blank(patternFingerprint)) {
            return false;
        }
        if (factService.isPatternRejected(workspaceId, patternFingerprint)) {
            return true;
        }
        return containsInMaintenanceLog(patternFingerprint.trim());
    }

    // ── Staging 草稿与驳回账本（严禁触碰 articles/） ─────────────────────

    private String writeStagingResearchDraft(DatasetProfileCompletedEvent event, GovernedFact proposed) {
        if (blank(researchDir)) {
            return null;
        }
        try {
            Path dir = Path.of(researchDir).toAbsolutePath().normalize();
            if (dir.toString().contains("articles")) {
                throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                        "违反 C9：禁止向 knowledge/articles/ 写入自动草稿");
            }
            Files.createDirectories(dir);
            String safeFp = event.patternFingerprint().replaceAll("[^a-zA-Z0-9_-]", "");
            Path file = dir.resolve("auto-" + safeFp + ".md").normalize();
            if (!file.startsWith(dir)) {
                throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "非法草稿路径");
            }
            StringBuilder md = new StringBuilder();
            md.append("# Staging 演进草稿：").append(event.datasetName()).append("\n\n");
            md.append("- **Proposal ID**: `").append(proposed.getId()).append("`\n");
            md.append("- **Pattern Fingerprint**: `").append(event.patternFingerprint()).append("`\n");
            md.append("- **Profile Run**: `").append(event.profileRunId()).append("`\n");
            md.append("- **Total Rows**: ").append(event.totalRows()).append("\n");
            md.append("- **State**: `PROPOSED`（仅 Staging 暂存，须经人工审校与 `pass^3` 后方可晋升）\n\n");
            md.append("## 检出异常（聚合统计证据，零 L3/L4 明细）\n\n");
            for (AnomalyEvidence a : event.anomalies()) {
                md.append("- [").append(a.dimension()).append("] `").append(a.columnName()).append("`: ")
                        .append(a.summary()).append("\n");
            }
            Files.writeString(file, md.toString(), StandardCharsets.UTF_8);
            return file.toString();
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("写入 Staging 研究草稿失败: {}", ex.getMessage());
            return null;
        }
    }

    private boolean containsInMaintenanceLog(String patternFingerprint) {
        if (blank(maintenanceLogPath)) {
            return false;
        }
        try {
            Path path = Path.of(maintenanceLogPath).toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                return false;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return content.contains(patternFingerprint) && content.contains("REJECTED");
        } catch (Exception ex) {
            log.debug("读取 maintenance-log.md 跳过: {}", ex.getMessage());
            return false;
        }
    }

    private void appendMaintenanceLogRejection(GovernedFact rejected, String reason) {
        if (blank(maintenanceLogPath)) {
            return;
        }
        try {
            Path path = Path.of(maintenanceLogPath).toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                return;
            }
            String date = LocalDate.now(ZoneOffset.UTC).toString();
            String fp = blank(rejected.getPatternFingerprint()) ? rejected.getId() : rejected.getPatternFingerprint();
            String safeTitle = (rejected.getTitle() == null ? "" : rejected.getTitle()).replace("|", "/");
            String safeReason = (reason == null ? "" : reason).replace("|", "/").replace("\n", " ");
            String row = "| " + date + " | " + safeTitle + " (`" + fp + "`) | **REJECTED** | " + safeReason + " |\n";
            Files.writeString(path, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (Exception ex) {
            log.warn("追加 maintenance-log.md 驳回记录失败: {}", ex.getMessage());
        }
    }

    private Map<String, Object> toDeltaItem(AnomalyEvidence a) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("dimension", a.dimension());
        item.put("column", a.columnName());
        item.put("violationCount", a.violationCount());
        item.put("violationRatio", a.violationRatio());
        item.put("standardRef", a.standardRef());
        item.put("summary", a.summary());
        item.put("suggestedRuleSpec", a.suggestedRuleSpec());
        return item;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "序列化演进提案失败", ex.getMessage(), ex);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
