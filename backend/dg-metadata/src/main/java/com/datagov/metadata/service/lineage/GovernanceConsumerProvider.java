package com.datagov.metadata.service.lineage;

import java.util.Collection;
import java.util.List;

/**
 * 治理消费方的扩展点（column-lineage-e2e P3-1）。
 *
 * <p>指标与质检规则就在 Metadata Space 内，由 {@link SemanticLineageBridge} 直接读取；
 * 分析报告属于 Governance Space，而 {@code dg-metadata} 不可见 {@code dg-governance}（单向依赖），
 * 所以由 {@code dg-app} 实现本接口并装配进来，Metadata Space 只认接口。
 *
 * <p>实现只做<b>读取</b>，且必须按 {@code workspaceId} 过滤；权限由 {@link #requiredPermission()} 声明，
 * 桥接器在调用前用调用者实时权限判定，无权则整个提供者被跳过。
 */
public interface GovernanceConsumerProvider {

    /** 提供的治理对象类型，如 REPORT。 */
    String refType();

    /** 调用者需持有的权限；无权则桥接器不会调用 {@link #findConsumers}。 */
    String requiredPermission();

    /**
     * @param datasetIds 当前图中调用者<b>有权查看</b>的数据集 ID（上层已做逐跳 ACL）
     */
    List<GovernanceConsumer> findConsumers(String workspaceId, Collection<String> datasetIds);
}
