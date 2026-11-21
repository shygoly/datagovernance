package com.datagov.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@TableName("gv_mapping_proposal")
public class MappingProposal {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String sourceTable;
    private String sourceField;
    private String recommendedBusinessUrn;
    private String alternativeCandidatesJson;
    private BigDecimal confidence;
    private String transformationType;
    private String transformExpression;
    private String joinConditionProposal;
    private String evidenceJson;
    private String routingTier;
    private String suggestionFingerprint;
    private String state;
    private String decisionNote;
    private String decidedBy;
    private Instant decidedAt;
    private String createdBy;
    private Instant createdAt;
}
