package com.datagov.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 双时间态受控治理事实（Phase C / c2：A0.1 + A0.3 + GOVERNANCE-MODEL Fact/Learn）。
 */
@Data
@TableName("gv_governed_fact")
public class GovernedFact {

    @TableId(type = IdType.INPUT)
    private String id;

    private String workspaceId;
    private String assetRef;
    private String factType;
    private String title;
    private String contentJson;
    private String source;
    private String feedbackSource;
    private String evidenceRef;
    private Double confidence;
    private Integer version;
    private String supersedesId;
    private String state;
    private Instant validFrom;
    private Instant validTo;
    private Instant txRecordedAt;
    private String createdBy;
    private String reviewedBy;
    private Instant reviewedAt;
    private String reviewNote;
    private Instant createdAt;
    private Instant updatedAt;
}
