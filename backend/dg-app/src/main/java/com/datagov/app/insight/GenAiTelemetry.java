package com.datagov.app.insight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Java 侧 OpenTelemetry GenAI 语义规约统一适配层（Phase C / c1）。
 *
 * <p>单一模块锁定 {@code gen_ai.*} 属性常量与规约版本，生产默认关闭原始 Prompt /
 * 回复采集（{@code dg.insight.otel.capture-content=false}），防止患者 PII 进入遥测索引。
 */
@Component
public class GenAiTelemetry {

    private static final Logger log = LoggerFactory.getLogger(GenAiTelemetry.class);

    public static final String SEMCONV_VERSION = "1.42.0";
    public static final String ATTR_SEMCONV_VERSION = "gen_ai.semconv.version";
    public static final String ATTR_OPERATION_NAME = "gen_ai.operation.name";
    public static final String ATTR_TOOL_NAME = "gen_ai.tool.name";
    public static final String ATTR_OPERATION_DURATION = "gen_ai.client.operation.duration";
    public static final String ATTR_INPUT_MESSAGES = "gen_ai.input.messages";
    public static final String ATTR_OUTPUT_MESSAGES = "gen_ai.output.messages";

    @Value("${dg.insight.otel.capture-content:false}")
    private boolean captureContent = false;

    public boolean isCaptureContent() {
        return captureContent;
    }

    public Map<String, Object> recordSpan(String operationName,
                                          String workspaceId,
                                          String userId,
                                          String toolName,
                                          long durationMs,
                                          boolean succeeded,
                                          String inputPreview,
                                          String outputPreview) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put(ATTR_SEMCONV_VERSION, SEMCONV_VERSION);
        attrs.put(ATTR_OPERATION_NAME, operationName);
        attrs.put(ATTR_OPERATION_DURATION, Math.max(0L, durationMs) / 1000.0d);
        attrs.put("dg.workspace.id", workspaceId == null ? "" : workspaceId);
        if (userId != null && !userId.isBlank()) {
            attrs.put("dg.user.id", userId);
        }
        if (toolName != null && !toolName.isBlank()) {
            attrs.put(ATTR_TOOL_NAME, toolName);
        }
        attrs.put("otel.status", succeeded ? "OK" : "ERROR");
        if (captureContent) {
            if (inputPreview != null) {
                attrs.put(ATTR_INPUT_MESSAGES, inputPreview.length() > 500
                        ? inputPreview.substring(0, 500) : inputPreview);
            }
            if (outputPreview != null) {
                attrs.put(ATTR_OUTPUT_MESSAGES, outputPreview.length() > 500
                        ? outputPreview.substring(0, 500) : outputPreview);
            }
        }

        Map<String, Object> span = new LinkedHashMap<>();
        span.put("traceId", UUID.randomUUID().toString().replace("-", ""));
        span.put("timestamp", Instant.now().toString());
        span.put("attributes", attrs);
        log.info("otel_genai_span={}", span);
        return span;
    }
}
