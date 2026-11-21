package com.datagov.control.autonomy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.control.entity.AutonomyControlEntities.TriggerDefinition;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.mapper.SemanticObjectMapper;
import com.datagov.runtime.autonomy.CompiledFlowPlan;
import com.datagov.runtime.autonomy.EntityLockService;
import com.datagov.runtime.autonomy.FlowStep;
import com.datagov.runtime.autonomy.FlowTriggerContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 两阶段确定性 Arbiter（P1-5 / D4）。
 *
 * <p>顺序不可交换：
 * <ol>
 *   <li>硬不变量一票否决（Target.invariants）；</li>
 *   <li>实体在途锁 / 冷却期（防震荡）；</li>
 *   <li>冲突规则：命中无法确定裁决的竞争流程时升级 L2，而不是让 LLM 拍板。</li>
 * </ol>
 */
@Service
public class ArbiterService {

    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String SUPPRESSED = "SUPPRESSED";
    public static final String ESCALATE_L2 = "ESCALATE_L2";

    private final SemanticObjectMapper semanticObjectMapper;
    private final EntityLockService locks;
    private final ObjectMapper json;
    private final TriggerDefinitionService triggerService;

    public ArbiterService(SemanticObjectMapper semanticObjectMapper,
                          EntityLockService locks,
                          ObjectMapper json,
                          TriggerDefinitionService triggerService) {
        this.semanticObjectMapper = semanticObjectMapper;
        this.locks = locks;
        this.json = json;
        this.triggerService = triggerService;
    }

    public record InvariantResult(String code, String severity, String status, String detail) {
    }

    public record ArbiterDecision(
            String status,
            String reasonCode,
            Map<String, Object> detail,
            List<InvariantResult> invariants,
            boolean requireL2
    ) {
        public ArbiterDecision {
            detail = detail == null ? Map.of() : Map.copyOf(detail);
            invariants = invariants == null ? List.of() : List.copyOf(invariants);
        }

        public boolean executable() {
            return APPROVED.equals(status);
        }
    }

    public ArbiterDecision decide(String workspaceId, CompiledFlowPlan plan, FlowTriggerContext ctx) {
        return decide(workspaceId, plan, ctx, null);
    }

    /**
     * @param ownInstanceId 当前实例 ID；引擎抢锁后再次裁决时跳过自己持有的锁，
     *                      否则会把“自己的锁”误判成并发冲突。
     */
    public ArbiterDecision decide(String workspaceId, CompiledFlowPlan plan, FlowTriggerContext ctx,
                                  String ownInstanceId) {
        List<InvariantResult> invariants = checkInvariants(workspaceId, plan, ctx.metrics(), ctx.snapshot());
        boolean hardBlocked = invariants.stream().anyMatch(i ->
                "HARD_BLOCK".equals(i.severity()) && !"PASS".equals(i.status()));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("invariants", invariants);
        detail.put("entityKey", ctx.entityKey());
        if (hardBlocked) {
            detail.put("stage", "INVARIANT");
            return new ArbiterDecision(REJECTED, "INVARIANT_VIOLATED", detail, invariants, false);
        }

        EntityLockService.LockState lock = locks.check(workspaceId, ctx.entityKey());
        boolean ownLock = ownInstanceId != null && ownInstanceId.equals(lock.instanceId());
        if (lock.blocked(java.time.Instant.now()) && !ownLock) {
            detail.put("stage", "LOCK_OR_COOLDOWN");
            detail.put("lock", Map.of(
                    "locked", lock.locked(),
                    "instanceId", lock.instanceId() == null ? "" : lock.instanceId(),
                    "cooldownUntil", lock.cooldownUntil() == null ? "" : lock.cooldownUntil().toString()));
            return new ArbiterDecision(SUPPRESSED, "IN_FLIGHT_OR_COOLDOWN", detail, invariants, false);
        }

        boolean fallbackToL2 = plan.steps().stream()
                .filter(s -> "ARBITER_DECIDE".equals(s.kind()))
                .anyMatch(FlowStep::fallbackToL2);
        if (!ctx.competingFlowCodes().isEmpty()) {
            detail.put("stage", "CONFLICT");
            detail.put("competingFlows", ctx.competingFlowCodes());
            if (fallbackToL2) {
                return new ArbiterDecision(ESCALATE_L2, "CONFLICT_REQUIRES_HUMAN", detail, invariants, true);
            }
            // 声明式优先级规则表：priority 升序（数值越小越优先），同优先级用 triggerId 字典序打破平局。
            // 这不是“让 LLM 猜”，而是可复现的确定性裁决。
            try {
                TriggerDefinition current = triggerService.require(workspaceId, ctx.triggerId());
                List<TriggerDefinition> competitors = triggerService.listEnabledBySource(
                        workspaceId, current.getSourceType());
                TriggerDefinition winner = competitors.stream()
                        .min(java.util.Comparator
                                .comparingInt((TriggerDefinition t) -> t.getPriority() == null ? 100 : t.getPriority())
                                .thenComparing(t -> t.getId() == null ? "" : t.getId()))
                        .orElse(current);
                detail.put("priorityWinner", winner.getCode());
                if (!current.getId().equals(winner.getId())) {
                    return new ArbiterDecision(SUPPRESSED, "LOWER_PRIORITY",
                            detail, invariants, false);
                }
            } catch (RuntimeException ex) {
                // 竞争事实存在但优先级表不可读：按 C8 升级人工，而不是猜测谁优先。
                detail.put("priorityError", ex.getMessage());
                return new ArbiterDecision(ESCALATE_L2, "CONFLICT_UNRESOLVED", detail, invariants, true);
            }
        }

        detail.put("stage", "APPROVED");
        return new ArbiterDecision(APPROVED, "ALL_GATES_PASSED", detail, invariants, false);
    }

    public List<InvariantResult> checkInvariants(String workspaceId, CompiledFlowPlan plan,
                                                 Map<String, Object> metrics,
                                                 Map<String, Object> snapshot) {
        SemanticObject target = resolveTarget(workspaceId, plan);
        if (target == null) {
            boolean hasSideEffect = plan.steps().stream()
                    .anyMatch(s -> "INVOKE_ACTIVITY".equals(s.kind()));
            if (!hasSideEffect) {
                return List.of();
            }
            return List.of(new InvariantResult("TARGET_MISSING", "HARD_BLOCK", "INCONCLUSIVE",
                    "未找到已发布的 Target，按 C8 视为证据不足"));
        }
        Map<String, Object> spec = readMap(target.getSpecJson());
        List<Map<String, Object>> invariants = mapList(spec.get("invariants"));
        List<InvariantResult> out = new ArrayList<>();
        for (Map<String, Object> inv : invariants) {
            String code = str(inv.get("code"));
            String severity = str(inv.get("severity")).isBlank()
                    ? "HARD_BLOCK" : str(inv.get("severity")).toUpperCase();
            String metricRef = str(inv.get("metricRef"));
            String ruleRef = str(inv.get("ruleRef"));
            if (!metricRef.isBlank()) {
                Double actual = number(metrics.get(metricRef));
                if (actual == null) {
                    actual = number(snapshot.get(metricRef));
                }
                Double threshold = number(inv.get("threshold"));
                String op = str(inv.get("operator")).toUpperCase();
                if (actual == null || threshold == null) {
                    out.add(new InvariantResult(code, severity, "INCONCLUSIVE",
                            "指标 " + metricRef + " 或阈值缺失"));
                } else {
                    boolean pass = TriggerEvaluationService.compare(actual, threshold, op);
                    out.add(new InvariantResult(code, severity, pass ? "PASS" : "VIOLATED",
                            metricRef + "=" + actual + " " + op + " " + threshold));
                }
            } else if (!ruleRef.isBlank()) {
                Object value = metrics.containsKey(ruleRef)
                        ? metrics.get(ruleRef) : snapshot.get(ruleRef);
                if (value instanceof Boolean b) {
                    out.add(new InvariantResult(code, severity, b ? "PASS" : "VIOLATED",
                            "规则 " + ruleRef + "=" + b));
                } else if (value != null) {
                    boolean pass = !"FAILED".equalsIgnoreCase(String.valueOf(value))
                            && !"FAIL".equalsIgnoreCase(String.valueOf(value));
                    out.add(new InvariantResult(code, severity, pass ? "PASS" : "VIOLATED",
                            "规则 " + ruleRef + "=" + value));
                } else {
                    out.add(new InvariantResult(code, severity, "INCONCLUSIVE",
                            "规则结果 " + ruleRef + " 缺失"));
                }
            } else {
                out.add(new InvariantResult(code, severity, "INCONCLUSIVE",
                        "invariant 没有 metricRef / ruleRef"));
            }
        }
        return out;
    }

    public List<String> competingFlowCodes(String workspaceId, String sourceType, String triggerId) {
        List<TriggerDefinition> triggers = triggerService.listEnabledBySource(workspaceId, sourceType);
        List<String> out = new ArrayList<>();
        for (TriggerDefinition trigger : triggers) {
            if (!trigger.getId().equals(triggerId) && trigger.getFlowId() != null) {
                out.add(trigger.getFlowId());
            }
        }
        return out;
    }

    private SemanticObject resolveTarget(String workspaceId, CompiledFlowPlan plan) {
        String targetRef = plan.steps().stream()
                .filter(s -> "CHECK_INVARIANT".equals(s.kind()))
                .map(FlowStep::targetRef)
                .filter(r -> r != null && !r.isBlank())
                .findFirst()
                .orElse(null);
        if (targetRef == null) {
            return null;
        }
        return semanticObjectMapper.selectOne(new LambdaQueryWrapper<SemanticObject>()
                .and(w -> w.eq(SemanticObject::getWorkspaceId, workspaceId)
                        .or(g -> g.eq(SemanticObject::getScope, "GLOBAL")
                                .eq(SemanticObject::getStatus, "PUBLISHED")))
                .eq(SemanticObject::getObjectType, "TARGET")
                .eq(SemanticObject::getStatus, "PUBLISHED")
                .and(w -> w.eq(SemanticObject::getId, targetRef)
                        .or().eq(SemanticObject::getCode, targetRef))
                .last("LIMIT 1"));
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static List<Map<String, Object>> mapList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> typed = new LinkedHashMap<>();
                map.forEach((k, v) -> typed.put(String.valueOf(k), v));
                out.add(typed);
            }
        }
        return out;
    }

    private static Double number(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
