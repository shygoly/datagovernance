<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import type { EtlFlowNodeData } from '@/types/etl'
import { kindMeta } from '@/utils/etl'

/**
 * ETL 画布节点。四种外观(Source / Transform / CustomSQL / Sink)共用这一个组件,
 * 靠 data.kind 取元数据 —— 外观差异只是顶部色条与端口方向,没必要拆四份。
 * 颜色全部取 var(--dg-*) 语义色,不引入新色值。
 */
const props = defineProps<{ id: string; data: EtlFlowNodeData; selected?: boolean }>()

const meta = computed(() => kindMeta(props.data.kind))
const hasInput = computed(() => meta.value.visual !== 'source')
const hasOutput = computed(() => meta.value.visual !== 'sink')

const summary = computed(() => {
  const c = props.data.config
  const str = (k: string) => (typeof c[k] === 'string' ? (c[k] as string) : '')
  switch (props.data.kind) {
    case 'SOURCE_JDBC':
      return str('table') || '未选择表'
    case 'TRANSFORM_FILTER':
      return str('condition') || '未配置条件'
    case 'TRANSFORM_CUSTOM_SQL':
      return str('sql').replace(/\s+/g, ' ').slice(0, 48) || '未编写 SQL'
    case 'SINK_JDBC':
      return `${str('table') || '未选择表'} · ${str('writeMode') || 'APPEND'}`
    case 'SINK_PARQUET':
      return str('path') || '未配置路径'
    default:
      return ''
  }
})
</script>

<template>
  <div
    class="etl-node"
    :class="[
      `etl-node--${meta.visual}`,
      { 'etl-node--selected': selected, [`etl-node--diag-${data.diagnostic}`]: !!data.diagnostic },
    ]"
  >
    <Handle v-if="hasInput" type="target" :position="Position.Left" />
    <div class="etl-node__head">
      <span class="etl-node__kind">{{ meta.label }}</span>
      <span class="etl-node__id text-mono">{{ id }}</span>
    </div>
    <div class="etl-node__name">{{ data.name || id }}</div>
    <div v-if="summary" class="etl-node__summary text-muted" :title="summary">{{ summary }}</div>
    <Handle v-if="hasOutput" type="source" :position="Position.Right" />
  </div>
</template>

<style scoped>
.etl-node {
  --etl-accent: var(--dg-primary);
  --etl-soft: var(--dg-primary-soft);
  width: 220px;
  padding: var(--dg-space-sm) var(--dg-space-md);
  background: var(--dg-surface);
  border: 1px solid var(--dg-border-strong);
  border-top: 3px solid var(--etl-accent);
  border-radius: var(--dg-radius);
  color: var(--dg-text);
  cursor: pointer;
  transition: box-shadow var(--dg-duration-fast) var(--dg-ease-out),
    border-color var(--dg-duration-fast) var(--dg-ease-out);
}

.etl-node--source {
  --etl-accent: var(--dg-primary);
  --etl-soft: var(--dg-primary-soft);
}

.etl-node--transform {
  --etl-accent: var(--dg-info);
  --etl-soft: var(--dg-info-soft);
}

.etl-node--customSql {
  --etl-accent: var(--dg-warning);
  --etl-soft: var(--dg-warning-soft);
}

.etl-node--sink {
  --etl-accent: var(--dg-success);
  --etl-soft: var(--dg-success-soft);
}

.etl-node--selected {
  border-color: var(--etl-accent);
  box-shadow: var(--dg-shadow-overlay);
}

.etl-node--diag-error {
  border-color: var(--dg-danger);
  background: var(--dg-danger-soft);
}

.etl-node--diag-warning {
  border-color: var(--dg-warning);
}

.etl-node__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  margin-bottom: var(--dg-space-xs);
}

.etl-node__kind {
  padding: 0 6px;
  font-size: 11px;
  line-height: 18px;
  color: var(--etl-accent);
  background: var(--etl-soft);
  border-radius: var(--dg-radius-sm);
}

.etl-node__id {
  font-size: 11px;
  color: var(--dg-text-muted);
}

.etl-node__name {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.etl-node__summary {
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
