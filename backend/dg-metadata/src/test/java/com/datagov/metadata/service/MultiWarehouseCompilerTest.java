package com.datagov.metadata.service;

import com.datagov.metadata.entity.CatalogAssetEntities.CatalogColumn;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.SemanticEntities.LogicalTable;
import com.datagov.metadata.entity.SemanticEntities.SemanticMapping;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.entity.SemanticEntities.WorkspaceWarehouse;
import com.datagov.metadata.mapper.CatalogColumnMapper;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.service.MultiWarehouseQueryCompiler.ExecutionMode;
import com.datagov.metadata.service.MultiWarehouseQueryCompiler.MultiWarehousePlan;
import com.datagov.metadata.service.SemanticQueryCompiler.CompileContext;
import com.datagov.metadata.service.SemanticQueryCompiler.FilterClause;
import com.datagov.metadata.service.SemanticQueryCompiler.SemanticQuerySpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultiWarehouseCompilerTest {

    private SemanticOntologyService ontologyService;
    private SemanticWarehouseService warehouseService;
    private SemanticQueryCompiler singleCompiler;
    private CatalogDatasetMapper datasetMapper;
    private CatalogColumnMapper columnMapper;
    private MultiWarehouseQueryCompiler compiler;

    @BeforeEach
    void setUp() {
        ontologyService = mock(SemanticOntologyService.class);
        warehouseService = mock(SemanticWarehouseService.class);
        singleCompiler = mock(SemanticQueryCompiler.class);
        datasetMapper = mock(CatalogDatasetMapper.class);
        columnMapper = mock(CatalogColumnMapper.class);
        compiler = new MultiWarehouseQueryCompiler(
                ontologyService, warehouseService, singleCompiler,
                datasetMapper, columnMapper, new ObjectMapper()
        );
    }

    @Test
    @DisplayName("Mode B (COHORT_UNION) + Mode A 队列裁剪：MIMIC-IV + eICU-CRD 异构映射、值归一化、单位换算、命名空间防碰撞与两阶段 AVG 拆解")
    void compilesMimicAndEicuCohortUnionAndPrunesSingleCohort() {
        WorkspaceWarehouse mimic = wh("wh_mimic", "ds_mimic", "mimic_iv", "MIMIC-IV", "MIMIC_IV",
                "POSTGRESQL", "mimic:", "COHORT_UNION", "AUTO", null, 10);
        WorkspaceWarehouse eicu = wh("wh_eicu", "ds_eicu", "eicu_crd", "eICU-CRD", "EICU_CRD",
                "POSTGRESQL", "eicu:", "COHORT_UNION", "AUTO", null, 20);
        when(warehouseService.listActiveWarehousesInternal("ws_1")).thenReturn(List.of(mimic, eicu));

        LogicalTable ltMimic = lt("lt_mimic", "wh_mimic", "cd_mimic", "mimiciv_icu.icustays", 500);
        LogicalTable ltMimicSummary = lt("lt_mimic_agg", "wh_mimic", "cd_mimic_agg", "mimiciv_icu.icustays_summary", 50);
        LogicalTable ltEicu = lt("lt_eicu", "wh_eicu", "cd_eicu", "eicu.patient", 500);
        when(warehouseService.listActiveLogicalTablesInternal("ws_1")).thenReturn(List.of(ltMimic, ltMimicSummary, ltEicu));

        SemanticObject gender = obj("obj_gender", "GLOBAL", "attr.patient.gender", "患者性别", "ATTRIBUTE", null, "{}");
        SemanticObject avgLos = obj("obj_los", "GLOBAL", "metric.icu.avg_los_hours", "平均ICU时长", "METRIC",
                "STD-ICU-LOS", "{\"aggregation\":\"AVG\",\"unit\":\"小时\"}");
        SemanticObject patCnt = obj("obj_cnt", "GLOBAL", "metric.patient.count", "患者数", "METRIC",
                "STD-PAT-CNT", "{\"aggregation\":\"COUNT_DISTINCT\",\"unit\":\"人\"}");
        when(ontologyService.listPublishedObjectsInternal("ws_1")).thenReturn(List.of(gender, avgLos, patCnt));

        // 异构映射：MIMIC 有两个逻辑表（验证 logicalSize=50 汇总表优先于 500 明细表），eICU 有性别值归一化 + 分钟转小时
        List<SemanticMapping> mappings = List.of(
                map("m1", "obj_gender", "CODE", "cd_mimic", "col_m_g", "wh_mimic", "lt_mimic", null, null),
                map("m2", "obj_gender", "CODE", "cd_mimic_agg", "col_m_g", "wh_mimic", "lt_mimic_agg", null, null),
                map("m3", "obj_gender", "CODE", "cd_eicu", "col_e_g", "wh_eicu", "lt_eicu", null,
                        "{\"Male\":\"M\",\"Female\":\"F\"}"),
                map("m4", "obj_los", "MEASURE", "cd_mimic", null, "wh_mimic", "lt_mimic", "los * 24.0", null),
                map("m5", "obj_los", "MEASURE", "cd_mimic_agg", null, "wh_mimic", "lt_mimic_agg", "los * 24.0", null),
                map("m6", "obj_los", "MEASURE", "cd_eicu", null, "wh_eicu", "lt_eicu", "unitdischargeoffset / 60.0", null),
                map("m7", "obj_cnt", "MEASURE", "cd_mimic_agg", "col_m_id", "wh_mimic", "lt_mimic_agg", null, null),
                map("m8", "obj_cnt", "MEASURE", "cd_eicu", "col_e_id", "wh_eicu", "lt_eicu", null, null)
        );
        when(ontologyService.listWorkspaceMappingsInternal("ws_1")).thenReturn(mappings);
        when(datasetMapper.selectList(any())).thenReturn(List.of(
                ds("cd_mimic", "mimiciv_icu.icustays"),
                ds("cd_mimic_agg", "mimiciv_icu.icustays_summary"),
                ds("cd_eicu", "eicu.patient")
        ));
        when(columnMapper.selectList(any())).thenReturn(List.of(
                col("col_m_g", "gender"),
                col("col_e_g", "gender"),
                col("col_m_id", "subject_id"),
                col("col_e_id", "uniquepid")
        ));

        // 1) 全量联合查询 -> COHORT_UNION
        MultiWarehousePlan unionPlan = compiler.compileMultiWarehouse(
                new SemanticQuerySpec(null,
                        List.of("metric.icu.avg_los_hours", "metric.patient.count"),
                        List.of("attr.system.cohort", "attr.patient.gender"),
                        List.of(), null, List.of(), 100),
                new CompileContext("ws_1", null, "POSTGRESQL", 200)
        );

        assertThat(unionPlan.mode()).isEqualTo(ExecutionMode.COHORT_UNION);
        assertThat(unionPlan.subQueries()).hasSize(2);
        // MIMIC 命中 logicalSize=50 的汇总表 + mimic: 命名空间 + AVG 两阶段拆分
        String mimicSql = unionPlan.subQueries().get(0).sql();
        assertThat(mimicSql).contains("mimiciv_icu.icustays_summary");
        assertThat(mimicSql).contains("SUM(los * 24.0) AS __sum_metric_icu_avg_los_hours");
        assertThat(mimicSql).contains("COUNT(los * 24.0) AS __cnt_metric_icu_avg_los_hours");
        assertThat(mimicSql).contains("'mimic:' || CAST(subject_id AS VARCHAR)");
        // eICU 命中值归一化 CASE WHEN + 分钟转小时 + eicu: 命名空间
        String eicuSql = unionPlan.subQueries().get(1).sql();
        assertThat(eicuSql).contains("CASE gender WHEN 'Male' THEN 'M' WHEN 'Female' THEN 'F' ELSE gender END");
        assertThat(eicuSql).contains("SUM(unitdischargeoffset / 60.0) AS __sum_metric_icu_avg_los_hours");
        assertThat(eicuSql).contains("'eicu:' || CAST(uniquepid AS VARCHAR)");
        assertThat(unionPlan.mergeColumns()).anyMatch(c -> "AVG".equals(c.aggKind()) && "__sum_metric_icu_avg_los_hours".equals(c.sumCol()));

        // 2) 队列筛选裁剪 cohort=MIMIC_IV -> 自动退化为 SINGLE_ROUTE，仅生成 1 条 SQL
        MultiWarehousePlan singlePlan = compiler.compileMultiWarehouse(
                new SemanticQuerySpec(null,
                        List.of("metric.icu.avg_los_hours"),
                        List.of("attr.patient.gender"),
                        List.of(new FilterClause("attr.system.cohort", "=", List.of("MIMIC_IV"))),
                        null, List.of(), 100),
                new CompileContext("ws_1", null, "POSTGRESQL", 200)
        );
        assertThat(singlePlan.mode()).isEqualTo(ExecutionMode.SINGLE_ROUTE);
        assertThat(singlePlan.subQueries()).hasSize(1);
        assertThat(singlePlan.subQueries().get(0).warehouseCode()).isEqualTo("mimic_iv");
    }

    @Test
    @DisplayName("Mode C (ENTITY_FEDERATED_JOIN)：HIS + LIS 共享 EMPI 支持 DuckDB 嵌入式提取联结与 StarRocks External Catalog 直推")
    void compilesFederatedJoinForDuckDbAndStarRocks() {
        SemanticObject patient = obj("obj_pat", "GLOBAL", "entity.patient", "患者实体", "ENTITY", null, "{}");
        SemanticObject gender = obj("obj_gender", "GLOBAL", "attr.patient.gender", "患者性别", "ATTRIBUTE", null, "{}");
        SemanticObject lactate = obj("obj_lac", "WORKSPACE", "metric.lab.avg_lactate", "平均乳酸", "METRIC",
                "STD-LAB-LAC", "{\"aggregation\":\"AVG\",\"unit\":\"mmol/L\"}");
        when(ontologyService.listPublishedObjectsInternal("ws_hosp")).thenReturn(List.of(patient, gender, lactate));

        List<SemanticMapping> mappings = List.of(
                map("m1", "obj_pat", "ID", "cd_his", "col_his_empi", "wh_his", "lt_his", null, null),
                map("m2", "obj_pat", "ID", "cd_lis", "col_lis_empi", "wh_lis", "lt_lis", null, null),
                map("m3", "obj_gender", "CODE", "cd_his", "col_his_gender", "wh_his", "lt_his", null, null),
                map("m4", "obj_lac", "MEASURE", "cd_lis", "col_lis_lac", "wh_lis", "lt_lis", null, null)
        );
        when(ontologyService.listWorkspaceMappingsInternal("ws_hosp")).thenReturn(mappings);
        when(datasetMapper.selectList(any())).thenReturn(List.of(
                ds("cd_his", "public.patients"),
                ds("cd_lis", "lab.results")
        ));
        when(columnMapper.selectList(any())).thenReturn(List.of(
                col("col_his_empi", "empi_id"),
                col("col_lis_empi", "empi_no"),
                col("col_his_gender", "gender"),
                col("col_lis_lac", "lactate")
        ));

        // 场景 A：无 StarRocks Catalog -> 自动走 DuckDB 嵌入式联邦
        WorkspaceWarehouse hisDuck = wh("wh_his", "ds_his", "his_pg", "HIS", "HIS",
                "POSTGRESQL", null, "ENTITY_FEDERATED_JOIN", "DUCKDB", null, 10);
        WorkspaceWarehouse lisDuck = wh("wh_lis", "ds_lis", "lis_mysql", "LIS", "LIS",
                "MYSQL", null, "ENTITY_FEDERATED_JOIN", "DUCKDB", null, 20);
        when(warehouseService.listActiveWarehousesInternal("ws_hosp")).thenReturn(List.of(hisDuck, lisDuck));
        when(warehouseService.listActiveLogicalTablesInternal("ws_hosp")).thenReturn(List.of(
                lt("lt_his", "wh_his", "cd_his", "lt_his_patients", 100),
                lt("lt_lis", "wh_lis", "cd_lis", "lt_lis_results", 200)
        ));

        MultiWarehousePlan duckPlan = compiler.compileMultiWarehouse(
                new SemanticQuerySpec(null, List.of("metric.lab.avg_lactate"), List.of("attr.patient.gender"),
                        List.of(), null, List.of(), 100),
                new CompileContext("ws_hosp", Set.of("cd_his", "cd_lis"), "POSTGRESQL", 200)
        );
        assertThat(duckPlan.mode()).isEqualTo(ExecutionMode.ENTITY_FEDERATED_JOIN);
        assertThat(duckPlan.federationEngine()).isEqualTo("DUCKDB");
        assertThat(duckPlan.federatedJoinPlan().extracts()).hasSize(2);
        assertThat(duckPlan.federatedJoinPlan().federatedSql()).contains("INNER JOIN");
        assertThat(duckPlan.federatedJoinPlan().federatedSql()).contains("AVG(s1.metric_lab_avg_lactate)");

        // 场景 B：配置 StarRocks Hub + External Catalog -> 自动直推三段式跨 Catalog SQL
        WorkspaceWarehouse srHub = wh("wh_sr", "ds_sr", "sr_hub", "StarRocks Hub", "SR",
                "STARROCKS", null, "ENTITY_FEDERATED_JOIN", "STARROCKS", "default_catalog", 5);
        WorkspaceWarehouse hisSr = wh("wh_his", "ds_his", "his_pg", "HIS", "HIS",
                "POSTGRESQL", null, "ENTITY_FEDERATED_JOIN", "AUTO", "pg_his_cat", 10);
        WorkspaceWarehouse lisSr = wh("wh_lis", "ds_lis", "lis_mysql", "LIS", "LIS",
                "MYSQL", null, "ENTITY_FEDERATED_JOIN", "AUTO", "mysql_lis_cat", 20);
        when(warehouseService.listActiveWarehousesInternal("ws_hosp")).thenReturn(List.of(srHub, hisSr, lisSr));

        MultiWarehousePlan srPlan = compiler.compileMultiWarehouse(
                new SemanticQuerySpec(null, List.of("metric.lab.avg_lactate"), List.of("attr.patient.gender"),
                        List.of(), null, List.of(), 100),
                new CompileContext("ws_hosp", Set.of("cd_his", "cd_lis"), "STARROCKS", 200)
        );
        assertThat(srPlan.mode()).isEqualTo(ExecutionMode.ENTITY_FEDERATED_JOIN);
        assertThat(srPlan.federationEngine()).isEqualTo("STARROCKS");
        assertThat(srPlan.federatedJoinPlan().starRocksDatasourceId()).isEqualTo("ds_sr");
        assertThat(srPlan.federatedJoinPlan().federatedSql())
                .contains("pg_his_cat.public.patients")
                .contains("mysql_lis_cat.lab.results")
                .contains("INNER JOIN");
    }

    private WorkspaceWarehouse wh(String id, String dsId, String code, String name, String cohort,
                                  String dialect, String prefix, String mode, String fedEngine,
                                  String srCatalog, int prio) {
        WorkspaceWarehouse w = new WorkspaceWarehouse();
        w.setId(id);
        w.setWorkspaceId("ws_1");
        w.setDatasourceId(dsId);
        w.setCode(code);
        w.setName(name);
        w.setCohortCode(cohort);
        w.setDialect(dialect);
        w.setIdNamespacePrefix(prefix);
        w.setCombineMode(mode);
        w.setFederationEngine(fedEngine);
        w.setStarrocksCatalogName(srCatalog);
        w.setPriority(prio);
        w.setStatus("ACTIVE");
        return w;
    }

    private LogicalTable lt(String id, String whId, String dsId, String name, int size) {
        LogicalTable t = new LogicalTable();
        t.setId(id);
        t.setWarehouseId(whId);
        t.setCatalogDatasetId(dsId);
        t.setLogicalName(name);
        t.setLogicalSize(size);
        t.setStatus("ACTIVE");
        return t;
    }

    private SemanticObject obj(String id, String scope, String code, String name, String type,
                               String stdRef, String specJson) {
        SemanticObject o = new SemanticObject();
        o.setId(id);
        o.setScope(scope);
        o.setCode(code);
        o.setName(name);
        o.setObjectType(type);
        o.setStandardRef(stdRef);
        o.setSpecJson(specJson);
        o.setStatus("PUBLISHED");
        o.setVersionNo(1);
        return o;
    }

    private SemanticMapping map(String id, String objId, String role, String dsId, String colId,
                                String whId, String ltId, String expr, String normJson) {
        SemanticMapping m = new SemanticMapping();
        m.setId(id);
        m.setObjectId(objId);
        m.setMappingRole(role);
        m.setCatalogDatasetId(dsId);
        m.setCatalogColumnId(colId);
        m.setWarehouseId(whId);
        m.setLogicalTableId(ltId);
        m.setExpressionSql(expr);
        m.setValueNormalizationJson(normJson);
        m.setPriority(100);
        return m;
    }

    private CatalogDataset ds(String id, String fqName) {
        CatalogDataset d = new CatalogDataset();
        d.setId(id);
        d.setQualifiedName(fqName);
        d.setName(fqName);
        return d;
    }

    private CatalogColumn col(String id, String name) {
        CatalogColumn c = new CatalogColumn();
        c.setId(id);
        c.setName(name);
        return c;
    }
}
