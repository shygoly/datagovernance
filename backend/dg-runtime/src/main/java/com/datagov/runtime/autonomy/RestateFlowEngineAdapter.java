package com.datagov.runtime.autonomy;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.Caller;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可选的 Restate 持久化工作流适配器（data-driven-ops-profile P3-1）。
 *
 * <p>通过 Restate HTTP ingress 调用一个约定的 workflow service；FlowSpec IR 与
 * {@link DurableFlowEngine} SPI 均不变。部署侧需要提供同名服务：
 *
 * <pre>
 * POST /restate/send/{service}/{instanceId}/run          body: {plan, trigger, initiator}
 * POST /restate/send/{service}/{instanceId}/signal       body: {signal, payload, actor}
 * POST /restate/send/{service}/{instanceId}/cancel       body: {reason, actor}
 * POST /restate/output                                   body: {target:"workflow", workFlowName, workflowKey}
 * POST /restate/call/{adminService}/cancelActive         body: {workspaceId, reason, actor}
 * </pre>
 *
 * <p>`run` 的返回值必须是 {@link FlowInstanceView} JSON；未完成时 Restate 的
 * output 会返回 `not ready`，这里映射为 RUNNING 视图。Workflow 的审批/回检定时器
 * 由 Restate 自己持久化，因此 {@link #sweepDueInstances} 不需要外部轮询。
 *
 * <p>启用方式：`dg.autonomy.engine=restate`。默认 PG 实现仍是零外部中间件的
 * 交付路径；Restate 只是可选高阶后端。
 */
@Service
@ConditionalOnProperty(name = "dg.autonomy.engine", havingValue = "restate")
public class RestateFlowEngineAdapter implements DurableFlowEngine {

    private static final Logger log = LoggerFactory.getLogger(RestateFlowEngineAdapter.class);

    private final ObjectMapper json;
    private final String ingressUrl;
    private final String serviceName;
    private final String adminServiceName;
    private final String authToken;
    private final Duration timeout;
    private final HttpClient http;

    public RestateFlowEngineAdapter(
            ObjectMapper json,
            @Value("${dg.autonomy.restate.ingress-url:http://127.0.0.1:8080}") String ingressUrl,
            @Value("${dg.autonomy.restate.service:DataGovernanceFlow}") String serviceName,
            @Value("${dg.autonomy.restate.admin-service:DataGovernanceFlowAdmin}") String adminServiceName,
            @Value("${dg.autonomy.restate.auth-token:}") String authToken,
            @Value("${dg.autonomy.restate.timeout-ms:10000}") long timeoutMs) {
        this.json = json;
        this.ingressUrl = trimTrailingSlash(ingressUrl);
        this.serviceName = serviceName;
        this.adminServiceName = adminServiceName;
        this.authToken = authToken == null ? "" : authToken;
        this.timeout = Duration.ofMillis(Math.max(500, timeoutMs));
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String startInstance(CompiledFlowPlan plan, FlowTriggerContext trigger, Caller initiator) {
        String instanceId = Ids.of("afi");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan", plan);
        body.put("trigger", trigger);
        body.put("initiator", initiator);
        body.put("instanceId", instanceId);
        // Workflow key 由平台生成，保证同一实例重试不会重复创建 workflow。
        send("/restate/send/" + segment(serviceName) + "/" + segment(instanceId) + "/run",
                body, Map.of("Idempotency-Key", instanceId));
        return instanceId;
    }

    @Override
    public void sendSignal(String instanceId, String signalName, Map<String, Object> payload, Caller actor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("signal", signalName);
        body.put("payload", payload == null ? Map.of() : payload);
        body.put("actor", actor);
        send("/restate/send/" + segment(serviceName) + "/" + segment(instanceId) + "/signal", body, Map.of());
    }

    @Override
    public void cancelInstance(String instanceId, String reason, Caller actor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("actor", actor);
        send("/restate/send/" + segment(serviceName) + "/" + segment(instanceId) + "/cancel", body, Map.of());
    }

    @Override
    public FlowInstanceView inspect(String instanceId) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("target", "workflow");
        target.put("workflowName", serviceName);
        target.put("workflowKey", instanceId);
        HttpResponse<String> response = post("/restate/output", target, Map.of());
        if (response.statusCode() == 404) {
            throw new BizException(ErrorCode.RTM_FLOW_NOT_FOUND, "Restate workflow 不存在: " + instanceId);
        }
        requireSuccess(response, "inspect");
        String body = response.body() == null ? "" : response.body();
        if (body.contains("not ready") || body.contains("not found")) {
            return runningView(instanceId);
        }
        try {
            FlowInstanceView view = json.readValue(body, FlowInstanceView.class);
            if (view.id() == null || view.id().isBlank()) {
                return runningView(instanceId);
            }
            return view;
        } catch (Exception ex) {
            log.warn("Restate output 不是 FlowInstanceView，按 RUNNING 处理 instance={}: {}",
                    instanceId, ex.getMessage());
            return runningView(instanceId);
        }
    }

    /**
     * Restate workflow 的 durable timer 自己处理审批超时与 T+1 唤醒，
     * 平台不再轮询；保留 SPI 方法是为了 PG 后端可替换。
     */
    @Override
    public int sweepDueInstances(Instant now, int batchSize) {
        return 0;
    }

    @Override
    public int cancelActive(String workspaceId, String reason, Caller actor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspaceId", workspaceId);
        body.put("reason", reason);
        body.put("actor", actor);
        HttpResponse<String> response = post(
                "/restate/call/" + segment(adminServiceName) + "/cancelActive", body, Map.of());
        requireSuccess(response, "cancelActive");
        try {
            Map<String, Object> result = json.readValue(response.body(),
                    new TypeReference<Map<String, Object>>() {
                    });
            Object cancelled = result.get("cancelled");
            return cancelled instanceof Number n ? n.intValue() : 0;
        } catch (Exception ex) {
            return 0;
        }
    }

    private FlowInstanceView runningView(String instanceId) {
        return new FlowInstanceView(instanceId, "", "", "", 0, null, null,
                "RUNNING", null, "", "SHADOW", Map.of(), java.util.List.of(),
                null, null, Instant.now(), null);
    }

    private HttpResponse<String> post(String path, Object body, Map<String, String> headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(ingressUrl + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            headers.forEach(builder::header);
            if (!authToken.isBlank()) {
                builder.header("Authorization", "Bearer " + authToken);
            }
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.RTM_FLOW_STATE, "Restate ingress 调用被中断");
        } catch (Exception ex) {
            throw new BizException(ErrorCode.RTM_FLOW_STATE,
                    "Restate ingress 调用失败: " + ex.getMessage());
        }
    }

    private void send(String path, Object body, Map<String, String> headers) {
        HttpResponse<String> response = post(path, body, headers);
        requireSuccess(response, path);
    }

    private void requireSuccess(HttpResponse<String> response, String action) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        throw new BizException(ErrorCode.RTM_FLOW_STATE,
                "Restate " + action + " 返回 " + response.statusCode()
                        + ": " + truncate(response.body(), 300));
    }

    private static String trimTrailingSlash(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String segment(String raw) {
        return raw == null || raw.isBlank() ? "unknown" : raw.trim();
    }

    private static String truncate(String raw, int max) {
        if (raw == null) {
            return "";
        }
        return raw.length() <= max ? raw : raw.substring(0, max);
    }
}
