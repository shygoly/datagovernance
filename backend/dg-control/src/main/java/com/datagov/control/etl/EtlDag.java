package com.datagov.control.etl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 拓扑排序(Kahn)。稳定:同层按节点声明顺序,两次编译的产物可比对。 */
public final class EtlDag {

    private EtlDag() {
    }

    /**
     * @param order     拓扑序的节点 id;有环时只含能排出来的那部分
     * @param cycleNodes 无法排出的节点 id(环上或被环阻塞的节点),无环时为空
     */
    public record TopoResult(List<String> order, List<String> cycleNodes) {
        public boolean hasCycle() {
            return !cycleNodes.isEmpty();
        }
    }

    /** 边引用了不存在的节点时,该边被忽略(由编译器另行报错)。 */
    public static TopoResult sort(List<EtlNode> nodes, List<EtlEdge> edges) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> out = new HashMap<>();
        for (EtlNode node : nodes) {
            indegree.put(node.id(), 0);
            out.put(node.id(), new ArrayList<>());
        }
        for (EtlEdge edge : edges) {
            if (!indegree.containsKey(edge.from()) || !indegree.containsKey(edge.to())) {
                continue;
            }
            out.get(edge.from()).add(edge.to());
            indegree.merge(edge.to(), 1, Integer::sum);
        }

        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, deg) -> {
            if (deg == 0) {
                queue.add(id);
            }
        });

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            order.add(id);
            for (String next : out.get(id)) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        if (order.size() == indegree.size()) {
            return new TopoResult(order, List.of());
        }
        TreeSet<String> stuck = new TreeSet<>(indegree.keySet());
        stuck.removeAll(order);
        return new TopoResult(order, List.copyOf(stuck));
    }
}
