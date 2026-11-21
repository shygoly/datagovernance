package com.datagov.metadata.service.metric6d;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.entity.SemanticEntities.SemanticLink;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.mapper.SemanticLinkMapper;
import com.datagov.metadata.mapper.SemanticObjectMapper;
import com.datagov.metadata.service.metric6d.SemanticYamlCodec.CatalogEntry;
import com.datagov.metadata.service.metric6d.SemanticYamlCodec.ImportDocument;
import com.datagov.metadata.service.metric6d.SemanticYamlCodec.ResolveResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * metric-6d 导入/导出/验证/删除影响与 STALE_REVIEW 持久化门面。
 */
@Service
public class SemanticMetric6dService {

    private final SemanticObjectMapper objectMapper;
    private final SemanticLinkMapper linkMapper;
    private final SemanticGraphGateService gate;
    private final SemanticYamlCodec yamlCodec;
    private final SemanticAntiPatternLinter antiPatternLinter;

    public SemanticMetric6dService(SemanticObjectMapper objectMapper,
                                   SemanticLinkMapper linkMapper,
                                   SemanticGraphGateService gate,
                                   SemanticYamlCodec yamlCodec,
                                   SemanticAntiPatternLinter antiPatternLinter) {
        this.objectMapper = objectMapper;
        this.linkMapper = linkMapper;
        this.gate = gate;
        this.yamlCodec = yamlCodec;
        this.antiPatternLinter = antiPatternLinter;
    }

    public record VerifyResult(String objectId, String status, Metric6DSpec spec, List<String> messages) {
    }

    public record DeleteImpact(String objectId, boolean deletable, List<String> referrerObjectIds) {
    }

    public record ImportResult(int metricCount, Map<String, String> resolvedRefs, List<String> warnings) {
    }

    public ResolveResult resolveRef(String shortName) {
        return yamlCodec.resolveRef(shortName, catalogEntries(null));
    }

    public String exportYaml(String mode) {
        List<CatalogEntry> all = catalogEntries(null);
        List<CatalogEntry> metrics = all.stream()
                .filter(e -> "METRIC".equalsIgnoreCase(e.objectType())).toList();
        List<CatalogEntry> dims = all.stream()
                .filter(e -> SetOfDim(e.objectType())).toList();
        return yamlCodec.export(metrics, dims, mode);
    }

    public ImportResult previewImport(String yamlText) {
        ImportDocument doc = yamlCodec.parse(yamlText);
        Map<String, String> resolved = yamlCodec.resolveAllOrThrow(doc, catalogEntries(null));
        for (var m : doc.metrics()) {
            if (m.spec() != null && !m.spec().isEmpty()) {
                try {
                    ProvenancePolicy.assertWritableByLlm(writeJson(m.spec()));
                    Metric6DSpec spec = gate.validateSchema(writeJson(m.spec()));
                    antiPatternLinter.assertClean(spec, m.spec(), null);
                } catch (SemanticGraphGateException ex) {
                    throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID,
                            "闸门失败 [" + m.code() + "]: " + ex.getMessage());
                }
            }
        }
        return new ImportResult(doc.metrics().size(), resolved, List.of("preview-only：未落库，请经人工确认后发布对象"));
    }

    @Transactional
    public VerifyResult verifyObject(String objectId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        SemanticObject obj = require(ws, objectId);
        List<String> messages = new ArrayList<>();
        Metric6DSpec spec = null;
        if ("METRIC".equalsIgnoreCase(obj.getObjectType())) {
            try {
                ProvenancePolicy.assertWritableByLlm(obj.getSpecJson());
                spec = gate.validateSchema(obj.getSpecJson());
                messages.add("G1 PASS");
                antiPatternLinter.assertClean(spec, readSpec(obj.getSpecJson()), null);
                messages.add("ANTI_PATTERN PASS");
            } catch (SemanticGraphGateException ex) {
                throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, ex.getMessage());
            }
        }
        ProvenancePolicy.Evaluation provenance = ProvenancePolicy.evaluate(
                obj.getProvenanceJson(), obj.getSpecJson());
        if (provenance.requiresHumanReview()) {
            obj.setRequiresHumanReview(true);
            messages.add("PROVENANCE: llm_inference → requires_human_review");
        }
        Map<String, Object> verified = new LinkedHashMap<>();
        verified.put("status", "PASS");
        verified.put("gatesPass", true);
        verified.put("verifiedAt", Instant.now().toString());
        verified.put("messages", messages);
        obj.setVerifiedJson(writeJson(verified));
        obj.setUpdatedAt(Instant.now());
        objectMapper.updateById(obj);
        return new VerifyResult(objectId, "PASS", spec, messages);
    }

    @Transactional
    public List<String> persistStaleReview(String changedObjectId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        SemanticObject changed = require(ws, changedObjectId);
        List<SemanticLink> inbound = linkMapper.selectList(new LambdaQueryWrapper<SemanticLink>()
                .eq(SemanticLink::getWorkspaceId, ws)
                .eq(SemanticLink::getToObjectId, changedObjectId));
        Map<String, SemanticObject> byId = objectMapper.selectList(new LambdaQueryWrapper<SemanticObject>()
                        .eq(SemanticObject::getWorkspaceId, ws))
                .stream()
                .collect(Collectors.toMap(SemanticObject::getId, o -> o, (a, b) -> a));
        List<SemanticObject> stale = gate.markStaleOnDigestChange(changed, inbound, byId);
        List<String> ids = new ArrayList<>();
        for (SemanticObject s : stale) {
            s.setUpdatedAt(Instant.now());
            objectMapper.updateById(s);
            ids.add(s.getId());
        }
        return ids;
    }

    public DeleteImpact deleteImpact(String objectId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        require(ws, objectId);
        List<SemanticLink> inbound = linkMapper.selectList(new LambdaQueryWrapper<SemanticLink>()
                .eq(SemanticLink::getWorkspaceId, ws)
                .eq(SemanticLink::getToObjectId, objectId));
        try {
            gate.assertDeletable(objectId, inbound);
            return new DeleteImpact(objectId, true, List.of());
        } catch (SemanticGraphGateException ex) {
            return new DeleteImpact(objectId, false, ex.details() == null ? List.of() : ex.details());
        }
    }

    private List<CatalogEntry> catalogEntries(String objectTypeFilter) {
        String ws = WorkspaceContext.requireWorkspaceId();
        return objectMapper.selectList(new LambdaQueryWrapper<SemanticObject>()
                        .eq(SemanticObject::getWorkspaceId, ws)
                        .eq(SemanticObject::getStatus, "PUBLISHED"))
                .stream()
                .filter(o -> objectTypeFilter == null || objectTypeFilter.equalsIgnoreCase(o.getObjectType()))
                .map(o -> new CatalogEntry(o.getId(), o.getCode(), o.getName(), o.getObjectType(), readSpec(o.getSpecJson())))
                .toList();
    }

    private SemanticObject require(String ws, String id) {
        SemanticObject obj = objectMapper.selectById(id);
        if (obj == null || !Objects.equals(ws, obj.getWorkspaceId())) {
            throw new BizException(ErrorCode.SYS_NOT_FOUND, "语义对象不存在");
        }
        return obj;
    }

    private Map<String, Object> readSpec(String specJson) {
        if (specJson == null || specJson.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(specJson, Map.class);
            return m;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "JSON 序列化失败");
        }
    }

    private static boolean SetOfDim(String type) {
        return "DIMENSION".equalsIgnoreCase(type)
                || "ATTRIBUTE".equalsIgnoreCase(type)
                || "ENTITY".equalsIgnoreCase(type);
    }
}
