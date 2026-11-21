package com.datagov.runtime.autonomy;

import com.datagov.common.tenant.Caller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class RestateFlowEngineAdapterTest {

    private HttpServer server;
    private ObjectMapper json;
    private RestateFlowEngineAdapter adapter;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile String outputBody = "{\"message\":\"not ready\"}";

    @BeforeEach
    void setUp() throws IOException {
        json = new ObjectMapper().registerModule(new JavaTimeModule());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        adapter = new RestateFlowEngineAdapter(json,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "DataGovernanceFlow", "DataGovernanceFlowAdmin", "", 3000);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("start 使用 Restate workflow send；inspect 读取 output；signal/cancel 走同一 workflow key")
    void speaksRestateIngressContract() throws Exception {
        String instanceId = adapter.startInstance(plan(),
                new FlowTriggerContext("ws1", "atr_1", "job_1",
                        Map.of("traceId", "atrc_1"), Map.of("badRowRate", 0.2), 100, List.of()),
                actor());

        assertThat(instanceId).startsWith("afi_");
        assertThat(paths).anyMatch(p -> p.equals("/restate/send/DataGovernanceFlow/" + instanceId + "/run"));
        assertThat(bodies.get(0)).contains("\"plan\"").contains("\"trigger\"");

        outputBody = json.writeValueAsString(new FlowInstanceView(
                instanceId, "ws1", "afl_1", "guard", 1, "atr_1", "atrc_1",
                "WAITING_APPROVAL", "s2", "job_1", "L2_ASSISTED",
                Map.of("proposalId", "agpr_1"), List.of(), Instant.now(),
                null, Instant.now(), null));
        FlowInstanceView view = adapter.inspect(instanceId);
        assertThat(view.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(view.checkpoint()).containsEntry("proposalId", "agpr_1");

        adapter.sendSignal(instanceId, "APPROVED", Map.of("proposalId", "agpr_1"), actor());
        adapter.cancelInstance(instanceId, "test", actor());
        assertThat(paths).contains(
                "/restate/send/DataGovernanceFlow/" + instanceId + "/signal",
                "/restate/send/DataGovernanceFlow/" + instanceId + "/cancel");

        assertThat(adapter.cancelActive("ws1", "kill switch", actor())).isEqualTo(2);
        assertThat(paths).contains("/restate/call/DataGovernanceFlowAdmin/cancelActive");
        assertThat(adapter.sweepDueInstances(Instant.now(), 10)).isZero();
    }

    @Test
    @DisplayName("Restate output=not ready 时 inspect 返回 RUNNING 合成视图")
    void notReadyOutputMapsToRunning() throws Exception {
        outputBody = "{\"message\":\"not ready\"}";
        String instanceId = adapter.startInstance(plan(),
                new FlowTriggerContext("ws1", null, "job_1", Map.of(), Map.of(), 100, List.of()),
                actor());

        FlowInstanceView view = adapter.inspect(instanceId);
        assertThat(view.id()).isEqualTo(instanceId);
        assertThat(view.status()).isEqualTo("RUNNING");
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        paths.add(path);
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (path.endsWith("/cancelActive")) {
            respond(exchange, 200, "{\"cancelled\":2}");
            return;
        }
        if (path.equals("/restate/output")) {
            respond(exchange, 200, outputBody);
            return;
        }
        respond(exchange, 202, "{\"invocationId\":\"inv_1\",\"status\":\"Accepted\"}");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static CompiledFlowPlan plan() {
        return new CompiledFlowPlan("afl_1", "guard", 1, "L2_ASSISTED",
                "trigger.jobId", 3600, "arbiter_default",
                List.of(new FlowStep("s1", "CAPTURE_CONTEXT", List.of("badRowRate"),
                        null, null, false, null, null, null, null, null, Map.of())),
                "{}", "d".repeat(64), Map.of());
    }

    private static Caller actor() {
        return new Caller("u1", "tester", "ws1", false, Set.of("autonomy:flow:approve"));
    }
}
