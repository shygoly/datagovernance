import { http } from './request'
import type {
  CompiledSemanticQuery,
  HeterogeneousMappingMatrix,
  LogicalTable,
  LogicalTablePayload,
  MultiWarehousePlan,
  SemanticImpactResult,
  SemanticLink,
  SemanticLinkPayload,
  SemanticMapping,
  SemanticMappingPayload,
  SemanticObject,
  SemanticObjectPayload,
  SemanticObjectVersion,
  SemanticPage,
  SemanticProposalItem,
  SemanticQuerySpec,
  SemanticSubgraphResult,
  SemanticSummaryStats,
  SemanticValueDictEntry,
  WorkspaceWarehouse,
  WorkspaceWarehousePayload,
} from '@/types/semantic'

export const semanticApi = {
  summary() {
    return http.get<SemanticSummaryStats>('/metadata/semantic/summary')
  },
  listObjects(params: {
    page?: number
    size?: number
    keyword?: string
    objectType?: string
    domainId?: string
    status?: string
    scope?: string
  } = {}) {
    return http.get<SemanticPage<SemanticObject>>('/metadata/semantic/objects', params)
  },
  getObject(id: string) {
    return http.get<SemanticObject>(`/metadata/semantic/objects/${id}`)
  },
  createObject(payload: SemanticObjectPayload) {
    return http.post<SemanticObject>('/metadata/semantic/objects', payload)
  },
  updateObject(id: string, payload: SemanticObjectPayload) {
    return http.put<SemanticObject>(`/metadata/semantic/objects/${id}`, payload)
  },
  publishObject(id: string) {
    return http.post<SemanticObject>(`/metadata/semantic/objects/${id}/publish`)
  },
  deprecateObject(id: string) {
    return http.post<SemanticObject>(`/metadata/semantic/objects/${id}/deprecate`)
  },
  deleteObject(id: string) {
    return http.delete<void>(`/metadata/semantic/objects/${id}`)
  },
  listVersions(id: string) {
    return http.get<SemanticObjectVersion[]>(`/metadata/semantic/objects/${id}/versions`)
  },
  rollbackObject(id: string, versionNo: number) {
    return http.post<SemanticObject>(`/metadata/semantic/objects/${id}/rollback`, { versionNo })
  },
  listMappings(objectId: string) {
    return http.get<SemanticMapping[]>(`/metadata/semantic/objects/${objectId}/mappings`)
  },
  addMapping(objectId: string, payload: SemanticMappingPayload) {
    return http.post<SemanticMapping>(`/metadata/semantic/objects/${objectId}/mappings`, payload)
  },
  deleteMapping(objectId: string, mappingId: string) {
    return http.delete<void>(`/metadata/semantic/objects/${objectId}/mappings/${mappingId}`)
  },
  listValueDict(objectId: string) {
    return http.get<SemanticValueDictEntry[]>(`/metadata/semantic/objects/${objectId}/value-dict`)
  },
  upsertValueDict(
    objectId: string,
    payload: { rawValue: string; displayLabel: string; synonyms?: string[]; sourceKind?: string },
  ) {
    return http.post<SemanticValueDictEntry>(
      `/metadata/semantic/objects/${objectId}/value-dict`,
      payload,
    )
  },
  deleteValueDict(objectId: string, entryId: string) {
    return http.delete<void>(`/metadata/semantic/objects/${objectId}/value-dict/${entryId}`)
  },
  listLinks(params: { objectId?: string; edgeCategory?: string; status?: string } = {}) {
    return http.get<SemanticLink[]>('/metadata/semantic/links', params)
  },
  createLink(payload: SemanticLinkPayload) {
    return http.post<SemanticLink>('/metadata/semantic/links', payload)
  },
  deleteLink(linkId: string) {
    return http.delete<void>(`/metadata/semantic/links/${linkId}`)
  },
  listProposals(status = 'PROPOSED') {
    return http.get<SemanticProposalItem[]>('/metadata/semantic/proposals', { status })
  },
  approveProposal(kind: 'OBJECT' | 'LINK', id: string) {
    return http.post<SemanticProposalItem>(`/metadata/semantic/proposals/${kind}/${id}/approve`)
  },
  rejectProposal(kind: 'OBJECT' | 'LINK', id: string, reason: string) {
    return http.post<SemanticProposalItem>(`/metadata/semantic/proposals/${kind}/${id}/reject`, {
      reason,
    })
  },
  searchSubgraph(payload: {
    query: string
    maxHops?: number
    topK?: number
    allowedDatasetIds?: string[]
    externalModel?: boolean
  }) {
    return http.post<SemanticSubgraphResult>('/metadata/semantic/subgraph/search', payload)
  },
  impact(params: { columnId?: string; datasetId?: string }) {
    return http.get<SemanticImpactResult>('/metadata/semantic/impact', params)
  },
  compileSemantic(spec: SemanticQuerySpec) {
    return http.post<CompiledSemanticQuery>('/insight/semantic/compile', spec)
  },

  /** SMQ 干跑：成功返回 compiled；失败返回类型化 CompileReject（由后端 200+ok 封装时走 Agent 端点） */
  compileAndValidate(spec: SemanticQuerySpec) {
    return http.post<{
      ok: boolean
      reject?: {
        errorCode: string
        message: string
        location?: string
        allowed?: string[]
        repairHint?: string
      } | null
      compiled?: CompiledSemanticQuery | null
    }>('/insight/semantic/compile-and-validate', spec)
  },
  exportYaml(mode: 'short' | 'full' = 'short') {
    return http.get<{ mode: string; yaml: string }>('/metadata/semantic/yaml/export', { mode })
  },
  importYamlPreview(content: string) {
    return http.post<{ metricCount: number; resolvedRefs: Record<string, string>; warnings: string[] }>(
      '/metadata/semantic/yaml/import',
      { content },
    )
  },
  resolveRef(shortName: string) {
    return http.get<{
      status: string
      shortName: string
      canonicalId?: string | null
      canonicalCode?: string | null
      candidates: Array<{ id: string; code: string; name: string; objectType: string; editDistance: number }>
    }>('/metadata/semantic/resolve-ref', { shortName })
  },
  verifyObject(id: string) {
    return http.post<{ objectId: string; status: string; messages: string[] }>(
      `/metadata/semantic/objects/${id}/verify`,
      {},
    )
  },
  falsifyObject(
    id: string,
    body: {
      dataSourceId: string
      parentTable: string
      parentPk: string
      childTable: string
      childPk: string
      childFk: string
      fkMatchThreshold?: number
    },
  ) {
    return http.post<{
      objectId: string
      status: string
      assertions: Array<{ id: string; assertion: string; passed: boolean; observed: string; detail: string }>
      lowConfidenceFields: string[]
      verified: Record<string, unknown>
    }>(`/metadata/semantic/objects/${id}/falsify`, body)
  },
  planProbes(id: string, params?: { metricCode?: string; dimensionCode?: string }) {
    const qs = new URLSearchParams()
    if (params?.metricCode) qs.set('metricCode', params.metricCode)
    if (params?.dimensionCode) qs.set('dimensionCode', params.dimensionCode)
    const q = qs.toString()
    return http.post<{
      objectId: string
      status: string
      probes: Array<Record<string, unknown>>
      verified: Record<string, unknown>
    }>(`/metadata/semantic/objects/${id}/plan-probes${q ? `?${q}` : ''}`, {})
  },
  deleteImpact(id: string) {
    return http.get<{ objectId: string; deletable: boolean; referrerObjectIds: string[] }>(
      `/metadata/semantic/objects/${id}/delete-impact`,
    )
  },
  listWarehouses() {
    return http.get<WorkspaceWarehouse[]>('/metadata/semantic/warehouses')
  },
  upsertWarehouse(payload: WorkspaceWarehousePayload) {
    return http.post<WorkspaceWarehouse>('/metadata/semantic/warehouses', payload)
  },
  deleteWarehouse(id: string) {
    return http.delete<void>(`/metadata/semantic/warehouses/${id}`)
  },
  listLogicalTables(warehouseId?: string) {
    return http.get<LogicalTable[]>('/metadata/semantic/logical-tables', warehouseId ? { warehouseId } : {})
  },
  upsertLogicalTable(payload: LogicalTablePayload) {
    return http.post<LogicalTable>('/metadata/semantic/logical-tables', payload)
  },
  createLogicalTableAlias(
    baseTableId: string,
    payload: { logicalName: string; tableRole?: string; logicalSize?: number },
  ) {
    return http.post<LogicalTable>(`/metadata/semantic/logical-tables/${baseTableId}/alias`, payload)
  },
  deleteLogicalTable(id: string) {
    return http.delete<void>(`/metadata/semantic/logical-tables/${id}`)
  },
  mappingMatrix(objectId: string) {
    return http.get<HeterogeneousMappingMatrix>(`/metadata/semantic/objects/${objectId}/mapping-matrix`)
  },
  compileMultiWarehousePlan(spec: SemanticQuerySpec) {
    return http.post<MultiWarehousePlan>('/metadata/semantic/compile-plan', spec)
  },
}
