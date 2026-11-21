package com.datagov.metadata.service.lineage;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.DataSourceType;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.mapper.DataSourceMapper;
import com.datagov.metadata.service.LineageCatalogResolver;
import com.datagov.metadata.service.LineageService;
import com.datagov.metadata.service.LineageService.EdgeInput;
import com.datagov.metadata.service.LineageService.JobRequest;
import com.datagov.metadata.service.LineageService.JobResult;
import com.datagov.metadata.service.LineageService.NodeRef;
import com.datagov.metadata.service.lineage.SqlLineageParserClient.ColumnEdge;
import com.datagov.metadata.service.lineage.SqlLineageParserClient.Diagnostic;
import com.datagov.metadata.service.lineage.SqlLineageParserClient.ParseRequest;
import com.datagov.metadata.service.lineage.SqlLineageParserClient.ParseResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 列级血缘的编排层(column-lineage-e2e P1-7 / P1-8)。
 *
 * <p>把 {@link SqlLineageParserClient}(纯解析)与 Catalog 对齐、{@link LineageService}(写入)
 * 串起来，供三类调用方共用：
 * <ul>
 *   <li>REST 的 {@code parse-preview} / {@code import}；</li>
 *   <li>dg-app 的作业事件监听(BatchDev / StreamingDev 的 SQL 作业)；</li>
 *   <li>{@code CatalogAssetService} 的 VIEW / MATERIALIZED_VIEW 采集。</li>
 * </ul>
 *
 * <p><b>C8：只写解析确认的边。</b>解析结论不是 {@code OK}(或 sidecar 不可达)时，不写任何边，
 * 只把 {@code parse_status / parse_message} 登记到血缘作业上，已有的边保持原状。
 *
 * <p>所有写入都走 {@code WorkspaceContext}；后台线程调用方须先 {@code callAs}。
 */
@Service
public class SqlLineageService {

    private static final Logger log = LoggerFactory.getLogger(SqlLineageService.class);

    public static final String ORIGIN = "AUTO";
    public static final String JOB_TYPE_SQL_IMPORT = "SQL_IMPORT";
    public static final String JOB_TYPE_VIEW = "VIEW";

    private static final int MAX_CATALOG_TABLES = 50;
    private static final int MAX_SQL_LENGTH = 2_000_000;
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)\\b(?:from|join|into)\\s+((?:[`\"\\[]?[\\w$]+[`\"\\]]?\\.){0,2}[`\"\\[]?[\\w$]+[`\"\\]]?)");
    private static final Pattern STARTS_AS_STATEMENT = Pattern.compile(
            "(?is)^\\s*(?:(?:select|with)\\b|\\().*");

    /** 写入方式。 */
    public enum Mode {
        /** 发布/编译后的预估血缘。 */
        PLANNED,
        /** 已被事实证实的血缘(视图定义、手工导入)。 */
        ACTIVE,
        /** ExecutionSucceeded：晋升为 ACTIVE 并绑定 executionId。 */
        PROMOTE
    }

    // ── 入参 / 出参 ─────────────────────────────────────────────────────

    /** 一段待解析 SQL 的上下文。{@code dialect} 为空时由 {@code dataSourceId} 的类型推断。 */
    public record SqlSource(String sql, String dialect, String dataSourceId, String database,
                            String defaultSchema, Map<String, List<String>> catalogSchema) {
    }

    /** 作业级输入(写入时需要名称、类型与来源)。 */
    public record JobSpec(String name, String jobType, String sourceRef, SqlSource source) {
    }

    public record ParsedLineage(String status, String sqlHash, List<EdgeInput> edges,
                                List<Diagnostic> diagnostics, List<String> unresolvedTables) {
        public boolean ok() {
            return SqlLineageParserClient.STATUS_OK.equals(status);
        }

        String message() {
            if (diagnostics == null || diagnostics.isEmpty()) {
                return null;
            }
            String joined = String.join("; ", diagnostics.stream()
                    .map(d -> d.code() + ": " + d.message()).toList());
            return joined.length() <= 500 ? joined : joined.substring(0, 499) + "…";
        }
    }

    public record PreviewRequest(String dialect, String sql, String dataSourceId, String database,
                                 String defaultSchema, Map<String, List<String>> catalogSchema) {
    }

    public record PreviewEdge(String fromQualifiedName, String toQualifiedName,
                              boolean fromCatalogBound, boolean toCatalogBound,
                              String relation, String dependencyType, String transformExpr,
                              boolean maskingApplied) {
    }

    public record PreviewView(String status, String sqlHash, List<PreviewEdge> edges,
                              List<Diagnostic> diagnostics, List<String> unresolvedTables) {
    }

    public record ImportRequest(String jobName, String dialect, String sql, String dataSourceId,
                                String database, String defaultSchema,
                                Map<String, List<String>> catalogSchema) {
    }

    public record ImportView(String status, boolean imported, String jobId, int edges,
                             String sqlHash, List<Diagnostic> diagnostics) {
    }

    /** {@link #record} 的结果。{@code written=false} 表示只登记了解析结论。 */
    public record Outcome(String status, boolean written, String jobId, int edges,
                          String sqlHash, List<Diagnostic> diagnostics) {
    }

    // ── 依赖 ────────────────────────────────────────────────────────────

    private final SqlLineageParserClient client;
    private final LineageCatalogResolver resolver;
    private final LineageService lineageService;
    private final DataSourceMapper dataSourceMapper;

    public SqlLineageService(SqlLineageParserClient client, LineageCatalogResolver resolver,
                             LineageService lineageService, DataSourceMapper dataSourceMapper) {
        this.client = client;
        this.resolver = resolver;
        this.lineageService = lineageService;
        this.dataSourceMapper = dataSourceMapper;
    }

    // ── REST 入口 ───────────────────────────────────────────────────────

    /** 解析预览：只解析，不写任何血缘。 */
    public PreviewView preview(PreviewRequest request) {
        validate(request == null ? null : request.sql());
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        ParsedLineage parsed = parse(workspaceId, new SqlSource(request.sql(), request.dialect(),
                request.dataSourceId(), request.database(), request.defaultSchema(),
                request.catalogSchema()));
        List<PreviewEdge> edges = parsed.edges().stream()
                .map(e -> new PreviewEdge(e.from().qualifiedName(), e.to().qualifiedName(),
                        e.from().catalogColumnId() != null, e.to().catalogColumnId() != null,
                        e.relation(), e.dependencyType(), e.transformExpr(),
                        Boolean.TRUE.equals(e.maskingApplied())))
                .toList();
        return new PreviewView(parsed.status(), parsed.sqlHash(), edges, parsed.diagnostics(),
                parsed.unresolvedTables());
    }

    /** 解析并导入：仅 {@code OK} 时写 ACTIVE 边；其余只登记解析结论。 */
    public ImportView importSql(ImportRequest request) {
        if (request == null || request.jobName() == null || request.jobName().isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "导入血缘必须指定作业名称 jobName");
        }
        validate(request.sql());
        Outcome outcome = record(new JobSpec(request.jobName().trim(), JOB_TYPE_SQL_IMPORT,
                        "sql-import", new SqlSource(request.sql(), request.dialect(),
                        request.dataSourceId(), request.database(), request.defaultSchema(),
                        request.catalogSchema())),
                Mode.ACTIVE, null);
        return new ImportView(outcome.status(), outcome.written(), outcome.jobId(), outcome.edges(),
                outcome.sqlHash(), outcome.diagnostics());
    }

    // ── 编排入口(事件监听 / 视图采集复用) ────────────────────────────

    /**
     * 解析 + 按 {@link Mode} 写入。永不因解析失败抛异常。
     *
     * <p>解析 {@code OK} 且有边 → 写边；{@code OK} 但没有边 → 只登记「未发现列级血缘」；
     * 非 {@code OK} → 只登记解析结论与诊断。
     */
    public Outcome record(JobSpec spec, Mode mode, String executionId) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        ParsedLineage parsed = parse(workspaceId, spec.source());
        String sqlHash = parsed.sqlHash();

        if (parsed.ok() && !parsed.edges().isEmpty()) {
            JobRequest request = new JobRequest(spec.name(), spec.jobType(), spec.sourceRef(),
                    sqlHash, parsed.edges(), "OK", parsed.message());
            JobResult result = switch (mode) {
                case PLANNED -> lineageService.recordPlannedJob(request);
                case ACTIVE -> lineageService.recordJob(request);
                case PROMOTE -> lineageService.promoteJobExecution(request, executionId);
            };
            return new Outcome(parsed.status(), true, result.jobId(), result.edges(), sqlHash,
                    parsed.diagnostics());
        }

        String message = parsed.ok() ? "SQL 解析成功，但未发现列级血缘" : parsed.message();
        JobResult result = lineageService.recordJobParseOutcome(new JobRequest(spec.name(),
                spec.jobType(), spec.sourceRef(), sqlHash, List.of(), parsed.status(), message));
        return new Outcome(parsed.status(), false, result.jobId(), 0, sqlHash, parsed.diagnostics());
    }

    /**
     * 视图 / 物化视图：把视图定义补全成 {@code CREATE VIEW ... AS ...} 后按 ACTIVE 写入
     * ({@code jobType=VIEW})。
     *
     * @param viewQualifiedName Catalog 里视图的限定名，作为血缘作业名
     * @param viewName          视图在 SQL 里的写法({@code schema.view} 或 {@code view})
     */
    public Outcome recordView(String datasetId, String viewQualifiedName, String viewName,
                              String viewDefinition, String dataSourceId, String database,
                              String defaultSchema) {
        String sql = asCreateView(viewName, viewDefinition);
        return record(new JobSpec(viewQualifiedName, JOB_TYPE_VIEW, "catalog:" + datasetId,
                        new SqlSource(sql, null, dataSourceId, database, defaultSchema, null)),
                Mode.ACTIVE, null);
    }

    static String asCreateView(String viewName, String definition) {
        String body = definition == null ? "" : definition.trim();
        if (body.endsWith(";")) {
            body = body.substring(0, body.length() - 1).trim();
        }
        if (STARTS_AS_STATEMENT.matcher(body).matches()) {
            return "CREATE VIEW " + viewName + " AS " + body;
        }
        // 已经是完整 DDL(CREATE [OR REPLACE] [MATERIALIZED] VIEW ...)，原样交给 sidecar。
        return body;
    }

    // ── 解析 ────────────────────────────────────────────────────────────

    /** 解析并把边对齐到 Catalog；永不抛异常(sidecar 故障降级为 INCONCLUSIVE)。 */
    public ParsedLineage parse(String workspaceId, SqlSource source) {
        String dialect = resolveDialect(workspaceId, source);
        if (dialect == null) {
            return inconclusive("DIALECT_UNKNOWN",
                    "无法确定 SQL 方言：请显式指定 dialect，或使用 PostgreSQL / MySQL / Oracle / "
                            + "SQLServer / Doris / StarRocks 数据源");
        }

        Map<String, List<String>> catalogSchema = source.catalogSchema() != null
                && !source.catalogSchema().isEmpty()
                ? source.catalogSchema()
                : assembleCatalogSchema(workspaceId, source);

        ParseResponse response = client.parseColumn(new ParseRequest(dialect, source.sql(),
                blankToNull(source.defaultSchema()), catalogSchema.isEmpty() ? null : catalogSchema));
        if (!response.ok()) {
            return new ParsedLineage(response.status(), response.sqlHash(), List.of(),
                    response.diagnostics(), List.of());
        }

        List<EdgeInput> edges = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> unresolved = new LinkedHashSet<>();
        List<Diagnostic> diagnostics = new ArrayList<>(response.diagnostics());
        for (ColumnEdge edge : response.allEdges()) {
            NodeRef from = nodeRef(workspaceId, source, edge.sourceTable(), edge.sourceColumn());
            NodeRef to = nodeRef(workspaceId, source, edge.targetTable(), edge.targetColumn());
            if (from.catalogDatasetId() == null) {
                unresolved.add(edge.sourceTable());
            }
            if (to.catalogDatasetId() == null) {
                unresolved.add(edge.targetTable());
            }
            if (from.qualifiedName().equals(to.qualifiedName())) {
                continue;
            }
            String relation = edge.relation() == null || edge.relation().isBlank()
                    ? "DERIVE" : edge.relation().trim().toUpperCase(Locale.ROOT);
            String dependency = "INDIRECT".equalsIgnoreCase(edge.dependencyType())
                    ? "INDIRECT" : "DIRECT";
            if (!seen.add(from.qualifiedName() + "|" + to.qualifiedName() + "|" + relation)) {
                continue;
            }
            edges.add(new EdgeInput(from, to, relation, truncate(edge.transformExpr(), 1000),
                    1.0, ORIGIN, dependency, edge.maskingApplied(),
                    edge.maskingApplied() ? "sql:masking-function" : null));
        }
        return new ParsedLineage(response.status(), response.sqlHash(), edges, diagnostics,
                List.copyOf(unresolved));
    }

    private NodeRef nodeRef(String workspaceId, SqlSource source, String table, String column) {
        String[] parts = table == null ? new String[0] : table.split("\\.");
        String name = parts.length == 0 ? "" : parts[parts.length - 1];
        String schema = parts.length >= 2 ? parts[parts.length - 2] : null;
        String database = parts.length >= 3 ? parts[parts.length - 3] : blankToNull(source.database());
        return resolver.resolveNodeRef(workspaceId, blankToNull(source.dataSourceId()),
                database, schema, name, column);
    }

    /**
     * 自动组装 {@code catalogSchema}：粗扫 SQL 里 FROM / JOIN / INTO 后的表名，
     * 在 Catalog 里解析到的才带上列清单。多扫出来的(CTE 名、别名)解析不到就会被丢掉，无副作用；
     * 解析不到的真实表由 sidecar 按 C8 给出 INCONCLUSIVE，不会被猜出列。
     */
    Map<String, List<String>> assembleCatalogSchema(String workspaceId, SqlSource source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (source.sql() == null) {
            return result;
        }
        Matcher matcher = TABLE_REF.matcher(source.sql());
        while (matcher.find() && result.size() < MAX_CATALOG_TABLES) {
            String[] parts = matcher.group(1).replaceAll("[`\"\\[\\]]", "").split("\\.");
            String table = parts[parts.length - 1];
            String schema = parts.length >= 2 ? parts[parts.length - 2] : null;
            String database = parts.length >= 3 ? parts[parts.length - 3] : blankToNull(source.database());
            String key = (schema == null ? table : schema + "." + table).toLowerCase(Locale.ROOT);
            if (result.containsKey(key)) {
                continue;
            }
            try {
                resolver.resolveDataset(workspaceId, blankToNull(source.dataSourceId()),
                                database, schema, table)
                        .map(CatalogDataset::getId)
                        .map(resolver::listColumnNames)
                        .filter(columns -> !columns.isEmpty())
                        .ifPresent(columns -> result.put(key, columns));
            } catch (RuntimeException e) {
                log.debug("组装 catalogSchema 时解析表失败 table={}", key, e);
            }
        }
        return result;
    }

    // ── 方言 ────────────────────────────────────────────────────────────

    String resolveDialect(String workspaceId, SqlSource source) {
        if (source.dialect() != null && !source.dialect().isBlank()) {
            return source.dialect().trim();
        }
        if (source.dataSourceId() == null || source.dataSourceId().isBlank()) {
            return null;
        }
        DataSourceEntity dataSource = dataSourceMapper.selectById(source.dataSourceId().trim());
        if (dataSource == null || !workspaceId.equals(dataSource.getWorkspaceId())) {
            return null;
        }
        return dialectOf(dataSource.getType());
    }

    /**
     * 数据源类型 → sidecar 方言。只收录与 SQLGlot 方言语义一致的类型；达梦 / Db2 / 人大金仓
     * 等不做「近似」映射(C8：宁可 INCONCLUSIVE，也不用一个不准的方言去猜血缘)。
     */
    public static String dialectOf(DataSourceType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case POSTGRESQL -> "postgres";
            case MYSQL -> "mysql";
            case ORACLE -> "oracle";
            case SQLSERVER -> "tsql";
            case DORIS -> "doris";
            case STARROCKS -> "starrocks";
            default -> null;
        };
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private static ParsedLineage inconclusive(String code, String message) {
        return new ParsedLineage(SqlLineageParserClient.STATUS_INCONCLUSIVE, null, List.of(),
                List.of(new Diagnostic(code, "INCONCLUSIVE", message, null)), List.of());
    }

    private static void validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "SQL 不能为空");
        }
        if (sql.length() > MAX_SQL_LENGTH) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "SQL 过长");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
