package com.datagov.app.autonomy;

import com.datagov.control.event.ControlEvents;
import com.datagov.runtime.autonomy.DurableFlowEngine;
import com.fasterxml.jackson.databind.JsonNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * data-driven-ops-profile 的真实 PostgreSQL + HTTP 端到端验证：
 * 登录 → Layer 3 契约发布 → Flow 编译/发布 → Trigger 命中 → Arbiter →
 * SHADOW 实例 → T+1 回检 → 晋升 L2 → L2 审批提案。
 *
 * <p>这是唯一会启动完整 Spring Context 的自治测试；外部依赖只有 EmbeddedPostgres。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "dg.security.secret-key=e2e-autonomy-secret-key",
        "dg.security.jwt.signing-key=e2e-autonomy-jwt-signing-key-0123456789abcdef",
        "dg.bootstrap.enabled=true",
        "dg.bootstrap.admin-username=admin",
        "dg.bootstrap.admin-password=e2e-admin-pass",
        "dg.bootstrap.default-workspace-code=e2e",
        "dg.bootstrap.default-workspace-name=E2E",
        "dg.lineage.parser.base-url=",
        "dg.insight.agent-token=e2e-agent-token",
        "dg.scheduler.enabled=false",
        "dg.ops-report.scheduler.enabled=false",
        "dg.autonomy.flow-sweep-ms=600000"
})
class AutonomyHttpE2ETest {

    private static final EmbeddedPostgres PG = startPostgres();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception ex) {
            throw new IllegalStateException("EmbeddedPostgres 启动失败", ex);
        }
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        PG.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    DurableFlowEngine flowEngine;

    @Test
    @DisplayName("HTTP 端到端：Trigger → Arbiter → SHADOW → Verify → 晋升 → L2 审批")
    void autonomyFlowEndToEnd() {
        String token = login();
        String workspaceId = workspaceId();
        HttpHeaders base = headers(token, workspaceId);

        String entityId = createAndPublish(base, "entity_e2e", "同步作业", "ENTITY",
                "{}", null);
        String metricId = createAndPublish(base, "badrowrate", "脏数据率", "METRIC",
                "{\"metricKind\":\"BASE\",\"aggregation\":\"AVG\"}", "STD-E2E-BADROW");
        String targetSpec = """
                {"targetEntityRef":"entity_e2e","invariants":[
                  {"code":"INV_BADROW","metricRef":"badrowrate","operator":"LTE",
                   "threshold":0.5,"severity":"HARD_BLOCK"}
                ],"objectives":[
                  {"metricRef":"badrowrate","direction":"MINIMIZE","threshold":0.05,"weight":1}
                ]}
                """;
        String targetId = createAndPublish(base, "target_e2e", "同步新鲜度目标", "TARGET",
                targetSpec, null);
        String actionSpec = """
                {"actionCode":"GENERATE_REPORT","authLevel":"L2_APPROVAL",
                 "domainScope":"PLATFORM_INTERNAL","activityRef":"governance.report.generate",
                 "paramEnvelope":{"required":["reportId"],"properties":{"reportId":{"type":"string"}}},
                 "dryRunMode":"SIMULATE_IMPACT","compensatingActionCode":"GENERATE_REPORT",
                 "defaultCooldownSec":60,"verifyDelaySec":1}
                """;
        String actionId = createAndPublish(base, "action_e2e_report", "生成运营报表", "ACTION_TYPE",
                actionSpec, null);

        Map<String, Object> flowSpec = flowSpec("flow_e2e_guard", "E2E 自治守护");
        JsonNode flow = post(base, "/api/v1/autonomy/flows", Map.of(
                "code", "flow_e2e_guard",
                "name", "E2E 自治守护",
                "description", "trigger→arbiter→shadow→verify→L2",
                "runMode", "SHADOW",
                "spec", flowSpec));
        String flowId = flow.path("id").asText();
        assertThat(flow.path("runMode").asText()).isEqualTo("SHADOW");
        post(base, "/api/v1/autonomy/flows/" + flowId + "/publish", Map.of());

        JsonNode trigger = post(base, "/api/v1/autonomy/triggers", Map.of(
                "code", "trigger_e2e_etl",
                "name", "E2E ETL 异常",
                "sourceType", "ETL_ANOMALY",
                "condition", Map.of("statuses", List.of("FAILED_BAD_ROW_LIMIT")),
                "debounceSec", 0,
                "flowId", flowId,
                "enabled", true,
                "priority", 100));
        String triggerId = trigger.path("id").asText();

        JsonNode first = post(base, "/api/v1/autonomy/triggers/" + triggerId + "/fire", Map.of(
                "jobId", "job_e2e_1",
                "status", "FAILED_BAD_ROW_LIMIT",
                "actionParams", Map.of("reportId", "rep_e2e"),
                "metrics", Map.of("badRowRate", 0.2, "badrowrate", 0.2)));
        String instanceId = first.path("instanceId").asText();
        String traceId = first.path("traceId").asText();
        assertThat(instanceId).startsWith("afi_");
        assertThat(traceId).startsWith("atrc_");

        JsonNode instance = get(base, "/api/v1/autonomy/instances/" + instanceId);
        assertThat(instance.path("status").asText()).isEqualTo("WAITING_VERIFY");

        // 不发送手工 VERIFY_DUE：发布真实 ETL 完成事件，由 Provider 提供 after-metrics，
        // 再由引擎到期 sweep 自动唤醒 WAITING_VERIFY。
        events.publishEvent(etlEvent(workspaceId, "job_e2e_1", "exec_e2e_1", 0.01));
        int advanced = flowEngine.sweepDueInstances(Instant.now().plusSeconds(5), 10);
        assertThat(advanced).isGreaterThanOrEqualTo(1);
        JsonNode completed = get(base, "/api/v1/autonomy/instances/" + instanceId);
        assertThat(completed.path("status").asText()).isEqualTo("SHADOW_COMPLETED");

        JsonNode trace = get(base, "/api/v1/autonomy/traces/" + traceId);
        assertThat(trace.path("arbiterDecision").path("status").asText()).isEqualTo("APPROVED");
        assertThat(trace.path("verifyStatus").asText()).isEqualTo("IMPROVED");
        assertThat(trace.path("verifyResult").path("after").path("badrowrate").asDouble())
                .isEqualTo(0.01);

        JsonNode promoted = post(base, "/api/v1/autonomy/flows/" + flowId + "/promote", Map.of(
                "targetMode", "L2_ASSISTED",
                "note", "E2E SHADOW 回检 IMPROVED"));
        assertThat(promoted.path("runMode").asText()).isEqualTo("L2_ASSISTED");

        JsonNode second = post(base, "/api/v1/autonomy/triggers/" + triggerId + "/fire", Map.of(
                "jobId", "job_e2e_2",
                "status", "FAILED_BAD_ROW_LIMIT",
                "actionParams", Map.of("reportId", "rep_e2e"),
                "metrics", Map.of("badRowRate", 0.2, "badrowrate", 0.2)));
        String secondInstance = second.path("instanceId").asText();
        JsonNode waiting = get(base, "/api/v1/autonomy/instances/" + secondInstance);
        assertThat(waiting.path("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(waiting.path("checkpoint").path("proposalId").asText()).startsWith("agpr_");

        JsonNode approvals = get(base, "/api/v1/autonomy/approvals");
        assertThat(approvals.isArray()).isTrue();
        assertThat(approvals.size()).isGreaterThanOrEqualTo(1);
        JsonNode approval = approvals.get(0);
        assertThat(approval.path("status").asText()).isEqualTo("PENDING");
        assertThat(approval.path("payload").path("instanceId").asText()).isEqualTo(secondInstance);

        // 另一个 SHADOW 流程：真实 ETL 事件 badRowRate 上升，回检必须 DEGRADED。
        Map<String, Object> degradedSpec = flowSpec("flow_e2e_degraded", "E2E 降级守护");
        JsonNode degradedFlow = post(base, "/api/v1/autonomy/flows", Map.of(
                "code", "flow_e2e_degraded",
                "name", "E2E 降级守护",
                "runMode", "SHADOW",
                "spec", degradedSpec));
        String degradedFlowId = degradedFlow.path("id").asText();
        post(base, "/api/v1/autonomy/flows/" + degradedFlowId + "/publish", Map.of());
        JsonNode degradedTrigger = post(base, "/api/v1/autonomy/triggers", Map.of(
                "code", "trigger_e2e_degraded",
                "name", "E2E 降级触发器",
                "sourceType", "ETL_ANOMALY",
                "condition", Map.of("statuses", List.of("FAILED_BAD_ROW_LIMIT")),
                "debounceSec", 0,
                "flowId", degradedFlowId,
                "enabled", true,
                "priority", 100));
        JsonNode third = post(base, "/api/v1/autonomy/triggers/"
                + degradedTrigger.path("id").asText() + "/fire", Map.of(
                "jobId", "job_e2e_3",
                "status", "FAILED_BAD_ROW_LIMIT",
                "actionParams", Map.of("reportId", "rep_e2e"),
                "metrics", Map.of("badRowRate", 0.1, "badrowrate", 0.1)));
        events.publishEvent(etlEvent(workspaceId, "job_e2e_3", "exec_e2e_3", 0.4));
        flowEngine.sweepDueInstances(Instant.now().plusSeconds(5), 10);
        JsonNode degradedTrace = get(base, "/api/v1/autonomy/traces/" + third.path("traceId").asText());
        assertThat(degradedTrace.path("verifyStatus").asText()).isEqualTo("DEGRADED");

        // 清理：取消 L2 等待中的实例，避免测试结束时留下悬挂锁。
        post(base, "/api/v1/autonomy/instances/" + secondInstance + "/cancel",
                Map.of("note", "e2e cleanup"));
        assertThat(get(base, "/api/v1/autonomy/instances/" + secondInstance)
                .path("status").asText()).isEqualTo("CANCELLED");

        // 防悬浮断言：确保实体/目标/动作确实被发布，而不是靠 mock。
        assertThat(get(base, "/api/v1/metadata/semantic/objects/" + targetId)
                .path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(get(base, "/api/v1/metadata/semantic/objects/" + actionId)
                .path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(entityId).isNotBlank();
        assertThat(metricId).isNotBlank();
    }

    private Map<String, Object> flowSpec(String flowCode, String name) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("schemaVersion", "flow.ir/v1");
        spec.put("flowCode", flowCode);
        spec.put("name", name);
        spec.put("runMode", "SHADOW");
        spec.put("entityKeyExpr", "trigger.jobId");
        spec.put("cooldownSec", 60);
        spec.put("arbiterPolicyRef", "arbiter_default");
        spec.put("steps", List.of(
                Map.of("id", "s1_capture", "kind", "CAPTURE_CONTEXT",
                        "metrics", List.of("badrowrate")),
                Map.of("id", "s2_invariant", "kind", "CHECK_INVARIANT",
                        "targetRef", "target_e2e"),
                Map.of("id", "s3_dry", "kind", "DRY_RUN_ACTION",
                        "actionTypeRef", "action_e2e_report"),
                Map.of("id", "s4_arbiter", "kind", "ARBITER_DECIDE", "fallbackToL2", true),
                Map.of("id", "s5_approval", "kind", "WAIT_HUMAN_SIGNAL",
                        "timeoutSec", 3600, "skipWhen", "runMode == 'SHADOW'"),
                Map.of("id", "s6_execute", "kind", "INVOKE_ACTIVITY",
                        "actionTypeRef", "action_e2e_report",
                        "compensateWith", "action_e2e_report"),
                Map.of("id", "s7_verify", "kind", "DELAYED_VERIFY",
                        "delaySec", 1, "verifyTargetRef", "target_e2e")));
        return spec;
    }

    private ControlEvents.EtlExecutionCompleted etlEvent(String workspaceId, String jobId,
                                                          String executionId, double badRowRate) {
        return new ControlEvents.EtlExecutionCompleted(workspaceId, jobId, executionId, "DUCKDB",
                "SUCCEEDED", 100, 99, 1, badRowRate, Map.of(), "SKIP_BAD_ROWS", Instant.now());
    }

    private String login() {
        JsonNode data = postRaw("/api/v1/auth/login",
                Map.of("username", "admin", "password", "e2e-admin-pass"), null);
        return data.path("token").asText();
    }

    private String workspaceId() {
        JsonNode data = postRaw("/api/v1/auth/login",
                Map.of("username", "admin", "password", "e2e-admin-pass"), null);
        String current = data.path("currentWorkspaceId").asText();
        if (current != null && !current.isBlank()) {
            return current;
        }
        return data.path("workspaces").get(0).path("id").asText();
    }

    private String createAndPublish(HttpHeaders base, String code, String name, String type,
                                    String specJson, String standardRef) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("objectType", type);
        body.put("spec", readJson(specJson));
        body.put("aiContext", Map.of());
        body.put("sourceKind", "HUMAN");
        body.put("scope", "WORKSPACE");
        if (standardRef != null) {
            body.put("standardRef", standardRef);
        }
        JsonNode created = post(base, "/api/v1/metadata/semantic/objects", body);
        String id = created.path("id").asText();
        JsonNode published = post(base, "/api/v1/metadata/semantic/objects/" + id + "/publish", Map.of());
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
        return id;
    }

    private JsonNode get(HttpHeaders base, String path) {
        ResponseEntity<JsonNode> response = rest.exchange("http://127.0.0.1:" + port + path,
                HttpMethod.GET, new HttpEntity<>(null, base), JsonNode.class);
        return ok(response);
    }

    private JsonNode post(HttpHeaders base, String path, Object body) {
        ResponseEntity<JsonNode> response = rest.exchange("http://127.0.0.1:" + port + path,
                HttpMethod.POST, new HttpEntity<>(body, base), JsonNode.class);
        return ok(response);
    }

    private JsonNode postRaw(String path, Object body, HttpHeaders base) {
        HttpHeaders headers = base == null ? new HttpHeaders() : base;
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = rest.exchange("http://127.0.0.1:" + port + path,
                HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
        return ok(response);
    }

    private JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("HTTP status %s body=%s", response.getStatusCode(), response.getBody())
                .isTrue();
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("success").asBoolean(true)).isTrue();
        return body.path("data");
    }

    private HttpHeaders headers(String token, String workspaceId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("X-Workspace-Id", workspaceId);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String raw) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(raw, ex);
        }
    }
}
