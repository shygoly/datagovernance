package com.datagov.runtime.clinical;

import com.datagov.common.clinical.ClinicalOperator;
import com.datagov.common.clinical.ClinicalOperator.OperatorRequest;
import com.datagov.common.clinical.ClinicalOperator.OperatorResult;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确定性临床/统计算子沙箱执行面（仅已登记版本；禁止即席发明口径）。
 */
@Service
public class ClinicalStatsRuntime {

    private final Map<String, ClinicalOperator> operators;

    public ClinicalStatsRuntime() {
        this(List.of(
                new SofaTotalV1Operator(),
                new AlignToAnchorV1Operator(),
                new SplitTreatmentCyclesV1Operator(),
                new HccClinicalQcV1Operator()));
    }

    public ClinicalStatsRuntime(List<ClinicalOperator> operators) {
        Map<String, ClinicalOperator> map = new LinkedHashMap<>();
        for (ClinicalOperator op : operators == null ? List.<ClinicalOperator>of() : operators) {
            map.put(key(op.id(), op.version()), op);
            map.putIfAbsent(op.id(), op);
        }
        this.operators = Map.copyOf(map);
    }

    public List<Map<String, String>> listOperators() {
        return operators.values().stream()
                .distinct()
                .map(op -> Map.of("id", op.id(), "version", op.version()))
                .toList();
    }

    public OperatorResult execute(String operatorId, String version, OperatorRequest request) {
        ClinicalOperator op = version == null || version.isBlank()
                ? operators.get(operatorId)
                : operators.get(key(operatorId, version));
        if (op == null) {
            throw new IllegalArgumentException("未登记的 ClinicalOperator: " + operatorId
                    + (version == null ? "" : "@" + version));
        }
        return op.execute(request);
    }

    private static String key(String id, String version) {
        return id + "@" + version;
    }
}
