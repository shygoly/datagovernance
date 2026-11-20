<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, FunnelChart, LineChart, PieChart, ScatterChart } from 'echarts/charts'
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getInsightChartTheme } from '@/utils/chartTheme'
import type {
  InsightAnswer,
  InsightChartSpec,
  InsightChartType,
  InsightDrilldownOption,
} from '@/types/insight'
import type { SemanticQuerySpec } from '@/types/semantic'

echarts.use([
  BarChart,
  LineChart,
  PieChart,
  ScatterChart,
  FunnelChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  MarkLineComponent,
  CanvasRenderer,
])

export interface ChartBreadcrumb {
  label: string
}

export interface ChartFilterSelection {
  field: string
  fieldLabel: string
  value: string
}

export interface ChartDrilldownEvent {
  label: string
  spec: SemanticQuerySpec
}

const props = withDefaults(
  defineProps<{
    answer: InsightAnswer
    initialChartSpec?: Partial<InsightChartSpec> | null
    breadcrumbs?: ChartBreadcrumb[]
    drilling?: boolean
  }>(),
  {
    initialChartSpec: null,
    breadcrumbs: () => [],
    drilling: false,
  },
)

const emit = defineEmits<{
  'filter-select': [selection: ChartFilterSelection | null]
  drilldown: [event: ChartDrilldownEvent]
  'breadcrumb-back': [index: number]
  'ask-followup': [prompt: string]
  'spec-change': [spec: InsightChartSpec]
}>()

const CHART_TYPE_LABELS: Record<InsightChartType, string> = {
  kpi: '指标卡',
  bar: '柱状图',
  horizontal_bar: '条形图',
  stacked_bar: '堆叠柱图',
  line: '折线图',
  area: '面积图',
  pie: '饼图',
  scatter: '散点图',
  funnel: '漏斗图',
  table: '仅表格',
}

const chartEl = ref<HTMLDivElement>()
let chart: ReturnType<typeof echarts.init> | null = null

const activeType = ref<InsightChartType>('bar')
const selectedMetrics = ref<string[]>([])
const sortOrder = ref<'DESC' | 'ASC' | 'NONE'>('NONE')
const topN = ref<number>(20)
const selectedFilter = ref<ChartFilterSelection | null>(null)

function columnsOf(answer: InsightAnswer) {
  if (answer.columns?.length) {
    return answer.columns.map((col) => ({
      key: col.bizName || col.name,
      label: col.name,
      type: col.type || '',
    }))
  }
  const first = answer.rows?.[0]
  return first ? Object.keys(first).map((key) => ({ key, label: key, type: '' })) : []
}

function isNumeric(value: unknown): boolean {
  if (value === null || value === undefined || value === '') return false
  return !Number.isNaN(Number(value))
}

function toNum(value: unknown): number {
  const n = Number(value)
  return Number.isNaN(n) ? 0 : n
}

const columnMeta = computed(() => {
  const cols = columnsOf(props.answer)
  const rows = props.answer.rows ?? []
  const labelMap = new Map(cols.map((c) => [c.key, c.label]))
  const numeric = cols.filter(
    (c) =>
      /NUM|INT|DEC|DOUBLE|FLOAT/i.test(c.type) ||
      rows.some((r) => isNumeric(r[c.key])),
  )
  const dims = cols.filter((c) => !numeric.some((m) => m.key === c.key))
  return { cols, labelMap, numeric, dims }
})

const effectiveBaseSpec = computed<InsightChartSpec>(() => {
  const rows = props.answer.rows ?? []
  const { numeric, dims } = columnMeta.value
  const backendSpec = props.answer.chartSpec
  const override = props.initialChartSpec

  if (
    props.answer.state === 'METADATA' ||
    props.answer.state === 'INCONCLUSIVE' ||
    props.answer.state === 'CLARIFY_NEEDED' ||
    !rows.length ||
    !numeric.length
  ) {
    return {
      chartType: 'table',
      xField: null,
      seriesField: null,
      yFields: [],
      units: {},
      dualAxis: false,
      defaultSort: 'NONE',
      topN: null,
      supportedTypes: ['table'],
      drilldowns: [],
    }
  }

  const fallbackX = dims[0]?.key ?? null
  const fallbackSeries = dims[1]?.key ?? null
  const fallbackY = numeric.map((n) => n.key)
  const isTime =
    fallbackX !== null &&
    rows.every((r) => /^\d{4}[-/]\d{2}([-/]\d{2})?/.test(String(r[fallbackX] ?? '')))

  let fallbackType: InsightChartType = 'bar'
  if (rows.length === 1 && !dims.length && numeric.length <= 4) {
    fallbackType = 'kpi'
  } else if (isTime) {
    fallbackType = 'line'
  } else if (fallbackSeries) {
    fallbackType = 'stacked_bar'
  } else if (rows.length > 8) {
    fallbackType = 'horizontal_bar'
  }

  const base: InsightChartSpec = backendSpec
    ? {
        ...backendSpec,
        yFields: backendSpec.yFields?.length ? backendSpec.yFields : fallbackY,
        supportedTypes: backendSpec.supportedTypes?.length
          ? backendSpec.supportedTypes
          : [fallbackType, 'bar', 'line', 'table'],
      }
    : {
        chartType: fallbackType,
        xField: fallbackX,
        seriesField: fallbackSeries,
        yFields: fallbackY,
        units: {},
        dualAxis: false,
        defaultSort: isTime ? 'NONE' : 'DESC',
        topN: 20,
        supportedTypes:
          fallbackType === 'kpi'
            ? ['kpi', 'table']
            : isTime
              ? ['line', 'area', 'bar', 'table']
              : ['bar', 'horizontal_bar', 'line', 'area', 'pie', 'funnel', 'table'],
        drilldowns: [],
      }

  return {
    ...base,
    ...(override?.chartType ? { chartType: override.chartType } : {}),
    ...(override?.defaultSort ? { defaultSort: override.defaultSort } : {}),
    ...(override?.topN !== undefined ? { topN: override.topN } : {}),
  }
})

const supportedTypes = computed<InsightChartType[]>(() => {
  const list = effectiveBaseSpec.value.supportedTypes ?? ['table']
  return list.length ? list : ['table']
})

const hasChart = computed(() => effectiveBaseSpec.value.yFields.length > 0 && (props.answer.rows?.length ?? 0) > 0)

const processedRows = computed(() => {
  const raw = [...(props.answer.rows ?? [])]
  const primaryY = selectedMetrics.value[0] || effectiveBaseSpec.value.yFields[0]
  if (primaryY && sortOrder.value !== 'NONE') {
    raw.sort((a, b) => {
      const diff = toNum(a[primaryY]) - toNum(b[primaryY])
      return sortOrder.value === 'ASC' ? diff : -diff
    })
  }
  if (topN.value > 0 && raw.length > topN.value && activeType.value !== 'line' && activeType.value !== 'area') {
    return raw.slice(0, topN.value)
  }
  return raw
})

const kpiCards = computed(() => {
  const row = processedRows.value[0]
  if (!row) return []
  const units = effectiveBaseSpec.value.units ?? {}
  return (selectedMetrics.value.length ? selectedMetrics.value : effectiveBaseSpec.value.yFields).map((key) => {
    const val = row[key]
    const num = toNum(val)
    const unit = units[key] || ''
    const formatted =
      unit === '%'
        ? `${(num <= 1 && num >= -1 ? num * 100 : num).toFixed(2)}%`
        : Number.isFinite(num)
          ? num.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
          : String(val ?? '-')
    return {
      key,
      label: columnMeta.value.labelMap.get(key) || key,
      formatted,
      unit: unit === '%' ? '' : unit,
    }
  })
})

const matchingDrilldowns = computed<InsightDrilldownOption[]>(() => {
  if (!selectedFilter.value) return []
  const field = selectedFilter.value.field
  return (effectiveBaseSpec.value.drilldowns ?? []).filter(
    (d) => d.sourceField.toLowerCase() === field.toLowerCase() || d.sourceDimensionCode.toLowerCase() === field.toLowerCase(),
  )
})

function emitSpecSnapshot() {
  emit('spec-change', {
    ...effectiveBaseSpec.value,
    chartType: activeType.value,
    yFields: selectedMetrics.value.length ? [...selectedMetrics.value] : effectiveBaseSpec.value.yFields,
    defaultSort: sortOrder.value,
    topN: topN.value,
  })
}

function selectChartType(type: InsightChartType) {
  activeType.value = type
  emitSpecSnapshot()
}

function clearSelection() {
  selectedFilter.value = null
  emit('filter-select', null)
}

function triggerDrilldown(opt: InsightDrilldownOption) {
  const sel = selectedFilter.value
  const prevSpec = props.answer.executedSpec
  if (!sel || !prevSpec) return

  const nextDims = (prevSpec.dimensions ?? []).map((d) =>
    d.toLowerCase() === opt.sourceDimensionCode.toLowerCase() ? opt.nextDimensionCode : d,
  )
  if (!nextDims.includes(opt.nextDimensionCode)) {
    nextDims.push(opt.nextDimensionCode)
  }
  const nextFilters = [
    ...(prevSpec.filters ?? []).filter((f) => f.objectCode.toLowerCase() !== opt.sourceDimensionCode.toLowerCase()),
    { objectCode: opt.sourceDimensionCode, op: '=', values: [sel.value] },
  ]
  const nextSpec: SemanticQuerySpec = {
    ...prevSpec,
    dimensions: nextDims,
    filters: nextFilters,
  }
  clearSelection()
  emit('drilldown', {
    label: `${sel.fieldLabel}=${sel.value} → ${opt.nextDimensionName}`,
    spec: nextSpec,
  })
}

function triggerAiAttribution() {
  const sel = selectedFilter.value
  if (!sel) return
  emit(
    'ask-followup',
    `请基于语义知识图谱，对「${sel.fieldLabel} = ${sel.value}」的指标表现与异常原因做受治理归因分析。`,
  )
}

function exportPng() {
  if (!chart) return
  const url = chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: getInsightChartTheme().surface })
  const a = document.createElement('a')
  a.href = url
  a.download = `insight-chart-${Date.now()}.png`
  a.click()
}

function buildEChartsOption() {
  const theme = getInsightChartTheme()
  const rows = processedRows.value
  const spec = effectiveBaseSpec.value
  const xField = spec.xField
  const seriesField = spec.seriesField
  const metrics = selectedMetrics.value.length ? selectedMetrics.value : spec.yFields
  const labelOf = (k: string) => columnMeta.value.labelMap.get(k) || k
  const units = spec.units ?? {}

  if (!rows.length || !metrics.length) return null

  // 饼图（环形 Donut）
  if (activeType.value === 'pie') {
    const mKey = metrics[0]
    return {
      color: theme.palette,
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { bottom: 0, type: 'scroll', textStyle: { color: theme.textSecondary } },
      series: [
        {
          name: labelOf(mKey),
          type: 'pie',
          radius: ['42%', '68%'],
          center: ['50%', '46%'],
          itemStyle: { borderColor: theme.surface, borderWidth: 2 },
          data: rows.map((r, i) => ({
            name: xField ? String(r[xField] ?? `#${i + 1}`) : `#${i + 1}`,
            value: toNum(r[mKey]),
          })),
        },
      ],
    }
  }

  // 漏斗图
  if (activeType.value === 'funnel') {
    const mKey = metrics[0]
    return {
      color: theme.palette,
      tooltip: { trigger: 'item', formatter: '{b}: {c}' },
      legend: { bottom: 0, type: 'scroll', textStyle: { color: theme.textSecondary } },
      series: [
        {
          name: labelOf(mKey),
          type: 'funnel',
          left: '12%',
          top: 20,
          bottom: 42,
          width: '76%',
          sort: 'descending',
          data: rows.map((r, i) => ({
            name: xField ? String(r[xField] ?? `#${i + 1}`) : `#${i + 1}`,
            value: toNum(r[mKey]),
          })),
        },
      ],
    }
  }

  // 散点图
  if (activeType.value === 'scatter' && metrics.length >= 2) {
    const mX = metrics[0]
    const mY = metrics[1]
    return {
      color: theme.palette,
      tooltip: { trigger: 'item' },
      grid: { left: 56, right: 24, top: 28, bottom: 44 },
      xAxis: { type: 'value', name: labelOf(mX), splitLine: { lineStyle: { color: theme.border } } },
      yAxis: { type: 'value', name: labelOf(mY), splitLine: { lineStyle: { color: theme.border } } },
      series: [
        {
          type: 'scatter',
          symbolSize: 10,
          data: rows.map((r) => [toNum(r[mX]), toNum(r[mY])]),
        },
      ],
    }
  }

  // 堆叠柱图（两维度透视）
  if (activeType.value === 'stacked_bar' && xField && seriesField) {
    const mKey = metrics[0]
    const xCats = [...new Set(rows.map((r) => String(r[xField] ?? '-')))]
    const sCats = [...new Set(rows.map((r) => String(r[seriesField] ?? '-')))]
    const lookup = new Map<string, number>()
    for (const r of rows) {
      const k = `${String(r[xField] ?? '-')}||${String(r[seriesField] ?? '-')}`
      lookup.set(k, (lookup.get(k) ?? 0) + toNum(r[mKey]))
    }
    return {
      color: theme.palette,
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      legend: { top: 0, type: 'scroll', textStyle: { color: theme.textSecondary } },
      grid: { left: 52, right: 24, top: 38, bottom: 56 },
      xAxis: { type: 'category', data: xCats, axisLabel: { color: theme.textSecondary, rotate: xCats.length > 6 ? 25 : 0 } },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: theme.border } } },
      series: sCats.map((sc) => ({
        name: sc,
        type: 'bar',
        stack: 'total',
        data: xCats.map((xc) => lookup.get(`${xc}||${sc}`) ?? 0),
      })),
    }
  }

  // 标准柱图 / 条形图 / 折线图 / 面积图（支持多指标 + 自动双 Y 轴 + 缩放）
  const categories = rows.map((r, i) => (xField ? String(r[xField] ?? `#${i + 1}`) : `#${i + 1}`))
  const isHorizontal = activeType.value === 'horizontal_bar'
  const eType = activeType.value === 'line' || activeType.value === 'area' ? 'line' : 'bar'
  const useDualAxis = Boolean(spec.dualAxis) && metrics.length >= 2 && !isHorizontal

  const yAxes = useDualAxis
    ? [
        {
          type: 'value',
          name: units[metrics[0]] || labelOf(metrics[0]),
          splitLine: { lineStyle: { color: theme.border } },
          axisLabel: { color: theme.textMuted },
        },
        {
          type: 'value',
          name: units[metrics[1]] || labelOf(metrics[1]),
          splitLine: { show: false },
          axisLabel: { color: theme.textMuted },
        },
      ]
    : [{ type: 'value', name: units[metrics[0]] || '', splitLine: { lineStyle: { color: theme.border } }, axisLabel: { color: theme.textMuted } }]

  const needZoom = !isHorizontal && categories.length > 12
  return {
    color: theme.palette,
    tooltip: { trigger: 'axis', axisPointer: { type: eType === 'bar' ? 'shadow' : 'cross' } },
    legend: metrics.length > 1 ? { top: 0, type: 'scroll', textStyle: { color: theme.textSecondary } } : undefined,
    grid: {
      left: isHorizontal ? 110 : 54,
      right: useDualAxis ? 56 : 24,
      top: metrics.length > 1 ? 38 : 28,
      bottom: needZoom ? 72 : 54,
    },
    dataZoom: needZoom ? [{ type: 'inside' }, { type: 'slider', height: 18, bottom: 8 }] : undefined,
    xAxis: isHorizontal
      ? { type: 'value', splitLine: { lineStyle: { color: theme.border } }, axisLabel: { color: theme.textMuted } }
      : {
          type: 'category',
          data: categories,
          axisLabel: { color: theme.textSecondary, rotate: categories.length > 6 ? 25 : 0 },
        },
    yAxis: isHorizontal
      ? { type: 'category', data: categories, inverse: true, axisLabel: { color: theme.textSecondary } }
      : yAxes,
    series: metrics.map((mKey, idx) => ({
      name: labelOf(mKey),
      type: eType,
      yAxisIndex: useDualAxis ? Math.min(1, idx) : 0,
      smooth: eType === 'line',
      areaStyle: activeType.value === 'area' ? { opacity: 0.16 } : undefined,
      barMaxWidth: 36,
      data: rows.map((r) => toNum(r[mKey])),
    })),
  }
}

function renderChart() {
  if (!hasChart.value || activeType.value === 'kpi' || activeType.value === 'table' || !chartEl.value) {
    chart?.dispose()
    chart = null
    return
  }
  if (!chart) {
    chart = echarts.init(chartEl.value)
    chart.on('click', (params: any) => {
      const xField = effectiveBaseSpec.value.xField
      if (!xField || params?.name === undefined || params?.name === null) return
      const val = String(params.name)
      const selection: ChartFilterSelection = {
        field: xField,
        fieldLabel: columnMeta.value.labelMap.get(xField) || xField,
        value: val,
      }
      if (selectedFilter.value?.field === selection.field && selectedFilter.value?.value === selection.value) {
        clearSelection()
      } else {
        selectedFilter.value = selection
        emit('filter-select', selection)
      }
    })
  }
  const opt = buildEChartsOption()
  if (opt) {
    chart.setOption(opt, true)
  }
}

function syncStateFromProps() {
  const spec = effectiveBaseSpec.value
  activeType.value = spec.chartType
  selectedMetrics.value = [...spec.yFields]
  sortOrder.value = (spec.defaultSort as 'DESC' | 'ASC' | 'NONE') || 'NONE'
  topN.value = spec.topN ?? 20
  selectedFilter.value = null
}

function onResize() {
  chart?.resize()
}

watch(
  () => props.answer,
  async () => {
    syncStateFromProps()
    await nextTick()
    renderChart()
  },
  { immediate: true },
)

watch([activeType, selectedMetrics, sortOrder, topN], async () => {
  await nextTick()
  renderChart()
})

onMounted(async () => {
  window.addEventListener('resize', onResize)
  await nextTick()
  renderChart()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div v-if="hasChart" class="interactive-chart">
    <div v-if="breadcrumbs.length > 1" class="chart-breadcrumbs">
      <el-breadcrumb separator="/">
        <el-breadcrumb-item
          v-for="(bc, idx) in breadcrumbs"
          :key="`${bc.label}-${idx}`"
        >
          <el-button
            v-if="idx < breadcrumbs.length - 1"
            link
            type="primary"
            size="small"
            @click="emit('breadcrumb-back', idx)"
          >
            {{ bc.label }}
          </el-button>
          <span v-else class="bc-current">{{ bc.label }}</span>
        </el-breadcrumb-item>
      </el-breadcrumb>
    </div>

    <div class="chart-toolbar">
      <div class="toolbar-left">
        <el-button-group size="small">
          <el-button
            v-for="t in supportedTypes"
            :key="t"
            :type="activeType === t ? 'primary' : 'default'"
            @click="selectChartType(t)"
          >
            {{ CHART_TYPE_LABELS[t] || t }}
          </el-button>
        </el-button-group>
        <el-tag v-if="effectiveBaseSpec.dualAxis && activeType !== 'table' && activeType !== 'kpi'" size="small" type="info">
          双 Y 轴
        </el-tag>
      </div>

      <div v-if="activeType !== 'table'" class="toolbar-right">
        <el-select
          v-if="columnMeta.numeric.length > 1 && activeType !== 'pie' && activeType !== 'funnel'"
          v-model="selectedMetrics"
          multiple
          collapse-tags
          size="small"
          placeholder="选择指标"
          style="width: 170px"
          @change="emitSpecSnapshot"
        >
          <el-option
            v-for="m in columnMeta.numeric"
            :key="m.key"
            :label="m.label"
            :value="m.key"
          />
        </el-select>

        <el-select
          v-if="activeType !== 'kpi' && activeType !== 'scatter'"
          v-model="sortOrder"
          size="small"
          style="width: 88px"
          @change="emitSpecSnapshot"
        >
          <el-option label="降序" value="DESC" />
          <el-option label="升序" value="ASC" />
          <el-option label="原序" value="NONE" />
        </el-select>

        <el-select
          v-if="activeType !== 'kpi' && activeType !== 'line' && activeType !== 'area'"
          v-model="topN"
          size="small"
          style="width: 88px"
          @change="emitSpecSnapshot"
        >
          <el-option label="Top 10" :value="10" />
          <el-option label="Top 20" :value="20" />
          <el-option label="全部" :value="0" />
        </el-select>

        <el-button v-if="activeType !== 'kpi'" size="small" @click="exportPng">
          导出 PNG
        </el-button>
      </div>
    </div>

    <div v-if="activeType === 'kpi'" class="kpi-grid">
      <div v-for="card in kpiCards" :key="card.key" class="kpi-card">
        <div class="kpi-label">{{ card.label }}</div>
        <div class="kpi-value">
          {{ card.formatted }}
          <span v-if="card.unit" class="kpi-unit">{{ card.unit }}</span>
        </div>
      </div>
    </div>

    <div
      v-else-if="activeType !== 'table'"
      ref="chartEl"
      v-loading="drilling"
      class="answer-chart"
    />

    <div v-if="selectedFilter" class="selection-bar">
      <div class="selection-info">
        <el-tag size="small" type="primary">
          已筛选：{{ selectedFilter.fieldLabel }} = {{ selectedFilter.value }}
        </el-tag>
        <el-button link size="small" @click="clearSelection">清除联动筛选</el-button>
      </div>
      <div class="selection-actions">
        <el-button
          v-for="opt in matchingDrilldowns"
          :key="opt.nextDimensionCode"
          size="small"
          type="primary"
          plain
          :loading="drilling"
          @click="triggerDrilldown(opt)"
        >
          {{ opt.actionLabel }}
        </el-button>
        <el-button size="small" plain @click="triggerAiAttribution">
          AI 归因分析
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.interactive-chart {
  margin-top: var(--dg-space-sm);
  padding: var(--dg-space-md);
  background: var(--dg-surface);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
}
.chart-breadcrumbs {
  margin-bottom: var(--dg-space-sm);
}
.bc-current {
  font-size: 12px;
  font-weight: 600;
  color: var(--dg-text);
}
.chart-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  margin-bottom: var(--dg-space-sm);
}
.toolbar-left,
.toolbar-right {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--dg-space-sm);
}
.answer-chart {
  width: 100%;
  height: 300px;
}
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--dg-space-md);
  padding: var(--dg-space-sm) 0;
}
.kpi-card {
  padding: var(--dg-space-md);
  background: var(--dg-surface-muted);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
}
.kpi-label {
  font-size: 12px;
  color: var(--dg-text-muted);
}
.kpi-value {
  margin-top: 6px;
  font-size: 24px;
  font-weight: 600;
  color: var(--dg-text);
  font-variant-numeric: tabular-nums;
}
.kpi-unit {
  margin-left: 4px;
  font-size: 13px;
  font-weight: 400;
  color: var(--dg-text-muted);
}
.selection-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  margin-top: var(--dg-space-sm);
  padding: var(--dg-space-sm) var(--dg-space-md);
  background: var(--dg-surface-muted);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
}
.selection-info,
.selection-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--dg-space-sm);
}
</style>
