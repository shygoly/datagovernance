package com.datagov.control.etl;

/**
 * etl.ir/v1 的节点种类。
 *
 * <p>入度约束放在枚举上而不是散在编译器的 switch 里:新增一种节点时,
 * 「它接几路输入」是和「它叫什么」同时要回答的问题。
 */
public enum EtlNodeKind {

    SOURCE_JDBC(Category.SOURCE, 0, 0),
    SOURCE_FILE(Category.SOURCE, 0, 0),
    SOURCE_API(Category.SOURCE, 0, 0),

    TRANSFORM_FILTER(Category.TRANSFORM, 1, 1),
    TRANSFORM_SELECT(Category.TRANSFORM, 1, 1),
    TRANSFORM_DERIVE(Category.TRANSFORM, 1, 1),
    TRANSFORM_CLEANSE_MASK(Category.TRANSFORM, 1, 1),
    TRANSFORM_JOIN(Category.TRANSFORM, 2, 2),
    TRANSFORM_AGGREGATE(Category.TRANSFORM, 1, 1),
    TRANSFORM_DEDUP(Category.TRANSFORM, 1, 1),
    TRANSFORM_CUSTOM_SQL(Category.TRANSFORM, 1, 8),
    /** Phase 2:码表映射。绑定平台权威码表(语义对象 + 值字典),编译期校验存在性。 */
    TRANSFORM_CODESET_LOOKUP(Category.TRANSFORM, 1, 1),

    SINK_JDBC(Category.SINK, 1, 1),
    SINK_PARQUET(Category.SINK, 1, 1);

    public enum Category { SOURCE, TRANSFORM, SINK }

    private final Category category;
    private final int minInputs;
    private final int maxInputs;

    EtlNodeKind(Category category, int minInputs, int maxInputs) {
        this.category = category;
        this.minInputs = minInputs;
        this.maxInputs = maxInputs;
    }

    public Category category() {
        return category;
    }

    public int minInputs() {
        return minInputs;
    }

    public int maxInputs() {
        return maxInputs;
    }

    public boolean isSource() {
        return category == Category.SOURCE;
    }

    public boolean isSink() {
        return category == Category.SINK;
    }

    /** 大小写不敏感解析,未知返回 null(由调用方报带定位的诊断)。 */
    public static EtlNodeKind parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
