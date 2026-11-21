package com.datagov.app.controller;

import com.datagov.app.runner.EtlPipelineEngine;
import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.control.domain.JobType;
import com.datagov.control.entity.ControlEntities.JobDefinition;
import com.datagov.control.entity.EtlEntities.EtlBadRowBatch;
import com.datagov.control.entity.EtlEntities.EtlWatermark;
import com.datagov.control.service.BadRowBatchService;
import com.datagov.control.service.JobDefinitionService;
import com.datagov.control.service.WatermarkService;
import com.datagov.governance.service.AccessRequestService;
import com.datagov.governance.service.AccessRequestService.GrantCoverage;
import com.datagov.metadata.service.SensitivityService;
import com.datagov.metadata.service.SensitivityService.SensitiveColumnView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 轻量级 ETL 管道(lightweight-etl-pipeline Phase 1):节点预览。
 *
 * <p>预览与正式执行共用 {@link EtlPipelineEngine}(同一套抽取 + DuckDB 锁定 + 转换语义),
 * 区别只在限额:每个源节点只抽样 {@value #MAX_SOURCE_ROWS} 行、结果最多 {@value #PREVIEW_LIMIT} 行、
 * 15 秒超时、DuckDB 内存 512MB,并且<b>永不写目标端</b>。
 */
@RestController
@RequestMapping("/api/v1/control/etl")
@Tag(name = "ETL 管道", description = "轻量级 ETL 管道预览(DuckDB 向量化内核)")
public class EtlPipelineController {

    private static final Logger log = LoggerFactory.getLogger(EtlPipelineController.class);

    static final int PREVIEW_LIMIT = 100;
    static final int MAX_SOURCE_ROWS = 10_000;
    static final int TIMEOUT_SECONDS = 15;
    static final String MEMORY_LIMIT = "512MB";
    static final String MASK = "***";

    private final EtlPipelineEngine engine;
    private final JobDefinitionService jobService;
    private final SensitivityService sensitivityService;
    private final WatermarkService watermarkService;
    private final BadRowBatchService badRowBatchService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AccessRequestService accessRequestService;

    void setAccessRequestService(AccessRequestService accessRequestService) {
        this.accessRequestService = accessRequestService;
    }

    public EtlPipelineController(EtlPipelineEngine engine, JobDefinitionService jobService,
                                 SensitivityService sensitivityService, WatermarkService watermarkService,
                                 BadRowBatchService badRowBatchService) {
        this.engine = engine;
        this.jobService = jobService;
        this.sensitivityService = sensitivityService;
        this.watermarkService = watermarkService;
        this.badRowBatchService = badRowBatchService;
    }

    // ── 增量水位线(P1-8 第三页签) ─────────────────────────────────────

    public record WatermarkView(String sourceNodeId, String watermarkColumn, String watermarkType,
                                String lastValue, String lastExecutionId, Instant updatedAt,
                                String updatedBy) {

        static WatermarkView from(EtlWatermark w) {
            return new WatermarkView(w.getSourceNodeId(), w.getWatermarkColumn(), w.getWatermarkType(),
                    w.getLastValue(), w.getLastExecutionId(), w.getUpdatedAt(), w.getUpdatedBy());
        }
    }

    /**
     * @param reason   必填:为什么要重置(重置意味着重抽或漏抽,留痕用)
     * @param newValue 可选:重置到的值;缺省 / 空 = 清空,下次全量抽取
     */
    public record ResetWatermarkRequest(String reason, String newValue) {
    }

    @GetMapping("/{jobId}/watermarks")
    @RequirePermission("control:etl:read")
    @Operation(summary = "查看 ETL 任务的增量水位线", description = "每个增量源节点一行;从未成功推进过的节点没有记录")
    public ApiResponse<List<WatermarkView>> watermarks(@PathVariable String jobId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        requireEtlJob(workspaceId, jobId);
        return ApiResponse.ok(watermarkService.list(workspaceId, jobId).stream().map(WatermarkView::from).toList());
    }

    @PostMapping("/{jobId}/watermarks/{sourceNodeId}/reset")
    @RequirePermission("control:etl:manage")
    @Operation(summary = "重置某个源节点的增量水位线",
            description = "必须填写原因;审计记录由 EtlWatermarkReset 事件监听器落 gv_audit_record(含重置前后的值)")
    public ApiResponse<WatermarkView> resetWatermark(@PathVariable String jobId,
                                                     @PathVariable String sourceNodeId,
                                                     @RequestBody ResetWatermarkRequest request) {
        Caller caller = WorkspaceContext.require();
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        requireEtlJob(workspaceId, jobId);
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "重置水位线必须说明原因");
        }
        EtlWatermark reset = watermarkService.reset(workspaceId, jobId, sourceNodeId, request.newValue(),
                request.reason().trim(), caller.userId());
        return ApiResponse.ok(WatermarkView.from(reset));
    }

    // ── 脏数据批次摘要(P2-1b) ─────────────────────────────────────────

    /**
     * 与 {@code etl_bad_row_batch} 一一对应。{@code sampleErrorJson} 在写入时已由 {@code EtlBadRowLedger}
     * 清洗(错误类别 / SQLSTATE / 抹掉值的消息;无 L3/L4 明文),这里只做长度截断,不放大内容。
     * 死信行本体不经此接口返回,只给 {@code storageRef} 引用。
     */
    public record BadRowBatchView(String id, String jobId, String executionId, String nodeId,
                                  long badRowCount, String storageRef, String sampleErrorJson,
                                  Instant createdAt) {

        static final int MAX_SAMPLE = 4_000;

        static BadRowBatchView from(EtlBadRowBatch b) {
            String sample = b.getSampleErrorJson();
            if (sample != null && sample.length() > MAX_SAMPLE) {
                sample = sample.substring(0, MAX_SAMPLE) + "…[truncated]";
            }
            return new BadRowBatchView(b.getId(), b.getJobId(), b.getExecutionId(), b.getNodeId(),
                    b.getBadRowCount() == null ? 0 : b.getBadRowCount(), b.getStorageRef(), sample,
                    b.getCreatedAt());
        }
    }

    @GetMapping("/executions/{executionId}/bad-rows")
    @RequirePermission("control:etl:read")
    @Operation(summary = "某次 ETL 执行的脏数据批次摘要", description = "按空间隔离;只有计数、错误样例与死信引用")
    public ApiResponse<List<BadRowBatchView>> badRows(@PathVariable String executionId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        return ApiResponse.ok(badRowBatchService.listByExecution(workspaceId, executionId).stream()
                .map(BadRowBatchView::from).toList());
    }

    /** 跨空间与不存在同一个报错,不泄露别的空间有没有这个 ID。 */
    private JobDefinition requireEtlJob(String workspaceId, String jobId) {
        JobDefinition definition = jobService.loadInternal(jobId);
        if (!workspaceId.equals(definition.getWorkspaceId())) {
            throw BizException.notFound(ErrorCode.CTL_JOB_NOT_FOUND, jobId);
        }
        if (definition.getJobType() != JobType.ETL_PIPELINE) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "任务不是 ETL 管道: " + jobId);
        }
        return definition;
    }

    /**
     * @param ir     内联 IR(etl.ir/v1 计划形状),与 jobId 二选一
     * @param jobId  已保存的 ETL 任务定义 ID:取其已编译的物理计划,没有则回退到配置
     * @param nodeId 要预览的节点;缺省时预览 Sink 的上游
     */
    public record PreviewRequest(Map<String, Object> ir, String jobId, String nodeId) {
    }

    public record PreviewResponse(String nodeId, List<EtlPipelineEngine.PreviewColumn> columns,
                                  List<Map<String, Object>> rows, boolean truncated,
                                  List<String> maskedColumns, long elapsedMillis) {
    }

    @PostMapping("/preview")
    @Operation(summary = "预览 ETL 节点输出",
            description = "LIMIT 100、15s 超时、512MB 内存;源节点抽样,L3/L4 敏感列按列名兜底脱敏")
    public ApiResponse<PreviewResponse> preview(@RequestBody PreviewRequest request) {
        Caller caller = WorkspaceContext.require();
        // 「manage 或 read 任一」:@RequirePermission 只能声明一个码,所以在这里手工判定。
        // 接口仍然要登录(AuthInterceptor),并且 caller 的权限是按当前空间解析的。
        if (!caller.hasPermission("control:etl:manage") && !caller.hasPermission("control:etl:read")) {
            throw new BizException(ErrorCode.PLT_FORBIDDEN, "缺少权限: control:etl:read");
        }
        if (request == null || (request.ir() == null && (request.jobId() == null
                || request.jobId().isBlank()))) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "必须提供 ir 或 jobId");
        }
        String workspaceId = caller.workspaceId();

        EtlPipelineEngine.Plan plan;
        try {
            plan = engine.parse(request.ir() != null ? request.ir() : loadPlan(workspaceId, request.jobId()));
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "ETL 计划无效: " + e.getMessage());
        }

        long started = System.currentTimeMillis();
        EtlPipelineEngine.PreviewResult result;
        try {
            result = engine.preview(workspaceId, plan, request.nodeId(), PREVIEW_LIMIT,
                    EtlPipelineEngine.Limits.preview(MAX_SOURCE_ROWS, MEMORY_LIMIT, TIMEOUT_SECONDS));
        } catch (BizException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "ETL 预览参数无效: " + e.getMessage());
        } catch (Exception e) {
            log.warn("ETL 预览失败 ws={} node={} err={}", workspaceId, request.nodeId(), e.getMessage());
            throw new BizException(ErrorCode.DAT_QUERY_FAILED, "ETL 预览失败: " + e.getMessage());
        }

        List<String> masked = new ArrayList<>();
        List<Map<String, Object>> rows = maskSensitive(result, masked, caller);
        return ApiResponse.ok(new PreviewResponse(result.nodeId(), result.columns(), rows,
                result.truncated(), masked, Math.max(1, System.currentTimeMillis() - started)));
    }

    private Map<String, Object> loadPlan(String workspaceId, String jobId) {
        JobDefinition definition = jobService.loadInternal(jobId);
        // loadInternal 不带租户校验:跨空间与不存在同一个报错,不泄露别的空间有没有这个 ID
        if (!workspaceId.equals(definition.getWorkspaceId())) {
            throw BizException.notFound(ErrorCode.CTL_JOB_NOT_FOUND, jobId);
        }
        if (definition.getJobType() != JobType.ETL_PIPELINE) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "任务不是 ETL 管道: " + jobId);
        }
        Map<String, Object> plan = jobService.readPhysicalPlan(definition);
        Map<String, Object> source = plan.isEmpty() ? new LinkedHashMap<>(jobService.readConfig(definition))
                : new LinkedHashMap<>(plan);
        source.putIfAbsent("jobId", definition.getId());
        return source;
    }

    /**
     * L3/L4 敏感列兜底脱敏(按结果列名匹配 {@link SensitivityService#sensitiveColumnNames()},
     * 与问数网关的 {@code InsightMasking} 同一口径)。
     *
     * <p>已接入 {@code AccessRequestService.activeGrants}：按当前用户 + 有效授权类别/级别
     * 逐列判定，已授权列放行、未授权列脱敏。剩余限制：被 {@code AS} 重命名或表达式派生的
     * 敏感列尚未通过血缘追溯到物理源列，列名改写仍可能绕过名称匹配；预览继续受
     * {@code control:etl:*} 权限与 100 行上限约束。
     */
    List<Map<String, Object>> maskSensitive(EtlPipelineEngine.PreviewResult result,
                                            List<String> maskedOut, Caller caller) {
        Set<String> sensitive = new HashSet<>();
        try {
            List<GrantCoverage> grants = accessRequestService == null
                    ? List.of()
                    : accessRequestService.activeGrants(caller.workspaceId(), caller.username());
            for (SensitiveColumnView column : sensitivityService.sensitiveColumnNames()) {
                String level = column.level() == null ? "" : column.level().toUpperCase(Locale.ROOT);
                if (!("L3".equals(level) || "L4".equals(level)) || column.columnName() == null) {
                    continue;
                }
                // 逐列按当前用户的有效 AccessGrant 判定：同类别且授权级别 >= 列级别才放行。
                boolean covered = grants.stream().anyMatch(grant -> covers(grant, column));
                if (!covered) {
                    sensitive.add(column.columnName().toLowerCase(Locale.ROOT));
                }
            }
        } catch (RuntimeException e) {
            // 取不到敏感清单时宁可不返回数据,也不能静默放行明细
            throw new BizException(ErrorCode.DAT_QUERY_FAILED, "敏感字段清单获取失败,预览已拒绝: " + e.getMessage());
        }
        if (sensitive.isEmpty()) {
            return result.rows();
        }
        for (EtlPipelineEngine.PreviewColumn column : result.columns()) {
            if (sensitive.contains(column.name().toLowerCase(Locale.ROOT))) {
                maskedOut.add(column.name());
            }
        }
        if (maskedOut.isEmpty()) {
            return result.rows();
        }
        Set<String> maskedLower = new HashSet<>();
        maskedOut.forEach(n -> maskedLower.add(n.toLowerCase(Locale.ROOT)));
        List<Map<String, Object>> rows = new ArrayList<>(result.rows().size());
        for (Map<String, Object> row : result.rows()) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.replaceAll((k, v) -> maskedLower.contains(k.toLowerCase(Locale.ROOT)) && v != null ? MASK : v);
            rows.add(copy);
        }
        return rows;
    }

    private static boolean covers(GrantCoverage grant, SensitiveColumnView column) {
        if (grant.category() == null || column.category() == null) {
            return false;
        }
        return grant.category().equalsIgnoreCase(column.category())
                && levelRank(column.level()) <= levelRank(grant.maxLevel());
    }

    private static int levelRank(String level) {
        return switch (level == null ? "" : level.toUpperCase(Locale.ROOT)) {
            case "L4" -> 4;
            case "L3" -> 3;
            case "L2" -> 2;
            case "L1" -> 1;
            default -> 0;
        };
    }
}
