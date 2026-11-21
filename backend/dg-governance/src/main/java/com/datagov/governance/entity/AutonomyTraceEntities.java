package com.datagov.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 不可变决策因果链（data-driven-ops-profile P1-10）。
 *
 * <p>只追加，不删除；一次决策的触发快照、Arbiter 裁决、Dry-Run、审批、
 * 执行与 T+1 回检必须能在同一条 trace 上还原。
 */
public final class AutonomyTraceEntities {

    private AutonomyTraceEntities() {
    }

    @Data
    @TableName("au_decision_trace")
    public static class DecisionTrace {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String traceId;
        private String instanceId;
        private String flowId;
        private String triggerId;
        private String runMode;
        /** PENDING / IMPROVED / NO_EFFECT / DEGRADED / INCONCLUSIVE */
        private String verifyStatus;
        private String triggerSnapshotJson;
        private String arbiterDecisionJson;
        private String dryRunResultJson;
        private String proposalId;
        private String activityResultJson;
        private String verifyResultJson;
        private String reproducibilityFingerprint;
        private Instant createdAt;
        private Instant updatedAt;
    }
}
