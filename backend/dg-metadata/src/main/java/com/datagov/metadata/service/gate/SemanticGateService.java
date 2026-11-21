package com.datagov.metadata.service.gate;

import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.service.SemanticQueryCompiler.SemanticQuerySpec;
import com.datagov.metadata.service.SemanticQueryCompiler.TimeRange;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 单仓 / 多仓 / 联邦共用语义门禁（openspec: analysis-hardening-clinical-foundation · W1）。
 *
 * <p>纯确定性、零 LLM；路径差异不得削弱本服务检查项。
 */
@Service
public class SemanticGateService {

    private static final Pattern ABSOLUTE_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}([ T].*)?$");
    private static final Pattern RELATIVE_HINT = Pattern.compile(
            "上个|上周|上月|本周|本月|今年|去年|昨天|今天|明天|最近|近\\d|last\\s|this\\s|previous\\s|yesterday|today",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final ObjectMapper json;

    public SemanticGateService() {
        this(new ObjectMapper());
    }

    @Autowired
    public SemanticGateService(ObjectMapper json) {
        this.json = json == null ? new ObjectMapper() : json;
    }

    /**
     * 评估门禁；失败抛出 {@link CompileRejectException}（各 ExecutionMode 同一 error_code）。
     */
    public SemanticGatePass evaluate(SemanticGateRequest request) {
        if (request == null || request.workspaceId() == null || request.workspaceId().isBlank()) {
            throw reject(SemanticCompileErrorCode.EMPTY_QUERY,
                    "编译请求与 workspaceId 不能为空",
                    "$",
                    List.of(),
                    "提供非空 workspaceId 与合法 SMQ");
        }
        SemanticQuerySpec spec = request.spec();
        List<String> metricCodes = spec == null || spec.metrics() == null ? List.of() : spec.metrics();
        List<String> dimCodes = spec == null || spec.dimensions() == null ? List.of() : spec.dimensions();
        if (metricCodes.isEmpty() && dimCodes.isEmpty()) {
            throw reject(SemanticCompileErrorCode.EMPTY_QUERY,
                    "至少需要指定一个已发布指标或维度",
                    "$.metrics",
                    List.of(),
                    "在 SMQ.metrics 或 SMQ.dimensions 中填入 Catalog Grammar 内的合法 code");
        }

        for (SemanticObject metric : request.metrics()) {
            if (metric.getStandardRef() == null || metric.getStandardRef().isBlank()) {
                throw reject(SemanticCompileErrorCode.STANDARD_MISSING,
                        "指标 " + metric.getCode() + " 缺少 Standard 口径绑定（CONSTITUTION C2），拒绝编译",
                        "$.metrics",
                        List.of(),
                        "为指标绑定 Standard 后再发布，或更换已绑定 Standard 的度量");
            }
            assertAllowedDimensions(metric, request.dimensions());
            assertAllowedGrain(metric, request.grain());
        }

        assertNormalizedTimeRange(spec == null ? null : spec.timeRange());
        assertDatasetAcl(request.allowedDatasetIds(), request.requiredDatasetIds());

        int maxRows = Math.max(1, Math.min(2000, request.maxRows() <= 0 ? 200 : request.maxRows()));
        Integer requested = spec == null ? null : spec.limit();
        boolean truncated = requested != null && requested > maxRows;
        int effectiveLimit = requested == null ? maxRows : Math.max(1, Math.min(maxRows, requested));

        String digest = sha256(String.join("|",
                request.workspaceId(),
                request.executionMode().name(),
                String.valueOf(effectiveLimit),
                String.valueOf(truncated),
                String.valueOf(metricCodes),
                String.valueOf(dimCodes),
                request.grain() == null ? "" : request.grain()));

        return new SemanticGatePass(request.executionMode(), effectiveLimit, truncated, digest);
    }

    private void assertAllowedDimensions(SemanticObject metric, List<SemanticObject> dimensions) {
        Map<String, Object> mSpec = readJsonMap(metric.getSpecJson());
        Object raw = mSpec.get("allowedDimensions");
        if (!(raw instanceof List<?> allowed) || allowed.isEmpty() || dimensions == null || dimensions.isEmpty()) {
            return;
        }
        Set<String> allowedSet = new LinkedHashSet<>();
        for (Object v : allowed) {
            if (v != null) {
                allowedSet.add(String.valueOf(v).toLowerCase(Locale.ROOT));
            }
        }
        List<String> allowedDisplay = allowed.stream().map(String::valueOf).toList();
        for (SemanticObject dim : dimensions) {
            String code = dim.getCode() == null ? "" : dim.getCode().toLowerCase(Locale.ROOT);
            String id = dim.getId() == null ? "" : dim.getId().toLowerCase(Locale.ROOT);
            if (!allowedSet.contains(code) && !allowedSet.contains(id)) {
                throw reject(SemanticCompileErrorCode.DIMENSION_NOT_ALLOWED,
                        "维度 " + dim.getCode() + " 不在指标 " + metric.getCode() + " 允许的分析粒度范围内",
                        "$.dimensions",
                        allowedDisplay,
                        "将 dimensions 改为 allowed 列表中的维度，或调整度量 allowedDimensions 后重新发布");
            }
        }
    }

    private void assertAllowedGrain(SemanticObject metric, String grain) {
        if (grain == null || grain.isBlank()) {
            return;
        }
        Map<String, Object> mSpec = readJsonMap(metric.getSpecJson());
        Object raw = mSpec.get("allowedGrains");
        if (!(raw instanceof List<?> allowed) || allowed.isEmpty()) {
            return;
        }
        List<String> allowedDisplay = new ArrayList<>();
        Set<String> allowedSet = new LinkedHashSet<>();
        for (Object v : allowed) {
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v);
            allowedDisplay.add(s);
            allowedSet.add(s.toLowerCase(Locale.ROOT));
        }
        if (!allowedSet.contains(grain.toLowerCase(Locale.ROOT))) {
            throw reject(SemanticCompileErrorCode.GRAIN_MISMATCH,
                    "度量 " + metric.getCode() + " 不支持 grain=" + grain,
                    "$.grain",
                    allowedDisplay,
                    "将 grain 改为 allowed 之一，或去掉 grain 使用度量默认粒度");
        }
    }

    private void assertNormalizedTimeRange(TimeRange timeRange) {
        if (timeRange == null) {
            return;
        }
        if (!isAbsoluteDate(timeRange.start()) || !isAbsoluteDate(timeRange.end())
                || looksRelative(timeRange.start()) || looksRelative(timeRange.end())) {
            throw reject(SemanticCompileErrorCode.TIME_RANGE_NOT_NORMALIZED,
                    "时间窗必须先经确定性相对时间归一化为绝对日期（start/end）",
                    "$.timeRange",
                    List.of("YYYY-MM-DD"),
                    "调用 Value Linking / 时间归一化后再编译，禁止在 SMQ 中保留口语相对时间");
        }
    }

    private void assertDatasetAcl(Set<String> allowedDatasetIds, Set<String> requiredDatasetIds) {
        if (allowedDatasetIds == null || allowedDatasetIds.isEmpty()) {
            // 空白名单表示调用方未启用数据集 ACL 裁剪（向后兼容）；有 required 且白名单非空才校验
            return;
        }
        if (requiredDatasetIds == null || requiredDatasetIds.isEmpty()) {
            return;
        }
        for (String dsId : requiredDatasetIds) {
            if (dsId != null && !allowedDatasetIds.contains(dsId)) {
                throw reject(SemanticCompileErrorCode.DATASET_ACL_DENIED,
                        "查询引用了当前用户不可见的数据集",
                        "$.datasets",
                        List.of(),
                        "移除越权度量/维度，或申请对应数据集访问权限");
            }
        }
    }

    private static boolean isAbsoluteDate(String value) {
        return value != null && ABSOLUTE_DATE.matcher(value.trim()).matches();
    }

    private static boolean looksRelative(String value) {
        return value != null && RELATIVE_HINT.matcher(value).find();
    }

    private Map<String, Object> readJsonMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static CompileRejectException reject(SemanticCompileErrorCode code,
                                                 String message,
                                                 String location,
                                                 List<String> allowed,
                                                 String repairHint) {
        return new CompileRejectException(CompileReject.of(code, message, location, allowed, repairHint));
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
