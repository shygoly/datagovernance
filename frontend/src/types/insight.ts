import type { SemanticQuerySpec } from './semantic'

export interface InsightColumn {
  name: string
  bizName?: string
  type?: string
}

export type InsightChartType =
  | 'kpi'
  | 'bar'
  | 'horizontal_bar'
  | 'stacked_bar'
  | 'line'
  | 'area'
  | 'pie'
  | 'scatter'
  | 'funnel'
  | 'table'

export interface InsightDrilldownOption {
  sourceDimensionCode: string
  sourceField: string
  nextDimensionCode: string
  nextDimensionName: string
  actionLabel: string
}

export interface InsightChartSpec {
  chartType: InsightChartType
  xField?: string | null
  seriesField?: string | null
  yFields: string[]
  units?: Record<string, string>
  dualAxis?: boolean
  defaultSort?: 'DESC' | 'ASC' | 'NONE' | string
  topN?: number | null
  supportedTypes?: InsightChartType[]
  drilldowns?: InsightDrilldownOption[]
}

export interface InsightClarificationOption {
  code: string
  name: string
  disambiguation: string
}

export interface InsightWarehouseSqlPass {
  warehouseCode: string
  warehouseName: string
  cohortCode?: string | null
  dialect: string
  sql: string
}

export interface InsightAnswer {
  queryId?: string
  state: string
  sql?: string
  columns: InsightColumn[]
  rows: Record<string, unknown>[]
  rowCount: number
  truncated: boolean
  elapsedMs: number
  maskedColumns: string[]
  grantIds?: string[]
  summary?: string
  semanticSnapshotRefs?: string[]
  auditFingerprint?: string | null
  reproducibilityFingerprint?: import('./semantic').ReproducibilityFingerprint | null
  appliedExclusions?: string[]
  clarifications?: InsightClarificationOption[]
  executedSpec?: SemanticQuerySpec | null
  chartSpec?: InsightChartSpec | null
  executionMode?: string | null
  federationEngine?: string | null
  warehousesUsed?: string[]
  warehouseSqls?: InsightWarehouseSqlPass[]
}

export interface InsightDataset {
  id: number
  name: string
  domain: string
  viewId?: string
}

export interface InsightConfig {
  enabled: boolean
  engine?: string
  dialect?: string
  masking: boolean
  maxRows: number
  defaultDatasetId?: number
  agentId?: number
  message?: string
}

export interface InsightQueryLog {
  id: string
  username?: string
  question: string
  queryState?: string
  generatedSql?: string
  rowCount?: number
  elapsedMs?: number
  maskedColumns?: string
  grantIds?: string
  succeeded: boolean
  errorMessage?: string
  createdAt: string
  datasetId?: number | null
}

export interface InsightDashboard {
  id: string
  name: string
  description?: string
  itemCount: number
  version?: number
  visibility?: string
  createdBy?: string
  updatedAt: string
}

export interface InsightDashboardItem {
  id: string
  question: string
  datasetId?: number
  chartType: string
  sortOrder: number
  layoutJson?: string
}

export interface InsightDashboardDetail {
  id: string
  name: string
  description?: string
  version?: number
  visibility?: string
  createdBy?: string
  updatedAt: string
  items: InsightDashboardItem[]
}

export interface InsightPage<T> {
  records: T[]
  total: number
  page: number
  size: number
}

export interface InsightDashboardRunItem {
  itemId: string
  question: string
  succeeded: boolean
  answer?: InsightAnswer
  error?: string
}

export interface InsightDashboardRun {
  dashboardId: string
  name: string
  refreshedAt: string
  items: InsightDashboardRunItem[]
}

export interface InsightDashboardVersion {
  version: number
  createdBy?: string
  createdAt: string
  itemCount: number
}

export interface InsightDashboardAcl {
  username: string
  permission: 'READ' | 'MANAGE'
}

export interface InsightAclCandidate {
  username: string
  displayName?: string | null
  status: string
}

export interface InsightDashboardItemUpdate {
  sortOrder?: number
  layoutJson?: string
}
