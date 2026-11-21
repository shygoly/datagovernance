package com.datagov.metadata.service.lineage;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 调用 {@code lineage-parser-service}(FastAPI + SQLGlot)的列级 SQL 血缘解析客户端
 * (column-lineage-e2e P1-7)。
 *
 * <p><b>C8：解析不可达不是错误，而是「无法确定」。</b>超时、连接失败、5xx、响应无法解读时一律
 * 降级为 {@code INCONCLUSIVE} 并给出诊断，<b>绝不抛异常打断调用方</b>(作业发布、视图采集
 * 都不该因为 sidecar 挂了而失败)，更不会凭空编出血缘边。
 *
 * <p><b>熔断：</b>连续失败达到阈值后，在冷却期内直接返回 {@code INCONCLUSIVE}、不再发请求，
 * 避免 sidecar 宕机时每次编译都白白等满超时。冷却期过后放行一次探测，成功即关闭熔断。
 *
 * <p>只发 SQL 文本、方言与调用方 Workspace 内的 {@code catalogSchema}；sidecar 无状态、
 * 不连任何库，因此这里不传凭据或连接信息。
 */
@Component
public class SqlLineageParserClient {

    private static final Logger log = LoggerFactory.getLogger(SqlLineageParserClient.class);

    public static final String STATUS_OK = "OK";
    public static final String STATUS_INCONCLUSIVE = "INCONCLUSIVE";
    public static final String STATUS_ERROR = "ERROR";

    public static final String CODE_PARSER_UNAVAILABLE = "PARSER_UNAVAILABLE";
    public static final String CODE_PARSER_TIMEOUT = "PARSER_TIMEOUT";
    public static final String CODE_PARSER_CIRCUIT_OPEN = "PARSER_CIRCUIT_OPEN";
    public static final String CODE_PARSER_REJECTED = "PARSER_REJECTED_REQUEST";
    public static final String CODE_PARSER_BAD_RESPONSE = "PARSER_BAD_RESPONSE";
    public static final String CODE_PARSER_DISABLED = "PARSER_DISABLED";

    // ── 契约(镜像 lineage-parser-service/app/models.py) ───────────────

    public record ParseRequest(String dialect, String sql, String defaultSchema,
                               Map<String, List<String>> catalogSchema) {
    }

    public record ColumnEdge(String sourceTable, String sourceColumn,
                             String targetTable, String targetColumn,
                             String dependencyType, String relation,
                             String transformExpr, boolean maskingApplied) {
    }

    public record StatementLineage(String targetTable, List<ColumnEdge> edges, List<String> warnings) {
        public StatementLineage {
            edges = edges == null ? List.of() : List.copyOf(edges);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record Diagnostic(String code, String severity, String message, Integer statementIndex) {
    }

    public record ParseResponse(String status, String sqlHash, List<StatementLineage> statements,
                                List<Diagnostic> diagnostics) {
        public ParseResponse {
            statements = statements == null ? List.of() : List.copyOf(statements);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        public boolean ok() {
            return STATUS_OK.equals(status);
        }

        public List<ColumnEdge> allEdges() {
            return statements.stream().flatMap(s -> s.edges().stream()).toList();
        }

        /** 把诊断压成一行，写进血缘作业的 parse_message。 */
        public String diagnosticSummary() {
            if (diagnostics.isEmpty()) {
                return null;
            }
            String joined = String.join("; ", diagnostics.stream()
                    .map(d -> d.code() + ": " + d.message()).toList());
            return joined.length() <= 500 ? joined : joined.substring(0, 499) + "…";
        }

        public static ParseResponse inconclusive(String code, String message) {
            return new ParseResponse(STATUS_INCONCLUSIVE, null, List.of(),
                    List.of(new Diagnostic(code, "INCONCLUSIVE", message, null)));
        }
    }

    // ── 状态 ────────────────────────────────────────────────────────────

    private final String baseUrl;
    private final Duration timeout;
    private final int failureThreshold;
    private final long cooldownNanos;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final LongSupplier nanoClock;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicBoolean open = new AtomicBoolean(false);
    /** 仅在 {@link #open} 为 true 时有意义；{@code System.nanoTime()} 可为负，不能用 -1 当哨兵。 */
    private final AtomicLong openedAtNanos = new AtomicLong();

    @Autowired
    public SqlLineageParserClient(
            @Value("${dg.lineage.parser.base-url:http://127.0.0.1:8091}") String baseUrl,
            @Value("${dg.lineage.parser.timeout-ms:5000}") long timeoutMs,
            @Value("${dg.lineage.parser.circuit.failure-threshold:3}") int failureThreshold,
            @Value("${dg.lineage.parser.circuit.cooldown-seconds:30}") long cooldownSeconds) {
        this(baseUrl, timeoutMs, failureThreshold, cooldownSeconds, System::nanoTime);
    }

    SqlLineageParserClient(String baseUrl, long timeoutMs, int failureThreshold,
                           long cooldownSeconds, LongSupplier nanoClock) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        this.timeout = Duration.ofMillis(Math.max(200, timeoutMs));
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownNanos = Duration.ofSeconds(Math.max(1, cooldownSeconds)).toNanos();
        this.nanoClock = nanoClock;
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    // ── 调用 ────────────────────────────────────────────────────────────

    /** 永不抛异常：任何故障都折算成 {@code INCONCLUSIVE}。 */
    public ParseResponse parseColumn(ParseRequest request) {
        if (baseUrl.isEmpty()) {
            return ParseResponse.inconclusive(CODE_PARSER_DISABLED,
                    "未配置 dg.lineage.parser.base-url，SQL 列级血缘解析未启用");
        }
        if (request == null || request.sql() == null || request.sql().isBlank()) {
            return new ParseResponse(STATUS_ERROR, null, List.of(), List.of(
                    new Diagnostic("EMPTY_SQL", "ERROR", "SQL 为空", null)));
        }
        if (circuitOpen()) {
            return ParseResponse.inconclusive(CODE_PARSER_CIRCUIT_OPEN,
                    "血缘解析服务连续不可用，熔断冷却中，本次未解析");
        }
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/parse/column"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                    .build();
            HttpResponse<String> response = http.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code >= 500) {
                return failure(CODE_PARSER_UNAVAILABLE, "血缘解析服务返回 HTTP " + code, null);
            }
            if (code >= 400) {
                // 4xx 说明请求本身被拒(如方言不支持)：服务是健康的，不计入熔断。
                recordSuccess();
                return ParseResponse.inconclusive(CODE_PARSER_REJECTED,
                        "血缘解析服务拒绝了请求(HTTP " + code + "): " + abbreviate(response.body()));
            }
            ParseResponse parsed = mapper.readValue(response.body(), ParseResponse.class);
            if (parsed == null || parsed.status() == null) {
                return failure(CODE_PARSER_BAD_RESPONSE, "血缘解析服务响应缺少 status", null);
            }
            recordSuccess();
            return parsed;
        } catch (java.net.http.HttpTimeoutException e) {
            return failure(CODE_PARSER_TIMEOUT, "血缘解析服务超时(" + timeout.toMillis() + "ms)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ParseResponse.inconclusive(CODE_PARSER_UNAVAILABLE, "血缘解析被中断");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return failure(CODE_PARSER_BAD_RESPONSE, "血缘解析响应无法解读: " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            return failure(CODE_PARSER_UNAVAILABLE,
                    "无法连接血缘解析服务: " + e.getClass().getSimpleName(), e);
        } catch (RuntimeException e) {
            return failure(CODE_PARSER_BAD_RESPONSE, "血缘解析响应无法解读: " + e.getMessage(), e);
        }
    }

    /** 熔断器当前是否处于打开(冷却)状态。 */
    public boolean circuitOpen() {
        if (!open.get()) {
            return false;
        }
        if (nanoClock.getAsLong() - openedAtNanos.get() >= cooldownNanos) {
            // 半开：放行一次探测。探测失败会在 failure() 里重新打开。
            open.set(false);
            consecutiveFailures.set(failureThreshold - 1);
            return false;
        }
        return true;
    }

    private ParseResponse failure(String code, String message, Exception cause) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            openedAtNanos.set(nanoClock.getAsLong());
            open.set(true);
        }
        log.warn("血缘解析服务调用失败 code={} failures={} {}", code, failures, message);
        if (cause != null) {
            log.debug("血缘解析服务调用失败详情", cause);
        }
        return ParseResponse.inconclusive(code, message);
    }

    private void recordSuccess() {
        consecutiveFailures.set(0);
        open.set(false);
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 199) + "…";
    }
}
