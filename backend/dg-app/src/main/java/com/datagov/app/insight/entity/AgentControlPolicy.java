package com.datagov.app.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 智能体控制面策略与急停开关（Phase B / b3，闭合 OWASP ASI08 / ASI10）。
 */
@Data
@TableName("ig_agent_control_policy")
public class AgentControlPolicy {

    public static final String GLOBAL_WORKSPACE = "__GLOBAL__";

    @TableId(type = IdType.INPUT)
    private String id;

    private String workspaceId;
    private Boolean killSwitchEnabled;
    private String killSwitchReason;
    private Integer maxToolCallsPerTurn;
    private Integer maxConsecutiveErrors;
    private Integer dailyTurnQuota;
    private String updatedBy;
    private Instant updatedAt;
}
