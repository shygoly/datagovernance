<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import { autonomyApi } from '@/api/autonomy'
import { formatDateTime } from '@/utils/format'
import type { DecisionTrace } from '@/types/autonomy'

defineOptions({ name: 'AutonomyTraceListView' })

const route = useRoute()
const loading = ref(false)
const rows = ref<DecisionTrace[]>([])
const total = ref(0)
const query = reactive({
  page: 1,
  size: 20,
  verifyStatus: '',
  runMode: '',
})

const detailVisible = ref(false)
const detail = ref<DecisionTrace | null>(null)
const verifyVisible = ref(false)
const verifyText = ref('{\n  "metrics": {\n    "quality_pass_rate": 0.995\n  }\n}')
const verifyBusy = ref(false)

const verifyMeta: Record<string, { label: string; type: 'info' | 'success' | 'warning' | 'danger' }> = {
  PENDING: { label: '待回检', type: 'info' },
  IMPROVED: { label: '改善', type: 'success' },
  NO_EFFECT: { label: '无变化', type: 'info' },
  DEGRADED: { label: '恶化', type: 'danger' },
  INCONCLUSIVE: { label: '证据不足', type: 'warning' },
}

const shadowObjectives = computed(() => {
  const evaluation = detail.value?.verifyResult?.evaluation as Record<string, unknown> | undefined
  const objectives = evaluation?.objectives
  return Array.isArray(objectives) ? objectives as Array<Record<string, unknown>> : []
})

const timeline = computed(() => {
  if (!detail.value) return []
  return [
    { title: '触发快照', value: detail.value.triggerSnapshot },
    { title: 'Arbiter 裁决', value: detail.value.arbiterDecision },
    { title: 'Dry-Run 预演', value: detail.value.dryRunResult },
    { title: '宿主审批', value: detail.value.proposalId ? { proposalId: detail.value.proposalId } : {} },
    { title: 'Activity 执行', value: detail.value.activityResult },
    { title: 'T+1 回检', value: detail.value.verifyResult },
  ]
})

async function load() {
  loading.value = true
  try {
    const page = await autonomyApi.listTraces({
      page: query.page,
      size: query.size,
      verifyStatus: query.verifyStatus || undefined,
      runMode: query.runMode || undefined,
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await load()
  const instanceId = typeof route.query.instanceId === 'string' ? route.query.instanceId : ''
  if (instanceId) {
    try {
      detail.value = await autonomyApi.getTraceByInstance(instanceId)
      detailVisible.value = true
    } catch {
      // 轨迹可能尚未落盘；列表仍可用
    }
  }
})

function openDetail(row: DecisionTrace) {
  detail.value = row
  detailVisible.value = true
}

function metaOf(status: string) {
  return verifyMeta[status] || { label: status, type: 'info' as const }
}

async function submitVerify() {
  if (!detail.value?.instanceId) {
    ElMessage.warning('该轨迹没有关联实例，无法提交回检')
    return
  }
  let payload: Record<string, unknown>
  try {
    payload = JSON.parse(verifyText.value || '{}')
  } catch {
    ElMessage.error('回检数据不是合法 JSON')
    return
  }
  verifyBusy.value = true
  try {
    await autonomyApi.signalInstance(detail.value.instanceId, 'VERIFY_DUE', payload)
    ElMessage.success('已提交回检数据并唤醒流程')
    verifyVisible.value = false
    await load()
    const refreshed = await autonomyApi.getTrace(detail.value.traceId)
    detail.value = refreshed
  } finally {
    verifyBusy.value = false
  }
}

function onFilterChange() {
  query.page = 1
  void load()
}
</script>

<template>
  <div class="page-container">
    <PageHeader
      title="决策轨迹"
      description="触发快照 → Arbiter 裁决 → Dry-Run → 宿主审批 → 执行/补偿 → T+1 效果回检。"
    >
      <template #actions>
        <el-button @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <el-card shadow="never">
      <div class="filter-row">
        <el-select v-model="query.verifyStatus" size="small" clearable placeholder="回检结论" style="width: 150px" @change="onFilterChange">
          <el-option v-for="(meta, key) in verifyMeta" :key="key" :label="meta.label" :value="key" />
        </el-select>
        <el-select v-model="query.runMode" size="small" clearable placeholder="灰度模式" style="width: 140px" @change="onFilterChange">
          <el-option label="SHADOW" value="SHADOW" />
          <el-option label="L2_ASSISTED" value="L2_ASSISTED" />
          <el-option label="L1_AUTO" value="L1_AUTO" />
        </el-select>
        <el-button size="small" @click="onFilterChange">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" size="small" border>
        <el-table-column label="Trace" min-width="190">
          <template #default="{ row }">
            <div class="mono">{{ row.traceId }}</div>
            <div class="muted">{{ row.instanceId || '未绑定实例' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="runMode" label="模式" width="120" />
        <el-table-column label="回检结论" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="metaOf(row.verifyStatus).type">{{ metaOf(row.verifyStatus).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Arbiter" width="150">
          <template #default="{ row }">
            <span class="mono">{{ row.arbiterDecision?.status || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="审批提案" min-width="170">
          <template #default="{ row }">
            <span class="mono">{{ row.proposalId || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row as DecisionTrace)">时间线</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          small
          @current-change="load"
        />
      </div>
    </el-card>

    <el-drawer v-model="detailVisible" :title="`决策时间线：${detail?.traceId || ''}`" size="760px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="实例">{{ detail.instanceId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="模式">{{ detail.runMode }}</el-descriptions-item>
          <el-descriptions-item label="回检">
            <el-tag size="small" :type="metaOf(detail.verifyStatus).type">{{ metaOf(detail.verifyStatus).label }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="审批提案">{{ detail.proposalId || '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="actions">
          <el-button v-if="detail.instanceId" type="primary" plain @click="verifyVisible = true">
            提交 T+1 回检数据
          </el-button>
        </div>

        <el-card v-if="shadowObjectives.length" shadow="never" style="margin-top: var(--dg-space-lg)">
          <template #header><span class="card-title">影子回测对比（Target.objectives）</span></template>
          <el-table :data="shadowObjectives" size="small" border>
            <el-table-column prop="metricRef" label="指标" min-width="180" />
            <el-table-column prop="direction" label="方向" width="110" />
            <el-table-column prop="before" label="执行前" width="110" />
            <el-table-column prop="after" label="回检值" width="110" />
            <el-table-column label="结论" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="metaOf(String(row.status)).type">
                  {{ metaOf(String(row.status)).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="detail" label="说明" min-width="220" />
          </el-table>
        </el-card>

        <el-timeline style="margin-top: var(--dg-space-lg)">
          <el-timeline-item
            v-for="item in timeline"
            :key="item.title"
            :timestamp="item.title"
            placement="top"
          >
            <pre class="json">{{ JSON.stringify(item.value, null, 2) }}</pre>
          </el-timeline-item>
        </el-timeline>

        <el-collapse>
          <el-collapse-item title="可复现指纹（C8）" name="fingerprint">
            <pre class="json">{{ JSON.stringify(detail.reproducibilityFingerprint, null, 2) }}</pre>
          </el-collapse-item>
        </el-collapse>
      </template>
    </el-drawer>

    <el-dialog v-model="verifyVisible" title="提交 T+1 回检观测" width="560px">
      <el-alert
        type="info"
        :closable="false"
        title="只提交 Target.objectives 声明的聚合指标；缺少指标会明确落为 INCONCLUSIVE。"
        show-icon
      />
      <el-input v-model="verifyText" type="textarea" :rows="10" class="mono" style="margin-top: 12px" />
      <template #footer>
        <el-button @click="verifyVisible = false">取消</el-button>
        <el-button type="primary" :loading="verifyBusy" @click="submitVerify">提交并回检</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-container { padding: var(--dg-space-lg); }
.filter-row { display: flex; gap: var(--dg-space-sm); flex-wrap: wrap; margin-bottom: var(--dg-space-md); }
.mono { font-family: var(--dg-font-mono); }
.muted { color: var(--dg-text-muted); font-size: 12px; }
.pager { display: flex; justify-content: flex-end; margin-top: var(--dg-space-md); }
.actions { display: flex; gap: var(--dg-space-sm); margin-top: var(--dg-space-md); }
.card-title { font-weight: 600; color: var(--dg-text); }
.json { margin: 0; padding: 10px; background: var(--dg-surface-muted); border-radius: 6px; font-size: 12px; max-height: 260px; overflow: auto; white-space: pre-wrap; word-break: break-all; }
</style>
