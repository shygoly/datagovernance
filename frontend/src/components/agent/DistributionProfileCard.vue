<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

defineOptions({ name: 'DistributionProfileCard' })

export interface ProfileNullItem {
  column: string
  nullCount: number
  nullRatio: number
  anomalous?: boolean
}

export interface ProfileAnomalyItem {
  dimension: string
  columnName?: string
  column?: string
  violationCount: number
  violationRatio: number
  standardRef?: string
  summary?: string
}

const props = defineProps<{
  datasetName?: string
  verdict?: string
  totalRows?: number
  truncated?: boolean
  anomalyCount?: number
  patternFingerprint?: string | null
  nullRates?: ProfileNullItem[]
  anomalies?: ProfileAnomalyItem[]
  standardRefs?: string[]
}>()

const chartRef = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

const verdictTag = computed(() => {
  if (props.verdict === 'OK') return { type: 'success' as const, label: '通过 OK' }
  if (props.verdict === 'ANOMALIES_DETECTED') return { type: 'warning' as const, label: '检出异常' }
  return { type: 'info' as const, label: 'INCONCLUSIVE（证据不足）' }
})

const chartItems = computed(() => {
  const list = Array.isArray(props.nullRates) ? props.nullRates : []
  return list.slice(0, 10).map((item) => ({
    name: String(item.column || ''),
    ratioPct: Number(((Number(item.nullRatio || 0)) * 100).toFixed(2)),
    anomalous: Boolean(item.anomalous),
  }))
})

function cssVar(name: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

function renderChart() {
  if (!chartRef.value || !chartItems.value.length) return
  if (!chart) {
    chart = echarts.init(chartRef.value)
  }
  const primary = cssVar('--dg-primary', 'rgb(36, 86, 184)')
  const warning = cssVar('--dg-warning', 'rgb(184, 107, 20)')
  const textSecondary = cssVar('--dg-text-secondary', 'rgb(82, 91, 108)')
  const border = cssVar('--dg-border', 'rgb(229, 232, 238)')

  chart.setOption({
    grid: { top: 18, right: 16, bottom: 28, left: 42 },
    tooltip: { trigger: 'axis', valueFormatter: (v: unknown) => `${v}%` },
    xAxis: {
      type: 'category',
      data: chartItems.value.map((i) => i.name),
      axisLabel: { color: textSecondary, fontSize: 11 },
      axisLine: { lineStyle: { color: border } },
    },
    yAxis: {
      type: 'value',
      max: 100,
      axisLabel: { color: textSecondary, fontSize: 11, formatter: '{value}%' },
      splitLine: { lineStyle: { color: border } },
    },
    series: [
      {
        name: '空值率',
        type: 'bar',
        barMaxWidth: 28,
        data: chartItems.value.map((i) => ({
          value: i.ratioPct,
          itemStyle: { color: i.anomalous ? warning : primary, borderRadius: [3, 3, 0, 0] },
        })),
      },
    ],
  })
}

onMounted(renderChart)
watch(chartItems, renderChart, { deep: true })
onBeforeUnmount(() => {
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="dg-profile-card">
    <div class="dg-profile-card__header">
      <div class="dg-profile-card__heading">
        <span class="dg-profile-card__title">确定性质量剖析 · {{ datasetName || '数据集' }}</span>
        <el-tag size="small" :type="verdictTag.type">{{ verdictTag.label }}</el-tag>
      </div>
      <div class="dg-profile-card__meta">
        <span>样本 {{ (totalRows ?? 0).toLocaleString() }} 行</span>
        <span v-if="patternFingerprint" class="dg-profile-card__fp">{{ patternFingerprint }}</span>
      </div>
    </div>

    <el-alert
      v-if="truncated"
      type="info"
      :closable="false"
      show-icon
      title="查询结果发生截断，按 C8 宪法强制标记为 INCONCLUSIVE"
      class="dg-profile-card__alert"
    />

    <div v-if="chartItems.length" ref="chartRef" class="dg-profile-card__chart" />

    <div v-if="anomalies?.length" class="dg-profile-card__anomalies">
      <div
        v-for="(item, idx) in anomalies"
        :key="`${item.dimension}-${idx}`"
        class="dg-profile-card__anomaly"
      >
        <el-tag size="small" type="warning">{{ item.dimension }}</el-tag>
        <span class="dg-profile-card__col">{{ item.columnName || item.column }}</span>
        <span class="dg-profile-card__stat">
          {{ Number(item.violationCount || 0).toLocaleString() }} 条
          ({{ (Number(item.violationRatio || 0) * 100).toFixed(2) }}%)
        </span>
        <span v-if="item.standardRef" class="dg-profile-card__std">{{ item.standardRef }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dg-profile-card {
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
  background: var(--dg-surface);
  padding: var(--dg-space-md);
}
.dg-profile-card__header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  margin-bottom: var(--dg-space-sm);
}
.dg-profile-card__heading {
  display: inline-flex;
  align-items: center;
  gap: var(--dg-space-sm);
}
.dg-profile-card__title {
  font-weight: 600;
  font-size: 13px;
  color: var(--dg-text);
}
.dg-profile-card__meta {
  display: inline-flex;
  align-items: center;
  gap: var(--dg-space-sm);
  font-size: 12px;
  color: var(--dg-text-secondary);
  font-family: var(--dg-font-mono);
}
.dg-profile-card__fp {
  padding: 1px 6px;
  border-radius: var(--dg-radius-sm);
  background: var(--dg-surface-muted);
  color: var(--dg-text-muted);
}
.dg-profile-card__alert {
  margin-bottom: var(--dg-space-sm);
}
.dg-profile-card__chart {
  width: 100%;
  height: 180px;
  margin-bottom: var(--dg-space-sm);
}
.dg-profile-card__anomalies {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.dg-profile-card__anomaly {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--dg-space-sm);
  padding: 6px 8px;
  border-radius: var(--dg-radius-sm);
  background: var(--dg-warning-soft);
  font-size: 12px;
  color: var(--dg-text);
}
.dg-profile-card__col,
.dg-profile-card__stat,
.dg-profile-card__std {
  font-family: var(--dg-font-mono);
}
.dg-profile-card__std {
  margin-left: auto;
  color: var(--dg-text-secondary);
}
</style>
