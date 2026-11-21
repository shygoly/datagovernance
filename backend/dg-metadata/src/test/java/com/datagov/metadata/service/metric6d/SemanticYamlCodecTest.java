package com.datagov.metadata.service.metric6d;

import com.datagov.metadata.service.metric6d.SemanticYamlCodec.CatalogEntry;
import com.datagov.metadata.service.metric6d.SemanticYamlCodec.ImportDocument;
import com.datagov.metadata.service.metric6d.SemanticYamlCodec.ResolveResult;
import com.datagov.metadata.service.metric6d.SemanticYamlCodec.ResolveStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticYamlCodecTest {

    private final SemanticYamlCodec codec = new SemanticYamlCodec();

    @Test
    @DisplayName("短名唯一命中 RESOLVED；冲突 AMBIGUOUS；未命中带候选")
    void resolveRefStatuses() {
        List<CatalogEntry> catalog = List.of(
                entry("1", "sales.dimension.region", "区域", "DIMENSION"),
                entry("2", "hr.dimension.region", "HR区域", "DIMENSION"),
                entry("3", "sales.metric.gmv", "GMV", "METRIC"));

        ResolveResult ok = codec.resolveRef("sales.metric.gmv", catalog);
        assertThat(ok.status()).isEqualTo(ResolveStatus.RESOLVED);
        assertThat(ok.canonicalCode()).isEqualTo("sales.metric.gmv");

        ResolveResult amb = codec.resolveRef("region", catalog);
        assertThat(amb.status()).isEqualTo(ResolveStatus.AMBIGUOUS_REF);
        assertThat(amb.candidates()).hasSize(2);

        ResolveResult miss = codec.resolveRef("totally_unknown_xyz", catalog);
        assertThat(miss.status()).isEqualTo(ResolveStatus.UNRESOLVED_REF);
    }

    @Test
    @DisplayName("YAML 解析 + 导出 short/full 往返")
    void parseAndExport() {
        String yaml = """
                model: sales
                metrics:
                  - code: sales.metric.gmv
                    name: GMV
                    standardRef: STD-GMV
                    dimensions: [sales.dimension.region]
                    spec:
                      additivity:
                        kind: additive
                      composition:
                        metric_type: atomic
                dimensions:
                  - code: sales.dimension.region
                    name: 区域
                """;
        ImportDocument doc = codec.parse(yaml);
        assertThat(doc.metrics()).hasSize(1);
        assertThat(doc.metrics().get(0).dimensionRefs()).contains("sales.dimension.region");

        List<CatalogEntry> catalog = List.of(
                entry("1", "sales.dimension.region", "区域", "DIMENSION"),
                entry("2", "sales.metric.gmv", "GMV", "METRIC"));
        Map<String, String> resolved = codec.resolveAllOrThrow(doc, catalog);
        assertThat(resolved).containsEntry("sales.dimension.region", "sales.dimension.region");

        String shortYaml = codec.export(
                List.of(entry("2", "sales.metric.gmv", "GMV", "METRIC")),
                List.of(entry("1", "sales.dimension.region", "区域", "DIMENSION")),
                "short");
        assertThat(shortYaml).contains("gmv").contains("region");

        String fullYaml = codec.export(
                List.of(entry("2", "sales.metric.gmv", "GMV", "METRIC")),
                List.of(entry("1", "sales.dimension.region", "区域", "DIMENSION")),
                "full");
        assertThat(fullYaml).contains("sales.metric.gmv").contains("id:");
    }

    @Test
    @DisplayName("未解析引用拒绝导入")
    void unresolvedRejectsImport() {
        ImportDocument doc = codec.parse("""
                metrics:
                  - code: m1
                    dimensions: [missing_dim]
                """);
        assertThatThrownBy(() -> codec.resolveAllOrThrow(doc, List.of()))
                .hasMessageContaining("UNRESOLVED_REF");
    }

    private static CatalogEntry entry(String id, String code, String name, String type) {
        return new CatalogEntry(id, code, name, type, Map.of());
    }
}
