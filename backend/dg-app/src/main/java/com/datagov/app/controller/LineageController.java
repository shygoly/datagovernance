package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.metadata.service.LineageService;
import com.datagov.metadata.service.LineageService.EdgeInput;
import com.datagov.metadata.service.LineageService.EdgeView;
import com.datagov.metadata.service.LineageService.GraphView;
import com.datagov.metadata.service.LineageService.JobRequest;
import com.datagov.metadata.service.LineageService.JobDiffView;
import com.datagov.metadata.service.LineageService.JobResult;
import com.datagov.metadata.service.LineageService.NodeView;
import com.datagov.metadata.service.LineageService.OverviewView;
import com.datagov.metadata.service.lineage.OpenLineageService;
import com.datagov.metadata.service.lineage.OpenLineageService.IngestResult;
import com.datagov.metadata.service.lineage.SqlLineageService;
import com.datagov.metadata.service.lineage.SqlLineageService.ImportRequest;
import com.datagov.metadata.service.lineage.SqlLineageService.ImportView;
import com.datagov.metadata.service.lineage.SqlLineageService.PreviewRequest;
import com.datagov.metadata.service.lineage.SqlLineageService.PreviewView;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 字段级血缘(P10.4)。
 *
 * <p>写入来自 ETL/CDC/OpenLineage 作业;查询用于“这个字段从哪来、改它会影响谁”。
 * 边不删除,SQL 变化时旧边由服务端标 SUPERSEDED。
 */
@RestController
@RequestMapping("/api/v1/metadata/lineage")
@Tag(name = "字段血缘", description = "列级血缘、上游/下游与影响分析")
public class LineageController {

    private final LineageService lineageService;
    private final SqlLineageService sqlLineageService;
    private final OpenLineageService openLineageService;

    public LineageController(LineageService lineageService, SqlLineageService sqlLineageService,
                             OpenLineageService openLineageService) {
        this.lineageService = lineageService;
        this.sqlLineageService = sqlLineageService;
        this.openLineageService = openLineageService;
    }

    @PostMapping("/jobs")
    @RequirePermission("metadata:lineage:manage")
    @Operation(summary = "上报一次作业的列级血缘（ACTIVE）",
            description = "同一作业重复上报会复用/更新边,旧边标 SUPERSEDED")
    public ApiResponse<JobResult> recordJob(@RequestBody JobRequest request) {
        return ApiResponse.ok(lineageService.recordJob(request));
    }

    @PostMapping("/jobs/planned")
    @RequirePermission("metadata:lineage:manage")
    @Operation(summary = "写入 PLANNED 预估血缘（作业发布/编译）")
    public ApiResponse<JobResult> recordPlannedJob(@RequestBody JobRequest request) {
        return ApiResponse.ok(lineageService.recordPlannedJob(request));
    }

    public record PromoteRequest(String executionId, JobRequest job) {
    }

    @PostMapping("/jobs/promote-execution")
    @RequirePermission("metadata:lineage:manage")
    @Operation(summary = "ExecutionSucceeded 后晋升 PLANNED → ACTIVE 并绑定 executionId")
    public ApiResponse<JobResult> promoteExecution(@RequestBody PromoteRequest request) {
        return ApiResponse.ok(lineageService.promoteJobExecution(request.job(), request.executionId()));
    }

    @PostMapping("/sql/parse-preview")
    @RequirePermission("metadata:lineage:manage")
    @Operation(summary = "SQL 列级血缘解析预览（不落库）",
            description = "经 lineage-parser-service 解析；sidecar 不可达或无法确定列映射时返回 "
                    + "status=INCONCLUSIVE 与诊断，不会猜列。dialect 缺省时按 dataSourceId 的类型推断")
    public ApiResponse<PreviewView> previewSql(@RequestBody PreviewRequest request) {
        return ApiResponse.ok(sqlLineageService.preview(request));
    }

    @PostMapping("/sql/import")
    @RequirePermission("metadata:lineage:manage")
    @Operation(summary = "解析 SQL 并导入列级血缘（ACTIVE）",
            description = "仅 status=OK 时写边；INCONCLUSIVE / ERROR 只登记作业解析状态，imported=false")
    public ApiResponse<ImportView> importSql(@RequestBody ImportRequest request) {
        return ApiResponse.ok(sqlLineageService.importSql(request));
    }

    @PostMapping("/openlineage")
    @RequirePermission("metadata:lineage:manage")
    @Operation(summary = "OpenLineage 1.x RunEvent Webhook（COMPLETE → ACTIVE）",
            description = "仅处理 eventType=COMPLETE 且带 outputs[].facets.columnLineage 的事件；"
                    + "按 Catalog 对齐后写 origin=OPENLINEAGE 的 ACTIVE 边。身份来自登录态/令牌解析出的"
                    + "真实用户与 workspace（C4），payload 中的任何租户信息都被忽略；写入由审计拦截器留痕")
    public ApiResponse<IngestResult> openLineage(@RequestBody JsonNode event) {
        return ApiResponse.ok(openLineageService.ingest(event));
    }

    @PostMapping("/edges")
    @RequirePermission("metadata:lineage:manage")
    @Operation(summary = "人工补录一条列级血缘")
    public ApiResponse<EdgeView> recordEdge(@RequestBody EdgeInput input) {
        return ApiResponse.ok(lineageService.recordManualEdge(input));
    }

    @GetMapping("/overview")
    @RequirePermission("metadata:lineage:read")
    @Operation(summary = "血缘工作台概览")
    public ApiResponse<OverviewView> overview() {
        return ApiResponse.ok(lineageService.overview());
    }

    @GetMapping("/edges/{id}")
    @RequirePermission("metadata:lineage:read")
    @Operation(summary = "边详情（含作业与执行证据）")
    public ApiResponse<EdgeView> getEdge(@PathVariable String id) {
        return ApiResponse.ok(lineageService.getEdge(id));
    }

    @GetMapping("/nodes/{id}")
    @RequirePermission("metadata:lineage:read")
    @Operation(summary = "血缘节点")
    public ApiResponse<NodeView> getNode(@PathVariable String id) {
        return ApiResponse.ok(lineageService.getNode(id));
    }

    @GetMapping("/columns/{id}/upstream")
    @RequirePermission("metadata:lineage:read")
    @Operation(summary = "字段上游", description = "depth 上限 5；includePlanned=true 时同时返回 PLANNED 预估边。"
            + "无权数据集逐跳截断并以 RESTRICTED 占位返回（restrictedCount / truncatedByAcl）")
    public ApiResponse<GraphView> upstream(@PathVariable String id,
                                           @RequestParam(defaultValue = "3") int depth,
                                           @RequestParam(defaultValue = "false") boolean includePlanned) {
        return ApiResponse.ok(lineageService.upstream(id, depth, includePlanned));
    }

    @GetMapping("/columns/{id}/downstream")
    @RequirePermission("metadata:lineage:read")
    @Operation(summary = "字段下游", description = "includeGovernance=true 时并入下游治理消费方"
            + "（METRIC / REPORT / QUALITY_RULE 节点，relation=CONSUMED_BY）；消费方按调用者权限二次过滤")
    public ApiResponse<GraphView> downstream(@PathVariable String id,
                                             @RequestParam(defaultValue = "3") int depth,
                                             @RequestParam(defaultValue = "false") boolean includePlanned,
                                             @RequestParam(defaultValue = "false") boolean includeGovernance) {
        return ApiResponse.ok(lineageService.downstream(id, depth, includePlanned, includeGovernance));
    }

    @GetMapping("/columns/{id}/impact")
    @RequirePermission("metadata:lineage:read")
    @Operation(summary = "影响分析", description = "等价于下游遍历,单独命名让调用方读得懂意图；"
            + "includeGovernance=true 时影响清单包含下游指标/分析报告/质检规则")
    public ApiResponse<GraphView> impact(@PathVariable String id,
                                         @RequestParam(defaultValue = "5") int depth,
                                         @RequestParam(defaultValue = "false") boolean includePlanned,
                                         @RequestParam(defaultValue = "false") boolean includeGovernance) {
        return ApiResponse.ok(lineageService.impact(id, depth, includePlanned, includeGovernance));
    }

    @GetMapping("/datasets/{id}/graph")
    @RequirePermission("metadata:lineage:read")
    @Operation(summary = "表级血缘",
            description = "列级事实按数据集聚合; direction=upstream/downstream/both, depth 上限 5")
    public ApiResponse<GraphView> datasetGraph(
            @PathVariable String id,
            @RequestParam(defaultValue = "both") String direction,
            @RequestParam(defaultValue = "2") int depth,
            @RequestParam(defaultValue = "false") boolean includePlanned,
            @RequestParam(defaultValue = "false") boolean includeGovernance) {
        return ApiResponse.ok(lineageService.datasetGraph(id, direction, depth, includePlanned, includeGovernance));
    }

    @GetMapping("/datasets/{fromId}/column-edges")
    @RequirePermission("metadata:lineage:read")
    @Operation(summary = "展开两表之间的列级血缘",
            description = "表级聚合边 dsedge:{from}->{to} 背后的字段级明细；"
                    + "必填 toDatasetId。用于「N 条列级血缘」点击展开。")
    public ApiResponse<GraphView> columnEdgesBetween(
            @PathVariable String fromId,
            @RequestParam String toDatasetId,
            @RequestParam(defaultValue = "false") boolean includePlanned) {
        return ApiResponse.ok(lineageService.columnEdgesBetweenDatasets(fromId, toDatasetId, includePlanned));
    }

    @GetMapping("/jobs/{jobId}/diff")
    @RequirePermission("metadata:lineage:read")
    @Operation(summary = "作业血缘历史版本 Diff",
            description = "当前 ACTIVE 边 vs 最近一批改动（SUPERSEDED / 表达式改写）：added / removed / modifiedTransform。"
                    + "无历史改动时 hasBaseline=false 且列表为空；触及受限数据集的行以 [受限资产] 占位")
    public ApiResponse<JobDiffView> jobDiff(@PathVariable String jobId) {
        return ApiResponse.ok(lineageService.jobDiff(jobId));
    }
}
