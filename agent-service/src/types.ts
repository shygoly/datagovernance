export interface UiToolStep {
  id: string
  name: string
  status: 'running' | 'ok' | 'error'
  args?: unknown
  summary?: string
  startedAt: string
  finishedAt?: string
}

export type UiBlockComponent =
  | 'FilterFunnelCard'
  | 'DistributionProfileCard'
  | 'RuleDiffProposalCard'

export interface UiBlock {
  component: UiBlockComponent
  version: '1'
  props: Record<string, unknown>
}

export interface UiTurn {
  id: string
  question: string
  datasetId?: number
  status: 'running' | 'done' | 'error' | 'aborted'
  text: string
  thinking: string
  tools: UiToolStep[]
  uiBlocks?: UiBlock[]
  answer?: unknown
  error?: string
  createdAt: string
  finishedAt?: string
}

export interface StoredEvent {
  seq: number
  type: string
  turnId?: string
  payload: Record<string, unknown>
}

export interface Conversation {
  id: string
  workspaceId: string
  userId?: string
  platformAdmin?: boolean
  username: string
  title: string
  datasetId?: number
  datasetLabel?: string
  activeSkills?: string[]
  createdAt: string
  updatedAt: string
  messages: unknown[]
  turns: UiTurn[]
  events: StoredEvent[]
  seq: number
  running: boolean
}

export interface RunContext {
  conversationId: string
  workspaceId: string
  userId?: string
  platformAdmin?: boolean
  username: string
  datasetId?: number
  datasetLabel?: string
  maxToolCallsPerTurn?: number
  maxConsecutiveErrors?: number
}

export interface ProposalSummary {
  id: string
  actionType: string
  requiredPermission: string
  title: string
  summary: string
  payload: Record<string, unknown>
  payloadDigest: string
  riskLevel: string
  status: string
  expiresAt: string
}
