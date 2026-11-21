package com.datagov.metadata.service;

import com.datagov.metadata.entity.SemanticEntities.SemanticMapping;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.service.SemanticCatalogGrammarService.CatalogGrammar;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticCatalogGrammarServiceTest {

    @Mock
    private SemanticOntologyService ontologyService;

    private SemanticCatalogGrammarService service;

    @BeforeEach
    void setUp() {
        service = new SemanticCatalogGrammarService(ontologyService, new ObjectMapper());
    }

    @Test
    @DisplayName("productionOnly=true 时未 verified 度量不可枚举；维度仍可见")
    void productionOnlyHidesUnverifiedMetrics() {
        SemanticObject unverified = obj("m1", "metric.gmv", "GMV", "METRIC", null,
                "{\"allowedDimensions\":[\"attr.region\"],\"allowedGrains\":[\"day\",\"month\"]}");
        SemanticObject verified = obj("m2", "metric.orders", "订单数", "METRIC",
                "{\"status\":\"PASS\"}",
                "{\"metricKind\":\"BASE\",\"allowedDimensions\":[\"attr.region\"]}");
        SemanticObject dim = obj("d1", "attr.region", "区域", "ATTRIBUTE", null, "{}");

        when(ontologyService.listPublishedObjectsInternal("ws1"))
                .thenReturn(List.of(unverified, verified, dim));
        when(ontologyService.listWorkspaceMappingsInternal("ws1")).thenReturn(List.of());

        CatalogGrammar prod = service.project("ws1", null, true);
        assertThat(prod.metrics()).extracting(m -> m.code()).containsExactly("metric.orders");
        assertThat(prod.metrics().get(0).allowedDimensions()).containsExactly("attr.region");
        assertThat(prod.metrics().get(0).verified()).isTrue();
        assertThat(prod.dimensions()).extracting(d -> d.code()).containsExactly("attr.region");
        assertThat(prod.atModifiers()).contains("USING", "SET_GRAIN");

        CatalogGrammar all = service.project("ws1", null, false);
        assertThat(all.metrics()).extracting(m -> m.code())
                .containsExactlyInAnyOrder("metric.gmv", "metric.orders");
    }

    @Test
    @DisplayName("ACL：无权限数据集上的对象从 Grammar 中剔除")
    void aclPrunesUnauthorized() {
        SemanticObject metric = obj("m1", "metric.secret", "机密", "METRIC",
                "{\"status\":\"PASS\"}", "{}");
        SemanticMapping mapping = new SemanticMapping();
        mapping.setObjectId("m1");
        mapping.setCatalogDatasetId("ds_secret");

        when(ontologyService.listPublishedObjectsInternal("ws1")).thenReturn(List.of(metric));
        when(ontologyService.listWorkspaceMappingsInternal("ws1")).thenReturn(List.of(mapping));

        CatalogGrammar grammar = service.project("ws1", Set.of("ds_public"), true);
        assertThat(grammar.metrics()).isEmpty();
    }

    private static SemanticObject obj(String id, String code, String name, String type,
                                      String verifiedJson, String specJson) {
        SemanticObject o = new SemanticObject();
        o.setId(id);
        o.setCode(code);
        o.setName(name);
        o.setObjectType(type);
        o.setStatus("PUBLISHED");
        o.setVerifiedJson(verifiedJson);
        o.setSpecJson(specJson);
        o.setStandardRef("STD-1");
        return o;
    }
}
