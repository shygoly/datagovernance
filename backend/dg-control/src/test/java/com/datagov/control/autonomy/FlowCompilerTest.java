package com.datagov.control.autonomy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.mapper.SemanticObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlowCompilerTest {

    private SemanticObjectMapper mapper;
    private FlowCompiler compiler;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        mapper = mock(SemanticObjectMapper.class);
        json = new ObjectMapper();
        compiler = new FlowCompiler(mapper, json, false, "", "control.job.pause,control.job.resume");
    }

    @Test
    @DisplayName("合法 FlowSpec 通过全部静态闸门并产出 CompiledFlowPlan")
    void compilesValidFlow() {
        SemanticObject target = object("sem_target", "target_core", "TARGET",
                """
                {"targetEntityRef":"entity_sync","invariants":[
                  {"code":"INV_FRESH","metricRef":"metric_freshness","operator":"LTE","threshold":300,"severity":"HARD_BLOCK"}
                ],"objectives":[{"metricRef":"metric_freshness","direction":"MINIMIZE","threshold":300,"weight":1}]}
                """);
        SemanticObject entity = object("sem_entity", "entity_sync", "ENTITY", "{}");
        SemanticObject metric = object("sem_metric", "metric_freshness", "METRIC", "{}");
        SemanticObject pause = object("sem_action_pause", "action_pause", "ACTION_TYPE",
                """
                {"actionCode":"PAUSE_SYNC_JOB","authLevel":"L1_AUTO","domainScope":"PLATFORM_INTERNAL",
                 "activityRef":"control.job.pause","paramEnvelope":{"required":["jobId"]},
                 "compensatingActionCode":"action_resume","defaultCooldownSec":1800}
                """);
        SemanticObject resume = object("sem_action_resume", "action_resume", "ACTION_TYPE",
                """
                {"actionCode":"RESUME_SYNC_JOB","authLevel":"L1_AUTO","domainScope":"PLATFORM_INTERNAL",
                 "activityRef":"control.job.resume","paramEnvelope":{"required":["jobId"]},
                 "compensatingActionCode":"action_pause","defaultCooldownSec":0}
                """);
        when(mapper.selectOne(any())).thenReturn(target, entity, metric, pause, pause);

        FlowSpec spec = parser().parse(spec("SHADOW", "action_resume"));
        FlowCompiler.FlowCompileResult result = compiler.compile(
                "ws1", spec, "flow1", 1, "SHADOW");

        assertThat(result.ok()).isTrue();
        assertThat(result.plan()).isNotNull();
        assertThat(result.plan().steps()).hasSize(4);
        assertThat(result.plan().runMode()).isEqualTo("SHADOW");
        assertThat(result.plan().contentDigest()).hasSize(64);
    }

    @Test
    @DisplayName("C6：外部写回未开启白名单时编译失败")
    void rejectsExternalScopeByDefault() {
        SemanticObject target = object("sem_target", "target_core", "TARGET",
                """
                {"targetEntityRef":"entity_sync","invariants":[
                  {"code":"INV_FRESH","metricRef":"metric_freshness","operator":"LTE","threshold":300}
                ]}
                """);
        SemanticObject entity = object("sem_entity", "entity_sync", "ENTITY", "{}");
        SemanticObject metric = object("sem_metric", "metric_freshness", "METRIC", "{}");
        SemanticObject action = object("sem_action", "action_pause", "ACTION_TYPE",
                """
                {"actionCode":"PAUSE_SYNC_JOB","authLevel":"L1_AUTO","domainScope":"EXTERNAL_EXEMPTED",
                 "activityRef":"control.job.pause","paramEnvelope":{"required":["jobId"]},
                 "compensatingActionCode":"action_resume"}
                """);
        SemanticObject resume = object("sem_action2", "action_resume", "ACTION_TYPE",
                """
                {"actionCode":"RESUME_SYNC_JOB","authLevel":"L1_AUTO","domainScope":"PLATFORM_INTERNAL",
                 "activityRef":"control.job.resume","paramEnvelope":{"required":["jobId"]},
                 "compensatingActionCode":"action_pause"}
                """);
        when(mapper.selectOne(any())).thenReturn(target, entity, metric, action, action);

        FlowCompiler.FlowCompileResult result = compiler.compile(
                "ws1", parser().parse(spec("SHADOW", "action_resume")), "flow1", 1, "SHADOW");

        assertThat(result.ok()).isFalse();
        assertThat(result.diagnostics()).anyMatch(d -> "C6_EXTERNAL_SCOPE".equals(d.code()));
    }

    @Test
    @DisplayName("L2 动作缺少配对补偿时编译失败")
    void rejectsMissingCompensation() {
        SemanticObject target = object("sem_target", "target_core", "TARGET",
                """
                {"targetEntityRef":"entity_sync","invariants":[
                  {"code":"INV_FRESH","metricRef":"metric_freshness","operator":"LTE","threshold":300}
                ]}
                """);
        SemanticObject entity = object("sem_entity", "entity_sync", "ENTITY", "{}");
        SemanticObject metric = object("sem_metric", "metric_freshness", "METRIC", "{}");
        SemanticObject action = object("sem_action", "action_pause", "ACTION_TYPE",
                """
                {"actionCode":"PAUSE_SYNC_JOB","authLevel":"L2_APPROVAL","domainScope":"PLATFORM_INTERNAL",
                 "activityRef":"control.job.pause","paramEnvelope":{"required":["jobId"]},
                 "compensatingActionCode":"action_resume"}
                """);
        when(mapper.selectOne(any())).thenReturn(target, entity, metric, action, action);

        FlowSpec spec = parser().parse(spec("L2_ASSISTED", null));
        FlowCompiler.FlowCompileResult result = compiler.compile("ws1", spec, "flow1", 2, "L2_ASSISTED");

        assertThat(result.ok()).isFalse();
        assertThat(result.diagnostics()).anyMatch(d -> "COMPENSATION".equals(d.code()));
    }

    private FlowSpecParser parser() {
        return new FlowSpecParser(json);
    }

    private static Map<String, Object> spec(String mode, String compensateWith) {
        Map<String, Object> execute = new java.util.LinkedHashMap<>();
        execute.put("id", "s3_execute");
        execute.put("kind", "INVOKE_ACTIVITY");
        execute.put("actionTypeRef", "action_pause");
        if (compensateWith != null) {
            execute.put("compensateWith", compensateWith);
        }
        return Map.of(
                "schemaVersion", "flow.ir/v1",
                "flowCode", "quality_guard",
                "runMode", mode,
                "entityKeyExpr", "trigger.jobId",
                "cooldownSec", 3600,
                "steps", List.of(
                        Map.of("id", "s1_capture", "kind", "CAPTURE_CONTEXT",
                                "metrics", List.of("metric_freshness")),
                        Map.of("id", "s2_invariant", "kind", "CHECK_INVARIANT",
                                "targetRef", "target_core"),
                        Map.of("id", "s3_dry", "kind", "DRY_RUN_ACTION",
                                "actionTypeRef", "action_pause"),
                        execute));
    }

    private static SemanticObject object(String id, String code, String type, String specJson) {
        SemanticObject object = new SemanticObject();
        object.setId(id);
        object.setWorkspaceId("ws1");
        object.setCode(code);
        object.setName(code);
        object.setObjectType(type);
        object.setStatus("PUBLISHED");
        object.setScope("WORKSPACE");
        object.setSpecJson(specJson);
        return object;
    }
}
