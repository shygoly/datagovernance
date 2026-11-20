package com.datagov.app.controller;

import com.datagov.app.insight.InsightProperties;
import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.api.PageResult;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.service.MultiWarehouseQueryCompiler;
import com.datagov.metadata.service.MultiWarehouseQueryCompiler.MultiWarehousePlan;
import com.datagov.metadata.service.SemanticOntologyService;
import com.datagov.metadata.service.SemanticQueryCompiler.CompileContext;
import com.datagov.metadata.service.SemanticQueryCompiler.SemanticQuerySpec;
import com.datagov.metadata.service.SemanticWarehouseService;
import com.datagov.metadata.service.SemanticWarehouseService.AliasRequest;
import com.datagov.metadata.service.SemanticWarehouseService.HeterogeneousMappingMatrix;
import com.datagov.metadata.service.SemanticWarehouseService.LogicalTableRequest;
import com.datagov.metadata.service.SemanticWarehouseService.LogicalTableView;
import com.datagov.metadata.service.SemanticWarehouseService.WarehouseRequest;
import com.datagov.metadata.service.SemanticWarehouseService.WarehouseView;
import com.datagov.metadata.service.SemanticOntologyService.ImpactResult;
import com.datagov.metadata.service.SemanticOntologyService.LinkRequest;
import com.datagov.metadata.service.SemanticOntologyService.LinkView;
import com.datagov.metadata.service.SemanticOntologyService.MappingRequest;
import com.datagov.metadata.service.SemanticOntologyService.MappingView;
import com.datagov.metadata.service.SemanticOntologyService.ObjectRequest;
import com.datagov.metadata.service.SemanticOntologyService.ObjectView;
import com.datagov.metadata.service.SemanticOntologyService.ProposalItem;
import com.datagov.metadata.service.SemanticOntologyService.SemanticSummaryStats;
import com.datagov.metadata.service.SemanticOntologyService.SemanticViewOption;
import com.datagov.metadata.service.SemanticOntologyService.SubgraphResult;
import com.datagov.metadata.service.SemanticOntologyService.SubgraphSearchRequest;
import com.datagov.metadata.service.SemanticOntologyService.ValueDictRequest;
import com.datagov.metadata.service.SemanticOntologyService.ValueDictView;
import com.datagov.metadata.service.SemanticOntologyService.VersionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 统一本体语义元数据内核与轻量业务知识图谱控制器（OpenSpec: semantic-ontology-kernel）。
 */
@RestController
@RequestMapping("/api/v1/metadata/semantic")
@Tag(name = "语义本体与知识图谱", description = "单一语义真理源：逻辑对象、物理映射、知识图谱边、逐跳子图检索与影响分析")
public class SemanticOntologyController {

    private final SemanticOntologyService semanticService;
    private final SemanticWarehouseService warehouseService;
    private final MultiWarehouseQueryCompiler multiWarehouseCompiler;
    private final InsightProperties insightProperties;

    public SemanticOntologyController(SemanticOntologyService semanticService,
                                      SemanticWarehouseService warehouseService,
                                      MultiWarehouseQueryCompiler multiWarehouseCompiler,
                                      InsightProperties insightProperties) {
        this.semanticService = semanticService;
        this.warehouseService = warehouseService;
        this.multiWarehouseCompiler = multiWarehouseCompiler;
        this.insightProperties = insightProperties;
    }

    public record RollbackRequest(int versionNo) {
    }

    public record RejectRequest(String reason) {
    }

    @GetMapping("/summary")
    @RequirePermission("metadata:semantic:read")
    @Operation(summary = "语义本体工作台概览指标")
    public ApiResponse<SemanticSummaryStats> summary() {
        return ApiResponse.ok(semanticService.summaryStats());
    }

    @GetMapping("/views")
    @RequirePermission("metadata:semantic:read")
    @Operation(summary = "当前空间已发布的语义主题视图")
    public ApiResponse<List<SemanticViewOption>> views() {
        return ApiResponse.ok(semanticService.listSemanticViews());
    }

    // ── Objects ──────────────────────────────────────────────────────────

    @GetMapping("/objects")
    @RequirePermission("metadata:semantic:read")
    @Operation(summary = "分页查询语义对象（含本空间与已发布 GLOBAL 权威本体）")
    public ApiResponse<PageResult<ObjectView>> listObjects(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String domainId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String scope) {
        return ApiResponse.ok(semanticService.listObjects(page, size, keyword, objectType, domainId, status, scope));
    }

    @GetMapping("/objects/{id}")
    @RequirePermission("metadata:semantic:read")
    @Operation(summary = "查看语义对象详情")
    public ApiResponse<ObjectView> getObject(@PathVariable String id) {
        return ApiResponse.ok(semanticService.getObject(id));
    }

    @PostMapping("/objects")
    @RequirePermission("metadata:semantic:manage")
    @Operation(summary = "新建语义对象（DRAFT）")
    public ApiResponse<ObjectView> createObject(@RequestBody ObjectRequest request) {
        return ApiResponse.ok(semanticService.createObject(request));
    }

    @PutMapping("/objects/{id}")
    @RequirePermission("metadata:semantic:manage")
    @Operation(summary = "更新语义对象")
    public ApiResponse<ObjectView> updateObject(@PathVariable String id,
                                                @RequestBody ObjectRequest request) {
        return ApiResponse.ok(semanticService.updateObject(id, request));
    }

    @PostMapping("/objects/{id}/publish")
    @RequirePermission("metadata:semantic:publish")
    @Operation(summary = "发布语义对象并冻结双时间态不可变快照（C2/C8）")
    public ApiResponse<ObjectView> publishObject(@PathVariable String id) {
        return ApiResponse.ok(semanticService.publishObject(id));
    }

    @PostMapping("/objects/{id}/deprecate")
    @RequirePermission("metadata:semantic:publish")
    @Operation(summary = "废弃语义对象")
    public ApiResponse<ObjectView> deprecateObject(@PathVariable String id) {
        return ApiResponse.ok(semanticService.deprecateObject(id));
    }

    @DeleteMapping("/objects/{id}")
    @RequirePermission("metadata:semantic:manage")
    @Operation(summary = "删除未发布的语义对象")
    public ApiResponse<Void> deleteObject(@PathVariable String id) {
        semanticService.deleteObject(id);
        return ApiResponse.ok();
    }

    @GetMapping("/objects/{id}/versions")
    @RequirePermission("metadata:semantic:read")
    @Operation(summary = "查看对象双时间态版本快照历史")
    public ApiResponse<List<VersionView>> listVersions(@PathVariable String id) {
        return ApiResponse.ok(semanticService.listVersions(id));
    }

    @PostMapping("/objects/{id}/rollback")
    @RequirePermission("metadata:semantic:publish")
    @Operation(summary = "回滚语义对象至指定历史版本并生成新发布版本")
    public ApiResponse<ObjectView> rollbackObject(@PathVariable String id,
                                                  @RequestBody RollbackRequest request) {
        return ApiResponse.ok(semanticService.rollbackObject(id, request.versionNo()));
    }

    // ── Physical Mappings ────────────────────────────────────────────────

    @GetMapping("/objects/{id}/mappings")
    @RequirePermission("metadata:semantic:read")
    @Operation(summary = "查看对象的逻辑→物理表列映射")
    public ApiResponse<List<MappingView>> listMappings(@PathVariable String id) {
        return ApiResponse.ok(semanticService.listMappings(id));
    }

    @PostMapping("/objects/{id}/mappings")
    @RequirePermission("metadata:semantic:manage")
    @Operation(summary = "新增物理表列映射（含只读 SQL 表达式护栏校验）")
    public ApiResponse<MappingView> addMapping(@PathVariable String id,
                                               @RequestBody MappingRequest request) {
        return ApiResponse.ok(semanticService.addMapping(id, request));
    }

    @DeleteMapping("/objects/{id}/mappings/{mappingId}")
    @RequirePermission("metadata:semantic:manage")
    @Operation(summary = "删除物理表列映射")
    public ApiResponse<Void> deleteMapping(@PathVariable String id,
                                           @PathVariable String mappingId) {
        semanticService.deleteMapping(id, mappingId);
        return ApiResponse.ok();
    }

    // ── Value Dictionary (L1/L2 Only) ────────────────────────────────────

    @GetMapping("/objects/{id}/value-dict")
    @RequirePermission("metadata:semantic:read")
    @Operation(summary = "查看 L1/L2 安全维度值对齐字典")
    public ApiResponse<List<ValueDictView>> listValueDict(@PathVariable String id) {
        return ApiResponse.ok(semanticService.listValueDict(id));
    }

    @PostMapping("/objects/{id}/value-dict")
    @RequirePermission("metadata:semantic:manage")
    @Operation(summary = "新增/更新维度值字典条目（L3/L4 对象按 C5 拦截）")
    public ApiResponse<ValueDictView> upsertValueDict(@PathVariable String id,
                                                      @RequestBody ValueDictRequest request) {
        return ApiResponse.ok(semanticService.upsertValueDict(id, request));
    }

    @DeleteMapping("/objects/{id}/value-dict/{entryId}")
    @RequirePermission("metadata:semantic:manage")
    @Operation(summary = "删除维度值字典条目")
    public ApiResponse<Void> deleteValueDict(@PathVariable String id,
                                             @PathVariable String entryId) {
        semanticService.deleteValueDict(id, entryId);
        return ApiResponse.ok();
    }

    // ── Links (Structural + KG Semantic/Causal) ──────────────────────────

    @GetMapping("/links")
    @RequirePermission("metadata:semantic:read")
    @Operation(summary = "查询结构关系边与知识图谱语义/因果边")
    public ApiResponse<List<LinkView>> listLinks(
            @RequestParam(required = false) String objectId,
            @RequestParam(required = false) String edgeCategory,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(semanticService.listLinks(objectId, edgeCategory, status));
    }

    @PostMapping("/links")
    @RequirePermission("metadata:semantic:manage")
    @Operation(summary = "创建关系边或知识图谱边")
    public ApiResponse<LinkView> createLink(@RequestBody LinkRequest request) {
        return ApiResponse.ok(semanticService.createLink(request));
    }

    @DeleteMapping("/links/{linkId}")
    @RequirePermission("metadata:semantic:manage")
    @Operation(summary = "删除关系边")
    public ApiResponse<Void> deleteLink(@PathVariable String linkId) {
        semanticService.deleteLink(linkId);
        return ApiResponse.ok();
    }

    // ── Staging Proposals (C9) ───────────────────────────────────────────

    @GetMapping("/proposals")
    @RequirePermission("metadata:semantic:read")
    @Operation(summary = "查看语义本体与图谱暂存提案列表")
    public ApiResponse<List<ProposalItem>> listProposals(
            @RequestParam(defaultValue = "PROPOSED") String status) {
        return ApiResponse.ok(semanticService.listProposals(status));
    }

    @PostMapping("/proposals/objects")
    @RequirePermission("metadata:semantic:manage")
    @Operation(summary = "提交 AI/增量语义对象暂存提案（含负反馈防重提检查）")
    public ApiResponse<ObjectView> proposeObject(@RequestBody ObjectRequest request) {
        return ApiResponse.ok(semanticService.proposeObject(request));
    }

    @PostMapping("/proposals/{kind}/{id}/approve")
    @RequirePermission("metadata:semantic:publish")
    @Operation(summary = "审批通过并晋升暂存提案")
    public ApiResponse<ProposalItem> approveProposal(@PathVariable String kind,
                                                     @PathVariable String id) {
        return ApiResponse.ok(semanticService.approveProposal(kind, id));
    }

    @PostMapping("/proposals/{kind}/{id}/reject")
    @RequirePermission("metadata:semantic:publish")
    @Operation(summary = "驳回暂存提案并记录理由以抑制重复提案")
    public ApiResponse<ProposalItem> rejectProposal(@PathVariable String kind,
                                                    @PathVariable String id,
                                                    @RequestBody RejectRequest request) {
        return ApiResponse.ok(semanticService.rejectProposal(kind, id,
                request == null ? null : request.reason()));
    }

    // ── Subgraph Grounding & Impact Analysis ─────────────────────────────

    @PostMapping("/subgraph/search")
    @RequirePermission("metadata:semantic:read")
    @Operation(summary = "面向 AI 与调试台的受控 k-Hop 逐跳鉴权子图检索（C5）")
    public ApiResponse<SubgraphResult> searchSubgraph(@RequestBody SubgraphSearchRequest request) {
        return ApiResponse.ok(semanticService.searchSubgraph(request));
    }

    @GetMapping("/impact")
    @RequirePermission("metadata:semantic:read")
    @Operation(summary = "物理列或物理表变更的语义影响分析")
    public ApiResponse<ImpactResult> impact(
            @RequestParam(required = false) String columnId,
            @RequestParam(required = false) String datasetId) {
        if (columnId != null && !columnId.isBlank()) {
            return ApiResponse.ok(semanticService.impactByColumn(columnId.trim()));
        }
        if (datasetId != null && !datasetId.isBlank()) {
            return ApiResponse.ok(semanticService.impactByDataset(datasetId.trim()));
        }
        return ApiResponse.ok(new ImpactResult("NONE", "", List.of()));
    }

    // ── Multi-Warehouse Catalog, Logical Tables & Compile Plan ──────────

    @GetMapping("/warehouses")
    @RequirePermission("metadata:semantic:read")
    @Operation(summary = "列出当前 Workspace 挂载的多仓库（MicroStrategy MultiSource DBInstances）")
    public ApiResponse<List<WarehouseView>> listWarehouses() {
        return ApiResponse.ok(warehouseService.listWarehouses());
    }

    @PostMapping("/warehouses")
    @RequirePermission("metadata:semantic:manage")
    @Operation(summary = "新增或更新 Workspace 仓库挂载")
    public ApiResponse<WarehouseView> upsertWarehouse(@RequestBody WarehouseRequest request) {
        return ApiResponse.ok(warehouseService.upsertWarehouse(request));
    }

    @DeleteMapping("/warehouses/{id}")
    @RequirePermission("metadata:semantic:manage")
    @Operation(summary = "移除 Workspace 仓库挂载")
    public ApiResponse<Void> deleteWarehouse(@PathVariable String id) {
        warehouseService.deleteWarehouse(id);
        return ApiResponse.ok();
    }

    @GetMapping("/logical-tables")
    @RequirePermission("metadata:semantic:read")
    @Operation(summary = "列出 Workspace Warehouse Catalog 逻辑表与别名")
    public ApiResponse<List<LogicalTableView>> listLogicalTables(
            @RequestParam(required = false) String warehouseId) {
        return ApiResponse.ok(warehouseService.listLogicalTables(warehouseId));
    }

    @PostMapping("/logical-tables")
    @RequirePermission("metadata:semantic:manage")
    @Operation(summary = "导入或更新逻辑表（含 Logical Size 与受限视图 SQL）")
    public ApiResponse<LogicalTableView> upsertLogicalTable(@RequestBody LogicalTableRequest request) {
        return ApiResponse.ok(warehouseService.upsertLogicalTable(request));
    }

    @PostMapping("/logical-tables/{id}/alias")
    @RequirePermission("metadata:semantic:manage")
    @Operation(summary = "为逻辑表创建 Logical Table Alias（解决 Attribute Role）")
    public ApiResponse<LogicalTableView> createLogicalTableAlias(@PathVariable String id,
                                                                 @RequestBody AliasRequest request) {
        return ApiResponse.ok(warehouseService.createLogicalTableAlias(id, request));
    }

    @DeleteMapping("/logical-tables/{id}")
    @RequirePermission("metadata:semantic:manage")
    @Operation(summary = "删除逻辑表")
    public ApiResponse<Void> deleteLogicalTable(@PathVariable String id) {
        warehouseService.deleteLogicalTable(id);
        return ApiResponse.ok();
    }

    @GetMapping("/objects/{id}/mapping-matrix")
    @RequirePermission("metadata:semantic:read")
    @Operation(summary = "查看语义本体的跨仓库异构映射矩阵")
    public ApiResponse<HeterogeneousMappingMatrix> mappingMatrix(@PathVariable String id) {
        return ApiResponse.ok(warehouseService.mappingMatrix(id));
    }

    @PostMapping("/compile-plan")
    @RequirePermission("metadata:semantic:read")
    @Operation(summary = "多仓库确定性编译计划预览（SINGLE_ROUTE / COHORT_UNION / ENTITY_FEDERATED_JOIN）")
    public ApiResponse<MultiWarehousePlan> compilePlan(@RequestBody SemanticQuerySpec spec) {
        String ws = WorkspaceContext.requireWorkspaceId();
        return ApiResponse.ok(multiWarehouseCompiler.compileMultiWarehouse(spec, new CompileContext(
                ws, null, insightProperties.getDialect(), insightProperties.getMaxRows())));
    }

    @GetMapping("/meta")
    @RequirePermission("metadata:semantic:read")
    @Operation(summary = "枚举元信息（对象类型、映射角色、边类型、多仓组合模式与联邦引擎）")
    public ApiResponse<Map<String, Object>> meta() {
        return ApiResponse.ok(Map.of(
                "objectTypes", SemanticOntologyService.OBJECT_TYPES,
                "mappingRoles", SemanticOntologyService.MAPPING_ROLES,
                "edgeCategories", SemanticOntologyService.EDGE_CATEGORIES,
                "structuralLinks", SemanticOntologyService.STRUCTURAL_LINKS,
                "semanticLinks", SemanticOntologyService.SEMANTIC_LINKS,
                "causalLinks", SemanticOntologyService.CAUSAL_LINKS,
                "warehouseRoles", SemanticWarehouseService.WAREHOUSE_ROLES,
                "combineModes", SemanticWarehouseService.COMBINE_MODES,
                "federationEngines", SemanticWarehouseService.FEDERATION_ENGINES,
                "tableRoles", SemanticWarehouseService.TABLE_ROLES));
    }
}
