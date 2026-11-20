package com.datagov.app.insight;

import com.datagov.app.insight.InsightQueryEngine.EngineAskResult;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.CatalogSourceMapper;
import com.datagov.metadata.service.DataQueryService;
import com.datagov.metadata.service.SemanticOntologyService;
import com.datagov.metadata.service.SemanticOntologyService.SubgraphNode;
import com.datagov.metadata.service.SemanticOntologyService.SubgraphResult;
import com.datagov.metadata.service.SemanticQueryCompiler;
import com.datagov.metadata.service.SemanticQueryCompiler.CompiledColumn;
import com.datagov.metadata.service.SemanticQueryCompiler.CompiledSemanticQuery;
import com.datagov.metadata.service.SemanticValueLinkingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("原生语义问数引擎（替代 SuperSonic）")
class NativeInsightEngineTest {

    private SemanticOntologyService ontologyService;
    private SemanticValueLinkingService valueLinkingService;
    private SemanticQueryCompiler compiler;
    private CatalogDatasetMapper datasetMapper;
    private CatalogSourceMapper sourceMapper;
    private DataQueryService dataQueryService;
    private NativeInsightEngine engine;

    @BeforeEach
    void setUp() {
        ontologyService = mock(SemanticOntologyService.class);
        valueLinkingService = mock(SemanticValueLinkingService.class);
        compiler = mock(SemanticQueryCompiler.class);
        datasetMapper = mock(CatalogDatasetMapper.class);
        sourceMapper = mock(CatalogSourceMapper.class);
        dataQueryService = mock(DataQueryService.class);
        InsightProperties props = new InsightProperties();
        engine = new NativeInsightEngine(
                ontologyService, valueLinkingService, compiler,
                datasetMapper, sourceMapper, dataQueryService, props);
        WorkspaceContext.set(new Caller("usr_1", "alice", "ws_1", false, Set.of("insight:ask")));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("C8：未匹配到已发布指标时显式返回 INCONCLUSIVE，绝不瞎猜")
    void returnsInconclusiveWhenNoMetricMatched() {
        when(ontologyService.searchSubgraph(any()))
                .thenReturn(new SubgraphResult("未知指标", 2, List.of(), List.of(), 0, List.of(), List.of()));
        when(valueLinkingService.matchValues(anyString(), anyString())).thenReturn(List.of());
        when(valueLinkingService.normalizeRelativeTime(anyString(), any())).thenReturn(null);

        EngineAskResult res = engine.ask("查一下火星人口增长率", 1L, null);
        assertThat(res.state()).isEqualTo("INCONCLUSIVE");
        assertThat(res.sql()).isNull();
    }

    @Test
    @DisplayName("多义指标消歧：召回多个带 disambiguation 的冲突指标时返回 CLARIFY_NEEDED")
    void returnsClarifyNeededOnAmbiguousMetrics() {
        SubgraphNode m1 = new SubgraphNode("id1", "metric.order.valid_gmv", "有效GMV", "METRIC",
                "STD-1", "L1", 1, "d1", 0, Map.of(),
                Map.of("disambiguation", "剔除退单和未支付的净成交额"), List.of());
        SubgraphNode m2 = new SubgraphNode("id2", "metric.order.gross_gmv", "下单GMV", "METRIC",
                "STD-2", "L1", 1, "d2", 0, Map.of(),
                Map.of("disambiguation", "含未支付的全部下单金额"), List.of());
        when(ontologyService.searchSubgraph(any()))
                .thenReturn(new SubgraphResult("GMV", 2, List.of(m1, m2), List.of(), 0, List.of(), List.of()));
        when(valueLinkingService.matchValues(anyString(), anyString())).thenReturn(List.of());
        when(valueLinkingService.normalizeRelativeTime(anyString(), any())).thenReturn(null);

        EngineAskResult res = engine.ask("上月 GMV 是多少", 1L, null);
        assertThat(res.state()).isEqualTo("CLARIFY_NEEDED");
        assertThat(res.clarifications()).hasSize(2);
    }

    @Test
    @DisplayName("命中单一指标时确定性编译并附带 SHA-256 审计指纹")
    void compilesDeterministicallyAndAttachesFingerprint() {
        SubgraphNode m1 = new SubgraphNode("id1", "metric.order.valid_gmv", "有效GMV", "METRIC",
                "STD-1", "L1", 2, "abc123", 0, Map.of(), Map.of(), List.of());
        when(ontologyService.searchSubgraph(any()))
                .thenReturn(new SubgraphResult("有效GMV", 2, List.of(m1), List.of(), 0, List.of(), List.of("metric.order.valid_gmv@v2#abc123")));
        when(valueLinkingService.matchValues(anyString(), anyString())).thenReturn(List.of());
        when(valueLinkingService.normalizeRelativeTime(anyString(), any())).thenReturn(null);
        when(compiler.compile(any(), any())).thenReturn(new CompiledSemanticQuery(
                "SELECT SUM(t0.amount) AS metric_order_valid_gmv FROM dwd_order t0 LIMIT 200",
                "STARROCKS", "ds_1", List.of("ds_1"),
                List.of(new CompiledColumn("metric_order_valid_gmv", "有效GMV", "METRIC")),
                List.of("metric.order.valid_gmv@v2#abc123"),
                "fp_sha256_demo",
                List.of("KG_EXCLUDES:退单"),
                false, List.of()));
        when(datasetMapper.selectOne(any())).thenReturn(null);

        EngineAskResult res = engine.ask("统计有效GMV", 1L, null);
        assertThat(res.state()).isEqualTo("COMPILED_ONLY");
        assertThat(res.sql()).contains("SUM(t0.amount)");
        assertThat(res.auditFingerprint()).isEqualTo("fp_sha256_demo");
        assertThat(res.appliedExclusions()).containsExactly("KG_EXCLUDES:退单");
    }
}
