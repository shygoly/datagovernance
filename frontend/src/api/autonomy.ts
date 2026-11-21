import { http } from './request'
import type {
  AutonomyApproval,
  DecisionTrace,
  FilterPreset,
  FireResult,
  FlowCompileResult,
  FlowDefinition,
  FlowInstance,
  FlowPayload,
  FlowVersion,
  TriggerDefinition,
  TriggerEvaluation,
  TriggerPayload,
} from '@/types/autonomy'
import type { PageResult } from '@/types/api'

export const autonomyApi = {
  listTriggers(params: {
    page?: number
    size?: number
    keyword?: string
    sourceType?: string
    enabled?: boolean
  } = {}) {
    return http.get<PageResult<TriggerDefinition>>('/autonomy/triggers', params)
  },
  getTrigger(id: string) {
    return http.get<TriggerDefinition>(`/autonomy/triggers/${id}`)
  },
  createTrigger(payload: TriggerPayload) {
    return http.post<TriggerDefinition>('/autonomy/triggers', payload)
  },
  updateTrigger(id: string, payload: TriggerPayload) {
    return http.put<TriggerDefinition>(`/autonomy/triggers/${id}`, payload)
  },
  deleteTrigger(id: string) {
    return http.delete<void>(`/autonomy/triggers/${id}`)
  },
  testTrigger(id: string, snapshot: Record<string, unknown>) {
    return http.post<TriggerEvaluation>(`/autonomy/triggers/${id}/test`, snapshot)
  },
  fireTrigger(id: string, snapshot: Record<string, unknown>) {
    return http.post<FireResult>(`/autonomy/triggers/${id}/fire`, snapshot)
  },

  listFlows(params: {
    page?: number
    size?: number
    keyword?: string
    status?: string
    runMode?: string
  } = {}) {
    return http.get<PageResult<FlowDefinition>>('/autonomy/flows', params)
  },
  getFlow(id: string) {
    return http.get<FlowDefinition>(`/autonomy/flows/${id}`)
  },
  listVersions(id: string) {
    return http.get<FlowVersion[]>(`/autonomy/flows/${id}/versions`)
  },
  compile(spec: Record<string, unknown>) {
    return http.post<FlowCompileResult>('/autonomy/flows/compile', { spec })
  },
  saveFlow(payload: FlowPayload) {
    return http.post<FlowDefinition>('/autonomy/flows', payload)
  },
  publishFlow(id: string) {
    return http.post<FlowDefinition>(`/autonomy/flows/${id}/publish`)
  },
  deprecateFlow(id: string) {
    return http.post<FlowDefinition>(`/autonomy/flows/${id}/deprecate`)
  },
  dryRun(id: string, snapshot: Record<string, unknown>) {
    return http.post<FireResult>(`/autonomy/flows/${id}/dry-run`, snapshot)
  },
  promote(id: string, targetMode: string, note: string) {
    return http.post<FlowDefinition>(`/autonomy/flows/${id}/promote`, { targetMode, note })
  },
  killSwitch(note: string) {
    return http.post<{ demotedFlows: number; cancelledInstances: number }>(
      '/autonomy/flows/kill-switch',
      { note },
    )
  },

  getInstance(id: string) {
    return http.get<FlowInstance>(`/autonomy/instances/${id}`)
  },
  signalInstance(id: string, signal: string, payload: Record<string, unknown> = {}) {
    return http.post<FlowInstance>(`/autonomy/instances/${id}/signal`, { signal, payload })
  },
  cancelInstance(id: string, note: string) {
    return http.post<FlowInstance>(`/autonomy/instances/${id}/cancel`, { note })
  },

  listApprovals() {
    return http.get<AutonomyApproval[]>('/autonomy/approvals')
  },
  confirmApproval(id: string, payloadDigest: string, note?: string) {
    return http.post<AutonomyApproval>(`/autonomy/approvals/${id}/confirm`, {
      payloadDigest,
      note,
    })
  },
  rejectApproval(id: string, note?: string) {
    return http.post<AutonomyApproval>(`/autonomy/approvals/${id}/reject`, { note })
  },

  listTraces(params: {
    page?: number
    size?: number
    verifyStatus?: string
    runMode?: string
    flowId?: string
  } = {}) {
    return http.get<PageResult<DecisionTrace>>('/autonomy/traces', params)
  },
  getTrace(traceId: string) {
    return http.get<DecisionTrace>(`/autonomy/traces/${traceId}`)
  },
  getTraceByInstance(instanceId: string) {
    return http.get<DecisionTrace>(`/autonomy/traces/by-instance/${instanceId}`)
  },

  listFilterPresets(params: {
    page?: number
    size?: number
    keyword?: string
    presetType?: string
    status?: string
  } = {}) {
    return http.get<PageResult<FilterPreset>>('/metadata/filter-presets', params)
  },
  createFilterPreset(payload: {
    code: string
    name: string
    description?: string
    presetType: string
    spec: Record<string, unknown>
    status?: string
  }) {
    return http.post<FilterPreset>('/metadata/filter-presets', payload)
  },
  updateFilterPreset(id: string, payload: Record<string, unknown>) {
    return http.put<FilterPreset>(`/metadata/filter-presets/${id}`, payload)
  },
  deleteFilterPreset(id: string) {
    return http.delete<void>(`/metadata/filter-presets/${id}`)
  },
}
