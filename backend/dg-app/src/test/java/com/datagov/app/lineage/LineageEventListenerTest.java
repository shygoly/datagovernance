package com.datagov.app.lineage;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.datagov.app.lineage.StructuredJobLineageExtractor.RuleInfo;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.control.domain.JobType;
import com.datagov.control.entity.ControlEntities.JobDefinition;
import com.datagov.control.event.ControlEvents;
import com.datagov.control.service.JobDefinitionService;
import com.datagov.metadata.entity.LineageEntities.LineageEdge;
import com.datagov.metadata.entity.LineageEntities.LineageJob;
import com.datagov.metadata.entity.LineageEntities.LineageNode;
import com.datagov.metadata.mapper.CatalogDatasetMapper;
import com.datagov.metadata.mapper.LineageEdgeMapper;
import com.datagov.metadata.mapper.LineageJobMapper;
import com.datagov.metadata.mapper.LineageNodeMapper;
import com.datagov.metadata.service.LineageCatalogResolver;
import com.datagov.metadata.service.LineageService;
import com.datagov.metadata.service.LineageService.NodeRef;
import com.datagov.metadata.service.lineage.SqlLineageService;
import com.datagov.runtime.domain.ExecutionStatus;
import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.dto.ExecutionView;
import com.datagov.runtime.event.ExecutionEvents;
import com.datagov.runtime.service.ExecutionService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-5：编译 → PLANNED、执行成功 → ACTIVE(+executionId)、重编译 → SUPERSEDED 的双态流转。
 *
 * <p>用真实的 {@link LineageService} + 内存版 mapper(按 wrapper 的参数值过滤)，
 * 断言的是<b>落库后的边状态</b>，而不是「调用了哪个方法」。
 */
@DisplayName("列级血缘事件监听(PLANNED → ACTIVE → SUPERSEDED)")
class LineageEventListenerTest {

    private static final String WS = "ws_alpha";
    private static final String JOB_ID = "ctlj_1";

    private final List<LineageNode> nodes = new ArrayList<>();
    private final List<LineageJob> jobs = new ArrayList<>();
    private final List<LineageEdge> edges = new ArrayList<>();

    private JobDefinitionService jobService;
    private ExecutionService executionService;
    private SqlLineageService sqlLineage;
    private LineageEventListener listener;

    /** LambdaQueryWrapper 需要 TableInfo 缓存才能把方法引用翻译成列名。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, LineageNode.class);
        TableInfoHelper.initTableInfo(assistant, LineageJob.class);
        TableInfoHelper.initTableInfo(assistant, LineageEdge.class);
    }

    @BeforeEach
    void setUp() {
        LineageNodeMapper nodeMapper = mock(LineageNodeMapper.class);
        LineageJobMapper jobMapper = mock(LineageJobMapper.class);
        LineageEdgeMapper edgeMapper = mock(LineageEdgeMapper.class);
        stubStore(nodeMapper, jobMapper, edgeMapper);

        LineageService lineageService = new LineageService(nodeMapper, jobMapper, edgeMapper,
                mock(CatalogDatasetMapper.class));
        StructuredJobLineageExtractor extractor = new StructuredJobLineageExtractor(
                new FakeResolver(), (workspaceId, ids) -> Map.<String, RuleInfo>of());

        jobService = mock(JobDefinitionService.class);
        executionService = mock(ExecutionService.class);
        sqlLineage = mock(SqlLineageService.class);
        listener = new LineageEventListener(extractor, sqlLineage, lineageService, jobService,
                executionService, new LineageEventListener.TxRunner() {
            @Override
            public <T> T inNewTransaction(Supplier<T> work) {
                return work.get();
            }
        });
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    // ── 双态流转 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("编译成功 → 写入 PLANNED 边，作业登记 OK")
    void compileWritesPlanned() {
        listener.onCompileSucceeded(compiled(plan(mapping("a", "x", "b", "y"))));

        assertThat(edgeStatuses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ods.public.orders.a->dws.public.sum.x", "PLANNED",
                "ods.public.orders.b->dws.public.sum.y", "PLANNED"));
        LineageJob job = jobs.get(0);
        assertThat(job.getJobType()).isEqualTo("OFFLINE_SYNC");
        assertThat(job.getName()).isEqualTo("订单同步");
        assertThat(job.getSourceRef()).isEqualTo("jobdef:" + JOB_ID);
        assertThat(job.getParseStatus()).isEqualTo("OK");
        assertThat(edges).allSatisfy(e -> {
            assertThat(e.getExecutionId()).isNull();
            assertThat(e.getOrigin()).isEqualTo("AUTO");
            assertThat(e.getDependencyType()).isEqualTo("DIRECT");
        });
    }

    @Test
    @DisplayName("执行成功 → PLANNED 原地晋升 ACTIVE 并绑定 executionId，不新增重复边")
    void executionSucceededPromotesToActive() {
        listener.onCompileSucceeded(compiled(plan(mapping("a", "x", "b", "y"))));
        List<String> plannedEdgeIds = edges.stream().map(LineageEdge::getId).toList();
        givenDefinition(plan(mapping("a", "x", "b", "y")), 1, 1);

        listener.onExecutionFinished(finished("exec_1", ExecutionStatus.SUCCEEDED));

        assertThat(edges).hasSize(2);
        assertThat(edges.stream().map(LineageEdge::getId)).containsExactlyElementsOf(plannedEdgeIds);
        assertThat(edges).allSatisfy(e -> {
            assertThat(e.getStatus()).isEqualTo("ACTIVE");
            assertThat(e.getExecutionId()).isEqualTo("exec_1");
        });
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getLastExecutionId()).isEqualTo("exec_1");
    }

    @Test
    @DisplayName("重新编译：未变化的 ACTIVE 边保持 ACTIVE，新边 PLANNED，消失的边 SUPERSEDED")
    void recompileSupersedesRemovedEdges() {
        listener.onCompileSucceeded(compiled(plan(mapping("a", "x", "b", "y"))));
        givenDefinition(plan(mapping("a", "x", "b", "y")), 1, 1);
        listener.onExecutionFinished(finished("exec_1", ExecutionStatus.SUCCEEDED));

        // 同一份定义再次编译：已被执行证实的边不能被降回预估
        listener.onCompileSucceeded(compiled(plan(mapping("a", "x", "b", "y"))));
        assertThat(edgeStatuses().values()).containsOnly("ACTIVE");

        // v2：b→y 被删，新增 c→z
        listener.onCompileSucceeded(compiled(plan(mapping("a", "x", "c", "z"))));
        assertThat(edgeStatuses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ods.public.orders.a->dws.public.sum.x", "ACTIVE",
                "ods.public.orders.b->dws.public.sum.y", "SUPERSEDED",
                "ods.public.orders.c->dws.public.sum.z", "PLANNED"));
        LineageEdge superseded = edgeByKey("ods.public.orders.b->dws.public.sum.y");
        assertThat(superseded.getSupersededAt()).isNotNull();

        // v2 的执行成功：c→z 晋升，b→y 保持 SUPERSEDED
        givenDefinition(plan(mapping("a", "x", "c", "z")), 2, 2);
        listener.onExecutionFinished(finished("exec_2", ExecutionStatus.SUCCEEDED));
        assertThat(edgeStatuses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ods.public.orders.a->dws.public.sum.x", "ACTIVE",
                "ods.public.orders.b->dws.public.sum.y", "SUPERSEDED",
                "ods.public.orders.c->dws.public.sum.z", "ACTIVE"));
        assertThat(edgeByKey("ods.public.orders.c->dws.public.sum.z").getExecutionId())
                .isEqualTo("exec_2");
        assertThat(edgeByKey("ods.public.orders.b->dws.public.sum.y").getExecutionId())
                .as("被取代的边不会被新执行重新背书").isEqualTo("exec_1");
        assertThat(jobs.get(0).getLastExecutionId()).isEqualTo("exec_2");
    }

    @Test
    @DisplayName("没有 PLANNED 就直接执行成功(例如监听器上线前已发布的作业) → 直接按 ACTIVE 写入")
    void executionWithoutPlannedWritesActive() {
        givenDefinition(plan(mapping("a", "x")), 1, 1);

        listener.onExecutionFinished(finished("exec_9", ExecutionStatus.SUCCEEDED));

        assertThat(edgeStatuses().values()).containsOnly("ACTIVE");
        assertThat(edges.get(0).getExecutionId()).isEqualTo("exec_9");
    }

    // ── 不该动的情形 ────────────────────────────────────────────────────

    @Test
    @DisplayName("执行失败 / 取消不晋升、不读定义")
    void nonSuccessIgnored() {
        listener.onExecutionFinished(finished("exec_f", ExecutionStatus.FAILED));
        listener.onExecutionFinished(finished("exec_c", ExecutionStatus.CANCELED));

        assertThat(edges).isEmpty();
        verify(jobService, never()).loadInternal(any());
    }

    @Test
    @DisplayName("执行跑的计划版本已不是当前计划 → 跳过晋升，不为旧执行背书新血缘")
    void staleExecutionSkipped() {
        listener.onCompileSucceeded(compiled(plan(mapping("a", "x"))));
        givenDefinition(plan(mapping("a", "x")), 3, 2);       // 执行 v2，当前计划 v3

        listener.onExecutionFinished(finished("exec_old", ExecutionStatus.SUCCEEDED));

        assertThat(edgeStatuses().values()).containsOnly("PLANNED");
        assertThat(edges.get(0).getExecutionId()).isNull();
    }

    @Test
    @DisplayName("不产出列级血缘的任务类型(工作流 / Python)被忽略")
    void unsupportedTypesIgnored() {
        listener.onCompileSucceeded(new ControlEvents.CompileSucceeded(WS, "wf_1", "流程",
                JobType.WORKFLOW, 1, Map.of("kind", "WORKFLOW"), Map.of(), Instant.now()));
        listener.onExecutionFinished(new ExecutionEvents.ExecutionFinished(WS, "e", JobRefType.WORKFLOW,
                "wf_1", "流程", ExecutionStatus.SUCCEEDED, 1, 10L, 0L, 0L, 0L, null, null, Instant.now()));

        assertThat(jobs).isEmpty();
        assertThat(edges).isEmpty();
    }

    @Test
    @DisplayName("C8：抽不到边时不写边，只把作业登记为 INCONCLUSIVE 并带原因")
    void emptyExtractionRecordsInconclusive() {
        Map<String, Object> plan = plan(mapping());
        listener.onCompileSucceeded(compiled(plan));

        assertThat(edges).isEmpty();
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getParseStatus()).isEqualTo("INCONCLUSIVE");
        assertThat(jobs.get(0).getParseMessage()).contains("字段映射");
    }

    @Test
    @DisplayName("监听器内部任何异常都被隔离，不会向编译 / 执行流程抛出")
    void failuresAreIsolated() {
        when(jobService.loadInternal(any())).thenThrow(new IllegalStateException("gone"));

        listener.onExecutionFinished(finished("exec_x", ExecutionStatus.SUCCEEDED));   // 不抛
        listener.onCompileSucceeded(new ControlEvents.CompileSucceeded(WS, JOB_ID, "x",
                JobType.OFFLINE_SYNC, 1, Map.of("source", "not-a-map", "fieldMappings", 3),
                Map.of(), Instant.now()));                                                // 不抛
    }

    // ── SQL 作业 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("BatchDev 的 SQL 作业：编译 → PLANNED，执行成功 → PROMOTE，交给 SqlLineageService")
    void sqlJobDelegatesToSqlLineage() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", "BATCH_DEV");
        plan.put("sourceKind", "SQL");
        plan.put("sql", "INSERT INTO dws.t SELECT x FROM ods.s");
        plan.put("dataSourceId", "ds_pg");
        when(sqlLineage.record(any(), any(), any())).thenReturn(
                new SqlLineageService.Outcome("OK", true, "linj_1", 1, "h", List.of()));

        listener.onCompileSucceeded(new ControlEvents.CompileSucceeded(WS, "ctlj_sql", "日汇总",
                JobType.BATCH, 1, plan, Map.of(), Instant.now()));

        ArgumentCaptor<SqlLineageService.JobSpec> spec = ArgumentCaptor.forClass(SqlLineageService.JobSpec.class);
        verify(sqlLineage).record(spec.capture(), eq(SqlLineageService.Mode.PLANNED), eq(null));
        assertThat(spec.getValue().name()).isEqualTo("日汇总");
        assertThat(spec.getValue().jobType()).isEqualTo("BATCH");
        assertThat(spec.getValue().sourceRef()).isEqualTo("jobdef:ctlj_sql");
        assertThat(spec.getValue().source().sql()).contains("INSERT INTO dws.t");
        assertThat(spec.getValue().source().dataSourceId()).isEqualTo("ds_pg");

        JobDefinition definition = new JobDefinition();
        definition.setId("ctlj_sql");
        definition.setWorkspaceId(WS);
        definition.setName("日汇总");
        definition.setJobType(JobType.BATCH);
        definition.setVersion(1);
        definition.setPlanDefVersion(1);
        when(jobService.loadInternal("ctlj_sql")).thenReturn(definition);
        when(jobService.readPhysicalPlan(definition)).thenReturn(plan);
        when(jobService.readConfig(definition)).thenReturn(Map.of());
        stubExecutionVersion(1);
        listener.onExecutionFinished(new ExecutionEvents.ExecutionFinished(WS, "exec_sql",
                JobRefType.BATCH_DEV, "ctlj_sql", "日汇总", ExecutionStatus.SUCCEEDED, 1, 5L, 1L, 1L, 1L,
                null, null, Instant.now()));
        verify(sqlLineage).record(any(), eq(SqlLineageService.Mode.PROMOTE), eq("exec_sql"));
    }

    @Test
    @DisplayName("BatchDev 的 JAR 作业(sourceKind≠SQL)不走 SQL 解析")
    void jarJobSkipped() {
        Map<String, Object> plan = Map.of("kind", "BATCH_DEV", "sourceKind", "JAR", "artifactId", "a1");

        listener.onCompileSucceeded(new ControlEvents.CompileSucceeded(WS, "ctlj_jar", "jar",
                JobType.BATCH, 1, plan, Map.of(), Instant.now()));

        verify(sqlLineage, never()).record(any(), any(), any());
    }

    @Test
    @DisplayName("ETL_PIPELINE：编译 → PLANNED(取 lineageEdges)，执行成功 → 原地晋升 ACTIVE")
    void etlPipelineCompilePlannedThenExecutionActive() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("schemaVersion", "etl.ir/v1");
        plan.put("kind", "ETL_PIPELINE");
        plan.put("lineageEdges", List.of(Map.of(
                "from", Map.of("dataSourceId", "ds_src", "database", "ods", "schema", "public",
                        "table", "orders", "column", "a"),
                "to", Map.of("dataSourceId", "ds_dst", "database", "dws", "schema", "public",
                        "table", "sum", "column", "x"))));

        listener.onCompileSucceeded(new ControlEvents.CompileSucceeded(WS, JOB_ID, "ETL 订单",
                JobType.ETL_PIPELINE, 1, plan, Map.of(), Instant.now()));
        assertThat(edgeStatuses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ods.public.orders.a->dws.public.sum.x", "PLANNED"));
        assertThat(jobs.get(0).getJobType()).isEqualTo("ETL_PIPELINE");

        JobDefinition definition = new JobDefinition();
        definition.setId(JOB_ID);
        definition.setWorkspaceId(WS);
        definition.setName("ETL 订单");
        definition.setJobType(JobType.ETL_PIPELINE);
        definition.setVersion(1);
        definition.setPlanDefVersion(1);
        when(jobService.loadInternal(JOB_ID)).thenReturn(definition);
        when(jobService.readPhysicalPlan(definition)).thenReturn(plan);
        when(jobService.readConfig(definition)).thenReturn(Map.of());
        stubExecutionVersion(1);

        listener.onExecutionFinished(new ExecutionEvents.ExecutionFinished(WS, "exec_etl",
                JobRefType.ETL_PIPELINE, JOB_ID, "ETL 订单", ExecutionStatus.SUCCEEDED, 1, 100L,
                10L, 10L, 100L, null, null, Instant.now()));

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).getStatus()).isEqualTo("ACTIVE");
        assertThat(edges.get(0).getExecutionId()).isEqualTo("exec_etl");
    }

    // ── helpers ────────────────────────────────────────────────────────

    private ControlEvents.CompileSucceeded compiled(Map<String, Object> plan) {
        return new ControlEvents.CompileSucceeded(WS, JOB_ID, "订单同步", JobType.OFFLINE_SYNC, 1,
                plan, Map.of(), Instant.now());
    }

    private ExecutionEvents.ExecutionFinished finished(String executionId, ExecutionStatus status) {
        return new ExecutionEvents.ExecutionFinished(WS, executionId, JobRefType.OFFLINE_SYNC, JOB_ID,
                "订单同步", status, 1, 100L, 10L, 10L, 100L, null, null, Instant.now());
    }

    /** 当前定义的计划为 {@code plan}、计划版本 {@code planVersion}，这次执行跑的是 {@code executedVersion}。 */
    private void givenDefinition(Map<String, Object> plan, int planVersion, int executedVersion) {
        JobDefinition definition = new JobDefinition();
        definition.setId(JOB_ID);
        definition.setWorkspaceId(WS);
        definition.setName("订单同步");
        definition.setJobType(JobType.OFFLINE_SYNC);
        definition.setVersion(planVersion);
        definition.setPlanDefVersion(planVersion);
        when(jobService.loadInternal(JOB_ID)).thenReturn(definition);
        when(jobService.readPhysicalPlan(definition)).thenReturn(plan);
        when(jobService.readConfig(definition)).thenReturn(Map.of());
        stubExecutionVersion(executedVersion);
    }

    private void stubExecutionVersion(int defVersion) {
        ExecutionView view = mock(ExecutionView.class);
        when(view.defVersion()).thenReturn(defVersion);
        ExecutionView.Detail detail = mock(ExecutionView.Detail.class);
        when(detail.execution()).thenReturn(view);
        when(executionService.get(any())).thenReturn(detail);
    }

    private static Map<String, Object> plan(Map<String, String> mappings) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", "OFFLINE_SYNC");
        plan.put("source", Map.of("dataSourceId", "ds_src", "database", "ods", "schema", "public",
                "table", "orders"));
        plan.put("target", Map.of("dataSourceId", "ds_dst", "database", "dws", "schema", "public",
                "table", "sum"));
        plan.put("fieldMappings", mappings);
        plan.put("fieldRules", Map.of());
        return plan;
    }

    private static Map<String, String> mapping(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    /** {@code fromQualifiedName->toQualifiedName} → status */
    private Map<String, String> edgeStatuses() {
        Map<String, String> result = new LinkedHashMap<>();
        for (LineageEdge edge : edges) {
            result.put(keyOf(edge), edge.getStatus());
        }
        return result;
    }

    private LineageEdge edgeByKey(String key) {
        return edges.stream().filter(e -> keyOf(e).equals(key)).findFirst().orElseThrow();
    }

    private String keyOf(LineageEdge edge) {
        return nameOf(edge.getFromNodeId()) + "->" + nameOf(edge.getToNodeId());
    }

    private String nameOf(String nodeId) {
        return nodes.stream().filter(n -> n.getId().equals(nodeId)).findFirst().orElseThrow()
                .getQualifiedName();
    }

    // ── 内存版 mapper ────────────────────────────────────────────────

    private void stubStore(LineageNodeMapper nodeMapper, LineageJobMapper jobMapper,
                           LineageEdgeMapper edgeMapper) {
        when(nodeMapper.insert(any(LineageNode.class))).thenAnswer(i -> add(nodes, i.getArgument(0)));
        when(nodeMapper.updateById(any(LineageNode.class))).thenReturn(1);
        when(nodeMapper.selectOne(any())).thenAnswer(i -> first(match(nodes, i.getArgument(0))));
        when(nodeMapper.selectList(any())).thenAnswer(i -> match(nodes, i.getArgument(0)));

        when(jobMapper.insert(any(LineageJob.class))).thenAnswer(i -> add(jobs, i.getArgument(0)));
        when(jobMapper.updateById(any(LineageJob.class))).thenReturn(1);
        when(jobMapper.selectOne(any())).thenAnswer(i -> first(match(jobs, i.getArgument(0))));
        when(jobMapper.selectList(any())).thenAnswer(i -> match(jobs, i.getArgument(0)));

        when(edgeMapper.insert(any(LineageEdge.class))).thenAnswer(i -> add(edges, i.getArgument(0)));
        when(edgeMapper.updateById(any(LineageEdge.class))).thenReturn(1);
        when(edgeMapper.selectList(any())).thenAnswer(i -> match(edges, i.getArgument(0)));
    }

    private static <T> int add(List<T> store, T entity) {
        store.add(entity);
        return 1;
    }

    private static <T> T first(List<T> list) {
        return list.isEmpty() ? null : list.get(0);
    }

    /** 实体的所有字段值都出现在 wrapper 的参数值里才算命中(服务里的查询都是 eq 链)。 */
    private static <T> List<T> match(List<T> store, Wrapper<T> wrapper) {
        // 参数值是惰性生成的：必须先取一次 SQL 片段，paramNameValuePairs 才会被填充。
        wrapper.getSqlSegment();
        Collection<Object> params = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs().values();
        return store.stream().filter(entity -> {
            Set<Object> fieldValues = fieldValues(entity);
            return params.stream().allMatch(fieldValues::contains);
        }).toList();
    }

    private static Set<Object> fieldValues(Object entity) {
        Set<Object> values = new java.util.HashSet<>();
        for (Field field : entity.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            try {
                Object value = field.get(entity);
                if (value != null) {
                    values.add(value);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return values;
    }

    /** 不连库的 Catalog 替身：节点限定名 = db.schema.table.column。 */
    private static final class FakeResolver extends LineageCatalogResolver {
        FakeResolver() {
            super(null, null, null);
        }

        @Override
        public NodeRef resolveNodeRef(String workspaceId, String datasourceId, String database,
                                      String schema, String table, String columnName) {
            StringBuilder sb = new StringBuilder();
            for (String part : new String[]{database, schema, table, columnName}) {
                if (part != null && !part.isBlank()) {
                    sb.append(sb.length() == 0 ? "" : ".").append(part);
                }
            }
            return new NodeRef("COLUMN", sb.toString(), null, null, columnName);
        }

        @Override
        public Optional<String> resolveDatasetId(String workspaceId, String datasourceId,
                                                 String database, String schema, String table) {
            return Optional.empty();
        }
    }
}
