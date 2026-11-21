package com.datagov.metadata.service.lineage;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.common.tenant.WorkspaceContext;
import com.datagov.metadata.service.LineageCatalogResolver;
import com.datagov.metadata.service.LineageService;
import com.datagov.metadata.service.LineageService.EdgeInput;
import com.datagov.metadata.service.LineageService.JobRequest;
import com.datagov.metadata.service.LineageService.JobResult;
import com.datagov.metadata.service.LineageService.NodeRef;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OpenLineage 1.x 接入(column-lineage-e2e P2-3)。
 *
 * <p>只消费 {@code eventType = COMPLETE} 的 {@code RunEvent} 里
 * {@code outputs[].facets.columnLineage}：START/RUNNING/FAIL/ABORT 都不代表数据真的被写出，
 * 不能当成 ACTIVE 事实(C8)。
 *
 * <p>每个 dataset 与列按 {@link LineageCatalogResolver} 对齐 Catalog 资产；解析不到的仍写边，
 * 但不绑定 catalog ID(与其他采集链路一致，等历史回填)，响应里的 {@code unalignedColumns}
 * 会告诉调用方有多少端点没有对上。
 *
 * <p>鉴权不在这里：调用方(Controller)负责 {@code metadata:lineage:manage} 与 workspace 上下文，
 * 写入审计由 AuditInterceptor 记录。本服务只信 {@link WorkspaceContext}，不接受 payload 里
 * 任何租户信息。
 */
@Service
public class OpenLineageService {

    public static final String JOB_TYPE = "OPENLINEAGE";
    public static final String ORIGIN = "OPENLINEAGE";

    static final int MAX_EDGES_PER_EVENT = 20_000;
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_EXPR_LENGTH = 2000;

    private final LineageService lineageService;
    private final LineageCatalogResolver resolver;

    public OpenLineageService(LineageService lineageService, LineageCatalogResolver resolver) {
        this.lineageService = lineageService;
        this.resolver = resolver;
    }

    /**
     * @param status            ACCEPTED（已写入）/ IGNORED（非 COMPLETE 或没有列级血缘）
     * @param unalignedColumns  没有对上 Catalog 列的端点数（去重后）
     */
    public record IngestResult(String status, String message, String jobId, int edges,
                               int outputs, int unalignedColumns) {
    }

    public IngestResult ingest(JsonNode event) {
        String workspaceId = WorkspaceContext.requireWorkspaceId();
        if (event == null || !event.isObject()) {
            throw invalid("OpenLineage 事件必须是 JSON 对象");
        }
        String eventType = text(event, "eventType");
        if (eventType == null) {
            throw invalid("OpenLineage RunEvent 缺少 eventType");
        }
        if (!"COMPLETE".equalsIgnoreCase(eventType)) {
            return new IngestResult("IGNORED",
                    "仅 COMPLETE 事件会写入血缘，当前 eventType=" + eventType, null, 0, 0, 0);
        }
        JsonNode job = event.path("job");
        String jobName = text(job, "name");
        if (jobName == null) {
            throw invalid("OpenLineage RunEvent 缺少 job.name");
        }
        String jobNamespace = text(job, "namespace");
        String runId = text(event.path("run"), "runId");

        Map<String, EdgeInput> edges = new LinkedHashMap<>();
        Set<String> unaligned = new LinkedHashSet<>();
        int outputs = 0;
        for (JsonNode output : iterable(event.path("outputs"))) {
            JsonNode columnLineage = output.path("facets").path("columnLineage");
            if (!columnLineage.isObject()) {
                continue;
            }
            outputs++;
            collectOutput(workspaceId, output, columnLineage, edges, unaligned);
        }
        if (edges.isEmpty()) {
            return new IngestResult("IGNORED",
                    outputs == 0 ? "事件的 outputs 中没有 columnLineage facet" : "columnLineage 未解析出任何有效列映射",
                    null, 0, outputs, 0);
        }

        String name = jobNamespace == null ? jobName : jobNamespace + ":" + jobName;
        JobResult result = lineageService.recordJob(new JobRequest(
                truncate(name, MAX_NAME_LENGTH), JOB_TYPE,
                runId == null ? null : truncate("openlineage:" + runId, MAX_NAME_LENGTH),
                sqlHash(event), new ArrayList<>(edges.values()), "OK", null));
        return new IngestResult("ACCEPTED", "已写入 ACTIVE 血缘", result.jobId(), result.edges(),
                outputs, unaligned.size());
    }

    private void collectOutput(String workspaceId, JsonNode output, JsonNode columnLineage,
                               Map<String, EdgeInput> edges, Set<String> unaligned) {
        String outNamespace = text(output, "namespace");
        String outName = text(output, "name");
        if (outName == null) {
            return;
        }
        JsonNode fields = columnLineage.path("fields");
        Map<String, NodeRef> outputRefs = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = fields.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String column = entry.getKey();
            NodeRef to = ref(workspaceId, outNamespace, outName, column, unaligned);
            outputRefs.put(column, to);
            JsonNode field = entry.getValue();
            String fieldType = text(field, "transformationType");
            String fieldDescription = text(field, "transformationDescription");
            for (JsonNode input : iterable(field.path("inputFields"))) {
                addEdge(workspaceId, edges, unaligned, input, to, fieldType, fieldDescription, false);
            }
        }
        // dataset 级：影响所有输出列的间接依赖(过滤/连接/分组…)。
        for (JsonNode input : iterable(columnLineage.path("dataset"))) {
            for (NodeRef to : outputRefs.values()) {
                addEdge(workspaceId, edges, unaligned, input, to, null, null, true);
            }
        }
    }

    private void addEdge(String workspaceId, Map<String, EdgeInput> edges, Set<String> unaligned,
                         JsonNode input, NodeRef to, String fieldType, String fieldDescription,
                         boolean datasetLevel) {
        String inName = text(input, "name");
        String inField = text(input, "field");
        if (inName == null || inField == null) {
            return;
        }
        NodeRef from = ref(workspaceId, text(input, "namespace"), inName, inField, unaligned);
        if (from.qualifiedName().equals(to.qualifiedName())) {
            return;
        }

        boolean direct = !datasetLevel;
        boolean masking = "MASKED".equalsIgnoreCase(fieldType);
        String subtype = null;
        List<String> descriptions = new ArrayList<>();
        if (fieldDescription != null) {
            descriptions.add(fieldDescription);
        }
        JsonNode transformations = input.path("transformations");
        if (transformations.isArray() && !transformations.isEmpty()) {
            boolean anyTyped = false;
            boolean anyDirect = false;
            for (JsonNode t : transformations) {
                String type = text(t, "type");
                if (type != null) {
                    anyTyped = true;
                    anyDirect |= "DIRECT".equalsIgnoreCase(type);
                }
                if (subtype == null) {
                    subtype = text(t, "subtype");
                }
                masking |= t.path("masking").asBoolean(false);
                String description = text(t, "description");
                if (description != null) {
                    descriptions.add(description);
                }
            }
            // 显式声明了 type 就以它为准：只出现 INDIRECT 即间接依赖，不能因为在 fields 里就当成直接取值。
            if (anyTyped) {
                direct = anyDirect;
            }
        }

        String dependency = direct ? "DIRECT" : "INDIRECT";
        // 直接/间接依赖同一对列时 relation 必须不同，否则会撞边的唯一键。
        String relation = direct ? "DERIVE"
                : subtype == null ? "INDIRECT" : subtype.trim().toUpperCase(Locale.ROOT);
        String expr = descriptions.isEmpty() ? null
                : truncate(String.join("; ", new LinkedHashSet<>(descriptions)), MAX_EXPR_LENGTH);
        EdgeInput edge = new EdgeInput(from, to, relation, expr, 1.0, ORIGIN, dependency, masking,
                masking ? "OpenLineage 标记为脱敏(masking / MASKED)" : null);

        String key = from.qualifiedName() + "|" + to.qualifiedName() + "|" + relation;
        if (edges.size() >= MAX_EDGES_PER_EVENT && !edges.containsKey(key)) {
            throw invalid("单个 OpenLineage 事件的列级边超过上限 " + MAX_EDGES_PER_EVENT);
        }
        edges.putIfAbsent(key, edge);
    }

    /**
     * OpenLineage 的 dataset {@code name} 对关系库是 {@code database.schema.table}
     * (或 {@code schema.table} / {@code database.table})，对对象存储是路径。
     * 3 段按 db/schema/table，2 段按 db.table（与 schema.table 的候选限定名相同），
     * 其它形态整体作为表名交给 Resolver 的唯一后缀匹配。
     */
    private NodeRef ref(String workspaceId, String namespace, String datasetName, String column,
                        Set<String> unaligned) {
        String database = null;
        String schema = null;
        String table = datasetName;
        if (!datasetName.contains("/") && !datasetName.contains(":")) {
            String[] parts = datasetName.split("\\.");
            if (parts.length == 3) {
                database = parts[0];
                schema = parts[1];
                table = parts[2];
            } else if (parts.length == 2) {
                database = parts[0];
                table = parts[1];
            }
        }
        NodeRef node = resolver.resolveNodeRef(workspaceId, null, database, schema, table, column);
        if (node.catalogColumnId() == null) {
            unaligned.add(node.qualifiedName());
        }
        return node;
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    private static Iterable<JsonNode> iterable(JsonNode node) {
        return node != null && node.isArray() ? node : List.<JsonNode>of();
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 作业 SQL 同 SQL 解析链路一样存 sha256，不存明文；没有 sql facet 就不存。 */
    private static String sqlHash(JsonNode event) {
        String query = text(event.path("job").path("facets").path("sql"), "query");
        if (query == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(query.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.SYS_VALIDATION_FAILED, message);
    }
}
