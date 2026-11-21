<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import type { LineageEdge, LineageGraph, LineageNode } from '@/types/lineage'
import MetadataPanel from '@/components/insight/MetadataPanel.vue'

/** 右侧检查器的「连线详情」：转换表达式、作业来源、执行证据与状态。 */
const props = defineProps<{
  edge: LineageEdge
  fromNode?: LineageNode | null
  toNode?: LineageNode | null
  loading?: boolean
  /** 表级聚合边展开后的列级明细。 */
  columnBreakdown?: LineageGraph | null
  columnBreakdownLoading?: boolean
}>()

const emit = defineEmits<{
  'focus-node': [nodeId: string]
  'view-job-diff': [payload: { jobId: string; jobName?: string | null }]
  'open-column-edge': [payload: {
    edge: LineageEdge
    fromNode?: LineageNode | null
    toNode?: LineageNode | null
  }]
}>()

const isAggregated = computed(() => props.edge.origin === 'AGGREGATED' || props.edge.id.startsWith('dsedge:'))

const breakdownRows = computed(() => {
  const graph = props.columnBreakdown
  if (!graph?.edges?.length) {
    return []
  }
  const byId = new Map((graph.nodes || []).map((node) => [node.id, node]))
  return graph.edges.map((edge) => ({
    edge,
    from: byId.get(edge.fromNodeId) || null,
    to: byId.get(edge.toNodeId) || null,
  }))
})

function shortName(node?: LineageNode | null): string {
  if (!node) {
    return '?'
  }
  if (node.displayName) {
    return node.displayName
  }
  const q = node.qualifiedName || ''
  const i = q.lastIndexOf('.')
  return i >= 0 ? q.slice(i + 1) : q || node.id
}

const status = computed(() => (props.edge.status || 'ACTIVE').toUpperCase())
const statusType = computed<'success' | 'warning' | 'info'>(() => {
  switch (status.value) {
    case 'ACTIVE':
      return 'success'
    case 'PLANNED':
      return 'warning'
    default:
      return 'info'
  }
})
const statusLabel = computed(() => {
  switch (status.value) {
    case 'ACTIVE':
      return '已确认'
    case 'PLANNED':
      return '预估（未执行）'
    case 'SUPERSEDED':
      return '已失效'
    default:
      return status.value
  }
})
const dependencyLabel = computed(() =>
  (props.edge.dependencyType || 'DIRECT').toUpperCase() === 'INDIRECT'
    ? 'INDIRECT 间接依赖'
    : 'DIRECT 直接依赖',
)
const evidenceHint = computed(() => {
  if (props.edge.executionId) {
    return ''
  }
  return status.value === 'PLANNED'
    ? '该血缘来自作业编译/发布预估，尚无执行证据；作业执行成功后会晋升为已确认。'
    : '该血缘没有绑定执行记录（人工补录或元数据采集）。'
})
const items = computed(() => {
  const edge = props.edge
  return [
    { label: '源节点', value: props.fromNode?.qualifiedName || edge.fromNodeId },
    { label: '目标节点', value: props.toNode?.qualifiedName || edge.toNodeId },
    { label: '关系', value: edge.relation || '-' },
    { label: '依赖类型', value: dependencyLabel.value },
    { label: '作业', value: edge.jobName || (edge.jobId ? edge.jobId : '无（人工补录）') },
    { label: '作业类型', value: edge.jobType || '-' },
    { label: '执行 ID', value: edge.executionId || '-' },
    { label: '来源', value: edge.origin || '-' },
    {
      label: '置信度',
      value:
        edge.confidence === null || edge.confidence === undefined ? '-' : String(edge.confidence),
    },
    { label: '发现时间', value: formatTime(edge.discoveredAt) },
    { label: '边 ID', value: edge.id },
  ]
})

function formatTime(value?: string | null): string {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}

async function copyText(text: string, message: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(message)
  } catch {
    ElMessage.warning('浏览器不允许自动复制，请手动选择文本')
  }
}
</script>

<template>
  <div v-loading="!!loading" class="edge-inspector">
    <div class="edge-inspector__tags">
      <el-tag size="small" :type="statusType">{{ statusLabel }}</el-tag>
      <el-tag v-if="edge.maskingApplied" size="small" type="warning" effect="plain">
        已脱敏
      </el-tag>
    </div>

    <div v-if="isAggregated" class="edge-inspector__section">
      <div class="edge-inspector__title">
        <span>
          列级明细
          <template v-if="!columnBreakdownLoading">（{{ breakdownRows.length }}）</template>
        </span>
      </div>
      <div v-loading="!!columnBreakdownLoading" class="edge-inspector__breakdown">
        <div v-if="!columnBreakdownLoading && !breakdownRows.length" class="edge-inspector__empty">
          暂无列级明细（可能无权或边已失效）
        </div>
        <button
          v-for="row in breakdownRows"
          :key="row.edge.id"
          type="button"
          class="edge-inspector__row"
          @click="emit('open-column-edge', {
            edge: row.edge,
            fromNode: row.from,
            toNode: row.to,
          })"
        >
          <span class="edge-inspector__row-main">
            <code>{{ shortName(row.from) }}</code>
            <span class="edge-inspector__arrow">→</span>
            <code>{{ shortName(row.to) }}</code>
          </span>
          <span class="edge-inspector__row-meta">
            {{ row.edge.relation || '-' }}
            <template v-if="row.edge.dependencyType"> · {{ row.edge.dependencyType }}</template>
            <template v-if="row.edge.jobName"> · {{ row.edge.jobName }}</template>
          </span>
          <span v-if="row.edge.transformExpr" class="edge-inspector__row-expr">
            {{ row.edge.transformExpr }}
          </span>
        </button>
      </div>
    </div>

    <div v-else class="edge-inspector__section">
      <div class="edge-inspector__title">
        <span>转换表达式</span>
        <el-button
          v-if="edge.transformExpr"
          link
          type="primary"
          @click="copyText(edge.transformExpr, '表达式已复制')"
        >
          复制
        </el-button>
      </div>
      <pre v-if="edge.transformExpr" class="edge-inspector__code">{{ edge.transformExpr }}</pre>
      <div v-else class="edge-inspector__empty">无转换表达式（直接复制或未记录）</div>
    </div>

    <MetadataPanel v-if="!isAggregated" :items="items" />

    <div class="edge-inspector__section">
      <div class="edge-inspector__title">执行证据</div>
      <div v-if="edge.executionId" class="edge-inspector__evidence">
        <code>{{ edge.executionId }}</code>
        <el-button link type="primary" @click="copyText(edge.executionId, '执行 ID 已复制')">
          复制
        </el-button>
      </div>
      <div v-else class="edge-inspector__empty">{{ evidenceHint }}</div>
    </div>

    <div v-if="edge.maskingApplied || edge.maskingEvidence" class="edge-inspector__section">
      <div class="edge-inspector__title">脱敏证据</div>
      <div class="edge-inspector__empty">{{ edge.maskingEvidence || '该边经过脱敏/哈希处理' }}</div>
    </div>

    <div class="edge-inspector__actions">
      <el-button plain @click="emit('focus-node', edge.fromNodeId)">查看源节点</el-button>
      <el-button plain @click="emit('focus-node', edge.toNodeId)">查看目标节点</el-button>
      <el-button
        v-if="edge.jobId"
        plain
        @click="emit('view-job-diff', { jobId: edge.jobId, jobName: edge.jobName })"
      >
        查看作业历史变更
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.edge-inspector {
  min-height: 120px;
}

.edge-inspector__tags {
  display: flex;
  align-items: center;
  gap: var(--dg-space-xs);
  padding-bottom: var(--dg-space-md);
  margin-bottom: var(--dg-space-md);
  border-bottom: 1px solid var(--dg-border);
}

.edge-inspector__section {
  margin-bottom: var(--dg-space-md);
}

.edge-inspector__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--dg-space-xs);
  font-size: 12px;
  font-weight: 600;
  color: var(--dg-text-secondary);
}

.edge-inspector__code {
  margin: 0;
  padding: var(--dg-space-sm) var(--dg-space-md);
  max-height: 200px;
  overflow: auto;
  background: var(--dg-surface-muted);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
  font-family: var(--dg-font-mono);
  font-size: 12px;
  line-height: 1.6;
  color: var(--dg-text);
  white-space: pre-wrap;
  word-break: break-all;
}

.edge-inspector__empty {
  padding: var(--dg-space-sm) var(--dg-space-md);
  background: var(--dg-surface-muted);
  border: 1px dashed var(--dg-border);
  border-radius: var(--dg-radius);
  font-size: 12px;
  color: var(--dg-text-muted);
  line-height: 1.6;
}

.edge-inspector__evidence {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  font-size: 12px;
  word-break: break-all;
}

.edge-inspector__evidence code {
  font-family: var(--dg-font-mono);
  color: var(--dg-text);
}

.edge-inspector__actions {
  display: flex;
  flex-direction: column;
  gap: var(--dg-space-xs);
  margin-top: var(--dg-space-lg);
}

.edge-inspector__actions :deep(.el-button) {
  margin-left: 0;
}

.edge-inspector__breakdown {
  display: flex;
  flex-direction: column;
  gap: var(--dg-space-xs);
  min-height: 48px;
}

.edge-inspector__row {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  padding: var(--dg-space-sm) var(--dg-space-md);
  text-align: left;
  background: var(--dg-surface-muted);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
  cursor: pointer;
  color: var(--dg-text);
}

.edge-inspector__row:hover {
  border-color: var(--dg-primary);
  background: var(--dg-surface);
}

.edge-inspector__row-main {
  display: flex;
  align-items: center;
  gap: var(--dg-space-xs);
  font-size: 12px;
}

.edge-inspector__row-main code {
  font-family: var(--dg-font-mono);
}

.edge-inspector__arrow {
  color: var(--dg-text-muted);
}

.edge-inspector__row-meta {
  font-size: 11px;
  color: var(--dg-text-secondary);
}

.edge-inspector__row-expr {
  font-family: var(--dg-font-mono);
  font-size: 11px;
  color: var(--dg-text-muted);
  word-break: break-all;
}
</style>
