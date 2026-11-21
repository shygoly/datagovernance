package com.datagov.app.insight;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.app.insight.ChartSpecRecommender.ChartSpec;
import com.datagov.app.insight.InsightQueryEngine.ClarificationOption;
import com.datagov.app.insight.InsightQueryEngine.DatasetOption;
import com.datagov.app.insight.InsightQueryEngine.EngineAskResult;
import com.datagov.app.insight.InsightQueryEngine.InsightColumn;
import com.datagov.app.insight.InsightQueryEngine.WarehouseSqlPass;
import com.datagov.app.insight.entity.InsightQueryLog;
import com.datagov.app.insight.mapper.InsightQueryLogMapper;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.service.AccessRequestService;
import com.datagov.governance.service.AccessRequestService.GrantCoverage;
import com.datagov.governance.service.AuditService;
import com.datagov.governance.service.AuditService.AuditEntry;
import com.datagov.metadata.service.SemanticQueryCompiler.SemanticQuerySpec;
import com.datagov.metadata.service.SensitivityService;
import com.datagov.metadata.service.SensitivityService.SensitiveColumnView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 智能问数网关（原生语义引擎版 · OpenSpec: semantic-ontology-kernel）。
 *
 * <p>统一承担五项治理职责：
 * <ol>
 *   <li>身份与空间：按平台 JWT / {@link WorkspaceContext} 实时鉴权，零外部共享服务账号；</li>
 *   <li>单一语义源：底层委托 {@link InsightQueryEngine}（默认 {@link NativeInsightEngine}）基于 {@code dg-metadata} 语义内核确定性编译执行；</li>
 *   <li>结果脱敏与临时放行：按敏感标签兜底遮蔽 PII，并对生效中的 {@link GrantCoverage} 留痕放行；</li>
 *   <li>行数上限与拒答边界：截断上限、证据不足返 {@code INCONCLUSIVE}、多义口径返 {@code CLARIFY_NEEDED}；</li>
 *   <li>审计与可复现指纹：记录查询日志、语义快照版本号与 SHA-256 审计指纹（CONSTITUTION C8）。</li>
 * </ol>
 */
@Service
public class InsightQueryService {

    private static final Logger log = LoggerFactory.getLogger(InsightQueryService.class);

    private static final Pattern CATALOG_QUESTION = Pattern.compile(
            "(数据域|数据集|表|主题).*(有哪些|清单|列表)|有哪些.*(表|数据集|主题)|列出.*(表|数据集|主题)",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> NON_ERROR_STATES = Set.of(
            "SUCCESS", "COMPILED_ONLY", "CLARIFY_NEEDED", "INCONCLUSIVE", "METADATA");

    private final InsightQueryEngine engine;
    private final InsightProperties properties;
    private final InsightQueryLogMapper logMapper;
    private final SensitivityService sensitivityService;
    private final AccessRequestService accessRequestService;
    private final AuditService auditService;

    public InsightQueryService(InsightQueryEngine engine,
                               InsightProperties properties,
                               InsightQueryLogMapper logMapper,
                               SensitivityService sensitivityService,
                               AccessRequestService accessRequestService,
                               AuditService auditService) {
        this.engine = engine;
        this.properties = properties;
        this.logMapper = logMapper;
        this.sensitivityService = sensitivityService;
        this.accessRequestService = accessRequestService;
        this.auditService = auditService;
    }

    public record AskRequest(String question, Long dataSetId, Integer agentId, SemanticQuerySpec semanticSpec) {
        public AskRequest(String question, Long dataSetId, Integer agentId) {
            this(question, dataSetId, agentId, null);
        }
    }

    public record Answer(
            String queryId,
            String state,
            String sql,
            List<InsightColumn> columns,
            List<Map<String, Object>> rows,
            int rowCount,
            boolean truncated,
            long elapsedMs,
            List<String> maskedColumns,
            List<String> grantIds,
            String summary,
            List<String> semanticSnapshotRefs,
            String auditFingerprint,
            List<String> appliedExclusions,
            List<ClarificationOption> clarifications,
            SemanticQuerySpec executedSpec,
            ChartSpec chartSpec,
            String executionMode,
            String federationEngine,
            List<String> warehousesUsed,
            List<WarehouseSqlPass> warehouseSqls,
            com.datagov.common.audit.ReproducibilityFingerprint reproducibilityFingerprint
    ) {
        public Answer(String queryId, String state, String sql,
                      List<InsightColumn> columns, List<Map<String, Object>> rows,
                      int rowCount, boolean truncated, long elapsedMs,
                      List<String> maskedColumns, List<String> grantIds, String summary,
                      List<String> semanticSnapshotRefs, String auditFingerprint,
                      List<String> appliedExclusions, List<ClarificationOption> clarifications,
                      SemanticQuerySpec executedSpec, ChartSpec chartSpec) {
            this(queryId, state, sql, columns, rows, rowCount, truncated, elapsedMs,
                    maskedColumns, grantIds, summary, semanticSnapshotRefs, auditFingerprint,
                    appliedExclusions, clarifications, executedSpec, chartSpec,
                    null, null, List.of(), List.of(), null);
        }

        public Answer(String queryId, String state, String sql,
                      List<InsightColumn> columns, List<Map<String, Object>> rows,
                      int rowCount, boolean truncated, long elapsedMs,
                      List<String> maskedColumns, List<String> grantIds, String summary,
                      List<String> semanticSnapshotRefs, String auditFingerprint,
                      List<String> appliedExclusions, List<ClarificationOption> clarifications,
                      SemanticQuerySpec executedSpec, ChartSpec chartSpec,
                      String executionMode, String federationEngine,
                      List<String> warehousesUsed, List<WarehouseSqlPass> warehouseSqls) {
            this(queryId, state, sql, columns, rows, rowCount, truncated, elapsedMs,
                    maskedColumns, grantIds, summary, semanticSnapshotRefs, auditFingerprint,
                    appliedExclusions, clarifications, executedSpec, chartSpec,
                    executionMode, federationEngine, warehousesUsed, warehouseSqls, null);
        }
    }

    public record ExportFile(String filename, byte[] content, int rowCount,
                             List<String> maskedColumns) {
    }

    public record QueryLogView(
            String id,
            String username,
            String question,
            String queryState,
            String generatedSql,
            Integer rowCount,
            Long elapsedMs,
            String maskedColumns,
            String grantIds,
            boolean succeeded,
            String errorMessage,
            Instant createdAt,
            Long datasetId
    ) {
        static QueryLogView from(InsightQueryLog value) {
            return new QueryLogView(value.getId(), value.getUsername(), value.getQuestion(),
                    value.getQueryState(), value.getGeneratedSql(), value.getRowCount(),
                    value.getElapsedMs(), value.getMaskedColumns(), value.getGrantIds(),
                    Boolean.TRUE.equals(value.getSucceeded()), value.getErrorMessage(),
                    value.getCreatedAt(), value.getDatasetId());
        }
    }

    public List<DatasetOption> datasets() {
        WorkspaceContext.require();
        WorkspaceContext.requireWorkspaceId();
        return engine.datasets();
    }

    public Answer ask(AskRequest request) {
        Caller caller = WorkspaceContext.require();
        WorkspaceContext.requireWorkspaceId();
        String question = request == null ? null : request.question();
        SemanticQuerySpec explicitSpec = request == null ? null : request.semanticSpec();
        if ((question == null || question.isBlank()) && explicitSpec == null) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "问题不能为空");
        }
        question = question == null || question.isBlank() ? "结构化语义查询" : question.trim();
        if (question.length() > 2000) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "问题不能超过 2000 字");
        }
        int agentId = request.agentId() == null || request.agentId() <= 0
                ? properties.getAgentId() : request.agentId();
        long datasetId = request.dataSetId() == null || request.dataSetId() <= 0
                ? properties.getDefaultDatasetId() : request.dataSetId();

        long started = System.currentTimeMillis();
        InsightQueryLog fact = newFact(caller, question, agentId, datasetId);
        try {
            if (explicitSpec == null && isCatalogQuestion(question)) {
                return answerCatalogQuestion(caller, question, datasetId, started, fact);
            }
            EngineAskResult result = engine.ask(question, datasetId, explicitSpec);
            String state = result.state() == null ? "FAILED" : result.state().toUpperCase();
            if (!NON_ERROR_STATES.contains(state)) {
                throw new BizException(ErrorCode.GOV_INSIGHT_FAILED,
                        "问数未成功: " + nullSafe(result.error(), state));
            }

            List<SensitiveColumnView> sensitive = sensitivityService.sensitiveColumnNames();
            List<GrantCoverage> grants = accessRequestService.activeGrants(
                    caller.workspaceId(), caller.username());
            List<SensitiveColumnView> remaining = new java.util.ArrayList<>();
            java.util.Set<String> usedGrants = new java.util.LinkedHashSet<>();
            for (SensitiveColumnView column : sensitive) {
                List<GrantCoverage> covering = grants.stream()
                        .filter(grant -> covers(grant, column)).toList();
                if (covering.isEmpty()) {
                    remaining.add(column);
                } else {
                    covering.forEach(grant -> usedGrants.add(grant.id()));
                }
            }
            InsightMasking.Masked masked = InsightMasking.mask(result.rows(),
                    result.columns(), result.sql(), remaining);

            boolean truncated = masked.rows().size() > properties.getMaxRows();
            List<Map<String, Object>> rows = truncated
                    ? masked.rows().subList(0, properties.getMaxRows())
                    : masked.rows();
            long elapsed = System.currentTimeMillis() - started;

            fact.setQueryState(state);
            fact.setGeneratedSql(truncate(result.sql(), 20000));
            fact.setRowCount(rows.size());
            fact.setElapsedMs(elapsed);
            fact.setMaskedColumns(truncate(String.join(",", masked.maskedColumns()), 1000));
            fact.setGrantIds(usedGrants.isEmpty() ? null
                    : truncate(String.join(",", usedGrants), 1000));
            fact.setSucceeded(true);
            logMapper.insert(fact);

            auditService.record(new AuditEntry(
                    AuditService.EXECUTE,
                    "INSIGHT_QUERY",
                    fact.getId(),
                    truncate(question, 256),
                    "GOVERNANCE",
                    true,
                    null,
                    "POST /api/v1/insight/ask",
                    "engine=" + engine.engineId() + "; dataset=" + datasetId + "; state=" + state
                            + "; masked=" + masked.maskedColumns()
                            + "; grants=" + usedGrants
                            + "; fingerprint=" + nullSafe(result.auditFingerprint(), "none")
                            + "; rows=" + rows.size() + "; elapsedMs=" + elapsed));

            return new Answer(result.queryId(), state, result.sql(),
                    result.columns(), rows, rows.size(), truncated, elapsed,
                    masked.maskedColumns(), List.copyOf(usedGrants), result.summary(),
                    result.semanticSnapshotRefs(), result.auditFingerprint(),
                    result.appliedExclusions(), result.clarifications(),
                    result.executedSpec(), result.chartSpec(),
                    result.executionMode(), result.federationEngine(),
                    result.warehousesUsed(), result.warehouseSqls(),
                    result.reproducibilityFingerprint());
        } catch (RuntimeException ex) {
            long elapsed = System.currentTimeMillis() - started;
            fact.setSucceeded(false);
            fact.setElapsedMs(elapsed);
            fact.setErrorMessage(truncate(ex.getMessage(), 1000));
            logMapper.insert(fact);
            auditService.record(new AuditEntry(
                    AuditService.EXECUTE,
                    "INSIGHT_QUERY",
                    fact.getId(),
                    truncate(question, 256),
                    "GOVERNANCE",
                    false,
                    ex instanceof BizException biz ? biz.errorCode().code() : "SYS_INTERNAL_ERROR",
                    "POST /api/v1/insight/ask",
                    "engine=" + engine.engineId() + "; dataset=" + datasetId + "; elapsedMs=" + elapsed));
            log.warn("智能问数失败 workspace={} user={} dataset={}",
                    caller.workspaceId(), caller.username(), datasetId, ex);
            if (ex instanceof BizException biz) {
                throw biz;
            }
            throw new BizException(ErrorCode.GOV_INSIGHT_FAILED,
                    "智能问数失败: " + ex.getMessage(), null, ex);
        }
    }

    public ExportFile export(AskRequest request) {
        WorkspaceContext.require();
        WorkspaceContext.requireWorkspaceId();
        Answer answer = ask(request);
        List<String> keys = answer.columns().isEmpty()
                ? (answer.rows().isEmpty() ? List.of() : List.copyOf(answer.rows().get(0).keySet()))
                : answer.columns().stream()
                        .map(column -> column.bizName() == null ? column.name() : column.bizName())
                        .toList();
        List<String> labels = answer.columns().isEmpty()
                ? keys
                : answer.columns().stream().map(InsightColumn::name).toList();

        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append(labels.stream().map(InsightQueryService::csvCell)
                .collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        for (Map<String, Object> row : answer.rows()) {
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    csv.append(',');
                }
                csv.append(csvCell(row.get(keys.get(i))));
            }
            csv.append("\r\n");
        }
        auditService.record(new AuditEntry(
                AuditService.EXPORT,
                "INSIGHT_EXPORT",
                Ids.of("inx"),
                truncate(request == null ? null : request.question(), 256),
                "GOVERNANCE",
                true,
                null,
                "POST /api/v1/insight/export",
                "rows=" + answer.rowCount() + "; masked=" + answer.maskedColumns()
                        + "; grants=" + answer.grantIds()
                        + "; fingerprint=" + nullSafe(answer.auditFingerprint(), "none")
                        + "; labels=" + labels));
        return new ExportFile("insight-" + System.currentTimeMillis() + ".csv",
                csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                answer.rowCount(), answer.maskedColumns());
    }

    public ExportFile exportXlsx(AskRequest request) {
        WorkspaceContext.require();
        WorkspaceContext.requireWorkspaceId();
        Answer answer = ask(request);
        List<String> keys = answer.columns().isEmpty()
                ? (answer.rows().isEmpty() ? List.of() : List.copyOf(answer.rows().get(0).keySet()))
                : answer.columns().stream()
                        .map(column -> column.bizName() == null ? column.name() : column.bizName())
                        .toList();
        List<String> labels = answer.columns().isEmpty()
                ? keys
                : answer.columns().stream().map(InsightColumn::name).toList();
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             var output = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("问数结果");
            var header = sheet.createRow(0);
            var headerStyle = workbook.createCellStyle();
            var font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            for (int i = 0; i < labels.size(); i++) {
                var cell = header.createCell(i);
                cell.setCellValue(labels.get(i));
                cell.setCellStyle(headerStyle);
            }
            for (int rowIndex = 0; rowIndex < answer.rows().size(); rowIndex++) {
                var row = sheet.createRow(rowIndex + 1);
                Map<String, Object> source = answer.rows().get(rowIndex);
                for (int i = 0; i < keys.size(); i++) {
                    Object value = source.get(keys.get(i));
                    row.createCell(i).setCellValue(value == null ? "" : String.valueOf(value));
                }
            }
            for (int i = 0; i < labels.size(); i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(output);
            auditService.record(new AuditEntry(
                    AuditService.EXPORT,
                    "INSIGHT_EXPORT",
                    Ids.of("inx"),
                    truncate(request == null ? null : request.question(), 256),
                    "GOVERNANCE",
                    true,
                    null,
                    "POST /api/v1/insight/export/xlsx",
                    "format=xlsx; rows=" + answer.rowCount()
                            + "; masked=" + answer.maskedColumns()
                            + "; grants=" + answer.grantIds()
                            + "; fingerprint=" + nullSafe(answer.auditFingerprint(), "none")));
            return new ExportFile("insight-" + System.currentTimeMillis() + ".xlsx",
                    output.toByteArray(), answer.rowCount(), answer.maskedColumns());
        } catch (java.io.IOException e) {
            throw new BizException(ErrorCode.GOV_INSIGHT_FAILED,
                    "生成 XLSX 失败: " + e.getMessage(), null, e);
        }
    }

    private static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    public PageResult<QueryLogView> history(long page, long size, String keyword,
                                            Boolean succeeded, String username) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        String term = keyword == null ? null : keyword.trim();
        if (term != null && term.length() > 200) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "查询关键词不能超过 200 字");
        }
        String user = username == null ? null : username.trim();
        LambdaQueryWrapper<InsightQueryLog> wrapper = new LambdaQueryWrapper<InsightQueryLog>()
                .eq(InsightQueryLog::getWorkspaceId, workspaceId)
                .and(term != null && !term.isBlank(),
                        w -> w.like(InsightQueryLog::getQuestion, term)
                                .or().like(InsightQueryLog::getGeneratedSql, term))
                .eq(succeeded != null, InsightQueryLog::getSucceeded, succeeded)
                .eq(user != null && !user.isBlank(), InsightQueryLog::getUsername, user)
                .orderByDesc(InsightQueryLog::getCreatedAt);
        Page<InsightQueryLog> result = logMapper.selectPage(Page.of(page, size), wrapper);
        return PageResult.of(result.getRecords().stream().map(QueryLogView::from).toList(),
                result.getTotal(), page, size);
    }

    private InsightQueryLog newFact(Caller caller, String question, int agentId, long datasetId) {
        InsightQueryLog fact = new InsightQueryLog();
        fact.setId(Ids.of("inq"));
        fact.setWorkspaceId(caller.workspaceId());
        fact.setUserId(caller.userId());
        fact.setUsername(caller.username());
        fact.setQuestion(truncate(question, 2000));
        fact.setAgentId(agentId);
        fact.setDatasetId(datasetId);
        fact.setSucceeded(false);
        fact.setCreatedAt(Instant.now());
        return fact;
    }

    private Answer answerCatalogQuestion(Caller caller, String question,
                                         long datasetId, long started, InsightQueryLog fact) {
        List<DatasetOption> all = engine.datasets();
        DatasetOption selected = all.stream()
                .filter(item -> item.id() == datasetId)
                .findFirst().orElse(null);
        String domain = selected == null ? null : selected.domain();
        List<DatasetOption> matched = all.stream()
                .filter(item -> domain == null || domain.equals(item.domain()))
                .sorted(Comparator.comparing(DatasetOption::domain,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(DatasetOption::name,
                                Comparator.nullsLast(String::compareTo)))
                .limit(200)
                .toList();
        List<InsightColumn> columns = List.of(
                new InsightColumn("数据域", "domain", "STRING"),
                new InsightColumn("语义主题", "dataset", "STRING"),
                new InsightColumn("主题 ID", "datasetId", "LONG"));
        List<Map<String, Object>> rows = matched.stream()
                .map(item -> Map.<String, Object>of(
                        "domain", item.domain() == null ? "" : item.domain(),
                        "dataset", item.name() == null ? "" : item.name(),
                        "datasetId", item.id()))
                .toList();
        long elapsed = System.currentTimeMillis() - started;
        fact.setQueryState("METADATA");
        fact.setRowCount(rows.size());
        fact.setElapsedMs(elapsed);
        fact.setSucceeded(true);
        logMapper.insert(fact);
        auditService.record(new AuditEntry(
                AuditService.EXECUTE,
                "INSIGHT_QUERY",
                fact.getId(),
                truncate(question, 256),
                "GOVERNANCE",
                true,
                null,
                "POST /api/v1/insight/ask",
                "dataset=" + datasetId + "; metadata=true; domain=" + domain
                        + "; rows=" + rows.size()));
        String summary = domain == null
                ? "未定位当前主题，返回全部语义主题（共 " + rows.size() + " 个）"
                : "数据域「" + domain + "」下共 " + rows.size() + " 个语义主题";
        return new Answer(null, "METADATA", null, columns, rows, rows.size(),
                false, elapsed, List.of(), List.of(), summary,
                List.of(), null, List.of(), List.of(), null, null);
    }

    private static boolean isCatalogQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String text = question.toLowerCase();
        if (text.contains("字段") || text.contains("列")) {
            return false;
        }
        return CATALOG_QUESTION.matcher(question).find();
    }

    private static String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean covers(GrantCoverage grant, SensitiveColumnView column) {
        if (grant.category() == null || column.category() == null) {
            return false;
        }
        return grant.category().equalsIgnoreCase(column.category())
                && levelRank(column.level()) <= levelRank(grant.maxLevel());
    }

    private static int levelRank(String level) {
        return switch (level == null ? "" : level.toUpperCase()) {
            case "L4" -> 4;
            case "L3" -> 3;
            case "L2" -> 2;
            case "L1" -> 1;
            default -> 0;
        };
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
