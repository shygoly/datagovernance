package com.datagov.metadata.service.lineage;

/**
 * 血缘下游的治理消费方（column-lineage-e2e P3-1）：指标 / 分析报告 / 质检规则。
 *
 * <p>它是只读投影，不是血缘事实：不落 {@code md_lineage_*}，每次查询按当前治理对象状态动态生成。
 *
 * @param refType       METRIC / REPORT / QUALITY_RULE
 * @param refId         治理对象主键
 * @param qualifiedName 画布/清单上的稳定名称，如 {@code metric:gmv}
 * @param displayName   展示名
 * @param status        对象当前状态（指标 PUBLISHED、报告 PUBLISHED、规则 ENABLED 等）
 * @param datasetId     它绑定的 Catalog 数据集
 * @param columnId      它绑定的 Catalog 字段；为 null 表示绑定在整表上
 * @param detail        一句话说明（规则类型/严重度、报告主体等），不含任何数据值
 */
public record GovernanceConsumer(String refType, String refId, String qualifiedName,
                                 String displayName, String status,
                                 String datasetId, String columnId, String detail) {
}
