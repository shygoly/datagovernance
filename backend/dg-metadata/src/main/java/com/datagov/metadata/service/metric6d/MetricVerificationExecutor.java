package com.datagov.metadata.service.metric6d;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.query.SqlQuery;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.mapper.SemanticObjectMapper;
import com.datagov.metadata.service.DataQueryService;
import com.datagov.metadata.service.metric6d.MetricProbeRunner.ProbeCase;
import com.datagov.metadata.service.metric6d.MetricStatisticalFalsifier.FalsifyQuery;
import com.datagov.metadata.service.metric6d.MetricStatisticalFalsifier.TableBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * ③b/③c：经 {@link DataAccessGateway} 执行证伪 SQL / 记录探针套件，并回写 {@code verified_json}。
 */
@Service
public class MetricVerificationExecutor {

    private static final int TIMEOUT_SEC = 30;
    private static final int MAX_ROWS = 20;

    private final SemanticObjectMapper objectMapper;
    private final DataQueryService dataQueryService;
    private final MetricStatisticalFalsifier falsifier;
    private final MetricProbeRunner probeRunner;
    private final ObjectMapper json;

    public MetricVerificationExecutor(SemanticObjectMapper objectMapper,
                                      DataQueryService dataQueryService,
                                      MetricStatisticalFalsifier falsifier,
                                      MetricProbeRunner probeRunner,
                                      ObjectMapper json) {
        this.objectMapper = objectMapper;
        this.dataQueryService = dataQueryService;
        this.falsifier = falsifier;
        this.probeRunner = probeRunner;
        this.json = json;
    }

    public record FalsifyAssertion(String id, String assertion, boolean passed, String observed, String detail) {
    }

    public record FalsifyRunResult(
            String objectId,
            String status,
            List<FalsifyAssertion> assertions,
            List<String> lowConfidenceFields,
            Map<String, Object> verified
    ) {
    }

    public record ProbePlanResult(
            String objectId,
            String status,
            List<Map<String, Object>> probes,
            Map<String, Object> verified
    ) {
    }

    public record FalsifyRequest(
            String dataSourceId,
            TableBinding parent,
            TableBinding child,
            Double fkMatchThreshold
    ) {
    }

    @Transactional
    public FalsifyRunResult runFalsification(String objectId, FalsifyRequest request) {
        if (request == null || blank(request.dataSourceId()) || request.parent() == null || request.child() == null) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "dataSourceId / parent / child 必填");
        }
        String ws = WorkspaceContext.requireWorkspaceId();
        SemanticObject obj = require(ws, objectId);

        double thr = request.fkMatchThreshold() == null || request.fkMatchThreshold() <= 0
                ? 0.95 : request.fkMatchThreshold();
        List<FalsifyQuery> queries = falsifier.buildQueries(request.parent(), request.child(), thr);
        List<FalsifyAssertion> assertions = new ArrayList<>();
        List<String> lowConfidence = new ArrayList<>();

        for (FalsifyQuery q : queries) {
            try {
                // DataQueryService → DataAccessGateway（唯一查询入口，含可用性校验）
                SqlQuery.Result result = dataQueryService.execute(
                        request.dataSourceId(),
                        new SqlQuery.Request(q.sql(), MAX_ROWS, TIMEOUT_SEC));
                String observed = firstCell(result);
                boolean passed = evaluateAssertion(q.id(), q.assertion(), observed, thr);
                assertions.add(new FalsifyAssertion(q.id(), q.assertion(), passed, observed, null));
                if (!passed) {
                    lowConfidence.add(mapField(q.id()));
                }
            } catch (RuntimeException ex) {
                assertions.add(new FalsifyAssertion(q.id(), q.assertion(), false, null,
                        ex.getMessage() == null ? "execution failed" : ex.getMessage()));
                lowConfidence.add(mapField(q.id()));
            }
        }

        boolean allPass = lowConfidence.isEmpty();
        String status = allPass ? "PASS" : "LOW_CONFIDENCE";
        Map<String, Object> verified = mergeVerified(obj, Map.of(
                "stat_falsification", status,
                "statFalsification", status,
                "falsifyAssertions", assertions.stream().map(a -> Map.of(
                        "id", a.id(),
                        "assertion", a.assertion(),
                        "passed", a.passed(),
                        "observed", a.observed() == null ? "" : a.observed(),
                        "detail", a.detail() == null ? "" : a.detail()
                )).toList(),
                "lowConfidenceFields", lowConfidence,
                "requiresHumanReview", !allPass,
                "checkedAt", Instant.now().toString()
        ));
        if (!allPass) {
            obj.setRequiresHumanReview(true);
        }
        obj.setVerifiedJson(writeJson(verified));
        obj.setUpdatedAt(Instant.now());
        objectMapper.updateById(obj);
        return new FalsifyRunResult(objectId, status, assertions, lowConfidence, verified);
    }

    /**
     * ③c：生成 6 类探针 SMQ 计划并写入 verified_json（编译/执行断言由 CI 夹具或问数引擎承接）。
     */
    @Transactional
    public ProbePlanResult planProbes(String objectId, String metricCode, String dimensionCode) {
        String ws = WorkspaceContext.requireWorkspaceId();
        SemanticObject obj = require(ws, objectId);
        String code = blank(metricCode) ? obj.getCode() : metricCode.trim();
        List<ProbeCase> probes = probeRunner.buildProbes(code, dimensionCode);
        List<Map<String, Object>> planned = new ArrayList<>();
        for (ProbeCase p : probes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", p.kind().name());
            row.put("assertion", p.assertion());
            row.put("metrics", p.spec().metrics());
            row.put("dimensions", p.spec().dimensions());
            row.put("grain", p.spec().grain());
            planned.add(row);
        }
        Map<String, Object> verified = mergeVerified(obj, Map.of(
                "probe_suite", "PLANNED",
                "probeSuite", "PLANNED",
                "probes", planned,
                "plannedAt", Instant.now().toString()
        ));
        obj.setVerifiedJson(writeJson(verified));
        obj.setUpdatedAt(Instant.now());
        objectMapper.updateById(obj);
        return new ProbePlanResult(objectId, "PLANNED", planned, verified);
    }

    private Map<String, Object> mergeVerified(SemanticObject obj, Map<String, Object> patch) {
        Map<String, Object> existing = readMap(obj.getVerifiedJson());
        Map<String, Object> out = new LinkedHashMap<>(existing);
        out.putAll(patch);
        out.put("verifiedAt", Instant.now().toString());
        if (out.containsKey("lowConfidenceFields") && out.get("lowConfidenceFields") instanceof List<?> list
                && !list.isEmpty()) {
            out.put("status", "LOW_CONFIDENCE");
            out.put("gatesPass", false);
        } else if (!out.containsKey("status")) {
            out.put("status", "PASS");
        }
        return out;
    }

    private static boolean evaluateAssertion(String id, String assertion, String observed, double thr) {
        if (observed == null || observed.isBlank()) {
            return false;
        }
        String v = observed.trim().toLowerCase(Locale.ROOT);
        return switch (id) {
            case "PK_UNIQUENESS" -> v.equals("t") || v.equals("true") || v.equals("1");
            case "NDV_RATIO_1N" -> {
                try {
                    double d = Double.parseDouble(observed);
                    yield d > 0 && d < 1.0;
                } catch (NumberFormatException ex) {
                    yield false;
                }
            }
            case "FK_MATCH_RATE" -> {
                try {
                    yield Double.parseDouble(observed) >= thr;
                } catch (NumberFormatException ex) {
                    yield false;
                }
            }
            default -> assertion != null && !assertion.isBlank();
        };
    }

    private static String mapField(String queryId) {
        return switch (queryId) {
            case "PK_UNIQUENESS" -> "graph_locality.entity_keys";
            case "NDV_RATIO_1N", "FK_MATCH_RATE" -> "graph_locality.join_cardinalities";
            default -> "graph_locality";
        };
    }

    private static String firstCell(SqlQuery.Result result) {
        if (result == null || result.rows() == null || result.rows().isEmpty()) {
            return null;
        }
        List<String> row = result.rows().get(0);
        return row == null || row.isEmpty() ? null : row.get(0);
    }

    private SemanticObject require(String ws, String id) {
        SemanticObject obj = objectMapper.selectById(id);
        if (obj == null || !Objects.equals(ws, obj.getWorkspaceId())) {
            throw new BizException(ErrorCode.SYS_NOT_FOUND, "语义对象不存在");
        }
        return obj;
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = json.readValue(raw, Map.class);
            return m == null ? new LinkedHashMap<>() : new LinkedHashMap<>(m);
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "JSON 序列化失败");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
