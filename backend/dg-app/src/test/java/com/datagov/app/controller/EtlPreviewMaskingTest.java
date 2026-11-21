package com.datagov.app.controller;

import com.datagov.app.runner.EtlPipelineEngine;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.control.service.BadRowBatchService;
import com.datagov.control.service.JobDefinitionService;
import com.datagov.control.service.WatermarkService;
import com.datagov.governance.service.AccessRequestService;
import com.datagov.governance.service.AccessRequestService.GrantCoverage;
import com.datagov.metadata.service.SensitivityService;
import com.datagov.metadata.service.SensitivityService.SensitiveColumnView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EtlPreviewMaskingTest {

    private SensitivityService sensitivity;
    private AccessRequestService accessRequests;
    private EtlPipelineController controller;

    @BeforeEach
    void setUp() {
        sensitivity = mock(SensitivityService.class);
        accessRequests = mock(AccessRequestService.class);
        controller = new EtlPipelineController(mock(EtlPipelineEngine.class),
                mock(JobDefinitionService.class), sensitivity,
                mock(WatermarkService.class), mock(BadRowBatchService.class));
        controller.setAccessRequestService(accessRequests);
        WorkspaceContext.set(new Caller("u1", "alice", "ws1", false,
                Set.of("control:etl:read")));
        when(sensitivity.sensitiveColumnNames()).thenReturn(List.of(
                new SensitiveColumnView("phone", "PII", "L3")));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    @DisplayName("无 AccessGrant 时 L3 列按当前用户脱敏")
    void masksWithoutGrant() {
        when(accessRequests.activeGrants("ws1", "alice")).thenReturn(List.of());
        List<String> masked = new ArrayList<>();

        var rows = controller.maskSensitive(preview(), masked,
                WorkspaceContext.require());

        assertThat(masked).containsExactly("phone");
        assertThat(rows.get(0)).containsEntry("phone", "***").containsEntry("name", "Alice");
    }

    @Test
    @DisplayName("当前用户持有 PII/L3 有效 grant 时放行该列")
    void allowsWithGrant() {
        when(accessRequests.activeGrants("ws1", "alice")).thenReturn(List.of(
                new GrantCoverage("grant_1", "PII", "L3", Instant.now().plusSeconds(3600))));
        List<String> masked = new ArrayList<>();

        var rows = controller.maskSensitive(preview(), masked,
                WorkspaceContext.require());

        assertThat(masked).isEmpty();
        assertThat(rows.get(0)).containsEntry("phone", "13800000000");
    }

    private static EtlPipelineEngine.PreviewResult preview() {
        return new EtlPipelineEngine.PreviewResult("node_1",
                List.of(new EtlPipelineEngine.PreviewColumn("phone", "VARCHAR"),
                        new EtlPipelineEngine.PreviewColumn("name", "VARCHAR")),
                List.of(Map.of("phone", "13800000000", "name", "Alice")),
                false);
    }
}
