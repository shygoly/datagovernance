package com.datagov.control.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Control Space 的自治控制环定义（data-driven-ops-profile）。
 *
 * <p>这里只存“可重新编译得到”的定义与版本，绝不存运行态实例：
 * 实例、锁与冷却归 Runtime（{@code au_flow_instance} / {@code au_entity_lock_cooldown}），
 * 决策证据归 Governance（{@code au_decision_trace}）。
 */
public final class AutonomyControlEntities {

    private AutonomyControlEntities() {
    }

    @Data
    @TableName("au_flow_def")
    public static class FlowDefinition {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String code;
        private String name;
        private String description;
        /** DRAFT / PUBLISHED / DEPRECATED */
        private String status;
        private Integer currentVersion;
        /** SHADOW / L2_ASSISTED / L1_AUTO */
        private String runMode;
        private String ownerUsername;
        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;
    }

    @Data
    @TableName("au_flow_version")
    public static class FlowVersion {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String flowId;
        private Integer versionNo;
        private String specJson;
        private String compiledJson;
        private String contentDigest;
        private String runMode;
        private String promotionNote;
        private Instant compiledAt;
        private String compiledBy;
    }

    @Data
    @TableName("au_trigger_def")
    public static class TriggerDefinition {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String code;
        private String name;
        /** METRIC_THRESHOLD / QUALITY_FAILURE / ETL_ANOMALY / DATA_ARRIVAL */
        private String sourceType;
        private String conditionJson;
        private Integer debounceSec;
        private String flowId;
        private Boolean enabled;
        private Integer priority;
        private Instant lastFiredAt;
        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;
    }
}
