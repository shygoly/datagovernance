import request from './request'

export interface EvolutionProposal {
  id: string
  workspaceId: string
  assetRef: string
  factType: string
  title: string
  contentJson: string
  source: string
  feedbackSource: string
  evidenceRef: string
  confidence: number
  version: number
  supersedesId?: string | null
  state: 'PROPOSED' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED' | 'ROLLED_BACK' | string
  patternFingerprint?: string | null
  rejectionReason?: string | null
  reviewNote?: string | null
  createdBy: string
  createdAt: string
}

export const evolutionApi = {
  listProposals(params?: { state?: string; assetRef?: string }): Promise<EvolutionProposal[]> {
    return request.get('/governance/evolution/proposals', { params })
  },
  approveProposal(id: string, note?: string): Promise<EvolutionProposal> {
    return request.post(`/governance/evolution/proposals/${encodeURIComponent(id)}/approve`, { note })
  },
  rejectProposal(id: string, reason: string): Promise<EvolutionProposal> {
    return request.post(`/governance/evolution/proposals/${encodeURIComponent(id)}/reject`, { reason })
  },
  rollbackProposal(id: string, reason: string): Promise<EvolutionProposal> {
    return request.post(`/governance/evolution/proposals/${encodeURIComponent(id)}/rollback`, { reason })
  },
}
