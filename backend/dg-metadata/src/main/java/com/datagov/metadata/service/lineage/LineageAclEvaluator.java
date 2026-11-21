package com.datagov.metadata.service.lineage;

import com.datagov.common.tenant.Caller;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 血缘图遍历的逐跳权限判定(column-lineage-e2e P2-4，宪法 C5 Hop-by-Hop ACL)。
 *
 * <p>{@code LineageService} 的 BFS 每走一跳，都要问一次「当前调用者能不能看见下一跳所在的数据集」。
 * 接口放在 dg-metadata、实现由 dg-app 装配 —— 平台权限(Platform Space)的判定不能被
 * Metadata Space 直接依赖。
 *
 * <p>接口是批量的：一层 BFS 会碰到很多节点，逐个数据集发一次查询会把 1000 张表的图拖慢。
 * 实现必须<b>实时</b>按调用者判定，不得缓存跨请求结果。
 */
public interface LineageAclEvaluator {

    /**
     * 在给定数据集 ID 中，返回调用者<b>有权查看</b>的子集。
     *
     * <p>不在返回集合里的数据集一律视为受限：包括不存在的、不属于调用者 workspace 的。
     * 因此实现是「默认拒绝」的。
     */
    Set<String> viewableDatasetIds(Caller caller, Collection<String> datasetIds);

    default boolean canViewDataset(Caller caller, String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            return false;
        }
        return viewableDatasetIds(caller, Set.of(datasetId)).contains(datasetId);
    }

    /**
     * 放行一切。<b>仅用于单元测试或无 ACL 语义的离线场景</b>；生产装配必须由 dg-app 提供真实实现。
     */
    static LineageAclEvaluator allowAll() {
        return (caller, datasetIds) -> datasetIds == null ? Set.of() : new LinkedHashSet<>(datasetIds);
    }
}
