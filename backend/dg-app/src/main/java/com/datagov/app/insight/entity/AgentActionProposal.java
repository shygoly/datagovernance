package com.datagov.app.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Agent 写操作宿主确认提案（Phase B / b2，闭合 OWASP ASI09）。
 *
 * <p>Agent 写工具仅生成 PENDING 提案，实际执行必须由发起人在前端宿主确认 UI
 * 提交带真实 JWT 的 confirm 请求后经后端二次校验完成。
 */
@Data
@TableName("ig_agent_action_proposal")
public class AgentActionProposal {

    @TableId(type = IdType.INPUT)
    private String id;

    private String workspaceId;
    private String userId;
    private String username;
    private String conversationId;
    private String actionType;
    private String requiredPerm;
    private String title;
    private String summary;
    private String payloadJson;
    private String payloadDigest;
    private String riskLevel;
    private String status;
    private String resultJson;
    private String errorMessage;
    private Instant expiresAt;
    private String decidedBy;
    private Instant decidedAt;
    private String decisionNote;
    private Instant createdAt;
    private Instant updatedAt;
}
