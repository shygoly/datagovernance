package com.datagov.app.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * A2A v1.0 Agent Card 发现端点（Phase C / c1）。
 *
 * <p>对外声明本平台受治理智能体的能力边界、身份认证要求与协议入口（只读元数据卡片，不暴露敏感信息）。
 */
@RestController
@Tag(name = "A2A Agent Card", description = "Agent2Agent Protocol v1.0 能力发现卡片")
public class AgentCardController {

    @GetMapping({"/.well-known/agent-card.json", "/api/v1/a2a/agent-card.json"})
    @Operation(summary = "获取本平台 A2A v1.0 Agent Card")
    public Map<String, Object> agentCard() {
        return Map.of(
                "protocolVersion", "1.0",
                "name", "datagovernance-platform-agent",
                "description", "医疗数据治理平台受治理智能体（只读问数 + 元数据目录 + 宿主确认受控提案）",
                "version", "1.0.0",
                "url", "/api/v1/insight/agent",
                "mcpEndpoint", "/api/v1/mcp",
                "authentication", Map.of(
                        "schemes", List.of("Bearer"),
                        "delegation", "UserContext required; no shared service account (CONSTITUTION C4)"
                ),
                "capabilities", Map.of(
                        "streaming", true,
                        "hostConsentRequiredForWrites", true,
                        "opaqueCrossSystemPeers", List.of("dataQA", "DeepAnalyze")
                ),
                "skills", List.of(
                        Map.of(
                                "id", "insight-data-ask",
                                "name", "受治理智能问数",
                                "description", "发现空间数据集并发起带 L3/L4 自动脱敏与审计的 NL2SQL 问数"
                        ),
                        Map.of(
                                "id", "parquet-local-onboarding",
                                "name", "本地/NFS Parquet 数据源引导接入",
                                "description", "以宿主确认提案方式注册本地 Parquet 数据源并采集元数据目录"
                        )
                )
        );
    }
}
