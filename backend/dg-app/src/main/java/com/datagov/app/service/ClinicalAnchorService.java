package com.datagov.app.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.clinical.ClinicalOperator.OperatorRequest;
import com.datagov.common.clinical.ClinicalOperator.OperatorResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.entity.ClinicalQcFinding;
import com.datagov.governance.mapper.ClinicalQcFindingMapper;
import com.datagov.metadata.entity.AnchorAlignmentHit;
import com.datagov.metadata.entity.AnchorAlignmentRun;
import com.datagov.metadata.entity.AnchorEventSpec;
import com.datagov.metadata.mapper.AnchorAlignmentHitMapper;
import com.datagov.metadata.mapper.AnchorAlignmentRunMapper;
import com.datagov.metadata.mapper.AnchorEventSpecMapper;
import com.datagov.runtime.clinical.AlignToAnchorV1Operator;
import com.datagov.runtime.clinical.ClinicalStatsRuntime;
import com.datagov.runtime.clinical.HccClinicalQcV1Operator;
import com.datagov.runtime.clinical.SplitTreatmentCyclesV1Operator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T₀ 锚点规格、对齐/切分运行与 HCC 质控编排（装配层；算子在 Runtime）。
 */
@Service
public class ClinicalAnchorService {

    public static final String STD_CHANCE_HCC_QC_V1 = HccClinicalQcV1Operator.STANDARD_REF;

    private final AnchorEventSpecMapper specMapper;
    private final AnchorAlignmentRunMapper runMapper;
    private final AnchorAlignmentHitMapper hitMapper;
    private final ClinicalQcFindingMapper findingMapper;
    private final ClinicalStatsRuntime statsRuntime;
    private final ObjectMapper objectMapper;

    public ClinicalAnchorService(AnchorEventSpecMapper specMapper,
                                 AnchorAlignmentRunMapper runMapper,
                                 AnchorAlignmentHitMapper hitMapper,
                                 ClinicalQcFindingMapper findingMapper,
                                 ClinicalStatsRuntime statsRuntime,
                                 ObjectMapper objectMapper) {
        this.specMapper = specMapper;
        this.runMapper = runMapper;
        this.hitMapper = hitMapper;
        this.findingMapper = findingMapper;
        this.statsRuntime = statsRuntime;
        this.objectMapper = objectMapper;
    }

    public record SpecRequest(
            String code,
            String name,
            String anchorType,
            Map<String, Object> binding,
            Map<String, Object> windowConfig,
            String standardRef
    ) {
    }

    public record SpecView(
            String id,
            String code,
            String name,
            String anchorType,
            Map<String, Object> binding,
            Map<String, Object> windowConfig,
            String standardRef,
            String status
    ) {
    }

    public record RunRequest(
            String specId,
            List<Map<String, Object>> rows,
            Map<String, Object> params
    ) {
    }

    public record AlignmentRunView(
            String runId,
            String status,
            int anchorCount,
            int hitCount,
            int ambiguousCount,
            int missingCount,
            String operatorDigest,
            List<Map<String, Object>> hits
    ) {
    }

    public record QcRunView(
            String runId,
            String rulesetRef,
            int findingCount,
            List<Map<String, Object>> findings
    ) {
    }

    public record ResolveRequest(String note) {
    }

    @Transactional
    public SpecView upsertSpec(SpecRequest req) {
        Caller caller = WorkspaceContext.require();
        if (req == null || blank(req.code()) || blank(req.name()) || blank(req.anchorType())) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "code/name/anchorType 必填");
        }
        if (req.binding() == null || req.binding().isEmpty()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "binding 必填（逻辑表/语义列 URN，禁止物理表名）");
        }
        String ws = caller.workspaceId();
        AnchorEventSpec existing = specMapper.selectOne(new LambdaQueryWrapper<AnchorEventSpec>()
                .eq(AnchorEventSpec::getWorkspaceId, ws)
                .eq(AnchorEventSpec::getCode, req.code().trim()));
        Instant now = Instant.now();
        if (existing == null) {
            existing = new AnchorEventSpec();
            existing.setId(Ids.of("anch"));
            existing.setWorkspaceId(ws);
            existing.setCode(req.code().trim());
            existing.setCreatedBy(caller.userId());
            existing.setCreatedAt(now);
            existing.setStatus("ACTIVE");
        }
        existing.setName(req.name().trim());
        existing.setAnchorType(req.anchorType().trim());
        existing.setBindingJson(toJson(req.binding()));
        existing.setWindowConfigJson(toJson(req.windowConfig() == null ? Map.of() : req.windowConfig()));
        existing.setStandardRef(blank(req.standardRef()) ? STD_CHANCE_HCC_QC_V1 : req.standardRef().trim());
        existing.setUpdatedAt(now);
        if (specMapper.selectById(existing.getId()) == null) {
            specMapper.insert(existing);
        } else {
            specMapper.updateById(existing);
        }
        return toSpecView(existing);
    }

    public List<SpecView> listSpecs() {
        String ws = WorkspaceContext.requireWorkspaceId();
        return specMapper.selectList(new LambdaQueryWrapper<AnchorEventSpec>()
                        .eq(AnchorEventSpec::getWorkspaceId, ws)
                        .orderByDesc(AnchorEventSpec::getUpdatedAt))
                .stream()
                .map(this::toSpecView)
                .toList();
    }

    /** 确保工作区存在 STD_CHANCE_HCC_QC_V1 占位规格。 */
    @Transactional
    public SpecView ensureStandardStub() {
        return upsertSpec(new SpecRequest(
                "chance_hcc_t0_default",
                "CHANCE HCC 默认 T0 锚点（Standard 占位）",
                "INTERVENTIONAL_TACE",
                Map.of(
                        "anchorTsUrn", "semantic://attr.treatment.actual_exec_end_t0",
                        "patientRefUrn", "semantic://entity.patient.id",
                        "sessionSeqUrn", "semantic://attr.treatment.session_seq"),
                Map.of(
                        "BASELINE_IMAGING", Map.of("startDays", -30, "endDays", 0),
                        "FOLLOWUP", Map.of("startDays", 28, "endDays", 56)),
                STD_CHANCE_HCC_QC_V1));
    }

    @Transactional
    public AlignmentRunView splitAndAlign(RunRequest request) {
        Caller caller = WorkspaceContext.require();
        List<Map<String, Object>> rows = request == null || request.rows() == null ? List.of() : request.rows();
        Map<String, Object> params = request == null || request.params() == null ? Map.of() : request.params();

        // 1) 切分程次
        OperatorResult split = statsRuntime.execute(
                SplitTreatmentCyclesV1Operator.ID, SplitTreatmentCyclesV1Operator.VERSION,
                new OperatorRequest(caller.workspaceId(), extractAnchors(rows), params));

        // 2) 对齐：切分结果作为 ANCHOR + 原 CANDIDATE
        List<Map<String, Object>> alignRows = new ArrayList<>();
        for (Map<String, Object> a : split.rows()) {
            Map<String, Object> r = new LinkedHashMap<>(a);
            r.put("row_kind", "ANCHOR");
            alignRows.add(r);
        }
        for (Map<String, Object> row : rows) {
            if ("CANDIDATE".equalsIgnoreCase(String.valueOf(row.getOrDefault("row_kind", "")))) {
                alignRows.add(row);
            }
        }
        if (request != null && request.specId() != null && !request.specId().isBlank()) {
            AnchorEventSpec spec = requireSpec(request.specId(), caller.workspaceId());
            Map<String, Object> win = fromJsonMap(spec.getWindowConfigJson());
            if (!win.isEmpty()) {
                Map<String, Object> merged = new LinkedHashMap<>(params);
                merged.put("windows", win);
                params = merged;
            }
        }

        OperatorResult aligned = statsRuntime.execute(
                AlignToAnchorV1Operator.ID, AlignToAnchorV1Operator.VERSION,
                new OperatorRequest(caller.workspaceId(), alignRows, params));

        AnchorAlignmentRun run = new AnchorAlignmentRun();
        run.setId(Ids.of("aar"));
        run.setWorkspaceId(caller.workspaceId());
        run.setSpecId(request == null ? null : request.specId());
        run.setOperatorId(AlignToAnchorV1Operator.ID);
        run.setOperatorVersion(AlignToAnchorV1Operator.VERSION);
        run.setStatus("COMPLETED");
        run.setAnchorCount(split.rows().size());
        int matched = 0, amb = 0, miss = 0;
        for (Map<String, Object> h : aligned.rows()) {
            String st = String.valueOf(h.getOrDefault("status", ""));
            if ("MATCHED".equals(st)) {
                matched++;
            } else if ("AMBIGUOUS".equals(st)) {
                amb++;
            } else if ("MISSING".equals(st)) {
                miss++;
            }
        }
        run.setHitCount(matched);
        run.setAmbiguousCount(amb);
        run.setMissingCount(miss);
        run.setOperatorDigest(aligned.operatorDigest());
        run.setInputDigest(aligned.operatorDigest());
        run.setReproducibilityFingerprint(toJson(aligned.fingerprint()));
        run.setCreatedBy(caller.userId());
        run.setCreatedAt(Instant.now());
        runMapper.insert(run);

        for (Map<String, Object> h : aligned.rows()) {
            AnchorAlignmentHit hit = new AnchorAlignmentHit();
            hit.setId(Ids.of("aah"));
            hit.setWorkspaceId(caller.workspaceId());
            hit.setRunId(run.getId());
            hit.setPatientRef(str(h.get("patient_ref")));
            hit.setAnchorId(str(h.get("anchor_id")));
            hit.setSessionSeq(intVal(h.get("session_seq"), 1));
            hit.setRole(str(h.get("role")));
            hit.setWindowLabel(str(h.get("window_label")));
            hit.setCandidateRef(blank(str(h.get("candidate_ref"))) ? null : str(h.get("candidate_ref")));
            hit.setStatus(str(h.get("status")));
            hit.setDetailJson(toJson(h));
            hitMapper.insert(hit);
        }

        return new AlignmentRunView(
                run.getId(), run.getStatus(), run.getAnchorCount(), run.getHitCount(),
                run.getAmbiguousCount(), run.getMissingCount(), run.getOperatorDigest(),
                aligned.rows());
    }

    @Transactional
    public QcRunView runHccQc(RunRequest request) {
        Caller caller = WorkspaceContext.require();
        List<Map<String, Object>> rows = request == null || request.rows() == null ? List.of() : request.rows();
        OperatorResult result = statsRuntime.execute(
                HccClinicalQcV1Operator.ID, HccClinicalQcV1Operator.VERSION,
                new OperatorRequest(caller.workspaceId(), rows, request == null ? Map.of() : request.params()));

        String runId = Ids.of("qcr");
        for (Map<String, Object> f : result.rows()) {
            ClinicalQcFinding finding = new ClinicalQcFinding();
            finding.setId(Ids.of("qcf"));
            finding.setWorkspaceId(caller.workspaceId());
            finding.setRunId(runId);
            finding.setRulesetRef(STD_CHANCE_HCC_QC_V1);
            finding.setRuleId(str(f.get("rule_id")));
            finding.setSeverity(str(f.get("severity")));
            finding.setPatientRef(str(f.get("patient_ref")));
            finding.setAnchorRef(blank(str(f.get("anchor_ref"))) ? null : str(f.get("anchor_ref")));
            finding.setSessionSeq(f.get("session_seq") == null ? null : intVal(f.get("session_seq"), 0));
            finding.setMessage(str(f.get("message")));
            finding.setEvidenceJson(toJson(f.getOrDefault("evidence", Map.of())));
            finding.setStatus("OPEN");
            finding.setCreatedBy(caller.userId());
            finding.setCreatedAt(Instant.now());
            findingMapper.insert(finding);
        }
        return new QcRunView(runId, STD_CHANCE_HCC_QC_V1, result.rows().size(), result.rows());
    }

    public List<ClinicalQcFinding> listFindings(String status) {
        String ws = WorkspaceContext.requireWorkspaceId();
        LambdaQueryWrapper<ClinicalQcFinding> q = new LambdaQueryWrapper<ClinicalQcFinding>()
                .eq(ClinicalQcFinding::getWorkspaceId, ws)
                .orderByDesc(ClinicalQcFinding::getCreatedAt);
        if (!blank(status)) {
            q.eq(ClinicalQcFinding::getStatus, status.trim());
        }
        return findingMapper.selectList(q);
    }

    @Transactional
    public ClinicalQcFinding resolveFinding(String id, ResolveRequest request, boolean close) {
        Caller caller = WorkspaceContext.require();
        ClinicalQcFinding finding = findingMapper.selectById(id);
        if (finding == null || !caller.workspaceId().equals(finding.getWorkspaceId())) {
            throw new BizException(ErrorCode.SYS_NOT_FOUND, "质控发现不存在");
        }
        if ("Blocker".equalsIgnoreCase(finding.getSeverity()) && close
                && (request == null || blank(request.note()))) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "关闭 Blocker 必须提供宿主确认备注（C7）");
        }
        finding.setStatus(close ? "RESOLVED" : "REOPEN");
        finding.setResolutionNote(request == null ? null : request.note());
        finding.setResolvedBy(caller.userId());
        finding.setResolvedAt(Instant.now());
        findingMapper.updateById(finding);
        return finding;
    }

    /** 复检：对 OPEN 发现对应患者行再跑 QC（调用方传入刷新后的 rows）。 */
    @Transactional
    public QcRunView recheck(RunRequest request) {
        return runHccQc(request);
    }

    private AnchorEventSpec requireSpec(String id, String ws) {
        AnchorEventSpec spec = specMapper.selectById(id);
        if (spec == null || !ws.equals(spec.getWorkspaceId())) {
            throw new BizException(ErrorCode.SYS_NOT_FOUND, "锚点规格不存在");
        }
        return spec;
    }

    private SpecView toSpecView(AnchorEventSpec s) {
        return new SpecView(
                s.getId(), s.getCode(), s.getName(), s.getAnchorType(),
                fromJsonMap(s.getBindingJson()), fromJsonMap(s.getWindowConfigJson()),
                s.getStandardRef(), s.getStatus());
    }

    private static List<Map<String, Object>> extractAnchors(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String kind = String.valueOf(row.getOrDefault("row_kind", "ANCHOR"));
            if ("ANCHOR".equalsIgnoreCase(kind) || !row.containsKey("row_kind")) {
                if (row.get("anchor_ts") != null || row.get("actual_exec_end_t0") != null) {
                    Map<String, Object> a = new LinkedHashMap<>(row);
                    if (a.get("anchor_ts") == null) {
                        a.put("anchor_ts", a.get("actual_exec_end_t0"));
                    }
                    out.add(a);
                }
            }
        }
        return out;
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static int intVal(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(str(o));
        } catch (Exception e) {
            return fallback;
        }
    }
}
