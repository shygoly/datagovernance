/** 解析表级聚合边 ID：`dsedge:{fromDatasetId}->{toDatasetId}`。 */
export function parseAggregatedDatasetEdge(
  edgeId: string,
): { fromDatasetId: string; toDatasetId: string } | null {
  if (!edgeId.startsWith('dsedge:')) {
    return null
  }
  const body = edgeId.slice('dsedge:'.length)
  const sep = body.indexOf('->')
  if (sep <= 0 || sep >= body.length - 2) {
    return null
  }
  const fromDatasetId = body.slice(0, sep)
  const toDatasetId = body.slice(sep + 2)
  if (!fromDatasetId || !toDatasetId) {
    return null
  }
  return { fromDatasetId, toDatasetId }
}
