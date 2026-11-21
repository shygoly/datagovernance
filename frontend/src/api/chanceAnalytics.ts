import { http } from './request'

export interface ChanceBoardResponse {
  data: Record<string, unknown>
  warnings: string[]
  reproducibilityFingerprint?: Record<string, unknown>
}

export const chanceAnalyticsApi = {
  cohortBaseline(): Promise<ChanceBoardResponse> {
    return http.get<ChanceBoardResponse>('/insight/chance/cohort-baseline')
  },
  treatmentPathway(): Promise<ChanceBoardResponse> {
    return http.get<ChanceBoardResponse>('/insight/chance/treatment-pathway')
  },
  survival(endpoint: 'OS' | 'PFS' = 'OS'): Promise<ChanceBoardResponse> {
    return http.get<ChanceBoardResponse>('/insight/chance/survival', { endpoint })
  },
}
