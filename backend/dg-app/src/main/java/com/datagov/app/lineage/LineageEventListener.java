package com.datagov.app.lineage;

import com.datagov.app.lineage.StructuredJobLineageExtractor.Extraction;
import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.control.domain.JobType;
import com.datagov.control.entity.ControlEntities.JobDefinition;
import com.datagov.control.event.ControlEvents;
import com.datagov.control.service.JobDefinitionService;
import com.datagov.metadata.service.LineageService;
import com.datagov.metadata.service.LineageService.JobRequest;
import com.datagov.metadata.service.LineageService.JobResult;
import com.datagov.metadata.service.lineage.SqlLineageService;
import com.datagov.metadata.service.lineage.SqlLineageService.Mode;
import com.datagov.metadata.service.lineage.SqlLineageService.SqlSource;
import com.datagov.runtime.domain.ExecutionStatus;
import com.datagov.runtime.domain.JobRefType;
import com.datagov.runtime.event.ExecutionEvents;
import com.datagov.runtime.service.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 平台内建作业的列级血缘事件监听(column-lineage-e2e P1-5 / P1-8)。
 *
 * <p>血缘属于 Metadata，而 Control / Runtime 都<b>不可见</b> {@code dg-metadata}(C1)。
 * 因此它们只发布事实事件({@code CompileSucceeded} / {@code ExecutionFinished})，
 * 由装配层 dg-app 在这里订阅、抽取、写入 —— 与 {@link StructuredJobLineageExtractor}
 * 放在 dg-app 是同一个理由。
 *
 * <h3>双态流转</h3>
 * <ol>
 *   <li><b>编译成功</b> → 抽取血缘 → {@code PLANNED}(预估)；同一作业再次编译时，
 *       本次不再出现的旧边由 {@code LineageService} 标 {@code SUPERSEDED}。</li>
 *   <li><b>执行成功</b>({@code ExecutionFinished.status=SUCCEEDED}) → 再抽一次并
 *       {@code promoteJobExecution}：PLANNED 原地晋升 {@code ACTIVE}，回填 {@code executionId}，
 *       多出来的旧边同样 {@code SUPERSEDED}。</li>
 * </ol>
 *
 * <h3>抽取来源</h3>
 * <ul>
 *   <li>OfflineSync / DbMigration / FileParse / ApiParse / EtlPipeline → 结构化抽取器(读物理计划；
 *       EtlPipeline 优先用编译产物里的 {@code lineageEdges})；</li>
 *   <li>BatchDev / StreamingDev 且 {@code sourceKind=SQL} → sidecar SQL 解析；</li>
 *   <li>其余类型(工作流、Python、JAR 作业)没有可信的列级映射，不产出血缘。</li>
 * </ul>
 *
 * <h3>容错</h3>
 * <p>血缘是旁路能力：监听器<b>永不</b>让编译或执行结束失败。两个事件都在原事务
 * <b>提交之后</b>({@code AFTER_COMMIT})才处理 —— 既避免为一个未提交、可能回滚的编译写预估血缘，
 * 也避免血缘写入异常把编译 / 执行事务标记为只回滚；写入自己用独立事务。
 *
 * <p>C8：抽取不到边(或 SQL 解析 INCONCLUSIVE)时不写边，只在血缘作业上登记
 * {@code parse_status / parse_message}。执行成功晋升前还会核对「这次执行跑的计划版本」
 * 与当前计划版本一致，否则跳过 —— 不拿新版本的血缘去为旧版本的执行背书。
 */
@Component
public class LineageEventListener {

    private static final Logger log = LoggerFactory.getLogger(LineageEventListener.class);

    /** 能走结构化抽取的任务类型。 */
    private static final Set<JobType> STRUCTURED = Set.of(
            JobType.DB_MIGRATION, JobType.OFFLINE_SYNC, JobType.FILE_PARSE, JobType.API_PARSE,
            JobType.ETL_PIPELINE);
    /** 可能带 SQL 的开发任务类型。 */
    private static final Set<JobType> SQL_JOBS = Set.of(JobType.BATCH, JobType.STREAMING);

    private static final int MESSAGE_MAX = 500;

    /** 一次落库所需的作业信息快照(事件里的，或从 Control 读出来的)。 */
    public record JobInfo(String workspaceId, String id, String name, JobType type,
                          Map<String, Object> plan, Map<String, Object> config) {
    }

    /** {@link #apply} 的结果：{@code written=false} 表示没有边落库(只登记了解析结论或被跳过)。 */
    public record Applied(boolean written, String parseStatus, int edges, String jobId) {
        static Applied skipped() {
            return new Applied(false, null, 0, null);
        }
    }

    /** 在独立事务里执行一段写入。 */
    @FunctionalInterface
    interface TxRunner {
        <T> T inNewTransaction(Supplier<T> work);
    }

    private final StructuredJobLineageExtractor extractor;
    private final SqlLineageService sqlLineage;
    private final LineageService lineageService;
    private final JobDefinitionService jobService;
    private final ExecutionService executionService;
    private final TxRunner tx;

    @Autowired
    public LineageEventListener(StructuredJobLineageExtractor extractor,
                                SqlLineageService sqlLineage,
                                LineageService lineageService,
                                JobDefinitionService jobService,
                                ExecutionService executionService,
                                PlatformTransactionManager transactionManager) {
        this(extractor, sqlLineage, lineageService, jobService, executionService,
                newTransactionRunner(transactionManager));
    }

    LineageEventListener(StructuredJobLineageExtractor extractor, SqlLineageService sqlLineage,
                         LineageService lineageService, JobDefinitionService jobService,
                         ExecutionService executionService, TxRunner tx) {
        this.extractor = extractor;
        this.sqlLineage = sqlLineage;
        this.lineageService = lineageService;
        this.jobService = jobService;
        this.executionService = executionService;
        this.tx = tx;
    }

    private static TxRunner newTransactionRunner(PlatformTransactionManager manager) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new TxRunner() {
            @Override
            public <T> T inNewTransaction(Supplier<T> work) {
                return template.execute(status -> work.get());
            }
        };
    }

    // ── 事件入口 ────────────────────────────────────────────────────────

    /** 编译成功 → PLANNED。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCompileSucceeded(ControlEvents.CompileSucceeded event) {
        if (event == null || event.jobType() == null || !supports(event.jobType())) {
            return;
        }
        JobInfo job = new JobInfo(event.workspaceId(), event.jobDefinitionId(), event.jobName(),
                event.jobType(), event.physicalPlan(), event.config());
        guarded("编译", event.jobDefinitionId(), () -> apply(job, Mode.PLANNED, null));
    }

    /** 执行成功 → 晋升 ACTIVE 并绑定 executionId。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onExecutionFinished(ExecutionEvents.ExecutionFinished event) {
        if (event == null || event.status() != ExecutionStatus.SUCCEEDED) {
            return;
        }
        JobType type = jobTypeOf(event.jobRefType());
        if (type == null || !supports(type) || event.jobRefId() == null) {
            return;
        }
        guarded("执行", event.executionId(), () -> WorkspaceContext.callAs(
                systemCaller(event.workspaceId()), () -> {
                    JobDefinition definition = jobService.loadInternal(event.jobRefId());
                    if (!event.workspaceId().equals(definition.getWorkspaceId())) {
                        return Applied.skipped();
                    }
                    Integer executedVersion = executionService.get(event.executionId())
                            .execution().defVersion();
                    if (executedVersion != null && definition.getPlanDefVersion() != null
                            && !executedVersion.equals(definition.getPlanDefVersion())) {
                        log.info("跳过血缘晋升:执行 {} 跑的是计划 v{},当前计划已是 v{} job={}",
                                event.executionId(), executedVersion,
                                definition.getPlanDefVersion(), definition.getId());
                        return Applied.skipped();
                    }
                    JobInfo job = new JobInfo(definition.getWorkspaceId(), definition.getId(),
                            definition.getName(), definition.getJobType(),
                            jobService.readPhysicalPlan(definition),
                            jobService.readConfig(definition));
                    return applyInternal(job, Mode.PROMOTE, event.executionId());
                }));
    }

    // ── 核心逻辑 ────────────────────────────────────────────────────────

    /**
     * 抽取并按 {@link Mode} 写入。不依赖事件对象，便于测试与回填复用。
     * 调用方不需要预先设置 {@code WorkspaceContext}。
     */
    public Applied apply(JobInfo job, Mode mode, String executionId) {
        return WorkspaceContext.callAs(systemCaller(job.workspaceId()),
                () -> applyInternal(job, mode, executionId));
    }

    private Applied applyInternal(JobInfo job, Mode mode, String executionId) {
        if (STRUCTURED.contains(job.type())) {
            return tx.inNewTransaction(() -> applyStructured(job, mode, executionId));
        }
        if (SQL_JOBS.contains(job.type())) {
            return applySql(job, mode, executionId);
        }
        return Applied.skipped();
    }

    private Applied applyStructured(JobInfo job, Mode mode, String executionId) {
        // 计划是编译后的事实，优先于配置；两者键形状不同，抽取器统一识别。
        Map<String, Object> source = job.plan() == null || job.plan().isEmpty()
                ? job.config() : job.plan();
        Extraction extraction = extractor.extract(job.workspaceId(), job.type().name(), source);
        String message = summarize(extraction.warnings());

        if (extraction.edges().isEmpty()) {
            // C8：没有可信的边就不写边，但要让工作台看见「这个作业的血缘没抽出来」及原因。
            JobResult result = lineageService.recordJobParseOutcome(new JobRequest(job.name(),
                    job.type().name(), sourceRef(job), null, List.of(), "INCONCLUSIVE",
                    message == null ? "未抽取到列级血缘" : message));
            return new Applied(false, "INCONCLUSIVE", 0, result.jobId());
        }

        JobRequest request = new JobRequest(job.name(), job.type().name(), sourceRef(job), null,
                extraction.toEdgeInputs(), "OK", message);
        JobResult result = switch (mode) {
            case PLANNED -> lineageService.recordPlannedJob(request);
            case ACTIVE -> lineageService.recordJob(request);
            case PROMOTE -> lineageService.promoteJobExecution(request, executionId);
        };
        return new Applied(true, "OK", result.edges(), result.jobId());
    }

    private Applied applySql(JobInfo job, Mode mode, String executionId) {
        Map<String, Object> plan = job.plan() == null || job.plan().isEmpty()
                ? job.config() : job.plan();
        String sql = text(plan, "sql");
        String sourceKind = text(plan, "sourceKind");
        if (sql == null || (sourceKind != null && !"SQL".equalsIgnoreCase(sourceKind))) {
            return Applied.skipped();
        }
        SqlSource source = new SqlSource(sql, text(plan, "dialect"), text(plan, "dataSourceId"),
                null, null, null);
        SqlLineageService.Outcome outcome = tx.inNewTransaction(() -> sqlLineage.record(
                new SqlLineageService.JobSpec(job.name(), job.type().name(), sourceRef(job), source),
                mode, executionId));
        return new Applied(outcome.written(), outcome.status(), outcome.edges(), outcome.jobId());
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private void guarded(String phase, String ref, Supplier<Applied> work) {
        try {
            Applied applied = work.get();
            if (applied != null && applied.jobId() != null) {
                log.info("列级血缘已处理 phase={} ref={} status={} edges={}", phase, ref,
                        applied.parseStatus(), applied.edges());
            }
        } catch (RuntimeException e) {
            // 血缘是旁路：失败只记日志，绝不向发布事件的编译 / 执行流程抛出。
            log.warn("列级血缘处理失败(已隔离) phase={} ref={} err={}", phase, ref, e.getMessage());
            log.debug("列级血缘处理失败详情", e);
        }
    }

    private static boolean supports(JobType type) {
        return STRUCTURED.contains(type) || SQL_JOBS.contains(type);
    }

    static JobType jobTypeOf(JobRefType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case MIGRATION -> JobType.DB_MIGRATION;
            case OFFLINE_SYNC -> JobType.OFFLINE_SYNC;
            case FILE_PARSE -> JobType.FILE_PARSE;
            case API_PARSE -> JobType.API_PARSE;
            case ETL_PIPELINE -> JobType.ETL_PIPELINE;
            case BATCH_DEV -> JobType.BATCH;
            case STREAMING_DEV -> JobType.STREAMING;
            default -> null;
        };
    }

    private static String sourceRef(JobInfo job) {
        return "jobdef:" + job.id();
    }

    private static String summarize(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return null;
        }
        String joined = String.join("; ", warnings);
        return joined.length() <= MESSAGE_MAX ? joined : joined.substring(0, MESSAGE_MAX - 1) + "…";
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value == null) {
            return null;
        }
        String s = value.toString();
        return s.isBlank() ? null : s;
    }

    private static Caller systemCaller(String workspaceId) {
        return new Caller("system", "system", workspaceId, true, Set.of());
    }
}
