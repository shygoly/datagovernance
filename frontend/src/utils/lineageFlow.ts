import dagre from '@dagrejs/dagre'
import type { InjectionKey } from 'vue'
import { GOVERNANCE_EDGE_RELATION, GOVERNANCE_NODE_TYPES } from '@/types/lineage'
import type { LineageEdge, LineageGraph, LineageNode } from '@/types/lineage'
import { parentDatasetOf } from '@/utils/lineage'

/**
 * 表卡片 + 字段端口画布的纯逻辑：分组、路径高亮、dagre 布局。
 *
 * 画布组件只负责渲染；这里不碰 DOM，也不依赖 Vue Flow，便于单独推理与复用。
 */

export const RESTRICTED_NODE_TYPE = 'RESTRICTED'
export const TABLE_CARD_WIDTH = 264
export const CARD_HEADER_HEIGHT = 48
export const CARD_ROW_HEIGHT = 28
export const CARD_FOOTER_HEIGHT = 30
export const CARD_PADDING_Y = 4

export type LineageEdgeKind = 'DIRECT' | 'INDIRECT' | 'PLANNED' | 'RESTRICTED' | 'GOVERNANCE'

/** 表卡片里的一行：既可能是血缘子图里的字段（有端口），也可能是展开后的其余字段。 */
export interface TableFieldRow {
  /** 血缘节点 ID；仅「其余字段」行为 null。 */
  nodeId: string | null
  name: string
  type?: string | null
  catalogColumnId?: string | null
  isRoot: boolean
  /** 是否在当前高亮路径上（无焦点时恒为 true）。 */
  highlighted: boolean
  selected: boolean
  /** 展开后补出来的、不在当前血缘子图里的字段。 */
  extra: boolean
  sensitivityLevel?: string | null
}

/** governance：指标 / 分析报告 / 质检规则等治理消费方卡片（后端动态投影，非血缘事实）。 */
export type TableCardKind = 'table' | 'restricted' | 'entity' | 'governance'

export function isGovernanceNode(node: { nodeType?: string | null }): boolean {
  return !!node.nodeType && GOVERNANCE_NODE_TYPES.includes(node.nodeType)
}

export function governanceLabel(type?: string | null): string {
  switch (type) {
    case 'METRIC':
      return '指标'
    case 'REPORT':
      return '分析报告'
    case 'QUALITY_RULE':
      return '质检规则'
    default:
      return type || '治理对象'
  }
}

/**
 * 治理消费方的跳转目标。分析报告目前没有独立的前端页面，返回 null（卡片不显示跳转入口）。
 */
export function governanceRoute(
  type?: string | null,
  refId?: string | null,
  datasetId?: string | null,
): { path: string; query: Record<string, string> } | null {
  switch (type) {
    case 'METRIC':
      return refId ? { path: '/metadata/semantic', query: { objectId: refId } } : null
    case 'QUALITY_RULE':
      return { path: '/metadata/quality', query: datasetId ? { datasetId } : {} }
    default:
      return null
  }
}

export interface TableNodeData {
  kind: TableCardKind
  title: string
  subtitle: string
  datasetId: string | null
  rows: TableFieldRow[]
  isRoot: boolean
  dimmed: boolean
  /** 可展开其余字段（有数据集 ID 且是表卡片）。 */
  expandable: boolean
  expanded: boolean
  loadingColumns: boolean
  /** 展开后还剩多少个不在子图里的字段；未加载过为 null。 */
  extraCount: number | null
  /** kind=governance 时的外部对象引用，用于跳转。 */
  govType?: string | null
  govRefId?: string | null
  /** 治理卡片是否有可跳转的页面。 */
  govJumpable?: boolean
  /** 表卡片头部：本表在子图中的最高 CONFIRMED 敏感等级。 */
  sensitivityLevel?: string | null
}

const SENSITIVITY_RANK: Record<string, number> = { L1: 1, L2: 2, L3: 3, L4: 4 }

/** 取若干等级中的最高 CONFIRMED 级别（仅 L1–L4）。 */
export function maxSensitivityLevel(levels: Array<string | null | undefined>): string | null {
  let best = 0
  let label: string | null = null
  for (const level of levels) {
    if (!level) continue
    const key = level.trim().toUpperCase()
    const rank = SENSITIVITY_RANK[key] ?? 0
    if (rank > best) {
      best = rank
      label = key
    }
  }
  return label
}

export interface TableGroup {
  id: string
  kind: TableCardKind
  title: string
  subtitle: string
  datasetId: string | null
  nodes: LineageNode[]
}

export interface LineageFlowActions {
  selectNode: (nodeId: string) => void
  openGovernance: (payload: { refType: string; refId: string; datasetId: string | null }) => void
  centerField: (payload: { datasetId: string; columnId: string }) => void
  toggleExpand: (datasetId: string) => void
}

export const LINEAGE_FLOW_ACTIONS: InjectionKey<LineageFlowActions> = Symbol('lineage-flow-actions')

// ── 分组 ────────────────────────────────────────────────────────────────

export function fieldNameOf(node: LineageNode): string {
  const raw = (node.displayName || node.qualifiedName || node.id).trim()
  const separator = raw.lastIndexOf('.')
  return separator > 0 ? raw.slice(separator + 1) : raw
}

function splitQualified(qualifiedName: string): { title: string; subtitle: string } {
  const index = qualifiedName.lastIndexOf('.')
  if (index <= 0) {
    return { title: qualifiedName, subtitle: '' }
  }
  return { title: qualifiedName.slice(index + 1), subtitle: qualifiedName.slice(0, index) }
}

/** 字段节点按所属数据集归并成表卡片；受限节点与非字段节点各自成卡，且受限卡绝不带名称。 */
export function groupTables(nodes: LineageNode[]): TableGroup[] {
  const groups = new Map<string, TableGroup>()
  for (const node of nodes) {
    if (node.nodeType === RESTRICTED_NODE_TYPE) {
      groups.set(`table:${node.id}`, {
        id: `table:${node.id}`,
        kind: 'restricted',
        title: '[受限资产]',
        subtitle: '无权访问，已按最小权限截断',
        datasetId: null,
        nodes: [node],
      })
      continue
    }
    if (isGovernanceNode(node)) {
      groups.set(`table:${node.id}`, {
        id: `table:${node.id}`,
        kind: 'governance',
        title: node.displayName || node.qualifiedName,
        subtitle: `${governanceLabel(node.nodeType)} · ${node.qualifiedName}`,
        datasetId: node.catalogDatasetId || null,
        nodes: [node],
      })
      continue
    }
    if (node.nodeType !== 'COLUMN') {
      groups.set(`table:${node.id}`, {
        id: `table:${node.id}`,
        kind: 'entity',
        title: node.displayName || node.qualifiedName,
        subtitle: node.nodeType,
        datasetId: node.catalogDatasetId || null,
        nodes: [node],
      })
      continue
    }
    const parent = parentDatasetOf(node)
    const key = node.catalogDatasetId ? `table:${node.catalogDatasetId}` : `table:ext:${parent}`
    let group = groups.get(key)
    if (!group) {
      const { title, subtitle } = splitQualified(parent)
      group = {
        id: key,
        kind: 'table',
        title,
        subtitle,
        datasetId: node.catalogDatasetId || null,
        nodes: [],
      }
      groups.set(key, group)
    }
    group.nodes.push(node)
  }
  return [...groups.values()]
}

// ── 路径高亮 ────────────────────────────────────────────────────────────

export interface HighlightSet {
  nodes: Set<string>
  edges: Set<string>
}

export type FlowFocus = { kind: 'node'; id: string } | { kind: 'edge'; id: string } | null

function reach(startId: string, edges: LineageEdge[], direction: 'up' | 'down', out: HighlightSet) {
  const adjacency = new Map<string, LineageEdge[]>()
  for (const edge of edges) {
    const key = direction === 'down' ? edge.fromNodeId : edge.toNodeId
    const list = adjacency.get(key)
    if (list) {
      list.push(edge)
    } else {
      adjacency.set(key, [edge])
    }
  }
  const queue = [startId]
  const seen = new Set([startId])
  for (let cursor = 0; cursor < queue.length; cursor += 1) {
    for (const edge of adjacency.get(queue[cursor]) ?? []) {
      out.edges.add(edge.id)
      const next = direction === 'down' ? edge.toNodeId : edge.fromNodeId
      out.nodes.add(next)
      if (!seen.has(next)) {
        seen.add(next)
        queue.push(next)
      }
    }
  }
}

/**
 * 点击字段或连线时，高亮从该处出发的**端到端完整路径**：
 * 字段 → 它的全部上游链 + 全部下游链；连线 → 起点的上游链 + 终点的下游链 + 这条线本身。
 * 只沿单一方向延伸，不会把「上游节点的其它下游分支」算进路径。
 * 无焦点（或焦点不在图里）时返回 null，调用方据此不做任何淡化。
 */
export function computeHighlight(graph: LineageGraph, focus: FlowFocus): HighlightSet | null {
  if (!focus) {
    return null
  }
  const result: HighlightSet = { nodes: new Set(), edges: new Set() }
  if (focus.kind === 'node') {
    if (!graph.nodes.some((node) => node.id === focus.id)) {
      return null
    }
    result.nodes.add(focus.id)
    reach(focus.id, graph.edges, 'up', result)
    reach(focus.id, graph.edges, 'down', result)
    return result
  }
  const edge = graph.edges.find((item) => item.id === focus.id)
  if (!edge) {
    return null
  }
  result.edges.add(edge.id)
  result.nodes.add(edge.fromNodeId)
  result.nodes.add(edge.toNodeId)
  reach(edge.fromNodeId, graph.edges, 'up', result)
  reach(edge.toNodeId, graph.edges, 'down', result)
  return result
}

// ── 连线类别 ────────────────────────────────────────────────────────────

/** 样式类别：PLANNED 优先（预估），其次 INDIRECT；受限边只保留连通提示。 */
export function edgeKindOf(edge: LineageEdge): LineageEdgeKind {
  if (edge.relation === RESTRICTED_NODE_TYPE) {
    return 'RESTRICTED'
  }
  if (edge.relation === GOVERNANCE_EDGE_RELATION) {
    return 'GOVERNANCE'
  }
  if ((edge.status || '').toUpperCase() === 'PLANNED') {
    return 'PLANNED'
  }
  return (edge.dependencyType || 'DIRECT').toUpperCase() === 'INDIRECT' ? 'INDIRECT' : 'DIRECT'
}

// ── 布局 ────────────────────────────────────────────────────────────────

export function cardHeight(rowCount: number, hasFooter: boolean): number {
  return (
    CARD_HEADER_HEIGHT +
    CARD_PADDING_Y * 2 +
    Math.max(rowCount, 1) * CARD_ROW_HEIGHT +
    (hasFooter ? CARD_FOOTER_HEIGHT : 0)
  )
}

export interface LayoutInput {
  id: string
  rowCount: number
  hasFooter: boolean
}

/** dagre 从左到右分层；返回左上角坐标（Vue Flow 以左上角为原点）。 */
export function layoutTables(
  tables: LayoutInput[],
  tableEdges: Array<{ from: string; to: string }>,
): Map<string, { x: number; y: number; width: number; height: number }> {
  const graph = new dagre.graphlib.Graph()
  graph.setGraph({ rankdir: 'LR', nodesep: 36, ranksep: 120, marginx: 24, marginy: 24 })
  graph.setDefaultEdgeLabel(() => ({}))
  for (const table of tables) {
    graph.setNode(table.id, {
      width: TABLE_CARD_WIDTH,
      height: cardHeight(table.rowCount, table.hasFooter),
    })
  }
  const known = new Set(tables.map((table) => table.id))
  const seen = new Set<string>()
  for (const edge of tableEdges) {
    const key = `${edge.from}->${edge.to}`
    if (edge.from === edge.to || seen.has(key) || !known.has(edge.from) || !known.has(edge.to)) {
      continue
    }
    seen.add(key)
    graph.setEdge(edge.from, edge.to)
  }
  dagre.layout(graph)

  const result = new Map<string, { x: number; y: number; width: number; height: number }>()
  for (const table of tables) {
    const placed = graph.node(table.id)
    const height = cardHeight(table.rowCount, table.hasFooter)
    result.set(table.id, {
      x: (placed?.x ?? 0) - TABLE_CARD_WIDTH / 2,
      y: (placed?.y ?? 0) - height / 2,
      width: TABLE_CARD_WIDTH,
      height,
    })
  }
  return result
}
