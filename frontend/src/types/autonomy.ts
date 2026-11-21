export type AutonomySourceType =
  | 'METRIC_THRESHOLD'
  | 'QUALITY_FAILURE'
  | 'ETL_ANOMALY'
  | 'DATA_ARRIVAL'

export type AutonomyRunMode = 'SHADOW' | 'L2_ASSISTED' | 'L1_AUTO'

export type VerifyStatus = 'PENDING' | 'IMPROVED' | 'NO_EFFECT' | 'DEGRADED' | 'INCONCLUSIVE'

export interface TriggerDefinition {
  id: string
  code: string
  name: string
  sourceType: AutonomySourceType
  condition: Record<string, unknown>
  debounceSec: number
  flowId: string
  flowCode?: string | null
  enabled: boolean
  priority: number
  lastFiredAt?: string | null
  updatedAt?: string | null
}

export interface TriggerPayload {
  code: string
  name: string
  sourceType: AutonomySourceType
  condition: Record<string, unknown>
  debounceSec?: number
  flowId: string
  enabled?: boolean
  priority?: number
}

export interface FlowDefinition {
  id: string
  code: string
  name: string
  description?: string | null
  status: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED' | string
  currentVersion: number
  runMode: AutonomyRunMode
  ownerUsername?: string | null
  spec: Record<string, unknown>
  compiled: Record<string, unknown>
  contentDigest?: string | null
  updatedAt?: string | null
}

export interface FlowVersion {
  id: string
  versionNo: number
  runMode: AutonomyRunMode
  contentDigest: string
  promotionNote?: string | null
  compiledAt: string
  compiledBy?: string | null
  spec: Record<string, unknown>
}

export interface FlowPayload {
  flowId?: string
  code: string
  name: string
  description?: string
  runMode?: AutonomyRunMode
  ownerUsername?: string
  spec: Record<string, unknown>
}

export interface CompileDiagnostic {
  code: string
  field: string
  message: string
}

export interface FlowCompileResult {
  ok: boolean
  plan?: Record<string, unknown> | null
  diagnostics: CompileDiagnostic[]
  refs: Record<string, unknown>
}

export interface TriggerEvaluation {
  fired: boolean
  reason: string
  sourceType: string
  snapshot: Record<string, unknown>
  metrics: Record<string, unknown>
}

export interface FireResult {
  triggerId: string
  fired: boolean
  reason: string
  traceId?: string | null
  instanceId?: string | null
  arbiterStatus?: string | null
}

export interface FlowInstance {
  id: string
  workspaceId: string
  flowId: string
  flowCode: string
  flowVersion: number
  triggerId?: string | null
  traceId?: string | null
  status: string
  currentStepId?: string | null
  entityKey: string
  runMode: AutonomyRunMode
  checkpoint: Record<string, unknown>
  history: Array<Record<string, unknown>>
  wakeAt?: string | null
  errorMessage?: string | null
  startedAt?: string | null
  finishedAt?: string | null
}

export interface DecisionTrace {
  id: string
  traceId: string
  instanceId?: string | null
  flowId?: string | null
  triggerId?: string | null
  runMode: AutonomyRunMode
  verifyStatus: VerifyStatus
  triggerSnapshot: Record<string, unknown>
  arbiterDecision: Record<string, unknown>
  dryRunResult: Record<string, unknown>
  proposalId?: string | null
  activityResult: Record<string, unknown>
  verifyResult: Record<string, unknown>
  reproducibilityFingerprint: Record<string, unknown>
  createdAt: string
  updatedAt: string
}

export interface AutonomyApproval {
  id: string
  workspaceId: string
  userId: string
  username: string
  actionType: string
  requiredPermission: string
  title: string
  summary: string
  payload: Record<string, unknown>
  payloadDigest: string
  riskLevel: string
  status: string
  result?: Record<string, unknown> | null
  errorMessage?: string | null
  expiresAt: string
  decidedBy?: string | null
  decidedAt?: string | null
  decisionNote?: string | null
  createdAt: string
}

export interface FilterPreset {
  id: string
  code: string
  name: string
  description?: string | null
  presetType: 'SMQ_FILTER' | 'COHORT' | 'QUALITY_SCOPE' | string
  spec: Record<string, unknown>
  status: 'ACTIVE' | 'DISABLED' | string
  createdAt?: string | null
  updatedAt?: string | null
}
