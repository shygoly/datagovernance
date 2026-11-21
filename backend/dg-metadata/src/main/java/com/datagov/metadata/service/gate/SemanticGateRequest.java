package com.datagov.metadata.service.gate;

import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.service.SemanticQueryCompiler.SemanticQuerySpec;

import java.util.List;
import java.util.Set;

/**
 * 统一门禁输入。{@code grain} 与 {@code requiredDatasetIds} 可在解析映射后补齐。
 */
public record SemanticGateRequest(
        String workspaceId,
        SemanticExecutionMode executionMode,
        SemanticQuerySpec spec,
        List<SemanticObject> metrics,
        List<SemanticObject> dimensions,
        String grain,
        Set<String> allowedDatasetIds,
        int maxRows,
        Set<String> requiredDatasetIds
) {
    public SemanticGateRequest {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        allowedDatasetIds = allowedDatasetIds == null ? Set.of() : Set.copyOf(allowedDatasetIds);
        requiredDatasetIds = requiredDatasetIds == null ? Set.of() : Set.copyOf(requiredDatasetIds);
        executionMode = executionMode == null ? SemanticExecutionMode.SINGLE : executionMode;
    }

    public SemanticGateRequest withGrain(String grain) {
        return new SemanticGateRequest(workspaceId, executionMode, spec, metrics, dimensions,
                grain, allowedDatasetIds, maxRows, requiredDatasetIds);
    }

    public SemanticGateRequest withMaxRows(int maxRows) {
        return new SemanticGateRequest(workspaceId, executionMode, spec, metrics, dimensions,
                grain, allowedDatasetIds, maxRows, requiredDatasetIds);
    }

    public SemanticGateRequest withRequiredDatasetIds(Set<String> requiredDatasetIds) {
        return new SemanticGateRequest(workspaceId, executionMode, spec, metrics, dimensions,
                grain, allowedDatasetIds, maxRows, requiredDatasetIds);
    }

    public SemanticGateRequest withAllowedDatasetIds(Set<String> allowedDatasetIds) {
        return new SemanticGateRequest(workspaceId, executionMode, spec, metrics, dimensions,
                grain, allowedDatasetIds, maxRows, requiredDatasetIds);
    }
}
