package com.datagov.control.autonomy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.control.entity.AutonomyControlEntities.FlowDefinition;
import com.datagov.control.entity.AutonomyControlEntities.TriggerDefinition;
import com.datagov.control.mapper.AuFlowDefMapper;
import com.datagov.control.mapper.AuTriggerDefMapper;
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
 * 声明式触发器定义（P1-6）。
 *
 * <p>触发器只声明“什么条件算触发”，动作由绑定的 FlowSpec 决定；
 * 这样调整阈值不需要改流程，调整流程也不需要复制触发条件。
 */
@Service
public class TriggerDefinitionService {

    public static final Set<String> SOURCE_TYPES = Set.of(
            "METRIC_THRESHOLD", "QUALITY_FAILURE", "ETL_ANOMALY", "DATA_ARRIVAL");

    private final AuTriggerDefMapper triggerMapper;
    private final AuFlowDefMapper flowMapper;
    private final ObjectMapper json;

    public TriggerDefinitionService(AuTriggerDefMapper triggerMapper,
                                    AuFlowDefMapper flowMapper,
                                    ObjectMapper json) {
        this.triggerMapper = triggerMapper;
        this.flowMapper = flowMapper;
        this.json = json;
    }

    public record UpsertRequest(
            String code,
            String name,
            String sourceType,
            Map<String, Object> condition,
            Integer debounceSec,
            String flowId,
            Boolean enabled,
            Integer priority
    ) {
    }

    public record TriggerView(
            String id,
            String code,
            String name,
            String sourceType,
            Map<String, Object> condition,
            int debounceSec,
            String flowId,
            String flowCode,
            boolean enabled,
            int priority,
            Instant lastFiredAt,
            Instant updatedAt
    ) {
    }

    public PageResult<TriggerView> list(long page, long size, String keyword, String sourceType,
                                        Boolean enabled) {
        String ws = WorkspaceContext.requireWorkspaceId();
        LambdaQueryWrapper<TriggerDefinition> q = new LambdaQueryWrapper<TriggerDefinition>()
                .eq(TriggerDefinition::getWorkspaceId, ws);
        if (sourceType != null && !sourceType.isBlank()) {
            q.eq(TriggerDefinition::getSourceType, sourceType.trim().toUpperCase(Locale.ROOT));
        }
        if (enabled != null) {
            q.eq(TriggerDefinition::getEnabled, enabled);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            q.and(w -> w.like(TriggerDefinition::getCode, kw)
                    .or().like(TriggerDefinition::getName, kw));
        }
        q.orderByAsc(TriggerDefinition::getPriority).orderByDesc(TriggerDefinition::getUpdatedAt);
        Page<TriggerDefinition> p = triggerMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(200, Math.max(1, size))), q);
        return PageResult.of(p.getRecords().stream().map(this::toView).toList(),
                p.getTotal(), p.getCurrent(), p.getSize());
    }

    public TriggerView get(String id) {
        return toView(require(WorkspaceContext.requireWorkspaceId(), id));
    }

    public List<TriggerDefinition> listEnabledBySource(String workspaceId, String sourceType) {
        return triggerMapper.selectList(new LambdaQueryWrapper<TriggerDefinition>()
                .eq(TriggerDefinition::getWorkspaceId, workspaceId)
                .eq(TriggerDefinition::getSourceType, sourceType)
                .eq(TriggerDefinition::getEnabled, true)
                .orderByAsc(TriggerDefinition::getPriority));
    }

    @Transactional
    public TriggerView create(UpsertRequest req) {
        String ws = WorkspaceContext.requireWorkspaceId();
        validate(ws, req);
        Long dup = triggerMapper.selectCount(new LambdaQueryWrapper<TriggerDefinition>()
                .eq(TriggerDefinition::getWorkspaceId, ws)
                .apply("lower(code) = {0}", req.code().trim().toLowerCase(Locale.ROOT)));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.CTL_TRIGGER_INVALID, "触发器编码已存在: " + req.code());
        }
        Instant now = Instant.now();
        TriggerDefinition entity = new TriggerDefinition();
        entity.setId(Ids.of("atr"));
        entity.setWorkspaceId(ws);
        entity.setCreatedAt(now);
        entity.setCreatedBy(WorkspaceContext.require().userId());
        apply(entity, req);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(WorkspaceContext.require().userId());
        triggerMapper.insert(entity);
        return toView(entity);
    }

    @Transactional
    public TriggerView update(String id, UpsertRequest req) {
        String ws = WorkspaceContext.requireWorkspaceId();
        validate(ws, req);
        TriggerDefinition entity = require(ws, id);
        if (!entity.getCode().equalsIgnoreCase(req.code().trim())) {
            Long dup = triggerMapper.selectCount(new LambdaQueryWrapper<TriggerDefinition>()
                    .eq(TriggerDefinition::getWorkspaceId, ws)
                    .ne(TriggerDefinition::getId, id)
                    .apply("lower(code) = {0}", req.code().trim().toLowerCase(Locale.ROOT)));
            if (dup != null && dup > 0) {
                throw new BizException(ErrorCode.CTL_TRIGGER_INVALID, "触发器编码已存在: " + req.code());
            }
        }
        apply(entity, req);
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(WorkspaceContext.require().userId());
        triggerMapper.updateById(entity);
        return toView(entity);
    }

    @Transactional
    public void delete(String id) {
        TriggerDefinition entity = require(WorkspaceContext.requireWorkspaceId(), id);
        triggerMapper.deleteById(entity.getId());
    }

    @Transactional
    public void markFired(String id, Instant at) {
        TriggerDefinition entity = triggerMapper.selectById(id);
        if (entity != null) {
            entity.setLastFiredAt(at);
            entity.setUpdatedAt(at);
            triggerMapper.updateById(entity);
        }
    }

    public TriggerDefinition require(String ws, String id) {
        TriggerDefinition entity = triggerMapper.selectOne(new LambdaQueryWrapper<TriggerDefinition>()
                .eq(TriggerDefinition::getWorkspaceId, ws)
                .eq(TriggerDefinition::getId, id));
        if (entity == null) {
            throw new BizException(ErrorCode.CTL_TRIGGER_NOT_FOUND, "自治触发器不存在: " + id);
        }
        return entity;
    }

    private void validate(String ws, UpsertRequest req) {
        if (req == null || blank(req.code()) || blank(req.name()) || blank(req.sourceType())) {
            throw new BizException(ErrorCode.CTL_TRIGGER_INVALID, "code、name、sourceType 必填");
        }
        String type = req.sourceType().trim().toUpperCase(Locale.ROOT);
        if (!SOURCE_TYPES.contains(type)) {
            throw new BizException(ErrorCode.CTL_TRIGGER_INVALID, "不支持的触发器类型: " + type);
        }
        if (req.debounceSec() != null && req.debounceSec() < 0) {
            throw new BizException(ErrorCode.CTL_TRIGGER_INVALID, "debounceSec 不能为负");
        }
        if (req.priority() != null && req.priority() < 0) {
            throw new BizException(ErrorCode.CTL_TRIGGER_INVALID, "priority 不能为负");
        }
        if (blank(req.flowId())) {
            throw new BizException(ErrorCode.CTL_TRIGGER_INVALID, "flowId 必填");
        }
        FlowDefinition flow = flowMapper.selectOne(new LambdaQueryWrapper<FlowDefinition>()
                .eq(FlowDefinition::getWorkspaceId, ws)
                .eq(FlowDefinition::getId, req.flowId().trim()));
        if (flow == null) {
            throw new BizException(ErrorCode.CTL_FLOW_NOT_FOUND, "绑定的自治流程不存在: " + req.flowId());
        }
        if (!FlowDefinitionService.STATUS_PUBLISHED.equals(flow.getStatus())) {
            throw new BizException(ErrorCode.CTL_TRIGGER_INVALID, "触发器只能绑定已发布流程");
        }
        Map<String, Object> condition = req.condition() == null ? Map.of() : req.condition();
        switch (type) {
            case "METRIC_THRESHOLD" -> {
                if (blank(str(condition.get("metricRef"))) && blank(str(condition.get("metric")))) {
                    throw new BizException(ErrorCode.CTL_TRIGGER_INVALID, "METRIC_THRESHOLD 缺少 metricRef");
                }
                if (condition.get("threshold") == null) {
                    throw new BizException(ErrorCode.CTL_TRIGGER_INVALID, "METRIC_THRESHOLD 缺少 threshold");
                }
                String op = str(condition.get("operator")).toUpperCase(Locale.ROOT);
                if (!Set.of("LT", "LTE", "GT", "GTE", "EQ", "NE").contains(op)) {
                    throw new BizException(ErrorCode.CTL_TRIGGER_INVALID,
                            "METRIC_THRESHOLD operator 必须是 LT/LTE/GT/GTE/EQ/NE");
                }
            }
            case "QUALITY_FAILURE" -> {
                if (condition.get("maxPassRate") == null && condition.get("failed") == null) {
                    throw new BizException(ErrorCode.CTL_TRIGGER_INVALID,
                            "QUALITY_FAILURE 需要 maxPassRate 或 failed");
                }
            }
            case "ETL_ANOMALY" -> {
                if (condition.get("badRowRateAtLeast") == null && condition.get("statuses") == null) {
                    throw new BizException(ErrorCode.CTL_TRIGGER_INVALID,
                            "ETL_ANOMALY 需要 badRowRateAtLeast 或 statuses");
                }
            }
            case "DATA_ARRIVAL" -> {
                if (condition.get("maxDelaySec") == null) {
                    throw new BizException(ErrorCode.CTL_TRIGGER_INVALID, "DATA_ARRIVAL 缺少 maxDelaySec");
                }
            }
            default -> {
            }
        }
    }

    private void apply(TriggerDefinition entity, UpsertRequest req) {
        entity.setCode(req.code().trim());
        entity.setName(req.name().trim());
        entity.setSourceType(req.sourceType().trim().toUpperCase(Locale.ROOT));
        entity.setConditionJson(write(req.condition() == null ? Map.of() : req.condition()));
        entity.setDebounceSec(req.debounceSec() == null ? 0 : req.debounceSec());
        entity.setFlowId(req.flowId().trim());
        entity.setEnabled(req.enabled() == null || req.enabled());
        entity.setPriority(req.priority() == null ? 100 : req.priority());
    }

    private TriggerView toView(TriggerDefinition e) {
        FlowDefinition flow = flowMapper.selectById(e.getFlowId());
        return new TriggerView(e.getId(), e.getCode(), e.getName(), e.getSourceType(),
                readMap(e.getConditionJson()), e.getDebounceSec() == null ? 0 : e.getDebounceSec(),
                e.getFlowId(), flow == null ? null : flow.getCode(),
                Boolean.TRUE.equals(e.getEnabled()), e.getPriority() == null ? 100 : e.getPriority(),
                e.getLastFiredAt(), e.getUpdatedAt());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "序列化触发条件失败");
        }
    }

    private Map<String, Object> readMap(String raw) {
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

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private static boolean blank(String raw) {
        return raw == null || raw.isBlank();
    }
}
