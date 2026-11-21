package com.datagov.app.controller;

import com.datagov.app.runner.EtlOpsTriggerBridge;
import com.datagov.app.runner.EtlPipelineEngine;
import com.datagov.app.runner.EtlWatermarkAuditListener;
import com.datagov.app.web.RequirePermission;
import com.datagov.common.error.BizException;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.control.domain.JobType;
import com.datagov.control.entity.ControlEntities.JobDefinition;
import com.datagov.control.entity.EtlEntities.EtlBadRowBatch;
import com.datagov.control.entity.EtlEntities.EtlWatermark;
import com.datagov.control.event.ControlEvents;
import com.datagov.control.service.BadRowBatchService;
import com.datagov.control.service.JobDefinitionService;
import com.datagov.control.service.WatermarkService;
import com.datagov.governance.service.AuditService;
import com.datagov.governance.service.AuditService.AuditEntry;
import com.datagov.metadata.service.SensitivityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ETL 水位线 / 脏数据 REST · 审计监听 · 运维联动桩")
class EtlPipelineControllerOpsTest {

    private static final String WS = "ws_1";

    private WatermarkService watermarks;
    private BadRowBatchService badRows;
    private JobDefinitionService jobs;
    private EtlPipelineController controller;

    @BeforeEach
    void setUp() {
        watermarks = mock(WatermarkService.class);
        badRows = mock(BadRowBatchService.class);
        jobs = mock(JobDefinitionService.class);
        controller = new EtlPipelineController(mock(EtlPipelineEngine.class), jobs,
                mock(SensitivityService.class), watermarks, badRows);
        WorkspaceContext.set(new Caller("u1", "alice", WS, false, Set.of("control:etl:manage")));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    private JobDefinition job(String id, String ws, JobType type) {
        JobDefinition d = new JobDefinition();
        d.setId(id);
        d.setWorkspaceId(ws);
        d.setJobType(type);
        when(jobs.loadInternal(id)).thenReturn(d);
        return d;
    }

    private static EtlWatermark watermark(String node, String value) {
        EtlWatermark w = new EtlWatermark();
        w.setSourceNodeId(node);
        w.setWatermarkColumn("updated_at");
        w.setWatermarkType("TIMESTAMP");
        w.setLastValue(value);
        w.setLastExecutionId("exec_9");
        w.setUpdatedAt(Instant.parse("2026-11-20T00:00:00Z"));
        return w;
    }

    // ── 权限声明 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET 要 control:etl:read,reset 要 control:etl:manage")
    void permissionsDeclared() throws Exception {
        assertThat(permission("watermarks", String.class)).isEqualTo("control:etl:read");
        assertThat(permission("badRows", String.class)).isEqualTo("control:etl:read");
        assertThat(permission("resetWatermark", String.class, String.class,
                EtlPipelineController.ResetWatermarkRequest.class)).isEqualTo("control:etl:manage");
    }

    private static String permission(String method, Class<?>... types) throws Exception {
        Method m = EtlPipelineController.class.getMethod(method, types);
        return m.getAnnotation(RequirePermission.class).value();
    }

    // ── 水位线 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET 水位线:返回该任务各源节点的水位")
    void listWatermarks() {
        job("job_1", WS, JobType.ETL_PIPELINE);
        when(watermarks.list(WS, "job_1")).thenReturn(List.of(watermark("src", "2026-11-19T10:00:00")));

        List<EtlPipelineController.WatermarkView> body = controller.watermarks("job_1").data();

        assertThat(body).hasSize(1);
        assertThat(body.get(0).sourceNodeId()).isEqualTo("src");
        assertThat(body.get(0).lastValue()).isEqualTo("2026-11-19T10:00:00");
        assertThat(body.get(0).watermarkType()).isEqualTo("TIMESTAMP");
    }

    @Test
    @DisplayName("跨空间任务 / 非 ETL 任务:拒绝,且不会去查或重置水位线")
    void foreignOrNonEtlJobRejected() {
        job("job_x", "ws_other", JobType.ETL_PIPELINE);
        job("job_sql", WS, JobType.OFFLINE_SYNC);

        assertThatThrownBy(() -> controller.watermarks("job_x")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> controller.watermarks("job_sql")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> controller.resetWatermark("job_x", "src",
                new EtlPipelineController.ResetWatermarkRequest("r", null))).isInstanceOf(BizException.class);
        verify(watermarks, never()).list(any(), any());
        verify(watermarks, never()).reset(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reset:必须有原因;委托 WatermarkService.reset 并带上操作人")
    void resetRequiresReason() {
        job("job_1", WS, JobType.ETL_PIPELINE);
        when(watermarks.reset(eq(WS), eq("job_1"), eq("src"), eq(null), eq("补数"), eq("u1")))
                .thenReturn(watermark("src", null));

        assertThatThrownBy(() -> controller.resetWatermark("job_1", "src",
                new EtlPipelineController.ResetWatermarkRequest("  ", null))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> controller.resetWatermark("job_1", "src", null)).isInstanceOf(BizException.class);

        EtlPipelineController.WatermarkView view = controller.resetWatermark("job_1", "src",
                new EtlPipelineController.ResetWatermarkRequest(" 补数 ", null)).data();
        assertThat(view.lastValue()).isNull();
        verify(watermarks).reset(WS, "job_1", "src", null, "补数", "u1");
    }

    // ── 审计 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("重置事件 → 审计记录:带原因与重置前后的值")
    void resetIsAudited() {
        AuditService audit = mock(AuditService.class);
        new EtlWatermarkAuditListener(audit).onWatermarkReset(new ControlEvents.EtlWatermarkReset(
                WS, "job_1", "src", "100", null, "补数", "u1", Instant.now()));

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captor.capture());
        AuditEntry entry = captor.getValue();
        assertThat(entry.action()).isEqualTo("UPDATE");
        assertThat(entry.resourceType()).isEqualTo("ETL_WATERMARK");
        assertThat(entry.resourceId()).isEqualTo("job_1:src");
        assertThat(entry.detail()).contains("reason=补数").contains("previous=100").contains("new=null");
    }

    // ── 脏数据批次 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("GET bad-rows:按当前空间查询并映射摘要字段;过长样例被截断")
    void badRowSummaries() {
        EtlBadRowBatch batch = new EtlBadRowBatch();
        batch.setId("ebr_1");
        batch.setJobId("job_1");
        batch.setExecutionId("exec_1");
        batch.setNodeId("cs");
        batch.setBadRowCount(7L);
        batch.setStorageRef("exec_1/cs.ndjson");
        batch.setSampleErrorJson("x".repeat(5_000));
        batch.setCreatedAt(Instant.parse("2026-11-20T00:00:00Z"));
        when(badRows.listByExecution(WS, "exec_1")).thenReturn(List.of(batch));

        List<EtlPipelineController.BadRowBatchView> body = controller.badRows("exec_1").data();

        assertThat(body).hasSize(1);
        assertThat(body.get(0).badRowCount()).isEqualTo(7);
        assertThat(body.get(0).storageRef()).isEqualTo("exec_1/cs.ndjson");
        assertThat(body.get(0).sampleErrorJson()).hasSizeLessThan(4_100).endsWith("[truncated]");
        verify(badRows).listByExecution(WS, "exec_1");
    }

    // ── 运维联动桩 ──────────────────────────────────────────────────────

    private ControlEvents.EtlExecutionCompleted event(String status, long badRows, double rate) {
        return new ControlEvents.EtlExecutionCompleted(WS, "job_1", "exec_1", "DUCKDB", status, 100, 90,
                badRows, rate, Map.of(), "SKIP_BAD_ROWS", Instant.now());
    }

    @Test
    @DisplayName("ops 桩:FAILED_BAD_ROW_LIMIT 或脏数据率 ≥ 阈值 → 记 ANOMALY 审计(只含计数);正常不记")
    void opsBridge() {
        AuditService audit = mock(AuditService.class);
        EtlOpsTriggerBridge bridge = new EtlOpsTriggerBridge(audit, 0.05);

        bridge.onEtlExecutionCompleted(event("SUCCEEDED", 0, 0));
        bridge.onEtlExecutionCompleted(event("SUCCEEDED", 1, 0.01));
        verify(audit, never()).record(any(AuditEntry.class));

        bridge.onEtlExecutionCompleted(event("SUCCEEDED", 10, 0.10));
        bridge.onEtlExecutionCompleted(event("FAILED_BAD_ROW_LIMIT", 3, 0.03));

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit, org.mockito.Mockito.times(2)).record(captor.capture());
        assertThat(captor.getAllValues()).extracting(AuditEntry::errorCode)
                .containsExactly("ETL_BAD_ROW_RATE_HIGH", "ETL_BAD_ROW_LIMIT");
        AuditEntry first = captor.getAllValues().get(0);
        assertThat(first.action()).isEqualTo("ANOMALY");
        assertThat(first.resourceType()).isEqualTo("ETL_EXECUTION");
        assertThat(first.resourceId()).isEqualTo("exec_1");
        assertThat(first.succeeded()).isFalse();
        assertThat(first.detail()).contains("badRows=10").contains("badRowRate=0.1000");
    }

    @Test
    @DisplayName("ops 桩:审计写入抛异常也不外溢")
    void opsBridgeSwallowsFailures() {
        AuditService audit = mock(AuditService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(audit).record(any(AuditEntry.class));
        new EtlOpsTriggerBridge(audit, 0.05).onEtlExecutionCompleted(event("FAILED_BAD_ROW_LIMIT", 3, 0.03));
    }
}
