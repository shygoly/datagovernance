package com.datagov.app.lineage;

import com.datagov.common.tenant.Caller;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("血缘逐跳 ACL 平台实现")
class DatasetPermissionLineageAclEvaluatorTest {

    private final CatalogDatasetMapper datasetMapper = mock(CatalogDatasetMapper.class);
    private final DatasetPermissionLineageAclEvaluator evaluator =
            new DatasetPermissionLineageAclEvaluator(datasetMapper);

    private static CatalogDataset dataset(String id) {
        CatalogDataset dataset = new CatalogDataset();
        dataset.setId(id);
        dataset.setWorkspaceId("ws_alpha");
        return dataset;
    }

    @Test
    @DisplayName("持有 metadata:asset:read：只放行本 workspace 内真实存在的数据集")
    void viewableOnlyExistingDatasetsInWorkspace() {
        Caller caller = new Caller("u1", "alice", "ws_alpha", false, Set.of("metadata:asset:read"));
        // ds_other 属于别的 workspace / 已删除：查询按 workspace 过滤后不会返回
        when(datasetMapper.selectList(any())).thenReturn(List.of(dataset("ds_a")));

        Set<String> viewable = evaluator.viewableDatasetIds(caller, List.of("ds_a", "ds_other"));

        assertThat(viewable).containsExactly("ds_a");
        assertThat(evaluator.canViewDataset(caller, "ds_a")).isTrue();
        assertThat(evaluator.canViewDataset(caller, "ds_other")).isFalse();
    }

    @Test
    @DisplayName("缺少 metadata:asset:read：全部受限，且不必查库")
    void withoutPermissionEverythingIsRestricted() {
        Caller caller = new Caller("u1", "alice", "ws_alpha", false, Set.of("metadata:lineage:read"));

        assertThat(evaluator.viewableDatasetIds(caller, List.of("ds_a"))).isEmpty();
        verify(datasetMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("平台管理员视为持有；无 workspace 的调用者一律受限")
    void platformAdminAndMissingWorkspace() {
        when(datasetMapper.selectList(any())).thenReturn(List.of(dataset("ds_a")));
        Caller admin = new Caller("u1", "root", "ws_alpha", true, Set.of());
        Caller noWorkspace = new Caller("u1", "root", null, true, Set.of());

        assertThat(evaluator.viewableDatasetIds(admin, List.of("ds_a"))).containsExactly("ds_a");
        assertThat(evaluator.viewableDatasetIds(noWorkspace, List.of("ds_a"))).isEmpty();
        assertThat(evaluator.viewableDatasetIds(null, List.of("ds_a"))).isEmpty();
    }

    @Test
    @DisplayName("空/空白数据集 ID 受限")
    void blankIdsAreRestricted() {
        Caller caller = new Caller("u1", "alice", "ws_alpha", false, Set.of("metadata:asset:read"));

        assertThat(evaluator.canViewDataset(caller, null)).isFalse();
        assertThat(evaluator.canViewDataset(caller, " ")).isFalse();
        verify(datasetMapper, never()).selectList(any());
    }
}
