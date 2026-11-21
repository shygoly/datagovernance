package com.datagov.runtime.autonomy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.runtime.entity.AutonomyRuntimeEntities.FlowInstance;
import com.datagov.runtime.mapper.AuFlowInstanceMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL 持久化状态机实现（data-driven-ops-profile D3）。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>每一步都落 checkpoint，进程重启后从 current_step_id 继续；</li>
 *   <li>WAITING_APPROVAL / WAITING_VERIFY 用 wake_at 表达超时与 T+1；</li>
 *   <li>失败或取消时按历史逆序补偿已执行的 INVOKE_ACTIVITY 步骤（Saga）；</li>
 *   <li>实体锁由唯一索引兜底，状态机本身不假设单实例部署。</li>
 * </ul>
 *
 * <p>Runtime 不解释 Target / ActionType：所有业务求值交给装配层注册的
 * {@link FlowStepHandler}，Runtime 只保证顺序、持久化、审批与补偿。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "dg.autonomy.engine", havingValue = "pg", matchIfMissing = true)
public class PgDurableFlowEngine implements DurableFlowEngine {

    private static final Logger log = LoggerFactory.getLogger(PgDurableFlowEngine.class);

    private static final Set<String> TERMINAL = Set.of(
            "COMPLETED", "SHADOW_COMPLETED", "FAILED", "CANCELLED", "COMPENSATION_FAILED");
    private static final int MAX_STEPS = 100;

    private final AuFlowInstanceMapper mapper;
    private final EntityLockService locks;
    private final FlowStepHandlerRegistry handlers;
    private final ObjectMapper json;
    private final List<FlowObserver> observers;
    private FlowVerifyObservationProviderRegistry observationProviders;

    public PgDurableFlowEngine(AuFlowInstanceMapper mapper,
                               EntityLockService locks,
                               FlowStepHandlerRegistry handlers,
                               ObjectMapper json,
                               List<FlowObserver> observers) {
        this.mapper = mapper;
        this.locks = locks;
        this.handlers = handlers;
        this.json = json;
        this.observers = observers == null ? List.of() : List.copyOf(observers);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setObservationProviders(FlowVerifyObservationProviderRegistry observationProviders) {
        this.observationProviders = observationProviders;
    }

    @Override
    public String startInstance(CompiledFlowPlan plan, FlowTriggerContext trigger, Caller initiator) {
        if (plan == null || plan.steps().isEmpty()) {
            throw new BizException(ErrorCode.RTM_FLOW_STATE, "Flow 计划没有可执行步骤");
        }
        if (trigger == null || blank(trigger.workspaceId()) || blank(trigger.entityKey())) {
            throw new BizException(ErrorCode.RTM_FLOW_STATE, "Flow 实例缺少 workspaceId 或 entityKey");
        }
        String instanceId = Ids.of("afi");
        Instant now = Instant.now();
        if (!locks.tryAcquire(trigger.workspaceId(), trigger.entityKey(), instanceId, now)) {
            throw new BizException(ErrorCode.RTM_FLOW_SUPPRESSED,
                    "实体 " + trigger.entityKey() + " 存在在途流程或处于冷却期");
        }

        FlowInstance instance = new FlowInstance();
        instance.setId(instanceId);
        instance.setWorkspaceId(trigger.workspaceId());
        instance.setFlowId(plan.flowId());
        instance.setFlowCode(plan.flowCode());
        instance.setFlowVersion(plan.flowVersion());
        instance.setTriggerId(trigger.triggerId());
        instance.setTraceId(str(trigger.snapshot().get("traceId")));
        instance.setStatus("RUNNING");
        instance.setCurrentStepId(plan.steps().get(0).id());
        instance.setEntityKey(trigger.entityKey());
        instance.setRunMode(plan.runMode());
        instance.setPlanJson(write(plan));
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("triggerSnapshot", trigger.snapshot());
        checkpoint.put("metrics", trigger.metrics());
        checkpoint.put("initiatorUserId", initiator == null ? null : initiator.userId());
        checkpoint.put("initiatorUsername", initiator == null ? null : initiator.username());
        instance.setCheckpointJson(write(checkpoint));
        instance.setHistoryJson("[]");
        instance.setStartedAt(now);
        instance.setUpdatedAt(now);
        try {
            mapper.insert(instance);
        } catch (RuntimeException ex) {
            locks.forceRelease(trigger.workspaceId(), trigger.entityKey());
            throw ex;
        }

        FlowInstanceView view = toView(instance);
        observers.forEach(o -> o.onInstanceStarted(view, plan, trigger));
        try {
            advance(instance, null, initiator);
        } catch (RuntimeException ex) {
            failAndCompensate(instance, plan, initiator, ex.getMessage());
            throw ex;
        }
        return instanceId;
    }

    @Override
    public void sendSignal(String instanceId, String signalName, Map<String, Object> payload, Caller actor) {
        if (blank(signalName)) {
            throw new BizException(ErrorCode.RTM_FLOW_STATE, "signalName 不能为空");
        }
        FlowInstance instance = require(instanceId, actor);
        if (TERMINAL.contains(instance.getStatus())) {
            throw new BizException(ErrorCode.RTM_FLOW_STATE,
                    "实例 " + instanceId + " 已终止（" + instance.getStatus() + "），不能再发送信号");
        }
        if (!"WAITING_APPROVAL".equals(instance.getStatus())
                && !"WAITING_VERIFY".equals(instance.getStatus())) {
            throw new BizException(ErrorCode.RTM_FLOW_STATE,
                    "实例当前状态 " + instance.getStatus() + " 不接受审批/回检信号");
        }
        String normalized = signalName.trim().toUpperCase();
        if ("REJECT".equals(normalized) || "CANCEL".equals(normalized)) {
            cancelInstance(instanceId, "signal:" + normalized, actor);
            return;
        }
        CompiledFlowPlan plan = readPlan(instance);
        Map<String, Object> checkpoint = readMap(instance.getCheckpointJson());
        checkpoint.put("lastSignal", normalized);
        checkpoint.put("signalPayload", payload == null ? Map.of() : payload);
        instance.setCheckpointJson(write(checkpoint));
        instance.setWakeAt(null);
        instance.setStatus("RUNNING");
        instance.setUpdatedAt(Instant.now());
        mapper.updateById(instance);
        try {
            advance(instance, normalized, actor);
        } catch (RuntimeException ex) {
            failAndCompensate(instance, plan, actor, ex.getMessage());
            throw ex;
        }
    }

    @Override
    public void cancelInstance(String instanceId, String reason, Caller actor) {
        FlowInstance instance = require(instanceId, actor);
        if (TERMINAL.contains(instance.getStatus())) {
            return;
        }
        CompiledFlowPlan plan = readPlan(instance);
        compensate(instance, plan, actor, reason == null ? "cancelled" : reason, "CANCELLED");
    }

    @Override
    public FlowInstanceView inspect(String instanceId) {
        Caller caller = WorkspaceContext.get();
        FlowInstance instance = mapper.selectById(instanceId);
        if (instance == null) {
            throw new BizException(ErrorCode.RTM_FLOW_NOT_FOUND, "自治流程实例不存在: " + instanceId);
        }
        if (caller != null && !caller.platformAdmin()
                && caller.workspaceId() != null && !caller.workspaceId().equals(instance.getWorkspaceId())) {
            throw new BizException(ErrorCode.RTM_FLOW_NOT_FOUND, "自治流程实例不存在: " + instanceId);
        }
        return toView(instance);
    }

    @Override
    public int sweepDueInstances(Instant now, int batchSize) {
        List<FlowInstance> due = mapper.selectList(new LambdaQueryWrapper<FlowInstance>()
                .in(FlowInstance::getStatus, List.of("WAITING_VERIFY", "WAITING_APPROVAL"))
                .isNotNull(FlowInstance::getWakeAt)
                .le(FlowInstance::getWakeAt, now)
                .orderByAsc(FlowInstance::getWakeAt)
                .last("LIMIT " + Math.max(1, Math.min(200, batchSize))));
        int advanced = 0;
        for (FlowInstance instance : due) {
            try {
                Caller system = new Caller("system", "autonomy-sweeper", instance.getWorkspaceId(),
                        true, Set.of());
                WorkspaceContext.callAs(system, () -> {
                    if ("WAITING_APPROVAL".equals(instance.getStatus())) {
                        cancelInstance(instance.getId(), "approval timeout", system);
                    } else {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("sweptAt", now.toString());
                        if (observationProviders != null) {
                            Map<String, Object> checkpoint = readMap(instance.getCheckpointJson());
                            String verifyTargetRef = str(checkpoint.get("verifyTargetRef"));
                            observationProviders.observe(new FlowVerifyObservationProvider.Context(
                                            instance.getWorkspaceId(), instance.getId(), instance.getFlowId(),
                                            instance.getFlowCode(), instance.getTriggerId(), verifyTargetRef,
                                            mapValue(checkpoint.get("triggerSnapshot")),
                                            mapValue(checkpoint.get("verifyBefore")),
                                            instance.getStartedAt(), now))
                                    .ifPresent(observation -> {
                                        payload.put("metrics", observation.metrics());
                                        payload.put("source", observation.source());
                                        payload.put("detail", observation.detail());
                                    });
                        }
                        sendSignal(instance.getId(), "VERIFY_DUE", payload, system);
                    }
                    return null;
                });
                advanced++;
            } catch (RuntimeException ex) {
                log.error("自治流程到期推进失败 instance={}", instance.getId(), ex);
            }
        }
        return advanced;
    }

    @Override
    public int cancelActive(String workspaceId, String reason, Caller actor) {
        List<FlowInstance> active = mapper.selectList(new LambdaQueryWrapper<FlowInstance>()
                .eq(FlowInstance::getWorkspaceId, workspaceId)
                .in(FlowInstance::getStatus, List.of("RUNNING", "WAITING_APPROVAL", "WAITING_VERIFY"))
                .last("LIMIT 200"));
        int cancelled = 0;
        for (FlowInstance instance : active) {
            try {
                cancelInstance(instance.getId(), reason, actor);
                cancelled++;
            } catch (RuntimeException ex) {
                log.error("Kill Switch 取消实例失败 instance={}", instance.getId(), ex);
            }
        }
        return cancelled;
    }

    /**
     * 从 current_step_id 开始推进，直到终止、等待审批或等待回检。
     */
    private void advance(FlowInstance instance, String signal, Caller actor) {
        CompiledFlowPlan plan = readPlan(instance);
        List<FlowStep> steps = plan.steps();
        Map<String, Object> checkpoint = readMap(instance.getCheckpointJson());
        List<Map<String, Object>> history = readHistory(instance.getHistoryJson());
        int index = indexOf(steps, instance.getCurrentStepId());
        if (index < 0) {
            index = 0;
        }
        int guard = 0;
        while (index < steps.size()) {
            if (++guard > MAX_STEPS) {
                throw new BizException(ErrorCode.RTM_FLOW_STATE, "Flow 步骤超过安全上限 " + MAX_STEPS);
            }
            FlowStep step = steps.get(index);
            FlowStepHandler handler = handlers.require(step.kind());
            Caller effectiveActor = actor != null ? actor : initiatorOf(instance, checkpoint);
            FlowStepContext ctx = new FlowStepContext(
                    instance.getId(), instance.getWorkspaceId(), plan, step,
                    checkpoint, mapValue(checkpoint.get("triggerSnapshot")),
                    mapValue(checkpoint.get("metrics")), instance.getRunMode(),
                    effectiveActor, signal);
            StepResult result = signal != null && step.id().equals(instance.getCurrentStepId())
                    ? handler.resume(ctx) : handler.execute(ctx);
            checkpoint.putAll(result.checkpointPatch());
            history.add(historyEntry(step, result));
            instance.setCheckpointJson(write(checkpoint));
            instance.setHistoryJson(write(history));
            instance.setUpdatedAt(Instant.now());

            switch (result.outcome()) {
                case CONTINUE -> {
                    index++;
                    if (index >= steps.size()) {
                        finish(instance, "COMPLETED", result.output());
                        return;
                    }
                    instance.setCurrentStepId(steps.get(index).id());
                    instance.setStatus("RUNNING");
                    mapper.updateById(instance);
                    observers.forEach(o -> o.onStepCompleted(toView(instance), step, result));
                    signal = null;
                }
                case WAIT_APPROVAL -> {
                    int timeout = step.timeoutSec() == null || step.timeoutSec() <= 0
                            ? 86_400 : step.timeoutSec();
                    instance.setStatus("WAITING_APPROVAL");
                    instance.setCurrentStepId(step.id());
                    instance.setWakeAt(Instant.now().plusSeconds(timeout));
                    mapper.updateById(instance);
                    observers.forEach(o -> o.onWaiting(toView(instance), step, result));
                    return;
                }
                case WAIT_VERIFY -> {
                    int delay = step.delaySec() == null || step.delaySec() <= 0
                            ? 86_400 : step.delaySec();
                    instance.setStatus("WAITING_VERIFY");
                    instance.setCurrentStepId(step.id());
                    instance.setWakeAt(Instant.now().plusSeconds(delay));
                    mapper.updateById(instance);
                    observers.forEach(o -> o.onWaiting(toView(instance), step, result));
                    return;
                }
                case COMPLETED -> {
                    finish(instance, "COMPLETED", result.output());
                    return;
                }
                case SHADOW_COMPLETED -> {
                    finish(instance, "SHADOW_COMPLETED", result.output());
                    return;
                }
                case FAILED -> {
                    failAndCompensate(instance, plan, effectiveActor,
                            result.reason() == null ? "step failed: " + step.id() : result.reason());
                    return;
                }
                default -> throw new BizException(ErrorCode.RTM_FLOW_STATE,
                        "未知 Flow 步骤结果: " + result.outcome());
            }
        }
        finish(instance, "COMPLETED", Map.of());
    }

    private void finish(FlowInstance instance, String status, Map<String, Object> output) {
        Instant now = Instant.now();
        instance.setStatus(status);
        instance.setFinishedAt(now);
        instance.setUpdatedAt(now);
        instance.setWakeAt(null);
        Map<String, Object> checkpoint = readMap(instance.getCheckpointJson());
        checkpoint.put("output", output == null ? Map.of() : output);
        instance.setCheckpointJson(write(checkpoint));
        mapper.updateById(instance);
        locks.release(instance.getWorkspaceId(), instance.getEntityKey(), instance.getId(),
                now, cooldownOf(instance));
        FlowInstanceView view = toView(instance);
        observers.forEach(o -> o.onCompleted(view, output == null ? Map.of() : output));
    }

    private void failAndCompensate(FlowInstance instance, CompiledFlowPlan plan,
                                   Caller actor, String reason) {
        compensate(instance, plan, actor, reason, "FAILED");
    }

    /**
     * Saga 逆序补偿。只要有一个补偿动作失败，实例进入 COMPENSATION_FAILED，
     * 不再伪装成普通失败：需要人工介入的事实必须显式可见。
     */
    private void compensate(FlowInstance instance, CompiledFlowPlan plan, Caller actor,
                            String reason, String finalStatus) {
        instance.setStatus("COMPENSATING");
        instance.setErrorMessage(truncate(reason, 1000));
        instance.setUpdatedAt(Instant.now());
        mapper.updateById(instance);

        Map<String, Object> checkpoint = readMap(instance.getCheckpointJson());
        List<Map<String, Object>> history = readHistory(instance.getHistoryJson());
        List<FlowStep> executed = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> entry = history.get(i);
            if (!"INVOKE_ACTIVITY".equals(str(entry.get("kind")))
                    || !"CONTINUE".equals(str(entry.get("outcome")))
                    || Boolean.TRUE.equals(entry.get("shadowSkipped"))) {
                continue;
            }
            FlowStep step = findStep(plan, str(entry.get("stepId")));
            if (step != null) {
                executed.add(step);
            }
        }

        boolean failedCompensation = false;
        for (FlowStep step : executed) {
            try {
                FlowStepHandler handler = handlers.require(step.kind());
                Caller effectiveActor = actor != null ? actor : initiatorOf(instance, checkpoint);
                Map<String, Object> result = handler.compensate(new FlowStepContext(
                        instance.getId(), instance.getWorkspaceId(), plan, step,
                        checkpoint, mapValue(checkpoint.get("triggerSnapshot")),
                        mapValue(checkpoint.get("metrics")), instance.getRunMode(),
                        effectiveActor, "COMPENSATE"));
                history.add(Map.of("stepId", step.id(), "kind", step.kind(),
                        "outcome", "COMPENSATED", "result", result == null ? Map.of() : result,
                        "at", Instant.now().toString()));
                observers.forEach(o -> o.onCompensated(toView(instance), step,
                        result == null ? Map.of() : result));
            } catch (RuntimeException ex) {
                failedCompensation = true;
                history.add(Map.of("stepId", step.id(), "kind", step.kind(),
                        "outcome", "COMPENSATION_FAILED",
                        "reason", truncate(ex.getMessage(), 500),
                        "at", Instant.now().toString()));
                log.error("自治流程补偿失败 instance={} step={}", instance.getId(), step.id(), ex);
            }
        }

        Instant now = Instant.now();
        instance.setStatus(failedCompensation ? "COMPENSATION_FAILED" : finalStatus);
        instance.setFinishedAt(now);
        instance.setUpdatedAt(now);
        instance.setWakeAt(null);
        instance.setHistoryJson(write(history));
        checkpoint.put("failureReason", reason);
        instance.setCheckpointJson(write(checkpoint));
        mapper.updateById(instance);
        locks.release(instance.getWorkspaceId(), instance.getEntityKey(), instance.getId(),
                now, cooldownOf(instance));

        FlowInstanceView view = toView(instance);
        observers.forEach(o -> o.onFailed(view, reason));
    }

    private FlowInstance require(String instanceId, Caller actor) {
        FlowInstance instance = mapper.selectById(instanceId);
        if (instance == null) {
            throw new BizException(ErrorCode.RTM_FLOW_NOT_FOUND, "自治流程实例不存在: " + instanceId);
        }
        if (actor != null && !actor.platformAdmin()
                && actor.workspaceId() != null && !actor.workspaceId().equals(instance.getWorkspaceId())) {
            throw new BizException(ErrorCode.RTM_FLOW_NOT_FOUND, "自治流程实例不存在: " + instanceId);
        }
        return instance;
    }

    private Caller initiatorOf(FlowInstance instance, Map<String, Object> checkpoint) {
        String userId = str(checkpoint.get("initiatorUserId"));
        String username = str(checkpoint.get("initiatorUsername"));
        return new Caller(
                userId.isBlank() ? "system" : userId,
                username.isBlank() ? "system" : username,
                instance.getWorkspaceId(), false, Set.of());
    }

    private int cooldownOf(FlowInstance instance) {
        try {
            return readPlan(instance).cooldownSec();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private FlowInstanceView toView(FlowInstance e) {
        return new FlowInstanceView(
                e.getId(), e.getWorkspaceId(), e.getFlowId(), e.getFlowCode(),
                e.getFlowVersion() == null ? 0 : e.getFlowVersion(), e.getTriggerId(), e.getTraceId(),
                e.getStatus(), e.getCurrentStepId(), e.getEntityKey(), e.getRunMode(),
                readMap(e.getCheckpointJson()), readHistory(e.getHistoryJson()),
                e.getWakeAt(), e.getErrorMessage(), e.getStartedAt(), e.getFinishedAt());
    }

    private CompiledFlowPlan readPlan(FlowInstance instance) {
        try {
            return json.readValue(instance.getPlanJson(), CompiledFlowPlan.class);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.RTM_FLOW_STATE, "Flow 实例计划损坏: " + instance.getId());
        }
    }

    private static int indexOf(List<FlowStep> steps, String stepId) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                return i;
            }
        }
        return -1;
    }

    private static FlowStep findStep(CompiledFlowPlan plan, String stepId) {
        for (FlowStep step : plan.steps()) {
            if (step.id().equals(stepId)) {
                return step;
            }
        }
        return null;
    }

    private static Map<String, Object> historyEntry(FlowStep step, StepResult result) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("stepId", step.id());
        entry.put("kind", step.kind());
        entry.put("outcome", result.outcome().name());
        entry.put("reason", result.reason());
        entry.put("output", result.output());
        entry.put("shadowSkipped", Boolean.TRUE.equals(result.output().get("shadowSkipped")));
        entry.put("at", Instant.now().toString());
        return entry;
    }

    private Map<String, Object> readMap(String raw) {
        if (blank(raw)) {
            return new LinkedHashMap<>();
        }
        try {
            return json.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private List<Map<String, Object>> readHistory(String raw) {
        if (blank(raw)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(json.readValue(raw, new TypeReference<List<Map<String, Object>>>() {
            }));
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "序列化 Flow 状态失败");
        }
    }

    private static Map<String, Object> mapValue(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static boolean blank(String raw) {
        return raw == null || raw.isBlank();
    }

    private static String truncate(String raw, int max) {
        if (raw == null) {
            return null;
        }
        return raw.length() <= max ? raw : raw.substring(0, max);
    }
}
