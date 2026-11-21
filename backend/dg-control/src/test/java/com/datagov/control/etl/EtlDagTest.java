package com.datagov.control.etl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EtlDagTest {

    private static EtlNode n(String id) {
        return new EtlNode(id, "TRANSFORM_FILTER", EtlNodeKind.TRANSFORM_FILTER, null, Map.of());
    }

    @Test
    @DisplayName("无环图得到稳定的拓扑序")
    void topologicalOrder() {
        EtlDag.TopoResult result = EtlDag.sort(List.of(n("a"), n("b"), n("c"), n("d")),
                List.of(new EtlEdge("a", "c", null), new EtlEdge("b", "c", null), new EtlEdge("c", "d", null)));
        assertThat(result.hasCycle()).isFalse();
        assertThat(result.order()).containsExactly("a", "b", "c", "d");
    }

    @Test
    @DisplayName("环上节点与被环阻塞的下游都被报出")
    void cycle() {
        EtlDag.TopoResult result = EtlDag.sort(List.of(n("s"), n("a"), n("b"), n("t")),
                List.of(new EtlEdge("s", "a", null), new EtlEdge("a", "b", null),
                        new EtlEdge("b", "a", null), new EtlEdge("b", "t", null)));
        assertThat(result.hasCycle()).isTrue();
        assertThat(result.order()).containsExactly("s");
        assertThat(result.cycleNodes()).containsExactly("a", "b", "t");
    }
}
