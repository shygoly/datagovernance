package com.datagov.app.controller;

import com.datagov.app.web.RequirePermission;
import com.datagov.common.api.ApiResponse;
import com.datagov.common.api.PageResult;
import com.datagov.metadata.service.FilterPresetService;
import com.datagov.metadata.service.FilterPresetService.PresetView;
import com.datagov.metadata.service.FilterPresetService.UpsertRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名过滤器预设（data-driven-ops-profile P1-2）。
 */
@RestController
@RequestMapping("/api/v1/metadata/filter-presets")
@Tag(name = "命名过滤器预设", description = "可复用 SMQ Filter / Cohort / Quality Scope 条件模板")
public class FilterPresetController {

    private final FilterPresetService service;

    public FilterPresetController(FilterPresetService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermission("metadata:semantic:read")
    @Operation(summary = "分页查询过滤器预设")
    public ApiResponse<PageResult<PresetView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String presetType,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.list(page, size, keyword, presetType, status));
    }

    @GetMapping("/{id}")
    @RequirePermission("metadata:semantic:read")
    public ApiResponse<PresetView> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @RequirePermission("metadata:semantic:manage")
    public ApiResponse<PresetView> create(@RequestBody UpsertRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @RequirePermission("metadata:semantic:manage")
    public ApiResponse<PresetView> update(@PathVariable String id,
                                          @RequestBody UpsertRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("metadata:semantic:manage")
    public ApiResponse<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }
}
