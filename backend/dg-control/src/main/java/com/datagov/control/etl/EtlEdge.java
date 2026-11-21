package com.datagov.control.etl;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 有向边 from → to。{@code port} 仅 JOIN 用:{@code left} / {@code right};
 * 其它节点留空。
 */
public record EtlEdge(String from, String to, String port) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("from", from);
        map.put("to", to);
        if (port != null) {
            map.put("port", port);
        }
        return map;
    }
}
