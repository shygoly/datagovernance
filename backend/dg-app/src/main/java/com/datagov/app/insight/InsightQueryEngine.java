package com.datagov.app.insight;

import com.datagov.app.insight.ChartSpecRecommender.ChartSpec;
import com.datagov.common.audit.ReproducibilityFingerprint;
import com.datagov.metadata.service.SemanticQueryCompiler.SemanticQuerySpec;

import java.util.List;
import java.util.Map;

/**
 * 受治理的问数引擎抽象接口（OpenSpec: semantic-ontology-kernel）。
 *
 * <p>彻底解耦并替代 SuperSonic：平台自身通过 {@link NativeInsightEngine} 基于
 * {@code dg-metadata} 语义本体内核 + 确定性 SQL 编译器 + {@code DataAccessGateway}
 * 提供人机共用单一语义真理源的只读问数能力。
 */
public interface InsightQueryEngine {

    record InsightColumn(String name, String bizName, String type) {
    }

    record DatasetOption(long id, String name, String domain, String viewId) {
        public DatasetOption(long id, String name, String domain) {
            this(id, name, domain, "view_" + id);
        }
    }

    record ClarificationOption(String code, String name, String disambiguation) {
    }

    record WarehouseSqlPass(
            String warehouseCode,
            String warehouseName,
            String cohortCode,
            String dialect,
            String sql
    ) {
    }

    record EngineAskResult(
            String queryId,
            String state,
            String sql,
            List<InsightColumn> columns,
            List<Map<String, Object>> rows,
            String summary,
            String error,
            List<String> semanticSnapshotRefs,
            String auditFingerprint,
            List<String> appliedExclusions,
            List<ClarificationOption> clarifications,
            SemanticQuerySpec executedSpec,
            ChartSpec chartSpec,
            String executionMode,
            String federationEngine,
            List<String> warehousesUsed,
            List<WarehouseSqlPass> warehouseSqls,
            ReproducibilityFingerprint reproducibilityFingerprint
    ) {
        public EngineAskResult(String queryId, String state, String sql,
                               List<InsightColumn> columns, List<Map<String, Object>> rows,
                               String summary, String error,
                               List<String> semanticSnapshotRefs,
                               String auditFingerprint,
                               List<String> appliedExclusions,
                               List<ClarificationOption> clarifications,
                               SemanticQuerySpec executedSpec,
                               ChartSpec chartSpec) {
            this(queryId, state, sql, columns, rows, summary, error,
                    semanticSnapshotRefs, auditFingerprint, appliedExclusions, clarifications,
                    executedSpec, chartSpec, null, null, List.of(), List.of(), null);
        }

        public EngineAskResult(String queryId, String state, String sql,
                               List<InsightColumn> columns, List<Map<String, Object>> rows,
                               String summary, String error,
                               List<String> semanticSnapshotRefs,
                               String auditFingerprint,
                               List<String> appliedExclusions,
                               List<ClarificationOption> clarifications,
                               SemanticQuerySpec executedSpec,
                               ChartSpec chartSpec,
                               String executionMode,
                               String federationEngine,
                               List<String> warehousesUsed,
                               List<WarehouseSqlPass> warehouseSqls) {
            this(queryId, state, sql, columns, rows, summary, error,
                    semanticSnapshotRefs, auditFingerprint, appliedExclusions, clarifications,
                    executedSpec, chartSpec, executionMode, federationEngine, warehousesUsed, warehouseSqls, null);
        }

        public EngineAskResult(String queryId, String state, String sql,
                               List<InsightColumn> columns, List<Map<String, Object>> rows,
                               String summary, String error,
                               List<String> semanticSnapshotRefs,
                               String auditFingerprint,
                               List<String> appliedExclusions,
                               List<ClarificationOption> clarifications) {
            this(queryId, state, sql, columns, rows, summary, error,
                    semanticSnapshotRefs, auditFingerprint, appliedExclusions, clarifications,
                    null, null, null, null, List.of(), List.of(), null);
        }

        public EngineAskResult(String queryId, String state, String sql,
                               List<InsightColumn> columns, List<Map<String, Object>> rows,
                               String summary, String error) {
            this(queryId, state, sql, columns, rows, summary, error,
                    List.of(), null, List.of(), List.of(), null, null,
                    null, null, List.of(), List.of(), null);
        }
    }

    String engineId();

    List<DatasetOption> datasets();

    EngineAskResult ask(String question, Long datasetId, SemanticQuerySpec explicitSpec);
}
