package com.datagov.metadata.service.gate;

/**
 * 问数编译执行路径（与 MultiWarehouse ExecutionMode 对齐的门禁视角）。
 */
public enum SemanticExecutionMode {
    SINGLE,
    COHORT_UNION,
    ENTITY_FEDERATED_JOIN
}
