package com.datagov.app.insight;

import com.datagov.app.insight.ChartSpecRecommender.ChartSpec;
import com.datagov.app.insight.InsightQueryEngine.InsightColumn;
import com.datagov.metadata.service.SemanticOntologyService.ObjectView;
import com.datagov.metadata.service.SemanticQueryCompiler.SemanticQuerySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("确定性白名单 ChartSpec 推荐器")
class ChartSpecRecommenderTest {

    private final ChartSpecRecommender recommender = new ChartSpecRecommender();

    @Test
    @DisplayName("单行多指标推荐 KPI 指标卡")
    void singleRowMetricsRecommendKpi() {
        ChartSpec spec = recommender.recommend(
                "SUCCESS",
                "总订单数和总GMV",
                List.of(
                        new InsightColumn("订单数", "metric_order_cnt", "NUMBER"),
                        new InsightColumn("有效GMV", "metric_order_gmv", "NUMBER")),
                List.of(Map.of("metric_order_cnt", 1200, "metric_order_gmv", 856000.5)),
                null,
                List.of());

        assertThat(spec.chartType()).isEqualTo("kpi");
        assertThat(spec.yFields()).containsExactly("metric_order_cnt", "metric_order_gmv");
        assertThat(spec.supportedTypes()).containsExactly("kpi", "table");
    }

    @Test
    @DisplayName("时间维度 + 不同量纲双指标（元 vs %）推荐折线图并自动开启双 Y 轴")
    void timeSeriesWithMixedUnitsEnablesDualAxis() {
        ObjectView gmv = obj("metric.order.gmv", "有效GMV", "METRIC", Map.of("unit", "元"));
        ObjectView rate = obj("metric.order.refund_rate", "退单率", "METRIC", Map.of("unit", "%"));
        ObjectView dateDim = obj("dim.time.biz_date", "业务日期", "DIMENSION", Map.of("isTimeDimension", true));

        ChartSpec spec = recommender.recommend(
                "SUCCESS",
                "按日统计有效GMV和退单率趋势",
                List.of(
                        new InsightColumn("业务日期", "dim_time_biz_date", "STRING"),
                        new InsightColumn("有效GMV", "metric_order_gmv", "NUMBER"),
                        new InsightColumn("退单率", "metric_order_refund_rate", "NUMBER")),
                List.of(
                        Map.of("dim_time_biz_date", "2026-10-01", "metric_order_gmv", 10000, "metric_order_refund_rate", 0.02),
                        Map.of("dim_time_biz_date", "2026-10-02", "metric_order_gmv", 12000, "metric_order_refund_rate", 0.03)),
                new SemanticQuerySpec(null, List.of("metric.order.gmv", "metric.order.refund_rate"),
                        List.of("dim.time.biz_date"), List.of(), null, List.of(), 50),
                List.of(gmv, rate, dateDim));

        assertThat(spec.chartType()).isEqualTo("line");
        assertThat(spec.dualAxis()).isTrue();
        assertThat(spec.units()).containsEntry("metric_order_gmv", "元")
                .containsEntry("metric_order_refund_rate", "%");
        assertThat(spec.supportedTypes()).contains("line", "area", "bar", "table");
    }

    @Test
    @DisplayName("分类维度自动从 DIMENSION.hierarchy 推导合法确定性下钻路径")
    void derivesDrilldownFromDimensionHierarchy() {
        ObjectView orgDim = obj("dim.org.geo", "地理层级", "DIMENSION",
                Map.of("hierarchy", List.of("attr.org.region", "attr.org.province", "attr.org.city")));
        ObjectView province = obj("attr.org.province", "省份", "ATTRIBUTE", Map.of());

        ChartSpec spec = recommender.recommend(
                "SUCCESS",
                "按区域统计有效GMV",
                List.of(
                        new InsightColumn("区域", "attr_org_region", "STRING"),
                        new InsightColumn("有效GMV", "metric_order_gmv", "NUMBER")),
                List.of(
                        Map.of("attr_org_region", "EAST", "metric_order_gmv", 50000),
                        Map.of("attr_org_region", "SOUTH", "metric_order_gmv", 32000)),
                new SemanticQuerySpec(null, List.of("metric.order.gmv"),
                        List.of("attr.org.region"), List.of(), null, List.of(), 50),
                List.of(orgDim, province));

        assertThat(spec.chartType()).isEqualTo("bar");
        assertThat(spec.drilldowns()).hasSize(1);
        assertThat(spec.drilldowns().get(0).nextDimensionCode()).isEqualTo("attr.org.province");
        assertThat(spec.drilldowns().get(0).nextDimensionName()).isEqualTo("省份");
    }

    @Test
    @DisplayName("双维度分类聚合自动推荐堆叠柱图并设置 seriesField")
    void twoDimensionsRecommendStackedBar() {
        ChartSpec spec = recommender.recommend(
                "SUCCESS",
                "按区域和订单状态统计GMV",
                List.of(
                        new InsightColumn("区域", "region", "STRING"),
                        new InsightColumn("状态", "status", "STRING"),
                        new InsightColumn("GMV", "gmv", "NUMBER")),
                List.of(
                        Map.of("region", "EAST", "status", "PAID", "gmv", 100),
                        Map.of("region", "EAST", "status", "COMPLETED", "gmv", 200)),
                null,
                List.of());

        assertThat(spec.chartType()).isEqualTo("stacked_bar");
        assertThat(spec.xField()).isEqualTo("region");
        assertThat(spec.seriesField()).isEqualTo("status");
    }

    @Test
    @DisplayName("占比问题且类目<=8 推荐饼图；METADATA/INCONCLUSIVE 强制仅表格")
    void pieForShareAndTableForMetadata() {
        ChartSpec pie = recommender.recommend(
                "SUCCESS",
                "各区域有效GMV占比构成",
                List.of(
                        new InsightColumn("区域", "region", "STRING"),
                        new InsightColumn("GMV", "gmv", "NUMBER")),
                List.of(
                        Map.of("region", "EAST", "gmv", 60),
                        Map.of("region", "SOUTH", "gmv", 40)),
                null,
                List.of());
        assertThat(pie.chartType()).isEqualTo("pie");

        ChartSpec meta = recommender.recommend("METADATA", "有哪些表", List.of(), List.of(), null, List.of());
        assertThat(meta.chartType()).isEqualTo("table");
        assertThat(meta.supportedTypes()).containsExactly("table");
    }

    private static ObjectView obj(String code, String name, String type, Map<String, Object> spec) {
        return new ObjectView("id_" + code, code, name, type, null, null, null, null,
                spec, Map.of(), "L1", "PUBLISHED", 1, null, null, null, null,
                "HUMAN", null, null, null, null, null, 0, 0);
    }
}
