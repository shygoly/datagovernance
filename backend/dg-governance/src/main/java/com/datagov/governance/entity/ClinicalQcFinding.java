package com.datagov.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 临床专病质控发现（对齐/RuleSpec 命中），复检关闭需宿主确认。 */
@Data
@TableName("gv_clinical_qc_finding")
public class ClinicalQcFinding {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String runId;
    private String rulesetRef;
    private String ruleId;
    private String severity;
    private String patientRef;
    private String anchorRef;
    private Integer sessionSeq;
    private String message;
    private String evidenceJson;
    private String status;
    private String resolutionNote;
    private String resolvedBy;
    private Instant resolvedAt;
    private String createdBy;
    private Instant createdAt;
}
