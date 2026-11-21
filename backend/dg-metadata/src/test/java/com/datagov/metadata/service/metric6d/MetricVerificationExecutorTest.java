package com.datagov.metadata.service.metric6d;

import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.data.spi.catalog.CanonicalType;
import com.datagov.data.spi.query.SqlQuery;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.mapper.SemanticObjectMapper;
import com.datagov.metadata.service.DataQueryService;
import com.datagov.metadata.service.metric6d.MetricStatisticalFalsifier.TableBinding;
import com.datagov.metadata.service.metric6d.MetricVerificationExecutor.FalsifyRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetricVerificationExecutorTest {

    @Mock
    private SemanticObjectMapper objectMapper;
    @Mock
    private DataQueryService dataQueryService;

    private MetricVerificationExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new MetricVerificationExecutor(
                objectMapper, dataQueryService,
                new MetricStatisticalFalsifier(), new MetricProbeRunner(), new ObjectMapper());
        WorkspaceContext.set(new Caller("u1", "alice", "ws1", false, Set.of()));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("证伪失败写入 LOW_CONFIDENCE 与 requiresHumanReview")
    void falsifyWritesLowConfidence() {
        SemanticObject obj = new SemanticObject();
        obj.setId("m1");
        obj.setWorkspaceId("ws1");
        obj.setCode("metric.gmv");
        obj.setObjectType("METRIC");
        obj.setVerifiedJson("{}");
        when(objectMapper.selectById("m1")).thenReturn(obj);

        when(dataQueryService.execute(eq("ds1"), any()))
                .thenReturn(cell("f"))
                .thenReturn(cell("0.5"))
                .thenReturn(cell("0.5"));

        var result = executor.runFalsification("m1", new FalsifyRequest(
                "ds1",
                new TableBinding("parent", "id", "id"),
                new TableBinding("child", "id", "parent_id"),
                0.95));

        assertThat(result.status()).isEqualTo("LOW_CONFIDENCE");
        assertThat(result.lowConfidenceFields()).isNotEmpty();
        ArgumentCaptor<SemanticObject> cap = ArgumentCaptor.forClass(SemanticObject.class);
        verify(objectMapper).updateById(cap.capture());
        assertThat(cap.getValue().getRequiresHumanReview()).isTrue();
        assertThat(cap.getValue().getVerifiedJson()).contains("LOW_CONFIDENCE");
    }

    @Test
    @DisplayName("planProbes 写入 6 类探针计划")
    void planProbesPersists() {
        SemanticObject obj = new SemanticObject();
        obj.setId("m1");
        obj.setWorkspaceId("ws1");
        obj.setCode("metric.gmv");
        obj.setObjectType("METRIC");
        when(objectMapper.selectById("m1")).thenReturn(obj);

        var result = executor.planProbes("m1", null, "attr.org.dept");
        assertThat(result.status()).isEqualTo("PLANNED");
        assertThat(result.probes()).hasSize(6);
        verify(objectMapper).updateById(any(SemanticObject.class));
    }

    private static SqlQuery.Result cell(String v) {
        return new SqlQuery.Result(
                List.of(new SqlQuery.Column("c", "bool", CanonicalType.BOOLEAN)),
                List.of(List.of(v)),
                1,
                false,
                1L);
    }
}
