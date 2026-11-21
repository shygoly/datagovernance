package com.datagov.app.autonomy;

import com.datagov.app.insight.AgentControlPlaneService;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.control.autonomy.ArbiterService;
import com.datagov.control.autonomy.ArbiterService.ArbiterDecision;
import com.datagov.control.autonomy.FlowDefinitionService;
import com.datagov.control.autonomy.FlowDefinitionService.FlowView;
import com.datagov.control.autonomy.TriggerDefinitionService;
import com.datagov.control.autonomy.TriggerEvaluationService;
import com.datagov.control.entity.AutonomyControlEntities.TriggerDefinition;
import com.datagov.governance.service.AuditService;
import com.datagov.governance.service.AuditService.AuditEntry;
import com.datagov.governance.service.DecisionTraceService;
import com.datagov.runtime.autonomy.CompiledFlowPlan;
import com.datagov.runtime.autonomy.DurableFlowEngine;
import com.datagov.runtime.autonomy.FlowTriggerContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 触发器 → Arbiter → DurableFlow → DecisionTrace 的装配层编排（P1-4 ~ P1-10）。
 *
 * <p>放在 dg-app 而不是任何一个 Space：它必须同时看到 Metadata（发布契约）、
 * Control（编译/裁决）、Runtime（实例）与 Governance（证据），把它们拼起来但不把
 * 依赖反向写回各模块。
 */
@Service
public class AutonomyFlowService {

    private final TriggerDefinitionService triggerService;
    private final TriggerEvaluationService evaluationService;
    private final FlowDefinitionService flowService;
    private final ArbiterService arbiterService;
    private final DecisionTraceService traceService;
    private final DurableFlowEngine flowEngine;
    private final AuditService auditService;
    private final AgentControlPlaneService agentControlPlane;

    public AutonomyFlowService(TriggerDefinitionService triggerService,
                               TriggerEvaluationService evaluationService,
                               FlowDefinitionService flowService,
                               ArbiterService arbiterService,
                               DecisionTraceService traceService,
                               DurableFlowEngine flowEngine,
                               AuditService auditService,
                               AgentControlPlaneService agentControlPlane) {
        this.triggerService = triggerService;
        this.evaluationService = evaluationService;
        this.flowService = flowService;
        this.arbiterService = arbiterService;
        this.traceService = traceService;
        this.flowEngine = flowEngine;
        this.auditService = auditService;
        this.agentControlPlane = agentControlPlane;
    }

    public record FireResult(
            String triggerId,
            boolean fired,
            String reason,
            String traceId,
            String instanceId,
            String arbiterStatus
    ) {
    }

    public FireResult evaluateAndFire(String triggerId, Map<String, Object> snapshot) {
        Caller caller = WorkspaceContext.require();
        String ws = caller.workspaceId();
        TriggerDefinition trigger = triggerService.require(ws, triggerId);
        Map<String, Object> safeSnapshot = sanitizeSnapshot(snapshot);
        TriggerEvaluationService.Evaluation evaluation = evaluationService.evaluate(
                trigger, safeSnapshot, Instant.now());
        if (!evaluation.fired()) {
            return new FireResult(triggerId, false, evaluation.reason(), null, null, null);
        }

        CompiledFlowPlan plan = flowService.findCompiledPlan(trigger.getFlowId());
        // V33 Kill Switch 与自治闭环联动：急停开启时 L1_AUTO 一律降回 SHADOW 运行，
        // 保留完整 Dry-Run 与 DecisionTrace，但不产生真实副作用。
        boolean agentKilled = agentControlPlane != null
                && agentControlPlane.resolveEffectivePolicy(ws).killSwitchEnabled();
        if (agentKilled && FlowDefinitionService.MODE_L1.equals(plan.runMode())) {
            plan = withRunMode(plan, FlowDefinitionService.MODE_SHADOW);
        }
        String entityKey = resolveEntityKey(plan.entityKeyExpr(), safeSnapshot);
        String traceId = traceService.start(new DecisionTraceService.StartCommand(
                ws, plan.flowId(), trigger.getId(), plan.runMode(),
                safeSnapshot, plan.contentDigest(), caller.userId()));
        if (entityKey == null || entityKey.isBlank()) {
            traceService.updateVerify(ws, traceId, DecisionTraceService.INCONCLUSIVE,
                    Map.of("reason", "entityKeyExpr 无法解析: " + plan.entityKeyExpr()));
            return new FireResult(triggerId, true, "ENTITY_KEY_UNRESOLVED", traceId, null, null);
        }

        Map<String, Object> traceSnapshot = new LinkedHashMap<>(safeSnapshot);
        traceSnapshot.put("traceId", traceId);
        traceSnapshot.put("triggerId", trigger.getId());
        traceSnapshot.put("entityKey", entityKey);
        traceSnapshot.put("reason", evaluation.reason());
        List<String> competing = arbiterService.competingFlowCodes(
                ws, trigger.getSourceType(), trigger.getId());
        traceSnapshot.put("competingFlowCodes", competing);

        FlowTriggerContext ctx = new FlowTriggerContext(
                ws, trigger.getId(), entityKey, traceSnapshot, evaluation.metrics(),
                trigger.getPriority() == null ? 100 : trigger.getPriority(), competing);
        ArbiterDecision decision = arbiterService.decide(ws, plan, ctx);
        Map<String, Object> decisionMap = new LinkedHashMap<>();
        decisionMap.put("status", decision.status());
        decisionMap.put("reasonCode", decision.reasonCode());
        decisionMap.put("detail", decision.detail());
        traceService.updateArbiter(ws, traceId, decisionMap);

        if (!decision.executable() && !ArbiterService.ESCALATE_L2.equals(decision.status())) {
            traceService.updateVerify(ws, traceId, DecisionTraceService.INCONCLUSIVE,
                    Map.of("reason", "Arbiter 未放行: " + decision.reasonCode()));
            auditService.record(new AuditEntry(
                    AuditService.EXECUTE, "AUTONOMY_FLOW", trigger.getFlowId(), trigger.getName(),
                    "CONTROL", false, ErrorCode.CTL_FLOW_STATE.code(),
                    "触发器 " + trigger.getCode() + " 被 Arbiter " + decision.status(),
                    "autonomy"));
            return new FireResult(triggerId, true, "ARBITER_" + decision.status(),
                    traceId, null, decision.status());
        }

        CompiledFlowPlan effectivePlan = plan;
        if (ArbiterService.ESCALATE_L2.equals(decision.status())
                && FlowDefinitionService.MODE_L1.equals(plan.runMode())) {
            effectivePlan = withRunMode(plan, FlowDefinitionService.MODE_L2);
        }
        String instanceId;
        try {
            instanceId = flowEngine.startInstance(effectivePlan, ctx, caller);
        } catch (BizException ex) {
            // Arbiter 预检查与引擎抢锁之间可能被并发实例抢先；把最终事实写回 trace，
            // 不能留下“裁决通过但实例没起来”的空白。
            traceService.updateActivity(ws, traceId,
                    Map.of("started", false, "reason", ex.getMessage() == null ? "" : ex.getMessage()));
            traceService.updateVerify(ws, traceId, DecisionTraceService.INCONCLUSIVE,
                    Map.of("reason", "实例启动失败: " + ex.getMessage()));
            return new FireResult(triggerId, true, "INSTANCE_REJECTED",
                    traceId, null, decision.status());
        }
        triggerService.markFired(trigger.getId(), Instant.now());
        auditService.record(new AuditEntry(
                AuditService.EXECUTE, "AUTONOMY_FLOW", trigger.getFlowId(), trigger.getName(),
                "CONTROL", true, null,
                "触发器 " + trigger.getCode() + " 启动实例 " + instanceId
                        + "（mode=" + effectivePlan.runMode() + "）", "autonomy"));
        return new FireResult(triggerId, true, "INSTANCE_STARTED",
                traceId, instanceId, decision.status());
    }

    /**
     * 事件源触发：ETL / 质检监听器调用。没有调用者时以系统身份运行，
     * 但仍显式携带目标 workspace，禁止跨租户扫描。
     */
    public List<FireResult> fireEventSource(String workspaceId, String sourceType,
                                            Map<String, Object> snapshot) {
        Caller system = new Caller("system", "autonomy-trigger", workspaceId, true, Set.of());
        return WorkspaceContext.callAs(system, () -> {
            List<FireResult> results = new ArrayList<>();
            for (TriggerDefinition trigger : triggerService.listEnabledBySource(workspaceId, sourceType)) {
                try {
                    results.add(evaluateAndFire(trigger.getId(), snapshot));
                } catch (RuntimeException ex) {
                    results.add(new FireResult(trigger.getId(), false,
                            "EVALUATION_FAILED: " + ex.getMessage(), null, null, null));
                }
            }
            return results;
        });
    }

    /**
     * 手动 Dry-Run：强制以 SHADOW 启动一个实例，保留完整轨迹但不产生副作用。
     * 这是灰度控制台的“试跑”入口，不依赖触发器。
     */
    public FireResult dryRun(String flowId, Map<String, Object> snapshot) {
        Caller caller = WorkspaceContext.require();
        String ws = caller.workspaceId();
        CompiledFlowPlan plan = withRunMode(flowService.findCompiledPlan(flowId),
                FlowDefinitionService.MODE_SHADOW);
        Map<String, Object> safeSnapshot = sanitizeSnapshot(snapshot);
        String entityKey = resolveEntityKey(plan.entityKeyExpr(), safeSnapshot);
        String traceId = traceService.start(new DecisionTraceService.StartCommand(
                ws, plan.flowId(), null, plan.runMode(), safeSnapshot, plan.contentDigest(),
                caller.userId()));
        if (entityKey == null || entityKey.isBlank()) {
            traceService.updateVerify(ws, traceId, DecisionTraceService.INCONCLUSIVE,
                    Map.of("reason", "entityKeyExpr 无法解析: " + plan.entityKeyExpr()));
            return new FireResult(null, false, "ENTITY_KEY_UNRESOLVED", traceId, null, null);
        }
        Map<String, Object> traceSnapshot = new LinkedHashMap<>(safeSnapshot);
        traceSnapshot.put("traceId", traceId);
        traceSnapshot.put("entityKey", entityKey);
        traceSnapshot.put("reason", "manual dry-run");
        FlowTriggerContext ctx = new FlowTriggerContext(ws, null, entityKey, traceSnapshot,
                metricsOf(safeSnapshot), 100, List.of());
        String instanceId = flowEngine.startInstance(plan, ctx, caller);
        return new FireResult(null, true, "DRY_RUN_STARTED", traceId, instanceId, "SHADOW");
    }

    public void signal(String instanceId, String signal, Map<String, Object> payload) {
        flowEngine.sendSignal(instanceId, signal, payload, WorkspaceContext.require());
    }

    public void cancel(String instanceId, String reason) {
        flowEngine.cancelInstance(instanceId, reason, WorkspaceContext.require());
    }

    /**
     * 受控灰度晋升：先验证 DecisionTrace 证据，再改 Flow 版本（P2-2 / C9）。
     */
    public FlowView promote(String flowId, String targetMode, String note) {
        Caller caller = WorkspaceContext.require();
        String ws = caller.workspaceId();
        FlowView flow = flowService.get(flowId);
        String target = targetMode == null ? "" : targetMode.trim().toUpperCase();
        if (FlowDefinitionService.MODE_L2.equals(target)) {
            long evidence = traceService.countVerified(ws, flowId,
                    FlowDefinitionService.MODE_SHADOW,
                    List.of(DecisionTraceService.IMPROVED, DecisionTraceService.NO_EFFECT));
            long degraded = traceService.countVerified(ws, flowId,
                    FlowDefinitionService.MODE_SHADOW, List.of(DecisionTraceService.DEGRADED));
            if (evidence < 1 || degraded > 0) {
                throw new BizException(ErrorCode.CTL_FLOW_STATE,
                        "晋升 L2_ASSISTED 需要至少 1 条 SHADOW 正向回检，且不得存在 DEGRADED");
            }
        } else if (FlowDefinitionService.MODE_L1.equals(target)) {
            if (agentControlPlane != null
                    && agentControlPlane.resolveEffectivePolicy(ws).killSwitchEnabled()) {
                throw new BizException(ErrorCode.CTL_FLOW_STATE,
                        "智能体/自治 Kill Switch 开启中，禁止晋升 L1_AUTO；请先解除急停");
            }
            long evidence = traceService.countVerified(ws, flowId,
                    FlowDefinitionService.MODE_L2,
                    List.of(DecisionTraceService.IMPROVED, DecisionTraceService.NO_EFFECT));
            long degraded = traceService.countVerified(ws, flowId,
                    FlowDefinitionService.MODE_L2, List.of(DecisionTraceService.DEGRADED));
            if (evidence < 3 || degraded > 0) {
                throw new BizException(ErrorCode.CTL_FLOW_STATE,
                        "晋升 L1_AUTO 需要至少 3 条 L2_ASSISTED 正向回检，且不得存在 DEGRADED");
            }
        }
        FlowView promoted = flowService.applyRunMode(flowId, target, note, caller.userId());
        auditService.record(new AuditEntry(
                AuditService.UPDATE, "AUTONOMY_FLOW", flowId, promoted.name(), "CONTROL",
                true, null, "灰度晋升 " + flow.runMode() + " → " + target + "：" + note, "autonomy"));
        return promoted;
    }

    /** Kill Switch：复用 V33 急停开关，所有流程降回 SHADOW，并取消在途实例。 */
    public Map<String, Object> killSwitch(String reason) {
        Caller caller = WorkspaceContext.require();
        String effectiveReason = reason == null || reason.isBlank()
                ? "数据自治急停" : reason.trim();
        boolean agentSwitch = false;
        if (agentControlPlane != null) {
            try {
                agentControlPlane.setKillSwitch(caller.workspaceId(), true, effectiveReason);
                agentSwitch = true;
            } catch (RuntimeException ex) {
                // 自治急停本身不能因 V33 写入失败而失效
                auditService.record(new AuditEntry(
                        AuditService.EXECUTE, "AUTONOMY_KILL_SWITCH", caller.workspaceId(),
                        "数据自治急停", "GOVERNANCE", false,
                        ErrorCode.SYS_INTERNAL_ERROR.code(),
                        "V33 Kill Switch 联动失败：" + ex.getMessage(), "autonomy"));
            }
        }
        int demoted = flowService.demoteAllToShadow(caller.userId());
        int cancelled = flowEngine.cancelActive(caller.workspaceId(), effectiveReason, caller);
        auditService.record(new AuditEntry(
                AuditService.EXECUTE, "AUTONOMY_KILL_SWITCH", caller.workspaceId(),
                "数据自治急停", "GOVERNANCE", true, null,
                "降级 " + demoted + " 个流程到 SHADOW，取消 " + cancelled + " 个在途实例"
                        + "，V33 联动=" + agentSwitch,
                "autonomy"));
        return Map.of("demotedFlows", demoted, "cancelledInstances", cancelled,
                "agentKillSwitch", agentSwitch);
    }

    private static CompiledFlowPlan withRunMode(CompiledFlowPlan plan, String runMode) {
        return new CompiledFlowPlan(plan.flowId(), plan.flowCode(), plan.flowVersion(), runMode,
                plan.entityKeyExpr(), plan.cooldownSec(), plan.arbiterPolicyRef(),
                plan.steps(), plan.planJson(), plan.contentDigest(), plan.refs());
    }

    private static final java.util.regex.Pattern PII_PHONE =
            java.util.regex.Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final java.util.regex.Pattern PII_ID_CARD =
            java.util.regex.Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final java.util.Set<String> FORBIDDEN_SNAPSHOT_KEYS = java.util.Set.of(
            "rows", "rowdata", "rawdata", "sample", "samplerows", "content", "rawcontent");

    /**
     * C5：触发快照只允许聚合指标与脱敏引用。行内容/疑似明文 PII 在进入
     * DecisionTrace 之前就被剥离；宁可少一段证据，也不把 L3/L4 明细落盘。
     */
    private static Map<String, Object> sanitizeSnapshot(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (FORBIDDEN_SNAPSHOT_KEYS.contains(key.toLowerCase())) {
                continue;
            }
            out.put(key, sanitizeValue(entry.getValue()));
        }
        return out;
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                if (!FORBIDDEN_SNAPSHOT_KEYS.contains(key.toLowerCase())) {
                    nested.put(key, sanitizeValue(v));
                }
            });
            return nested;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AutonomyFlowService::sanitizeValue).toList();
        }
        if (value instanceof String text) {
            String masked = PII_ID_CARD.matcher(PII_PHONE.matcher(text).replaceAll("[REDACTED]"))
                    .replaceAll("[REDACTED]");
            return masked.length() <= 512 ? masked : masked.substring(0, 512);
        }
        return value;
    }

    private static Map<String, Object> metricsOf(Map<String, Object> snapshot) {
        Object raw = snapshot == null ? null : snapshot.get("metrics");
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private static String resolveEntityKey(String expr, Map<String, Object> snapshot) {
        if (expr == null || expr.isBlank()) {
            return null;
        }
        String key = expr.startsWith("trigger.") ? expr.substring("trigger.".length()) : expr;
        Object value = snapshot.get(key);
        if (value == null) {
            value = snapshot.get("entityKey");
        }
        return value == null ? null : String.valueOf(value);
    }
}
