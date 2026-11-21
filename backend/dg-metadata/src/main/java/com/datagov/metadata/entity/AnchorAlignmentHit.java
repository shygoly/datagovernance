package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("md_anchor_alignment_hit")
public class AnchorAlignmentHit {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String runId;
    private String patientRef;
    private String anchorId;
    private Integer sessionSeq;
    private String role;
    private String windowLabel;
    private String candidateRef;
    private Instant eventTs;
    private String status;
    private String detailJson;
}
