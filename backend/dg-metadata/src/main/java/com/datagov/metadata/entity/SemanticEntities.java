package com.datagov.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 统一本体语义元数据内核与轻量业务知识图谱实体（OpenSpec: semantic-ontology-kernel）。
 *
 * <p>采用“双层同构”模型：
 * <ul>
 *   <li>Layer 1（计算语义）：ENTITY / ATTRIBUTE / DIMENSION / FACT / METRIC + STRUCTURAL 边 + 物理表列映射；</li>
 *   <li>Layer 2（业务知识图谱）：CONCEPT / PROCESS + SEMANTIC / CAUSAL 边 + AI 业务理解卡 + 安全值字典。</li>
 * </ul>
 */
public final class SemanticEntities {

    private SemanticEntities() {
    }

    @Data
    @TableName("md_semantic_object")
    public static class SemanticObject {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        /** GLOBAL（平台级权威标准本体，跨 Workspace 只读共享）| WORKSPACE（空间私有） */
        private String scope;
        private String code;
        private String name;
        /** ENTITY / ATTRIBUTE / DIMENSION / FACT / METRIC / CONCEPT / PROCESS */
        private String objectType;
        private String domainId;
        private String standardRef;
        private String glossaryTermId;
        private String specJson;
        private String aiContextJson;
        /** L1 / L2 / L3 / L4 */
        private String maxSensitivity;
        /** DRAFT / PROPOSED / PUBLISHED / DEPRECATED / REJECTED */
        private String status;
        private Integer versionNo;
        private Instant validFrom;
        private Instant validTo;
        private String ownerUsername;
        private String contentDigest;
        /** HUMAN / SEED / AI_PROPOSED */
        private String sourceKind;
        private String rejectionReason;
        private String publishedBy;
        private Instant publishedAt;
        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;
    }

    @Data
    @TableName("md_semantic_mapping")
    public static class SemanticMapping {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String objectId;
        /** ID / DESC / CODE / MEASURE / TIME_KEY / FILTER_KEY */
        private String mappingRole;
        private String catalogDatasetId;
        private String catalogColumnId;
        private String warehouseId;
        private String logicalTableId;
        private String expressionSql;
        private String valueNormalizationJson;
        private String sourceUnit;
        private String targetUnit;
        private Boolean isLookupTable;
        private Integer priority;
        private String status;
        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;
    }

    @Data
    @TableName("md_semantic_link")
    public static class SemanticLink {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String fromObjectId;
        private String toObjectId;
        /** STRUCTURAL / SEMANTIC / CAUSAL */
        private String edgeCategory;
        /**
         * STRUCTURAL: HAS_ATTRIBUTE / HAS_FACT / HIERARCHY_PARENT / RELATES_TO / DERIVED_FROM
         * SEMANTIC:   IS_A / PART_OF / SYNONYM_OF / MAPS_TO_CONCEPT / PRECEDES
         * CAUSAL:     CONSTRAINS / EXCLUDES / AFFECTS
         */
        private String linkType;
        /** 1:1 / 1:N / N:1 / N:M */
        private String cardinality;
        private String joinSpecJson;
        private String rationale;
        /** HUMAN / LINEAGE_DERIVED / AI_PROPOSED */
        private String sourceKind;
        /** DRAFT / PROPOSED / PUBLISHED / REJECTED */
        private String status;
        private String rejectionReason;
        private Instant validFrom;
        private Instant validTo;
        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;
    }

    @Data
    @TableName("md_semantic_object_version")
    public static class SemanticObjectVersion {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String objectId;
        private Integer versionNo;
        private String snapshotJson;
        private String contentDigest;
        private Instant validFrom;
        private Instant validTo;
        private String publishedBy;
        private Instant publishedAt;
    }

    @Data
    @TableName("md_semantic_value_dict")
    public static class SemanticValueDict {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String objectId;
        private String rawValue;
        private String displayLabel;
        private String synonymsJson;
        private String sourceKind;
        private Instant createdAt;
        private String createdBy;
    }

    /**
     * Workspace 多仓库挂载（借鉴 MicroStrategy Project DBInstances）。
     */
    @Data
    @TableName("md_workspace_warehouse")
    public static class WorkspaceWarehouse {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String datasourceId;
        private String code;
        private String name;
        /** PRIMARY / SECONDARY */
        private String warehouseRole;
        /** 队列/分区标识，如 MIMIC_IV / EICU_CRD / HIS */
        private String cohortCode;
        /** POSTGRESQL / STARROCKS / MYSQL / CLICKHOUSE / DUCKDB */
        private String dialect;
        /** 互斥队列主键命名空间前缀，如 mimic: / eicu: */
        private String idNamespacePrefix;
        /** SINGLE_ROUTE / COHORT_UNION / ENTITY_FEDERATED_JOIN */
        private String combineMode;
        /** AUTO / STARROCKS / DUCKDB */
        private String federationEngine;
        /** StarRocks External Catalog 名称（可选） */
        private String starrocksCatalogName;
        private Integer priority;
        private String status;
        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;
    }

    /**
     * Workspace 内逻辑表目录（借鉴 MicroStrategy Warehouse Catalog Logical Table & Alias）。
     */
    @Data
    @TableName("md_logical_table")
    public static class LogicalTable {
        @TableId(type = IdType.INPUT)
        private String id;

        private String workspaceId;
        private String warehouseId;
        private String catalogDatasetId;
        private String logicalName;
        /** LOOKUP / FACT / BRIDGE / VIEW_SQL */
        private String tableRole;
        /** 非空表示 Logical Table Alias（解决 Attribute Role） */
        private String aliasOfTableId;
        /** Logical Size：越小越优先被选表器选中 */
        private Integer logicalSize;
        private String customViewSql;
        private String status;
        private Instant createdAt;
        private String createdBy;
        private Instant updatedAt;
        private String updatedBy;
    }
}
