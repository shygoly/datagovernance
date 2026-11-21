<script setup lang="ts">
import { computed } from 'vue'
import type { LineageOverview } from '@/types/lineage'
import EmptyState from '@/components/common/EmptyState.vue'

/**
 * 血缘概览态：未选择数据集时展示。
 * 只负责展示与抛出事件，数据加载与路由跳转由 LineageView 处理。
 */
const props = defineProps<{
  overview: LineageOverview | null
  loading?: boolean
  error?: string
}>()

const emit = defineEmits<{
  'pick-dataset': [datasetId: string]
  'view-job-diff': [payload: { jobId: string; jobName?: string | null }]
  refresh: []
}>()

const kpis = computed(() => {
  const data = props.overview
  return [
    { key: 'datasets', label: '已建血缘数据集', value: data?.datasetsWithLineage ?? 0, tone: 'primary' },
    { key: 'edges', label: '活跃列级边', value: data?.activeColumnEdges ?? 0, tone: 'success' },
    { key: 'jobs', label: '活跃血缘作业', value: data?.activeJobs ?? 0, tone: 'info' },
    {
      key: 'sensitivity',
      label: '敏感传播待复核',
      value: data?.pendingSensitivitySuggestions ?? 0,
      tone: 'warning',
    },
  ]
})

const isEmpty = computed(
  () =>
    !!props.overview &&
    !props.overview.recentDatasets?.length &&
    !props.overview.recentJobs?.length &&
    !props.overview.datasetsWithLineage,
)

function onRowClick(row: { datasetId: string }) {
  emit('pick-dataset', row.datasetId)
}

function parseStatusTag(status?: string | null): 'success' | 'warning' | 'danger' | 'info' {
  switch ((status || 'OK').toUpperCase()) {
    case 'OK':
      return 'success'
    case 'INCONCLUSIVE':
      return 'warning'
    case 'ERROR':
      return 'danger'
    default:
      return 'info'
  }
}

function parseStatusLabel(status?: string | null): string {
  switch ((status || 'OK').toUpperCase()) {
    case 'OK':
      return '解析成功'
    case 'INCONCLUSIVE':
      return '无法确定'
    case 'ERROR':
      return '解析失败'
    default:
      return status || '-'
  }
}

function formatTime(value?: string | null): string {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}
</script>

<template>
  <div v-loading="!!loading" class="lineage-overview">
    <el-alert
      v-if="error"
      type="error"
      show-icon
      :closable="false"
      :title="error"
      class="lineage-overview__alert"
    >
      <el-button link type="primary" @click="emit('refresh')">重试</el-button>
    </el-alert>

    <div class="lineage-overview__kpis">
      <div
        v-for="item in kpis"
        :key="item.key"
        class="kpi-card"
        :class="`kpi-card--${item.tone}`"
      >
        <div class="kpi-card__label">{{ item.label }}</div>
        <div class="kpi-card__value">{{ item.value }}</div>
      </div>
    </div>

    <EmptyState
      v-if="isEmpty && !loading"
      description="工作空间内还没有任何血缘"
    >
      <span class="lineage-overview__hint">
        可以通过「SQL 解析导入」「手工补录血缘」或作业采集产生第一批血缘
      </span>
    </EmptyState>

    <div v-else class="lineage-overview__lists">
      <el-card shadow="never">
        <template #header>
          <div class="overview-head">
            <span class="overview-head__title">最近更新的血缘数据集</span>
            <span class="overview-head__hint">点击进入该数据集的血缘</span>
          </div>
        </template>
        <el-table
          :data="overview?.recentDatasets ?? []"
          size="small"
          class="overview-table"
          empty-text="暂无有血缘的数据集"
          @row-click="onRowClick"
        >
          <el-table-column label="数据集" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="dataset-cell">
                <span class="dataset-cell__name">{{ row.name }}</span>
                <span class="dataset-cell__meta">{{ row.qualifiedName }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="上游列边" width="96" align="right">
            <template #default="{ row }">
              <el-tooltip content="指向本表字段的活跃列级边数（进入后看表级汇总）" placement="top">
                <el-tag size="small" type="info">{{ row.upstreamEdges }}</el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="下游列边" width="96" align="right">
            <template #default="{ row }">
              <el-tooltip content="本表字段指出的活跃列级边数（进入后看表级汇总）" placement="top">
                <el-tag size="small" type="info">{{ row.downstreamEdges }}</el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="最近发现" width="168">
            <template #default="{ row }">{{ formatTime(row.lastDiscoveredAt) }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <div class="overview-head">
            <span class="overview-head__title">最近血缘作业</span>
            <span class="overview-head__hint">含解析状态与诊断</span>
          </div>
        </template>
        <el-table
          :data="overview?.recentJobs ?? []"
          size="small"
          empty-text="暂无血缘作业"
        >
          <el-table-column label="作业" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="dataset-cell">
                <span class="dataset-cell__name">{{ row.name }}</span>
                <span class="dataset-cell__meta">{{ row.jobType || '-' }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="解析状态" width="110">
            <template #default="{ row }">
              <el-tooltip
                :disabled="!row.parseMessage"
                :content="row.parseMessage || ''"
                placement="top"
              >
                <el-tag size="small" :type="parseStatusTag(row.parseStatus)">
                  {{ parseStatusLabel(row.parseStatus) }}
                </el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="活跃 / 预估边" width="120" align="right">
            <template #default="{ row }">
              {{ row.activeEdges }} / {{ row.plannedEdges }}
            </template>
          </el-table-column>
          <el-table-column label="最近出现" width="168">
            <template #default="{ row }">{{ formatTime(row.lastSeenAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="96" fixed="right">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                @click="emit('view-job-diff', { jobId: row.jobId, jobName: row.name })"
              >
                历史变更
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.lineage-overview {
  min-height: 240px;
}

.lineage-overview__alert {
  margin-bottom: var(--dg-space-md);
}

.lineage-overview__kpis {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--dg-space-md);
  margin-bottom: var(--dg-space-md);
}

.kpi-card {
  padding: var(--dg-space-lg);
  background: var(--dg-surface);
  border: 1px solid var(--dg-border);
  border-left-width: 3px;
  border-radius: var(--dg-radius);
}

.kpi-card--primary {
  border-left-color: var(--dg-primary);
}

.kpi-card--success {
  border-left-color: var(--dg-success);
}

.kpi-card--info {
  border-left-color: var(--dg-info);
}

.kpi-card--warning {
  border-left-color: var(--dg-warning);
}

.kpi-card__label {
  font-size: 12px;
  color: var(--dg-text-muted);
}

.kpi-card__value {
  margin-top: var(--dg-space-xs);
  font-size: 24px;
  font-weight: 600;
  color: var(--dg-text);
  line-height: 1.2;
}

.lineage-overview__hint {
  font-size: 12px;
  color: var(--dg-text-muted);
}

.lineage-overview__lists {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--dg-space-md);
}

.overview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
}

.overview-head__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--dg-text);
}

.overview-head__hint {
  font-size: 12px;
  color: var(--dg-text-muted);
}

.overview-table :deep(.el-table__row) {
  cursor: pointer;
}

.dataset-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
  min-width: 0;
}

.dataset-cell__name {
  color: var(--dg-text);
}

.dataset-cell__meta {
  font-size: 11px;
  color: var(--dg-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 1100px) {
  .lineage-overview__kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .lineage-overview__lists {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
