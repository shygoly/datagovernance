package com.datagov.governance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.entity.MappingProposal;
import com.datagov.governance.mapper.MappingProposalMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI/启发式字段→本体映射建议入治理收件箱（chance-ai-mapping-inbox）。
 *
 * <p>本服务可接收外部 LLM 已生成的 suggestion JSON，也可对脱敏画像做确定性启发式建议；
 * 生效必须经 approve（C7/C9）。
 */
@Service
public class MappingProposalService {

    private final MappingProposalMapper mapper;
    private final ObjectMapper objectMapper;

    @Value("${dg.evolution.maintenance-log-path:}")
    private String maintenanceLogPath;

    public MappingProposalService(MappingProposalMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public record FieldProfile(
            String sourceTable,
            String sourceField,
            String comment,
            String dataType,
            Double nullRatio,
            String unitHint,
            List<String> sampleEnums
    ) {
    }

    public record SuggestRequest(
            List<FieldProfile> fields,
            List<String> businessUrns,
            List<Map<String, Object>> llmSuggestions
    ) {
    }

    public record DecisionRequest(String note, String amendedUrn) {
    }

    @Transactional
    public List<MappingProposal> suggest(SuggestRequest request) {
        Caller caller = WorkspaceContext.require();
        List<MappingProposal> created = new ArrayList<>();
        if (request != null && request.llmSuggestions() != null && !request.llmSuggestions().isEmpty()) {
            for (Map<String, Object> s : request.llmSuggestions()) {
                MappingProposal p = fromSuggestionMap(s, caller);
                if (p != null && !suppressed(caller.workspaceId(), p.getSuggestionFingerprint())) {
                    mapper.insert(p);
                    created.add(p);
                }
            }
            return created;
        }
        List<String> urns = request == null || request.businessUrns() == null
                ? defaultUrns() : request.businessUrns();
        List<FieldProfile> fields = request == null || request.fields() == null ? List.of() : request.fields();
        for (FieldProfile f : fields) {
            MappingProposal p = heuristicSuggest(f, urns, caller);
            if (suppressed(caller.workspaceId(), p.getSuggestionFingerprint())) {
                continue;
            }
            mapper.insert(p);
            created.add(p);
        }
        return created;
    }

    public List<MappingProposal> list(String state) {
        String ws = WorkspaceContext.requireWorkspaceId();
        LambdaQueryWrapper<MappingProposal> q = new LambdaQueryWrapper<MappingProposal>()
                .eq(MappingProposal::getWorkspaceId, ws)
                .orderByDesc(MappingProposal::getCreatedAt);
        if (state != null && !state.isBlank()) {
            q.eq(MappingProposal::getState, state.trim());
        }
        return mapper.selectList(q);
    }

    @Transactional
    public MappingProposal approve(String id, DecisionRequest request) {
        Caller caller = WorkspaceContext.require();
        MappingProposal p = require(id, caller.workspaceId());
        if (!"PROPOSED".equals(p.getState())) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "仅 PROPOSED 可审校通过");
        }
        if ("block_and_escalate".equals(p.getRoutingTier())
                && (request == null || request.note() == null || request.note().isBlank())) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "block_and_escalate 必须附宿主确认备注（C7）");
        }
        if (request != null && request.amendedUrn() != null && !request.amendedUrn().isBlank()) {
            p.setRecommendedBusinessUrn(request.amendedUrn().trim());
        }
        p.setState("APPROVED");
        p.setDecisionNote(request == null ? null : request.note());
        p.setDecidedBy(caller.userId());
        p.setDecidedAt(Instant.now());
        mapper.updateById(p);
        return p;
    }

    @Transactional
    public MappingProposal reject(String id, DecisionRequest request) {
        Caller caller = WorkspaceContext.require();
        MappingProposal p = require(id, caller.workspaceId());
        if (request == null || request.note() == null || request.note().isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "驳回必须填写理由（写入防重提账本）");
        }
        p.setState("REJECTED");
        p.setDecisionNote(request.note().trim());
        p.setDecidedBy(caller.userId());
        p.setDecidedAt(Instant.now());
        mapper.updateById(p);
        appendMaintenanceLog(p);
        return p;
    }

    private MappingProposal heuristicSuggest(FieldProfile f, List<String> urns, Caller caller) {
        String name = (f.sourceField() == null ? "" : f.sourceField()).toLowerCase(Locale.ROOT);
        String comment = (f.comment() == null ? "" : f.comment()).toLowerCase(Locale.ROOT);
        String best = null;
        double conf = 0.40;
        String t0Warn = null;
        if (name.contains("treat") && (comment.contains("计费") || comment.contains("bill") || name.contains("bill"))) {
            t0Warn = "疑似计费/医嘱时间陷阱，勿映射为临床实际执行完成时间 T0";
            conf = 0.35;
            best = urns.stream().filter(u -> u.contains("billing") || u.contains("order")).findFirst().orElse(null);
        } else if (name.contains("t0") || name.contains("exec_end") || name.contains("cath_out")
                || comment.contains("实际治疗") || comment.contains("出导管室")) {
            best = urns.stream().filter(u -> u.contains("actual_exec") || u.contains("t0")).findFirst()
                    .orElse("semantic://attr.treatment.actual_exec_end_t0");
            conf = 0.92;
        } else if (name.contains("cnlc") || comment.contains("cnlc")) {
            best = urns.stream().filter(u -> u.contains("cnlc")).findFirst()
                    .orElse("semantic://attr.diagnosis.cnlc_stage");
            conf = 0.88;
        } else if (name.contains("afp")) {
            best = "semantic://attr.lab.afp_ng_ml";
            conf = 0.85;
        } else {
            best = urns.isEmpty() ? null : urns.getFirst();
            conf = 0.55;
        }

        String tier;
        if (t0Warn != null || conf < 0.75) {
            tier = "block_and_escalate";
        } else if (conf >= 0.90) {
            tier = "fast_track_review";
        } else {
            tier = "standard_hitl_review";
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("naming_signal", name + "|" + comment);
        evidence.put("value_domain_check", f.unitHint() == null ? "Insufficient" : "Compatible");
        evidence.put("t0_trap_warning", t0Warn);
        evidence.put("maintenance_log_hit", null);

        List<Map<String, Object>> alts = new ArrayList<>();
        for (String u : urns) {
            if (u != null && !u.equals(best)) {
                alts.add(Map.of("urn", u, "confidence", 0.3));
            }
            if (alts.size() >= 2) {
                break;
            }
        }

        String fp = fingerprint(f.sourceTable(), f.sourceField(), best, tier);
        MappingProposal p = new MappingProposal();
        p.setId(Ids.of("mpp"));
        p.setWorkspaceId(caller.workspaceId());
        p.setSourceTable(nullToEmpty(f.sourceTable()));
        p.setSourceField(nullToEmpty(f.sourceField()));
        p.setRecommendedBusinessUrn(best);
        p.setAlternativeCandidatesJson(toJson(alts));
        p.setConfidence(BigDecimal.valueOf(conf).setScale(4, RoundingMode.HALF_UP));
        p.setTransformationType("IDENTITY");
        p.setTransformExpression("IDENTITY");
        p.setEvidenceJson(toJson(evidence));
        p.setRoutingTier(tier);
        p.setSuggestionFingerprint(fp);
        p.setState("PROPOSED");
        p.setCreatedBy(caller.userId());
        p.setCreatedAt(Instant.now());
        return p;
    }

    @SuppressWarnings("unchecked")
    private MappingProposal fromSuggestionMap(Map<String, Object> s, Caller caller) {
        String table = str(s.get("source_table"));
        String field = str(s.get("source_field"));
        if (table.isBlank() || field.isBlank()) {
            return null;
        }
        String urn = str(s.get("recommended_business_urn"));
        double conf = s.get("confidence") instanceof Number n ? n.doubleValue() : 0.5;
        String tier = str(s.get("routing_tier"));
        if (tier.isBlank()) {
            tier = conf >= 0.90 ? "fast_track_review" : conf >= 0.75 ? "standard_hitl_review" : "block_and_escalate";
        }
        String fp = fingerprint(table, field, urn, tier);
        MappingProposal p = new MappingProposal();
        p.setId(Ids.of("mpp"));
        p.setWorkspaceId(caller.workspaceId());
        p.setSourceTable(table);
        p.setSourceField(field);
        p.setRecommendedBusinessUrn(urn.isBlank() ? null : urn);
        p.setAlternativeCandidatesJson(toJson(s.getOrDefault("alternative_candidates", List.of())));
        p.setConfidence(BigDecimal.valueOf(conf).setScale(4, RoundingMode.HALF_UP));
        p.setTransformationType(str(s.getOrDefault("transformation_type", "IDENTITY")));
        p.setTransformExpression(str(s.getOrDefault("transform_expression", "IDENTITY")));
        p.setJoinConditionProposal(str(s.get("join_condition_proposal")));
        p.setEvidenceJson(toJson(s.getOrDefault("evidence", Map.of())));
        p.setRoutingTier(tier);
        p.setSuggestionFingerprint(fp);
        p.setState("PROPOSED");
        p.setCreatedBy(caller.userId());
        p.setCreatedAt(Instant.now());
        return p;
    }

    private boolean suppressed(String workspaceId, String fingerprint) {
        Long rejected = mapper.selectCount(new LambdaQueryWrapper<MappingProposal>()
                .eq(MappingProposal::getWorkspaceId, workspaceId)
                .eq(MappingProposal::getSuggestionFingerprint, fingerprint)
                .eq(MappingProposal::getState, "REJECTED"));
        if (rejected != null && rejected > 0) {
            return true;
        }
        if (maintenanceLogPath == null || maintenanceLogPath.isBlank()) {
            return false;
        }
        try {
            String content = Files.readString(Path.of(maintenanceLogPath));
            return content.contains(fingerprint) && content.contains("REJECTED");
        } catch (Exception e) {
            return false;
        }
    }

    private void appendMaintenanceLog(MappingProposal p) {
        if (maintenanceLogPath == null || maintenanceLogPath.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(maintenanceLogPath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            String row = "| " + Instant.now() + " | mapping `" + p.getSuggestionFingerprint()
                    + "` | **REJECTED** | " + p.getDecisionNote() + " |\n";
            Files.writeString(path, row, StandardCharsets.UTF_8,
                    Files.exists(path) ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
        } catch (Exception ignored) {
            // 账本写失败不阻断主流程
        }
    }

    private MappingProposal require(String id, String ws) {
        MappingProposal p = mapper.selectById(id);
        if (p == null || !ws.equals(p.getWorkspaceId())) {
            throw new BizException(ErrorCode.SYS_NOT_FOUND, "映射提案不存在");
        }
        return p;
    }

    private static List<String> defaultUrns() {
        return List.of(
                "semantic://attr.treatment.actual_exec_end_t0",
                "semantic://attr.treatment.billing_date",
                "semantic://attr.diagnosis.cnlc_stage",
                "semantic://attr.lab.afp_ng_ml",
                "semantic://entity.patient.id");
    }

    private static String fingerprint(String table, String field, String urn, String tier) {
        return sha256(table + "|" + field + "|" + urn + "|" + tier);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "fp_error";
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
