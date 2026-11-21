package com.datagov.app.lineage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.tenant.Caller;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.service.lineage.LineageAclEvaluator;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 血缘逐跳 ACL 的平台实现(column-lineage-e2e P2-4，宪法 C5)。
 *
 * <p>复用平台目前对「数据集可见性」的既有判定，与 {@code CatalogAssetService.getDataset}
 * 及资产详情接口一致，不另造一套规则：
 * <ol>
 *   <li>调用者在<b>当前 workspace</b> 内持有 {@code metadata:asset:read}
 *       (平台管理员视为持有)；</li>
 *   <li>数据集真实存在、未被软删除，且归属调用者所在 workspace。</li>
 * </ol>
 * 任一条不满足即为受限。数据集 ID 不存在或属于别的 workspace 也算受限(默认拒绝)，
 * 这样血缘表里残留的跨租户/已删除资产引用不会以真实名称出现在图上。
 *
 * <p>每次调用实时查询，不做跨请求缓存：权限被收回后下一次遍历立即生效。
 */
@Component
public class DatasetPermissionLineageAclEvaluator implements LineageAclEvaluator {

    static final String PERMISSION = "metadata:asset:read";

    private final CatalogDatasetMapper datasetMapper;

    public DatasetPermissionLineageAclEvaluator(CatalogDatasetMapper datasetMapper) {
        this.datasetMapper = datasetMapper;
    }

    @Override
    public Set<String> viewableDatasetIds(Caller caller, Collection<String> datasetIds) {
        Set<String> result = new LinkedHashSet<>();
        if (caller == null || caller.workspaceId() == null || caller.workspaceId().isBlank()
                || datasetIds == null || datasetIds.isEmpty()
                || !caller.hasPermission(PERMISSION)) {
            return result;
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String id : datasetIds) {
            if (id != null && !id.isBlank()) {
                wanted.add(id);
            }
        }
        if (wanted.isEmpty()) {
            return result;
        }
        for (CatalogDataset dataset : datasetMapper.selectList(new LambdaQueryWrapper<CatalogDataset>()
                .eq(CatalogDataset::getWorkspaceId, caller.workspaceId())
                .in(CatalogDataset::getId, wanted))) {
            result.add(dataset.getId());
        }
        return result;
    }
}
