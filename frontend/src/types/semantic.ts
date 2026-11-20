export type SemanticObjectType =
  | 'ENTITY'
  | 'ATTRIBUTE'
  | 'DIMENSION'
  | 'FACT'
  | 'METRIC'
  | 'CONCEPT'
  | 'PROCESS'

export type SemanticStatus = 'DRAFT' | 'PROPOSED' | 'PUBLISHED' | 'DEPRECATED' | 'REJECTED'

export type SemanticScope = 'WORKSPACE' | 'GLOBAL'

export type SemanticMappingRole = 'ID' | 'DESC' | 'CODE' | 'MEASURE' | 'TIME_KEY' | 'FILTER_KEY'

export type SemanticEdgeCategory = 'STRUCTURAL' | 'SEMANTIC' | 'CAUSAL'

export interface SemanticObject {
  id: string
  code: string
  name: string
  objectType: SemanticObjectType
  domainId?: string | null
  domainName?: string | null
  standardRef?: string | null
  glossaryTermId?: string | null
  spec: Record<string, unknown>
  aiContext: Record<string, unknown>
  maxSensitivity: 'L1' | 'L2' | 'L3' | 'L4' | string
  status: SemanticStatus
  versionNo: number
  validFrom?: string
  validTo?: string | null
  ownerUsername?: string | null
  contentDigest?: string | null
  sourceKind?: string
  rejectionReason?: string | null
  publishedBy?: string | null
  publishedAt?: string | null
  createdAt?: string
  updatedAt?: string
  mappingCount: number
  linkCount: number
  scope?: SemanticScope
}

export interface SemanticObjectPayload {
  code: string
  name: string
  objectType: SemanticObjectType
  domainId?: string | null
  standardRef?: string | null
  glossaryTermId?: string | null
  spec?: Record<string, unknown>
  aiContext?: Record<string, unknown>
  ownerUsername?: string | null
  sourceKind?: string
  scope?: SemanticScope
}

export interface SemanticMapping {
  id: string
  objectId: string
  mappingRole: SemanticMappingRole
  catalogDatasetId: string
  datasetName: string
  datasetQualifiedName: string
  catalogColumnId?: string | null
  columnName?: string | null
  columnSensitivity: string
  expressionSql?: string | null
  isLookupTable: boolean
  priority: number
  status: string
  createdAt?: string
  warehouseId?: string | null
  logicalTableId?: string | null
  valueNormalization?: Record<string, string>
  sourceUnit?: string | null
  targetUnit?: string | null
}

export interface SemanticMappingPayload {
  mappingRole: SemanticMappingRole
  catalogDatasetId: string
  catalogColumnId?: string | null
  expressionSql?: string | null
  isLookupTable?: boolean
  priority?: number
  warehouseId?: string | null
  logicalTableId?: string | null
  valueNormalization?: Record<string, string> | null
  sourceUnit?: string | null
  targetUnit?: string | null
}

export interface SemanticLink {
  id: string
  fromObjectId: string
  fromCode?: string | null
  fromName: string
  toObjectId: string
  toCode?: string | null
  toName: string
  edgeCategory: SemanticEdgeCategory
  linkType: string
  cardinality?: string | null
  joinSpec?: Record<string, unknown>[]
  rationale?: string | null
  sourceKind?: string
  status: SemanticStatus
  rejectionReason?: string | null
  validFrom?: string
  validTo?: string | null
  createdAt?: string
}

export interface SemanticLinkPayload {
  fromObjectId: string
  toObjectId: string
  edgeCategory: SemanticEdgeCategory
  linkType: string
  cardinality?: string | null
  joinSpec?: Record<string, unknown>[]
  rationale?: string | null
  sourceKind?: string
  proposeOnly?: boolean
}

export interface SemanticObjectVersion {
  id: string
  objectId: string
  versionNo: number
  snapshot: Record<string, unknown>
  contentDigest: string
  validFrom: string
  validTo?: string | null
  publishedBy?: string | null
  publishedAt: string
}

export interface SemanticValueDictEntry {
  id: string
  objectId: string
  rawValue: string
  displayLabel: string
  synonyms: string[]
  sourceKind: string
  createdAt?: string
}

export interface SemanticSubgraphNode {
  id: string
  code: string
  name: string
  objectType: SemanticObjectType
  standardRef?: string | null
  maxSensitivity: string
  versionNo: number
  contentDigest?: string | null
  hopDistance: number
  spec: Record<string, unknown>
  aiContext: Record<string, unknown>
  mappings: SemanticMapping[]
}

export interface SemanticSubgraphResult {
  query: string
  maxHops: number
  nodes: SemanticSubgraphNode[]
  links: SemanticLink[]
  prunedNodeCount: number
  prunedReasons: string[]
  snapshotRefs: string[]
}

export interface SemanticImpactItem {
  objectId: string
  code: string
  name: string
  objectType: SemanticObjectType
  status: SemanticStatus
  versionNo: number
  depth: number
  relationPath: string
}

export interface SemanticImpactResult {
  targetType: string
  targetId: string
  impactedObjects: SemanticImpactItem[]
}

export interface SemanticProposalItem {
  kind: 'OBJECT' | 'LINK'
  id: string
  title: string
  summary: string
  sourceKind: string
  status: SemanticStatus
  rejectionReason?: string | null
  payload: Record<string, unknown>
  createdAt?: string
}

export interface SemanticSummaryStats {
  publishedMetrics: number
  publishedObjects: number
  mappedDatasets: number
  totalLinks: number
  pendingProposals: number
}

export interface SemanticQuerySpec {
  semanticViewId?: string
  metrics: string[]
  dimensions?: string[]
  filters?: Array<{ objectCode: string; op: string; values: string[] }>
  timeRange?: { dimensionCode: string; start?: string; end?: string } | null
  orderBy?: Array<{ objectCode: string; dir: 'ASC' | 'DESC' }>
  limit?: number
}

export interface CompiledSemanticQuery {
  sql: string
  dialect: string
  primaryDatasetId?: string | null
  involvedDatasetIds: string[]
  columns: Array<{ name: string; bizName: string; role: string }>
  semanticSnapshotRefs: string[]
  auditFingerprint: string
  appliedExclusions: string[]
  fanoutCteUsed: boolean
  warnings: string[]
}

export interface SemanticPage<T> {
  records: T[]
  total: number
  page: number
  size: number
}

export type WarehouseRole = 'PRIMARY' | 'SECONDARY'
export type WarehouseCombineMode = 'SINGLE_ROUTE' | 'COHORT_UNION' | 'ENTITY_FEDERATED_JOIN'
export type FederationEngineType = 'AUTO' | 'STARROCKS' | 'DUCKDB'
export type LogicalTableRole = 'LOOKUP' | 'FACT' | 'BRIDGE' | 'VIEW_SQL'

export interface WorkspaceWarehouse {
  id: string
  datasourceId: string
  datasourceName: string
  datasourceType?: string | null
  code: string
  name: string
  warehouseRole: WarehouseRole
  cohortCode?: string | null
  dialect: string
  idNamespacePrefix?: string | null
  combineMode: WarehouseCombineMode
  federationEngine: FederationEngineType
  starrocksCatalogName?: string | null
  priority: number
  status: string
  logicalTableCount: number
  updatedAt?: string
}

export interface WorkspaceWarehousePayload {
  id?: string
  datasourceId: string
  code: string
  name: string
  warehouseRole?: WarehouseRole
  cohortCode?: string | null
  dialect?: string
  idNamespacePrefix?: string | null
  combineMode?: WarehouseCombineMode
  federationEngine?: FederationEngineType
  starrocksCatalogName?: string | null
  priority?: number
  status?: string
}

export interface LogicalTable {
  id: string
  warehouseId: string
  warehouseCode?: string | null
  warehouseName?: string | null
  cohortCode?: string | null
  catalogDatasetId: string
  datasetName: string
  datasetQualifiedName: string
  logicalName: string
  tableRole: LogicalTableRole
  aliasOfTableId?: string | null
  aliasOfName?: string | null
  logicalSize: number
  customViewSql?: string | null
  status: string
  updatedAt?: string
}

export interface LogicalTablePayload {
  id?: string
  warehouseId: string
  catalogDatasetId: string
  logicalName: string
  tableRole?: LogicalTableRole
  aliasOfTableId?: string | null
  logicalSize?: number
  customViewSql?: string | null
  status?: string
}

export interface WarehouseMappingCell {
  mappingId: string
  mappingRole: SemanticMappingRole
  logicalTableId?: string | null
  logicalTableName?: string | null
  catalogDatasetId: string
  datasetQualifiedName: string
  catalogColumnId?: string | null
  columnName?: string | null
  expressionSql?: string | null
  valueNormalization?: Record<string, string>
  sourceUnit?: string | null
  targetUnit?: string | null
  priority: number
}

export interface WarehouseMappingRow {
  warehouseId: string
  warehouseCode: string
  warehouseName: string
  cohortCode?: string | null
  dialect: string
  combineMode: string
  federationEngine: string
  mappings: WarehouseMappingCell[]
}

export interface HeterogeneousMappingMatrix {
  objectId: string
  objectCode: string
  objectName: string
  objectType: SemanticObjectType
  scope?: SemanticScope
  warehouses: WarehouseMappingRow[]
  unboundMappings: WarehouseMappingCell[]
}

export interface MultiWarehousePlan {
  mode: WarehouseCombineMode
  federationEngine: string
  subQueries: Array<{
    warehouseId: string
    warehouseCode: string
    warehouseName: string
    datasourceId?: string | null
    cohortCode?: string | null
    dialect: string
    idNamespacePrefix?: string | null
    primaryDatasetId?: string | null
    involvedDatasetIds: string[]
    sql: string
    columns: Array<{ name: string; bizName: string; role: string }>
  }>
  mergeColumns: Array<{
    outputKey: string
    label: string
    role: string
    aggKind: string
    sourceCol?: string | null
    sumCol?: string | null
    countCol?: string | null
  }>
  federatedJoinPlan?: {
    engine: string
    starRocksDatasourceId?: string | null
    extracts: Array<{
      alias: string
      warehouseId: string
      warehouseCode: string
      warehouseName: string
      datasourceId: string
      dialect: string
      datasetId: string
      joinKeyAlias: string
      extractSql: string
    }>
    federatedSql: string
  } | null
  columns: Array<{ name: string; bizName: string; role: string }>
  semanticSnapshotRefs: string[]
  auditFingerprint: string
  appliedExclusions: string[]
  warnings: string[]
}
