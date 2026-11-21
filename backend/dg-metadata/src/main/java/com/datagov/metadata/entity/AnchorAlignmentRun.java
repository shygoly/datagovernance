package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("md_anchor_alignment_run")
public class AnchorAlignmentRun {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String specId;
    private String operatorId;
    private String operatorVersion;
    private String status;
    private Integer anchorCount;
    private Integer hitCount;
    private Integer ambiguousCount;
    private Integer missingCount;
    private String inputDigest;
    private String operatorDigest;
    private String reproducibilityFingerprint;
    private String createdBy;
    private Instant createdAt;
}
