package com.datagov.control.autonomy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.audit.ReproducibilityFingerprint;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.mapper.SemanticObjectMapper;
import com.datagov.runtime.autonomy.CompiledFlowPlan;
import com.datagov.runtime.autonomy.FlowStep;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * FlowSpec IR 静态编译器（data-driven-ops-profile P1-4 / D3）。
 *
 * <p>编译期闸门（任一失败都不得进入 Runtime）：
 * <ol>
 *   <li>存在性：targetRef / actionTypeRef / metricRef 必须 PUBLISHED；</li>
 *   <li>C6：EXTERNAL_EXEMPTED 必须显式开启租户白名单；</li>
 *   <li>包络与补偿：L1+ 动作必须有参数包络与配对补偿；</li>
 *   <li>防震荡：entityKeyExpr 与 cooldownSec &gt; 0；</li>
 *   <li>灰度：新流程/变更默认 SHADOW，由 FlowDefinitionService 管控晋升。</li>
 * </ol>
 */
@Service
public class FlowCompiler {

    public static final String SCHEMA_VERSION = "flow.ir/v1";
    public static final Set<String> RUN_MODES = Set.of("SHADOW", "L2_ASSISTED", "L1_AUTO");
    public static final Set<String> STEP_KINDS = Set.of(
            "CAPTURE_CONTEXT", "CHECK_INVARIANT", "DRY_RUN_ACTION", "ARBITER_DECIDE",
            "WAIT_HUMAN_SIGNAL", "INVOKE_ACTIVITY", "DELAYED_VERIFY");

    private final SemanticObjectMapper semanticObjectMapper;
    private final ObjectMapper json;
    private final boolean externalActionsEnabled;
    private final Set<String> externalWhitelist;
    private final Set<String> allowedActivities;

    public FlowCompiler(SemanticObjectMapper semanticObjectMapper,
                        ObjectMapper json,
                        @Value("${dg.autonomy.external-actions-enabled:false}") boolean externalActionsEnabled,
                        @Value("${dg.autonomy.external-action-whitelist:}") String externalWhitelist,
                        @Value("${dg.autonomy.allowed-activities:control.job.pause,control.job.resume,"
                                + "metadata.quality.run,governance.report.generate}") String allowedActivities) {
        this.semanticObjectMapper = semanticObjectMapper;
        this.json = json;
        this.externalActionsEnabled = externalActionsEnabled;
        this.externalWhitelist = commaSet(externalWhitelist);
        this.allowedActivities = commaSet(allowedActivities);
    }

    public record Diagnostic(String code, String field, String message) {
    }

    public record FlowCompileResult(
            boolean ok,
            CompiledFlowPlan plan,
            List<Diagnostic> diagnostics,
            Map<String, Object> refs
    ) {
        public FlowCompileResult {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            refs = refs == null ? Map.of() : Map.copyOf(refs);
        }
    }

    public FlowCompileResult compile(String workspaceId, FlowSpec spec, String flowId,
                                     int version, String requestedRunMode) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, Object> refs = new LinkedHashMap<>();
        String runMode = normalizeRunMode(
                requestedRunMode == null || requestedRunMode.isBlank() ? spec.runMode() : requestedRunMode,
                diagnostics);

        if (!SCHEMA_VERSION.equals(spec.schemaVersion())) {
            diagnostics.add(new Diagnostic("SCHEMA_VERSION", "schemaVersion",
                    "只支持 " + SCHEMA_VERSION + "，实际: " + spec.schemaVersion()));
        }
        if (blank(spec.flowCode())) {
            diagnostics.add(new Diagnostic("FLOW_CODE", "flowCode", "flowCode 必填"));
        }
        if (blank(spec.entityKeyExpr()) || !spec.entityKeyExpr().startsWith("trigger.")) {
            diagnostics.add(new Diagnostic("ENTITY_KEY", "entityKeyExpr",
                    "entityKeyExpr 必须是非空 trigger.* 表达式"));
        }
        if (spec.cooldownSec() == null || spec.cooldownSec() <= 0) {
            diagnostics.add(new Diagnostic("COOLDOWN", "cooldownSec",
                    "cooldownSec 必须 > 0（防震荡闸门）"));
        }
        if (spec.steps().isEmpty()) {
            diagnostics.add(new Diagnostic("STEPS", "steps", "至少需要一个步骤"));
        }

        Set<String> stepIds = new HashSet<>();
        Set<String> dryRunRefs = new HashSet<>();
        boolean invariantSeen = false;
        Map<String, ActionSpec> actionSpecs = new LinkedHashMap<>();
        for (int i = 0; i < spec.steps().size(); i++) {
            FlowSpec.Step step = spec.steps().get(i);
            String field = "steps[" + i + "]";
            if (blank(step.id())) {
                diagnostics.add(new Diagnostic("STEP_ID", field + ".id", "step.id 必填"));
            } else if (!stepIds.add(step.id())) {
                diagnostics.add(new Diagnostic("STEP_DUPLICATE", field + ".id",
                        "step.id 重复: " + step.id()));
            }
            if (blank(step.kind()) || !STEP_KINDS.contains(step.kind())) {
                diagnostics.add(new Diagnostic("STEP_KIND", field + ".kind",
                        "不支持的步骤类型: " + step.kind()));
                continue;
            }
            switch (step.kind()) {
                case "CAPTURE_CONTEXT" -> {
                    if (step.metrics().isEmpty()) {
                        diagnostics.add(new Diagnostic("CAPTURE_METRICS", field + ".metrics",
                                "CAPTURE_CONTEXT 至少声明一个 metricRef"));
                    }
                }
                case "CHECK_INVARIANT" -> {
                    invariantSeen = true;
                    SemanticObject target = requireRef(workspaceId, step.targetRef(), "TARGET",
                            field + ".targetRef", diagnostics);
                    if (target != null) {
                        refs.put("invariantTarget:" + step.id(), target.getId());
                        validateTargetRefs(workspaceId, target, field + ".targetRef", refs, diagnostics);
                    }
                }
                case "DRY_RUN_ACTION" -> {
                    if (!invariantSeen) {
                        diagnostics.add(new Diagnostic("INVARIANT_ORDER", field,
                                "动作步骤之前必须先有 CHECK_INVARIANT（硬不变量一票否决）"));
                    }
                    SemanticObject action = requireRef(workspaceId, step.actionTypeRef(), "ACTION_TYPE",
                            field + ".actionTypeRef", diagnostics);
                    if (action != null) {
                        ActionSpec actionSpec = actionSpec(workspaceId, action, field, refs, diagnostics);
                        actionSpecs.put(step.id(), actionSpec);
                        dryRunRefs.add(action.getId());
                        dryRunRefs.add(action.getCode());
                        if ("NONE".equals(actionSpec.dryRunMode())) {
                            diagnostics.add(new Diagnostic("DRY_RUN_MODE", field + ".actionTypeRef",
                                    "DRY_RUN_ACTION 引用的 ActionType 声明了 dryRunMode=NONE"));
                        }
                    }
                }
                case "INVOKE_ACTIVITY" -> {
                    if (!invariantSeen) {
                        diagnostics.add(new Diagnostic("INVARIANT_ORDER", field,
                                "动作步骤之前必须先有 CHECK_INVARIANT（硬不变量一票否决）"));
                    }
                    SemanticObject action = requireRef(workspaceId, step.actionTypeRef(), "ACTION_TYPE",
                            field + ".actionTypeRef", diagnostics);
                    if (action != null) {
                        ActionSpec actionSpec = actionSpec(workspaceId, action, field, refs, diagnostics);
                        actionSpecs.put(step.id(), actionSpec);
                        if (!"SHADOW".equals(runMode)
                                && !dryRunRefs.contains(action.getId())
                                && !dryRunRefs.contains(action.getCode())) {
                            diagnostics.add(new Diagnostic("DRY_RUN_REQUIRED", field,
                                    "灰度/自动执行前必须先有同 ActionType 的 DRY_RUN_ACTION"));
                        }
                        if (!"SHADOW".equals(runMode)
                                && !"L0_ADVISORY".equals(actionSpec.authLevel())) {
                            if (blank(step.compensateWith())) {
                                diagnostics.add(new Diagnostic("COMPENSATION", field + ".compensateWith",
                                        "灰度/自动执行的动作必须声明 compensateWith"));
                            } else {
                                requireRef(workspaceId, step.compensateWith(), "ACTION_TYPE",
                                        field + ".compensateWith", diagnostics);
                            }
                        }
                    }
                }
                case "WAIT_HUMAN_SIGNAL" -> {
                    if (step.timeoutSec() == null || step.timeoutSec() <= 0) {
                        diagnostics.add(new Diagnostic("APPROVAL_TIMEOUT", field + ".timeoutSec",
                                "WAIT_HUMAN_SIGNAL 必须声明正数 timeoutSec"));
                    }
                }
                case "DELAYED_VERIFY" -> {
                    if (step.delaySec() == null || step.delaySec() <= 0) {
                        diagnostics.add(new Diagnostic("VERIFY_DELAY", field + ".delaySec",
                                "DELAYED_VERIFY 必须声明正数 delaySec"));
                    }
                    SemanticObject verifyTarget = requireRef(workspaceId, step.verifyTargetRef(), "TARGET",
                            field + ".verifyTargetRef", diagnostics);
                    if (verifyTarget != null) {
                        refs.put("verifyTarget:" + step.id(), verifyTarget.getId());
                    }
                }
                case "ARBITER_DECIDE" -> {
                    // 无额外静态引用；fallbackToL2 由框架按策略使用
                }
                default -> {
                }
            }
        }
        if (actionSpecs.isEmpty()) {
            // 只有观察步骤的流程允许存在，但 MVP 明确记录：它不会产生副作用。
            refs.put("sideEffectFree", true);
        }

        if (!diagnostics.isEmpty()) {
            return new FlowCompileResult(false, null, diagnostics, refs);
        }

        List<FlowStep> steps = spec.steps().stream()
                .map(s -> new FlowStep(s.id(), s.kind(), s.metrics(), s.targetRef(), s.actionTypeRef(),
                        Boolean.TRUE.equals(s.fallbackToL2()), s.timeoutSec(), s.skipWhen(),
                        s.compensateWith(), s.delaySec(), s.verifyTargetRef(), s.params()))
                .toList();
        String planJson = write(spec);
        String digest = sha256(planJson);
        CompiledFlowPlan plan = new CompiledFlowPlan(
                flowId, spec.flowCode(), version, runMode, spec.entityKeyExpr(),
                spec.cooldownSec(), spec.arbiterPolicyRef(), steps, planJson, digest, refs);
        return new FlowCompileResult(true, plan, List.of(), refs);
    }

    public CompiledFlowPlan compileOrThrow(String workspaceId, FlowSpec spec, String flowId,
                                           int version, String runMode) {
        FlowCompileResult result = compile(workspaceId, spec, flowId, version, runMode);
        if (!result.ok()) {
            String message = result.diagnostics().stream()
                    .map(d -> d.field() + ": " + d.message())
                    .reduce((a, b) -> a + "; " + b).orElse("FlowSpec 编译失败");
            throw new BizException(ErrorCode.CTL_FLOW_COMPILE_FAILED, message);
        }
        return result.plan();
    }

    private String normalizeRunMode(String raw, List<Diagnostic> diagnostics) {
        String mode = raw == null || raw.isBlank() ? "SHADOW" : raw.trim().toUpperCase(Locale.ROOT);
        if (!RUN_MODES.contains(mode)) {
            diagnostics.add(new Diagnostic("RUN_MODE", "runMode",
                    "runMode 必须是 SHADOW / L2_ASSISTED / L1_AUTO"));
            return "SHADOW";
        }
        return mode;
    }

    private SemanticObject requireRef(String workspaceId, String ref, String type, String field,
                                      List<Diagnostic> diagnostics) {
        if (blank(ref)) {
            diagnostics.add(new Diagnostic("REF_REQUIRED", field, field + " 必填"));
            return null;
        }
        SemanticObject object = semanticObjectMapper.selectOne(new LambdaQueryWrapper<SemanticObject>()
                .and(w -> w.eq(SemanticObject::getWorkspaceId, workspaceId)
                        .or(g -> g.eq(SemanticObject::getScope, "GLOBAL")
                                .eq(SemanticObject::getStatus, "PUBLISHED")))
                .eq(SemanticObject::getObjectType, type)
                .eq(SemanticObject::getStatus, "PUBLISHED")
                .and(w -> w.eq(SemanticObject::getId, ref)
                        .or().eq(SemanticObject::getCode, ref))
                .last("LIMIT 1"));
        if (object == null) {
            diagnostics.add(new Diagnostic("REF_NOT_FOUND", field,
                    type + " 引用未发布或不存在: " + ref));
        }
        return object;
    }

    private ActionSpec actionSpec(String workspaceId, SemanticObject action, String field,
                                  Map<String, Object> refs, List<Diagnostic> diagnostics) {
        Map<String, Object> spec = readMap(action.getSpecJson());
        String authLevel = str(spec.get("authLevel")).toUpperCase(Locale.ROOT);
        String domainScope = str(spec.get("domainScope")).toUpperCase(Locale.ROOT);
        String activityRef = str(spec.get("activityRef"));
        String compensating = str(spec.get("compensatingActionCode"));
        Map<String, Object> envelope = mapValue(spec.get("paramEnvelope"));
        List<Object> required = listValue(envelope.get("required"));
        int cooldown = intValue(spec.get("defaultCooldownSec"), 0);

        refs.put("actionType:" + action.getCode(), action.getId());
        if (blank(authLevel) || !Set.of("L0_ADVISORY", "L1_AUTO", "L2_APPROVAL", "L3_DUAL_APPROVAL")
                .contains(authLevel)) {
            diagnostics.add(new Diagnostic("ACTION_AUTH", field + ".authLevel",
                    "ActionType " + action.getCode() + " 的 authLevel 非法: " + authLevel));
        }
        if (!"PLATFORM_INTERNAL".equals(domainScope) && !"EXTERNAL_EXEMPTED".equals(domainScope)) {
            diagnostics.add(new Diagnostic("ACTION_SCOPE", field + ".domainScope",
                    "ActionType " + action.getCode() + " 的 domainScope 非法: " + domainScope));
        }
        if ("EXTERNAL_EXEMPTED".equals(domainScope)) {
            boolean allowed = externalActionsEnabled
                    && (externalWhitelist.contains("*") || externalWhitelist.contains(workspaceId));
            if (!allowed) {
                diagnostics.add(new Diagnostic("C6_EXTERNAL_SCOPE", field + ".domainScope",
                        "C6 红线：外部系统写回系统级默认关闭，当前空间未获白名单豁免"));
            }
        }
        if (blank(activityRef) || !allowedActivities.contains(activityRef)) {
            diagnostics.add(new Diagnostic("ACTIVITY_REF", field + ".activityRef",
                    "不在平台允许的原子 Activity 白名单内: " + activityRef));
        }
        boolean needsEnvelope = !"L0_ADVISORY".equals(authLevel);
        if (needsEnvelope && required.isEmpty()) {
            diagnostics.add(new Diagnostic("PARAM_ENVELOPE", field + ".paramEnvelope",
                    "ActionType " + action.getCode() + " 缺少参数包络 required"));
        }
        if (needsEnvelope && blank(compensating)) {
            diagnostics.add(new Diagnostic("COMPENSATION_CONTRACT", field + ".compensatingActionCode",
                    "ActionType " + action.getCode() + " 缺少配对补偿动作"));
        }
        return new ActionSpec(authLevel, domainScope, activityRef, compensating,
                !required.isEmpty(), cooldown, str(spec.get("dryRunMode")).toUpperCase(java.util.Locale.ROOT));
    }

    private void validateTargetRefs(String workspaceId, SemanticObject target, String field,
                                    Map<String, Object> refs, List<Diagnostic> diagnostics) {
        Map<String, Object> spec = readMap(target.getSpecJson());
        String entityRef = str(spec.get("targetEntityRef"));
        if (!entityRef.isBlank()) {
            requireRef(workspaceId, entityRef, Set.of("ENTITY", "PROCESS"), field + ".targetEntityRef",
                    diagnostics);
        }
        for (Map<String, Object> obj : mapList(spec.get("objectives"))) {
            String metricRef = str(obj.get("metricRef"));
            SemanticObject metric = requireRef(workspaceId, metricRef, "METRIC",
                    field + ".objectives.metricRef", diagnostics);
            if (metric != null) {
                refs.put("metric:" + metricRef, metric.getId());
            }
        }
        for (Map<String, Object> inv : mapList(spec.get("invariants"))) {
            String metricRef = str(inv.get("metricRef"));
            if (!metricRef.isBlank()) {
                SemanticObject metric = requireRef(workspaceId, metricRef, "METRIC",
                        field + ".invariants.metricRef", diagnostics);
                if (metric != null) {
                    refs.put("metric:" + metricRef, metric.getId());
                }
            }
        }
    }

    private SemanticObject requireRef(String workspaceId, String ref, Set<String> types, String field,
                                      List<Diagnostic> diagnostics) {
        if (blank(ref)) {
            diagnostics.add(new Diagnostic("REF_REQUIRED", field, field + " 必填"));
            return null;
        }
        SemanticObject object = semanticObjectMapper.selectOne(new LambdaQueryWrapper<SemanticObject>()
                .and(w -> w.eq(SemanticObject::getWorkspaceId, workspaceId)
                        .or(g -> g.eq(SemanticObject::getScope, "GLOBAL")
                                .eq(SemanticObject::getStatus, "PUBLISHED")))
                .in(SemanticObject::getObjectType, types)
                .eq(SemanticObject::getStatus, "PUBLISHED")
                .and(w -> w.eq(SemanticObject::getId, ref)
                        .or().eq(SemanticObject::getCode, ref))
                .last("LIMIT 1"));
        if (object == null) {
            diagnostics.add(new Diagnostic("REF_NOT_FOUND", field,
                    "引用未发布或不存在: " + ref + "（期望 " + types + "）"));
        }
        return object;
    }

    private record ActionSpec(String authLevel, String domainScope, String activityRef,
                              String compensatingActionCode, boolean hasEnvelope, int cooldownSec,
                              String dryRunMode) {
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.CTL_FLOW_COMPILE_FAILED, "序列化 FlowSpec 失败");
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

    private static Map<String, Object> mapValue(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static List<Object> listValue(Object raw) {
        return raw instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    private static int intValue(Object raw, int fallback) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private static boolean blank(String raw) {
        return raw == null || raw.isBlank();
    }

    private static Set<String> commaSet(String raw) {
        if (blank(raw)) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(out::add);
        return out;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** 生成 C8 可复现指纹；被 DecisionTrace 复用。 */
    public static ReproducibilityFingerprint fingerprint(CompiledFlowPlan plan, String workspaceId,
                                                          String actorId, Instant now) {
        return new ReproducibilityFingerprint(
                1,
                List.of(Map.of("workspaceId", workspaceId)),
                List.of(Map.of("flowCode", plan.flowCode(), "version", String.valueOf(plan.flowVersion()),
                        "digest", plan.contentDigest())),
                List.of(),
                List.of(Map.of("operatorId", "FlowCompiler", "version", "1", "digest", plan.contentDigest())),
                plan.contentDigest(),
                plan.arbiterPolicyRef() == null ? "arbiter_default" : plan.arbiterPolicyRef(),
                List.of(Map.of("principalType", "USER", "id", actorId == null ? "system" : actorId)),
                Map.of("truncated", false, "maxSteps", plan.steps().size()),
                now.toString());
    }

    public static String sha256Hex(String input) {
        return sha256(input);
    }
}
