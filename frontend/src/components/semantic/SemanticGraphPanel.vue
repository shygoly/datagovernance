<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { semanticApi } from '@/api/semantic'
import type { SemanticLink, SemanticObject, SemanticSubgraphNode } from '@/types/semantic'

const props = defineProps<{
  allObjects: SemanticObject[]
}>()

const emit = defineEmits<{
  selectObject: [id: string]
}>()

const query = ref('')
const maxHops = ref(2)
const showStructural = ref(true)
const showSemantic = ref(true)
const showCausal = ref(true)
const loading = ref(false)
const nodes = ref<SemanticSubgraphNode[]>([])
const links = ref<SemanticLink[]>([])
const prunedCount = ref(0)
const selectedNode = ref<SemanticSubgraphNode | null>(null)

const visibleLinks = computed(() =>
  links.value.filter((l) => {
    if (l.edgeCategory === 'STRUCTURAL') return showStructural.value
    if (l.edgeCategory === 'SEMANTIC') return showSemantic.value
    if (l.edgeCategory === 'CAUSAL') return showCausal.value
    return true
  }),
)

const positionedNodes = computed(() => {
  const byHop = new Map<number, SemanticSubgraphNode[]>()
  for (const n of nodes.value) {
    const list = byHop.get(n.hopDistance) ?? []
    list.push(n)
    byHop.set(n.hopDistance, list)
  }
  const result: Array<{ node: SemanticSubgraphNode; x: number; y: number }> = []
  const hops = [...byHop.keys()].sort((a, b) => a - b)
  hops.forEach((hop, colIdx) => {
    const col = byHop.get(hop) ?? []
    col.forEach((node, rowIdx) => {
      result.push({
        node,
        x: 90 + colIdx * 250,
        y: 60 + rowIdx * 92,
      })
    })
  })
  return result
})

const nodeCoords = computed(() => {
  const map = new Map<string, { x: number; y: number }>()
  for (const p of positionedNodes.value) {
    map.set(p.node.id, { x: p.x, y: p.y })
  }
  return map
})

const svgHeight = computed(() => {
  const maxY = positionedNodes.value.reduce((acc, p) => Math.max(acc, p.y), 200)
  return Math.max(340, maxY + 100)
})

async function runSearch() {
  loading.value = true
  try {
    const res = await semanticApi.searchSubgraph({
      query: query.value.trim(),
      maxHops: maxHops.value,
      topK: 8,
    })
    nodes.value = res.nodes
    links.value = res.links
    prunedCount.value = res.prunedNodeCount
    selectedNode.value = res.nodes[0] ?? null
  } finally {
    loading.value = false
  }
}

watch(
  () => props.allObjects.length,
  () => {
    if (!nodes.value.length) void runSearch()
  },
  { immediate: true },
)
</script>

<template>
  <div class="graph-panel">
    <div class="graph-toolbar">
      <el-input
        v-model="query"
        size="small"
        clearable
        placeholder="输入中心语义对象、别名或业务词搜索子图"
        style="width: 280px"
        @keyup.enter="runSearch"
      />
      <div class="hop-control">
        <span class="muted">跳数</span>
        <el-radio-group v-model="maxHops" size="small" @change="runSearch">
          <el-radio-button :value="1">1 跳</el-radio-button>
          <el-radio-button :value="2">2 跳</el-radio-button>
          <el-radio-button :value="3">3 跳</el-radio-button>
        </el-radio-group>
      </div>
      <el-checkbox v-model="showStructural">结构 / Join</el-checkbox>
      <el-checkbox v-model="showSemantic">语义概念</el-checkbox>
      <el-checkbox v-model="showCausal">约束 / 因果</el-checkbox>
      <el-button type="primary" size="small" :loading="loading" @click="runSearch">刷新子图</el-button>
      <el-tag v-if="prunedCount > 0" size="small" type="warning">
        ACL 剪枝 {{ prunedCount }} 个无权节点
      </el-tag>
    </div>

    <div class="graph-body">
      <div v-loading="loading" class="graph-canvas">
        <el-empty v-if="!positionedNodes.length" description="暂无已发布的语义节点，请先在对象目录中发布" />
        <svg v-else width="100%" :height="svgHeight" class="graph-svg">
          <g v-for="edge in visibleLinks" :key="edge.id">
            <line
              v-if="nodeCoords.get(edge.fromObjectId) && nodeCoords.get(edge.toObjectId)"
              :x1="(nodeCoords.get(edge.fromObjectId)?.x ?? 0) + 95"
              :y1="(nodeCoords.get(edge.fromObjectId)?.y ?? 0) + 26"
              :x2="(nodeCoords.get(edge.toObjectId)?.x ?? 0) + 95"
              :y2="(nodeCoords.get(edge.toObjectId)?.y ?? 0) + 26"
              :class="['edge-line', `edge-${edge.edgeCategory.toLowerCase()}`]"
            />
            <text
              v-if="nodeCoords.get(edge.fromObjectId) && nodeCoords.get(edge.toObjectId)"
              :x="((nodeCoords.get(edge.fromObjectId)?.x ?? 0) + (nodeCoords.get(edge.toObjectId)?.x ?? 0)) / 2 + 95"
              :y="((nodeCoords.get(edge.fromObjectId)?.y ?? 0) + (nodeCoords.get(edge.toObjectId)?.y ?? 0)) / 2 + 20"
              class="edge-label"
            >
              {{ edge.linkType }}
            </text>
          </g>

          <g
            v-for="item in positionedNodes"
            :key="item.node.id"
            :transform="`translate(${item.x}, ${item.y})`"
            class="graph-node"
            @click="selectedNode = item.node"
          >
            <rect
              width="190"
              height="54"
              rx="6"
              :class="['node-box', { active: selectedNode?.id === item.node.id }]"
            />
            <text x="10" y="21" class="node-title">{{ item.node.name }}</text>
            <text x="10" y="40" class="node-meta">
              {{ item.node.objectType }} · Hop {{ item.node.hopDistance }} · v{{ item.node.versionNo }}
            </text>
          </g>
        </svg>
      </div>

      <div v-if="selectedNode" class="inspector">
        <div class="inspector-head">
          <span class="inspector-name">{{ selectedNode.name }}</span>
          <el-button link type="primary" size="small" @click="emit('selectObject', selectedNode.id)">
            编辑对象
          </el-button>
        </div>
        <div class="mono muted">{{ selectedNode.code }}</div>
        <div class="inspector-tags">
          <el-tag size="small">{{ selectedNode.objectType }}</el-tag>
          <el-tag size="small" type="info">{{ selectedNode.maxSensitivity }}</el-tag>
          <el-tag v-if="selectedNode.standardRef" size="small" type="success">
            {{ selectedNode.standardRef }}
          </el-tag>
        </div>
        <div v-if="selectedNode.aiContext?.disambiguation" class="inspector-block">
          <div class="block-label">歧义辨析</div>
          <div class="block-text">{{ selectedNode.aiContext.disambiguation }}</div>
        </div>
        <div v-if="selectedNode.mappings?.length" class="inspector-block">
          <div class="block-label">已绑定物理表（{{ selectedNode.mappings.length }}）</div>
          <div v-for="m in selectedNode.mappings" :key="m.id" class="mono mapping-item">
            {{ m.mappingRole }} → {{ m.datasetQualifiedName }}{{ m.columnName ? '.' + m.columnName : '' }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.graph-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--dg-space-md);
  margin-bottom: var(--dg-space-md);
}
.hop-control {
  display: flex;
  align-items: center;
  gap: var(--dg-space-sm);
}
.graph-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 290px;
  gap: var(--dg-space-md);
}
.graph-canvas {
  min-height: 360px;
  overflow: auto;
  background: var(--dg-surface-muted);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
}
.graph-node {
  cursor: pointer;
}
.node-box {
  fill: var(--dg-surface);
  stroke: var(--dg-border-strong);
  stroke-width: 1.2;
}
.node-box.active {
  stroke: var(--dg-primary);
  stroke-width: 2;
}
.node-title {
  fill: var(--dg-text);
  font-size: 13px;
  font-weight: 600;
}
.node-meta {
  fill: var(--dg-text-muted);
  font-size: 11px;
  font-family: var(--dg-font-mono);
}
.edge-line {
  stroke-width: 1.5;
}
.edge-structural {
  stroke: var(--dg-primary);
}
.edge-semantic {
  stroke: var(--dg-text-muted);
  stroke-dasharray: 4 3;
}
.edge-causal {
  stroke: var(--dg-warning);
  stroke-dasharray: 5 3;
}
.edge-label {
  fill: var(--dg-text-secondary);
  font-size: 10px;
  font-family: var(--dg-font-mono);
  text-anchor: middle;
}
.inspector {
  padding: var(--dg-space-md);
  background: var(--dg-surface);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
}
.inspector-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.inspector-name {
  font-weight: 600;
  color: var(--dg-text);
}
.inspector-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: var(--dg-space-sm) 0;
}
.inspector-block {
  margin-top: var(--dg-space-md);
}
.block-label {
  font-size: 12px;
  color: var(--dg-text-muted);
  margin-bottom: 4px;
}
.block-text {
  font-size: 13px;
  color: var(--dg-text-secondary);
  line-height: 1.5;
}
.mapping-item {
  font-size: 12px;
  color: var(--dg-text-secondary);
  word-break: break-all;
}
.muted {
  color: var(--dg-text-muted);
  font-size: 12px;
}
.mono {
  font-family: var(--dg-font-mono);
}
</style>
