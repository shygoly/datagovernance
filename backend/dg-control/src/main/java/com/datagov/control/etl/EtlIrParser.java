package com.datagov.control.etl;

import com.datagov.control.compile.CompileResult;
import com.datagov.control.compile.CompileStage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Map → {@link EtlPipelineIr}。只做<b>形状</b>层面的校验(类型、必填、id 格式、
 * 重复 id、schemaVersion);图结构与节点语义由编译器负责。
 */
final class EtlIrParser {

    /**
     * 节点 id:字母开头的字母数字下划线。限制得严是因为 id 要作为 DuckDB 视图名
     * 并出现在自定义 SQL 的 {{id}} 占位符里,宽松的字符集会给注入留口子。
     */
    static final Pattern NODE_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private EtlIrParser() {
    }

    static EtlPipelineIr parse(Map<String, Object> config, CompileResult.Collector collector) {
        if (config == null || config.isEmpty()) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "config",
                    "ETL 管道配置为空", "至少需要一个数据源、一个目标节点和连接它们的边");
            return null;
        }

        String version = EtlConfig.str(config, "schemaVersion");
        if (!EtlPipelineIr.SCHEMA_VERSION.equals(version)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "schemaVersion",
                    "schemaVersion 必须是 " + EtlPipelineIr.SCHEMA_VERSION + ",当前: " + version);
            return null;
        }

        int errorsBefore = collector.all().size();

        EtlErrorPolicy policy = parsePolicy(config, collector);

        List<EtlNode> nodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Object rawNodes = config.get("nodes");
        if (!(rawNodes instanceof List<?> nodeList) || nodeList.isEmpty()) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "nodes", "nodes 必须是非空数组");
        } else {
            for (int i = 0; i < nodeList.size(); i++) {
                parseNode(nodeList.get(i), i, seen, nodes, collector);
            }
        }

        List<EtlEdge> edges = new ArrayList<>();
        Object rawEdges = config.get("edges");
        if (rawEdges != null && !(rawEdges instanceof List<?>)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "edges", "edges 必须是数组");
        } else if (rawEdges instanceof List<?> edgeList) {
            for (int i = 0; i < edgeList.size(); i++) {
                parseEdge(edgeList.get(i), i, edges, collector);
            }
        }

        boolean newErrors = collector.all().stream().skip(errorsBefore)
                .anyMatch(d -> d.severity() == CompileResult.Severity.ERROR);
        if (newErrors) {
            return null;
        }
        return new EtlPipelineIr(version, policy, nodes, edges);
    }

    private static EtlErrorPolicy parsePolicy(Map<String, Object> config, CompileResult.Collector collector) {
        Object raw = config.get("errorPolicy");
        if (raw == null) {
            return EtlErrorPolicy.defaults();
        }
        if (!(raw instanceof Map<?, ?>)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "errorPolicy", "errorPolicy 必须是对象");
            return EtlErrorPolicy.defaults();
        }
        Map<String, Object> map = EtlConfig.map(config, "errorPolicy");
        String mode = EtlErrorPolicy.normalizeMode(EtlConfig.str(map, "mode"));
        if (!EtlErrorPolicy.MODES.contains(mode)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "errorPolicy.mode",
                    "errorPolicy.mode 无效: " + mode,
                    "可选值: FAIL_FAST / SKIP_BAD_ROWS(别名 SKIP) / ROUTE_TO_DEAD_LETTER");
        }
        Long maxBadRows = EtlConfig.longVal(map, "maxBadRows");
        if (map.get("maxBadRows") != null && (maxBadRows == null || maxBadRows < 0)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, "errorPolicy.maxBadRows",
                    "maxBadRows 必须是非负整数: " + map.get("maxBadRows"));
            maxBadRows = null;
        }
        Double maxBadRatio = null;
        if (map.get("maxBadRatio") != null) {
            try {
                maxBadRatio = Double.parseDouble(map.get("maxBadRatio").toString());
            } catch (NumberFormatException e) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, "errorPolicy.maxBadRatio",
                        "maxBadRatio 不是数字: " + map.get("maxBadRatio"));
            }
            if (maxBadRatio != null && (maxBadRatio < 0 || maxBadRatio > 1)) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, "errorPolicy.maxBadRatio",
                        "maxBadRatio 必须在 0~1 之间: " + maxBadRatio);
                maxBadRatio = null;
            }
        }
        return new EtlErrorPolicy(mode, maxBadRows, maxBadRatio);
    }

    private static void parseNode(Object raw, int index, Set<String> seen, List<EtlNode> nodes,
                                  CompileResult.Collector collector) {
        String location = "nodes[" + index + "]";
        if (!(raw instanceof Map<?, ?>)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, location, "节点必须是对象");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) raw;

        String id = EtlConfig.str(map, "id");
        if (id == null || !NODE_ID.matcher(id).matches()) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, location,
                    "节点 id 无效: " + id, "以字母开头,只含字母、数字、下划线,最长 64 位");
            return;
        }
        location = "nodes." + id;
        if (!seen.add(id)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, location, "节点 id 重复: " + id);
            return;
        }

        List<String> unknown = map.keySet().stream()
                .filter(k -> !EtlPipelineIr.KNOWN_NODE_KEYS.contains(k)).sorted().toList();
        if (!unknown.isEmpty()) {
            collector.warn(CompileStage.STRUCTURAL_VALIDATION, location,
                    "节点里有平台不认识的键: " + String.join(", ", unknown),
                    "它们不会生效;若本意是配置节点参数,请放进 config");
        }

        String kindRaw = EtlConfig.str(map, "kind");
        EtlNodeKind kind = EtlNodeKind.parse(kindRaw);
        if (kind == null) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, location + ".kind",
                    "未知的节点种类: " + kindRaw,
                    "可选值: " + String.join(" / ",
                            java.util.Arrays.stream(EtlNodeKind.values()).map(Enum::name).toList()));
        }
        if (map.get("config") != null && !(map.get("config") instanceof Map<?, ?>)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, location + ".config", "config 必须是对象");
            return;
        }
        nodes.add(new EtlNode(id, kindRaw, kind, EtlConfig.str(map, "name"),
                EtlConfig.map(map, "config")));
    }

    private static void parseEdge(Object raw, int index, List<EtlEdge> edges,
                                  CompileResult.Collector collector) {
        String location = "edges[" + index + "]";
        if (!(raw instanceof Map<?, ?>)) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, location, "边必须是对象");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) raw;
        String from = EtlConfig.str(map, "from");
        String to = EtlConfig.str(map, "to");
        if (from == null || to == null) {
            collector.error(CompileStage.STRUCTURAL_VALIDATION, location, "边必须同时有 from 与 to");
            return;
        }
        String port = EtlConfig.str(map, "port");
        if (port != null) {
            port = port.toLowerCase(java.util.Locale.ROOT);
            if (!port.equals("left") && !port.equals("right")) {
                collector.error(CompileStage.STRUCTURAL_VALIDATION, location + ".port",
                        "port 只能是 left / right: " + port);
                return;
            }
        }
        edges.add(new EtlEdge(from, to, port));
    }
}
