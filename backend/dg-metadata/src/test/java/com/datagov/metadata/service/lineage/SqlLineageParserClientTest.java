package com.datagov.metadata.service.lineage;

import com.datagov.metadata.service.lineage.SqlLineageParserClient.ParseRequest;
import com.datagov.metadata.service.lineage.SqlLineageParserClient.ParseResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SQL 血缘解析客户端(C8 降级 + 熔断)")
class SqlLineageParserClientTest {

    private static final String OK_BODY = """
            {"status":"OK","sqlHash":"abc","statements":[{"targetTable":"dws.t","edges":[
              {"sourceTable":"ods.s","sourceColumn":"x","targetTable":"dws.t","targetColumn":"a",
               "dependencyType":"DIRECT","relation":"COPY","transformExpr":null,"maskingApplied":false,
               "futureField":1}],"warnings":[]}],"diagnostics":[]}
            """;

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int statusCode = 200;
    private volatile String body = OK_BODY;
    private volatile long delayMillis = 0;
    private volatile String lastRequestBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/parse/column", exchange -> {
            hits.incrementAndGet();
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static ParseRequest request() {
        return new ParseRequest("postgres", "INSERT INTO dws.t (a) SELECT x FROM ods.s", null, null);
    }

    @Test
    @DisplayName("正常响应原样解析(忽略未知字段)，并按契约发送请求体")
    void parsesOkResponse() {
        SqlLineageParserClient client = new SqlLineageParserClient(url(), 2000, 3, 30, System::nanoTime);

        ParseResponse response = client.parseColumn(request());

        assertThat(response.ok()).isTrue();
        assertThat(response.sqlHash()).isEqualTo("abc");
        assertThat(response.allEdges()).hasSize(1);
        assertThat(response.allEdges().get(0).sourceTable()).isEqualTo("ods.s");
        assertThat(response.allEdges().get(0).relation()).isEqualTo("COPY");
        assertThat(lastRequestBody).contains("\"dialect\":\"postgres\"").contains("INSERT INTO dws.t");
    }

    @Test
    @DisplayName("sidecar 不可达 → INCONCLUSIVE，不抛异常、不编边")
    void unreachableDegradesToInconclusive() {
        // 端口 1 几乎必然拒绝连接
        SqlLineageParserClient client = new SqlLineageParserClient(
                "http://127.0.0.1:1", 500, 3, 30, System::nanoTime);

        ParseResponse response = client.parseColumn(request());

        assertThat(response.status()).isEqualTo("INCONCLUSIVE");
        assertThat(response.allEdges()).isEmpty();
        assertThat(response.diagnostics()).extracting(SqlLineageParserClient.Diagnostic::code)
                .containsExactly(SqlLineageParserClient.CODE_PARSER_UNAVAILABLE);
    }

    @Test
    @DisplayName("超时 → INCONCLUSIVE(PARSER_TIMEOUT)")
    void timeoutDegradesToInconclusive() {
        delayMillis = 1500;
        SqlLineageParserClient client = new SqlLineageParserClient(url(), 300, 3, 30, System::nanoTime);

        ParseResponse response = client.parseColumn(request());

        assertThat(response.status()).isEqualTo("INCONCLUSIVE");
        assertThat(response.diagnostics().get(0).code())
                .isEqualTo(SqlLineageParserClient.CODE_PARSER_TIMEOUT);
    }

    @Test
    @DisplayName("5xx 与无法解读的响应都降级为 INCONCLUSIVE")
    void serverErrorAndGarbageDegrade() {
        SqlLineageParserClient client = new SqlLineageParserClient(url(), 2000, 10, 30, System::nanoTime);

        statusCode = 500;
        body = "boom";
        assertThat(client.parseColumn(request()).diagnostics().get(0).code())
                .isEqualTo(SqlLineageParserClient.CODE_PARSER_UNAVAILABLE);

        statusCode = 200;
        body = "not json";
        assertThat(client.parseColumn(request()).diagnostics().get(0).code())
                .isEqualTo(SqlLineageParserClient.CODE_PARSER_BAD_RESPONSE);
    }

    @Test
    @DisplayName("4xx(请求被拒)降级但不计入熔断")
    void clientErrorDoesNotTripCircuit() {
        statusCode = 422;
        body = "{\"detail\":\"unsupported dialect\"}";
        SqlLineageParserClient client = new SqlLineageParserClient(url(), 2000, 2, 30, System::nanoTime);

        for (int i = 0; i < 5; i++) {
            ParseResponse response = client.parseColumn(request());
            assertThat(response.status()).isEqualTo("INCONCLUSIVE");
            assertThat(response.diagnostics().get(0).code())
                    .isEqualTo(SqlLineageParserClient.CODE_PARSER_REJECTED);
        }
        assertThat(client.circuitOpen()).isFalse();
        assertThat(hits.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("连续失败达到阈值后熔断:冷却期内不再发请求；冷却后半开探测，成功即恢复")
    void circuitBreakerOpensAndRecovers() {
        AtomicLong now = new AtomicLong(0);
        statusCode = 503;
        SqlLineageParserClient client = new SqlLineageParserClient(url(), 2000, 3, 30, now::get);

        for (int i = 0; i < 3; i++) {
            client.parseColumn(request());
        }
        assertThat(hits.get()).isEqualTo(3);
        assertThat(client.circuitOpen()).isTrue();

        ParseResponse blocked = client.parseColumn(request());
        assertThat(blocked.status()).isEqualTo("INCONCLUSIVE");
        assertThat(blocked.diagnostics().get(0).code())
                .isEqualTo(SqlLineageParserClient.CODE_PARSER_CIRCUIT_OPEN);
        assertThat(hits.get()).as("熔断期间不应再请求 sidecar").isEqualTo(3);

        // 冷却结束 + sidecar 恢复
        now.set(java.time.Duration.ofSeconds(31).toNanos());
        statusCode = 200;
        ParseResponse recovered = client.parseColumn(request());
        assertThat(recovered.ok()).isTrue();
        assertThat(client.circuitOpen()).isFalse();
        assertThat(hits.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("半开探测仍失败 → 立即重新熔断")
    void halfOpenProbeFailureReopens() {
        AtomicLong now = new AtomicLong(0);
        statusCode = 500;
        SqlLineageParserClient client = new SqlLineageParserClient(url(), 2000, 2, 30, now::get);
        client.parseColumn(request());
        client.parseColumn(request());
        assertThat(client.circuitOpen()).isTrue();

        now.set(java.time.Duration.ofSeconds(31).toNanos());
        client.parseColumn(request());          // 半开探测，失败

        assertThat(client.circuitOpen()).isTrue();
        assertThat(hits.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("未配置 base-url → 关闭解析并降级，不发任何请求")
    void blankBaseUrlDisablesParsing() {
        SqlLineageParserClient client = new SqlLineageParserClient(" ", 2000, 3, 30, System::nanoTime);

        ParseResponse response = client.parseColumn(request());

        assertThat(response.status()).isEqualTo("INCONCLUSIVE");
        assertThat(response.diagnostics().get(0).code())
                .isEqualTo(SqlLineageParserClient.CODE_PARSER_DISABLED);
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("空 SQL 直接 ERROR，不请求 sidecar")
    void blankSqlIsError() {
        SqlLineageParserClient client = new SqlLineageParserClient(url(), 2000, 3, 30, System::nanoTime);

        ParseResponse response = client.parseColumn(new ParseRequest("postgres", "  ", null, null));

        assertThat(response.status()).isEqualTo("ERROR");
        assertThat(hits.get()).isZero();
    }
}
