package com.datagov.app.insight;

import com.datagov.app.insight.InsightQueryEngine.EngineAskResult;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.connector.federation.DefaultFederatedQueryGateway;
import com.datagov.data.spi.DataAccessGateway;
import com.datagov.data.spi.catalog.CanonicalType;
import com.datagov.data.spi.query.SqlQuery;
import com.datagov.metadata.entity.SemanticEntities.WorkspaceWarehouse;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.CatalogSourceMapper;
import com.datagov.metadata.service.DataQueryService;
import com.datagov.metadata.service.MultiWarehouseQueryCompiler;
import com.datagov.metadata.service.MultiWarehouseQueryCompiler.AggregateMergeColumn;
import com.datagov.metadata.service.MultiWarehouseQueryCompiler.ExecutionMode;
import com.datagov.metadata.service.MultiWarehouseQueryCompiler.FederatedJoinPlan;
import com.datagov.metadata.service.MultiWarehouseQueryCompiler.FederatedSourceExtract;
import com.datagov.metadata.service.MultiWarehouseQueryCompiler.MultiWarehousePlan;
import com.datagov.metadata.service.MultiWarehouseQueryCompiler.WarehouseSubQuery;
import com.datagov.metadata.service.SemanticOntologyService;
import com.datagov.metadata.service.SemanticQueryCompiler;
import com.datagov.metadata.service.SemanticQueryCompiler.CompiledColumn;
import com.datagov.metadata.service.SemanticQueryCompiler.SemanticQuerySpec;
import com.datagov.metadata.service.SemanticValueLinkingService;
import com.datagov.metadata.service.SemanticWarehouseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NativeInsightMultiWarehouseTest {

    private SemanticOntologyService ontologyService;
    private SemanticValueLinkingService valueLinkingService;
    private SemanticQueryCompiler singleCompiler;
    private MultiWarehouseQueryCompiler multiCompiler;
    private SemanticWarehouseService warehouseService;
    private DataQueryService dataQueryService;
    private NativeInsightEngine engine;

    @BeforeEach
    void setUp() {
        WorkspaceContext.set(new Caller("usr_1", "admin", "ws_clinical", true, Set.of("insight:ask")));
        ontologyService = mock(SemanticOntologyService.class);
        valueLinkingService = mock(SemanticValueLinkingService.class);
        singleCompiler = mock(SemanticQueryCompiler.class);
        multiCompiler = mock(MultiWarehouseQueryCompiler.class);
        warehouseService = mock(SemanticWarehouseService.class);
        dataQueryService = mock(DataQueryService.class);
        InsightProperties props = new InsightProperties();
        DefaultFederatedQueryGateway duckDbGateway = new DefaultFederatedQueryGateway(mock(DataAccessGateway.class));

        engine = new NativeInsightEngine(
                ontologyService, valueLinkingService, singleCompiler,
                mock(CatalogDatasetMapper.class), mock(CatalogSourceMapper.class),
                dataQueryService, props, new ChartSpecRecommender(),
                multiCompiler, warehouseService, duckDbGateway
        );
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("COHORT_UNION 两阶段确定性聚合：MIMIC + eICU 精确加权平均（避免 AVG 求平均的辛普森悖论）")
    void cohortUnionComputesExactWeightedAverageAcrossMimicAndEicu() {
        WorkspaceWarehouse wh1 = new WorkspaceWarehouse();
        wh1.setId("wh_mimic");
        when(warehouseService.listActiveWarehousesInternal("ws_clinical")).thenReturn(List.of(wh1));

        List<CompiledColumn> subCols = List.of(
                new CompiledColumn("患者性别", "attr_patient_gender", "DIMENSION"),
                new CompiledColumn("平均ICU时长（和）", "__sum_avg_los", "METRIC"),
                new CompiledColumn("平均ICU时长（计数）", "__cnt_avg_los", "METRIC"),
                new CompiledColumn("患者数", "pat_cnt", "METRIC")
        );
        List<CompiledColumn> finalCols = List.of(
                new CompiledColumn("患者性别", "attr_patient_gender", "DIMENSION"),
                new CompiledColumn("平均ICU时长", "avg_los", "METRIC"),
                new CompiledColumn("患者数", "pat_cnt", "METRIC")
        );
        MultiWarehousePlan plan = new MultiWarehousePlan(
                ExecutionMode.COHORT_UNION,
                "TWO_STAGE_DETERMINISTIC",
                List.of(
                        new WarehouseSubQuery("wh_mimic", "mimic_iv", "MIMIC-IV", "ds_mimic", "MIMIC_IV",
                                "POSTGRESQL", "mimic:", "cd_m", List.of("cd_m"), "SELECT 1", subCols),
                        new WarehouseSubQuery("wh_eicu", "eicu_crd", "eICU-CRD", "ds_eicu", "EICU_CRD",
                                "POSTGRESQL", "eicu:", "cd_e", List.of("cd_e"), "SELECT 2", subCols)
                ),
                List.of(
                        new AggregateMergeColumn("attr_patient_gender", "患者性别", "DIMENSION", "DIM", "attr_patient_gender", null, null),
                        new AggregateMergeColumn("avg_los", "平均ICU时长", "METRIC", "AVG", null, "__sum_avg_los", "__cnt_avg_los"),
                        new AggregateMergeColumn("pat_cnt", "患者数", "METRIC", "COUNT_DISTINCT", "pat_cnt", null, null)
                ),
                null, finalCols, List.of("metric.icu.los@v1"), "fp_union", List.of(), List.of()
        );
        when(multiCompiler.compileMultiWarehouse(any(), any())).thenReturn(plan);

        // MIMIC: M 10人总时长 100h (avg 10h)
        when(dataQueryService.execute(eq("ds_mimic"), any())).thenReturn(new SqlQuery.Result(
                List.of(
                        new SqlQuery.Column("attr_patient_gender", "VARCHAR", CanonicalType.VARCHAR),
                        new SqlQuery.Column("__sum_avg_los", "DOUBLE", CanonicalType.DOUBLE),
                        new SqlQuery.Column("__cnt_avg_los", "BIGINT", CanonicalType.BIGINT),
                        new SqlQuery.Column("pat_cnt", "BIGINT", CanonicalType.BIGINT)
                ),
                List.of(List.of("M", "100.0", "10", "10")),
                1, false, 12L
        ));
        // eICU: M 90人总时长 1800h (avg 20h) -> 精确联合平均应为 1900/100 = 19.0（而非 (10+20)/2 = 15.0）
        when(dataQueryService.execute(eq("ds_eicu"), any())).thenReturn(new SqlQuery.Result(
                List.of(
                        new SqlQuery.Column("attr_patient_gender", "VARCHAR", CanonicalType.VARCHAR),
                        new SqlQuery.Column("__sum_avg_los", "DOUBLE", CanonicalType.DOUBLE),
                        new SqlQuery.Column("__cnt_avg_los", "BIGINT", CanonicalType.BIGINT),
                        new SqlQuery.Column("pat_cnt", "BIGINT", CanonicalType.BIGINT)
                ),
                List.of(List.of("M", "1800.0", "90", "90")),
                1, false, 15L
        ));

        SemanticQuerySpec spec = new SemanticQuerySpec(null, List.of("metric.icu.los"),
                List.of("attr.patient.gender"), List.of(), null, List.of(), 100);
        EngineAskResult res = engine.ask("对比 MIMIC 和 eICU 患者平均ICU时长", null, spec);

        assertThat(res.state()).isEqualTo("SUCCESS");
        assertThat(res.executionMode()).isEqualTo("COHORT_UNION");
        assertThat(res.warehousesUsed()).containsExactly("MIMIC-IV", "eICU-CRD");
        assertThat(res.warehouseSqls()).hasSize(2);
        assertThat(res.rows()).hasSize(1);
        assertThat(res.rows().get(0).get("avg_los")).isEqualTo(19.0);
        assertThat(res.rows().get(0).get("pat_cnt")).isEqualTo(100L);
    }

    @Test
    @DisplayName("ENTITY_FEDERATED_JOIN 端到端：通过真实嵌入式 DuckDB 完成 HIS ⋈ LIS 任意跨仓 JOIN")
    void federatedJoinExecutesRealDuckDbJoinAcrossHisAndLis() {
        WorkspaceWarehouse wh = new WorkspaceWarehouse();
        wh.setId("wh_his");
        when(warehouseService.listActiveWarehousesInternal("ws_clinical")).thenReturn(List.of(wh));

        FederatedJoinPlan fedPlan = new FederatedJoinPlan(
                "DUCKDB",
                null,
                List.of(
                        new FederatedSourceExtract("wh_src_0_his", "wh_his", "his", "HIS", "ds_his",
                                "POSTGRESQL", "cd_his", "__fed_join_key", "SELECT empi_id AS __fed_join_key, gender FROM patients"),
                        new FederatedSourceExtract("wh_src_1_lis", "wh_lis", "lis", "LIS", "ds_lis",
                                "MYSQL", "cd_lis", "__fed_join_key", "SELECT empi_no AS __fed_join_key, lactate FROM labs")
                ),
                "SELECT s0.gender, AVG(s1.lactate) AS avg_lactate FROM \"wh_src_0_his\" s0 INNER JOIN \"wh_src_1_lis\" s1 ON s0.__fed_join_key = s1.__fed_join_key GROUP BY s0.gender ORDER BY s0.gender ASC"
        );
        MultiWarehousePlan plan = new MultiWarehousePlan(
                ExecutionMode.ENTITY_FEDERATED_JOIN,
                "DUCKDB",
                List.of(),
                List.of(),
                fedPlan,
                List.of(
                        new CompiledColumn("性别", "gender", "DIMENSION"),
                        new CompiledColumn("平均乳酸", "avg_lactate", "METRIC")
                ),
                List.of("metric.lab.lactate@v1"),
                "fp_duck",
                List.of(),
                List.of()
        );
        when(multiCompiler.compileMultiWarehouse(any(), any())).thenReturn(plan);

        when(dataQueryService.execute(eq("ds_his"), any())).thenReturn(new SqlQuery.Result(
                List.of(
                        new SqlQuery.Column("__fed_join_key", "VARCHAR", CanonicalType.VARCHAR),
                        new SqlQuery.Column("gender", "VARCHAR", CanonicalType.VARCHAR)
                ),
                List.of(List.of("P1", "F"), List.of("P2", "M")),
                2, false, 5L
        ));
        when(dataQueryService.execute(eq("ds_lis"), any())).thenReturn(new SqlQuery.Result(
                List.of(
                        new SqlQuery.Column("__fed_join_key", "VARCHAR", CanonicalType.VARCHAR),
                        new SqlQuery.Column("lactate", "DOUBLE", CanonicalType.DOUBLE)
                ),
                List.of(List.of("P1", "1.8"), List.of("P2", "3.2"), List.of("P2", "4.8")),
                3, false, 6L
        ));

        SemanticQuerySpec spec = new SemanticQuerySpec(null, List.of("metric.lab.lactate"),
                List.of("attr.patient.gender"), List.of(), null, List.of(), 100);
        EngineAskResult res = engine.ask("按性别统计患者平均乳酸", null, spec);

        assertThat(res.state()).isEqualTo("SUCCESS");
        assertThat(res.executionMode()).isEqualTo("ENTITY_FEDERATED_JOIN");
        assertThat(res.federationEngine()).isEqualTo("DUCKDB");
        assertThat(res.warehousesUsed()).containsExactly("HIS", "LIS");
        assertThat(res.rows()).hasSize(2);
        assertThat(res.rows().get(0).get("gender")).isEqualTo("F");
        assertThat(((Number) res.rows().get(0).get("avg_lactate")).doubleValue()).isEqualTo(1.8);
        assertThat(res.rows().get(1).get("gender")).isEqualTo("M");
        assertThat(((Number) res.rows().get(1).get("avg_lactate")).doubleValue()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("C8 诚实边界：COHORT_UNION 任一仓库失败时拒绝拼装不完整结果，降级返回 COMPILED_ONLY")
    void cohortUnionRefusesPartialResultWhenOneWarehouseFails() {
        WorkspaceWarehouse wh = new WorkspaceWarehouse();
        wh.setId("wh_mimic");
        when(warehouseService.listActiveWarehousesInternal("ws_clinical")).thenReturn(List.of(wh));

        List<CompiledColumn> cols = List.of(new CompiledColumn("患者数", "pat_cnt", "METRIC"));
        MultiWarehousePlan plan = new MultiWarehousePlan(
                ExecutionMode.COHORT_UNION,
                "TWO_STAGE_DETERMINISTIC",
                List.of(
                        new WarehouseSubQuery("wh_mimic", "mimic_iv", "MIMIC-IV", "ds_mimic", "MIMIC_IV",
                                "POSTGRESQL", "mimic:", "cd_m", List.of("cd_m"), "SELECT 10 AS pat_cnt", cols),
                        new WarehouseSubQuery("wh_eicu", "eicu_crd", "eICU-CRD", "ds_eicu", "EICU_CRD",
                                "POSTGRESQL", "eicu:", "cd_e", List.of("cd_e"), "SELECT 20 AS pat_cnt", cols)
                ),
                List.of(new AggregateMergeColumn("pat_cnt", "患者数", "METRIC", "COUNT_DISTINCT", "pat_cnt", null, null)),
                null, cols, List.of(), "fp_partial", List.of(), List.of()
        );
        when(multiCompiler.compileMultiWarehouse(any(), any())).thenReturn(plan);
        when(dataQueryService.execute(eq("ds_mimic"), any())).thenReturn(new SqlQuery.Result(
                List.of(new SqlQuery.Column("pat_cnt", "BIGINT", CanonicalType.BIGINT)),
                List.of(List.of("10")), 1, false, 5L
        ));
        when(dataQueryService.execute(eq("ds_eicu"), any())).thenThrow(new RuntimeException("eICU connection refused"));

        SemanticQuerySpec spec = new SemanticQuerySpec(null, List.of("metric.patient.count"),
                List.of(), List.of(), null, List.of(), 100);
        EngineAskResult res = engine.ask("统计全部患者数", null, spec);

        assertThat(res.state()).isEqualTo("COMPILED_ONLY");
        assertThat(res.rows()).isEmpty();
        assertThat(res.summary()).contains("eICU-CRD");
        assertThat(res.warehouseSqls()).hasSize(2);
    }
}
