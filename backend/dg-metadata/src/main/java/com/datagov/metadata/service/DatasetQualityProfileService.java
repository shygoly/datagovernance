package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataAccessGateway;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.query.SqlQuery;
import com.datagov.metadata.domain.DataSourceStatus;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogColumn;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogSource;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.entity.DatasetProfileRun;
import com.datagov.metadata.event.DatasetProfileCompletedEvent;
import com.datagov.metadata.event.DatasetProfileCompletedEvent.AnomalyEvidence;
import com.datagov.metadata.mapper.CatalogColumnMapper;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.CatalogSourceMapper;
import com.datagov.metadata.mapper.DatasetProfileRunMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 确定性数据集质量剖析服务（FR-02 / governed-agent-evolution-mvp）。
 *
 * <p>遵循 CONSTITUTION C2/C3/C8：
 * <ul>
 *   <li>C2：单位/枚举异构、码表漂移、阈值离群剖析必须显式绑定已登记 {@code standardRef}；</li>
 *   <li>C3：全部空值率、异构占比、码表漂移、时间倒挂与阈值离群均由确定性聚合 SQL 计算，LLM 不心算数值；</li>
 *   <li>C8：样本行数不足或查询截断时强制输出 {@code INCONCLUSIVE}，并附带可复现审计指纹。</li>
 * </ul>
 */
@Service
public class DatasetQualityProfileService {

    public static final String VERDICT_OK = "OK";
    public static final String VERDICT_ANOMALIES = "ANOMALIES_DETECTED";
    public static final String VERDICT_INCONCLUSIVE = "INCONCLUSIVE";

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_PROFILE_COLUMNS = 20;
    private static final double DEFAULT_MAX_NULL_RATIO = 0.05d;

    private final CatalogDatasetMapper datasetMapper;
    private final CatalogColumnMapper columnMapper;
    private final CatalogSourceMapper sourceMapper;
    private final DataSourceService dataSourceService;
    private final ConnectionConfigAssembler assembler;
    private final DataAccessGateway gateway;
    private final DatasetProfileRunMapper profileRunMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public DatasetQualityProfileService(CatalogDatasetMapper datasetMapper,
                                        CatalogColumnMapper columnMapper,
                                        CatalogSourceMapper sourceMapper,
                                        DataSourceService dataSourceService,
                                        ConnectionConfigAssembler assembler,
                                        DataAccessGateway gateway,
                                        DatasetProfileRunMapper profileRunMapper,
                                        ApplicationEventPublisher eventPublisher,
                                        ObjectMapper objectMapper) {
        this.datasetMapper = datasetMapper;
        this.columnMapper = columnMapper;
        this.sourceMapper = sourceMapper;
        this.dataSourceService = dataSourceService;
        this.assembler = assembler;
        this.gateway = gateway;
        this.profileRunMapper = profileRunMapper;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    // ── 请求与视图 DTO ──────────────────────────────────────────────────

    public record UnitSpec(String column, List<String> expectedValues, String standardRef) {
    }

    public record CodeTableSpec(String column, List<String> allowedCodes, String standardRef) {
    }

    public record TimestampPairSpec(String startColumn, String endColumn, String standardRef) {
    }

    public record ThresholdSpec(String column, Double min, Double max, String standardRef) {
    }

    public record ProfileRequest(
            Integer minSampleSize,
            Double maxNullRatio,
            List<UnitSpec> unitSpecs,
            List<CodeTableSpec> codeTableSpecs,
            List<TimestampPairSpec> timestampPairs,
            List<ThresholdSpec> thresholdSpecs
    ) {
        public static ProfileRequest defaults() {
            return new ProfileRequest(1, DEFAULT_MAX_NULL_RATIO, List.of(), List.of(), List.of(), List.of());
        }
    }

    public record ColumnNullStat(String column, long nullCount, double nullRatio, boolean anomalous) {
    }

    public record DimensionMetric(
            String dimension,
            String column,
            long violationCount,
            double violationRatio,
            String standardRef,
            String summary
    ) {
    }

    public record ProfileView(
            String id,
            String datasetId,
            String datasetName,
            String datasetQualifiedName,
            String verdict,
            long totalRows,
            long sampledRows,
            boolean truncated,
            int anomalyCount,
            String patternFingerprint,
            List<ColumnNullStat> nullRates,
            List<DimensionMetric> unitOrEnumHeterogeneity,
            List<DimensionMetric> codeTableDrift,
            List<DimensionMetric> timestampInversion,
            List<DimensionMetric> thresholdOutliers,
            List<AnomalyEvidence> anomalies,
            List<String> standardRefs,
            String reproducibilityFingerprint,
            String createdBy,
            Instant createdAt
    ) {
    }

    // ── 核心剖析入口 ────────────────────────────────────────────────────

    public ProfileView profileDataset(String datasetId, ProfileRequest rawRequest) {
        Caller caller = WorkspaceContext.require();
        ProfileRequest req = rawRequest == null ? ProfileRequest.defaults() : rawRequest;
        validateC2Standards(req);

        CatalogDataset dataset = requireDataset(datasetId, caller.workspaceId());
        List<CatalogColumn> columns = columnMapper.selectList(
                new LambdaQueryWrapper<CatalogColumn>()
                        .eq(CatalogColumn::getDatasetId, dataset.getId())
                        .orderByAsc(CatalogColumn::getOrdinal));
        Map<String, CatalogColumn> columnIndex = columns.stream()
                .collect(Collectors.toMap(
                        c -> c.getName().toLowerCase(Locale.ROOT),
                        c -> c,
                        (a, b) -> a,
                        LinkedHashMap::new));

        ExecContext exec = resolveExecContext(dataset);
        int minSample = req.minSampleSize() == null || req.minSampleSize() < 1 ? 1 : req.minSampleSize();
        double maxNullRatio = req.maxNullRatio() == null ? DEFAULT_MAX_NULL_RATIO : req.maxNullRatio();

        List<String> sqlDigests = new ArrayList<>();
        Set<String> standardRefs = new LinkedHashSet<>();

        // 1. 总行数与字段空值率
        List<CatalogColumn> targetCols = columns.size() > MAX_PROFILE_COLUMNS
                ? columns.subList(0, MAX_PROFILE_COLUMNS) : columns;
        String nullSql = buildNullRateSql(exec, dataset, targetCols);
        sqlDigests.add(sha256(nullSql));
        SqlQuery.Result nullResult = runSql(exec, nullSql);
        boolean truncated = nullResult.truncated();

        List<String> nullRow = firstRow(nullResult);
        long totalRows = nullRow.isEmpty() ? 0L : parseLong(nullRow.get(0));

        List<ColumnNullStat> nullRates = new ArrayList<>();
        List<AnomalyEvidence> anomalies = new ArrayList<>();

        for (int i = 0; i < targetCols.size(); i++) {
            CatalogColumn col = targetCols.get(i);
            long present = (i + 1) < nullRow.size() ? parseLong(nullRow.get(i + 1)) : 0L;
            long nullCount = Math.max(0L, totalRows - present);
            double ratio = totalRows == 0 ? 0d : round((double) nullCount / totalRows);
            boolean bad = totalRows >= minSample && ratio > maxNullRatio;
            nullRates.add(new ColumnNullStat(col.getName(), nullCount, ratio, bad));
            if (bad) {
                anomalies.add(new AnomalyEvidence(
                        "NULL_RATE",
                        col.getName(),
                        nullCount,
                        ratio,
                        "BUILTIN_NULL_RATIO",
                        "字段 %s 空值率 %.2f%% 超过阈值 %.2f%%".formatted(col.getName(), ratio * 100, maxNullRatio * 100),
                        Map.of("ruleType", "NOT_NULL", "column", col.getName(), "maxNullRatio", maxNullRatio)
                ));
            }
        }

        // 2. 单位 / 枚举异构占比
        List<DimensionMetric> unitMetrics = new ArrayList<>();
        if (req.unitSpecs() != null) {
            for (UnitSpec spec : req.unitSpecs()) {
                CatalogColumn col = requireColumn(columnIndex, spec.column(), "unitSpecs");
                standardRefs.add(spec.standardRef().trim());
                String sql = buildNotInSql(exec, dataset, col, spec.expectedValues());
                sqlDigests.add(sha256(sql));
                SqlQuery.Result r = runSql(exec, sql);
                truncated = truncated || r.truncated();
                long violations = firstLong(r);
                double ratio = totalRows == 0 ? 0d : round((double) violations / totalRows);
                String summary = violations > 0
                        ? "字段 %s 存在 %d 条（%.2f%%）非标准单位/枚举记录（Standard=%s）"
                        .formatted(col.getName(), violations, ratio * 100, spec.standardRef())
                        : "字段 %s 单位/枚举与标准 %s 一致".formatted(col.getName(), spec.standardRef());
                unitMetrics.add(new DimensionMetric("UNIT_ENUM_HETEROGENEITY", col.getName(),
                        violations, ratio, spec.standardRef(), summary));
                if (totalRows >= minSample && violations > 0) {
                    anomalies.add(new AnomalyEvidence(
                            "UNIT_ENUM_HETEROGENEITY", col.getName(), violations, ratio,
                            spec.standardRef(), summary,
                            Map.of("ruleType", "UNIT_NORMALIZATION", "column", col.getName(),
                                    "standardRef", spec.standardRef(), "expectedValues", spec.expectedValues())
                    ));
                }
            }
        }

        // 3. 码表版本漂移
        List<DimensionMetric> codeMetrics = new ArrayList<>();
        if (req.codeTableSpecs() != null) {
            for (CodeTableSpec spec : req.codeTableSpecs()) {
                CatalogColumn col = requireColumn(columnIndex, spec.column(), "codeTableSpecs");
                standardRefs.add(spec.standardRef().trim());
                String sql = buildNotInSql(exec, dataset, col, spec.allowedCodes());
                sqlDigests.add(sha256(sql));
                SqlQuery.Result r = runSql(exec, sql);
                truncated = truncated || r.truncated();
                long violations = firstLong(r);
                double ratio = totalRows == 0 ? 0d : round((double) violations / totalRows);
                String summary = violations > 0
                        ? "字段 %s 检出 %d 条（%.2f%%）未收录于码表 %s 的漂移码值"
                        .formatted(col.getName(), violations, ratio * 100, spec.standardRef())
                        : "字段 %s 码值完全符合 %s".formatted(col.getName(), spec.standardRef());
                codeMetrics.add(new DimensionMetric("CODE_TABLE_DRIFT", col.getName(),
                        violations, ratio, spec.standardRef(), summary));
                if (totalRows >= minSample && violations > 0) {
                    anomalies.add(new AnomalyEvidence(
                            "CODE_TABLE_DRIFT", col.getName(), violations, ratio,
                            spec.standardRef(), summary,
                            Map.of("ruleType", "CODE_TABLE_CHECK", "column", col.getName(),
                                    "standardRef", spec.standardRef(), "allowedCodes", spec.allowedCodes())
                    ));
                }
            }
        }

        // 4. 时间戳倒挂
        List<DimensionMetric> tsMetrics = new ArrayList<>();
        if (req.timestampPairs() != null) {
            for (TimestampPairSpec pair : req.timestampPairs()) {
                CatalogColumn startCol = requireColumn(columnIndex, pair.startColumn(), "timestampPairs.startColumn");
                CatalogColumn endCol = requireColumn(columnIndex, pair.endColumn(), "timestampPairs.endColumn");
                String stdRef = blank(pair.standardRef()) ? "BUILTIN_TEMPORAL_ORDER" : pair.standardRef().trim();
                standardRefs.add(stdRef);
                String sql = "SELECT COUNT(*) AS cnt FROM " + tableRef(dataset, exec.type())
                        + " WHERE " + quote(exec.type(), startCol.getName()) + " IS NOT NULL AND "
                        + quote(exec.type(), endCol.getName()) + " IS NOT NULL AND "
                        + quote(exec.type(), endCol.getName()) + " < " + quote(exec.type(), startCol.getName());
                sqlDigests.add(sha256(sql));
                SqlQuery.Result r = runSql(exec, sql);
                truncated = truncated || r.truncated();
                long violations = firstLong(r);
                double ratio = totalRows == 0 ? 0d : round((double) violations / totalRows);
                String colLabel = startCol.getName() + "->" + endCol.getName();
                String summary = violations > 0
                        ? "时间对 %s 检出 %d 条（%.2f%%）结束早于开始的时间倒挂记录"
                        .formatted(colLabel, violations, ratio * 100)
                        : "时间对 %s 无倒挂记录".formatted(colLabel);
                tsMetrics.add(new DimensionMetric("TIMESTAMP_INVERSION", colLabel,
                        violations, ratio, stdRef, summary));
                if (totalRows >= minSample && violations > 0) {
                    anomalies.add(new AnomalyEvidence(
                            "TIMESTAMP_INVERSION", colLabel, violations, ratio, stdRef, summary,
                            Map.of("ruleType", "TEMPORAL_ORDER", "startColumn", startCol.getName(),
                                    "endColumn", endCol.getName(), "standardRef", stdRef)
                    ));
                }
            }
        }

        // 5. 基于 Standard 阈值的离群极值
        List<DimensionMetric> outlierMetrics = new ArrayList<>();
        if (req.thresholdSpecs() != null) {
            for (ThresholdSpec spec : req.thresholdSpecs()) {
                CatalogColumn col = requireColumn(columnIndex, spec.column(), "thresholdSpecs");
                standardRefs.add(spec.standardRef().trim());
                String sql = buildThresholdOutlierSql(exec, dataset, col, spec.min(), spec.max());
                sqlDigests.add(sha256(sql));
                SqlQuery.Result r = runSql(exec, sql);
                truncated = truncated || r.truncated();
                long violations = firstLong(r);
                double ratio = totalRows == 0 ? 0d : round((double) violations / totalRows);
                String summary = violations > 0
                        ? "字段 %s 检出 %d 条（%.2f%%）超出标准区间 [%s, %s] 的离群极值（Standard=%s）"
                        .formatted(col.getName(), violations, ratio * 100, spec.min(), spec.max(), spec.standardRef())
                        : "字段 %s 数值均落在标准区间 [%s, %s] 内".formatted(col.getName(), spec.min(), spec.max());
                outlierMetrics.add(new DimensionMetric("THRESHOLD_OUTLIER", col.getName(),
                        violations, ratio, spec.standardRef(), summary));
                if (totalRows >= minSample && violations > 0) {
                    anomalies.add(new AnomalyEvidence(
                            "THRESHOLD_OUTLIER", col.getName(), violations, ratio,
                            spec.standardRef(), summary,
                            Map.of("ruleType", "THRESHOLD_RANGE", "column", col.getName(),
                                    "min", spec.min() == null ? "" : spec.min(),
                                    "max", spec.max() == null ? "" : spec.max(),
                                    "standardRef", spec.standardRef())
                    ));
                }
            }
        }

        // C8：样本不足或查询截断强制返回 INCONCLUSIVE
        String verdict;
        if (totalRows < minSample || truncated) {
            verdict = VERDICT_INCONCLUSIVE;
        } else if (!anomalies.isEmpty()) {
            verdict = VERDICT_ANOMALIES;
        } else {
            verdict = VERDICT_OK;
        }

        String patternFingerprint = anomalies.isEmpty()
                ? null : computePatternFingerprint(dataset.getId(), anomalies);
        List<String> stdRefList = List.copyOf(standardRefs);
        Instant now = Instant.now();

        Map<String, Object> profilePayload = new LinkedHashMap<>();
        profilePayload.put("nullRates", nullRates);
        profilePayload.put("unitOrEnumHeterogeneity", unitMetrics);
        profilePayload.put("codeTableDrift", codeMetrics);
        profilePayload.put("timestampInversion", tsMetrics);
        profilePayload.put("thresholdOutliers", outlierMetrics);

        Map<String, Object> reproPayload = new LinkedHashMap<>();
        reproPayload.put("schemaVersion", 1);
        reproPayload.put("kind", "DATASET_QUALITY_PROFILE");
        reproPayload.put("workspaceId", caller.workspaceId());
        reproPayload.put("datasetId", dataset.getId());
        reproPayload.put("datasetQualifiedName", dataset.getQualifiedName());
        reproPayload.put("schemaHash", dataset.getSchemaHash());
        reproPayload.put("standardRefs", stdRefList);
        reproPayload.put("sqlDigests", sqlDigests);
        reproPayload.put("totalRows", totalRows);
        reproPayload.put("truncated", truncated);
        reproPayload.put("verdict", verdict);
        reproPayload.put("patternFingerprint", patternFingerprint);
        reproPayload.put("delegatedUserId", caller.userId());
        reproPayload.put("computedAt", now.toString());
        String reproJson = toJson(reproPayload);

        DatasetProfileRun run = new DatasetProfileRun();
        run.setId(Ids.of("dpr"));
        run.setWorkspaceId(caller.workspaceId());
        run.setDatasetId(dataset.getId());
        run.setDatasetName(dataset.getName());
        run.setVerdict(verdict);
        run.setTotalRows(totalRows);
        run.setSampledRows(totalRows);
        run.setTruncated(truncated);
        run.setAnomalyCount(anomalies.size());
        run.setPatternFingerprint(patternFingerprint);
        run.setProfileJson(toJson(profilePayload));
        run.setAnomaliesJson(toJson(anomalies));
        run.setStandardRefsJson(toJson(stdRefList));
        run.setReproducibilityFingerprint(reproJson);
        run.setCreatedBy(caller.userId());
        run.setCreatedAt(now);
        profileRunMapper.insert(run);

        // 发布剖析完成事件（仅聚合统计与指纹，零 L3/L4 原始行）
        eventPublisher.publishEvent(new DatasetProfileCompletedEvent(
                run.getId(),
                caller.workspaceId(),
                dataset.getId(),
                dataset.getName(),
                dataset.getQualifiedName(),
                verdict,
                totalRows,
                truncated,
                patternFingerprint,
                List.copyOf(anomalies),
                stdRefList,
                reproJson,
                caller.userId(),
                now
        ));

        return new ProfileView(
                run.getId(),
                dataset.getId(),
                dataset.getName(),
                dataset.getQualifiedName(),
                verdict,
                totalRows,
                totalRows,
                truncated,
                anomalies.size(),
                patternFingerprint,
                nullRates,
                unitMetrics,
                codeMetrics,
                tsMetrics,
                outlierMetrics,
                anomalies,
                stdRefList,
                reproJson,
                caller.userId(),
                now
        );
    }

    public List<ProfileView> listProfiles(String datasetId, int limit) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        CatalogDataset dataset = requireDataset(datasetId, workspaceId);
        int bounded = Math.max(1, Math.min(limit, 50));
        List<DatasetProfileRun> runs = profileRunMapper.selectList(
                new LambdaQueryWrapper<DatasetProfileRun>()
                        .eq(DatasetProfileRun::getWorkspaceId, workspaceId)
                        .eq(DatasetProfileRun::getDatasetId, dataset.getId())
                        .orderByDesc(DatasetProfileRun::getCreatedAt)
                        .last("LIMIT " + bounded));
        return runs.stream().map(r -> toView(r, dataset.getQualifiedName())).toList();
    }

    // ── 校验与 SQL 构造 ─────────────────────────────────────────────────

    private void validateC2Standards(ProfileRequest req) {
        if (req.unitSpecs() != null) {
            for (UnitSpec spec : req.unitSpecs()) {
                if (spec == null || blank(spec.column()) || spec.expectedValues() == null || spec.expectedValues().isEmpty()) {
                    throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID, "unitSpecs 必须指定 column 与 expectedValues");
                }
                if (blank(spec.standardRef())) {
                    throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                            "违反 C2：单位/枚举异构剖析必须绑定已登记 Standard（standardRef）");
                }
            }
        }
        if (req.codeTableSpecs() != null) {
            for (CodeTableSpec spec : req.codeTableSpecs()) {
                if (spec == null || blank(spec.column()) || spec.allowedCodes() == null || spec.allowedCodes().isEmpty()) {
                    throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID, "codeTableSpecs 必须指定 column 与 allowedCodes");
                }
                if (blank(spec.standardRef())) {
                    throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                            "违反 C2：码表版本漂移剖析必须绑定已登记 Standard（standardRef）");
                }
            }
        }
        if (req.thresholdSpecs() != null) {
            for (ThresholdSpec spec : req.thresholdSpecs()) {
                if (spec == null || blank(spec.column()) || (spec.min() == null && spec.max() == null)) {
                    throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID, "thresholdSpecs 必须指定 column 与 min/max 至少一项");
                }
                if (blank(spec.standardRef())) {
                    throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                            "违反 C2：阈值离群剖析必须绑定已登记 Standard（standardRef）");
                }
            }
        }
        if (req.timestampPairs() != null) {
            for (TimestampPairSpec pair : req.timestampPairs()) {
                if (pair == null || blank(pair.startColumn()) || blank(pair.endColumn())) {
                    throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                            "timestampPairs 必须指定 startColumn 与 endColumn");
                }
            }
        }
    }

    private record ExecContext(DataSourceType type, ConnectionConfig config) {
    }

    private ExecContext resolveExecContext(CatalogDataset dataset) {
        CatalogSource source = sourceMapper.selectById(dataset.getSourceId());
        if (source == null) {
            throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID, "数据集所属目录来源不存在");
        }
        if (!"JDBC".equals(source.getSourceType())) {
            throw new BizException(ErrorCode.DAT_UNSUPPORTED_OPERATION,
                    "该数据集来源(" + source.getSourceType() + ")暂不支持确定性 SQL 剖析");
        }
        if (blank(source.getDataSourceId())) {
            throw new BizException(ErrorCode.DAT_UNSUPPORTED_OPERATION, "目录来源未绑定数据源");
        }
        DataSourceEntity ds = dataSourceService.requireInWorkspace(source.getDataSourceId());
        if (ds.getStatus() != DataSourceStatus.AVAILABLE) {
            throw new BizException(ErrorCode.MTD_DATASOURCE_NOT_ACTIVE,
                    "数据源当前状态为 %s，需先通过连通性测试".formatted(ds.getStatus()));
        }
        return new ExecContext(ds.getType(), assembler.assemble(ds, dataset.getWorkspaceId()));
    }

    private SqlQuery.Result runSql(ExecContext exec, String sql) {
        return gateway.executeQuery(exec.type(), exec.config(),
                new SqlQuery.Request(sql, 1, TIMEOUT_SECONDS));
    }

    private String buildNullRateSql(ExecContext exec, CatalogDataset dataset, List<CatalogColumn> columns) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) AS total");
        for (int i = 0; i < columns.size(); i++) {
            sb.append(", COUNT(").append(quote(exec.type(), columns.get(i).getName()))
                    .append(") AS c_").append(i);
        }
        sb.append(" FROM ").append(tableRef(dataset, exec.type()));
        return sb.toString();
    }

    private String buildNotInSql(ExecContext exec, CatalogDataset dataset, CatalogColumn col, List<String> allowed) {
        String quotedCol = quote(exec.type(), col.getName());
        String literals = allowed.stream()
                .map(v -> "'" + v.replace("'", "''") + "'")
                .collect(Collectors.joining(", "));
        return "SELECT COUNT(*) AS cnt FROM " + tableRef(dataset, exec.type())
                + " WHERE " + quotedCol + " IS NOT NULL AND CAST(" + quotedCol
                + " AS VARCHAR) NOT IN (" + literals + ")";
    }

    private String buildThresholdOutlierSql(ExecContext exec, CatalogDataset dataset,
                                            CatalogColumn col, Double min, Double max) {
        String quotedCol = quote(exec.type(), col.getName());
        List<String> predicates = new ArrayList<>();
        if (min != null) {
            predicates.add(quotedCol + " < " + min);
        }
        if (max != null) {
            predicates.add(quotedCol + " > " + max);
        }
        return "SELECT COUNT(*) AS cnt FROM " + tableRef(dataset, exec.type())
                + " WHERE " + quotedCol + " IS NOT NULL AND (" + String.join(" OR ", predicates) + ")";
    }

    private CatalogDataset requireDataset(String datasetId, String workspaceId) {
        CatalogDataset dataset = datasetMapper.selectById(datasetId);
        if (dataset == null || !workspaceId.equals(dataset.getWorkspaceId())) {
            throw BizException.notFound(ErrorCode.MTD_CATALOG_ASSET_NOT_FOUND, "数据集");
        }
        return dataset;
    }

    private CatalogColumn requireColumn(Map<String, CatalogColumn> index, String name, String field) {
        CatalogColumn col = index.get(name.trim().toLowerCase(Locale.ROOT));
        if (col == null) {
            throw new BizException(ErrorCode.MTD_QUALITY_RULE_INVALID,
                    field + " 引用的字段在目录中不存在: " + name);
        }
        return col;
    }

    private static String tableRef(CatalogDataset dataset, DataSourceType type) {
        String qualified = dataset.getQualifiedName();
        if (blank(qualified)) {
            return quote(type, dataset.getName());
        }
        String[] parts = qualified.split("\\.");
        if (parts.length <= 2) {
            return joinQuoted(type, parts, 0);
        }
        return joinQuoted(type, parts, parts.length - 2);
    }

    private static String joinQuoted(DataSourceType type, String[] parts, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (i > from) {
                sb.append('.');
            }
            sb.append(quote(type, parts[i]));
        }
        return sb.toString();
    }

    private static String quote(DataSourceType type, String identifier) {
        boolean backtick = type == DataSourceType.MYSQL || type == DataSourceType.STARROCKS
                || type == DataSourceType.DORIS;
        String safe = identifier.replace("`", "").replace("\"", "");
        return backtick ? "`" + safe + "`" : "\"" + safe + "\"";
    }

    public static String computePatternFingerprint(String datasetId, List<AnomalyEvidence> anomalies) {
        String canonical = datasetId + "|" + anomalies.stream()
                .sorted(Comparator.comparing(AnomalyEvidence::dimension)
                        .thenComparing(AnomalyEvidence::columnName)
                        .thenComparing(a -> a.standardRef() == null ? "" : a.standardRef()))
                .map(a -> a.dimension() + ":" + a.columnName() + ":" + (a.standardRef() == null ? "" : a.standardRef()))
                .collect(Collectors.joining(";"));
        return "pat_" + sha256(canonical).substring(0, 32);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private ProfileView toView(DatasetProfileRun run, String qualifiedName) {
        Map<String, Object> profile = fromJson(run.getProfileJson(), new TypeReference<>() {
        }, Map.of());
        List<AnomalyEvidence> anomalies = fromJson(run.getAnomaliesJson(), new TypeReference<>() {
        }, List.of());
        List<String> stdRefs = fromJson(run.getStandardRefsJson(), new TypeReference<>() {
        }, List.of());
        List<ColumnNullStat> nullRates = convertList(profile.get("nullRates"), new TypeReference<>() {
        });
        List<DimensionMetric> unitMetrics = convertList(profile.get("unitOrEnumHeterogeneity"), new TypeReference<>() {
        });
        List<DimensionMetric> codeMetrics = convertList(profile.get("codeTableDrift"), new TypeReference<>() {
        });
        List<DimensionMetric> tsMetrics = convertList(profile.get("timestampInversion"), new TypeReference<>() {
        });
        List<DimensionMetric> outlierMetrics = convertList(profile.get("thresholdOutliers"), new TypeReference<>() {
        });
        return new ProfileView(
                run.getId(),
                run.getDatasetId(),
                run.getDatasetName(),
                qualifiedName,
                run.getVerdict(),
                run.getTotalRows() == null ? 0L : run.getTotalRows(),
                run.getSampledRows() == null ? 0L : run.getSampledRows(),
                Boolean.TRUE.equals(run.getTruncated()),
                run.getAnomalyCount() == null ? anomalies.size() : run.getAnomalyCount(),
                run.getPatternFingerprint(),
                nullRates,
                unitMetrics,
                codeMetrics,
                tsMetrics,
                outlierMetrics,
                anomalies,
                stdRefs,
                run.getReproducibilityFingerprint(),
                run.getCreatedBy(),
                run.getCreatedAt()
        );
    }

    private <T> List<T> convertList(Object value, TypeReference<List<T>> type) {
        if (value == null) {
            return List.of();
        }
        return objectMapper.convertValue(value, type);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "序列化剖析结果失败", ex.getMessage(), ex);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type, T fallback) {
        if (blank(json)) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static List<String> firstRow(SqlQuery.Result result) {
        if (result == null || result.rows() == null || result.rows().isEmpty()) {
            return List.of();
        }
        return result.rows().get(0);
    }

    private static long firstLong(SqlQuery.Result result) {
        List<String> row = firstRow(result);
        return row.isEmpty() ? 0L : parseLong(row.get(0));
    }

    private static long parseLong(String value) {
        if (blank(value)) {
            return 0L;
        }
        return new BigDecimal(value.trim()).longValue();
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
