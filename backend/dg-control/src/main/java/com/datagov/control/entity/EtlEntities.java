package com.datagov.control.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** 轻量 ETL 管道的持久化实体(V49)。与 ctl_* 同属 Control Space。 */
public final class EtlEntities {

    private EtlEntities() {
    }

    /** 增量水位线:(workspace, job, sourceNode) 唯一。 */
    @Data
    @TableName("etl_watermark")
    public static class EtlWatermark {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String jobId;
        private String sourceNodeId;
        private String watermarkColumn;
        /** TIMESTAMP / NUMBER / STRING */
        private String watermarkType;
        private String lastValue;
        private String lastExecutionId;
        private Instant updatedAt;
        private String updatedBy;
    }

    /** 脏数据批次:坏行本体落对象存储(storageRef),这里只存计数与错误样例。 */
    @Data
    @TableName("etl_bad_row_batch")
    public static class EtlBadRowBatch {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String jobId;
        private String executionId;
        private String nodeId;
        private Long badRowCount;
        private String storageRef;
        private String sampleErrorJson;
        private Instant createdAt;
    }
}
