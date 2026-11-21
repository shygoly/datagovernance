<script setup lang="ts">
import { computed, nextTick, onMounted, provide, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { MarkerType, VueFlow, useVueFlow } from '@vue-flow/core'
import type { Edge, GraphEdge, GraphNode, Node } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'
import { catalogAssetApi } from '@/api/catalog-assets'
import type { CatalogColumn } from '@/types/catalog-assets'
import type { LineageEdge, LineageGraph, LineageLayoutNode } from '@/types/lineage'
import TableLineageNode from '@/components/lineage/TableLineageNode.vue'
import {
  LINEAGE_FLOW_ACTIONS,
  computeHighlight,
  edgeKindOf,
  fieldNameOf,
  governanceRoute,
  groupTables,
  layoutTables,
  maxSensitivityLevel,
} from '@/utils/lineageFlow'
import type {
  FlowFocus,
  LineageEdgeKind,
  TableFieldRow,
  TableGroup,
  TableNodeData,
} from '@/utils/lineageFlow'

/**
 * 表卡片 + 字段端口双层画布（Vue Flow + dagre）。
 *
 * 数据契约与 LineageGraph.vue（ECharts 节点图）一致：layout / edges / selectedId / selectedEdgeId，
 * 由 LineageView 的「大图收敛保护」先裁剪后再传入，画布本身不再放大图规模。
 *
 * 连线：DIRECT 实线 / INDIRECT 虚线 / PLANNED 点划线 + 「预估」标签；
 * 点击字段或连线时高亮端到端完整路径，其余淡化。颜色只引用 var(--dg-*)。
 */
const props = withDefaults(
  defineProps<{
    layout: LineageLayoutNode[]
    edges: LineageEdge[]
    rootId?: string
    selectedId?: string
    selectedEdgeId?: string
    height?: number
  }>(),
  { rootId: '', selectedId: '', selectedEdgeId: '', height: 560 },
)

const emit = defineEmits<{
  select: [id: string]
  selectEdge: [id: string]
  centerField: [payload: { datasetId: string; columnId: string }]
  /** 点击治理消费方卡片（指标 / 质检规则）的跳转入口。 */
  openGovernance: [payload: { refType: string; refId: string; datasetId: string | null }]
}>()

const { fitView, onNodesInitialized, onNodeDragStop } = useVueFlow()

// ── 展开其余字段（懒加载 Catalog 列）─────────────────────────────────────
const expandedTables = ref<Set<string>>(new Set())
const loadingTables = ref<Set<string>>(new Set())
const columnsByDataset = ref<Record<string, CatalogColumn[]>>({})
/** 用户拖动过的卡片位置；图本身变化时清空，重新自动布局。 */
const draggedPositions = ref<Record<string, { x: number; y: number }>>({})

async function toggleExpand(datasetId: string) {
  const next = new Set(expandedTables.value)
  if (next.has(datasetId)) {
    next.delete(datasetId)
    expandedTables.value = next
    return
  }
  next.add(datasetId)
  expandedTables.value = next
  if (columnsByDataset.value[datasetId]) {
    return
  }
  const loading = new Set(loadingTables.value)
  loading.add(datasetId)
  loadingTables.value = loading
  try {
    const columns = await catalogAssetApi.listColumns(datasetId)
    columnsByDataset.value = { ...columnsByDataset.value, [datasetId]: columns }
  } catch {
    const rollback = new Set(expandedTables.value)
    rollback.delete(datasetId)
    expandedTables.value = rollback
    ElMessage.error('加载表字段失败')
  } finally {
    const done = new Set(loadingTables.value)
    done.delete(datasetId)
    loadingTables.value = done
  }
}

provide(LINEAGE_FLOW_ACTIONS, {
  selectNode: (nodeId) => emit('select', nodeId),
  openGovernance: (payload) => emit('openGovernance', payload),
  centerField: (payload) => emit('centerField', payload),
  toggleExpand: (datasetId) => void toggleExpand(datasetId),
})

// ── 模型 ────────────────────────────────────────────────────────────────
const visibleEdges = computed(() => {
  const ids = new Set(props.layout.map((node) => node.id))
  return props.edges.filter((edge) => ids.has(edge.fromNodeId) && ids.has(edge.toNodeId))
})
const graph = computed<LineageGraph>(() => ({ nodes: props.layout, edges: visibleEdges.value }))
const focus = computed<FlowFocus>(() => {
  if (props.selectedEdgeId) {
    return { kind: 'edge', id: props.selectedEdgeId }
  }
  return props.selectedId ? { kind: 'node', id: props.selectedId } : null
})
const highlight = computed(() => computeHighlight(graph.value, focus.value))
const groups = computed(() => groupTables(props.layout))
const nodeToTable = computed(() => {
  const map = new Map<string, string>()
  for (const group of groups.value) {
    for (const node of group.nodes) {
      map.set(node.id, group.id)
    }
  }
  return map
})

interface RowPlan {
  group: TableGroup
  base: Array<{
    nodeId: string
    name: string
    type: string | null
    catalogColumnId: string | null
    sensitivityLevel: string | null
  }>
  extras: Array<{ name: string; type: string | null; catalogColumnId: string }>
  extraCount: number | null
}

/** 行数结构：只随图与展开状态变化，不随选中变化，避免点击字段就重跑布局。 */
const rowPlans = computed<RowPlan[]>(() =>
  groups.value.map((group) => {
    const columns = group.datasetId ? columnsByDataset.value[group.datasetId] : undefined
    const typeOf = new Map((columns ?? []).map((column) => [column.id, column]))
    const base = group.nodes
      .map((node) => ({
        nodeId: node.id,
        name: group.kind === 'table' ? fieldNameOf(node) : node.displayName || node.qualifiedName,
        type:
          (node.catalogColumnId
            ? typeOf.get(node.catalogColumnId)?.rawType || typeOf.get(node.catalogColumnId)?.canonicalType
            : null) ?? null,
        catalogColumnId: node.catalogColumnId ?? null,
        sensitivityLevel: node.sensitivityLevel ?? null,
        root: node.id === props.rootId,
      }))
      .sort((a, b) => Number(b.root) - Number(a.root) || a.name.localeCompare(b.name))
    const present = new Set(base.map((row) => row.catalogColumnId).filter(Boolean))
    const remaining = (columns ?? []).filter((column) => !present.has(column.id))
    const expanded = !!group.datasetId && expandedTables.value.has(group.datasetId)
    return {
      group,
      base,
      extras: expanded
        ? remaining.map((column) => ({
            name: column.name,
            type: column.rawType || column.canonicalType || null,
            catalogColumnId: column.id,
          }))
        : [],
      extraCount: columns ? remaining.length : null,
    }
  }),
)

const placement = computed(() => {
  const tableEdges: Array<{ from: string; to: string }> = []
  for (const edge of visibleEdges.value) {
    const from = nodeToTable.value.get(edge.fromNodeId)
    const to = nodeToTable.value.get(edge.toNodeId)
    if (from && to) {
      tableEdges.push({ from, to })
    }
  }
  return layoutTables(
    rowPlans.value.map((plan) => ({
      id: plan.group.id,
      rowCount: plan.base.length + plan.extras.length,
      hasFooter: plan.group.kind === 'table' && !!plan.group.datasetId,
    })),
    tableEdges,
  )
})

/** 治理消费方卡片的外部对象引用（指标/规则可跳转，报告暂无页面）。 */
function governanceData(group: TableGroup): Partial<TableNodeData> {
  if (group.kind !== 'governance') {
    return {}
  }
  const node = group.nodes[0]
  return {
    govType: node.externalRefType || node.nodeType,
    govRefId: node.externalRefId || null,
    govJumpable: !!governanceRoute(node.externalRefType || node.nodeType, node.externalRefId, node.catalogDatasetId),
  }
}

const flowNodes = computed<Node[]>(() => {
  const active = highlight.value
  return rowPlans.value.map((plan) => {
    const { group } = plan
    const rows: TableFieldRow[] = [
      ...plan.base.map((row) => ({
        nodeId: row.nodeId,
        name: row.name,
        type: row.type,
        catalogColumnId: row.catalogColumnId,
        isRoot: row.nodeId === props.rootId,
        highlighted: !active || active.nodes.has(row.nodeId),
        selected: row.nodeId === props.selectedId && !props.selectedEdgeId,
        extra: false,
        sensitivityLevel: row.sensitivityLevel,
      })),
      ...plan.extras.map((row) => ({
        nodeId: null,
        name: row.name,
        type: row.type,
        catalogColumnId: row.catalogColumnId,
        isRoot: false,
        highlighted: true,
        selected: false,
        extra: true,
      })),
    ]
    const dimmed = !!active && !group.nodes.some((node) => active.nodes.has(node.id))
    const expandable = group.kind === 'table' && !!group.datasetId
    const tableSensitivity = maxSensitivityLevel(group.nodes.map((node) => node.sensitivityLevel))
    const data: TableNodeData = {
      kind: group.kind,
      title: group.title,
      subtitle: group.subtitle,
      datasetId: group.datasetId,
      sensitivityLevel: tableSensitivity,
      rows,
      isRoot: group.nodes.some((node) => node.id === props.rootId),
      dimmed,
      expandable,
      expanded: !!group.datasetId && expandedTables.value.has(group.datasetId),
      loadingColumns: !!group.datasetId && loadingTables.value.has(group.datasetId),
      extraCount: plan.extraCount,
      ...governanceData(group),
    }
    const placed = placement.value.get(group.id)
    const position = draggedPositions.value[group.id] ?? { x: placed?.x ?? 0, y: placed?.y ?? 0 }
    return {
      id: group.id,
      type: 'tableLineage',
      position,
      data,
      class: dimmed ? 'lineage-node--dim' : '',
      selectable: false,
    } satisfies Node
  })
})

// 箭头颜色是 SVG marker 属性，需要解析成具体颜色值；取自 --dg-* 令牌，不写字面量。
const palette = ref<Record<LineageEdgeKind, string>>({
  DIRECT: '',
  INDIRECT: '',
  PLANNED: '',
  RESTRICTED: '',
  GOVERNANCE: '',
})
const gridColor = ref('')
const maskColor = ref('')

function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

function readPalette() {
  palette.value = {
    DIRECT: cssVar('--dg-primary'),
    INDIRECT: cssVar('--dg-text-secondary'),
    PLANNED: cssVar('--dg-text-muted'),
    RESTRICTED: cssVar('--dg-text-muted'),
    GOVERNANCE: cssVar('--dg-success'),
  }
  gridColor.value = cssVar('--dg-border-strong')
  maskColor.value = cssVar('--dg-surface-muted')
}

const STROKE_VAR: Record<LineageEdgeKind, string> = {
  DIRECT: 'var(--dg-primary)',
  INDIRECT: 'var(--dg-text-secondary)',
  PLANNED: 'var(--dg-text-muted)',
  RESTRICTED: 'var(--dg-text-muted)',
  GOVERNANCE: 'var(--dg-success)',
}
const DASH: Record<LineageEdgeKind, string | undefined> = {
  DIRECT: undefined,
  INDIRECT: '6 4',
  PLANNED: '8 3 2 3',
  RESTRICTED: '2 4',
  GOVERNANCE: '3 3',
}

const flowEdges = computed<Edge[]>(() => {
  const active = highlight.value
  const result: Edge[] = []
  for (const edge of visibleEdges.value) {
    const source = nodeToTable.value.get(edge.fromNodeId)
    const target = nodeToTable.value.get(edge.toNodeId)
    if (!source || !target) {
      continue
    }
    const kind = edgeKindOf(edge)
    const selected = !!props.selectedEdgeId && edge.id === props.selectedEdgeId
    const onPath = !active || active.edges.has(edge.id)
    result.push({
      id: edge.id,
      source,
      sourceHandle: `${edge.fromNodeId}-out`,
      target,
      targetHandle: `${edge.toNodeId}-in`,
      type: 'default',
      selectable: false,
      markerEnd: { type: MarkerType.ArrowClosed, color: palette.value[kind] || undefined, width: 16, height: 16 },
      label: kind === 'PLANNED' ? '预估' : kind === 'GOVERNANCE' ? '消费' : undefined,
      labelStyle: { fill: 'var(--dg-text-muted)', fontSize: 10 },
      labelBgStyle: { fill: 'var(--dg-surface)' },
      labelBgPadding: [3, 2],
      labelBgBorderRadius: 3,
      style: {
        stroke: selected ? 'var(--dg-primary)' : STROKE_VAR[kind],
        strokeWidth: selected ? 3 : active && onPath ? 2.2 : 1.5,
        strokeDasharray: DASH[kind],
        opacity: onPath ? 1 : 0.2,
      },
    })
  }
  return result
})

// ── 视口 ────────────────────────────────────────────────────────────────
/** 图本身（节点/边集合）变化的指纹：只有它变化才重置视口与拖动位置。 */
const graphKey = computed(
  () =>
    `${props.layout.map((node) => node.id).join(',')}|${visibleEdges.value.map((edge) => edge.id).join(',')}`,
)
let pendingFit = true

watch(graphKey, () => {
  draggedPositions.value = {}
  pendingFit = true
})

onNodesInitialized(() => {
  if (pendingFit) {
    pendingFit = false
    void nextTick(() => fitView({ padding: 0.18, maxZoom: 1 }))
  }
})

onNodeDragStop(({ node }) => {
  draggedPositions.value = {
    ...draggedPositions.value,
    [node.id]: { x: node.position.x, y: node.position.y },
  }
})

function onEdgeClick(payload: { edge: GraphEdge }) {
  emit('selectEdge', payload.edge.id)
}

function miniMapColor(node: GraphNode): string {
  const data = node.data as TableNodeData | undefined
  if (data?.kind === 'restricted') {
    return palette.value.RESTRICTED
  }
  if (data?.kind === 'governance') {
    return palette.value.GOVERNANCE
  }
  return data?.isRoot ? palette.value.DIRECT : palette.value.INDIRECT
}

function resetView() {
  void fitView({ padding: 0.18, maxZoom: 1 })
}

defineExpose({ resetView })

onMounted(readPalette)
</script>

<template>
  <div class="lineage-flow">
    <div class="lineage-flow__toolbar">
      <div class="lineage-flow__legend" aria-label="连线图例">
        <span class="legend-item">
          <svg width="32" height="8" aria-hidden="true">
            <line x1="0" y1="4" x2="32" y2="4" class="legend-line legend-line--direct" />
          </svg>
          DIRECT
        </span>
        <span class="legend-item">
          <svg width="32" height="8" aria-hidden="true">
            <line x1="0" y1="4" x2="32" y2="4" class="legend-line legend-line--indirect" />
          </svg>
          INDIRECT
        </span>
        <span class="legend-item">
          <svg width="32" height="8" aria-hidden="true">
            <line x1="0" y1="4" x2="32" y2="4" class="legend-line legend-line--planned" />
          </svg>
          预估
        </span>
        <span class="legend-item">
          <svg width="32" height="8" aria-hidden="true">
            <line x1="0" y1="4" x2="32" y2="4" class="legend-line legend-line--governance" />
          </svg>
          治理消费方
        </span>
      </div>
      <span class="lineage-flow__hint">
        点击字段/连线高亮完整路径 · 双击字段以其为中心 · 滚轮缩放 · 拖拽画布
      </span>
    </div>

    <div class="lineage-flow__canvas" :style="{ height: `${height}px` }">
      <VueFlow
        :nodes="flowNodes"
        :edges="flowEdges"
        :min-zoom="0.1"
        :max-zoom="1.6"
        :nodes-connectable="false"
        :elements-selectable="false"
        :zoom-on-double-click="false"
        :delete-key-code="null"
        :only-render-visible-elements="flowNodes.length > 60"
        @edge-click="onEdgeClick"
      >
        <template #node-tableLineage="nodeProps">
          <TableLineageNode :id="nodeProps.id" :data="nodeProps.data" />
        </template>
        <Background :gap="18" :pattern-color="gridColor || undefined" />
        <MiniMap pannable zoomable :node-color="miniMapColor" :mask-color="maskColor || undefined" />
        <Controls :show-interactive="false" />
      </VueFlow>
    </div>
  </div>
</template>

<style scoped>
.lineage-flow__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  flex-wrap: wrap;
  margin-bottom: var(--dg-space-sm);
}

.lineage-flow__legend {
  display: flex;
  align-items: center;
  gap: var(--dg-space-lg);
  font-size: 12px;
  color: var(--dg-text-secondary);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: var(--dg-space-xs);
}

.legend-line {
  stroke-width: 2;
}

.legend-line--direct {
  stroke: var(--dg-primary);
}

.legend-line--indirect {
  stroke: var(--dg-text-secondary);
  stroke-dasharray: 6 4;
}

.legend-line--planned {
  stroke: var(--dg-text-muted);
  stroke-dasharray: 8 3 2 3;
}

.legend-line--governance {
  stroke: var(--dg-success);
  stroke-dasharray: 3 3;
}

.lineage-flow__hint {
  font-size: 12px;
  color: var(--dg-text-muted);
}

.lineage-flow__canvas {
  width: 100%;
  min-height: 360px;
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
  background: var(--dg-canvas);
  overflow: hidden;
}

.lineage-flow__canvas :deep(.vue-flow__node.lineage-node--dim) {
  opacity: 0.45;
}

.lineage-flow__canvas :deep(.vue-flow__node) {
  transition: opacity var(--dg-duration-base) var(--dg-ease-out);
}

.lineage-flow__canvas :deep(.vue-flow__edge-path) {
  transition: opacity var(--dg-duration-base) var(--dg-ease-out);
}

/* Controls / MiniMap 的默认配色来自第三方样式，统一覆盖为设计令牌。 */
.lineage-flow__canvas :deep(.vue-flow__controls) {
  box-shadow: var(--dg-shadow-overlay);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
  overflow: hidden;
}

.lineage-flow__canvas :deep(.vue-flow__controls-button) {
  background: var(--dg-surface);
  border-bottom: 1px solid var(--dg-border);
  color: var(--dg-text);
  fill: var(--dg-text);
}

.lineage-flow__canvas :deep(.vue-flow__controls-button:hover) {
  background: var(--dg-surface-muted);
}

.lineage-flow__canvas :deep(.vue-flow__minimap) {
  background: var(--dg-surface);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
}
</style>
