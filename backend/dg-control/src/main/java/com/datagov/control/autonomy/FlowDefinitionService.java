package com.datagov.control.autonomy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagov.common.api.PageResult;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.control.entity.AutonomyControlEntities.FlowDefinition;
import com.datagov.control.entity.AutonomyControlEntities.FlowVersion;
import com.datagov.control.mapper.AuFlowDefMapper;
import com.datagov.control.mapper.AuFlowVersionMapper;
import com.datagov.runtime.autonomy.CompiledFlowPlan;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FlowSpec IR 版本化存储与灰度状态机（P1-4 / D5）。
 *
 * <p>版本只追加不覆写；晋升会生成新版本（同一 spec，不同 run_mode），
 * 因此任何一次 L1_AUTO 执行都能回指到当时的授权证据。
 */
@Service
public class FlowDefinitionService {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DEPRECATED = "DEPRECATED";
    public static final String MODE_SHADOW = "SHADOW";
    public static final String MODE_L2 = "L2_ASSISTED";
    public static final String MODE_L1 = "L1_AUTO";

    private final AuFlowDefMapper flowMapper;
    private final AuFlowVersionMapper versionMapper;
    private final FlowSpecParser parser;
    private final FlowCompiler compiler;
    private final ObjectMapper json;

    public FlowDefinitionService(AuFlowDefMapper flowMapper,
                                 AuFlowVersionMapper versionMapper,
                                 FlowSpecParser parser,
                                 FlowCompiler compiler,
                                 ObjectMapper json) {
        this.flowMapper = flowMapper;
        this.versionMapper = versionMapper;
        this.parser = parser;
        this.compiler = compiler;
        this.json = json;
    }

    public record SaveRequest(
            String flowId,
            String code,
            String name,
            String description,
            String runMode,
            String ownerUsername,
            Map<String, Object> spec
    ) {
    }

    public record FlowView(
            String id,
            String code,
            String name,
            String description,
            String status,
            int currentVersion,
            String runMode,
            String ownerUsername,
            Map<String, Object> spec,
            Map<String, Object> compiled,
            String contentDigest,
            Instant updatedAt
    ) {
    }

    public record VersionView(
            String id,
            int versionNo,
            String runMode,
            String contentDigest,
            String promotionNote,
            Instant compiledAt,
            String compiledBy,
            Map<String, Object> spec
    ) {
    }

    public PageResult<FlowView> list(long page, long size, String keyword, String status, String runMode) {
        String ws = WorkspaceContext.requireWorkspaceId();
        LambdaQueryWrapper<FlowDefinition> q = new LambdaQueryWrapper<FlowDefinition>()
                .eq(FlowDefinition::getWorkspaceId, ws);
        if (status != null && !status.isBlank()) {
            q.eq(FlowDefinition::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        if (runMode != null && !runMode.isBlank()) {
            q.eq(FlowDefinition::getRunMode, runMode.trim().toUpperCase(Locale.ROOT));
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            q.and(w -> w.like(FlowDefinition::getCode, kw)
                    .or().like(FlowDefinition::getName, kw)
                    .or().like(FlowDefinition::getDescription, kw));
        }
        q.orderByDesc(FlowDefinition::getUpdatedAt);
        Page<FlowDefinition> p = flowMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(200, Math.max(1, size))), q);
        return PageResult.of(p.getRecords().stream().map(this::toView).toList(),
                p.getTotal(), p.getCurrent(), p.getSize());
    }

    public FlowView get(String id) {
        return toView(require(WorkspaceContext.requireWorkspaceId(), id));
    }

    public List<VersionView> versions(String flowId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        require(ws, flowId);
        return versionMapper.selectList(new LambdaQueryWrapper<FlowVersion>()
                        .eq(FlowVersion::getWorkspaceId, ws)
                        .eq(FlowVersion::getFlowId, flowId)
                        .orderByDesc(FlowVersion::getVersionNo))
                .stream()
                .map(v -> new VersionView(v.getId(), v.getVersionNo(), v.getRunMode(),
                        v.getContentDigest(), v.getPromotionNote(), v.getCompiledAt(),
                        v.getCompiledBy(), readMap(v.getSpecJson())))
                .toList();
    }

    @Transactional
    public FlowView save(SaveRequest req) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String userId = WorkspaceContext.require().userId();
        if (req == null || req.spec() == null) {
            throw new BizException(ErrorCode.CTL_FLOW_COMPILE_FAILED, "spec 不能为空");
        }
        FlowSpec spec = parser.parse(req.spec());
        String code = req.code() == null || req.code().isBlank() ? spec.flowCode() : req.code();
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.CTL_FLOW_COMPILE_FAILED, "flowCode / code 必填");
        }
        String name = req.name() == null || req.name().isBlank() ? code : req.name();

        FlowDefinition existing = req.flowId() == null || req.flowId().isBlank()
                ? findByCode(ws, code) : require(ws, req.flowId());
        boolean create = existing == null;
        if (create) {
            Long dup = flowMapper.selectCount(new LambdaQueryWrapper<FlowDefinition>()
                    .eq(FlowDefinition::getWorkspaceId, ws)
                    .apply("lower(code) = {0}", code.trim().toLowerCase(Locale.ROOT)));
            if (dup != null && dup > 0) {
                throw new BizException(ErrorCode.CTL_FLOW_STATE, "自治流程编码已存在: " + code);
            }
        }

        int nextVersion = create ? 1 : (existing.getCurrentVersion() == null ? 0 : existing.getCurrentVersion()) + 1;
        String requestedMode = req.runMode() == null || req.runMode().isBlank()
                ? (spec.runMode() == null ? MODE_SHADOW : spec.runMode())
                : req.runMode();
        FlowCompileResultHolder compiled = compile(ws, spec, create ? "pending" : existing.getId(),
                nextVersion, requestedMode);

        boolean specChanged = create || !compiled.plan().contentDigest()
                .equals(latestVersion(ws, existing.getId()).getContentDigest());
        if ((create || specChanged) && !MODE_SHADOW.equals(compiled.plan().runMode())) {
            throw new BizException(ErrorCode.CTL_FLOW_STATE,
                    "新建或变更后的 FlowSpec 必须以 SHADOW 启动；晋升请走受控 promote 端点");
        }
        String effectiveMode = create || specChanged
                ? MODE_SHADOW : (existing.getRunMode() == null ? MODE_SHADOW : existing.getRunMode());

        Instant now = Instant.now();
        FlowDefinition flow = create ? new FlowDefinition() : existing;
        if (create) {
            flow.setId(Ids.of("afl"));
            flow.setWorkspaceId(ws);
            flow.setCreatedAt(now);
            flow.setCreatedBy(userId);
            flow.setStatus(STATUS_DRAFT);
        }
        flow.setCode(code.trim());
        flow.setName(name.trim());
        flow.setDescription(req.description());
        flow.setCurrentVersion(nextVersion);
        flow.setRunMode(effectiveMode);
        flow.setOwnerUsername(req.ownerUsername());
        flow.setUpdatedAt(now);
        flow.setUpdatedBy(userId);
        if (create) {
            flowMapper.insert(flow);
        } else {
            flowMapper.updateById(flow);
        }

        // 重新编译，确保 flowId 被写进 CompiledFlowPlan
        CompiledFlowPlan plan = compiler.compileOrThrow(ws, spec, flow.getId(), nextVersion, effectiveMode);
        FlowVersion version = new FlowVersion();
        version.setId(Ids.of("afv"));
        version.setWorkspaceId(ws);
        version.setFlowId(flow.getId());
        version.setVersionNo(nextVersion);
        version.setSpecJson(write(spec));
        version.setCompiledJson(write(plan));
        version.setContentDigest(plan.contentDigest());
        version.setRunMode(effectiveMode);
        version.setPromotionNote("compiled");
        version.setCompiledAt(now);
        version.setCompiledBy(userId);
        versionMapper.insert(version);
        return toView(flow);
    }

    @Transactional
    public FlowView publish(String flowId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        FlowDefinition flow = require(ws, flowId);
        if (flow.getCurrentVersion() == null || flow.getCurrentVersion() < 1) {
            throw new BizException(ErrorCode.CTL_FLOW_STATE, "流程尚未编译，无法发布");
        }
        latestVersion(ws, flowId);
        flow.setStatus(STATUS_PUBLISHED);
        flow.setUpdatedAt(Instant.now());
        flow.setUpdatedBy(WorkspaceContext.require().userId());
        flowMapper.updateById(flow);
        return toView(flow);
    }

    @Transactional
    public FlowView deprecate(String flowId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        FlowDefinition flow = require(ws, flowId);
        flow.setStatus(STATUS_DEPRECATED);
        flow.setUpdatedAt(Instant.now());
        flow.setUpdatedBy(WorkspaceContext.require().userId());
        flowMapper.updateById(flow);
        return toView(flow);
    }

    /**
     * 受控灰度变更。调用方（装配层）必须先验证 DecisionTrace 证据。
     * 这里只做状态机合法性校验，并追加新版本保留历史。
     */
    @Transactional
    public FlowView applyRunMode(String flowId, String targetMode, String note, String operatorId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        FlowDefinition flow = require(ws, flowId);
        String target = targetMode == null ? "" : targetMode.trim().toUpperCase(Locale.ROOT);
        if (!FlowCompiler.RUN_MODES.contains(target)) {
            throw new BizException(ErrorCode.CTL_FLOW_STATE, "非法 runMode: " + targetMode);
        }
        String current = flow.getRunMode() == null ? MODE_SHADOW : flow.getRunMode();
        if (!MODE_SHADOW.equals(target) && !STATUS_PUBLISHED.equals(flow.getStatus())) {
            throw new BizException(ErrorCode.CTL_FLOW_STATE,
                    "只有已发布流程才能晋升灰度模式");
        }
        if (target.equals(current)) {
            return toView(flow);
        }
        boolean allowed = MODE_SHADOW.equals(target)
                || (MODE_SHADOW.equals(current) && MODE_L2.equals(target))
                || (MODE_L2.equals(current) && MODE_L1.equals(target));
        if (!allowed) {
            throw new BizException(ErrorCode.CTL_FLOW_STATE,
                    "不允许跳过灰度阶段: " + current + " → " + target);
        }
        FlowVersion latest = latestVersion(ws, flowId);
        int nextVersion = (flow.getCurrentVersion() == null ? 0 : flow.getCurrentVersion()) + 1;
        Instant now = Instant.now();
        CompiledFlowPlan latestPlan = readPlan(latest.getCompiledJson());
        CompiledFlowPlan promotedPlan = new CompiledFlowPlan(
                latestPlan.flowId(), latestPlan.flowCode(), nextVersion, target,
                latestPlan.entityKeyExpr(), latestPlan.cooldownSec(), latestPlan.arbiterPolicyRef(),
                latestPlan.steps(), latestPlan.planJson(), latestPlan.contentDigest(), latestPlan.refs());
        FlowVersion version = new FlowVersion();
        version.setId(Ids.of("afv"));
        version.setWorkspaceId(ws);
        version.setFlowId(flowId);
        version.setVersionNo(nextVersion);
        version.setSpecJson(latest.getSpecJson());
        version.setCompiledJson(write(promotedPlan));
        version.setContentDigest(latest.getContentDigest());
        version.setRunMode(target);
        version.setPromotionNote(note);
        version.setCompiledAt(now);
        version.setCompiledBy(operatorId);
        versionMapper.insert(version);

        flow.setRunMode(target);
        flow.setCurrentVersion(nextVersion);
        flow.setUpdatedAt(now);
        flow.setUpdatedBy(operatorId);
        flowMapper.updateById(flow);
        return toView(flow);
    }

    /** Kill Switch：把当前空间所有非 SHADOW 流程一键降级。 */
    @Transactional
    public int demoteAllToShadow(String operatorId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        List<FlowDefinition> flows = flowMapper.selectList(new LambdaQueryWrapper<FlowDefinition>()
                .eq(FlowDefinition::getWorkspaceId, ws)
                .ne(FlowDefinition::getRunMode, MODE_SHADOW));
        for (FlowDefinition flow : flows) {
            applyRunMode(flow.getId(), MODE_SHADOW, "Kill Switch 全局降级", operatorId);
        }
        return flows.size();
    }

    public CompiledFlowPlan findCompiledPlan(String flowId) {
        String ws = WorkspaceContext.requireWorkspaceId();
        FlowDefinition flow = require(ws, flowId);
        if (!STATUS_PUBLISHED.equals(flow.getStatus())) {
            throw new BizException(ErrorCode.CTL_FLOW_STATE, "流程未发布，触发器不能绑定: " + flowId);
        }
        FlowVersion latest = latestVersion(ws, flowId);
        return readPlan(latest.getCompiledJson());
    }

    private CompiledFlowPlan readPlan(String compiledJson) {
        try {
            return json.readValue(compiledJson, CompiledFlowPlan.class);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.CTL_FLOW_STATE, "流程编译产物损坏");
        }
    }

    public FlowDefinition require(String ws, String flowId) {
        FlowDefinition flow = flowMapper.selectOne(new LambdaQueryWrapper<FlowDefinition>()
                .eq(FlowDefinition::getWorkspaceId, ws)
                .eq(FlowDefinition::getId, flowId));
        if (flow == null) {
            throw new BizException(ErrorCode.CTL_FLOW_NOT_FOUND, "自治流程不存在: " + flowId);
        }
        return flow;
    }

    private FlowDefinition findByCode(String ws, String code) {
        return flowMapper.selectOne(new LambdaQueryWrapper<FlowDefinition>()
                .eq(FlowDefinition::getWorkspaceId, ws)
                .apply("lower(code) = {0}", code.trim().toLowerCase(Locale.ROOT))
                .last("LIMIT 1"));
    }

    private FlowVersion latestVersion(String ws, String flowId) {
        FlowVersion version = versionMapper.selectOne(new LambdaQueryWrapper<FlowVersion>()
                .eq(FlowVersion::getWorkspaceId, ws)
                .eq(FlowVersion::getFlowId, flowId)
                .orderByDesc(FlowVersion::getVersionNo)
                .last("LIMIT 1"));
        if (version == null) {
            throw new BizException(ErrorCode.CTL_FLOW_STATE, "流程尚无编译版本: " + flowId);
        }
        return version;
    }

    private FlowView toView(FlowDefinition flow) {
        String ws = flow.getWorkspaceId();
        FlowVersion latest = null;
        try {
            latest = latestVersion(ws, flow.getId());
        } catch (BizException ignored) {
            // 新建但编译失败的流程允许列表展示
        }
        return new FlowView(flow.getId(), flow.getCode(), flow.getName(), flow.getDescription(),
                flow.getStatus(), flow.getCurrentVersion() == null ? 0 : flow.getCurrentVersion(),
                flow.getRunMode(), flow.getOwnerUsername(),
                latest == null ? Map.of() : readMap(latest.getSpecJson()),
                latest == null ? Map.of() : readMap(latest.getCompiledJson()),
                latest == null ? null : latest.getContentDigest(),
                flow.getUpdatedAt());
    }

    private record FlowCompileResultHolder(CompiledFlowPlan plan) {
    }

    private FlowCompileResultHolder compile(String ws, FlowSpec spec, String flowId,
                                            int version, String requestedMode) {
        FlowCompiler.FlowCompileResult result = compiler.compile(ws, spec, flowId, version, requestedMode);
        if (!result.ok()) {
            String message = result.diagnostics().stream()
                    .map(d -> d.field() + ": " + d.message())
                    .reduce((a, b) -> a + "; " + b).orElse("FlowSpec 编译失败");
            throw new BizException(ErrorCode.CTL_FLOW_COMPILE_FAILED, message);
        }
        return new FlowCompileResultHolder(result.plan());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "序列化 FlowSpec 失败");
        }
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
}
