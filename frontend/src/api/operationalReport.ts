import { http } from './request'

export interface OperationalReportView {
  id: string
  code: string
  name: string
  status: string
  latestVersion: number
  contentDigest: string
}

export interface OperationalReportRunView {
  id: string
  reportId: string
  version: number
  status: string
  reproducibilityFingerprint?: string | null
  artifactMd?: string | null
  artifactCsv?: string | null
  errorMessage?: string | null
}

export interface PublishOperationalReportRequest {
  code: string
  name: string
  paramSchema?: Record<string, unknown>
  blocks: Array<{ type: string; title?: string; payload: Record<string, unknown> }>
}

export const operationalReportApi = {
  publish(payload: PublishOperationalReportRequest) {
    return http.post<OperationalReportView>('/governance/operational-reports', payload)
  },
  run(id: string, params?: Record<string, unknown>) {
    return http.post<OperationalReportRunView>(`/governance/operational-reports/${id}/runs`, { params })
  },
  exportUrl(runId: string, format: string) {
    return `/api/v1/governance/operational-reports/runs/${runId}/export?format=${encodeURIComponent(format)}`
  },
}
