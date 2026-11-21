<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import FilterPresetPanel from '@/components/autonomy/FilterPresetPanel.vue'
import { autonomyApi } from '@/api/autonomy'
import { useAuthStore } from '@/stores/auth'
import { confirmDanger } from '@/utils/confirm'
import { formatDateTime } from '@/utils/format'
import type {
  AutonomySourceType,
  FlowDefinition,
  TriggerDefinition,
  TriggerPayload,
} from '@/types/autonomy'

defineOptions({ name: 'AutonomyTriggerListView' })

const auth = useAuthStore()
const canManage = computed(() => auth.hasPermission('autonomy:trigger:manage'))

const loading = ref(false)
const rows = ref<TriggerDefinition[]>([])
const flows = ref<FlowDefinition[]>([])
const total = ref(0)
const query = reactive({
  page: 1,
  size: 20,
  keyword: '',
  sourceType: '' as '' | AutonomySourceType,
  enabled: '' as '' | 'true' | 'false',
})

const dialogVisible = ref(false)
const editingId = ref('')
const saving = ref(false)
const form = reactive({
  code: '',
  name: '',
  sourceType: 'ETL_ANOMALY' as AutonomySourceType,
  flowId: '',
  debounceSec: 300,
  priority: 100,
  enabled: true,
  conditionText: '{\n  "badRowRateAtLeast": 0.05,\n  "statuses": ["FAILED_BAD_ROW_LIMIT"]\n}',
})

const testVisible = ref(false)
const testTarget = ref<TriggerDefinition | null>(null)
const testSnapshot = ref('{\n  "status": "FAILED_BAD_ROW_LIMIT",\n  "badRowRate": 0.12,\n  "metrics": { "badRowRate": 0.12 }\n}')
const testResult = ref('')
const fireBusy = ref(false)

const sourceOptions: Array<{ value: AutonomySourceType; label: string; hint: string }> = [
  { value: 'METRIC_THRESHOLD', label: '指标阈值', hint: '{"metricRef":"...","operator":"LT","threshold":0.99}' },
  { value: 'QUALITY_FAILURE', label: '质检失败', hint: '{"maxPassRate":0.95} 或 {"failed":true}' },
  { value: 'ETL_ANOMALY', label: 'ETL 异常', hint: '{"badRowRateAtLeast":0.05,"statuses":["FAILED_BAD_ROW_LIMIT"]}' },
  { value: 'DATA_ARRIVAL', label: '数据到达延迟', hint: '{"maxDelaySec":3600}' },
]

const conditionHint = computed(
  () => sourceOptions.find((item) => item.value === form.sourceType)?.hint || '{}',
)

async function load() {
  loading.value = true
  try {
    const [page, flowPage] = await Promise.all([
      autonomyApi.listTriggers({
        page: query.page,
        size: query.size,
        keyword: query.keyword.trim() || undefined,
        sourceType: query.sourceType || undefined,
        enabled: query.enabled === '' ? undefined : query.enabled === 'true',
      }),
      autonomyApi.listFlows({ page: 1, size: 200, status: 'PUBLISHED' }),
    ])
    rows.value = page.records
    total.value = page.total
    flows.value = flowPage.records
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openCreate() {
  editingId.value = ''
  form.code = ''
  form.name = ''
  form.sourceType = 'ETL_ANOMALY'
  form.flowId = flows.value[0]?.id || ''
  form.debounceSec = 300
  form.priority = 100
  form.enabled = true
  form.conditionText = '{\n  "badRowRateAtLeast": 0.05,\n  "statuses": ["FAILED_BAD_ROW_LIMIT"]\n}'
  dialogVisible.value = true
}

function openEdit(row: TriggerDefinition) {
  editingId.value = row.id
  form.code = row.code
  form.name = row.name
  form.sourceType = row.sourceType
  form.flowId = row.flowId
  form.debounceSec = row.debounceSec
  form.priority = row.priority
  form.enabled = row.enabled
  form.conditionText = JSON.stringify(row.condition || {}, null, 2)
  dialogVisible.value = true
}

async function save() {
  let condition: Record<string, unknown>
  try {
    condition = JSON.parse(form.conditionText || '{}')
  } catch {
    ElMessage.error('触发条件不是合法 JSON')
    return
  }
  const payload: TriggerPayload = {
    code: form.code.trim(),
    name: form.name.trim(),
    sourceType: form.sourceType,
    flowId: form.flowId,
    debounceSec: form.debounceSec,
    priority: form.priority,
    enabled: form.enabled,
    condition,
  }
  saving.value = true
  try {
    if (editingId.value) {
      await autonomyApi.updateTrigger(editingId.value, payload)
      ElMessage.success('触发器已更新')
    } else {
      await autonomyApi.createTrigger(payload)
      ElMessage.success('触发器已创建')
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: TriggerDefinition) {
  if (!(await confirmDanger(`删除触发器「${row.name}」后不再自动启动流程。确定吗？`, '删除触发器'))) {
    return
  }
  await autonomyApi.deleteTrigger(row.id)
  ElMessage.success('已删除')
  await load()
}

function openTest(row: TriggerDefinition) {
  testTarget.value = row
  testResult.value = ''
  testVisible.value = true
}

async function runTest(execute: boolean) {
  if (!testTarget.value) return
  let snapshot: Record<string, unknown>
  try {
    snapshot = JSON.parse(testSnapshot.value || '{}')
  } catch {
    ElMessage.error('测试快照不是合法 JSON')
    return
  }
  fireBusy.value = true
  try {
    if (execute) {
      const result = await autonomyApi.fireTrigger(testTarget.value.id, snapshot)
      testResult.value = JSON.stringify(result, null, 2)
      if (result.fired) {
        ElMessage.success(`已触发：${result.reason}`)
      } else {
        ElMessage.warning(`未触发：${result.reason}`)
      }
      await load()
    } else {
      const result = await autonomyApi.testTrigger(testTarget.value.id, snapshot)
      testResult.value = JSON.stringify(result, null, 2)
    }
  } finally {
    fireBusy.value = false
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
      title="自治触发器"
      description="指标阈值 / 质检失败 / ETL 异常 / 数据到达延迟 → 声明式启动 FlowSpec 控制环。"
    >
      <template #actions>
        <el-button v-if="canManage" type="primary" @click="openCreate">新建触发器</el-button>
        <el-button @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <el-card shadow="never">
      <div class="filter-row">
        <el-input
          v-model="query.keyword"
          size="small"
          clearable
          placeholder="搜索编码或名称"
          style="width: 220px"
          @keyup.enter="onFilterChange"
        />
        <el-select v-model="query.sourceType" size="small" clearable placeholder="触发源" style="width: 170px" @change="onFilterChange">
          <el-option v-for="item in sourceOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select v-model="query.enabled" size="small" clearable placeholder="启停状态" style="width: 120px" @change="onFilterChange">
          <el-option label="启用" value="true" />
          <el-option label="停用" value="false" />
        </el-select>
        <el-button size="small" @click="onFilterChange">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" size="small" border>
        <el-table-column label="触发器" min-width="200">
          <template #default="{ row }">
            <div class="strong">{{ row.name }}</div>
            <div class="muted mono">{{ row.code }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="sourceType" label="触发源" width="150" />
        <el-table-column label="绑定流程" min-width="170">
          <template #default="{ row }">
            <span class="mono">{{ row.flowCode || row.flowId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="条件" min-width="240">
          <template #default="{ row }">
            <code class="condition">{{ JSON.stringify(row.condition) }}</code>
          </template>
        </el-table-column>
        <el-table-column label="防抖 / 优先级" width="130">
          <template #default="{ row }">{{ row.debounceSec }}s / {{ row.priority }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enabled ? 'success' : 'info'">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近触发" width="160">
          <template #default="{ row }">{{ row.lastFiredAt ? formatDateTime(row.lastFiredAt) : '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="210" align="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openTest(row as TriggerDefinition)">试跑</el-button>
            <el-button v-if="canManage" link type="primary" size="small" @click="openEdit(row as TriggerDefinition)">编辑</el-button>
            <el-button v-if="canManage" link type="danger" size="small" @click="remove(row as TriggerDefinition)">删除</el-button>
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

    <FilterPresetPanel :can-manage="canManage" style="margin-top: var(--dg-space-lg)" />

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑触发器' : '新建触发器'" width="620px">
      <el-form label-width="110px">
        <el-form-item label="编码" required>
          <el-input v-model="form.code" placeholder="如 etl_bad_row_guard" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 ETL 脏数据率守卫" />
        </el-form-item>
        <el-form-item label="触发源" required>
          <el-select v-model="form.sourceType" style="width: 100%">
            <el-option v-for="item in sourceOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="绑定流程" required>
          <el-select v-model="form.flowId" filterable style="width: 100%" placeholder="仅可选择已发布流程">
            <el-option v-for="flow in flows" :key="flow.id" :label="`${flow.name} (${flow.runMode})`" :value="flow.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="触发条件" required>
          <el-input v-model="form.conditionText" type="textarea" :rows="7" class="mono" />
          <div class="muted hint">{{ conditionHint }}</div>
        </el-form-item>
        <el-form-item label="防抖窗口">
          <el-input-number v-model="form.debounceSec" :min="0" :max="86400" />
          <span class="muted hint-inline">秒。窗口内重复命中不会重复启动流程。</span>
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="0" :max="1000" />
          <span class="muted hint-inline">数值越小越优先。</span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="testVisible" :title="`试跑触发器：${testTarget?.name || ''}`" width="640px">
      <el-alert
        type="info"
        :closable="false"
        title="试跑只做确定性求值；点击「求值并启动」才会真正创建 Flow 实例。"
        show-icon
      />
      <el-input v-model="testSnapshot" type="textarea" :rows="10" class="mono" style="margin-top: 12px" />
      <pre v-if="testResult" class="result">{{ testResult }}</pre>
      <template #footer>
        <el-button @click="testVisible = false">关闭</el-button>
        <el-button :loading="fireBusy" @click="runTest(false)">仅求值</el-button>
        <el-button type="primary" :loading="fireBusy" @click="runTest(true)">求值并启动</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-container { padding: var(--dg-space-lg); }
.filter-row { display: flex; gap: var(--dg-space-sm); flex-wrap: wrap; margin-bottom: var(--dg-space-md); }
.strong { font-weight: 600; color: var(--dg-text); }
.muted { color: var(--dg-text-muted); font-size: 12px; }
.mono { font-family: var(--dg-font-mono); }
.condition { font-size: 11px; color: var(--dg-text-muted); word-break: break-all; }
.pager { display: flex; justify-content: flex-end; margin-top: var(--dg-space-md); }
.hint { margin-top: 6px; }
.hint-inline { margin-left: 8px; }
.result { margin-top: 12px; padding: 12px; background: var(--dg-surface-muted); border-radius: 6px; font-size: 12px; max-height: 260px; overflow: auto; }
</style>
