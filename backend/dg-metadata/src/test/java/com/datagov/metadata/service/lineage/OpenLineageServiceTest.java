package com.datagov.metadata.service.lineage;

import com.datagov.common.error.BizException;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.service.LineageCatalogResolver;
import com.datagov.metadata.service.LineageService;
import com.datagov.metadata.service.LineageService.EdgeInput;
import com.datagov.metadata.service.LineageService.JobRequest;
import com.datagov.metadata.service.LineageService.JobResult;
import com.datagov.metadata.service.LineageService.NodeRef;
import com.datagov.metadata.service.lineage.OpenLineageService.IngestResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OpenLineage 接入")
class OpenLineageServiceTest {

    private static final String WS = "ws_alpha";
    private static final ObjectMapper JSON = new ObjectMapper();

    private LineageService lineageService;
    private LineageCatalogResolver resolver;
    private OpenLineageService service;

    @BeforeEach
    void setUp() {
        lineageService = mock(LineageService.class);
        resolver = mock(LineageCatalogResolver.class);
        service = new OpenLineageService(lineageService, resolver);
        WorkspaceContext.set(new Caller("usr_1", "alice", WS, false, Set.of("metadata:lineage:manage")));
        when(lineageService.recordJob(any(JobRequest.class))).thenReturn(new JobResult("linj_1", 0));
        // 模拟 Catalog 对齐：ods.* 能对上，其它对不上（catalog ID 为空）。
        when(resolver.resolveNodeRef(anyString(), isNull(), any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String database = inv.getArgument(2);
                    String schema = inv.getArgument(3);
                    String table = inv.getArgument(4);
                    String column = inv.getArgument(5);
                    String qualified = String.join(".", java.util.stream.Stream.of(database, schema, table, column)
                            .filter(java.util.Objects::nonNull).toList());
                    boolean aligned = "ods".equals(database) || "ods".equals(schema);
                    return new NodeRef("COLUMN", qualified,
                            aligned ? "ds_" + table : null, aligned ? "col_" + table + "_" + column : null, column);
                });
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    private static JsonNode event(String json) throws Exception {
        return JSON.readTree(json);
    }

    private JobRequest recorded() {
        ArgumentCaptor<JobRequest> captor = ArgumentCaptor.forClass(JobRequest.class);
        verify(lineageService).recordJob(captor.capture());
        return captor.getValue();
    }

    private static final String COMPLETE_EVENT = """
            {
              "eventType": "COMPLETE",
              "eventTime": "2026-11-20T10:00:00Z",
              "run": {"runId": "0194a8f0-1111-7000-8000-000000000001"},
              "job": {"namespace": "airflow", "name": "orders_etl.load_dwd",
                      "facets": {"sql": {"query": "insert into dwd.orders select * from ods.orders"}}},
              "inputs": [{"namespace": "postgres://h:5432", "name": "ods.public.orders"}],
              "outputs": [{
                "namespace": "postgres://h:5432", "name": "dwd.public.orders",
                "facets": {"columnLineage": {"fields": {
                  "amount": {"inputFields": [
                      {"namespace": "postgres://h:5432", "name": "ods.public.orders", "field": "amt",
                       "transformations": [{"type": "DIRECT", "subtype": "AGGREGATION", "description": "SUM(amt)"}]},
                      {"namespace": "postgres://h:5432", "name": "ods.public.orders", "field": "status",
                       "transformations": [{"type": "INDIRECT", "subtype": "FILTER", "description": "status = 'PAID'"}]}]},
                  "phone": {"inputFields": [
                      {"namespace": "postgres://h:5432", "name": "ods.public.orders", "field": "phone",
                       "transformations": [{"type": "DIRECT", "subtype": "TRANSFORMATION", "masking": true}]}]}
                }}}
              }]
            }
            """;

    @Test
    @DisplayName("COMPLETE + columnLineage：写 ACTIVE 边(origin=OPENLINEAGE)，区分 DIRECT/INDIRECT 与脱敏")
    void ingestsCompleteEvent() throws Exception {
        IngestResult result = service.ingest(event(COMPLETE_EVENT));

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(result.jobId()).isEqualTo("linj_1");
        assertThat(result.outputs()).isEqualTo(1);

        JobRequest job = recorded();
        assertThat(job.name()).isEqualTo("airflow:orders_etl.load_dwd");
        assertThat(job.jobType()).isEqualTo("OPENLINEAGE");
        assertThat(job.sourceRef()).isEqualTo("openlineage:0194a8f0-1111-7000-8000-000000000001");
        assertThat(job.sqlHash()).hasSize(64); // 只存哈希，不存 SQL 明文
        assertThat(job.parseStatus()).isEqualTo("OK");
        assertThat(job.edges()).hasSize(3);

        EdgeInput direct = job.edges().get(0);
        assertThat(direct.origin()).isEqualTo("OPENLINEAGE");
        assertThat(direct.dependencyType()).isEqualTo("DIRECT");
        assertThat(direct.relation()).isEqualTo("DERIVE");
        assertThat(direct.transformExpr()).isEqualTo("SUM(amt)");
        assertThat(direct.maskingApplied()).isFalse();
        // 与 Catalog 对齐：源端有 catalog ID，目标端(dwd)按 resolver 对不上则为空。
        assertThat(direct.from().catalogDatasetId()).isEqualTo("ds_orders");
        assertThat(direct.from().catalogColumnId()).isEqualTo("col_orders_amt");
        assertThat(direct.to().catalogColumnId()).isNull();

        EdgeInput indirect = job.edges().get(1);
        assertThat(indirect.dependencyType()).isEqualTo("INDIRECT");
        assertThat(indirect.relation()).isEqualTo("FILTER");
        assertThat(indirect.transformExpr()).isEqualTo("status = 'PAID'");

        EdgeInput masked = job.edges().get(2);
        assertThat(masked.maskingApplied()).isTrue();
        assertThat(masked.maskingEvidence()).isNotBlank();
        assertThat(result.unalignedColumns()).isEqualTo(2); // dwd.public.orders 的 amount / phone
    }

    @Test
    @DisplayName("非 COMPLETE 事件不写任何边")
    void nonCompleteIsIgnored() throws Exception {
        for (String type : List.of("START", "RUNNING", "FAIL", "ABORT", "OTHER")) {
            IngestResult result = service.ingest(event(COMPLETE_EVENT.replace("COMPLETE", type)));
            assertThat(result.status()).isEqualTo("IGNORED");
            assertThat(result.message()).contains(type);
        }
        verify(lineageService, never()).recordJob(any());
    }

    @Test
    @DisplayName("COMPLETE 但没有 columnLineage facet：忽略并说明原因")
    void noColumnLineageFacet() throws Exception {
        IngestResult result = service.ingest(event("""
                {"eventType":"COMPLETE","job":{"namespace":"n","name":"j"},
                 "outputs":[{"namespace":"n","name":"ods.public.t","facets":{"schema":{}}}]}
                """));

        assertThat(result.status()).isEqualTo("IGNORED");
        assertThat(result.message()).contains("columnLineage");
        verify(lineageService, never()).recordJob(any());
    }

    @Test
    @DisplayName("dataset 级 columnLineage 视为影响全部输出列的 INDIRECT 依赖")
    void datasetLevelIndirectInputs() throws Exception {
        service.ingest(event("""
                {"eventType":"COMPLETE","job":{"namespace":"n","name":"j"},
                 "outputs":[{"namespace":"n","name":"ods.public.out","facets":{"columnLineage":{
                   "fields":{"a":{"inputFields":[{"namespace":"n","name":"ods.public.src","field":"x"}]},
                             "b":{"inputFields":[{"namespace":"n","name":"ods.public.src","field":"y"}]}},
                   "dataset":[{"namespace":"n","name":"ods.public.src","field":"k",
                               "transformations":[{"type":"INDIRECT","subtype":"JOIN"}]}]}}}]}
                """));

        List<EdgeInput> edges = recorded().edges();
        assertThat(edges).hasSize(4);
        assertThat(edges).filteredOn(e -> "INDIRECT".equals(e.dependencyType())).hasSize(2)
                .allSatisfy(e -> assertThat(e.relation()).isEqualTo("JOIN"));
        assertThat(edges).filteredOn(e -> "DIRECT".equals(e.dependencyType())).hasSize(2);
    }

    @Test
    @DisplayName("旧版 1.0 字段级 transformationType=MASKED 视为脱敏；输入输出同列的自环被跳过")
    void legacyMaskedAndSelfEdges() throws Exception {
        service.ingest(event("""
                {"eventType":"COMPLETE","job":{"name":"j"},
                 "outputs":[{"name":"ods.public.t","facets":{"columnLineage":{"fields":{
                   "p":{"transformationType":"MASKED","transformationDescription":"mask(p)",
                        "inputFields":[{"name":"ods.public.s","field":"p"}]},
                   "same":{"inputFields":[{"name":"ods.public.t","field":"same"}]}}}}}]}
                """));

        List<EdgeInput> edges = recorded().edges();
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).maskingApplied()).isTrue();
        assertThat(edges.get(0).transformExpr()).isEqualTo("mask(p)");
        assertThat(recorded().name()).isEqualTo("j"); // 没有 namespace 时不加前缀
    }

    @Test
    @DisplayName("缺少 eventType / job.name 或非对象 payload：校验失败")
    void rejectsMalformedEvents() throws Exception {
        assertThatThrownBy(() -> service.ingest(event("{\"job\":{\"name\":\"j\"}}")))
                .isInstanceOf(BizException.class).hasMessageContaining("eventType");
        assertThatThrownBy(() -> service.ingest(event("{\"eventType\":\"COMPLETE\"}")))
                .isInstanceOf(BizException.class).hasMessageContaining("job.name");
        assertThatThrownBy(() -> service.ingest(event("[1,2]")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.ingest(null)).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("没有 workspace 上下文拒绝处理（租户只来自登录态，不信任 payload）")
    void requiresWorkspaceContext() throws Exception {
        WorkspaceContext.clear();
        JsonNode event = event(COMPLETE_EVENT.replace("\"eventTime\"", "\"workspaceId\":\"ws_evil\",\"eventTime\""));

        assertThatThrownBy(() -> service.ingest(event)).isInstanceOf(RuntimeException.class);
        verify(lineageService, never()).recordJob(any());
    }
}
