<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import AgentConsentDialog from '@/components/agent/AgentConsentDialog.vue'
import { autonomyApi } from '@/api/autonomy'
import { useAuthStore } from '@/stores/auth'
import { confirmDanger } from '@/utils/confirm'
import { formatDateTime } from '@/utils/format'
import type {
  AutonomyApproval,
  AutonomyRunMode,
  CompileDiagnostic,
  FlowDefinition,
  FlowVersion,
  FireResult,
} from '@/types/autonomy'
import type { AgentActionProposal } from '@/types/agent'

defineOptions({ name: 'AutonomyFlowListView' })

const auth = useAuthStore()
const canManage = computed(() => auth.hasPermission('autonomy:flow:manage'))
const canPublish = computed(() => auth.hasPermission('autonomy:flow:publish'))
const canApprove = computed(() => auth.hasPermission('autonomy:flow:approve'))

const loading = ref(false)
const rows = ref<FlowDefinition[]>([])
const total = ref(0)
const query = reactive({
  page: 1,
  size: 20,
  keyword: '',
  status: '',
  runMode: '',
})

const EXAMPLE_SPEC = {
  schemaVersion: 'flow.ir/v1',
  flowCode: 'quality_anomaly_auto_guard',
  name: '质检异常自动守护',
  runMode: 'SHADOW',
  entityKeyExpr: 'trigger.datasetId',
  cooldownSec: 3600,
  arbiterPolicyRef: 'arbiter_default_governance',
  steps: [
    { id: 's1_snapshot', kind: 'CAPTURE_CONTEXT', metrics: ['quality_pass_rate', 'row_count_delta'] },
    { id: 's2_invariant', kind: 'CHECK_INVARIANT', targetRef: 'target_core_dataset_sla' },
    { id: 's3_dry_run', kind: 'DRY_RUN_ACTION', actionTypeRef: 'action_pause_sync_job' },
    { id: 's4_arbiter', kind: 'ARBITER_DECIDE', fallbackToL2: true },
    { id: 's5_approval', kind: 'WAIT_HUMAN_SIGNAL', timeoutSec: 14400, skipWhen: "runMode == 'SHADOW'" },
    { id: 's6_execute', kind: 'INVOKE_ACTIVITY', actionTypeRef: 'action_pause_sync_job', compensateWith: 'action_resume_sync_job' },
    { id: 's7_verify', kind: 'DELAYED_VERIFY', delaySec: 86400, verifyTargetRef: 'target_core_dataset_sla' },
  ],
}

const dialogVisible = ref(false)
const editingId = ref('')
const saving = ref(false)
const compiling = ref(false)
const form = reactive({
  code: '',
  name: '',
  description: '',
  runMode: 'SHADOW' as AutonomyRunMode,
  specText: JSON.stringify(EXAMPLE_SPEC, null, 2),
})
const diagnostics = ref<CompileDiagnostic[]>([])
const compileSummary = ref('')

const versionsVisible = ref(false)
const versions = ref<FlowVersion[]>([])
const versionFlow = ref<FlowDefinition | null>(null)

const promoteVisible = ref(false)
const promoteFlow = ref<FlowDefinition | null>(null)
const promoteForm = reactive({ targetMode: 'L2_ASSISTED', note: '' })

const approvals = ref<AutonomyApproval[]>([])
const selectedApproval = ref<AutonomyApproval | null>(null)

const dryRunVisible = ref(false)
const dryRunFlow = ref<FlowDefinition | null>(null)
const dryRunSnapshot = ref('{\n  "jobId": "job_demo",\n  "metrics": {\n    "badRowRate": 0.12\n  }\n}')
const dryRunResult = ref<FireResult | null>(null)
const dryRunBusy = ref(false)

async function load() {
  loading.value = true
  try {
    const page = await autonomyApi.listFlows({
      page: query.page,
      size: query.size,
      keyword: query.keyword.trim() || undefined,
      status: query.status || undefined,
      runMode: query.runMode || undefined,
    })
    rows.value = page.records
    total.value = page.total
    if (canApprove.value) {
      approvals.value = await autonomyApi.listApprovals()
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openCreate() {
  editingId.value = ''
  form.code = ''
  form.name = ''
  form.description = ''
  form.runMode = 'SHADOW'
  form.specText = JSON.stringify(EXAMPLE_SPEC, null, 2)
  diagnostics.value = []
  compileSummary.value = ''
  dialogVisible.value = true
}

function openEdit(row: FlowDefinition) {
  editingId.value = row.id
  form.code = row.code
  form.name = row.name
  form.description = row.description || ''
  form.runMode = row.runMode
  form.specText = JSON.stringify(row.spec || {}, null, 2)
  diagnostics.value = []
  compileSummary.value = ''
  dialogVisible.value = true
}

function parsedSpec(): Record<string, unknown> | null {
  try {
    return JSON.parse(form.specText || '{}')
  } catch {
    ElMessage.error('FlowSpec IR 不是合法 JSON')
    return null
  }
}

async function compileOnly() {
  const spec = parsedSpec()
  if (!spec) return
  compiling.value = true
  try {
    const result = await autonomyApi.compile(spec)
    diagnostics.value = result.diagnostics
    compileSummary.value = result.ok
      ? `编译通过：${(result.plan as any)?.steps?.length || 0} 个步骤，digest=${(result.plan as any)?.contentDigest || '-'}`
      : '编译失败，请按诊断修正'
    if (result.ok) {
      ElMessage.success(compileSummary.value)
    } else {
      ElMessage.error(compileSummary.value)
    }
  } finally {
    compiling.value = false
  }
}

async function save() {
  const spec = parsedSpec()
  if (!spec) return
  saving.value = true
  try {
    await autonomyApi.saveFlow({
      flowId: editingId.value || undefined,
      code: form.code.trim(),
      name: form.name.trim(),
      description: form.description.trim(),
      runMode: form.runMode,
      spec,
    })
    ElMessage.success('已保存为新版本；新流程/变更默认以 SHADOW 启动')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function publish(row: FlowDefinition) {
  await autonomyApi.publishFlow(row.id)
  ElMessage.success('流程已发布，可被触发器绑定')
  await load()
}

async function deprecate(row: FlowDefinition) {
  if (!(await confirmDanger(`废弃「${row.name}」后触发器不能再绑定。确定吗？`, '废弃流程'))) return
  await autonomyApi.deprecateFlow(row.id)
  ElMessage.success('已废弃')
  await load()
}

async function openVersions(row: FlowDefinition) {
  versionFlow.value = row
  versions.value = await autonomyApi.listVersions(row.id)
  versionsVisible.value = true
}

function openPromote(row: FlowDefinition) {
  promoteFlow.value = row
  promoteForm.targetMode = row.runMode === 'SHADOW' ? 'L2_ASSISTED' : 'L1_AUTO'
  promoteForm.note = ''
  promoteVisible.value = true
}

async function doPromote() {
  if (!promoteFlow.value) return
  await autonomyApi.promote(promoteFlow.value.id, promoteForm.targetMode, promoteForm.note)
  ElMessage.success(`已晋升到 ${promoteForm.targetMode}`)
  promoteVisible.value = false
  await load()
}

function openDryRun(row: FlowDefinition) {
  dryRunFlow.value = row
  dryRunResult.value = null
  dryRunVisible.value = true
}

async function doDryRun() {
  if (!dryRunFlow.value) return
  let snapshot: Record<string, unknown>
  try {
    snapshot = JSON.parse(dryRunSnapshot.value || '{}')
  } catch {
    ElMessage.error('试跑快照不是合法 JSON')
    return
  }
  dryRunBusy.value = true
  try {
    dryRunResult.value = await autonomyApi.dryRun(dryRunFlow.value.id, snapshot)
    ElMessage.success('已以 SHADOW 启动试跑实例')
  } finally {
    dryRunBusy.value = false
  }
}

async function killSwitch() {
  if (!(await confirmDanger('急停会把所有流程降级回 SHADOW 并取消在途实例。确定吗？', '数据自治急停', '执行急停'))) return
  const result = await autonomyApi.killSwitch('控制台手动急停')
  ElMessage.success(`已降级 ${result.demotedFlows} 个流程，取消 ${result.cancelledInstances} 个实例`)
  await load()
}

function openApproval(row: AutonomyApproval) {
  selectedApproval.value = row
}

function onApprovalResolved() {
  selectedApproval.value = null
  void load()
}

function onFilterChange() {
  query.page = 1
  void load()
}

function runModeType(mode: string): 'info' | 'warning' | 'danger' {
  return mode === 'L1_AUTO' ? 'danger' : mode === 'L2_ASSISTED' ? 'warning' : 'info'
}
</script>

<template>
  <div class="page-container">
    <PageHeader
      title="自治流程"
      description="FlowSpec IR 编排与灰度控制台：SHADOW → L2_ASSISTED → L1_AUTO，审批复用宿主确认模型。"
    >
      <template #actions>
        <el-button v-if="canManage" type="primary" @click="openCreate">新建流程</el-button>
        <el-button v-if="canPublish" type="danger" plain @click="killSwitch">急停 / 全部降级</el-button>
        <el-button @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <el-card shadow="never" style="margin-bottom: var(--dg-space-lg)">
      <div class="filter-row">
        <el-input
          v-model="query.keyword"
          size="small"
          clearable
          placeholder="搜索编码或名称"
          style="width: 220px"
          @keyup.enter="onFilterChange"
        />
        <el-select v-model="query.status" size="small" clearable placeholder="发布状态" style="width: 130px" @change="onFilterChange">
          <el-option label="DRAFT" value="DRAFT" />
          <el-option label="PUBLISHED" value="PUBLISHED" />
          <el-option label="DEPRECATED" value="DEPRECATED" />
        </el-select>
        <el-select v-model="query.runMode" size="small" clearable placeholder="灰度模式" style="width: 140px" @change="onFilterChange">
          <el-option label="SHADOW" value="SHADOW" />
          <el-option label="L2_ASSISTED" value="L2_ASSISTED" />
          <el-option label="L1_AUTO" value="L1_AUTO" />
        </el-select>
        <el-button size="small" @click="onFilterChange">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" size="small" border>
        <el-table-column label="流程" min-width="210">
          <template #default="{ row }">
            <div class="strong">{{ row.name }}</div>
            <div class="muted mono">{{ row.code }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'PUBLISHED' ? 'success' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="灰度模式" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="runModeType(row.runMode)">{{ row.runMode }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="版本 / 摘要" min-width="200">
          <template #default="{ row }">
            <div>v{{ row.currentVersion }}</div>
            <div class="muted mono digest">{{ row.contentDigest || '-' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">{{ row.updatedAt ? formatDateTime(row.updatedAt) : '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="270" align="right">
          <template #default="{ row }">
            <el-button v-if="canManage" link type="primary" size="small" @click="openEdit(row as FlowDefinition)">编辑</el-button>
            <el-button v-if="canPublish && row.status !== 'PUBLISHED'" link type="success" size="small" @click="publish(row as FlowDefinition)">发布</el-button>
            <el-button v-if="canManage && row.status === 'PUBLISHED'" link type="primary" size="small" @click="openDryRun(row as FlowDefinition)">试跑</el-button>
            <el-button link type="primary" size="small" @click="openVersions(row as FlowDefinition)">版本</el-button>
            <el-button v-if="canPublish && row.status === 'PUBLISHED' && row.runMode !== 'L1_AUTO'" link type="warning" size="small" @click="openPromote(row as FlowDefinition)">晋升</el-button>
            <el-button v-if="canPublish && row.status === 'PUBLISHED'" link type="danger" size="small" @click="deprecate(row as FlowDefinition)">废弃</el-button>
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

    <el-card v-if="canApprove" shadow="never">
      <template #header>
        <div class="card-title">待审批 L2 自治动作（{{ approvals.length }}）</div>
      </template>
      <el-empty v-if="!approvals.length" description="当前没有待审批的自治动作" />
      <el-table v-else :data="approvals" size="small" border>
        <el-table-column prop="title" label="提案" min-width="220" />
        <el-table-column prop="summary" label="摘要" min-width="300" />
        <el-table-column label="风险" width="90">
          <template #default="{ row }">
            <el-tag size="small" type="danger">{{ row.riskLevel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="过期时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.expiresAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" align="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openApproval(row as AutonomyApproval)">打开宿主确认</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑 FlowSpec' : '新建 FlowSpec'" width="820px" top="6vh">
      <el-form label-width="110px">
        <el-form-item label="编码" required>
          <el-input v-model="form.code" placeholder="如 quality_anomaly_auto_guard" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" />
        </el-form-item>
        <el-form-item label="启动模式">
          <el-select v-model="form.runMode" style="width: 100%">
            <el-option label="SHADOW（默认，零副作用）" value="SHADOW" />
            <el-option label="L2_ASSISTED（需审批）" value="L2_ASSISTED" />
            <el-option label="L1_AUTO（自动执行）" value="L1_AUTO" />
          </el-select>
          <div class="muted hint">新建或参数变更后强制从 SHADOW 启动，晋升请走受控端点。</div>
        </el-form-item>
        <el-form-item label="FlowSpec IR" required>
          <el-input v-model="form.specText" type="textarea" :rows="20" class="mono" />
        </el-form-item>
      </el-form>
      <el-alert v-if="compileSummary" :title="compileSummary" :type="diagnostics.length ? 'error' : 'success'" :closable="false" show-icon />
      <el-table v-if="diagnostics.length" :data="diagnostics" size="small" border style="margin-top: 8px">
        <el-table-column prop="code" label="诊断码" width="180" />
        <el-table-column prop="field" label="字段" width="220" />
        <el-table-column prop="message" label="说明" />
      </el-table>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button :loading="compiling" @click="compileOnly">静态编译</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存为新版本</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="versionsVisible" :title="`版本历史：${versionFlow?.name || ''}`" size="720px">
      <el-table :data="versions" size="small" border>
        <el-table-column prop="versionNo" label="版本" width="70" />
        <el-table-column label="模式" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="runModeType(row.runMode)">{{ row.runMode }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="promotionNote" label="晋升说明" min-width="180" />
        <el-table-column label="编译时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.compiledAt) }}</template>
        </el-table-column>
        <el-table-column label="摘要" min-width="150">
          <template #default="{ row }"><span class="mono digest">{{ row.contentDigest }}</span></template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog v-model="dryRunVisible" :title="`Flow Dry-Run：${dryRunFlow?.name || ''}`" width="640px">
      <el-alert
        type="info"
        :closable="false"
        title="试跑强制以 SHADOW 启动：完整执行触发快照 / 不变量 / Dry-Run / Arbiter，但绝不调用副作用 Activity。"
        show-icon
      />
      <el-input v-model="dryRunSnapshot" type="textarea" :rows="10" class="mono" style="margin-top: 12px" />
      <pre v-if="dryRunResult" class="result">{{ JSON.stringify(dryRunResult, null, 2) }}</pre>
      <template #footer>
        <el-button @click="dryRunVisible = false">关闭</el-button>
        <el-button type="primary" :loading="dryRunBusy" @click="doDryRun">启动 SHADOW 试跑</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="promoteVisible" title="灰度晋升" width="520px">
      <el-alert
        type="warning"
        :closable="false"
        title="晋升前必须已有对应的 DecisionTrace 回检证据；后端会校验，不满足会拒绝。"
        show-icon
      />
      <el-form label-width="90px" style="margin-top: 12px">
        <el-form-item label="目标模式">
          <el-select v-model="promoteForm.targetMode" style="width: 100%">
            <el-option label="L2_ASSISTED" value="L2_ASSISTED" />
            <el-option label="L1_AUTO" value="L1_AUTO" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="promoteForm.note" type="textarea" :rows="3" placeholder="记录回检证据与审批依据" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="promoteVisible = false">取消</el-button>
        <el-button type="primary" @click="doPromote">确认晋升</el-button>
      </template>
    </el-dialog>
    <AgentConsentDialog
      :proposal="(selectedApproval as unknown as AgentActionProposal)"
      mode="autonomy"
      @resolved="onApprovalResolved"
      @dismiss="selectedApproval = null"
    />
  </div>
</template>

<style scoped>
.page-container { padding: var(--dg-space-lg); }
.filter-row { display: flex; gap: var(--dg-space-sm); flex-wrap: wrap; margin-bottom: var(--dg-space-md); }
.strong { font-weight: 600; color: var(--dg-text); }
.muted { color: var(--dg-text-muted); font-size: 12px; }
.mono { font-family: var(--dg-font-mono); }
.digest { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pager { display: flex; justify-content: flex-end; margin-top: var(--dg-space-md); }
.hint { margin-top: 6px; }
.card-title { font-weight: 600; color: var(--dg-text); }
.result { margin-top: 12px; padding: 12px; background: var(--dg-surface-muted); border-radius: 6px; font-size: 12px; max-height: 220px; overflow: auto; }
</style>
