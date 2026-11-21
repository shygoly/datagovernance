package com.datagov.metadata.service;

import com.datagov.metadata.entity.CatalogAssetEntities.CatalogColumn;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogSource;
import com.datagov.metadata.mapper.CatalogColumnMapper;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.CatalogSourceMapper;
import com.datagov.metadata.service.LineageService.NodeRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("血缘 Catalog 对齐器")
class LineageCatalogResolverTest {

    private static final String WS = "ws_alpha";

    private CatalogDatasetMapper datasetMapper;
    private CatalogColumnMapper columnMapper;
    private CatalogSourceMapper sourceMapper;
    private LineageCatalogResolver resolver;

    @BeforeEach
    void setUp() {
        datasetMapper = mock(CatalogDatasetMapper.class);
        columnMapper = mock(CatalogColumnMapper.class);
        sourceMapper = mock(CatalogSourceMapper.class);
        resolver = new LineageCatalogResolver(datasetMapper, columnMapper, sourceMapper);

        when(sourceMapper.selectList(any())).thenReturn(List.of(source("cats_1")));
        when(datasetMapper.selectList(any())).thenReturn(List.of());
        when(columnMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("数据源 + 库 + schema + 表精确命中数据集")
    void resolvesDatasetByQualifiedName() {
        when(datasetMapper.selectList(any())).thenReturn(
                List.of(dataset("catds_1", "ods.public.orders")));

        assertThat(resolver.resolveDatasetId(WS, "ds_1", "ods", "public", "orders"))
                .contains("catds_1");
    }

    @Test
    @DisplayName("指定的数据源还没进目录:返回空,不退到工作空间内撞同名表")
    void unknownDatasourceDoesNotFallBack() {
        when(sourceMapper.selectList(any())).thenReturn(List.of());

        assertThat(resolver.resolveDataset(WS, "ds_missing", "ods", "public", "orders")).isEmpty();
        verify(datasetMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("同一限定名出现多个数据集视为歧义,返回空")
    void ambiguousHitsReturnEmpty() {
        when(datasetMapper.selectList(any())).thenReturn(List.of(
                dataset("catds_1", "ods.public.orders"), dataset("catds_2", "ods.public.orders")));

        assertThat(resolver.resolveDataset(WS, "ds_1", "ods", "public", "orders")).isEmpty();
    }

    @Test
    @DisplayName("缺 database 时按 schema.table 尾部唯一匹配兜底")
    void suffixFallbackWhenDatabaseMissing() {
        // schema.table 与 table 两个精确候选未命中,第三次(尾部匹配)命中
        when(datasetMapper.selectList(any())).thenReturn(
                List.of(), List.of(), List.of(dataset("catds_9", "ods.public.orders")));

        assertThat(resolver.resolveDatasetId(WS, null, null, "public", "orders"))
                .contains("catds_9");
    }

    @Test
    @DisplayName("缺 database 且尾部匹配有多个候选:不猜")
    void suffixFallbackAmbiguous() {
        when(datasetMapper.selectList(any())).thenReturn(List.of(), List.of(), List.of(
                dataset("catds_1", "ods.public.orders"), dataset("catds_2", "dw.public.orders")));

        assertThat(resolver.resolveDataset(WS, null, null, "public", "orders")).isEmpty();
    }

    @Test
    @DisplayName("表名为空直接返回空")
    void blankTable() {
        assertThat(resolver.resolveDataset(WS, "ds_1", "ods", "public", " ")).isEmpty();
        verify(datasetMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("列:精确优先,其次大小写不敏感且唯一")
    void resolvesColumn() {
        when(columnMapper.selectList(any())).thenReturn(List.of(
                column("catc_2", "catds_1", "Amount", 2), column("catc_1", "catds_1", "id", 1)));

        assertThat(resolver.resolveColumn(WS, "catds_1", "id")).contains("catc_1");
        assertThat(resolver.resolveColumn(WS, "catds_1", "amount")).contains("catc_2");
        assertThat(resolver.resolveColumn(WS, "catds_1", "missing")).isEmpty();
        assertThat(resolver.listColumnNames("catds_1")).containsExactly("id", "Amount");
    }

    @Test
    @DisplayName("列名只差大小写且有多个候选:不猜")
    void ambiguousColumnCase() {
        when(columnMapper.selectList(any())).thenReturn(List.of(
                column("catc_1", "catds_1", "Name", 1), column("catc_2", "catds_1", "NAME", 2)));

        assertThat(resolver.resolveColumn(WS, "catds_1", "name")).isEmpty();
        assertThat(resolver.resolveColumn(WS, "catds_1", "NAME")).contains("catc_2");
    }

    @Test
    @DisplayName("resolveNodeRef:命中时使用 Catalog 限定名并绑定 dataset / column ID")
    void nodeRefResolved() {
        when(datasetMapper.selectList(any())).thenReturn(
                List.of(dataset("catds_1", "ods.public.orders")));
        when(columnMapper.selectList(any())).thenReturn(
                List.of(column("catc_1", "catds_1", "amount", 1)));

        NodeRef ref = resolver.resolveNodeRef(WS, "ds_1", "ods", "public", "orders", "amount");

        assertThat(ref.nodeType()).isEqualTo("COLUMN");
        assertThat(ref.qualifiedName()).isEqualTo("ods.public.orders.amount");
        assertThat(ref.catalogDatasetId()).isEqualTo("catds_1");
        assertThat(ref.catalogColumnId()).isEqualTo("catc_1");
        assertThat(ref.displayName()).isEqualTo("amount");
    }

    @Test
    @DisplayName("resolveNodeRef:未命中时用入参拼 db.schema.table.column,忽略空段且不带 ID")
    void nodeRefUnresolved() {
        NodeRef withDb = resolver.resolveNodeRef(WS, "ds_1", "dw", "public", "t", "c");
        assertThat(withDb.qualifiedName()).isEqualTo("dw.public.t.c");
        assertThat(withDb.catalogDatasetId()).isNull();
        assertThat(withDb.catalogColumnId()).isNull();

        NodeRef noDb = resolver.resolveNodeRef(WS, "ds_1", null, "public", "t", "c");
        assertThat(noDb.qualifiedName()).isEqualTo("public.t.c");

        NodeRef bare = resolver.resolveNodeRef(WS, "ds_1", " ", null, "t", "c");
        assertThat(bare.qualifiedName()).isEqualTo("t.c");
    }

    @Test
    @DisplayName("resolveNodeRef:数据集命中但列不在目录里,只绑定 dataset")
    void nodeRefDatasetOnly() {
        when(datasetMapper.selectList(any())).thenReturn(
                List.of(dataset("catds_1", "ods.orders")));

        NodeRef ref = resolver.resolveNodeRef(WS, "ds_1", "ods", null, "orders", "new_col");

        assertThat(ref.qualifiedName()).isEqualTo("ods.orders.new_col");
        assertThat(ref.catalogDatasetId()).isEqualTo("catds_1");
        assertThat(ref.catalogColumnId()).isNull();
    }

    @Test
    @DisplayName("候选限定名按精确度排序且去重")
    void candidateOrder() {
        assertThat(LineageCatalogResolver.candidateQualifiedNames("db", "sc", "t"))
                .containsExactly("db.sc.t", "db.t", "sc.t");
        assertThat(LineageCatalogResolver.candidateQualifiedNames(null, "sc", "t"))
                .containsExactly("sc.t", "t");
        assertThat(LineageCatalogResolver.candidateQualifiedNames("db", null, "t"))
                .containsExactly("db.t");
    }

    @Test
    @DisplayName("外部列引用不带 Catalog ID")
    void externalRef() {
        NodeRef ref = LineageCatalogResolver.externalColumnRef("file://ds/a.csv/id", "id");
        assertThat(ref.catalogDatasetId()).isNull();
        assertThat(ref.qualifiedName()).isEqualTo("file://ds/a.csv/id");
    }

    private static CatalogSource source(String id) {
        CatalogSource source = new CatalogSource();
        source.setId(id);
        source.setWorkspaceId(WS);
        source.setDataSourceId("ds_1");
        return source;
    }

    private static CatalogDataset dataset(String id, String qualifiedName) {
        CatalogDataset dataset = new CatalogDataset();
        dataset.setId(id);
        dataset.setWorkspaceId(WS);
        dataset.setSourceId("cats_1");
        dataset.setQualifiedName(qualifiedName);
        dataset.setName(qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1));
        return dataset;
    }

    private static CatalogColumn column(String id, String datasetId, String name, int ordinal) {
        CatalogColumn column = new CatalogColumn();
        column.setId(id);
        column.setDatasetId(datasetId);
        column.setName(name);
        column.setOrdinal(ordinal);
        return column;
    }
}
