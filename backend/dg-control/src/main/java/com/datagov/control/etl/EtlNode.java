package com.datagov.control.etl;

import java.util.LinkedHashMap;
import java.util.Map;

/** IR 里的一个节点。{@code kind} 为 null 表示种类无法识别(已由解析器报错)。 */
public record EtlNode(String id, String kindRaw, EtlNodeKind kind, String name,
                      Map<String, Object> config) {

    public EtlNode {
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("kind", kind == null ? kindRaw : kind.name());
        if (name != null) {
            map.put("name", name);
        }
        map.put("config", config);
        return map;
    }
}
