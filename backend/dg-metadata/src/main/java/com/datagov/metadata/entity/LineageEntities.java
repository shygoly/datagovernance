package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 字段级血缘（P10.4 + column-lineage-e2e）。边不删除，SQL 变化时旧边标 SUPERSEDED。 */
public final class LineageEntities {

    private LineageEntities() {
    }

    @Data
    @TableName("md_lineage_node")
    public static class LineageNode {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        /** TABLE / COLUMN / DATASET / RESTRICTED / METRIC / REPORT / QUALITY_RULE */
        private String nodeType;
        private String catalogDatasetId;
        private String catalogColumnId;
        private String qualifiedName;
        private String displayName;
        /** 外部治理对象类型：METRIC / REPORT / QUALITY_RULE */
        private String externalRefType;
        private String externalRefId;
        private Instant createdAt;
    }

    @Data
    @TableName("md_lineage_job")
    public static class LineageJob {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String name;
        /** FLINK / SPARK / DORIS / CDC / MANUAL / OFFLINE_SYNC / DB_MIGRATION / VIEW / BATCH / STREAM */
        private String jobType;
        private String sourceRef;
        private String sqlHash;
        private String lastExecutionId;
        /** OK / INCONCLUSIVE / FAILED */
        private String parseStatus;
        private String parseMessage;
        private Instant lastSeenAt;
        private Instant createdAt;
    }

    @Data
    @TableName("md_lineage_edge")
    public static class LineageEdge {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String fromNodeId;
        private String toNodeId;
        private String relation;
        private String transformExpr;
        private String jobId;
        private Double confidence;
        private String origin;
        private String sqlHash;
        private Instant discoveredAt;
        /** PLANNED / ACTIVE / SUPERSEDED */
        private String status;
        private String executionId;
        /** DIRECT / INDIRECT */
        private String dependencyType;
        private Boolean maskingApplied;
        private String maskingEvidence;
        private Instant supersededAt;
        /** 本行（按作业 + 边键）被某次作业上报引入/复活的时间；同一次上报内的所有边取同一值（P3-2 历史 Diff 的批次标识）。 */
        private Instant introducedAt;
        /** 原地更新前的转换表达式；仅在 transformExpr / dependencyType 被某次上报改写时填充（P3-2）。 */
        private String previousTransformExpr;
        private String previousDependencyType;
        /** 上述改写发生的批次时间，与同批次 introducedAt / supersededAt 同值。 */
        private Instant previousChangedAt;
    }
}
