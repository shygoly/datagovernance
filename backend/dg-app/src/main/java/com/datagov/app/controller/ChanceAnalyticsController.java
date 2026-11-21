package com.datagov.app.controller;

import com.datagov.app.service.ChanceAnalyticsService;
import com.datagov.app.service.ChanceAnalyticsService.BoardResponse;
import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/insight/chance")
@Tag(name = "CHANCE 专病看板", description = "队列基线 / 治疗路径 / 生存随访")
public class ChanceAnalyticsController {

    private final ChanceAnalyticsService service;

    public ChanceAnalyticsController(ChanceAnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/cohort-baseline")
    @RequirePermission("insight:ask")
    @Operation(summary = "队列与基线画像（漏斗 + Table1 分布）")
    public ApiResponse<BoardResponse> cohortBaseline() {
        return ApiResponse.ok(service.cohortBaseline());
    }

    @GetMapping("/treatment-pathway")
    @RequirePermission("insight:ask")
    @Operation(summary = "治疗路径 Sankey / 方案占比（按 session_seq）")
    public ApiResponse<BoardResponse> treatmentPathway() {
        return ApiResponse.ok(service.treatmentPathway());
    }

    @GetMapping("/survival")
    @RequirePermission("insight:ask")
    @Operation(summary = "随访与生存（OS/PFS MVP + ORR）")
    public ApiResponse<BoardResponse> survival(@RequestParam(defaultValue = "OS") String endpoint) {
        return ApiResponse.ok(service.survival(endpoint));
    }
}
