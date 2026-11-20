import type { InsightAnswer } from './insight'

export interface AgentToolStep {
  id: string
  name: string
  status: 'running' | 'ok' | 'error'
  args?: unknown
  summary?: string
  startedAt: string
  finishedAt?: string
}

export interface AgentTurn {
  id: string
  question: string
  datasetId?: number
  status: 'running' | 'done' | 'error' | 'aborted'
  text: string
  thinking: string
  tools: AgentToolStep[]
  answer?: InsightAnswer
  error?: string
  createdAt: string
  finishedAt?: string
}

export interface AgentConversationSummary {
  id: string
  workspaceId: string
  username: string
  title: string
  datasetId?: number
  datasetLabel?: string
  createdAt: string
  updatedAt: string
  running: boolean
  turnCount: number
}

export interface AgentConversation extends AgentConversationSummary {
  turns: AgentTurn[]
  events?: Array<{ seq: number; type: string; turnId?: string }>
}

export interface AgentActionProposal {
  id: string
  workspaceId?: string
  userId?: string
  username?: string
  conversationId?: string
  actionType: string
  requiredPermission: string
  title: string
  summary: string
  payload: Record<string, unknown>
  payloadDigest: string
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH' | string
  status: 'PENDING' | 'EXECUTED' | 'REJECTED' | 'FAILED' | 'EXPIRED' | string
  result?: Record<string, unknown> | null
  errorMessage?: string | null
  expiresAt: string
  decidedBy?: string | null
  decidedAt?: string | null
  decisionNote?: string | null
  createdAt?: string
}

export interface AgentStreamEvent {
  seq: number
  type: string
  turnId?: string
  [key: string]: unknown
}
