package com.datagov.metadata.service;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogColumn;
import com.datagov.metadata.entity.CatalogAssetEntities.CatalogDataset;
import com.datagov.metadata.entity.SemanticEntities.SemanticLink;
import com.datagov.metadata.entity.SemanticEntities.SemanticMapping;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.entity.SemanticEntities.SemanticValueDict;
import com.datagov.metadata.mapper.CatalogColumnMapper;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.SemanticObjectMapper;
import com.datagov.metadata.mapper.SemanticValueDictMapper;
import com.datagov.metadata.service.SemanticQueryCompiler.AtModifier;
import com.datagov.metadata.service.SemanticQueryCompiler.CompileContext;
import com.datagov.metadata.service.SemanticQueryCompiler.CompiledSemanticQuery;
import com.datagov.metadata.service.SemanticQueryCompiler.FilterClause;
import com.datagov.metadata.service.SemanticQueryCompiler.OrderClause;
import com.datagov.metadata.service.SemanticQueryCompiler.SemanticQuerySpec;
import com.datagov.metadata.service.SemanticQueryCompiler.TimeRange;
import com.datagov.metadata.service.SemanticValueLinkingService.NormalizedTimeRange;
import com.datagov.metadata.service.SemanticValueLinkingService.ValueMatch;
import com.datagov.metadata.service.gate.CompileReject;
import com.datagov.metadata.service.gate.CompileRejectException;
import com.datagov.metadata.service.gate.SemanticCompileErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("确定性语义 SQL 编译器与值对齐")
class SemanticQueryCompilerTest {

    private static final String WS = "ws_alpha";

    private SemanticOntologyService ontologyService;
    private CatalogDatasetMapper datasetMapper;
    private CatalogColumnMapper columnMapper;
    private SemanticQueryCompiler compiler;

    @BeforeEach
    void setUp() {
        ontologyService = mock(SemanticOntologyService.class);
        datasetMapper = mock(CatalogDatasetMapper.class);
        columnMapper = mock(CatalogColumnMapper.class);
        compiler = new SemanticQueryCompiler(ontologyService, datasetMapper, columnMapper, new ObjectMapper());
    }

    @Test
    @DisplayName("单表聚合 + 自动注入 defaultFilters 与知识图谱 EXCLUDES 边 + 审计指纹")
    void singleTableWithDefaultFiltersAndKgExcludes() {
        SemanticObject gmv = obj("m_gmv", "metric.order.gmv", "有效GMV", "METRIC", "STD-GMV",
                "{\"metricKind\":\"BASE\",\"aggregation\":\"SUM\",\"defaultFilters\":[{\"objectCode\":\"attr.order.status\",\"op\":\"NOT_IN\",\"values\":[\"CANCELED\"]}]}");
        SemanticObject region = obj("a_reg", "attr.org.region", "区域", "ATTRIBUTE", null, "{}");
        SemanticObject status = obj("a_st", "attr.order.status", "状态", "ATTRIBUTE", null, "{}");
        SemanticObject bizDate = obj("d_dt", "dim.time.biz_date", "业务日期", "DIMENSION", null, "{}");
        SemanticObject testOrder = obj("c_test", "concept.order.test", "内部测试单", "CONCEPT", null, "{}");

        SemanticMapping mGmv = map("mp1", "m_gmv", "ds_order", "col_amt", "MEASURE", false, 10);
        SemanticMapping mReg = map("mp2", "a_reg", "ds_order", "col_region", "ID", false, 10);
        SemanticMapping mSt = map("mp3", "a_st", "ds_order", "col_status", "CODE", false, 10);
        SemanticMapping mDt = map("mp4", "d_dt", "ds_order", "col_dt", "TIME_KEY", false, 10);

        SemanticLink excludes = new SemanticLink();
        excludes.setId("l_ex");
        excludes.setFromObjectId("c_test");
        excludes.setToObjectId("m_gmv");
        excludes.setEdgeCategory("CAUSAL");
        excludes.setLinkType("EXCLUDES");
        excludes.setRationale("剔除测试账号订单");
        excludes.setJoinSpecJson("[{\"objectCode\":\"attr.order.status\",\"op\":\"!=\",\"values\":[\"TEST\"]}]");
        excludes.setStatus("PUBLISHED");

        when(ontologyService.listPublishedObjectsInternal(WS))
                .thenReturn(List.of(gmv, region, status, bizDate, testOrder));
        when(ontologyService.listWorkspaceMappingsInternal(WS))
                .thenReturn(List.of(mGmv, mReg, mSt, mDt));
        when(ontologyService.listPublishedLinksInternal(WS)).thenReturn(List.of(excludes));
        when(datasetMapper.selectList(any())).thenReturn(List.of(ds("ds_order", "dwd_order")));
        when(columnMapper.selectList(any())).thenReturn(List.of(
                col("col_amt", "ds_order", "amount"),
                col("col_region", "ds_order", "region"),
                col("col_status", "ds_order", "status"),
                col("col_dt", "ds_order", "biz_date")));

        SemanticQuerySpec spec = new SemanticQuerySpec(
                "view_order",
                List.of("metric.order.gmv"),
                List.of("attr.org.region"),
                List.of(new FilterClause("attr.org.region", "=", List.of("EAST"))),
                new TimeRange("dim.time.biz_date", "2026-10-01", "2026-10-31"),
                List.of(new OrderClause("metric.order.gmv", "DESC")),
                50);

        CompiledSemanticQuery res = compiler.compile(spec, new CompileContext(WS, Set.of("ds_order"), "STARROCKS", 200));

        assertThat(res.fanoutCteUsed()).isFalse();
        assertThat(res.sql())
                .contains("SELECT t0.region AS attr_org_region, SUM(t0.amount) AS metric_order_gmv FROM dwd_order t0")
                .contains("t0.region = 'EAST'")
                .contains("t0.status NOT IN ('CANCELED')")
                .contains("t0.status != 'TEST'")
                .contains("t0.biz_date >= '2026-10-01'")
                .contains("GROUP BY t0.region")
                .contains("ORDER BY metric_order_gmv DESC")
                .contains("LIMIT 50");
        assertThat(res.appliedExclusions()).hasSize(2);
        assertThat(res.auditFingerprint()).hasSize(64);
        assertThat(res.reproducibilityFingerprint()).isNotNull();
        assertThat(res.reproducibilityFingerprint().schemaVersion()).isEqualTo(1);
        assertThat(res.reproducibilityFingerprint().sqlDigest()).hasSize(64);
        assertThat(res.reproducibilityFingerprint().gateDigest()).isNotBlank();
        assertThat(res.reproducibilityFingerprint().truncation()).containsKeys("truncated", "maxRows");
        res.reproducibilityFingerprint().requireComplete();
    }

    @Test
    @DisplayName("星型关联：沿 STRUCTURAL RELATES_TO 自动生成确定性 JOIN")
    void starJoinViaStructuralLink() {
        SemanticObject gmv = obj("m_gmv", "metric.order.gmv", "有效GMV", "METRIC", "STD-GMV",
                "{\"metricKind\":\"BASE\",\"aggregation\":\"SUM\"}");
        SemanticObject deptName = obj("a_dept", "attr.org.dept_name", "科室名称", "ATTRIBUTE", null, "{}");

        SemanticMapping mGmv = map("mp1", "m_gmv", "ds_order", "col_amt", "MEASURE", false, 10);
        SemanticMapping mDept = map("mp2", "a_dept", "ds_dim_dept", "col_dept_name", "DESC", true, 10);

        SemanticLink joinLink = new SemanticLink();
        joinLink.setId("l_j1");
        joinLink.setFromObjectId("m_gmv");
        joinLink.setToObjectId("a_dept");
        joinLink.setEdgeCategory("STRUCTURAL");
        joinLink.setLinkType("RELATES_TO");
        joinLink.setJoinSpecJson("[{\"leftColumn\":\"dept_id\",\"rightColumn\":\"id\",\"joinType\":\"LEFT\"}]");
        joinLink.setStatus("PUBLISHED");

        when(ontologyService.listPublishedObjectsInternal(WS)).thenReturn(List.of(gmv, deptName));
        when(ontologyService.listWorkspaceMappingsInternal(WS)).thenReturn(List.of(mGmv, mDept));
        when(ontologyService.listPublishedLinksInternal(WS)).thenReturn(List.of(joinLink));
        when(datasetMapper.selectList(any())).thenReturn(List.of(
                ds("ds_order", "dwd_order"), ds("ds_dim_dept", "dim_dept")));
        when(columnMapper.selectList(any())).thenReturn(List.of(
                col("col_amt", "ds_order", "amount"),
                col("col_dept_name", "ds_dim_dept", "dept_name")));

        CompiledSemanticQuery res = compiler.compile(
                new SemanticQuerySpec(null, List.of("metric.order.gmv"), List.of("attr.org.dept_name"),
                        List.of(), null, List.of(), 100),
                new CompileContext(WS, Set.of("ds_order", "ds_dim_dept"), "POSTGRESQL", 200));

        assertThat(res.sql())
                .contains("LEFT JOIN dim_dept t1 ON t0.dept_id = t1.id")
                .contains("GROUP BY t1.dept_name");
    }

    @Test
    @DisplayName("多 STRUCTURAL 路径 → MULTI_FACT_AMBIGUOUS_PATH；USING 消歧后可编译")
    void ambiguousStructuralPathRequiresUsing() {
        SemanticObject gmv = obj("m_gmv", "metric.order.gmv", "有效GMV", "METRIC", "STD-GMV",
                "{\"metricKind\":\"BASE\",\"aggregation\":\"SUM\"}");
        SemanticObject deptName = obj("a_dept", "attr.org.dept_name", "科室名称", "ATTRIBUTE", null, "{}");
        SemanticObject bridgeOrg = obj("e_org", "entity.org", "组织实体", "ENTITY", null, "{}");
        SemanticObject bridgeDept = obj("e_dept", "entity.dept", "科室实体", "ENTITY", null, "{}");

        SemanticMapping mGmv = map("mp1", "m_gmv", "ds_order", "col_amt", "MEASURE", false, 10);
        SemanticMapping mDept = map("mp2", "a_dept", "ds_dim_dept", "col_dept_name", "DESC", true, 10);
        SemanticMapping mBridgeOrgOrder = map("mp3", "e_org", "ds_order", "col_org", "ID", false, 10);
        SemanticMapping mBridgeOrgDim = map("mp4", "e_org", "ds_dim_dept", "col_org_d", "ID", true, 10);
        SemanticMapping mBridgeDeptOrder = map("mp5", "e_dept", "ds_order", "col_dept", "ID", false, 10);
        SemanticMapping mBridgeDeptDim = map("mp6", "e_dept", "ds_dim_dept", "col_dept_d", "ID", true, 10);

        SemanticLink pathOrg = new SemanticLink();
        pathOrg.setId("l_org");
        pathOrg.setFromObjectId("e_org");
        pathOrg.setToObjectId("e_org");
        pathOrg.setEdgeCategory("STRUCTURAL");
        pathOrg.setLinkType("RELATES_TO");
        pathOrg.setJoinSpecJson("[{\"leftColumn\":\"org_id\",\"rightColumn\":\"id\",\"joinType\":\"LEFT\"}]");
        pathOrg.setStatus("PUBLISHED");

        SemanticLink pathDept = new SemanticLink();
        pathDept.setId("l_dept");
        pathDept.setFromObjectId("e_dept");
        pathDept.setToObjectId("e_dept");
        pathDept.setEdgeCategory("STRUCTURAL");
        pathDept.setLinkType("RELATES_TO");
        pathDept.setJoinSpecJson("[{\"leftColumn\":\"dept_id\",\"rightColumn\":\"id\",\"joinType\":\"LEFT\"}]");
        pathDept.setStatus("PUBLISHED");

        when(ontologyService.listPublishedObjectsInternal(WS))
                .thenReturn(List.of(gmv, deptName, bridgeOrg, bridgeDept));
        when(ontologyService.listWorkspaceMappingsInternal(WS))
                .thenReturn(List.of(mGmv, mDept, mBridgeOrgOrder, mBridgeOrgDim, mBridgeDeptOrder, mBridgeDeptDim));
        when(ontologyService.listPublishedLinksInternal(WS)).thenReturn(List.of(pathOrg, pathDept));
        when(datasetMapper.selectList(any())).thenReturn(List.of(
                ds("ds_order", "dwd_order"), ds("ds_dim_dept", "dim_dept")));
        when(columnMapper.selectList(any())).thenReturn(List.of(
                col("col_amt", "ds_order", "amount"),
                col("col_dept_name", "ds_dim_dept", "dept_name")));

        SemanticQuerySpec ambiguous = new SemanticQuerySpec(
                null, List.of("metric.order.gmv"), List.of("attr.org.dept_name"),
                List.of(), null, List.of(), 100, null, List.of());
        assertThatThrownBy(() -> compiler.compile(ambiguous,
                new CompileContext(WS, Set.of("ds_order", "ds_dim_dept"), "POSTGRESQL", 200)))
                .isInstanceOf(CompileRejectException.class)
                .satisfies(ex -> {
                    CompileReject reject = ((CompileRejectException) ex).reject();
                    assertThat(reject.errorCode()).isEqualTo(SemanticCompileErrorCode.MULTI_FACT_AMBIGUOUS_PATH.name());
                    assertThat(reject.allowed()).contains("entity.org", "entity.dept");
                });

        SemanticQuerySpec disambiguated = new SemanticQuerySpec(
                null, List.of("metric.order.gmv"), List.of("attr.org.dept_name"),
                List.of(), null, List.of(), 100, null,
                List.of(new AtModifier("USING", "entity.dept", null)));
        CompiledSemanticQuery ok = compiler.compile(disambiguated,
                new CompileContext(WS, Set.of("ds_order", "ds_dim_dept"), "POSTGRESQL", 200));
        assertThat(ok.sql()).contains("LEFT JOIN dim_dept t1 ON t0.dept_id = t1.id");
    }

    @Test
    @DisplayName("多事实表防扇出（Fan-out CTE）+ 衍生比率除零保护（NULLIF）")
    void multiFactFanoutCteAndSafeDerivedRatio() {
        SemanticObject readmit = obj("m_num", "metric.icu.readmit_cnt", "再入院数", "METRIC", "STD-1",
                "{\"metricKind\":\"BASE\",\"aggregation\":\"COUNT\"}");
        SemanticObject discharge = obj("m_den", "metric.icu.discharge_cnt", "出院人数", "METRIC", "STD-2",
                "{\"metricKind\":\"BASE\",\"aggregation\":\"COUNT\"}");
        SemanticObject rate = obj("m_rate", "metric.icu.readmit_rate", "30天再入院率", "METRIC", "STD-3",
                "{\"metricKind\":\"DERIVED\",\"aggregation\":\"RATIO\",\"numeratorObjectCode\":\"metric.icu.readmit_cnt\",\"denominatorObjectCode\":\"metric.icu.discharge_cnt\"}");
        SemanticObject dept = obj("a_dept", "attr.org.dept", "科室", "ATTRIBUTE", null, "{}");

        SemanticMapping mNum = map("mp1", "m_num", "ds_readmit", "c_id1", "MEASURE", false, 10);
        SemanticMapping mDen = map("mp2", "m_den", "ds_discharge", "c_id2", "MEASURE", false, 10);
        SemanticMapping mDept1 = map("mp3", "a_dept", "ds_readmit", "c_d1", "ID", false, 10);
        SemanticMapping mDept2 = map("mp4", "a_dept", "ds_discharge", "c_d2", "ID", false, 20);

        when(ontologyService.listPublishedObjectsInternal(WS)).thenReturn(List.of(readmit, discharge, rate, dept));
        when(ontologyService.listWorkspaceMappingsInternal(WS)).thenReturn(List.of(mNum, mDen, mDept1, mDept2));
        when(ontologyService.listPublishedLinksInternal(WS)).thenReturn(List.of());
        when(datasetMapper.selectList(any())).thenReturn(List.of(
                ds("ds_readmit", "dwd_readmit"), ds("ds_discharge", "dwd_discharge")));
        when(columnMapper.selectList(any())).thenReturn(List.of(
                col("c_id1", "ds_readmit", "encounter_id"),
                col("c_id2", "ds_discharge", "encounter_id"),
                col("c_d1", "ds_readmit", "dept_code"),
                col("c_d2", "ds_discharge", "dept_code")));

        CompiledSemanticQuery res = compiler.compile(
                new SemanticQuerySpec(null, List.of("metric.icu.readmit_rate"), List.of("attr.org.dept"),
                        List.of(), null, List.of(), 20),
                new CompileContext(WS, Set.of("ds_readmit", "ds_discharge"), "STARROCKS", 200));

        assertThat(res.fanoutCteUsed()).isTrue();
        assertThat(res.sql())
                .startsWith("WITH cte_fact_0 AS")
                .contains("cte_fact_1 AS")
                .contains("FULL OUTER JOIN cte_fact_1 ON cte_fact_0.attr_org_dept IS NOT DISTINCT FROM cte_fact_1.attr_org_dept")
                .contains("NULLIF(CAST(cte_fact_1.m_1 AS DOUBLE), 0)");
    }

    @Test
    @DisplayName("at_modifiers 不可关闭 EXCLUDES/RLS")
    void atModifiersCannotBypassSecurity() {
        SemanticObject gmv = obj("m_gmv", "metric.order.gmv", "有效GMV", "METRIC", "STD-GMV",
                "{\"metricKind\":\"BASE\",\"aggregation\":\"SUM\"}");
        SemanticMapping mGmv = map("mp1", "m_gmv", "ds_order", "col_amt", "MEASURE", false, 10);
        when(ontologyService.listPublishedObjectsInternal(WS)).thenReturn(List.of(gmv));
        when(ontologyService.listWorkspaceMappingsInternal(WS)).thenReturn(List.of(mGmv));
        when(ontologyService.listPublishedLinksInternal(WS)).thenReturn(List.of());

        assertThatThrownBy(() -> compiler.compile(
                new SemanticQuerySpec(null, List.of("metric.order.gmv"), List.of(),
                        List.of(), null, List.of(), 10, null,
                        List.of(new AtModifier("IGNORE_EXCLUDES", null, null))),
                new CompileContext(WS, Set.of("ds_order"), "POSTGRESQL", 200)))
                .isInstanceOf(CompileRejectException.class)
                .satisfies(ex -> assertThat(((CompileRejectException) ex).reject().errorCode())
                        .isEqualTo(SemanticCompileErrorCode.SECURITY_FIELD_FORBIDDEN.name()));
    }

    @Test
    @DisplayName("C4/C5 ACL 闸门：用户无权访问底层物理数据集时类型化拒绝（DATASET_ACL_DENIED via SemanticGate）")
    void forbiddenDatasetBlockedAtCompileTime() {
        SemanticObject gmv = obj("m_gmv", "metric.order.gmv", "有效GMV", "METRIC", "STD-GMV",
                "{\"metricKind\":\"BASE\",\"aggregation\":\"SUM\"}");
        SemanticMapping mGmv = map("mp1", "m_gmv", "ds_secret", "col_amt", "MEASURE", false, 10);

        when(ontologyService.listPublishedObjectsInternal(WS)).thenReturn(List.of(gmv));
        when(ontologyService.listWorkspaceMappingsInternal(WS)).thenReturn(List.of(mGmv));
        when(ontologyService.listPublishedLinksInternal(WS)).thenReturn(List.of());

        assertThatThrownBy(() -> compiler.compile(
                new SemanticQuerySpec(null, List.of("metric.order.gmv"), List.of(), List.of(), null, List.of(), 10),
                new CompileContext(WS, Set.of("ds_other"), "STARROCKS", 200)))
                .isInstanceOf(CompileRejectException.class)
                .satisfies(ex -> {
                    CompileRejectException cre = (CompileRejectException) ex;
                    assertThat(cre.errorCode()).isEqualTo(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED);
                    assertThat(cre.reject().errorCode())
                            .isEqualTo(SemanticCompileErrorCode.DATASET_ACL_DENIED.name());
                });
    }

    @Test
    @DisplayName("Value Linking 与确定性相对时间归一化")
    void valueLinkingAndRelativeTime() {
        SemanticValueDictMapper dictMapper = mock(SemanticValueDictMapper.class);
        SemanticObjectMapper objMapper = mock(SemanticObjectMapper.class);
        SemanticValueLinkingService linking = new SemanticValueLinkingService(dictMapper, objMapper, new ObjectMapper());

        SemanticObject region = obj("a_reg", "attr.org.region", "区域", "ATTRIBUTE", null, "{}");
        region.setMaxSensitivity("L1");
        when(objMapper.selectList(any())).thenReturn(List.of(region));

        SemanticValueDict entry = new SemanticValueDict();
        entry.setId("v1");
        entry.setWorkspaceId(WS);
        entry.setObjectId("a_reg");
        entry.setRawValue("EAST");
        entry.setDisplayLabel("华东");
        entry.setSynonymsJson("[\"华东区\",\"华东大区\"]");
        when(dictMapper.selectList(any())).thenReturn(List.of(entry));

        List<ValueMatch> matches = linking.matchValues(WS, "统计上月华东区的有效GMV");
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).rawValue()).isEqualTo("EAST");
        assertThat(matches.get(0).matchedPhrase()).isEqualTo("华东区");

        NormalizedTimeRange lastMonth = linking.normalizeRelativeTime("上月华东区销售额", LocalDate.of(2026, 11, 19));
        assertThat(lastMonth).isNotNull();
        assertThat(lastMonth.startDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(lastMonth.endDate()).isEqualTo(LocalDate.of(2026, 10, 31));

        NormalizedTimeRange recent30 = linking.normalizeRelativeTime("近30天趋势", LocalDate.of(2026, 11, 19));
        assertThat(recent30.startDate()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(recent30.endDate()).isEqualTo(LocalDate.of(2026, 11, 19));
    }

    private SemanticObject obj(String id, String code, String name, String type, String stdRef, String spec) {
        SemanticObject o = new SemanticObject();
        o.setId(id);
        o.setWorkspaceId(WS);
        o.setCode(code);
        o.setName(name);
        o.setObjectType(type);
        o.setStandardRef(stdRef);
        o.setSpecJson(spec);
        o.setAiContextJson("{}");
        o.setStatus("PUBLISHED");
        o.setVersionNo(1);
        o.setContentDigest("abcdef1234567890");
        return o;
    }

    private SemanticMapping map(String id, String objId, String dsId, String colId,
                                String role, boolean lookup, int priority) {
        SemanticMapping m = new SemanticMapping();
        m.setId(id);
        m.setWorkspaceId(WS);
        m.setObjectId(objId);
        m.setCatalogDatasetId(dsId);
        m.setCatalogColumnId(colId);
        m.setMappingRole(role);
        m.setIsLookupTable(lookup);
        m.setPriority(priority);
        m.setStatus("PUBLISHED");
        return m;
    }

    private CatalogDataset ds(String id, String qname) {
        CatalogDataset d = new CatalogDataset();
        d.setId(id);
        d.setWorkspaceId(WS);
        d.setName(qname);
        d.setQualifiedName(qname);
        return d;
    }

    private CatalogColumn col(String id, String dsId, String name) {
        CatalogColumn c = new CatalogColumn();
        c.setId(id);
        c.setDatasetId(dsId);
        c.setName(name);
        return c;
    }
}
