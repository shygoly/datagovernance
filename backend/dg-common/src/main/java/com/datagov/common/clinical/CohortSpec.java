package com.datagov.common.clinical;

import java.util.List;

/**
 * 临床/分析队列定义（定义态；编译与物化不在本 record）。
 */
public record CohortSpec(
        String code,
        String name,
        Anchor anchor,
        RelativeWindow window,
        List<CriterionNode> include,
        List<CriterionNode> exclude,
        String memberGrain,
        boolean allowOverlap
) {
    public CohortSpec {
        include = include == null ? List.of() : List.copyOf(include);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
        memberGrain = memberGrain == null || memberGrain.isBlank() ? "encounter_id" : memberGrain.trim();
    }

    public record Anchor(String event, String timeAttr) {
    }

    public record RelativeWindow(String startOffset, String endOffset) {
    }
}
