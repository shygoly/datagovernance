import { http } from './request'

export interface MappingProposal {
  id: string
  sourceTable: string
  sourceField: string
  recommendedBusinessUrn?: string | null
  confidence: number
  routingTier: string
  state: string
  evidenceJson?: string
  decisionNote?: string | null
  suggestionFingerprint?: string
}

export const mappingProposalApi = {
  list(state?: string): Promise<MappingProposal[]> {
    return http.get<MappingProposal[]>('/governance/mapping-proposals', state ? { state } : undefined)
  },
  suggest(body: Record<string, unknown>): Promise<MappingProposal[]> {
    return http.post<MappingProposal[]>('/governance/mapping-proposals/suggest', body)
  },
  approve(id: string, note?: string, amendedUrn?: string): Promise<MappingProposal> {
    return http.post<MappingProposal>(`/governance/mapping-proposals/${encodeURIComponent(id)}/approve`, {
      note,
      amendedUrn,
    })
  },
  reject(id: string, note: string): Promise<MappingProposal> {
    return http.post<MappingProposal>(`/governance/mapping-proposals/${encodeURIComponent(id)}/reject`, {
      note,
    })
  },
}
