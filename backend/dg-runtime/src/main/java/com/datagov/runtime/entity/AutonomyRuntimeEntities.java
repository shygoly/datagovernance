package com.datagov.runtime.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Runtime Space 的自治运行态（data-driven-ops-profile）。
 *
 * <p>实例是事实，定义在 Control；两者用 flow_id + flow_version 钉死，
 * 保证一个月后回看仍知道当时跑的是哪一版 FlowSpec。
 */
public final class AutonomyRuntimeEntities {

    private AutonomyRuntimeEntities() {
    }

    @Data
    @TableName("au_flow_instance")
    public static class FlowInstance {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String flowId;
        private String flowCode;
        private Integer flowVersion;
        private String triggerId;
        private String traceId;
        /** RUNNING / WAITING_APPROVAL / WAITING_VERIFY / COMPENSATING /
         *  COMPLETED / SHADOW_COMPLETED / FAILED / CANCELLED */
        private String status;
        private String currentStepId;
        private String entityKey;
        private String runMode;
        private String planJson;
        private String checkpointJson;
        private String historyJson;
        private Instant wakeAt;
        private String errorMessage;
        private Instant startedAt;
        private Instant finishedAt;
        private Instant updatedAt;
    }

    @Data
    @TableName("au_entity_lock_cooldown")
    public static class EntityLock {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String entityKey;
        private String instanceId;
        private Instant lockedAt;
        private Instant cooldownUntil;
        private Instant updatedAt;
    }
}
