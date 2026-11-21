package com.datagov.governance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.domain.ReportConclusion;
import com.datagov.governance.domain.ReportState;
import com.datagov.governance.entity.GovernedFact;
import com.datagov.governance.mapper.GovernedFactMapper;
import com.datagov.governance.service.AnalysisReportService.LatestView;
import com.datagov.governance.service.AnalysisReportService.ReportView;
import com.datagov.governance.service.AuditService.AuditEntry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 双时间态治理事实与受控 Learn 闭环服务（Phase C / c2，闭合 OWASP ASI06 与 CONSTITUTION C9）。
 *
 * <p>强制四项工程约束：
 * <ol>
 *   <li>外部反馈前置条件：无可靠 {@code feedbackSource} 或 {@code evidenceRef} 一律拒收；</li>
 *   <li>Staging 隔离：所有新提炼事实只能进入 {@code PROPOSED}，绝不允许直接覆写权威层；</li>
 *   <li>双时间态演进：晋升 {@code APPROVED} 时为被取代旧版写 {@code validTo} 并转 {@code SUPERSEDED}，支持 {@code asOf} 历史回溯；</li>
 *   <li>可审计回滚：已晋升条目发生污染时，{@code rollback} 一键转 {@code ROLLED_BACK} 并恢复前驱版本。</li>
 * </ol>
 */
@Service
public class GovernedFactService {

    public static final String STATE_PROPOSED = "PROPOSED";
    public static final String STATE_APPROVED = "APPROVED";
    public static final String STATE_REJECTED = "REJECTED";
    public static final String STATE_SUPERSEDED = "SUPERSEDED";
    public static final String STATE_ROLLED_BACK = "ROLLED_BACK";

    private static final Set<String> ALLOWED_FACT_TYPES = Set.of(
            "structure", "glossary", "quality_convention", "case", "lineage", "classification");

    private static final Set<String> ALLOWED_FEEDBACK_SOURCES = Set.of(
            "ANALYSIS_REPORT_CONFIRMED", "SQL_VERIFIED", "HUMAN_CORRECTION",
            "TEST_PASSED", "EXECUTION_EVENT");

    /** 兜底拦截未脱敏手机号与 18 位身份证号，防止 L3 明文混入知识库（C5/C9）。 */
    private static final Pattern RAW_PHONE_RE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern RAW_IDCARD_RE = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");

    private final GovernedFactMapper mapper;
    private final AnalysisReportService reportService;
    private final AuditService auditService;

    public GovernedFactService(GovernedFactMapper mapper,
                               AnalysisReportService reportService,
                               AuditService auditService) {
        this.mapper = mapper;
        this.reportService = reportService;
        this.auditService = auditService;
    }

    public record ProposeFactCommand(
            String assetRef,
            String factType,
            String title,
            String contentJson,
            String source,
            String feedbackSource,
            String evidenceRef,
            Double confidence,
            String supersedesId,
            Instant validFrom
    ) {
    }

    public GovernedFact propose(ProposeFactCommand cmd) {
        return propose(cmd, null);
    }

    public GovernedFact propose(ProposeFactCommand cmd, String patternFingerprint) {
        Caller caller = WorkspaceContext.require();
        if (cmd == null) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "提案内容必填");
        }
        String assetRef = requireNonBlank(cmd.assetRef(), "assetRef 必填");
        String factType = requireNonBlank(cmd.factType(), "factType 必填").toLowerCase();
        if (!ALLOWED_FACT_TYPES.contains(factType)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "不支持的 factType: " + factType);
        }
        String title = requireNonBlank(cmd.title(), "title 必填");
        String contentJson = requireNonBlank(cmd.contentJson(), "contentJson 必填");
        if (RAW_PHONE_RE.matcher(contentJson).find() || RAW_IDCARD_RE.matcher(contentJson).find()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "检测到疑似未脱敏 L3 敏感信息（手机号/身份证号），禁止写入治理知识层");
        }
        String feedbackSource = requireNonBlank(cmd.feedbackSource(),
                "违反 C9：缺少可靠外部反馈信号 feedbackSource，禁止对无验证闲聊触发沉淀").toUpperCase();
        if (!ALLOWED_FEEDBACK_SOURCES.contains(feedbackSource)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED,
                    "不支持的外部反馈信号 feedbackSource: " + feedbackSource);
        }
        String evidenceRef = requireNonBlank(cmd.evidenceRef(),
                "违反 C9：必须附带可寻址的证据引用 evidenceRef");

        int version = 1;
        if (!blank(cmd.supersedesId())) {
            GovernedFact prior = loadInWorkspace(cmd.supersedesId().trim(), caller.workspaceId());
            if (!STATE_APPROVED.equals(prior.getState())) {
                throw new BizException(ErrorCode.GOV_FACT_STATE,
                        "只能取代当前为 APPROVED 状态的权威事实，当前状态: " + prior.getState());
            }
            version = (prior.getVersion() == null ? 1 : prior.getVersion()) + 1;
        }

        Instant now = Instant.now();
        GovernedFact entity = new GovernedFact();
        entity.setId(Ids.of("gvf"));
        entity.setWorkspaceId(caller.workspaceId());
        entity.setAssetRef(assetRef);
        entity.setFactType(factType);
        entity.setTitle(title);
        entity.setContentJson(contentJson);
        entity.setSource(blank(cmd.source()) ? "attribution" : cmd.source().trim());
        entity.setFeedbackSource(feedbackSource);
        entity.setEvidenceRef(evidenceRef);
        entity.setConfidence(cmd.confidence() == null ? 1.0d : Math.max(0.0d, Math.min(1.0d, cmd.confidence())));
        entity.setVersion(version);
        entity.setSupersedesId(blank(cmd.supersedesId()) ? null : cmd.supersedesId().trim());
        entity.setState(STATE_PROPOSED);
        entity.setPatternFingerprint(blank(patternFingerprint) ? null : patternFingerprint.trim());
        entity.setValidFrom(cmd.validFrom() == null ? now : cmd.validFrom());
        entity.setValidTo(null);
        entity.setTxRecordedAt(now);
        entity.setCreatedBy(caller.userId());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);

        auditService.record(new AuditEntry(
                AuditService.CREATE, "GOVERNED_FACT", entity.getId(), title, "GOVERNANCE",
                true, null, "写入暂存区 PROPOSED（feedback=" + feedbackSource + "）", "learn"));
        return entity;
    }

    /** 检索某异常模式指纹是否已被人工驳回（驳回防重提负反馈抑制，C9）。 */
    public boolean isPatternRejected(String workspaceId, String patternFingerprint) {
        if (blank(workspaceId) || blank(patternFingerprint)) {
            return false;
        }
        Long count = mapper.selectCount(new LambdaQueryWrapper<GovernedFact>()
                .eq(GovernedFact::getWorkspaceId, workspaceId)
                .eq(GovernedFact::getPatternFingerprint, patternFingerprint.trim())
                .eq(GovernedFact::getState, STATE_REJECTED));
        return count != null && count > 0;
    }

    /** 检索某异常模式指纹是否已存在待审或已通过提案，避免重复刷屏。 */
    public boolean hasActiveProposalForPattern(String workspaceId, String patternFingerprint) {
        if (blank(workspaceId) || blank(patternFingerprint)) {
            return false;
        }
        Long count = mapper.selectCount(new LambdaQueryWrapper<GovernedFact>()
                .eq(GovernedFact::getWorkspaceId, workspaceId)
                .eq(GovernedFact::getPatternFingerprint, patternFingerprint.trim())
                .in(GovernedFact::getState, List.of(STATE_PROPOSED, STATE_APPROVED)));
        return count != null && count > 0;
    }

    /**
     * Reflector 桥接：从已发布且结论为 CONFIRMED 的 AnalysisReport 提取候选 case 事实入暂存区。
     * 严禁从 INCONCLUSIVE 或 RETRACTED 报告中沉淀结论（CONSTITUTION C8 / C9）。
     */
    public GovernedFact distillFromAnalysisReport(String reportId, String assetRef) {
        ReportView view = reportService.get(reportId);
        if (view == null || view.state() != ReportState.PUBLISHED) {
            throw new BizException(ErrorCode.GOV_FACT_STATE,
                    "仅允许从 PUBLISHED 状态分析报告提炼知识");
        }
        LatestView latest = view.latest();
        if (latest == null) {
            throw new BizException(ErrorCode.GOV_REPORT_NOT_FOUND, "未找到最新版本分析报告");
        }
        if (latest.conclusion() != ReportConclusion.CONFIRMED) {
            throw new BizException(ErrorCode.GOV_FACT_STATE,
                    "违反 C8/C9：分析报告结论为 " + latest.conclusion() + "，禁止沉淀为权威案例候选");
        }
        String targetAsset = blank(assetRef)
                ? view.subjectType().name() + ":" + view.subjectId()
                : assetRef.trim();
        String safeSummary = latest.summary().replace("\\", "\\\\").replace("\"", "\\\"");
        String contentJson = "{\"reportId\":\"" + view.id()
                + "\",\"version\":" + latest.version()
                + ",\"summary\":\"" + safeSummary + "\"}";
        return propose(new ProposeFactCommand(
                targetAsset,
                "case",
                "案例沉淀：" + view.title(),
                contentJson,
                "attribution",
                "ANALYSIS_REPORT_CONFIRMED",
                "analysis-report:" + view.id() + "#v" + latest.version(),
                0.95d,
                null,
                Instant.now()
        ));
    }

    public GovernedFact approve(String factId, String note) {
        Caller caller = WorkspaceContext.require();
        GovernedFact fact = loadInWorkspace(factId, caller.workspaceId());
        if (!STATE_PROPOSED.equals(fact.getState())) {
            throw new BizException(ErrorCode.GOV_FACT_STATE,
                    "只能晋升 PROPOSED 状态的事实，当前状态: " + fact.getState());
        }
        Instant now = Instant.now();
        if (!blank(fact.getSupersedesId())) {
            GovernedFact prior = loadInWorkspace(fact.getSupersedesId(), caller.workspaceId());
            if (STATE_APPROVED.equals(prior.getState())) {
                prior.setState(STATE_SUPERSEDED);
                prior.setValidTo(fact.getValidFrom() != null ? fact.getValidFrom() : now);
                prior.setUpdatedAt(now);
                mapper.updateById(prior);
            }
        }
        fact.setState(STATE_APPROVED);
        fact.setReviewedBy(caller.userId());
        fact.setReviewedAt(now);
        fact.setReviewNote(blank(note) ? "人工审校通过" : note.trim());
        fact.setUpdatedAt(now);
        mapper.updateById(fact);

        auditService.record(new AuditEntry(
                AuditService.UPDATE, "GOVERNED_FACT", fact.getId(), fact.getTitle(), "GOVERNANCE",
                true, null, "人工审校晋升 APPROVED：" + fact.getReviewNote(), "learn-review"));
        return fact;
    }

    public GovernedFact reject(String factId, String note) {
        Caller caller = WorkspaceContext.require();
        GovernedFact fact = loadInWorkspace(factId, caller.workspaceId());
        if (!STATE_PROPOSED.equals(fact.getState())) {
            throw new BizException(ErrorCode.GOV_FACT_STATE,
                    "只能拒绝 PROPOSED 状态的事实，当前状态: " + fact.getState());
        }
        String reason = requireNonBlank(note, "拒绝理由必填（写入 maintenance-log 防止重复提案）");
        Instant now = Instant.now();
        fact.setState(STATE_REJECTED);
        fact.setReviewedBy(caller.userId());
        fact.setReviewedAt(now);
        fact.setReviewNote(reason);
        fact.setRejectionReason(reason);
        fact.setUpdatedAt(now);
        mapper.updateById(fact);

        auditService.record(new AuditEntry(
                AuditService.UPDATE, "GOVERNED_FACT", fact.getId(), fact.getTitle(), "GOVERNANCE",
                true, null, "拒绝候选事实：" + reason, "learn-review"));
        return fact;
    }

    /**
     * 一键回滚（防 ASI06 记忆投毒）：将当前 APPROVED 事实置 ROLLED_BACK，并恢复前驱 SUPERSEDED 版本。
     */
    public GovernedFact rollback(String factId, String reason) {
        Caller caller = WorkspaceContext.require();
        GovernedFact fact = loadInWorkspace(factId, caller.workspaceId());
        if (!STATE_APPROVED.equals(fact.getState())) {
            throw new BizException(ErrorCode.GOV_FACT_STATE,
                    "只能回滚 APPROVED 状态的事实，当前状态: " + fact.getState());
        }
        String note = requireNonBlank(reason, "回滚原因必填");
        Instant now = Instant.now();
        fact.setState(STATE_ROLLED_BACK);
        fact.setValidTo(now);
        fact.setReviewedBy(caller.userId());
        fact.setReviewedAt(now);
        fact.setReviewNote("ROLLBACK: " + note);
        fact.setUpdatedAt(now);
        mapper.updateById(fact);

        if (!blank(fact.getSupersedesId())) {
            GovernedFact prior = mapper.selectById(fact.getSupersedesId());
            if (prior != null && caller.workspaceId().equals(prior.getWorkspaceId())
                    && STATE_SUPERSEDED.equals(prior.getState())) {
                prior.setState(STATE_APPROVED);
                prior.setValidTo(null);
                prior.setUpdatedAt(now);
                mapper.updateById(prior);
            }
        }

        auditService.record(new AuditEntry(
                AuditService.UPDATE, "GOVERNED_FACT", fact.getId(), fact.getTitle(), "GOVERNANCE",
                true, null, "回滚已发布事实并恢复前驱版本：" + note, "learn-rollback"));
        return fact;
    }

    /**
     * 检索治理事实：
     * - 未传 {@code asOf} 时返回指定状态（默认 APPROVED）的当前有效事实；
     * - 传入 {@code asOf} 时执行双时间态时间旅行查询（valid_from <= asOf < valid_to 且非 REJECTED/ROLLED_BACK/PROPOSED）。
     */
    public List<GovernedFact> query(String assetRef, String factType, String state, Instant asOf) {
        Caller caller = WorkspaceContext.require();
        LambdaQueryWrapper<GovernedFact> q = new LambdaQueryWrapper<GovernedFact>()
                .eq(GovernedFact::getWorkspaceId, caller.workspaceId())
                .orderByDesc(GovernedFact::getValidFrom)
                .last("LIMIT 100");
        if (!blank(assetRef)) {
            q.eq(GovernedFact::getAssetRef, assetRef.trim());
        }
        if (!blank(factType)) {
            q.eq(GovernedFact::getFactType, factType.trim().toLowerCase());
        }
        if (asOf != null) {
            q.in(GovernedFact::getState, List.of(STATE_APPROVED, STATE_SUPERSEDED))
                    .le(GovernedFact::getValidFrom, asOf)
                    .and(w -> w.isNull(GovernedFact::getValidTo).or().gt(GovernedFact::getValidTo, asOf));
        } else {
            String targetState = blank(state) ? STATE_APPROVED : state.trim().toUpperCase();
            q.eq(GovernedFact::getState, targetState);
        }
        return mapper.selectList(q);
    }

    private GovernedFact loadInWorkspace(String id, String workspaceId) {
        GovernedFact fact = mapper.selectById(id);
        if (fact == null || !workspaceId.equals(fact.getWorkspaceId())) {
            throw new BizException(ErrorCode.GOV_FACT_NOT_FOUND, "治理事实不存在");
        }
        return fact;
    }

    private static String requireNonBlank(String value, String message) {
        if (blank(value)) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, message);
        }
        return value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
