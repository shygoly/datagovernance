<script setup lang="ts">
import { computed } from 'vue'

defineOptions({ name: 'FilterFunnelCard' })

export interface FunnelStep {
  label: string
  count: number
  retentionRatio?: number
  excludedReason?: string
}

const props = defineProps<{
  title?: string
  steps?: FunnelStep[]
}>()

const normalizedSteps = computed(() => {
  const list = Array.isArray(props.steps) ? props.steps : []
  if (!list.length) return []
  const maxCount = Math.max(1, Number(list[0].count || 0))
  return list.map((step, index) => {
    const count = Number(step.count || 0)
    const ratio = typeof step.retentionRatio === 'number'
      ? step.retentionRatio
      : count / maxCount
    const prev = index > 0 ? Number(list[index - 1].count || 0) : count
    const dropped = index > 0 ? Math.max(0, prev - count) : 0
    return {
      label: String(step.label || `步骤 ${index + 1}`),
      count,
      pct: Math.max(0, Math.min(100, Number((ratio * 100).toFixed(1)))),
      dropped,
      excludedReason: step.excludedReason ? String(step.excludedReason) : '',
    }
  })
})
</script>

<template>
  <div class="dg-funnel-card">
    <div class="dg-funnel-card__header">
      <span class="dg-funnel-card__title">{{ title || '多步筛选 / 纳排漏斗' }}</span>
      <el-tag size="small" type="info">白名单声明式画布</el-tag>
    </div>

    <div v-if="normalizedSteps.length" class="dg-funnel-card__steps">
      <div v-for="(step, idx) in normalizedSteps" :key="`${step.label}-${idx}`" class="dg-funnel-card__row">
        <div class="dg-funnel-card__meta">
          <span class="dg-funnel-card__step-label">{{ idx + 1 }}. {{ step.label }}</span>
          <span class="dg-funnel-card__numbers">
            <strong class="dg-funnel-card__count">{{ step.count.toLocaleString() }}</strong>
            <span class="dg-funnel-card__pct">{{ step.pct }}%</span>
          </span>
        </div>
        <div class="dg-funnel-card__track">
          <div class="dg-funnel-card__bar" :style="{ width: `${step.pct}%` }" />
        </div>
        <div v-if="step.dropped > 0 || step.excludedReason" class="dg-funnel-card__sub">
          <span v-if="step.dropped > 0">较上步剔除 {{ step.dropped.toLocaleString() }} 条</span>
          <span v-if="step.excludedReason"> · {{ step.excludedReason }}</span>
        </div>
      </div>
    </div>
    <el-empty v-else description="暂无漏斗步骤数据" :image-size="48" />
  </div>
</template>

<style scoped>
.dg-funnel-card {
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
  background: var(--dg-surface);
  padding: var(--dg-space-md);
}
.dg-funnel-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--dg-space-md);
}
.dg-funnel-card__title {
  font-weight: 600;
  color: var(--dg-text);
  font-size: 13px;
}
.dg-funnel-card__steps {
  display: flex;
  flex-direction: column;
  gap: var(--dg-space-sm);
}
.dg-funnel-card__row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.dg-funnel-card__meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--dg-text);
}
.dg-funnel-card__numbers {
  display: inline-flex;
  gap: var(--dg-space-sm);
  font-family: var(--dg-font-mono);
}
.dg-funnel-card__pct {
  color: var(--dg-text-secondary);
}
.dg-funnel-card__track {
  height: 8px;
  border-radius: var(--dg-radius-sm);
  background: var(--dg-surface-muted);
  overflow: hidden;
}
.dg-funnel-card__bar {
  height: 100%;
  background: var(--dg-primary);
  border-radius: var(--dg-radius-sm);
  transition: width var(--dg-duration-base) var(--dg-ease-out);
}
.dg-funnel-card__sub {
  font-size: 11px;
  color: var(--dg-text-muted);
}
</style>
