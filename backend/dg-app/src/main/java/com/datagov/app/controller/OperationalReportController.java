package com.datagov.app.controller;

import com.datagov.common.api.ApiResponse;
import com.datagov.governance.service.OperationalReportService;
import com.datagov.governance.service.OperationalReportService.ExportFile;
import com.datagov.governance.service.OperationalReportService.PublishRequest;
import com.datagov.governance.service.OperationalReportService.ReportView;
import com.datagov.governance.service.OperationalReportService.RunRequest;
import com.datagov.governance.service.OperationalReportService.RunView;
import com.datagov.governance.service.OperationalReportService.ScheduleRequest;
import com.datagov.governance.service.OperationalReportService.ScheduleView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营报表 API（≠ AnalysisReport）。
 */
@RestController
@RequestMapping("/api/v1/governance/operational-reports")
@Tag(name = "运营报表", description = "参数化复合块报表定义、运行与导出")
public class OperationalReportController {

    private final OperationalReportService service;

    public OperationalReportController(OperationalReportService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "发布运营报表定义（新版本）")
    public ApiResponse<ReportView> publish(@RequestBody PublishRequest request) {
        return ApiResponse.ok(service.publish(request));
    }

    @PostMapping("/{id}/runs")
    @Operation(summary = "触发一次运营报表运行并生成快照制品")
    public ApiResponse<RunView> run(@PathVariable String id, @RequestBody(required = false) RunRequest request) {
        return ApiResponse.ok(service.run(id, request == null ? new RunRequest(null) : request));
    }

    @GetMapping("/runs/{runId}/export")
    @Operation(summary = "导出运行制品（md/csv；pdf 当前为 Markdown 快照）")
    public ResponseEntity<byte[]> export(@PathVariable String runId,
                                         @RequestParam(defaultValue = "md") String format,
                                         @RequestParam(defaultValue = "false") boolean exportConfirmed) {
        ExportFile file = service.export(runId, format, exportConfirmed);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    @PostMapping("/{id}/schedule")
    @Operation(summary = "配置运营报表定时分发（cron + 收件人；C7 敏感导出确认开关）")
    public ApiResponse<ScheduleView> schedule(@PathVariable String id, @RequestBody ScheduleRequest request) {
        return ApiResponse.ok(service.upsertSchedule(id, request));
    }
}
