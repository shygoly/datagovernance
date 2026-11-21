package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.entity.AutonomyEntities.FilterPreset;
import com.datagov.metadata.mapper.FilterPresetMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 命名过滤器预设（data-driven-ops-profile P1-2）。
 *
 * <p>预设只保存声明式条件（SMQ filter / cohort / quality scope），值在触发或问数时求值。
 * 这样数据变化后不会出现“预设里存的是三天前的快照”这种静默错误。
 */
@Service
public class FilterPresetService {

    public static final Set<String> PRESET_TYPES = Set.of("SMQ_FILTER", "COHORT", "QUALITY_SCOPE");
    public static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");

    private static final int MAX_SPEC_BYTES = 64 * 1024;

    private final FilterPresetMapper mapper;
    private final ObjectMapper json;

    public FilterPresetService(FilterPresetMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public record UpsertRequest(
            String code,
            String name,
            String description,
            String presetType,
            Map<String, Object> spec,
            String status
    ) {
    }

    public record PresetView(
            String id,
            String code,
            String name,
            String description,
            String presetType,
            Map<String, Object> spec,
            String status,
            Instant createdAt,
            String createdBy,
            Instant updatedAt,
            String updatedBy
    ) {
    }

    public PageResult<PresetView> list(long page, long size, String keyword, String presetType, String status) {
        String ws = WorkspaceContext.requireWorkspaceId();
        LambdaQueryWrapper<FilterPreset> q = new LambdaQueryWrapper<FilterPreset>()
                .eq(FilterPreset::getWorkspaceId, ws);
        if (presetType != null && !presetType.isBlank()) {
            q.eq(FilterPreset::getPresetType, presetType.trim().toUpperCase(Locale.ROOT));
        }
        if (status != null && !status.isBlank()) {
            q.eq(FilterPreset::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            q.and(w -> w.like(FilterPreset::getCode, kw)
                    .or().like(FilterPreset::getName, kw)
                    .or().like(FilterPreset::getDescription, kw));
        }
        q.orderByDesc(FilterPreset::getUpdatedAt);
        var p = mapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                Math.max(1, page), Math.min(200, Math.max(1, size))), q);
        return PageResult.of(p.getRecords().stream().map(this::toView).toList(),
                p.getTotal(), p.getCurrent(), p.getSize());
    }

    public PresetView get(String id) {
        return toView(require(WorkspaceContext.requireWorkspaceId(), id));
    }

    @Transactional
    public PresetView create(UpsertRequest req) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String userId = WorkspaceContext.require().userId();
        validate(req);
        Long dup = mapper.selectCount(new LambdaQueryWrapper<FilterPreset>()
                .eq(FilterPreset::getWorkspaceId, ws)
                .apply("lower(code) = {0}", req.code().trim().toLowerCase(Locale.ROOT)));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.SYS_CONFLICT, "过滤器预设编码已存在: " + req.code());
        }
        Instant now = Instant.now();
        FilterPreset entity = new FilterPreset();
        entity.setId(Ids.of("afp"));
        entity.setWorkspaceId(ws);
        entity.setCreatedAt(now);
        entity.setCreatedBy(userId);
        apply(entity, req);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(userId);
        mapper.insert(entity);
        return toView(entity);
    }

    @Transactional
    public PresetView update(String id, UpsertRequest req) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String userId = WorkspaceContext.require().userId();
        FilterPreset entity = require(ws, id);
        validate(req);
        if (!entity.getCode().equalsIgnoreCase(req.code().trim())) {
            Long dup = mapper.selectCount(new LambdaQueryWrapper<FilterPreset>()
                    .eq(FilterPreset::getWorkspaceId, ws)
                    .ne(FilterPreset::getId, id)
                    .apply("lower(code) = {0}", req.code().trim().toLowerCase(Locale.ROOT)));
            if (dup != null && dup > 0) {
                throw new BizException(ErrorCode.SYS_CONFLICT, "过滤器预设编码已存在: " + req.code());
            }
        }
        apply(entity, req);
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(userId);
        mapper.updateById(entity);
        return toView(entity);
    }

    @Transactional
    public void delete(String id) {
        FilterPreset entity = require(WorkspaceContext.requireWorkspaceId(), id);
        mapper.deleteById(entity.getId());
    }

    private void apply(FilterPreset entity, UpsertRequest req) {
        entity.setCode(req.code().trim());
        entity.setName(req.name().trim());
        entity.setDescription(blankToNull(req.description()));
        entity.setPresetType(req.presetType().trim().toUpperCase(Locale.ROOT));
        entity.setSpecJson(writeJson(req.spec() == null ? Map.of() : req.spec()));
        entity.setStatus(req.status() == null || req.status().isBlank()
                ? "ACTIVE" : req.status().trim().toUpperCase(Locale.ROOT));
    }

    private void validate(UpsertRequest req) {
        if (req == null || blank(req.code()) || blank(req.name()) || blank(req.presetType())) {
            throw new BizException(ErrorCode.MTD_FILTER_PRESET_INVALID, "code、name、presetType 为必填项");
        }
        String type = req.presetType().trim().toUpperCase(Locale.ROOT);
        if (!PRESET_TYPES.contains(type)) {
            throw new BizException(ErrorCode.MTD_FILTER_PRESET_INVALID, "不支持的预设类型: " + type);
        }
        if (req.status() != null && !req.status().isBlank()
                && !STATUSES.contains(req.status().trim().toUpperCase(Locale.ROOT))) {
            throw new BizException(ErrorCode.MTD_FILTER_PRESET_INVALID, "不支持的预设状态: " + req.status());
        }
        String spec = writeJson(req.spec() == null ? Map.of() : req.spec());
        if (spec.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SPEC_BYTES) {
            throw new BizException(ErrorCode.MTD_FILTER_PRESET_INVALID, "预设条件超过 64KB 上限");
        }
    }

    private FilterPreset require(String ws, String id) {
        FilterPreset entity = mapper.selectOne(new LambdaQueryWrapper<FilterPreset>()
                .eq(FilterPreset::getWorkspaceId, ws)
                .eq(FilterPreset::getId, id));
        if (entity == null) {
            throw new BizException(ErrorCode.MTD_FILTER_PRESET_NOT_FOUND, "过滤器预设不存在: " + id);
        }
        return entity;
    }

    private PresetView toView(FilterPreset e) {
        return new PresetView(e.getId(), e.getCode(), e.getName(), e.getDescription(),
                e.getPresetType(), readJsonMap(e.getSpecJson()), e.getStatus(),
                e.getCreatedAt(), e.getCreatedBy(), e.getUpdatedAt(), e.getUpdatedBy());
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.MTD_FILTER_PRESET_INVALID, "预设条件不是合法 JSON");
        }
    }

    private Map<String, Object> readJsonMap(String raw) {
        if (blank(raw)) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return blank(value) ? null : value.trim();
    }
}
