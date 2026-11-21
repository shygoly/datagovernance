<script setup lang="ts">
import { computed, inject } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { Lock } from '@element-plus/icons-vue'
import {
  CARD_FOOTER_HEIGHT,
  CARD_HEADER_HEIGHT,
  CARD_PADDING_Y,
  CARD_ROW_HEIGHT,
  LINEAGE_FLOW_ACTIONS,
  TABLE_CARD_WIDTH,
  governanceLabel,
} from '@/utils/lineageFlow'
import type { TableFieldRow, TableNodeData } from '@/utils/lineageFlow'

/**
 * 表容器卡片：表名/限定名、字段行（左右各一个 Handle 端口）、展开其余字段。
 *
 * 受限节点只渲染固定占位文案，绝不读取任何表名/字段名（后端已脱敏，这里再保一层）。
 * 尺寸常量与 dagre 布局共用 utils/lineageFlow，保证端口位置与布局高度一致。
 */
const props = defineProps<{ id: string; data: TableNodeData }>()
defineOptions({ inheritAttrs: false })

const actions = inject(LINEAGE_FLOW_ACTIONS, null)

const restricted = computed(() => props.data.kind === 'restricted')
const governance = computed(() => props.data.kind === 'governance')
const governanceTag = computed(() => governanceLabel(props.data.govType))
const hasFooter = computed(() => props.data.expandable)
const cardStyle = computed(() => ({
  width: `${TABLE_CARD_WIDTH}px`,
  '--row-h': `${CARD_ROW_HEIGHT}px`,
  '--head-h': `${CARD_HEADER_HEIGHT}px`,
  '--foot-h': `${CARD_FOOTER_HEIGHT}px`,
  '--pad-y': `${CARD_PADDING_Y}px`,
}))
function sensitivityClass(level?: string | null): string {
  const key = (level || 'L1').trim().toUpperCase()
  if (key === 'L4' || key === 'L3' || key === 'L2') {
    return `sensitivity-badge--${key}`
  }
  return 'sensitivity-badge--L1'
}

const footerLabel = computed(() => {
  if (props.data.loadingColumns) {
    return '加载字段…'
  }
  if (props.data.expanded) {
    return '收起其余字段'
  }
  return props.data.extraCount === null
    ? '展开其余字段'
    : `展开其余 ${props.data.extraCount} 个字段`
})

function onRowClick(row: TableFieldRow) {
  if (row.nodeId) {
    actions?.selectNode(row.nodeId)
  } else if (row.extra) {
    // 其余字段不在当前子图里，没有可选中的血缘节点：点击即以它为中心加载。
    onRowDblClick(row)
  }
}

/** 双击：以该字段为中心重新加载（按跳数懒加载展开上下游）。 */
function onRowDblClick(row: TableFieldRow) {
  if (props.data.datasetId && row.catalogColumnId) {
    actions?.centerField({ datasetId: props.data.datasetId, columnId: row.catalogColumnId })
  }
}

/** 治理消费方卡片：跳到指标 / 质检规则所在页面（分析报告暂无页面，不显示入口）。 */
function onOpenGovernance() {
  if (props.data.govType && props.data.govRefId) {
    actions?.openGovernance({
      refType: props.data.govType,
      refId: props.data.govRefId,
      datasetId: props.data.datasetId,
    })
  }
}

function onToggleExpand() {
  if (props.data.datasetId) {
    actions?.toggleExpand(props.data.datasetId)
  }
}
</script>

<template>
  <div
    class="table-card"
    :class="{
      'table-card--restricted': restricted,
      'table-card--root': data.isRoot,
      'table-card--entity': data.kind === 'entity',
      'table-card--governance': governance,
    }"
    :style="cardStyle"
  >
    <header class="table-card__header">
      <el-icon v-if="restricted" class="table-card__lock"><Lock /></el-icon>
      <div class="table-card__titles">
        <div class="table-card__title" :title="restricted ? undefined : data.title">
          {{ data.title }}
        </div>
        <div v-if="data.subtitle" class="table-card__subtitle" :title="restricted ? undefined : data.subtitle">
          {{ data.subtitle }}
        </div>
      </div>
      <span
        v-if="!restricted && data.sensitivityLevel"
        class="sensitivity-badge table-card__sensitivity nodrag"
        :class="sensitivityClass(data.sensitivityLevel)"
      >
        {{ data.sensitivityLevel }}
      </span>
      <span v-if="governance" class="table-card__tag">{{ governanceTag }}</span>
      <button
        v-if="governance && data.govJumpable"
        type="button"
        class="table-card__jump nodrag"
        :title="`跳转到${governanceTag}`"
        @click.stop="onOpenGovernance"
      >
        跳转
      </button>
      <span v-if="data.isRoot" class="table-card__badge">中心</span>
    </header>

    <ul class="table-card__rows">
      <li
        v-for="row in data.rows"
        :key="row.nodeId ?? `extra:${row.catalogColumnId ?? row.name}`"
        class="field-row nodrag"
        :class="{
          'field-row--root': row.isRoot,
          'field-row--selected': row.selected,
          'field-row--dim': !row.highlighted,
          'field-row--extra': row.extra,
          'field-row--restricted': restricted,
        }"
        @click.stop="onRowClick(row)"
        @dblclick.stop="onRowDblClick(row)"
      >
        <Handle
          v-if="row.nodeId"
          :id="`${row.nodeId}-in`"
          type="target"
          :position="Position.Left"
          class="field-handle"
        />
        <span class="field-row__name" :title="restricted ? undefined : row.name">
          <template v-if="restricted">
            <el-icon><Lock /></el-icon>
          </template>
          {{ restricted ? '[受限资产]' : row.name }}
        </span>
        <span
          v-if="!restricted && row.sensitivityLevel"
          class="sensitivity-badge field-row__sensitivity nodrag"
          :class="sensitivityClass(row.sensitivityLevel)"
        >
          {{ row.sensitivityLevel }}
        </span>
        <span v-if="!restricted && row.type" class="field-row__type">{{ row.type }}</span>
        <span v-if="row.isRoot" class="field-row__mark">●</span>
        <Handle
          v-if="row.nodeId"
          :id="`${row.nodeId}-out`"
          type="source"
          :position="Position.Right"
          class="field-handle"
        />
      </li>
    </ul>

    <footer v-if="hasFooter" class="table-card__footer">
      <button
        type="button"
        class="table-card__expand nodrag"
        :disabled="data.loadingColumns"
        @click.stop="onToggleExpand"
      >
        {{ footerLabel }}
      </button>
    </footer>
  </div>
</template>

<style scoped>
.table-card {
  background: var(--dg-surface);
  border: 1px solid var(--dg-border-strong);
  border-radius: var(--dg-radius);
  box-shadow: var(--dg-shadow-overlay);
  font-size: 12px;
  color: var(--dg-text);
  transition: opacity var(--dg-duration-base) var(--dg-ease-out);
}

.table-card--root {
  border-color: var(--dg-primary);
}

.table-card--governance {
  border-color: var(--dg-success);
}

.table-card--governance .table-card__header {
  background: var(--dg-success-soft, var(--dg-surface-muted));
}

.table-card--restricted {
  background: var(--dg-surface-muted);
  border-style: dashed;
  color: var(--dg-text-muted);
  box-shadow: none;
}

.table-card__header {
  display: flex;
  align-items: center;
  gap: var(--dg-space-sm);
  height: var(--head-h);
  padding: 0 var(--dg-space-md);
  border-bottom: 1px solid var(--dg-border);
  box-sizing: border-box;
}

.table-card--root .table-card__header {
  background: var(--dg-primary-soft);
}

.table-card__lock {
  flex-shrink: 0;
  color: var(--dg-text-muted);
}

.table-card__titles {
  flex: 1;
  min-width: 0;
}

.table-card__title {
  font-size: 13px;
  font-weight: 600;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-card__subtitle {
  font-size: 11px;
  line-height: 1.3;
  color: var(--dg-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sensitivity-badge {
  flex-shrink: 0;
  padding: 0 6px;
  border-radius: var(--dg-radius-sm);
  font-size: 11px;
  line-height: 16px;
  font-weight: 600;
  border: 1px solid var(--dg-border-strong);
  background: var(--dg-surface-muted);
  color: var(--dg-text-secondary);
}

.sensitivity-badge--L2 {
  border-color: var(--dg-primary);
  background: var(--dg-primary-soft);
  color: var(--dg-primary);
}

.sensitivity-badge--L3 {
  border-color: var(--dg-warning);
  background: var(--dg-warning-soft);
  color: var(--dg-warning);
}

.sensitivity-badge--L4 {
  border-color: var(--dg-danger);
  background: var(--dg-danger-soft);
  color: var(--dg-danger);
}

.table-card__sensitivity {
  flex-shrink: 0;
}

.table-card__tag {
  flex-shrink: 0;
  padding: 0 6px;
  border: 1px solid var(--dg-success);
  border-radius: var(--dg-radius-sm);
  color: var(--dg-success);
  font-size: 11px;
  line-height: 16px;
}

.table-card__jump {
  flex-shrink: 0;
  border: 0;
  background: transparent;
  color: var(--dg-primary);
  font-size: 12px;
  cursor: pointer;
}

.table-card__jump:hover {
  color: var(--dg-primary-hover);
}

.table-card__badge {
  flex-shrink: 0;
  padding: 0 6px;
  border-radius: var(--dg-radius-sm);
  background: var(--dg-primary);
  color: var(--dg-surface);
  font-size: 11px;
  line-height: 18px;
}

.table-card__rows {
  margin: 0;
  padding: var(--pad-y) 0;
  list-style: none;
}

.field-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--dg-space-sm);
  height: var(--row-h);
  padding: 0 var(--dg-space-md);
  box-sizing: border-box;
  cursor: pointer;
  transition:
    background-color var(--dg-duration-fast) var(--dg-ease-out),
    opacity var(--dg-duration-base) var(--dg-ease-out);
}

.field-row:hover {
  background: var(--dg-surface-muted);
}

.field-row--root {
  font-weight: 600;
}

.field-row--selected {
  background: var(--dg-primary-soft);
}

.field-row--dim {
  opacity: 0.35;
}

.field-row--extra {
  color: var(--dg-text-muted);
}

.field-row--restricted {
  cursor: default;
}

.field-row__name {
  display: inline-flex;
  align-items: center;
  gap: var(--dg-space-xs);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.field-row__sensitivity {
  flex-shrink: 0;
}

.field-row__type {
  flex-shrink: 0;
  max-width: 84px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--dg-font-mono);
  font-size: 11px;
  color: var(--dg-text-muted);
}

.field-row__mark {
  flex-shrink: 0;
  font-size: 10px;
  color: var(--dg-primary);
}

.table-card__footer {
  height: var(--foot-h);
  display: flex;
  align-items: center;
  justify-content: center;
  border-top: 1px solid var(--dg-border);
  box-sizing: border-box;
}

.table-card__expand {
  border: 0;
  background: transparent;
  color: var(--dg-primary);
  font-size: 12px;
  cursor: pointer;
}

.table-card__expand:hover:not(:disabled) {
  color: var(--dg-primary-hover);
}

.table-card__expand:disabled {
  color: var(--dg-text-muted);
  cursor: default;
}

/* 端口：贴着卡片左右边缘，行内垂直居中。位置由 Vue Flow 的 handle 基础样式给出。 */
.field-handle {
  width: 8px;
  height: 8px;
  min-width: 0;
  min-height: 0;
  border: 1px solid var(--dg-border-strong);
  border-radius: 50%;
  background: var(--dg-surface);
}

.field-row--root .field-handle,
.field-row--selected .field-handle {
  border-color: var(--dg-primary);
  background: var(--dg-primary);
}

.field-row--extra .field-handle {
  display: none;
}
</style>
