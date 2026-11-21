package com.datagov.control.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

/** ActionType 的跨实例日执行计数（data-driven-ops-profile P1-4）。 */
@Data
@TableName("au_action_execution_counter")
public class AutonomyRateLimitEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String workspaceId;
    private String actionCode;
    private LocalDate counterDay;
    private Integer executionCount;
    private Instant updatedAt;
}
