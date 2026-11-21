package com.datagov.metadata.service.gate;

/**
 * SMQ / 多仓 / 联邦共用的类型化编译拒绝码（openspec: analysis-hardening-clinical-foundation · W1）。
 */
public enum SemanticCompileErrorCode {
    EMPTY_QUERY,
    STANDARD_MISSING,
    DIMENSION_NOT_ALLOWED,
    GRAIN_MISMATCH,
    TIME_RANGE_NOT_NORMALIZED,
    UNRESOLVED_METRIC,
    UNRESOLVED_DIMENSION,
    DATASET_ACL_DENIED,
    SECURITY_FIELD_FORBIDDEN,
    /** 禁止将 NULL 静默 COALESCE/IFNULL/NVL 为 0，除非度量 Spec 显式 null_as_zero=true */
    NULL_AS_ZERO_FORBIDDEN,
    /** 多事实/星型路径存在多条 STRUCTURAL 候选，需 at_modifiers.USING 消歧 */
    MULTI_FACT_AMBIGUOUS_PATH
}
