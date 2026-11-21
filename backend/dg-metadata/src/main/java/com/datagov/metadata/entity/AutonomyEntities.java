package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 数据自治领域包在 Metadata Space 的定义态对象（data-driven-ops-profile）。
 *
 * <p>Target / ActionType 直接复用 {@code md_semantic_object}（object_type = TARGET / ACTION_TYPE），
 * 天然获得双时间态版本、内容摘要、RLS 与发布审批；本文件只放无法塞进语义对象的命名过滤器。
 */
public final class AutonomyEntities {

    private AutonomyEntities() {
    }

    /**
     * 命名可复用过滤器（SMQ Filter / Cohort 条件模板）。
     *
     * <p>只保存声明式条件，不保存查询结果：预设过期与数据变化必须由执行时求值解决。
     */
    @Data
    @TableName("au_filter_preset")
    public static class FilterPreset {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String code;
        private String name;
        private String description;
        /** SMQ_FILTER / COHORT / QUALITY_SCOPE */
        private String presetType;
        private String specJson;
        /** ACTIVE / DISABLED */
        private String status;
        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;
    }
}
