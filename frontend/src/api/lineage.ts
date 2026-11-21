import { http } from './request'
import type {
  LineageEdge,
  LineageGraph,
  LineageGraphQuery,
  LineageJobDiff,
  LineageNode,
  LineageOverview,
  ManualLineageEdgeInput,
  SqlLineageImportResult,
  SqlLineagePreview,
  SqlLineageRequest,
} from '@/types/lineage'

/**
 * 仅在需要时附加 includePlanned / includeGovernance，默认请求与旧后端保持一致
 * （只返回 ACTIVE 血缘、不含治理消费方）。
 */
function graphParams(base: Record<string, unknown>, query?: LineageGraphQuery) {
  const params: Record<string, unknown> = { ...base }
  if (query?.includePlanned) {
    params.includePlanned = true
  }
  if (query?.includeGovernance) {
    params.includeGovernance = true
  }
  return params
}

/** 治理消费方只存在于下游，上游请求不携带该参数。 */
function upstreamParams(base: Record<string, unknown>, query?: LineageGraphQuery) {
  return graphParams(base, query ? { includePlanned: query.includePlanned } : undefined)
}

/** 字段级血缘。所有查询都走平台权限与 workspace。 */
export const lineageApi = {
  /** 工作台概览：KPI、有血缘的数据集、最近作业。 */
  overview(): Promise<LineageOverview> {
    return http.get<LineageOverview>('/metadata/lineage/overview')
  },
  node(id: string): Promise<LineageNode> {
    return http.get<LineageNode>(`/metadata/lineage/nodes/${id}`)
  },
  /** 边详情（含作业与执行证据）。 */
  edge(id: string): Promise<LineageEdge> {
    return http.get<LineageEdge>(`/metadata/lineage/edges/${id}`)
  },
  upstream(columnId: string, depth = 3, query?: LineageGraphQuery): Promise<LineageGraph> {
    return http.get<LineageGraph>(
      `/metadata/lineage/columns/${columnId}/upstream`,
      upstreamParams({ depth }, query),
    )
  },
  downstream(columnId: string, depth = 3, query?: LineageGraphQuery): Promise<LineageGraph> {
    return http.get<LineageGraph>(
      `/metadata/lineage/columns/${columnId}/downstream`,
      graphParams({ depth }, query),
    )
  },
  impact(columnId: string, depth = 5, query?: LineageGraphQuery): Promise<LineageGraph> {
    return http.get<LineageGraph>(
      `/metadata/lineage/columns/${columnId}/impact`,
      graphParams({ depth }, query),
    )
  },
  datasetGraph(
    datasetId: string,
    direction: 'upstream' | 'downstream' | 'both',
    depth = 2,
    query?: LineageGraphQuery,
  ): Promise<LineageGraph> {
    return http.get<LineageGraph>(
      `/metadata/lineage/datasets/${datasetId}/graph`,
      graphParams({ direction, depth }, query),
    )
  },
  /** 展开表级聚合边 dsedge:{from}->{to} 背后的列级明细。 */
  columnEdgesBetween(
    fromDatasetId: string,
    toDatasetId: string,
    query?: LineageGraphQuery,
  ): Promise<LineageGraph> {
    return http.get<LineageGraph>(
      `/metadata/lineage/datasets/${encodeURIComponent(fromDatasetId)}/column-edges`,
      graphParams({ toDatasetId }, query ? { includePlanned: query.includePlanned } : undefined),
    )
  },
  /** 作业血缘历史 Diff：当前 ACTIVE vs 最近一批 SUPERSEDED / 表达式改写。 */
  jobDiff(jobId: string): Promise<LineageJobDiff> {
    return http.get<LineageJobDiff>(`/metadata/lineage/jobs/${encodeURIComponent(jobId)}/diff`)
  },
  /** 手工补录一条列级血缘（需要 metadata:lineage:manage）。 */
  recordManualEdge(input: ManualLineageEdgeInput): Promise<LineageEdge> {
    return http.post<LineageEdge>('/metadata/lineage/edges', input)
  },
  /** 粘贴 SQL 解析预览，不落库。无法确定时返回 INCONCLUSIVE 与诊断。 */
  parseSqlPreview(request: SqlLineageRequest): Promise<SqlLineagePreview> {
    return http.post<SqlLineagePreview>('/metadata/lineage/sql/parse-preview', request)
  },
  /** 确认后入库（需要 metadata:lineage:manage）。 */
  importSql(request: SqlLineageRequest): Promise<SqlLineageImportResult> {
    return http.post<SqlLineageImportResult>('/metadata/lineage/sql/import', request)
  },
}
