package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 数据集确定性质量剖析快照（V44，governed-agent-evolution-mvp）。
 */
@Data
@TableName("md_dataset_profile_run")
public class DatasetProfileRun {

    @TableId(type = IdType.INPUT)
    private String id;

    private String workspaceId;
    private String datasetId;
    private String datasetName;
    private String verdict;
    private Long totalRows;
    private Long sampledRows;
    private Boolean truncated;
    private Integer anomalyCount;
    private String patternFingerprint;
    private String profileJson;
    private String anomaliesJson;
    private String standardRefsJson;
    private String reproducibilityFingerprint;
    private String createdBy;
    private Instant createdAt;
}
