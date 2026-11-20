package com.datagov.metadata.service;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.entity.SemanticEntities.SemanticLink;
import com.datagov.metadata.entity.SemanticEntities.SemanticMapping;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.entity.SemanticEntities.SemanticObjectVersion;
import com.datagov.metadata.entity.SemanticEntities.SemanticValueDict;
import com.datagov.metadata.mapper.BusinessDomainMapper;
import com.datagov.metadata.mapper.CatalogColumnMapper;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.ColumnSensitivityMapper;
import com.datagov.metadata.mapper.SemanticLinkMapper;
import com.datagov.metadata.mapper.SemanticMappingMapper;
import com.datagov.metadata.mapper.SemanticObjectMapper;
import com.datagov.metadata.mapper.SemanticObjectVersionMapper;
import com.datagov.metadata.mapper.SemanticValueDictMapper;
import com.datagov.metadata.service.SemanticOntologyService.ImpactResult;
import com.datagov.metadata.service.SemanticOntologyService.ObjectRequest;
import com.datagov.metadata.service.SemanticOntologyService.ObjectView;
import com.datagov.metadata.service.SemanticOntologyService.SubgraphResult;
import com.datagov.metadata.service.SemanticOntologyService.SubgraphSearchRequest;
import com.datagov.metadata.service.SemanticOntologyService.ValueDictRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("语义本体内核与知识图谱服务")
class SemanticOntologyServiceTest {

    private static final String WS = "ws_alpha";

    private SemanticObjectMapper objectMapperDao;
    private SemanticMappingMapper mappingMapper;
    private SemanticLinkMapper linkMapper;
    private SemanticObjectVersionMapper versionMapper;
    private SemanticValueDictMapper valueDictMapper;
    private CatalogDatasetMapper datasetMapper;
    private CatalogColumnMapper columnMapper;
    private ColumnSensitivityMapper sensitivityMapper;
    private BusinessDomainMapper domainMapper;
    private SemanticOntologyService service;

    @BeforeEach
    void setUp() {
        objectMapperDao = mock(SemanticObjectMapper.class);
        mappingMapper = mock(SemanticMappingMapper.class);
        linkMapper = mock(SemanticLinkMapper.class);
        versionMapper = mock(SemanticObjectVersionMapper.class);
        valueDictMapper = mock(SemanticValueDictMapper.class);
        datasetMapper = mock(CatalogDatasetMapper.class);
        columnMapper = mock(CatalogColumnMapper.class);
        sensitivityMapper = mock(ColumnSensitivityMapper.class);
        domainMapper = mock(BusinessDomainMapper.class);

        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new SemanticOntologyService(
                objectMapperDao, mappingMapper, linkMapper, versionMapper, valueDictMapper,
                datasetMapper, columnMapper, sensitivityMapper, domainMapper, om);

        WorkspaceContext.set(new Caller("usr_1", "alice", WS, false, Set.of()));
        when(domainMapper.selectList(any())).thenReturn(List.of());
        when(mappingMapper.selectList(any())).thenReturn(List.of());
        when(linkMapper.selectList(any())).thenReturn(List.of());
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(sensitivityMapper.selectList(any())).thenReturn(List.of());
        when(datasetMapper.selectList(any())).thenReturn(List.of());
        when(columnMapper.selectList(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("C2 闸门：指标缺少 Standard 口径绑定时拒绝发布")
    void metricWithoutStandardRefCannotPublish() {
        SemanticObject metric = object("sem_m1", "metric.order.gmv", "有效GMV", "METRIC", "DRAFT", null, "L1");
        when(objectMapperDao.selectOne(any())).thenReturn(metric);

        assertThatThrownBy(() -> service.publishObject("sem_m1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("C2");
    }

    @Test
    @DisplayName("C2/C8：指标绑定 Standard 后发布生成不可变版本与 SHA-256 摘要")
    void metricWithStandardPublishesWithDigest() {
        SemanticObject metric = object("sem_m1", "metric.order.gmv", "有效GMV", "METRIC", "DRAFT", "STD-GMV-001", "L1");
        when(objectMapperDao.selectOne(any())).thenReturn(metric);

        ObjectView published = service.publishObject("sem_m1");

        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.versionNo()).isEqualTo(1);
        assertThat(published.contentDigest()).hasSize(64);
        verify(versionMapper).insert(any(SemanticObjectVersion.class));
    }

    @Test
    @DisplayName("C5 PII 防线：aiContext 含手机号明文被拒绝")
    void piiInAiContextBlocked() {
        when(objectMapperDao.selectCount(any())).thenReturn(0L);

        ObjectRequest req = new ObjectRequest(
                "attr.customer.note", "客户备注", "ATTRIBUTE", null, null, null,
                Map.of(), Map.of("caveats", List.of("联系 13812345678 核实")), "alice", "HUMAN");

        assertThatThrownBy(() -> service.createObject(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.MTD_SEMANTIC_INVALID);
    }

    @Test
    @DisplayName("C5 值字典红线：L3 敏感对象严禁建立 Value Linking 值字典")
    void valueDictBlockedForL3Object() {
        SemanticObject l3 = object("sem_phone", "attr.patient.phone", "患者手机号", "ATTRIBUTE", "PUBLISHED", null, "L3");
        when(objectMapperDao.selectOne(any())).thenReturn(l3);

        assertThatThrownBy(() -> service.upsertValueDict("sem_phone",
                new ValueDictRequest("139", "测试", List.of(), "HUMAN")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("C5");
    }

    @Test
    @DisplayName("C5 逐跳鉴权（Hop-by-Hop ACL）：遇到无权中间节点立即剪枝且不向下穿越")
    void hopByHopAclPrunesUnauthorizedIntermediateNode() {
        SemanticObject a = object("sem_a", "metric.order.gmv", "有效GMV", "METRIC", "PUBLISHED", "STD-1", "L1");
        SemanticObject b = object("sem_b", "entity.patient.secret", "敏感病历实体", "ENTITY", "PUBLISHED", null, "L3");
        SemanticObject c = object("sem_c", "concept.hidden.downstream", "下游概念", "CONCEPT", "PUBLISHED", null, "L1");
        when(objectMapperDao.selectList(any())).thenReturn(List.of(a, b, c));

        SemanticMapping ma = mapping("m_a", "sem_a", "ds_allowed", "col_amt");
        SemanticMapping mb = mapping("m_b", "sem_b", "ds_forbidden", "col_note");
        SemanticMapping mc = mapping("m_c", "sem_c", "ds_allowed", "col_tag");
        when(mappingMapper.selectList(any())).thenReturn(List.of(ma, mb, mc));

        SemanticLink ab = link("l_ab", "sem_a", "sem_b", "CAUSAL", "AFFECTS", "PUBLISHED");
        SemanticLink bc = link("l_bc", "sem_b", "sem_c", "SEMANTIC", "MAPS_TO_CONCEPT", "PUBLISHED");
        when(linkMapper.selectList(any())).thenReturn(List.of(ab, bc));

        SubgraphResult res = service.searchSubgraph(new SubgraphSearchRequest(
                "有效GMV", 2, 5, Set.of("ds_allowed"), true));

        assertThat(res.nodes()).extracting(SemanticOntologyService.SubgraphNode::code)
                .containsExactly("metric.order.gmv");
        assertThat(res.links()).isEmpty();
        assertThat(res.prunedNodeCount()).isGreaterThanOrEqualTo(1);
        assertThat(res.prunedReasons()).anyMatch(r -> r.contains("HOP_1_ACL_PRUNED:entity.patient.secret"));
    }

    @Test
    @DisplayName("C9 负反馈防重提：已被驳回的 AI 提案编码再次提交时被抑制")
    void rejectedProposalIsSuppressed() {
        when(objectMapperDao.selectCount(any())).thenReturn(1L);

        ObjectRequest req = new ObjectRequest(
                "concept.noise.dup", "噪声概念", "CONCEPT", null, null, null,
                Map.of(), Map.of(), "ai", "AI_PROPOSED");

        assertThatThrownBy(() -> service.proposeObject(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.MTD_SEMANTIC_STATE);
    }

    @Test
    @DisplayName("物理列影响分析：Column -> Attribute -> Metric 递归向上追踪")
    void impactByColumnTracesUpstreamMetrics() {
        SemanticObject attr = object("sem_attr", "fact.order.amt", "订单金额事实", "FACT", "PUBLISHED", null, "L1");
        SemanticObject metric = object("sem_metric", "metric.order.gmv", "有效GMV", "METRIC", "PUBLISHED", "STD-1", "L1");

        SemanticMapping m = mapping("m1", "sem_attr", "ds_order", "col_amt");
        SemanticLink dep = link("l1", "sem_metric", "sem_attr", "STRUCTURAL", "DERIVED_FROM", "PUBLISHED");

        when(mappingMapper.selectList(any())).thenReturn(List.of(m));
        when(objectMapperDao.selectList(any())).thenReturn(List.of(attr, metric));
        when(linkMapper.selectList(any())).thenReturn(List.of(dep));

        ImpactResult impact = service.impactByColumn("col_amt");
        assertThat(impact.impactedObjects()).extracting(SemanticOntologyService.ImpactItem::code)
                .containsExactly("fact.order.amt", "metric.order.gmv");
    }

    private SemanticObject object(String id, String code, String name, String type,
                                  String status, String stdRef, String sens) {
        SemanticObject o = new SemanticObject();
        o.setId(id);
        o.setWorkspaceId(WS);
        o.setCode(code);
        o.setName(name);
        o.setObjectType(type);
        o.setStatus(status);
        o.setStandardRef(stdRef);
        o.setMaxSensitivity(sens);
        o.setSpecJson("{}");
        o.setAiContextJson("{\"aliases\":[\"净GMV\"],\"safeEnumExamples\":[\"PAID\"]}");
        o.setVersionNo(1);
        o.setValidFrom(Instant.now());
        o.setSourceKind("HUMAN");
        return o;
    }

    private SemanticMapping mapping(String id, String objId, String dsId, String colId) {
        SemanticMapping m = new SemanticMapping();
        m.setId(id);
        m.setWorkspaceId(WS);
        m.setObjectId(objId);
        m.setCatalogDatasetId(dsId);
        m.setCatalogColumnId(colId);
        m.setMappingRole("MEASURE");
        m.setPriority(100);
        m.setStatus("PUBLISHED");
        return m;
    }

    private SemanticLink link(String id, String from, String to, String cat, String type, String status) {
        SemanticLink l = new SemanticLink();
        l.setId(id);
        l.setWorkspaceId(WS);
        l.setFromObjectId(from);
        l.setToObjectId(to);
        l.setEdgeCategory(cat);
        l.setLinkType(type);
        l.setStatus(status);
        l.setValidFrom(Instant.now());
        return l;
    }
}
