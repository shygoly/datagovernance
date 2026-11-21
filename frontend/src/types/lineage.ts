export interface LineageNode {
  id: string
  /** COLUMN / TABLE / DATASET；Hop-by-Hop ACL 截断时为 RESTRICTED（占位，名称恒为「[受限资产]」）。 */
  nodeType: string
  qualifiedName: string
  catalogDatasetId?: string | null
  catalogColumnId?: string | null
  displayName?: string | null
  externalRefType?: string | null
  externalRefId?: string | null
  /** CONFIRMED 敏感标签的最高等级 L1–L4（仅列/数据集节点）。 */
  sensitivityLevel?: string | null
}

export interface LineageEdge {
  id: string
  fromNodeId: string
  toNodeId: string
  relation: string
  transformExpr?: string | null
  confidence?: number | null
  origin?: string | null
  /** PLANNED（编译/发布预估）/ ACTIVE（执行确认或人工）/ SUPERSEDED（历史失效）。 */
  status?: LineageEdgeStatus | string | null
  jobId?: string | null
  jobName?: string | null
  jobType?: string | null
  /** 执行证据：PLANNED 晋升为 ACTIVE 时回填。 */
  executionId?: string | null
  dependencyType?: LineageDependencyType | string | null
  maskingApplied?: boolean | null
  maskingEvidence?: string | null
  discoveredAt?: string | null
}

export type LineageEdgeStatus = 'PLANNED' | 'ACTIVE' | 'SUPERSEDED'
export type LineageDependencyType = 'DIRECT' | 'INDIRECT'

export interface LineageGraph {
  nodes: LineageNode[]
  edges: LineageEdge[]
  /** 表级聚合时无法归属数据集的列级边数量。 */
  unattributedEdges?: number
  /** Hop-by-Hop ACL 截断的受限分支（占位节点）数量。 */
  restrictedCount?: number
  truncatedByAcl?: boolean
}

/** 图查询的可选参数。includePlanned=true 时请求 PLANNED 预估边。 */
export interface LineageGraphQuery {
  includePlanned?: boolean
  /** true 时并入下游治理消费方（METRIC / REPORT / QUALITY_RULE 节点，relation=CONSUMED_BY）；仅下游/影响/表级图生效。 */
  includeGovernance?: boolean
}

/** 治理消费方节点类型：由后端 SemanticLineageBridge 动态投影，ID 以 gov: 开头，不是血缘事实。 */
export type GovernanceNodeType = 'METRIC' | 'REPORT' | 'QUALITY_RULE'
export const GOVERNANCE_NODE_TYPES: readonly string[] = ['METRIC', 'REPORT', 'QUALITY_RULE']
/** 指向治理消费方的边 relation。 */
export const GOVERNANCE_EDGE_RELATION = 'CONSUMED_BY'

/** 概览：有血缘的数据集。 */
export interface LineageOverviewDataset {
  datasetId: string
  name: string
  qualifiedName: string
  upstreamEdges: number
  downstreamEdges: number
  lastDiscoveredAt?: string | null
}

/** 概览：最近血缘作业及解析状态。 */
export interface LineageOverviewJob {
  jobId: string
  name: string
  jobType: string
  /** OK / INCONCLUSIVE / ERROR */
  parseStatus?: string | null
  parseMessage?: string | null
  lastSeenAt?: string | null
  activeEdges: number
  plannedEdges: number
}

export interface LineageOverview {
  datasetsWithLineage: number
  activeColumnEdges: number
  activeJobs: number
  pendingSensitivitySuggestions: number
  recentDatasets: LineageOverviewDataset[]
  recentJobs: LineageOverviewJob[]
  /** 可选：字段 ID → 上下游边数。后端提供时字段下拉展示徽标。 */
  columnEdgeCounts?: Record<string, { upstreamEdges: number; downstreamEdges: number }>
}

/** 手工补录/导入时的节点引用，对应后端 NodeRef。 */
export interface LineageNodeRef {
  nodeType: 'COLUMN' | 'TABLE' | 'DATASET' | string
  qualifiedName: string
  catalogDatasetId?: string | null
  catalogColumnId?: string | null
  displayName?: string | null
}

/** 手工补录一条边，对应后端 EdgeInput。 */
export interface ManualLineageEdgeInput {
  from: LineageNodeRef
  to: LineageNodeRef
  relation?: string
  transformExpr?: string | null
  confidence?: number | null
  origin?: string | null
  dependencyType?: LineageDependencyType
  maskingApplied?: boolean
  maskingEvidence?: string | null
}

export type SqlDialect =
  | 'postgres'
  | 'mysql'
  | 'doris'
  | 'starrocks'
  | 'oracle'
  | 'spark'
  | 'tsql'
  | 'hive'

export interface SqlLineageRequest {
  dialect: SqlDialect | string
  sql: string
  /** 导入时作为血缘作业名称；预览可不传。 */
  jobName?: string
  defaultSchema?: string
}

export type SqlParseStatus = 'OK' | 'INCONCLUSIVE' | 'ERROR'

export interface SqlParsedEdge {
  sourceTable: string
  sourceColumn: string
  targetTable: string
  targetColumn: string
  dependencyType?: LineageDependencyType | string | null
  relation?: string | null
  transformExpr?: string | null
  maskingApplied?: boolean | null
}

export interface SqlParsedStatement {
  targetTable?: string | null
  edges: SqlParsedEdge[]
  warnings?: string[]
}

export interface SqlParseDiagnostic {
  code?: string | null
  message: string
  severity?: string | null
}

export interface SqlLineagePreview {
  status: SqlParseStatus
  sqlHash?: string | null
  statements: SqlParsedStatement[]
  /** 后端可能返回字符串或结构化诊断，前端统一按文本展示。 */
  diagnostics?: Array<string | SqlParseDiagnostic>
}

export interface SqlLineageImportResult {
  jobId: string
  edges: number
  parseStatus?: string | null
  parseMessage?: string | null
}

export interface LineageJobResult {
  jobId: string
  edges: number
}

/** 图上的节点相对中心字段的位置：负数上游、正数下游、0 为中心。 */
export interface LineageLayoutNode extends LineageNode {
  offset: number
  level: number
  side: 'UPSTREAM' | 'DOWNSTREAM' | 'CENTER'
}

/** 影响分析表格的一行。始终来自 impact API，而不是图内过滤结果。 */
export interface ImpactItem {
  id: string
  level: number
  nodeType: string
  qualifiedName: string
  displayName?: string | null
  catalogDatasetId?: string | null
  catalogColumnId?: string | null
  /** 表级影响清单用：该节点关联的表级边数量。 */
  edgeCount?: number
  /** 治理消费方（METRIC / REPORT / QUALITY_RULE）的外部对象引用。 */
  externalRefType?: string | null
  externalRefId?: string | null
}

/** 历史 Diff 的一行；触及受限数据集的行名称为 [受限资产] 且 restricted=true。 */
export interface LineageDiffItem {
  edgeId: string
  fromNodeId: string
  fromName?: string | null
  toNodeId: string
  toName?: string | null
  relation?: string | null
  dependencyType?: string | null
  transformExpr?: string | null
  /** 仅 modifiedTransform 行有值：改写前的内容。 */
  previousRelation?: string | null
  previousDependencyType?: string | null
  previousTransformExpr?: string | null
  restricted: boolean
}

/** 作业血缘历史 Diff：当前 ACTIVE vs 最近一批改动。 */
export interface LineageJobDiff {
  jobId: string
  jobName?: string | null
  jobType?: string | null
  /** false：没有任何历史改动可对比，三个列表恒为空。 */
  hasBaseline: boolean
  baselineAt?: string | null
  activeEdges: number
  added: LineageDiffItem[]
  removed: LineageDiffItem[]
  modifiedTransform: LineageDiffItem[]
  restrictedCount: number
  truncatedByAcl: boolean
}
