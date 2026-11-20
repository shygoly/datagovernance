package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.query.ReadOnlySqlGuard;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogColumn;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.GovernanceEntities.BusinessDomain;
import com.datagov.metadata.entity.SemanticEntities.SemanticLink;
import com.datagov.metadata.entity.SemanticEntities.SemanticMapping;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.entity.SemanticEntities.SemanticObjectVersion;
import com.datagov.metadata.entity.SemanticEntities.SemanticValueDict;
import com.datagov.metadata.entity.SensitivityEntities.ColumnSensitivity;
import com.datagov.metadata.mapper.BusinessDomainMapper;
import com.datagov.metadata.mapper.CatalogColumnMapper;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.ColumnSensitivityMapper;
import com.datagov.metadata.mapper.SemanticLinkMapper;
import com.datagov.metadata.mapper.SemanticMappingMapper;
import com.datagov.metadata.mapper.SemanticObjectMapper;
import com.datagov.metadata.mapper.SemanticObjectVersionMapper;
import com.datagov.metadata.mapper.SemanticValueDictMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 统一本体语义元数据内核与轻量业务知识图谱服务（OpenSpec: semantic-ontology-kernel）。
 *
 * <p>核心红线（CONSTITUTION.md）：
 * <ul>
 *   <li>C2：{@code METRIC} 发布强制要求已登记 {@code standardRef}；</li>
 *   <li>C5：{@code aiContextJson}/{@code rationale}/{@code valueDict} 严禁夹带 L3+ 明文 PII，
 *       L3/L4 对象禁止采集值字典；子图检索执行检索前 + 逐跳 ACL 剪枝；</li>
 *   <li>C8：每次发布生成不可变 {@code md_semantic_object_version} 快照与 SHA-256 摘要；</li>
 *   <li>C9：AI 提议（{@code PROPOSED}）绝不进入生产检索与编译，审批晋升或驳回记录防重提。</li>
 * </ul>
 */
@Service
public class SemanticOntologyService {

    public static final Set<String> OBJECT_TYPES = Set.of(
            "ENTITY", "ATTRIBUTE", "DIMENSION", "FACT", "METRIC", "CONCEPT", "PROCESS");
    public static final Set<String> MAPPING_ROLES = Set.of(
            "ID", "DESC", "CODE", "MEASURE", "TIME_KEY", "FILTER_KEY");
    public static final Set<String> EDGE_CATEGORIES = Set.of("STRUCTURAL", "SEMANTIC", "CAUSAL");
    public static final Set<String> STRUCTURAL_LINKS = Set.of(
            "HAS_ATTRIBUTE", "HAS_FACT", "HIERARCHY_PARENT", "RELATES_TO", "DERIVED_FROM");
    public static final Set<String> SEMANTIC_LINKS = Set.of(
            "IS_A", "PART_OF", "SYNONYM_OF", "MAPS_TO_CONCEPT", "PRECEDES");
    public static final Set<String> CAUSAL_LINKS = Set.of("CONSTRAINS", "EXCLUDES", "AFFECTS");
    public static final Set<String> SENSITIVITY_LEVELS = Set.of("L1", "L2", "L3", "L4");

    private static final Pattern PII_PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern PII_ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Map<String, Integer> SENSITIVITY_RANK = Map.of(
            "L1", 1, "L2", 2, "L3", 3, "L4", 4);

    private final SemanticObjectMapper objectMapperDao;
    private final SemanticMappingMapper mappingMapper;
    private final SemanticLinkMapper linkMapper;
    private final SemanticObjectVersionMapper versionMapper;
    private final SemanticValueDictMapper valueDictMapper;
    private final CatalogDatasetMapper datasetMapper;
    private final CatalogColumnMapper columnMapper;
    private final ColumnSensitivityMapper sensitivityMapper;
    private final BusinessDomainMapper domainMapper;
    private final ObjectMapper json;

    public SemanticOntologyService(SemanticObjectMapper objectMapperDao,
                                   SemanticMappingMapper mappingMapper,
                                   SemanticLinkMapper linkMapper,
                                   SemanticObjectVersionMapper versionMapper,
                                   SemanticValueDictMapper valueDictMapper,
                                   CatalogDatasetMapper datasetMapper,
                                   CatalogColumnMapper columnMapper,
                                   ColumnSensitivityMapper sensitivityMapper,
                                   BusinessDomainMapper domainMapper,
                                   ObjectMapper json) {
        this.objectMapperDao = objectMapperDao;
        this.mappingMapper = mappingMapper;
        this.linkMapper = linkMapper;
        this.versionMapper = versionMapper;
        this.valueDictMapper = valueDictMapper;
        this.datasetMapper = datasetMapper;
        this.columnMapper = columnMapper;
        this.sensitivityMapper = sensitivityMapper;
        this.domainMapper = domainMapper;
        this.json = json;
    }

    // ── DTOs ─────────────────────────────────────────────────────────────

    public record ObjectRequest(
            String code,
            String name,
            String objectType,
            String domainId,
            String standardRef,
            String glossaryTermId,
            Map<String, Object> spec,
            Map<String, Object> aiContext,
            String ownerUsername,
            String sourceKind,
            String scope
    ) {
        public ObjectRequest(String code, String name, String objectType, String domainId,
                             String standardRef, String glossaryTermId,
                             Map<String, Object> spec, Map<String, Object> aiContext,
                             String ownerUsername, String sourceKind) {
            this(code, name, objectType, domainId, standardRef, glossaryTermId,
                    spec, aiContext, ownerUsername, sourceKind, "WORKSPACE");
        }
    }

    public record ObjectView(
            String id,
            String code,
            String name,
            String objectType,
            String domainId,
            String domainName,
            String standardRef,
            String glossaryTermId,
            Map<String, Object> spec,
            Map<String, Object> aiContext,
            String maxSensitivity,
            String status,
            int versionNo,
            Instant validFrom,
            Instant validTo,
            String ownerUsername,
            String contentDigest,
            String sourceKind,
            String rejectionReason,
            String publishedBy,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt,
            long mappingCount,
            long linkCount,
            String scope
    ) {
        public ObjectView(String id, String code, String name, String objectType,
                          String domainId, String domainName, String standardRef, String glossaryTermId,
                          Map<String, Object> spec, Map<String, Object> aiContext,
                          String maxSensitivity, String status, int versionNo,
                          Instant validFrom, Instant validTo, String ownerUsername,
                          String contentDigest, String sourceKind, String rejectionReason,
                          String publishedBy, Instant publishedAt, Instant createdAt, Instant updatedAt,
                          long mappingCount, long linkCount) {
            this(id, code, name, objectType, domainId, domainName, standardRef, glossaryTermId,
                    spec, aiContext, maxSensitivity, status, versionNo, validFrom, validTo,
                    ownerUsername, contentDigest, sourceKind, rejectionReason, publishedBy,
                    publishedAt, createdAt, updatedAt, mappingCount, linkCount, "WORKSPACE");
        }
    }

    public record MappingRequest(
            String mappingRole,
            String catalogDatasetId,
            String catalogColumnId,
            String expressionSql,
            Boolean isLookupTable,
            Integer priority,
            String warehouseId,
            String logicalTableId,
            Map<String, String> valueNormalization,
            String sourceUnit,
            String targetUnit
    ) {
        public MappingRequest(String mappingRole, String catalogDatasetId, String catalogColumnId,
                              String expressionSql, Boolean isLookupTable, Integer priority) {
            this(mappingRole, catalogDatasetId, catalogColumnId, expressionSql,
                    isLookupTable, priority, null, null, null, null, null);
        }
    }

    public record MappingView(
            String id,
            String objectId,
            String mappingRole,
            String catalogDatasetId,
            String datasetName,
            String datasetQualifiedName,
            String catalogColumnId,
            String columnName,
            String columnSensitivity,
            String expressionSql,
            boolean isLookupTable,
            int priority,
            String status,
            Instant createdAt,
            String warehouseId,
            String logicalTableId,
            Map<String, String> valueNormalization,
            String sourceUnit,
            String targetUnit
    ) {
    }

    public record LinkRequest(
            String fromObjectId,
            String toObjectId,
            String edgeCategory,
            String linkType,
            String cardinality,
            List<Map<String, Object>> joinSpec,
            String rationale,
            String sourceKind,
            boolean proposeOnly
    ) {
    }

    public record LinkView(
            String id,
            String fromObjectId,
            String fromCode,
            String fromName,
            String toObjectId,
            String toCode,
            String toName,
            String edgeCategory,
            String linkType,
            String cardinality,
            List<Map<String, Object>> joinSpec,
            String rationale,
            String sourceKind,
            String status,
            String rejectionReason,
            Instant validFrom,
            Instant validTo,
            Instant createdAt
    ) {
    }

    public record VersionView(
            String id,
            String objectId,
            int versionNo,
            Map<String, Object> snapshot,
            String contentDigest,
            Instant validFrom,
            Instant validTo,
            String publishedBy,
            Instant publishedAt
    ) {
    }

    public record ValueDictRequest(
            String rawValue,
            String displayLabel,
            List<String> synonyms,
            String sourceKind
    ) {
    }

    public record ValueDictView(
            String id,
            String objectId,
            String rawValue,
            String displayLabel,
            List<String> synonyms,
            String sourceKind,
            Instant createdAt
    ) {
    }

    public record SubgraphSearchRequest(
            String query,
            Integer maxHops,
            Integer topK,
            Set<String> allowedDatasetIds,
            Boolean externalModel
    ) {
    }

    public record SubgraphNode(
            String id,
            String code,
            String name,
            String objectType,
            String standardRef,
            String maxSensitivity,
            int versionNo,
            String contentDigest,
            int hopDistance,
            Map<String, Object> spec,
            Map<String, Object> aiContext,
            List<MappingView> mappings
    ) {
    }

    public record SubgraphResult(
            String query,
            int maxHops,
            List<SubgraphNode> nodes,
            List<LinkView> links,
            int prunedNodeCount,
            List<String> prunedReasons,
            List<String> snapshotRefs
    ) {
    }

    public record ImpactItem(
            String objectId,
            String code,
            String name,
            String objectType,
            String status,
            int versionNo,
            int depth,
            String relationPath
    ) {
    }

    public record ImpactResult(
            String targetType,
            String targetId,
            List<ImpactItem> impactedObjects
    ) {
    }

    public record ProposalItem(
            String kind, // OBJECT / LINK
            String id,
            String title,
            String summary,
            String sourceKind,
            String status,
            String rejectionReason,
            Map<String, Object> payload,
            Instant createdAt
    ) {
    }

    public record SemanticSummaryStats(
            long publishedMetrics,
            long publishedObjects,
            long mappedDatasets,
            long totalLinks,
            long pendingProposals
    ) {
    }

    public record SemanticViewOption(
            String id,
            String name,
            String domain,
            long metricCount,
            long objectCount
    ) {
    }

    // ── Summary & Views ──────────────────────────────────────────────────

    public SemanticSummaryStats summaryStats() {
        String ws = WorkspaceContext.requireWorkspaceId();
        List<SemanticObject> objects = objectMapperDao.selectList(
                new LambdaQueryWrapper<SemanticObject>().eq(SemanticObject::getWorkspaceId, ws));
        long publishedMetrics = objects.stream()
                .filter(o -> "PUBLISHED".equals(o.getStatus()) && "METRIC".equals(o.getObjectType()))
                .count();
        long publishedObjects = objects.stream()
                .filter(o -> "PUBLISHED".equals(o.getStatus()))
                .count();
        long pendingObj = objects.stream()
                .filter(o -> "PROPOSED".equals(o.getStatus()))
                .count();

        List<SemanticMapping> mappings = mappingMapper.selectList(
                new LambdaQueryWrapper<SemanticMapping>().eq(SemanticMapping::getWorkspaceId, ws));
        long mappedDatasets = mappings.stream()
                .map(SemanticMapping::getCatalogDatasetId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        List<SemanticLink> links = linkMapper.selectList(
                new LambdaQueryWrapper<SemanticLink>().eq(SemanticLink::getWorkspaceId, ws));
        long publishedLinks = links.stream()
                .filter(l -> "PUBLISHED".equals(l.getStatus()))
                .count();
        long pendingLinks = links.stream()
                .filter(l -> "PROPOSED".equals(l.getStatus()))
                .count();

        return new SemanticSummaryStats(
                publishedMetrics,
                publishedObjects,
                mappedDatasets,
                publishedLinks,
                pendingObj + pendingLinks);
    }

    public List<SemanticViewOption> listSemanticViews() {
        String ws = WorkspaceContext.requireWorkspaceId();
        List<SemanticObject> published = objectMapperDao.selectList(
                new LambdaQueryWrapper<SemanticObject>()
                        .eq(SemanticObject::getWorkspaceId, ws)
                        .eq(SemanticObject::getStatus, "PUBLISHED"));
        Map<String, BusinessDomain> domains = loadDomains(ws);
        Map<String, List<SemanticObject>> byDomain = new LinkedHashMap<>();
        for (SemanticObject obj : published) {
            String key = obj.getDomainId() == null || obj.getDomainId().isBlank() ? "__default__" : obj.getDomainId();
            byDomain.computeIfAbsent(key, k -> new ArrayList<>()).add(obj);
        }
        List<SemanticViewOption> result = new ArrayList<>();
        for (var entry : byDomain.entrySet()) {
            String key = entry.getKey();
            BusinessDomain d = "__default__".equals(key) ? null : domains.get(key);
            String domainName = d != null ? d.getName() : "通用语义域";
            long metricCount = entry.getValue().stream()
                    .filter(o -> "METRIC".equals(o.getObjectType())).count();
            result.add(new SemanticViewOption(
                    "__default__".equals(key) ? "view_default" : "view_" + key,
                    domainName + "语义主题",
                    domainName,
                    metricCount,
                    entry.getValue().size()));
        }
        if (result.isEmpty()) {
            result.add(new SemanticViewOption("view_default", "默认语义主题", "通用语义域", 0, 0));
        }
        return result;
    }

    // ── Object CRUD & Lifecycle ──────────────────────────────────────────

    public PageResult<ObjectView> listObjects(long page, long size, String keyword,
                                              String objectType, String domainId, String status) {
        return listObjects(page, size, keyword, objectType, domainId, status, null);
    }

    public PageResult<ObjectView> listObjects(long page, long size, String keyword,
                                              String objectType, String domainId, String status, String scope) {
        String ws = WorkspaceContext.requireWorkspaceId();
        LambdaQueryWrapper<SemanticObject> q = new LambdaQueryWrapper<SemanticObject>()
                .and(w -> w.eq(SemanticObject::getWorkspaceId, ws)
                        .or(g -> g.eq(SemanticObject::getScope, "GLOBAL")
                                .eq(SemanticObject::getStatus, "PUBLISHED")));
        if (scope != null && !scope.isBlank()) {
            q.eq(SemanticObject::getScope, scope.trim().toUpperCase(Locale.ROOT));
        }
        if (objectType != null && !objectType.isBlank()) {
            q.eq(SemanticObject::getObjectType, objectType.trim().toUpperCase(Locale.ROOT));
        }
        if (domainId != null && !domainId.isBlank()) {
            q.eq(SemanticObject::getDomainId, domainId.trim());
        }
        if (status != null && !status.isBlank()) {
            q.eq(SemanticObject::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            q.and(w -> w.like(SemanticObject::getName, kw)
                    .or().like(SemanticObject::getCode, kw)
                    .or().like(SemanticObject::getAiContextJson, kw));
        }
        q.orderByDesc(SemanticObject::getUpdatedAt);

        Page<SemanticObject> p = objectMapperDao.selectPage(new Page<>(Math.max(1, page), Math.min(200, Math.max(1, size))), q);
        return PageResult.of(toObjectViews(ws, p.getRecords()), p.getTotal(), p.getCurrent(), p.getSize());
    }

    public ObjectView getObject(String id) {
        String ws = WorkspaceContext.requireWorkspaceId();
        SemanticObject obj = requireObject(ws, id);
        return toObjectViews(ws, List.of(obj)).get(0);
    }

    @Transactional
    public ObjectView createObject(ObjectRequest req) {
        return insertObject(req, "DRAFT", req.sourceKind() == null ? "HUMAN" : req.sourceKind());
    }

    /**
     * AI 暂存提案入口（C9）：只能创建 PROPOSED 状态对象，且检查是否命中历史驳回指纹。
     */
    @Transactional
    public ObjectView proposeObject(ObjectRequest req) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String normCode = normalizeCode(req.code());
        if (isPreviouslyRejectedObject(ws, normCode)) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_STATE,
                    "语义对象 " + normCode + " 曾被治理员驳回，已触发负反馈防重提抑制（请先查阅 maintenance-log 或人工恢复）");
        }
        return insertObject(req, "PROPOSED", req.sourceKind() == null ? "AI_PROPOSED" : req.sourceKind());
    }

    private ObjectView insertObject(ObjectRequest req, String initialStatus, String sourceKind) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String userId = WorkspaceContext.require().userId();
        validateObjectRequest(req);
        String code = normalizeCode(req.code());

        Long dup = objectMapperDao.selectCount(new LambdaQueryWrapper<SemanticObject>()
                .eq(SemanticObject::getWorkspaceId, ws)
                .apply("lower(code) = {0}", code));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_CODE_DUPLICATED,
                    "语义对象编码已存在: " + code);
        }

        String specJson = writeJson(req.spec() == null ? Map.of() : req.spec());
        String aiContextJson = writeJson(req.aiContext() == null ? Map.of() : req.aiContext());
        assertNoPlaintextPii(aiContextJson, "aiContext");

        Instant now = Instant.now();
        String normalizedScope = normalizeScope(req.scope());
        SemanticObject obj = new SemanticObject();
        obj.setId(Ids.of("sem"));
        obj.setWorkspaceId(ws);
        obj.setScope(normalizedScope);
        obj.setCode(code);
        obj.setName(req.name().trim());
        obj.setObjectType(req.objectType().trim().toUpperCase(Locale.ROOT));
        obj.setDomainId(blankToNull(req.domainId()));
        obj.setStandardRef(blankToNull(req.standardRef()));
        obj.setGlossaryTermId(blankToNull(req.glossaryTermId()));
        obj.setSpecJson(specJson);
        obj.setAiContextJson(aiContextJson);
        obj.setMaxSensitivity("L1");
        obj.setStatus(initialStatus);
        obj.setVersionNo(1);
        obj.setValidFrom(now);
        obj.setOwnerUsername(blankToNull(req.ownerUsername()));
        obj.setSourceKind(sourceKind.toUpperCase(Locale.ROOT));
        obj.setCreatedAt(now);
        obj.setCreatedBy(userId);
        obj.setUpdatedAt(now);
        obj.setUpdatedBy(userId);
        objectMapperDao.insert(obj);
        return toObjectViews(ws, List.of(obj)).get(0);
    }

    @Transactional
    public ObjectView updateObject(String id, ObjectRequest req) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String userId = WorkspaceContext.require().userId();
        SemanticObject obj = requireObject(ws, id);
        if (!ws.equals(obj.getWorkspaceId())) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_STATE,
                    "跨空间共享的 GLOBAL 权威本体只允许在其归属空间修改，当前空间可为其配置独立物理映射");
        }
        validateObjectRequest(req);

        String code = normalizeCode(req.code());
        if (!code.equalsIgnoreCase(obj.getCode())) {
            Long dup = objectMapperDao.selectCount(new LambdaQueryWrapper<SemanticObject>()
                    .eq(SemanticObject::getWorkspaceId, ws)
                    .ne(SemanticObject::getId, id)
                    .apply("lower(code) = {0}", code));
            if (dup != null && dup > 0) {
                throw new BizException(ErrorCode.MTD_SEMANTIC_CODE_DUPLICATED,
                        "语义对象编码已存在: " + code);
            }
        }

        String specJson = writeJson(req.spec() == null ? Map.of() : req.spec());
        String aiContextJson = writeJson(req.aiContext() == null ? Map.of() : req.aiContext());
        assertNoPlaintextPii(aiContextJson, "aiContext");

        obj.setCode(code);
        obj.setScope(normalizeScope(req.scope() != null ? req.scope() : obj.getScope()));
        obj.setName(req.name().trim());
        obj.setObjectType(req.objectType().trim().toUpperCase(Locale.ROOT));
        obj.setDomainId(blankToNull(req.domainId()));
        obj.setStandardRef(blankToNull(req.standardRef()));
        obj.setGlossaryTermId(blankToNull(req.glossaryTermId()));
        obj.setSpecJson(specJson);
        obj.setAiContextJson(aiContextJson);
        obj.setOwnerUsername(blankToNull(req.ownerUsername()));
        obj.setUpdatedAt(Instant.now());
        obj.setUpdatedBy(userId);
        objectMapperDao.updateById(obj);
        return toObjectViews(ws, List.of(obj)).get(0);
    }

    @Transactional
    public ObjectView publishObject(String id) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String userId = WorkspaceContext.require().userId();
        SemanticObject obj = requireObject(ws, id);

        if ("REJECTED".equals(obj.getStatus())) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_STATE, "已驳回的对象不能直接发布，请先重新编辑为草稿");
        }
        // C2 编译期存在性闸门：METRIC 发布必须登记 Standard 口径引用
        if ("METRIC".equals(obj.getObjectType())
                && (obj.getStandardRef() == null || obj.getStandardRef().isBlank())) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID,
                    "指标（METRIC）发布失败（CONSTITUTION C2）：必须绑定权威 Standard 口径引用（standardRef）");
        }
        assertNoPlaintextPii(obj.getAiContextJson(), "aiContext");

        List<SemanticMapping> mappings = mappingMapper.selectList(
                new LambdaQueryWrapper<SemanticMapping>()
                        .eq(SemanticMapping::getWorkspaceId, ws)
                        .eq(SemanticMapping::getObjectId, id));
        String maxSens = computeMaxSensitivity(ws, mappings);
        obj.setMaxSensitivity(maxSens);

        Instant now = Instant.now();
        int nextVersion = "PUBLISHED".equals(obj.getStatus())
                ? (obj.getVersionNo() == null ? 1 : obj.getVersionNo()) + 1
                : Math.max(1, obj.getVersionNo() == null ? 1 : obj.getVersionNo());

        // 关闭上一个版本的双时间态 valid_to
        List<SemanticObjectVersion> existingVersions = versionMapper.selectList(
                new LambdaQueryWrapper<SemanticObjectVersion>()
                        .eq(SemanticObjectVersion::getWorkspaceId, ws)
                        .eq(SemanticObjectVersion::getObjectId, id)
                        .isNull(SemanticObjectVersion::getValidTo));
        for (SemanticObjectVersion prev : existingVersions) {
            prev.setValidTo(now);
            versionMapper.updateById(prev);
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", obj.getId());
        snapshot.put("code", obj.getCode());
        snapshot.put("name", obj.getName());
        snapshot.put("objectType", obj.getObjectType());
        snapshot.put("standardRef", obj.getStandardRef());
        snapshot.put("maxSensitivity", maxSens);
        snapshot.put("spec", readJsonMap(obj.getSpecJson()));
        snapshot.put("aiContext", readJsonMap(obj.getAiContextJson()));
        snapshot.put("mappings", mappings.stream().map(m -> Map.of(
                "role", safeStr(m.getMappingRole()),
                "datasetId", safeStr(m.getCatalogDatasetId()),
                "columnId", safeStr(m.getCatalogColumnId()),
                "expr", safeStr(m.getExpressionSql()),
                "priority", m.getPriority() == null ? 100 : m.getPriority()
        )).toList());
        String snapshotJson = writeJson(snapshot);
        String digest = sha256(snapshotJson);

        SemanticObjectVersion ver = new SemanticObjectVersion();
        ver.setId(Ids.of("semv"));
        ver.setWorkspaceId(ws);
        ver.setObjectId(id);
        ver.setVersionNo(nextVersion);
        ver.setSnapshotJson(snapshotJson);
        ver.setContentDigest(digest);
        ver.setValidFrom(now);
        ver.setPublishedBy(userId);
        ver.setPublishedAt(now);
        versionMapper.insert(ver);

        obj.setStatus("PUBLISHED");
        obj.setVersionNo(nextVersion);
        obj.setValidFrom(now);
        obj.setValidTo(null);
        obj.setContentDigest(digest);
        obj.setPublishedBy(userId);
        obj.setPublishedAt(now);
        obj.setUpdatedAt(now);
        obj.setUpdatedBy(userId);
        objectMapperDao.updateById(obj);

        return toObjectViews(ws, List.of(obj)).get(0);
    }

    @Transactional
    public ObjectView deprecateObject(String id) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String userId = WorkspaceContext.require().userId();
        SemanticObject obj = requireObject(ws, id);
        Instant now = Instant.now();
        obj.setStatus("DEPRECATED");
        obj.setValidTo(now);
        obj.setUpdatedAt(now);
        obj.setUpdatedBy(userId);
        objectMapperDao.updateById(obj);
        return toObjectViews(ws, List.of(obj)).get(0);
    }

    @Transactional
    public void deleteObject(String id) {
        String ws = WorkspaceContext.requireWorkspaceId();
        SemanticObject obj = requireObject(ws, id);
        if ("PUBLISHED".equals(obj.getStatus())) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_STATE,
                    "已发布的语义对象不可直接删除，请先废弃（DEPRECATE）以保留审计历史");
        }
        objectMapperDao.deleteById(id);
    }

    public List<VersionView> listVersions(String objectId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        requireObject(ws, objectId);
        return versionMapper.selectList(new LambdaQueryWrapper<SemanticObjectVersion>()
                        .eq(SemanticObjectVersion::getWorkspaceId, ws)
                        .eq(SemanticObjectVersion::getObjectId, objectId)
                        .orderByDesc(SemanticObjectVersion::getVersionNo))
                .stream()
                .map(v -> new VersionView(
                        v.getId(), v.getObjectId(), v.getVersionNo(),
                        readJsonMap(v.getSnapshotJson()), v.getContentDigest(),
                        v.getValidFrom(), v.getValidTo(), v.getPublishedBy(), v.getPublishedAt()))
                .toList();
    }

    @Transactional
    public ObjectView rollbackObject(String objectId, int targetVersionNo) {
        String ws = WorkspaceContext.requireWorkspaceId();
        SemanticObject obj = requireObject(ws, objectId);
        SemanticObjectVersion target = versionMapper.selectOne(
                new LambdaQueryWrapper<SemanticObjectVersion>()
                        .eq(SemanticObjectVersion::getWorkspaceId, ws)
                        .eq(SemanticObjectVersion::getObjectId, objectId)
                        .eq(SemanticObjectVersion::getVersionNo, targetVersionNo));
        if (target == null) {
            throw new BizException(ErrorCode.SYS_NOT_FOUND, "未找到目标版本: v" + targetVersionNo);
        }
        Map<String, Object> snap = readJsonMap(target.getSnapshotJson());
        if (snap.get("name") instanceof String n) {
            obj.setName(n);
        }
        if (snap.get("standardRef") instanceof String sr) {
            obj.setStandardRef(blankToNull(sr));
        }
        if (snap.get("spec") instanceof Map<?, ?> spec) {
            obj.setSpecJson(writeJson(spec));
        }
        if (snap.get("aiContext") instanceof Map<?, ?> ai) {
            obj.setAiContextJson(writeJson(ai));
        }
        objectMapperDao.updateById(obj);
        return publishObject(objectId);
    }

    // ── Physical Mappings ────────────────────────────────────────────────

    public List<MappingView> listMappings(String objectId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        requireObject(ws, objectId);
        List<SemanticMapping> list = mappingMapper.selectList(
                new LambdaQueryWrapper<SemanticMapping>()
                        .eq(SemanticMapping::getWorkspaceId, ws)
                        .eq(SemanticMapping::getObjectId, objectId)
                        .orderByAsc(SemanticMapping::getPriority));
        return toMappingViews(ws, list);
    }

    @Transactional
    public MappingView addMapping(String objectId, MappingRequest req) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String userId = WorkspaceContext.require().userId();
        SemanticObject obj = requireObject(ws, objectId);

        if (req == null || req.catalogDatasetId() == null || req.catalogDatasetId().isBlank()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, "物理数据集 ID 不能为空");
        }
        String role = req.mappingRole() == null || req.mappingRole().isBlank()
                ? "MEASURE" : req.mappingRole().trim().toUpperCase(Locale.ROOT);
        if (!MAPPING_ROLES.contains(role)) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, "不支持的映射角色: " + role);
        }
        if (req.expressionSql() != null && !req.expressionSql().isBlank()) {
            // 经 dg-data-spi 只读 SQL 护栏校验，防止映射表达式夹带写操作/多语句
            ReadOnlySqlGuard.requireReadOnly("SELECT " + req.expressionSql().trim());
        }

        Instant now = Instant.now();
        SemanticMapping mapping = new SemanticMapping();
        mapping.setId(Ids.of("semm"));
        mapping.setWorkspaceId(ws);
        mapping.setObjectId(objectId);
        mapping.setMappingRole(role);
        mapping.setCatalogDatasetId(req.catalogDatasetId().trim());
        mapping.setCatalogColumnId(blankToNull(req.catalogColumnId()));
        mapping.setWarehouseId(blankToNull(req.warehouseId()));
        mapping.setLogicalTableId(blankToNull(req.logicalTableId()));
        mapping.setExpressionSql(blankToNull(req.expressionSql()));
        mapping.setValueNormalizationJson(req.valueNormalization() == null || req.valueNormalization().isEmpty()
                ? null : writeJson(req.valueNormalization()));
        mapping.setSourceUnit(blankToNull(req.sourceUnit()));
        mapping.setTargetUnit(blankToNull(req.targetUnit()));
        mapping.setIsLookupTable(Boolean.TRUE.equals(req.isLookupTable()));
        mapping.setPriority(req.priority() == null ? 100 : req.priority());
        mapping.setStatus("PUBLISHED");
        mapping.setCreatedAt(now);
        mapping.setCreatedBy(userId);
        mapping.setUpdatedAt(now);
        mapping.setUpdatedBy(userId);
        mappingMapper.insert(mapping);

        // 仅当对象属于当前 Workspace 时重算并回写对象的最高敏感级别投影（保护 GLOBAL 权威对象不被租户改写）
        if (ws.equals(obj.getWorkspaceId())) {
            List<SemanticMapping> all = mappingMapper.selectList(
                    new LambdaQueryWrapper<SemanticMapping>()
                            .eq(SemanticMapping::getWorkspaceId, ws)
                            .eq(SemanticMapping::getObjectId, objectId));
            obj.setMaxSensitivity(computeMaxSensitivity(ws, all));
            obj.setUpdatedAt(now);
            objectMapperDao.updateById(obj);
        }

        return toMappingViews(ws, List.of(mapping)).get(0);
    }

    @Transactional
    public void deleteMapping(String objectId, String mappingId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        SemanticObject obj = requireObject(ws, objectId);
        SemanticMapping existing = mappingMapper.selectOne(new LambdaQueryWrapper<SemanticMapping>()
                .eq(SemanticMapping::getWorkspaceId, ws)
                .eq(SemanticMapping::getObjectId, objectId)
                .eq(SemanticMapping::getId, mappingId));
        if (existing == null) {
            throw new BizException(ErrorCode.SYS_NOT_FOUND, "物理映射不存在");
        }
        mappingMapper.deleteById(mappingId);
        if (ws.equals(obj.getWorkspaceId())) {
            List<SemanticMapping> remain = mappingMapper.selectList(
                    new LambdaQueryWrapper<SemanticMapping>()
                            .eq(SemanticMapping::getWorkspaceId, ws)
                            .eq(SemanticMapping::getObjectId, objectId));
            obj.setMaxSensitivity(computeMaxSensitivity(ws, remain));
            objectMapperDao.updateById(obj);
        }
    }

    // ── Semantic & Knowledge Graph Links ─────────────────────────────────

    public List<LinkView> listLinks(String objectId, String edgeCategory, String status) {
        String ws = WorkspaceContext.requireWorkspaceId();
        LambdaQueryWrapper<SemanticLink> q = new LambdaQueryWrapper<SemanticLink>()
                .eq(SemanticLink::getWorkspaceId, ws);
        if (objectId != null && !objectId.isBlank()) {
            String oid = objectId.trim();
            q.and(w -> w.eq(SemanticLink::getFromObjectId, oid).or().eq(SemanticLink::getToObjectId, oid));
        }
        if (edgeCategory != null && !edgeCategory.isBlank()) {
            q.eq(SemanticLink::getEdgeCategory, edgeCategory.trim().toUpperCase(Locale.ROOT));
        }
        if (status != null && !status.isBlank()) {
            q.eq(SemanticLink::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        q.orderByDesc(SemanticLink::getCreatedAt);
        return toLinkViews(ws, linkMapper.selectList(q));
    }

    @Transactional
    public LinkView createLink(LinkRequest req) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String userId = WorkspaceContext.require().userId();
        if (req == null || req.fromObjectId() == null || req.toObjectId() == null) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, "起点与终点对象 ID 必填");
        }
        if (req.fromObjectId().equals(req.toObjectId())) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, "不允许自环关系边");
        }
        requireObject(ws, req.fromObjectId());
        requireObject(ws, req.toObjectId());

        String category = req.edgeCategory() == null ? "" : req.edgeCategory().trim().toUpperCase(Locale.ROOT);
        String linkType = req.linkType() == null ? "" : req.linkType().trim().toUpperCase(Locale.ROOT);
        validateLinkCategoryAndType(category, linkType);
        assertNoPlaintextPii(req.rationale(), "rationale");

        if (req.proposeOnly() && isPreviouslyRejectedLink(ws, req.fromObjectId(), req.toObjectId(), linkType)) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_STATE,
                    "该语义关系提案曾被治理员驳回，已触发负反馈抑制");
        }

        Instant now = Instant.now();
        SemanticLink link = new SemanticLink();
        link.setId(Ids.of("seml"));
        link.setWorkspaceId(ws);
        link.setFromObjectId(req.fromObjectId().trim());
        link.setToObjectId(req.toObjectId().trim());
        link.setEdgeCategory(category);
        link.setLinkType(linkType);
        link.setCardinality(blankToNull(req.cardinality()));
        link.setJoinSpecJson(req.joinSpec() == null ? null : writeJson(req.joinSpec()));
        link.setRationale(blankToNull(req.rationale()));
        link.setSourceKind(req.sourceKind() == null || req.sourceKind().isBlank()
                ? (req.proposeOnly() ? "AI_PROPOSED" : "HUMAN")
                : req.sourceKind().trim().toUpperCase(Locale.ROOT));
        link.setStatus(req.proposeOnly() ? "PROPOSED" : "PUBLISHED");
        link.setValidFrom(now);
        link.setCreatedAt(now);
        link.setCreatedBy(userId);
        link.setUpdatedAt(now);
        link.setUpdatedBy(userId);
        linkMapper.insert(link);
        return toLinkViews(ws, List.of(link)).get(0);
    }

    @Transactional
    public void deleteLink(String linkId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        SemanticLink existing = linkMapper.selectOne(new LambdaQueryWrapper<SemanticLink>()
                .eq(SemanticLink::getWorkspaceId, ws)
                .eq(SemanticLink::getId, linkId));
        if (existing == null) {
            throw new BizException(ErrorCode.SYS_NOT_FOUND, "关系边不存在");
        }
        linkMapper.deleteById(linkId);
    }

    // ── Value Dictionary (L1/L2 Only · C5) ───────────────────────────────

    public List<ValueDictView> listValueDict(String objectId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        requireObject(ws, objectId);
        return valueDictMapper.selectList(new LambdaQueryWrapper<SemanticValueDict>()
                        .eq(SemanticValueDict::getWorkspaceId, ws)
                        .eq(SemanticValueDict::getObjectId, objectId)
                        .orderByAsc(SemanticValueDict::getRawValue))
                .stream()
                .map(this::toValueDictView)
                .toList();
    }

    @Transactional
    public ValueDictView upsertValueDict(String objectId, ValueDictRequest req) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String userId = WorkspaceContext.require().userId();
        SemanticObject obj = requireObject(ws, objectId);

        // C5 红线：严禁对 L3/L4 敏感对象采集维度值字典
        if ("L3".equalsIgnoreCase(obj.getMaxSensitivity()) || "L4".equalsIgnoreCase(obj.getMaxSensitivity())) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID,
                    "敏感级别为 " + obj.getMaxSensitivity() + " 的对象严禁建立值字典（CONSTITUTION C5）");
        }
        if (req == null || req.rawValue() == null || req.rawValue().isBlank()
                || req.displayLabel() == null || req.displayLabel().isBlank()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, "rawValue 与 displayLabel 不能为空");
        }
        String synJson = writeJson(req.synonyms() == null ? List.of() : req.synonyms());
        assertNoPlaintextPii(req.rawValue(), "rawValue");
        assertNoPlaintextPii(req.displayLabel(), "displayLabel");
        assertNoPlaintextPii(synJson, "synonyms");

        SemanticValueDict existing = valueDictMapper.selectOne(new LambdaQueryWrapper<SemanticValueDict>()
                .eq(SemanticValueDict::getWorkspaceId, ws)
                .eq(SemanticValueDict::getObjectId, objectId)
                .eq(SemanticValueDict::getRawValue, req.rawValue().trim()));
        if (existing != null) {
            existing.setDisplayLabel(req.displayLabel().trim());
            existing.setSynonymsJson(synJson);
            valueDictMapper.updateById(existing);
            return toValueDictView(existing);
        }

        SemanticValueDict entry = new SemanticValueDict();
        entry.setId(Ids.of("semval"));
        entry.setWorkspaceId(ws);
        entry.setObjectId(objectId);
        entry.setRawValue(req.rawValue().trim());
        entry.setDisplayLabel(req.displayLabel().trim());
        entry.setSynonymsJson(synJson);
        entry.setSourceKind(req.sourceKind() == null ? "HUMAN" : req.sourceKind().toUpperCase(Locale.ROOT));
        entry.setCreatedAt(Instant.now());
        entry.setCreatedBy(userId);
        valueDictMapper.insert(entry);
        return toValueDictView(entry);
    }

    @Transactional
    public void deleteValueDict(String objectId, String entryId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        requireObject(ws, objectId);
        SemanticValueDict existing = valueDictMapper.selectOne(new LambdaQueryWrapper<SemanticValueDict>()
                .eq(SemanticValueDict::getWorkspaceId, ws)
                .eq(SemanticValueDict::getObjectId, objectId)
                .eq(SemanticValueDict::getId, entryId));
        if (existing == null) {
            throw new BizException(ErrorCode.SYS_NOT_FOUND, "值字典条目不存在");
        }
        valueDictMapper.deleteById(entryId);
    }

    // ── Staging Proposals (C9) ───────────────────────────────────────────

    public List<ProposalItem> listProposals(String statusFilter) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String status = statusFilter == null || statusFilter.isBlank()
                ? "PROPOSED" : statusFilter.trim().toUpperCase(Locale.ROOT);
        List<ProposalItem> items = new ArrayList<>();

        List<SemanticObject> objs = objectMapperDao.selectList(new LambdaQueryWrapper<SemanticObject>()
                .eq(SemanticObject::getWorkspaceId, ws)
                .eq(SemanticObject::getStatus, status)
                .orderByDesc(SemanticObject::getCreatedAt));
        for (SemanticObject o : objs) {
            items.add(new ProposalItem(
                    "OBJECT", o.getId(), o.getName() + " (" + o.getCode() + ")",
                    o.getObjectType() + " · " + safeStr(o.getStandardRef()),
                    o.getSourceKind(), o.getStatus(), o.getRejectionReason(),
                    Map.of("code", o.getCode(), "objectType", o.getObjectType(),
                            "spec", readJsonMap(o.getSpecJson()),
                            "aiContext", readJsonMap(o.getAiContextJson())),
                    o.getCreatedAt()));
        }

        List<LinkView> links = listLinks(null, null, status);
        for (LinkView l : links) {
            items.add(new ProposalItem(
                    "LINK", l.id(), l.fromName() + " -[" + l.linkType() + "]-> " + l.toName(),
                    l.edgeCategory() + (l.rationale() == null ? "" : " · " + l.rationale()),
                    l.sourceKind(), l.status(), l.rejectionReason(),
                    Map.of("fromCode", safeStr(l.fromCode()), "toCode", safeStr(l.toCode()),
                            "edgeCategory", l.edgeCategory(), "linkType", l.linkType(),
                            "rationale", safeStr(l.rationale())),
                    l.createdAt()));
        }
        items.sort(Comparator.comparing(ProposalItem::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return items;
    }

    @Transactional
    public ProposalItem approveProposal(String kind, String id) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String userId = WorkspaceContext.require().userId();
        if ("OBJECT".equalsIgnoreCase(kind)) {
            ObjectView published = publishObject(id);
            return new ProposalItem("OBJECT", published.id(), published.name(),
                    "已批准并发布 v" + published.versionNo(), published.sourceKind(),
                    published.status(), null, Map.of("contentDigest", safeStr(published.contentDigest())),
                    published.publishedAt());
        }
        if ("LINK".equalsIgnoreCase(kind)) {
            SemanticLink link = linkMapper.selectOne(new LambdaQueryWrapper<SemanticLink>()
                    .eq(SemanticLink::getWorkspaceId, ws)
                    .eq(SemanticLink::getId, id));
            if (link == null) {
                throw new BizException(ErrorCode.SYS_NOT_FOUND, "关系提案不存在");
            }
            if (!"PROPOSED".equals(link.getStatus())) {
                throw new BizException(ErrorCode.MTD_SEMANTIC_STATE, "仅 PROPOSED 状态允许审批晋升");
            }
            Instant now = Instant.now();
            link.setStatus("PUBLISHED");
            link.setValidFrom(now);
            link.setUpdatedAt(now);
            link.setUpdatedBy(userId);
            linkMapper.updateById(link);
            LinkView view = toLinkViews(ws, List.of(link)).get(0);
            return new ProposalItem("LINK", view.id(), view.fromName() + " -> " + view.toName(),
                    "已批准并发布关系 " + view.linkType(), view.sourceKind(),
                    view.status(), null, Map.of("linkType", view.linkType()), now);
        }
        throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "不支持的提案类型: " + kind);
    }

    @Transactional
    public ProposalItem rejectProposal(String kind, String id, String reason) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String userId = WorkspaceContext.require().userId();
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "驳回提案必须填写驳回理由（用于防重提账本）");
        }
        Instant now = Instant.now();
        if ("OBJECT".equalsIgnoreCase(kind)) {
            SemanticObject obj = requireObject(ws, id);
            if (!"PROPOSED".equals(obj.getStatus())) {
                throw new BizException(ErrorCode.MTD_SEMANTIC_STATE, "仅 PROPOSED 状态允许驳回");
            }
            obj.setStatus("REJECTED");
            obj.setRejectionReason(reason.trim());
            obj.setUpdatedAt(now);
            obj.setUpdatedBy(userId);
            objectMapperDao.updateById(obj);
            return new ProposalItem("OBJECT", obj.getId(), obj.getName(),
                    "已驳回", obj.getSourceKind(), "REJECTED", obj.getRejectionReason(), Map.of(), now);
        }
        if ("LINK".equalsIgnoreCase(kind)) {
            SemanticLink link = linkMapper.selectOne(new LambdaQueryWrapper<SemanticLink>()
                    .eq(SemanticLink::getWorkspaceId, ws)
                    .eq(SemanticLink::getId, id));
            if (link == null) {
                throw new BizException(ErrorCode.SYS_NOT_FOUND, "关系提案不存在");
            }
            if (!"PROPOSED".equals(link.getStatus())) {
                throw new BizException(ErrorCode.MTD_SEMANTIC_STATE, "仅 PROPOSED 状态允许驳回");
            }
            link.setStatus("REJECTED");
            link.setRejectionReason(reason.trim());
            link.setUpdatedAt(now);
            link.setUpdatedBy(userId);
            linkMapper.updateById(link);
            return new ProposalItem("LINK", link.getId(), link.getLinkType(),
                    "已驳回", link.getSourceKind(), "REJECTED", link.getRejectionReason(), Map.of(), now);
        }
        throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "不支持的提案类型: " + kind);
    }

    public boolean isPreviouslyRejectedObject(String workspaceId, String normalizedCode) {
        Long cnt = objectMapperDao.selectCount(new LambdaQueryWrapper<SemanticObject>()
                .eq(SemanticObject::getWorkspaceId, workspaceId)
                .eq(SemanticObject::getStatus, "REJECTED")
                .apply("lower(code) = {0}", normalizedCode));
        return cnt != null && cnt > 0;
    }

    public boolean isPreviouslyRejectedLink(String workspaceId, String fromId, String toId, String linkType) {
        Long cnt = linkMapper.selectCount(new LambdaQueryWrapper<SemanticLink>()
                .eq(SemanticLink::getWorkspaceId, workspaceId)
                .eq(SemanticLink::getFromObjectId, fromId)
                .eq(SemanticLink::getToObjectId, toId)
                .eq(SemanticLink::getLinkType, linkType)
                .eq(SemanticLink::getStatus, "REJECTED"));
        return cnt != null && cnt > 0;
    }

    // ── Hop-by-Hop ACL Subgraph Search (C5) ──────────────────────────────

    /**
     * 面向 AI 与前端的受控 k-Hop 子图检索（严格落实检索前 ACL + 逐跳 ACL 剪枝，CONSTITUTION C5）。
     *
     * <p>若 {@code allowedDatasetIds} 非空，任何节点若绑定了物理表且全部绑定表均不在允许集合内，
     * 则在种子召回与每一跳扩展时立即剪枝，绝不向下继续穿越。
     */
    public SubgraphResult searchSubgraph(SubgraphSearchRequest req) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String query = req == null || req.query() == null ? "" : req.query().trim();
        int maxHops = req == null || req.maxHops() == null ? 2 : Math.max(0, Math.min(3, req.maxHops()));
        int topK = req == null || req.topK() == null ? 5 : Math.max(1, Math.min(20, req.topK()));
        Set<String> allowedDatasets = req == null ? null : req.allowedDatasetIds();
        boolean externalModel = req != null && Boolean.TRUE.equals(req.externalModel());

        Instant now = Instant.now();
        List<SemanticObject> published = objectMapperDao.selectList(
                new LambdaQueryWrapper<SemanticObject>()
                        .eq(SemanticObject::getWorkspaceId, ws)
                        .eq(SemanticObject::getStatus, "PUBLISHED"))
                .stream()
                .filter(o -> o.getValidTo() == null || o.getValidTo().isAfter(now))
                .toList();

        Map<String, SemanticObject> byId = published.stream()
                .collect(Collectors.toMap(SemanticObject::getId, Function.identity(), (a, b) -> a));

        List<SemanticMapping> allMappings = mappingMapper.selectList(
                new LambdaQueryWrapper<SemanticMapping>().eq(SemanticMapping::getWorkspaceId, ws));
        Map<String, List<SemanticMapping>> mappingsByObj = allMappings.stream()
                .collect(Collectors.groupingBy(SemanticMapping::getObjectId));

        List<SemanticLink> publishedLinks = linkMapper.selectList(
                new LambdaQueryWrapper<SemanticLink>()
                        .eq(SemanticLink::getWorkspaceId, ws)
                        .eq(SemanticLink::getStatus, "PUBLISHED"))
                .stream()
                .filter(l -> l.getValidTo() == null || l.getValidTo().isAfter(now))
                .toList();

        // Step 1: 检索前鉴权与种子召回
        int prunedCount = 0;
        List<String> prunedReasons = new ArrayList<>();
        List<ScoredObject> candidates = new ArrayList<>();
        String qLower = query.toLowerCase(Locale.ROOT);
        List<String> tokens = tokenize(qLower);

        for (SemanticObject obj : published) {
            int score = scoreMatch(obj, qLower, tokens);
            if (!query.isBlank() && score <= 0) {
                continue;
            }
            if (!isNodeAuthorized(obj, mappingsByObj.getOrDefault(obj.getId(), List.of()), allowedDatasets)) {
                prunedCount++;
                prunedReasons.add("PRE_ACL_PRUNED:" + obj.getCode());
                continue;
            }
            candidates.add(new ScoredObject(obj, query.isBlank() ? 1 : score));
        }

        candidates.sort(Comparator.comparingInt(ScoredObject::score).reversed()
                .thenComparing(s -> s.object().getCode()));
        List<SemanticObject> seeds = candidates.stream()
                .limit(topK)
                .map(ScoredObject::object)
                .toList();

        // Step 2: 有界 k-Hop 逐跳权限扩展（Hop-by-Hop ACL）
        Map<String, List<SemanticLink>> adj = new HashMap<>();
        for (SemanticLink link : publishedLinks) {
            adj.computeIfAbsent(link.getFromObjectId(), k -> new ArrayList<>()).add(link);
            adj.computeIfAbsent(link.getToObjectId(), k -> new ArrayList<>()).add(link);
        }

        Map<String, Integer> visitedDepth = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (SemanticObject seed : seeds) {
            visitedDepth.put(seed.getId(), 0);
            queue.add(seed.getId());
        }

        Set<String> includedLinkIds = new LinkedHashSet<>();
        List<SemanticLink> includedLinks = new ArrayList<>();

        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            int depth = visitedDepth.get(currentId);
            if (depth >= maxHops) {
                continue;
            }
            for (SemanticLink edge : adj.getOrDefault(currentId, List.of())) {
                String neighborId = edge.getFromObjectId().equals(currentId)
                        ? edge.getToObjectId() : edge.getFromObjectId();
                SemanticObject neighbor = byId.get(neighborId);
                if (neighbor == null) {
                    continue;
                }
                // 逐跳 ACL 检查：遇到无权节点立即剪枝，严禁穿越！
                if (!isNodeAuthorized(neighbor, mappingsByObj.getOrDefault(neighborId, List.of()), allowedDatasets)) {
                    prunedCount++;
                    prunedReasons.add("HOP_" + (depth + 1) + "_ACL_PRUNED:" + neighbor.getCode());
                    continue;
                }
                if (includedLinkIds.add(edge.getId())) {
                    includedLinks.add(edge);
                }
                if (!visitedDepth.containsKey(neighborId)) {
                    visitedDepth.put(neighborId, depth + 1);
                    queue.add(neighborId);
                }
            }
        }

        // Step 3: 组装裁剪后的节点与映射（外部模型路由时剥离 L3/L4 样例）
        List<SubgraphNode> nodes = new ArrayList<>();
        List<String> snapshotRefs = new ArrayList<>();
        for (var entry : visitedDepth.entrySet()) {
            SemanticObject obj = byId.get(entry.getKey());
            if (obj == null) {
                continue;
            }
            List<SemanticMapping> allowedMappings = mappingsByObj.getOrDefault(obj.getId(), List.of())
                    .stream()
                    .filter(m -> allowedDatasets == null || allowedDatasets.contains(m.getCatalogDatasetId()))
                    .toList();
            Map<String, Object> aiCtx = readJsonMap(obj.getAiContextJson());
            if (externalModel && ("L3".equalsIgnoreCase(obj.getMaxSensitivity())
                    || "L4".equalsIgnoreCase(obj.getMaxSensitivity()))) {
                aiCtx = new LinkedHashMap<>(aiCtx);
                aiCtx.remove("safeEnumExamples");
                aiCtx.put("redactedForExternalModel", true);
            }
            nodes.add(new SubgraphNode(
                    obj.getId(), obj.getCode(), obj.getName(), obj.getObjectType(),
                    obj.getStandardRef(), obj.getMaxSensitivity(),
                    obj.getVersionNo() == null ? 1 : obj.getVersionNo(),
                    obj.getContentDigest(), entry.getValue(),
                    readJsonMap(obj.getSpecJson()), aiCtx,
                    toMappingViews(ws, allowedMappings)));
            snapshotRefs.add(obj.getCode() + "@v" + (obj.getVersionNo() == null ? 1 : obj.getVersionNo())
                    + "#" + (obj.getContentDigest() == null ? "draft" : obj.getContentDigest().substring(0, Math.min(12, obj.getContentDigest().length()))));
        }

        return new SubgraphResult(
                query, maxHops, nodes, toLinkViews(ws, includedLinks),
                prunedCount, prunedReasons, snapshotRefs);
    }

    // ── Impact Analysis (Column / Dataset -> Downstream Semantic Objects) ─

    public ImpactResult impactByColumn(String columnId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        List<SemanticMapping> direct = mappingMapper.selectList(new LambdaQueryWrapper<SemanticMapping>()
                .eq(SemanticMapping::getWorkspaceId, ws)
                .eq(SemanticMapping::getCatalogColumnId, columnId));
        return computeUpstreamImpact(ws, "COLUMN", columnId, direct);
    }

    public ImpactResult impactByDataset(String datasetId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        List<SemanticMapping> direct = mappingMapper.selectList(new LambdaQueryWrapper<SemanticMapping>()
                .eq(SemanticMapping::getWorkspaceId, ws)
                .eq(SemanticMapping::getCatalogDatasetId, datasetId));
        return computeUpstreamImpact(ws, "DATASET", datasetId, direct);
    }

    private ImpactResult computeUpstreamImpact(String ws, String targetType, String targetId,
                                               List<SemanticMapping> directMappings) {
        Set<String> seedIds = directMappings.stream()
                .map(SemanticMapping::getObjectId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (seedIds.isEmpty()) {
            return new ImpactResult(targetType, targetId, List.of());
        }

        List<SemanticObject> allObjects = objectMapperDao.selectList(
                new LambdaQueryWrapper<SemanticObject>().eq(SemanticObject::getWorkspaceId, ws));
        Map<String, SemanticObject> byId = allObjects.stream()
                .collect(Collectors.toMap(SemanticObject::getId, Function.identity(), (a, b) -> a));
        List<SemanticLink> allLinks = linkMapper.selectList(
                new LambdaQueryWrapper<SemanticLink>()
                        .eq(SemanticLink::getWorkspaceId, ws)
                        .eq(SemanticLink::getStatus, "PUBLISHED"));

        // 依赖方向：若 Metric DERIVED_FROM Fact/Metric，则 toObjectId -> fromObjectId 为受影响方向；
        // 若 Entity HAS_ATTRIBUTE Attribute，则 toObjectId -> fromObjectId 为受影响方向。
        Map<String, List<SemanticLink>> reverseDeps = new HashMap<>();
        for (SemanticLink l : allLinks) {
            if ("DERIVED_FROM".equals(l.getLinkType())
                    || "HAS_ATTRIBUTE".equals(l.getLinkType())
                    || "HAS_FACT".equals(l.getLinkType())
                    || "HIERARCHY_PARENT".equals(l.getLinkType())) {
                reverseDeps.computeIfAbsent(l.getToObjectId(), k -> new ArrayList<>()).add(l);
            }
        }

        Map<String, ImpactItem> visited = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String sid : seedIds) {
            SemanticObject obj = byId.get(sid);
            if (obj != null) {
                visited.put(sid, new ImpactItem(
                        obj.getId(), obj.getCode(), obj.getName(), obj.getObjectType(),
                        obj.getStatus(), obj.getVersionNo() == null ? 1 : obj.getVersionNo(),
                        0, "DIRECT_MAPPING"));
                queue.add(sid);
            }
        }

        while (!queue.isEmpty()) {
            String cur = queue.poll();
            ImpactItem curItem = visited.get(cur);
            if (curItem.depth() >= 5) {
                continue;
            }
            for (SemanticLink dep : reverseDeps.getOrDefault(cur, List.of())) {
                String upstreamId = dep.getFromObjectId();
                if (visited.containsKey(upstreamId)) {
                    continue;
                }
                SemanticObject up = byId.get(upstreamId);
                if (up != null) {
                    visited.put(upstreamId, new ImpactItem(
                            up.getId(), up.getCode(), up.getName(), up.getObjectType(),
                            up.getStatus(), up.getVersionNo() == null ? 1 : up.getVersionNo(),
                            curItem.depth() + 1, curItem.relationPath() + " <- " + dep.getLinkType()));
                    queue.add(upstreamId);
                }
            }
        }
        return new ImpactResult(targetType, targetId, new ArrayList<>(visited.values()));
    }

    // ── Internal Helpers (Package-Private for Compiler/Linking) ──────────

    List<SemanticObject> listPublishedObjectsInternal(String workspaceId) {
        Instant now = Instant.now();
        List<SemanticObject> raw = objectMapperDao.selectList(new LambdaQueryWrapper<SemanticObject>()
                        .eq(SemanticObject::getStatus, "PUBLISHED")
                        .and(w -> w.eq(SemanticObject::getWorkspaceId, workspaceId)
                                .or().eq(SemanticObject::getScope, "GLOBAL")))
                .stream()
                .filter(o -> o.getValidTo() == null || o.getValidTo().isAfter(now))
                .toList();
        // WORKSPACE 同 code 优先于 GLOBAL，实现“权威标准共享 + 空间级覆写”
        Map<String, SemanticObject> resolved = new LinkedHashMap<>();
        for (SemanticObject obj : raw) {
            if ("GLOBAL".equalsIgnoreCase(obj.getScope())) {
                resolved.putIfAbsent(obj.getCode().toLowerCase(Locale.ROOT), obj);
            }
        }
        for (SemanticObject obj : raw) {
            if (workspaceId.equals(obj.getWorkspaceId()) && !"GLOBAL".equalsIgnoreCase(obj.getScope())) {
                resolved.put(obj.getCode().toLowerCase(Locale.ROOT), obj);
            }
        }
        // 保留本空间发布的 GLOBAL 对象
        for (SemanticObject obj : raw) {
            if (workspaceId.equals(obj.getWorkspaceId()) && "GLOBAL".equalsIgnoreCase(obj.getScope())) {
                resolved.putIfAbsent(obj.getCode().toLowerCase(Locale.ROOT), obj);
            }
        }
        return new ArrayList<>(resolved.values());
    }

    List<SemanticMapping> listWorkspaceMappingsInternal(String workspaceId) {
        return mappingMapper.selectList(new LambdaQueryWrapper<SemanticMapping>()
                .eq(SemanticMapping::getWorkspaceId, workspaceId));
    }

    List<SemanticLink> listPublishedLinksInternal(String workspaceId) {
        Instant now = Instant.now();
        return linkMapper.selectList(new LambdaQueryWrapper<SemanticLink>()
                        .eq(SemanticLink::getWorkspaceId, workspaceId)
                        .eq(SemanticLink::getStatus, "PUBLISHED"))
                .stream()
                .filter(l -> l.getValidTo() == null || l.getValidTo().isAfter(now))
                .toList();
    }

    // ── Private Helpers ──────────────────────────────────────────────────

    private record ScoredObject(SemanticObject object, int score) {
    }

    private boolean isNodeAuthorized(SemanticObject obj, List<SemanticMapping> mappings, Set<String> allowedDatasets) {
        if (allowedDatasets == null) {
            return true;
        }
        // 纯概念/流程节点若未绑定物理表则允许读取；若绑定了物理表，至少有一张表在授权集内才允许通过
        if (mappings == null || mappings.isEmpty()) {
            return true;
        }
        return mappings.stream().anyMatch(m -> allowedDatasets.contains(m.getCatalogDatasetId()));
    }

    private int scoreMatch(SemanticObject obj, String qLower, List<String> tokens) {
        int score = 0;
        String name = safeStr(obj.getName()).toLowerCase(Locale.ROOT);
        String code = safeStr(obj.getCode()).toLowerCase(Locale.ROOT);
        String aiCtx = safeStr(obj.getAiContextJson()).toLowerCase(Locale.ROOT);

        if (qLower.equals(name) || qLower.equals(code)) {
            score += 100;
        }
        if (qLower.contains(name) && !name.isBlank()) {
            score += 60;
        }
        if (name.contains(qLower) || code.contains(qLower)) {
            score += 45;
        }
        Map<String, Object> ctx = readJsonMap(obj.getAiContextJson());
        if (ctx.get("aliases") instanceof List<?> aliases) {
            for (Object a : aliases) {
                String alias = String.valueOf(a).toLowerCase(Locale.ROOT).trim();
                if (!alias.isBlank() && (qLower.contains(alias) || alias.contains(qLower))) {
                    score += 55;
                }
            }
        }
        for (String tok : tokens) {
            if (tok.length() < 2) {
                continue;
            }
            if (name.contains(tok) || code.contains(tok)) {
                score += 20;
            } else if (aiCtx.contains(tok)) {
                score += 10;
            }
        }
        return score;
    }

    private List<String> tokenize(String qLower) {
        if (qLower.isBlank()) {
            return List.of();
        }
        String[] raw = qLower.split("[\\s,，。？?、/()（）\\-_]+");
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    private void validateObjectRequest(ObjectRequest req) {
        if (req == null || req.code() == null || req.code().isBlank()
                || req.name() == null || req.name().isBlank()
                || req.objectType() == null || req.objectType().isBlank()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, "code、name、objectType 为必填项");
        }
        String type = req.objectType().trim().toUpperCase(Locale.ROOT);
        if (!OBJECT_TYPES.contains(type)) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, "不支持的语义对象类型: " + type);
        }
    }

    private void validateLinkCategoryAndType(String category, String linkType) {
        if (!EDGE_CATEGORIES.contains(category)) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, "不支持的边大类: " + category);
        }
        boolean ok = switch (category) {
            case "STRUCTURAL" -> STRUCTURAL_LINKS.contains(linkType);
            case "SEMANTIC" -> SEMANTIC_LINKS.contains(linkType);
            case "CAUSAL" -> CAUSAL_LINKS.contains(linkType);
            default -> false;
        };
        if (!ok) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID,
                    "边类型 " + linkType + " 不属于大类 " + category);
        }
    }

    private void assertNoPlaintextPii(String text, String fieldName) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (PII_PHONE.matcher(text).find() || PII_ID_CARD.matcher(text).find()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID,
                    "字段 " + fieldName + " 检测到疑似 L3 明文 PII（手机号/身份证号），已按 CONSTITUTION C5 拦截");
        }
    }

    private String computeMaxSensitivity(String ws, List<SemanticMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return "L1";
        }
        Set<String> colIds = mappings.stream()
                .map(SemanticMapping::getCatalogColumnId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (colIds.isEmpty()) {
            return "L1";
        }
        List<ColumnSensitivity> sensList = sensitivityMapper.selectList(
                new LambdaQueryWrapper<ColumnSensitivity>()
                        .eq(ColumnSensitivity::getWorkspaceId, ws)
                        .in(ColumnSensitivity::getColumnId, colIds)
                        .ne(ColumnSensitivity::getStatus, "REJECTED"));
        String best = "L1";
        int bestRank = 1;
        for (ColumnSensitivity cs : sensList) {
            String lvl = cs.getLevel() == null ? "L1" : cs.getLevel().toUpperCase(Locale.ROOT);
            int rank = SENSITIVITY_RANK.getOrDefault(lvl, 1);
            if (rank > bestRank) {
                bestRank = rank;
                best = lvl;
            }
        }
        return best;
    }

    private SemanticObject requireObject(String ws, String id) {
        SemanticObject obj = objectMapperDao.selectOne(new LambdaQueryWrapper<SemanticObject>()
                .eq(SemanticObject::getId, id)
                .and(w -> w.eq(SemanticObject::getWorkspaceId, ws)
                        .or(g -> g.eq(SemanticObject::getScope, "GLOBAL")
                                .eq(SemanticObject::getStatus, "PUBLISHED"))));
        if (obj == null) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_OBJECT_NOT_FOUND, "语义对象不存在: " + id);
        }
        return obj;
    }

    private List<ObjectView> toObjectViews(String ws, List<SemanticObject> objects) {
        if (objects.isEmpty()) {
            return List.of();
        }
        Map<String, BusinessDomain> domains = loadDomains(ws);
        Set<String> ids = objects.stream().map(SemanticObject::getId).collect(Collectors.toSet());
        Map<String, Long> mappingCounts = mappingMapper.selectList(
                        new LambdaQueryWrapper<SemanticMapping>()
                                .eq(SemanticMapping::getWorkspaceId, ws)
                                .in(SemanticMapping::getObjectId, ids))
                .stream()
                .collect(Collectors.groupingBy(SemanticMapping::getObjectId, Collectors.counting()));

        List<SemanticLink> links = linkMapper.selectList(
                new LambdaQueryWrapper<SemanticLink>().eq(SemanticLink::getWorkspaceId, ws));
        Map<String, Long> linkCounts = new HashMap<>();
        for (SemanticLink l : links) {
            if (ids.contains(l.getFromObjectId())) {
                linkCounts.merge(l.getFromObjectId(), 1L, Long::sum);
            }
            if (ids.contains(l.getToObjectId())) {
                linkCounts.merge(l.getToObjectId(), 1L, Long::sum);
            }
        }

        List<ObjectView> out = new ArrayList<>(objects.size());
        for (SemanticObject o : objects) {
            BusinessDomain d = o.getDomainId() == null ? null : domains.get(o.getDomainId());
            out.add(new ObjectView(
                    o.getId(), o.getCode(), o.getName(), o.getObjectType(),
                    o.getDomainId(), d == null ? null : d.getName(),
                    o.getStandardRef(), o.getGlossaryTermId(),
                    readJsonMap(o.getSpecJson()), readJsonMap(o.getAiContextJson()),
                    o.getMaxSensitivity() == null ? "L1" : o.getMaxSensitivity(),
                    o.getStatus(), o.getVersionNo() == null ? 1 : o.getVersionNo(),
                    o.getValidFrom(), o.getValidTo(), o.getOwnerUsername(),
                    o.getContentDigest(), o.getSourceKind(), o.getRejectionReason(),
                    o.getPublishedBy(), o.getPublishedAt(), o.getCreatedAt(), o.getUpdatedAt(),
                    mappingCounts.getOrDefault(o.getId(), 0L),
                    linkCounts.getOrDefault(o.getId(), 0L),
                    o.getScope() == null ? "WORKSPACE" : o.getScope()));
        }
        return out;
    }

    private List<MappingView> toMappingViews(String ws, List<SemanticMapping> mappings) {
        if (mappings.isEmpty()) {
            return List.of();
        }
        Set<String> dsIds = mappings.stream().map(SemanticMapping::getCatalogDatasetId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> colIds = mappings.stream().map(SemanticMapping::getCatalogColumnId)
                .filter(Objects::nonNull).collect(Collectors.toSet());

        Map<String, CatalogDataset> datasets = dsIds.isEmpty() ? Map.of()
                : datasetMapper.selectList(new LambdaQueryWrapper<CatalogDataset>()
                        .eq(CatalogDataset::getWorkspaceId, ws)
                        .in(CatalogDataset::getId, dsIds))
                .stream().collect(Collectors.toMap(CatalogDataset::getId, Function.identity(), (a, b) -> a));

        Map<String, CatalogColumn> columns = colIds.isEmpty() ? Map.of()
                : columnMapper.selectList(new LambdaQueryWrapper<CatalogColumn>()
                        .in(CatalogColumn::getId, colIds))
                .stream().collect(Collectors.toMap(CatalogColumn::getId, Function.identity(), (a, b) -> a));

        Map<String, String> colSens = new HashMap<>();
        if (!colIds.isEmpty()) {
            for (ColumnSensitivity cs : sensitivityMapper.selectList(
                    new LambdaQueryWrapper<ColumnSensitivity>()
                            .eq(ColumnSensitivity::getWorkspaceId, ws)
                            .in(ColumnSensitivity::getColumnId, colIds)
                            .ne(ColumnSensitivity::getStatus, "REJECTED"))) {
                colSens.merge(cs.getColumnId(), cs.getLevel(), (a, b) ->
                        SENSITIVITY_RANK.getOrDefault(b, 1) > SENSITIVITY_RANK.getOrDefault(a, 1) ? b : a);
            }
        }

        List<MappingView> out = new ArrayList<>(mappings.size());
        for (SemanticMapping m : mappings) {
            CatalogDataset ds = datasets.get(m.getCatalogDatasetId());
            CatalogColumn col = m.getCatalogColumnId() == null ? null : columns.get(m.getCatalogColumnId());
            out.add(new MappingView(
                    m.getId(), m.getObjectId(), m.getMappingRole(),
                    m.getCatalogDatasetId(),
                    ds == null ? m.getCatalogDatasetId() : ds.getName(),
                    ds == null ? m.getCatalogDatasetId() : ds.getQualifiedName(),
                    m.getCatalogColumnId(),
                    col == null ? null : col.getName(),
                    m.getCatalogColumnId() == null ? "L1" : colSens.getOrDefault(m.getCatalogColumnId(), "L1"),
                    m.getExpressionSql(),
                    Boolean.TRUE.equals(m.getIsLookupTable()),
                    m.getPriority() == null ? 100 : m.getPriority(),
                    m.getStatus(), m.getCreatedAt(),
                    m.getWarehouseId(),
                    m.getLogicalTableId(),
                    readStringMap(m.getValueNormalizationJson()),
                    m.getSourceUnit(),
                    m.getTargetUnit()));
        }
        return out;
    }

    private List<LinkView> toLinkViews(String ws, List<SemanticLink> links) {
        if (links.isEmpty()) {
            return List.of();
        }
        Set<String> objIds = new HashSet<>();
        for (SemanticLink l : links) {
            objIds.add(l.getFromObjectId());
            objIds.add(l.getToObjectId());
        }
        Map<String, SemanticObject> objs = objIds.isEmpty() ? Map.of()
                : objectMapperDao.selectList(new LambdaQueryWrapper<SemanticObject>()
                        .eq(SemanticObject::getWorkspaceId, ws)
                        .in(SemanticObject::getId, objIds))
                .stream().collect(Collectors.toMap(SemanticObject::getId, Function.identity(), (a, b) -> a));

        List<LinkView> out = new ArrayList<>(links.size());
        for (SemanticLink l : links) {
            SemanticObject from = objs.get(l.getFromObjectId());
            SemanticObject to = objs.get(l.getToObjectId());
            out.add(new LinkView(
                    l.getId(),
                    l.getFromObjectId(), from == null ? null : from.getCode(), from == null ? l.getFromObjectId() : from.getName(),
                    l.getToObjectId(), to == null ? null : to.getCode(), to == null ? l.getToObjectId() : to.getName(),
                    l.getEdgeCategory(), l.getLinkType(), l.getCardinality(),
                    readJsonList(l.getJoinSpecJson()), l.getRationale(),
                    l.getSourceKind(), l.getStatus(), l.getRejectionReason(),
                    l.getValidFrom(), l.getValidTo(), l.getCreatedAt()));
        }
        return out;
    }

    private ValueDictView toValueDictView(SemanticValueDict v) {
        return new ValueDictView(
                v.getId(), v.getObjectId(), v.getRawValue(), v.getDisplayLabel(),
                readStringList(v.getSynonymsJson()), v.getSourceKind(), v.getCreatedAt());
    }

    private Map<String, BusinessDomain> loadDomains(String ws) {
        return domainMapper.selectList(new LambdaQueryWrapper<BusinessDomain>()
                        .eq(BusinessDomain::getWorkspaceId, ws))
                .stream().collect(Collectors.toMap(BusinessDomain::getId, Function.identity(), (a, b) -> a));
    }

    private String normalizeCode(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return "WORKSPACE";
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (!"GLOBAL".equals(upper) && !"WORKSPACE".equals(upper)) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, "不支持的语义作用域: " + raw);
        }
        return upper;
    }

    private Map<String, String> readStringMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String safeStr(String s) {
        return s == null ? "" : s;
    }

    private String writeJson(Object obj) {
        try {
            return json.writeValueAsString(obj);
        } catch (Exception e) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, "JSON 序列化失败: " + e.getMessage());
        }
    }

    private Map<String, Object> readJsonMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> readJsonList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> readStringList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
