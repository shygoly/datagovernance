package com.datagov.governance.service;

import com.datagov.common.audit.ReproducibilityFingerprint;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.id.Ids;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.governance.entity.GovernanceEntities.OperationalReportDef;
import com.datagov.governance.entity.GovernanceEntities.OperationalReportRun;
import com.datagov.governance.entity.GovernanceEntities.OperationalReportSchedule;
import com.datagov.governance.entity.GovernanceEntities.OperationalReportVersion;
import com.datagov.governance.mapper.OperationalReportDefMapper;
import com.datagov.governance.mapper.OperationalReportRunMapper;
import com.datagov.governance.mapper.OperationalReportScheduleMapper;
import com.datagov.governance.mapper.OperationalReportVersionMapper;
import com.datagov.common.tenant.Caller;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 运营报表（≠ AnalysisReport）：参数化复合块、运行快照、MD/CSV 制品与 C8 指纹。
 */
@Service
public class OperationalReportService {

    public static final Set<String> BLOCK_TYPES = Set.of(
            "SMQ_BLOCK", "COHORT_BLOCK", "STATS_BLOCK", "STATIC_MD");

    private static final Logger log = LoggerFactory.getLogger(OperationalReportService.class);
    private static final ZoneId SCHEDULE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int FIRE_BATCH = 50;

    private static final Pattern SQL_SMELL = Pattern.compile(
            "(?i)(\\b(select|insert|update|delete|drop|alter|union)\\b.*\\b(from|table|into)\\b|--|/\\*|;)");

    private final OperationalReportDefMapper defMapper;
    private final OperationalReportVersionMapper versionMapper;
    private final OperationalReportRunMapper runMapper;
    private final OperationalReportScheduleMapper scheduleMapper;
    private final ObjectMapper objectMapper;

    public OperationalReportService(OperationalReportDefMapper defMapper,
                                    OperationalReportVersionMapper versionMapper,
                                    OperationalReportRunMapper runMapper,
                                    OperationalReportScheduleMapper scheduleMapper,
                                    ObjectMapper objectMapper) {
        this.defMapper = defMapper;
        this.versionMapper = versionMapper;
        this.runMapper = runMapper;
        this.scheduleMapper = scheduleMapper;
        this.objectMapper = objectMapper;
    }

    public record ReportBlock(String type, String title, Map<String, Object> payload) {
        public ReportBlock {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    public record PublishRequest(
            String code,
            String name,
            Map<String, Object> paramSchema,
            List<ReportBlock> blocks
    ) {
    }

    public record RunRequest(Map<String, Object> params) {
    }

    public record ReportView(
            String id,
            String code,
            String name,
            String status,
            int latestVersion,
            String contentDigest
    ) {
    }

    public record RunView(
            String id,
            String reportId,
            int version,
            String status,
            String reproducibilityFingerprint,
            String artifactMd,
            String artifactCsv,
            String errorMessage
    ) {
    }

    public record ExportFile(String filename, byte[] content, String contentType) {
    }

    public record ScheduleRequest(
            String cronExpr,
            List<String> recipients,
            boolean enabled,
            boolean requireExportConfirm
    ) {
    }

    public record ScheduleView(
            String id,
            String reportId,
            String cronExpr,
            List<String> recipients,
            boolean enabled,
            boolean requireExportConfirm
    ) {
    }

    @Transactional
    public ReportView publish(PublishRequest request) {
        if (request == null || blank(request.code()) || blank(request.name())) {
            throw invalid("code / name 必填");
        }
        List<ReportBlock> blocks = validateBlocks(request.blocks());
        String ws = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();
        Instant now = Instant.now();

        String blocksJson = writeJson(blocks);
        String digest = sha256(request.code() + "|" + blocksJson);

        OperationalReportDef def = defMapper.selectByWorkspaceAndCode(ws, request.code());
        if (def == null) {
            def = new OperationalReportDef();
            def.setId(Ids.of("oprep"));
            def.setWorkspaceId(ws);
            def.setCode(request.code().trim());
            def.setName(request.name().trim());
            def.setStatus("PUBLISHED");
            def.setLatestVersion(1);
            def.setParamSchemaJson(writeJson(request.paramSchema() == null ? Map.of() : request.paramSchema()));
            def.setCreatedBy(operator);
            def.setCreatedAt(now);
            def.setUpdatedAt(now);
            defMapper.insert(def);

            OperationalReportVersion version = newVersion(def, 1, null, blocksJson, digest, operator, now);
            versionMapper.insert(version);
            return toView(def, digest);
        }

        int next = (def.getLatestVersion() == null ? 0 : def.getLatestVersion()) + 1;
        def.setName(request.name().trim());
        def.setStatus("PUBLISHED");
        def.setLatestVersion(next);
        def.setParamSchemaJson(writeJson(request.paramSchema() == null ? Map.of() : request.paramSchema()));
        def.setUpdatedAt(now);
        defMapper.updateById(def);

        OperationalReportVersion version = newVersion(
                def, next, next - 1 == 0 ? null : next - 1, blocksJson, digest, operator, now);
        versionMapper.insert(version);
        return toView(def, digest);
    }

    @Transactional
    public RunView run(String reportId, RunRequest request) {
        String ws = WorkspaceContext.requireWorkspaceId();
        String operator = WorkspaceContext.require().userId();
        OperationalReportDef def = requireDef(reportId, ws);
        if (def.getLatestVersion() == null || def.getLatestVersion() < 1) {
            throw invalid("报表尚无已发布版本");
        }
        OperationalReportVersion ver = versionMapper.selectByReportAndVersion(
                def.getId(), def.getLatestVersion());
        if (ver == null) {
            throw invalid("报表版本缺失");
        }

        Instant now = Instant.now();
        OperationalReportRun run = new OperationalReportRun();
        run.setId(Ids.of("oprun"));
        run.setReportId(def.getId());
        run.setWorkspaceId(ws);
        run.setVersion(ver.getVersion());
        run.setStatus("RUNNING");
        run.setParamsJson(writeJson(request == null || request.params() == null ? Map.of() : request.params()));
        run.setTriggeredBy(operator);
        run.setStartedAt(now);
        run.setCreatedAt(now);
        runMapper.insert(run);

        try {
            List<ReportBlock> blocks = readBlocks(ver.getBlocksJson());
            Map<String, Object> params = request == null || request.params() == null
                    ? Map.of() : request.params();
            String md = renderMarkdown(def, ver, blocks, params);
            String csv = renderCsv(blocks, params);
            ReproducibilityFingerprint fp = buildFingerprint(def, ver, params, md);
            run.setStatus("SUCCEEDED");
            run.setArtifactMd(md);
            run.setArtifactCsv(csv);
            run.setReproducibilityFingerprint(objectMapper.writeValueAsString(fp));
            run.setFinishedAt(Instant.now());
            runMapper.updateById(run);
        } catch (Exception ex) {
            run.setStatus("FAILED");
            run.setErrorMessage(ex.getMessage() == null ? "run failed" : ex.getMessage().substring(0,
                    Math.min(1000, ex.getMessage().length())));
            run.setFinishedAt(Instant.now());
            runMapper.updateById(run);
        }
        return toRunView(run);
    }

    public ExportFile export(String runId, String format) {
        return export(runId, format, false);
    }

    /**
     * @param exportConfirmed C7：当报表调度要求敏感导出确认时，须为 true
     */
    public ExportFile export(String runId, String format, boolean exportConfirmed) {
        String ws = WorkspaceContext.requireWorkspaceId();
        OperationalReportRun run = runMapper.selectById(runId);
        if (run == null || !ws.equals(run.getWorkspaceId())) {
            throw new BizException(ErrorCode.SYS_NOT_FOUND, "运行记录不存在");
        }
        if (!"SUCCEEDED".equals(run.getStatus())) {
            throw invalid("仅成功运行可导出");
        }
        if (requiresExportConfirm(run.getReportId()) && !exportConfirmed) {
            throw invalid("该报表要求敏感导出确认（C7）：请在宿主确认后携带 exportConfirmed=true");
        }
        String fmt = format == null ? "md" : format.trim().toLowerCase(Locale.ROOT);
        return switch (fmt) {
            case "csv", "xlsx", "excel" -> new ExportFile(
                    "operational-report-" + run.getId() + ".csv",
                    (run.getArtifactCsv() == null ? "" : run.getArtifactCsv()).getBytes(StandardCharsets.UTF_8),
                    "text/csv");
            case "pdf", "md", "markdown" -> new ExportFile(
                    "operational-report-" + run.getId() + (fmt.equals("pdf") ? ".md" : ".md"),
                    (run.getArtifactMd() == null ? "" : run.getArtifactMd()).getBytes(StandardCharsets.UTF_8),
                    "text/markdown");
            default -> throw invalid("不支持的导出格式: " + format + "（支持 md/csv；pdf 当前导出 Markdown 快照）");
        };
    }

    @Transactional
    public ScheduleView upsertSchedule(String reportId, ScheduleRequest request) {
        if (request == null || blank(request.cronExpr())) {
            throw invalid("cronExpr 必填");
        }
        String ws = WorkspaceContext.requireWorkspaceId();
        requireDef(reportId, ws);
        Instant now = Instant.now();
        OperationalReportSchedule existing = scheduleMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OperationalReportSchedule>()
                                .eq(OperationalReportSchedule::getWorkspaceId, ws)
                                .eq(OperationalReportSchedule::getReportId, reportId)
                                .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (existing == null) {
            existing = new OperationalReportSchedule();
            existing.setId(Ids.of("opsch"));
            existing.setReportId(reportId);
            existing.setWorkspaceId(ws);
            existing.setCreatedAt(now);
        }
        existing.setCronExpr(request.cronExpr().trim());
        existing.setRecipientsJson(writeJson(request.recipients() == null ? List.of() : request.recipients()));
        existing.setEnabled(request.enabled());
        existing.setRequireExportConfirm(request.requireExportConfirm());
        existing.setUpdatedAt(now);
        if (Boolean.TRUE.equals(request.enabled())) {
            existing.setNextFireAt(nextFireAfter(existing.getCronExpr(), now));
        } else {
            existing.setNextFireAt(null);
        }
        if (existing.getCreatedAt() == null) {
            existing.setCreatedAt(now);
        }
        if (scheduleMapper.selectById(existing.getId()) == null) {
            scheduleMapper.insert(existing);
        } else {
            scheduleMapper.updateById(existing);
        }
        return toScheduleView(existing);
    }

    /**
     * 装配层定时入口：扫描到点的运营报表调度并触发 run（系统身份，按 workspace 切上下文）。
     *
     * @return 本轮成功触发次数
     */
    public int fireDueSchedules(Instant now) {
        Instant cursor = now == null ? Instant.now() : now;
        List<OperationalReportSchedule> due = scheduleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OperationalReportSchedule>()
                        .eq(OperationalReportSchedule::getEnabled, true)
                        .isNotNull(OperationalReportSchedule::getNextFireAt)
                        .le(OperationalReportSchedule::getNextFireAt, cursor)
                        .orderByAsc(OperationalReportSchedule::getNextFireAt)
                        .last("LIMIT " + FIRE_BATCH));
        if (due.isEmpty()) {
            return 0;
        }
        int fired = 0;
        for (OperationalReportSchedule schedule : due) {
            try {
                WorkspaceContext.callAs(
                        new Caller("system-scheduler", "system-scheduler",
                                schedule.getWorkspaceId(), true, Set.of()),
                        () -> run(schedule.getReportId(), new RunRequest(Map.of())));
                fired++;
            } catch (RuntimeException ex) {
                log.error("运营报表调度触发失败 schedule={} report={}", schedule.getId(), schedule.getReportId(), ex);
            } finally {
                schedule.setLastFireAt(cursor);
                Instant next = nextFireAfter(schedule.getCronExpr(), cursor);
                schedule.setNextFireAt(next);
                schedule.setUpdatedAt(Instant.now());
                scheduleMapper.updateById(schedule);
            }
        }
        if (fired > 0) {
            log.info("运营报表调度本轮触发 {} 次（候选 {}）", fired, due.size());
        }
        return fired;
    }

    private Instant nextFireAfter(String cronExpr, Instant after) {
        String normalized = normalizeCron(cronExpr);
        if (!CronExpression.isValidExpression(normalized)) {
            throw invalid("Cron 表达式无效: " + cronExpr);
        }
        ZonedDateTime next = CronExpression.parse(normalized)
                .next(ZonedDateTime.ofInstant(after, SCHEDULE_ZONE));
        return next == null ? null : next.toInstant();
    }

    private static String normalizeCron(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.SYS_VALIDATION_FAILED, "Cron 表达式不能为空");
        }
        String trimmed = raw.trim().replaceAll("\\s+", " ");
        return trimmed.split(" ").length == 5 ? "0 " + trimmed : trimmed;
    }

    private boolean requiresExportConfirm(String reportId) {
        if (scheduleMapper == null) {
            return false;
        }
        String ws = WorkspaceContext.requireWorkspaceId();
        return scheduleMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OperationalReportSchedule>()
                                .eq(OperationalReportSchedule::getWorkspaceId, ws)
                                .eq(OperationalReportSchedule::getReportId, reportId)
                                .eq(OperationalReportSchedule::getRequireExportConfirm, true)
                                .eq(OperationalReportSchedule::getEnabled, true)
                                .last("LIMIT 1"))
                .stream().findFirst().isPresent();
    }

    private ScheduleView toScheduleView(OperationalReportSchedule s) {
        List<String> recipients;
        try {
            recipients = objectMapper.readValue(s.getRecipientsJson() == null ? "[]" : s.getRecipientsJson(),
                    new TypeReference<>() {
                    });
        } catch (Exception ex) {
            recipients = List.of();
        }
        return new ScheduleView(s.getId(), s.getReportId(), s.getCronExpr(), recipients,
                Boolean.TRUE.equals(s.getEnabled()), Boolean.TRUE.equals(s.getRequireExportConfirm()));
    }

    /** 供单测：块校验。 */
    public List<ReportBlock> validateBlocks(List<ReportBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            throw invalid("blocks 不能为空");
        }
        List<ReportBlock> out = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            ReportBlock b = blocks.get(i);
            if (b == null || blank(b.type())) {
                throw invalid("blocks[" + i + "].type 必填");
            }
            String type = b.type().trim().toUpperCase(Locale.ROOT);
            if (!BLOCK_TYPES.contains(type)) {
                throw invalid("非法块类型 " + b.type() + "；允许: " + BLOCK_TYPES);
            }
            rejectSql(b.title(), "blocks[" + i + "].title");
            for (var e : b.payload().entrySet()) {
                rejectSql(e.getKey(), "blocks[" + i + "].payload.key");
                if (e.getValue() instanceof String s) {
                    rejectSql(s, "blocks[" + i + "].payload." + e.getKey());
                }
            }
            if ("SMQ_BLOCK".equals(type) && !b.payload().containsKey("metrics")
                    && !b.payload().containsKey("smq")) {
                throw invalid("SMQ_BLOCK 须包含 metrics 或 smq");
            }
            if ("STATIC_MD".equals(type) && !b.payload().containsKey("markdown")) {
                throw invalid("STATIC_MD 须包含 markdown");
            }
            out.add(new ReportBlock(type, b.title(), b.payload()));
        }
        return out;
    }

    public String renderMarkdown(OperationalReportDef def,
                                 OperationalReportVersion ver,
                                 List<ReportBlock> blocks,
                                 Map<String, Object> params) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(def.getName()).append("\n\n");
        md.append("- code: `").append(def.getCode()).append("`\n");
        md.append("- version: ").append(ver.getVersion()).append("\n");
        md.append("- params: `").append(writeJson(params)).append("`\n\n");
        for (ReportBlock b : blocks) {
            md.append("## ").append(b.title() == null ? b.type() : b.title()).append("\n\n");
            md.append("- type: `").append(b.type()).append("`\n");
            if ("STATIC_MD".equals(b.type())) {
                md.append(Objects.toString(b.payload().get("markdown"), "")).append("\n\n");
            } else {
                md.append("```json\n").append(writeJson(b.payload())).append("\n```\n\n");
                md.append("_块执行结果由运行时绑定 SMQ/Cohort/Stats 输出；本快照保留定义与参数。_\n\n");
            }
        }
        return md.toString();
    }

    public String renderCsv(List<ReportBlock> blocks, Map<String, Object> params) {
        StringBuilder csv = new StringBuilder("block_type,title,payload_json\n");
        for (ReportBlock b : blocks) {
            csv.append(escapeCsv(b.type())).append(',')
                    .append(escapeCsv(b.title())).append(',')
                    .append(escapeCsv(writeJson(b.payload()))).append('\n');
        }
        csv.append("PARAMS,,");
        csv.append(escapeCsv(writeJson(params))).append('\n');
        return csv.toString();
    }

    private ReproducibilityFingerprint buildFingerprint(OperationalReportDef def,
                                                        OperationalReportVersion ver,
                                                        Map<String, Object> params,
                                                        String artifact) {
        String sqlDigest = sha256(ver.getContentDigest() + "|" + writeJson(params) + "|" + artifact);
        return new ReproducibilityFingerprint(
                1,
                List.of(Map.of("datasetId", "operational-report", "snapshotRef", def.getId())),
                List.of(Map.of("code", def.getCode(), "versionNo", String.valueOf(ver.getVersion()),
                        "contentDigest", ver.getContentDigest())),
                List.of(),
                List.of(Map.of("operatorId", "OperationalReportRuntime", "version", "1", "digest", sqlDigest)),
                sqlDigest,
                ver.getContentDigest(),
                List.of(Map.of("principalType", "USER", "id",
                        WorkspaceContext.require().userId())),
                Map.of("truncated", false, "maxRows", 0),
                Instant.now().toString());
    }

    private OperationalReportVersion newVersion(OperationalReportDef def, int version, Integer supersedes,
                                                String blocksJson, String digest,
                                                String operator, Instant now) {
        OperationalReportVersion v = new OperationalReportVersion();
        v.setId(Ids.of("oprepv"));
        v.setReportId(def.getId());
        v.setWorkspaceId(def.getWorkspaceId());
        v.setVersion(version);
        v.setSupersedesVersion(supersedes);
        v.setBlocksJson(blocksJson);
        v.setContentDigest(digest);
        v.setCreatedBy(operator);
        v.setCreatedAt(now);
        return v;
    }

    private OperationalReportDef requireDef(String id, String ws) {
        OperationalReportDef def = defMapper.selectById(id);
        if (def == null || !ws.equals(def.getWorkspaceId())) {
            throw new BizException(ErrorCode.SYS_NOT_FOUND, "运营报表不存在");
        }
        return def;
    }

    private ReportView toView(OperationalReportDef def, String digest) {
        return new ReportView(def.getId(), def.getCode(), def.getName(), def.getStatus(),
                def.getLatestVersion() == null ? 0 : def.getLatestVersion(), digest);
    }

    private RunView toRunView(OperationalReportRun run) {
        return new RunView(run.getId(), run.getReportId(),
                run.getVersion() == null ? 0 : run.getVersion(),
                run.getStatus(), run.getReproducibilityFingerprint(),
                run.getArtifactMd(), run.getArtifactCsv(), run.getErrorMessage());
    }

    private List<ReportBlock> readBlocks(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw invalid("blocks_json 损坏: " + ex.getMessage());
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYS_INTERNAL_ERROR, "JSON 序列化失败");
        }
    }

    private static void rejectSql(String value, String field) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (SQL_SMELL.matcher(value).find()) {
            throw invalid("运营报表禁止内嵌 SQL: " + field);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.SYS_VALIDATION_FAILED, message);
    }

    private static String escapeCsv(String raw) {
        String v = raw == null ? "" : raw.replace("\"", "\"\"");
        return "\"" + v + "\"";
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
