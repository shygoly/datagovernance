package com.datagov.control.etl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * etl.ir/v1 —— 声明式 ETL 管道的中间表示。
 *
 * <p>它是 JobDefinition.configJson 的根结构:
 * <pre>
 * {
 *   "schemaVersion": "etl.ir/v1",
 *   "engine": "DUCKDB" | "SEATUNNEL"  (可选,缺省 DUCKDB;见 EtlExecutionEngine SPI),
 *   "errorPolicy": { "mode": "FAIL_FAST" | "SKIP_BAD_ROWS" | "ROUTE_TO_DEAD_LETTER", "maxBadRows": 100, "maxBadRatio": 0.01 },
 *   "nodes": [ { "id": "src", "kind": "SOURCE_JDBC", "name": "...", "config": { ... } }, ... ],
 *   "edges": [ { "from": "src", "to": "flt", "port": "left|right (仅 JOIN)" }, ... ]
 * }
 * </pre>
 * 构造后的对象不保证合法 —— 合法性由 {@code EtlPipelineCompiler} 逐项校验并带定位报错。
 */
public record EtlPipelineIr(String schemaVersion, EtlErrorPolicy errorPolicy,
                            List<EtlNode> nodes, List<EtlEdge> edges) {

    public static final String SCHEMA_VERSION = "etl.ir/v1";

    /** IR 顶层认识的键;其它键会被 warnUnknownKeys 报出。 */
    public static final Set<String> KNOWN_KEYS =
            Set.of("schemaVersion", "engine", "errorPolicy", "nodes", "edges");

    /** 节点对象认识的键。 */
    public static final Set<String> KNOWN_NODE_KEYS =
            Set.of("id", "kind", "name", "config", "position");

    public EtlPipelineIr {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        errorPolicy = errorPolicy == null ? EtlErrorPolicy.defaults() : errorPolicy;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", schemaVersion);
        map.put("errorPolicy", errorPolicy.toMap());
        map.put("nodes", nodes.stream().map(EtlNode::toMap).toList());
        map.put("edges", edges.stream().map(EtlEdge::toMap).toList());
        return map;
    }

    public EtlNode node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst().orElse(null);
    }

    /** 从 JobDefinition 的 config Map 解析。结构性问题记入 collector,返回 null 表示无法继续。 */
    public static EtlPipelineIr parse(Map<String, Object> config,
                                      com.datagov.control.compile.CompileResult.Collector collector) {
        return EtlIrParser.parse(config, collector);
    }
}
