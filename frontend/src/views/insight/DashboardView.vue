<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { insightApi } from '@/api/insight'
import { confirmDanger } from '@/utils/confirm'
import { useAuthStore } from '@/stores/auth'
import InsightAnswerChart, {
  type ChartBreadcrumb,
  type ChartDrilldownEvent,
  type ChartFilterSelection,
} from '@/components/insight/InsightAnswerChart.vue'
import type {
  InsightAclCandidate,
  InsightAnswer,
  InsightChartSpec,
  InsightChartType,
  InsightDashboard,
  InsightDashboardAcl,
  InsightDashboardDetail,
  InsightDashboardRun,
  InsightDashboardRunItem,
  InsightDashboardVersion,
} from '@/types/insight'
import { formatDateTime } from '@/utils/format'

const auth = useAuthStore()
const router = useRouter()
const dashboards = ref<InsightDashboard[]>([])
const detail = ref<InsightDashboardDetail | null>(null)
const loading = ref(false)
const selectedId = ref('')
const dialogVisible = ref(false)
const draft = ref({ name: '', description: '' })
const running = ref(false)
const runResult = ref<InsightDashboardRun | null>(null)
const versionDialog = ref(false)
const versions = ref<InsightDashboardVersion[]>([])
const autoRefresh = ref(false)
const aclDrawer = ref(false)
const acl = ref<InsightDashboardAcl[]>([])
const aclCandidates = ref<InsightAclCandidate[]>([])
const aclLoading = ref(false)
const aclCandidateLoading = ref(false)
const aclCandidateError = ref('')
const aclSubmitting = ref(false)
const aclForm = ref<{ username: string; permission: 'READ' | 'MANAGE' }>({
  username: '',
  permission: 'READ',
})
const reordering = ref(false)
const itemFilters = ref<Record<string, ChartFilterSelection | null>>({})
const itemDrillStacks = ref<Record<string, Array<{ label: string; answer: InsightAnswer }>>>({})
const itemDrilling = ref<Record<string, boolean>>({})
const itemActiveAnswers = ref<Record<string, InsightAnswer>>({})
let refreshTimer: number | undefined
let candidateTimer: ReturnType<typeof setTimeout> | null = null
let candidateSequence = 0

const VALID_CHART_TYPES = new Set<InsightChartType>([
  'kpi',
  'bar',
  'horizontal_bar',
  'stacked_bar',
  'line',
  'area',
  'pie',
  'scatter',
  'funnel',
  'table',
])

function resetItemChartStates() {
  itemFilters.value = {}
  itemDrillStacks.value = {}
  itemDrilling.value = {}
  itemActiveAnswers.value = {}
}

function parseItemChartSpec(itemId: string): Partial<InsightChartSpec> | null {
  const item = detail.value?.items.find((entry) => entry.id === itemId)
  if (!item) {
    return null
  }
  if (item.layoutJson) {
    try {
      const parsed = JSON.parse(item.layoutJson) as { chartSpec?: Partial<InsightChartSpec> | null }
      if (parsed && typeof parsed === 'object' && parsed.chartSpec) {
        return parsed.chartSpec
      }
    } catch {
      // ignore malformed layoutJson
    }
  }
  const normalized = (item.chartType || '').toLowerCase() as InsightChartType
  if (VALID_CHART_TYPES.has(normalized)) {
    return { chartType: normalized }
  }
  return null
}

function activeAnswerOf(runItem: InsightDashboardRunItem): InsightAnswer | undefined {
  return itemActiveAnswers.value[runItem.itemId] ?? runItem.answer
}

function itemBreadcrumbsOf(itemId: string): ChartBreadcrumb[] {
  const stack = itemDrillStacks.value[itemId]
  if (!stack || stack.length <= 1) {
    return []
  }
  return stack.map((entry) => ({ label: entry.label }))
}

function filteredItemRows(runItem: InsightDashboardRunItem): Record<string, unknown>[] {
  const rows = activeAnswerOf(runItem)?.rows ?? []
  const filter = itemFilters.value[runItem.itemId]
  if (!filter) {
    return rows
  }
  return rows.filter((row) => String(row[filter.field] ?? '') === filter.value)
}

function onItemFilterSelect(itemId: string, selection: ChartFilterSelection | null) {
  itemFilters.value[itemId] = selection
}

function clearItemFilter(itemId: string) {
  itemFilters.value[itemId] = null
}

async function onItemDrilldown(runItem: InsightDashboardRunItem, event: ChartDrilldownEvent) {
  const currentAnswer = activeAnswerOf(runItem)
  if (!currentAnswer || itemDrilling.value[runItem.itemId]) {
    return
  }
  if (!itemDrillStacks.value[runItem.itemId]?.length) {
    itemDrillStacks.value[runItem.itemId] = [{ label: '看板基线', answer: currentAnswer }]
  }
  const itemMeta = detail.value?.items.find((entry) => entry.id === runItem.itemId)
  itemDrilling.value[runItem.itemId] = true
  try {
    const drilled = await insightApi.ask({
      question: event.label,
      dataSetId: itemMeta?.datasetId,
      semanticSpec: event.spec,
    })
    itemDrillStacks.value[runItem.itemId] = [
      ...itemDrillStacks.value[runItem.itemId],
      { label: event.label, answer: drilled },
    ]
    itemActiveAnswers.value[runItem.itemId] = drilled
    itemFilters.value[runItem.itemId] = null
  } catch (drillError: any) {
    ElMessage.error(drillError?.message || '下钻查询失败')
  } finally {
    itemDrilling.value[runItem.itemId] = false
  }
}

function onItemBreadcrumbBack(itemId: string, index: number) {
  const stack = itemDrillStacks.value[itemId]
  if (!stack || index < 0 || index >= stack.length) {
    return
  }
  const sliced = stack.slice(0, index + 1)
  itemFilters.value[itemId] = null
  if (sliced.length <= 1) {
    delete itemDrillStacks.value[itemId]
    delete itemActiveAnswers.value[itemId]
  } else {
    itemDrillStacks.value[itemId] = sliced
    itemActiveAnswers.value[itemId] = sliced[sliced.length - 1].answer
  }
}

function onItemAskFollowup(prompt: string) {
  void router.push({ path: '/insight/ask', query: { prompt } })
}

const canManage = computed(() => auth.hasPermission('insight:dashboard:manage'))
const canAsk = computed(() => auth.hasPermission('insight:ask'))

async function loadDashboards() {
  loading.value = true
  try {
    dashboards.value = await insightApi.dashboards()
    if (!dashboards.value.some((item) => item.id === selectedId.value)) {
      selectedId.value = dashboards.value[0]?.id || ''
    }
    if (selectedId.value) {
      await loadDetail(selectedId.value)
    } else {
      detail.value = null
    }
  } finally {
    loading.value = false
  }
}

async function loadDetail(id: string) {
  selectedId.value = id
  runResult.value = null
  resetItemChartStates()
  detail.value = await insightApi.dashboard(id)
}

async function createDashboard() {
  if (!canManage.value) {
    return
  }
  if (!draft.value.name.trim()) {
    ElMessage.warning('请输入看板名称')
    return
  }
  const created = await insightApi.createDashboard({
    name: draft.value.name.trim(),
    description: draft.value.description.trim() || undefined,
  })
  dialogVisible.value = false
  draft.value = { name: '', description: '' }
  await loadDashboards()
  await loadDetail(created.id)
}

async function runDashboard(silent = false) {
  if (!detail.value || running.value || !canAsk.value) {
    return
  }
  running.value = true
  try {
    runResult.value = await insightApi.runDashboard(detail.value.id)
    resetItemChartStates()
    if (!silent) {
      ElMessage.success('看板已刷新')
    }
  } finally {
    running.value = false
  }
}

async function openVersions() {
  if (!detail.value) {
    return
  }
  versions.value = await insightApi.dashboardVersions(detail.value.id)
  versionDialog.value = true
}

async function openAcl() {
  if (!detail.value) {
    return
  }
  aclForm.value = { username: '', permission: 'READ' }
  aclDrawer.value = true
  aclCandidateError.value = ''
  await loadAcl()
  if (canManage.value) {
    await loadAclCandidates('')
  }
}

async function loadAclCandidates(keyword: string) {
  if (!detail.value || !canManage.value) {
    return
  }
  const sequence = ++candidateSequence
  aclCandidateLoading.value = true
  aclCandidateError.value = ''
  try {
    const candidates = await insightApi.dashboardAclCandidates(detail.value.id, keyword.trim() || undefined)
    if (sequence === candidateSequence) {
      aclCandidates.value = candidates
    }
  } catch {
    if (sequence === candidateSequence) {
      aclCandidates.value = []
      aclCandidateError.value = '候选用户加载失败'
    }
  } finally {
    if (sequence === candidateSequence) {
      aclCandidateLoading.value = false
    }
  }
}

function onCandidateSearch(keyword: string) {
  if (candidateTimer) {
    clearTimeout(candidateTimer)
  }
  candidateTimer = setTimeout(() => {
    void loadAclCandidates(keyword)
  }, 300)
}

async function loadAcl() {
  if (!detail.value) {
    return
  }
  aclLoading.value = true
  try {
    acl.value = await insightApi.dashboardAcl(detail.value.id)
  } catch {
    acl.value = []
    ElMessage.error('看板权限加载失败')
  } finally {
    aclLoading.value = false
  }
}

async function addAcl() {
  if (!detail.value || !canManage.value) {
    return
  }
  const username = aclForm.value.username.trim()
  if (!username) {
    ElMessage.warning('请输入用户名')
    return
  }
  aclSubmitting.value = true
  try {
    await insightApi.addDashboardAcl(detail.value.id, {
      username,
      permission: aclForm.value.permission,
    })
    ElMessage.success('看板权限已保存')
    aclForm.value.username = ''
    await loadAcl()
    await loadAclCandidates('')
    await loadDashboards()
  } catch {
    ElMessage.error('保存看板权限失败')
  } finally {
    aclSubmitting.value = false
  }
}

async function removeAcl(username: string) {
  if (!detail.value || !canManage.value) {
    return
  }
  const confirmed = await confirmDanger(`移除 ${username} 的看板权限？`, '移除权限', '移除')
  if (!confirmed) {
    return
  }
  try {
    await insightApi.removeDashboardAcl(detail.value.id, username)
    ElMessage.success('看板权限已移除')
    await loadAcl()
    await loadAclCandidates('')
    await loadDashboards()
  } catch {
    ElMessage.error('移除看板权限失败')
  }
}

async function moveItem(index: number, delta: -1 | 1) {
  if (!detail.value || !canManage.value || reordering.value) {
    return
  }
  const items = detail.value.items
  const target = index + delta
  if (target < 0 || target >= items.length) {
    return
  }
  const current = items[index]
  const neighbour = items[target]
  let currentOrder = current.sortOrder ?? index
  let neighbourOrder = neighbour.sortOrder ?? target
  if (currentOrder === neighbourOrder) {
    currentOrder = index
    neighbourOrder = target
  }

  reordering.value = true
  try {
    await insightApi.updateDashboardItem(detail.value.id, current.id, {
      sortOrder: neighbourOrder,
    })
    await insightApi.updateDashboardItem(detail.value.id, neighbour.id, {
      sortOrder: currentOrder,
    })
    runResult.value = null
    await loadDashboards()
    ElMessage.success('问题顺序已更新')
  } catch {
    ElMessage.error('问题顺序更新失败')
  } finally {
    reordering.value = false
  }
}

function answerColumns(answer?: InsightAnswer) {
  if (!answer) {
    return []
  }
  if (answer.columns?.length) {
    return answer.columns.map((column) => ({
      key: column.bizName || column.name,
      label: column.name,
    }))
  }
  const first = answer.rows?.[0]
  return first ? Object.keys(first).map((key) => ({ key, label: key })) : []
}

async function removeDashboard(id: string) {
  if (!canManage.value) {
    return
  }
  const confirmed = await confirmDanger('删除看板会同时删除其问题项，确定吗？', '删除看板')
  if (!confirmed) {
    return
  }
  await insightApi.deleteDashboard(id)
  if (selectedId.value === id) {
    selectedId.value = ''
    detail.value = null
  }
  await loadDashboards()
}

async function removeItem(itemId: string) {
  if (!detail.value || !canManage.value) {
    return
  }
  await insightApi.deleteDashboardItem(detail.value.id, itemId)
  await loadDetail(detail.value.id)
}

watch(autoRefresh, (enabled) => {
  if (refreshTimer) {
    window.clearInterval(refreshTimer)
    refreshTimer = undefined
  }
  if (enabled) {
    runDashboard(true)
    refreshTimer = window.setInterval(() => runDashboard(true), 60_000)
  }
})

onMounted(loadDashboards)

onBeforeUnmount(() => {
  if (refreshTimer) {
    window.clearInterval(refreshTimer)
  }
  if (candidateTimer) {
    clearTimeout(candidateTimer)
  }
})
</script>

<template>
  <div class="page-container dashboard">
    <el-card shadow="never">
      <template #header>
        <div class="dashboard__header">
          <span>问数看板</span>
          <el-button v-if="canManage" type="primary" @click="dialogVisible = true">
            新建看板
          </el-button>
        </div>
      </template>

      <el-row :gutter="16">
        <el-col :span="8">
          <el-table
            :data="dashboards"
            size="small"
            v-loading="loading"
            highlight-current-row
            @row-click="(row: InsightDashboard) => loadDetail(row.id)"
          >
            <el-table-column prop="name" label="名称" min-width="140" />
            <el-table-column prop="itemCount" label="问题数" width="80" />
            <el-table-column label="更新时间" width="160">
              <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
            </el-table-column>
            <el-table-column v-if="canManage" width="70">
              <template #default="{ row }">
                <el-button link type="danger" @click.stop="removeDashboard(row.id)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!loading && !dashboards.length" description="还没有看板" />
        </el-col>

        <el-col :span="16">
          <template v-if="detail">
            <div class="dashboard__title">
              <div>
                <h3>{{ detail.name }}</h3>
                <div class="text-muted">{{ detail.description || '暂无描述' }}</div>
                <div class="text-muted dashboard__creator">
                  创建人：{{ detail.createdBy || '-' }}
                </div>
              </div>
              <div class="dashboard__actions">
                <el-tag size="small">v{{ detail.version }}</el-tag>
                <span class="text-muted">{{ detail.items.length }} 个问题</span>
                <el-button size="small" @click="openAcl">权限</el-button>
                <el-button size="small" @click="openVersions">版本</el-button>
                <el-switch
                  v-if="canAsk"
                  v-model="autoRefresh"
                  active-text="自动刷新 60s"
                />
                <el-button
                  v-if="canAsk"
                  size="small"
                  type="primary"
                  :loading="running"
                  :disabled="!detail.items.length"
                  @click="runDashboard()"
                >
                  刷新看板
                </el-button>
              </div>
            </div>

            <el-table :data="detail.items" size="small">
              <el-table-column label="#" width="50">
                <template #default="{ $index }">{{ $index + 1 }}</template>
              </el-table-column>
              <el-table-column prop="question" label="问题" min-width="240" show-overflow-tooltip />
              <el-table-column prop="datasetId" label="数据集 ID" width="100" />
              <el-table-column prop="chartType" label="图表" width="90" />
              <el-table-column v-if="canManage" label="排序" width="140">
                <template #default="{ $index }">
                  <el-button
                    link
                    type="primary"
                    :disabled="$index === 0 || reordering"
                    @click="moveItem($index, -1)"
                  >
                    上移
                  </el-button>
                  <el-button
                    link
                    type="primary"
                    :disabled="$index === detail.items.length - 1 || reordering"
                    @click="moveItem($index, 1)"
                  >
                    下移
                  </el-button>
                </template>
              </el-table-column>
              <el-table-column v-if="canManage" width="80">
                <template #default="{ row }">
                  <el-button link type="danger" @click="removeItem(row.id)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!detail.items.length" description="去智能问数页把问题保存到看板" />

            <template v-if="runResult">
              <el-divider>刷新于 {{ formatDateTime(runResult.refreshedAt) }}</el-divider>
              <el-card
                v-for="item in runResult.items"
                :key="item.itemId"
                shadow="never"
                class="dashboard__result"
              >
                <template #header>
                  <div class="dashboard__result-header">
                    <span>{{ item.question }}</span>
                    <el-tag :type="item.succeeded ? 'success' : 'danger'" size="small">
                      {{ item.succeeded ? 'SUCCESS' : 'FAILED' }}
                    </el-tag>
                  </div>
                </template>
                <el-alert
                  v-if="!item.succeeded"
                  type="error"
                  show-icon
                  :closable="false"
                  :title="item.error || '执行失败'"
                />
                <template v-else-if="activeAnswerOf(item)">
                  <div v-if="activeAnswerOf(item)?.summary" class="dashboard__summary">
                    {{ activeAnswerOf(item)?.summary }}
                  </div>
                  <InsightAnswerChart
                    :answer="activeAnswerOf(item)!"
                    :initial-chart-spec="parseItemChartSpec(item.itemId)"
                    :breadcrumbs="itemBreadcrumbsOf(item.itemId)"
                    :drilling="Boolean(itemDrilling[item.itemId])"
                    @filter-select="(sel) => onItemFilterSelect(item.itemId, sel)"
                    @drilldown="(ev) => onItemDrilldown(item, ev)"
                    @breadcrumb-back="(idx) => onItemBreadcrumbBack(item.itemId, idx)"
                    @ask-followup="onItemAskFollowup"
                  />
                  <div v-if="itemFilters[item.itemId]" class="dashboard__filter-bar">
                    <el-tag size="small" type="primary">
                      图表联动筛选：{{ itemFilters[item.itemId]!.fieldLabel }} = {{ itemFilters[item.itemId]!.value }}
                    </el-tag>
                    <span class="text-muted">
                      当前显示 {{ filteredItemRows(item).length }} / {{ activeAnswerOf(item)!.rows.length }} 行
                    </span>
                    <el-button link size="small" type="primary" @click="clearItemFilter(item.itemId)">
                      清除筛选
                    </el-button>
                  </div>
                  <el-table :data="filteredItemRows(item)" size="small" max-height="260">
                    <el-table-column
                      v-for="column in answerColumns(activeAnswerOf(item))"
                      :key="column.key"
                      :prop="column.key"
                      :label="column.label"
                      min-width="120"
                    />
                  </el-table>
                </template>
              </el-card>
            </template>
          </template>
          <el-empty v-else description="选择左侧看板查看详情" />
        </el-col>
      </el-row>
    </el-card>

    <el-drawer v-model="aclDrawer" title="看板权限" size="500px">
      <el-alert
        type="info"
        show-icon
        :closable="false"
        class="acl-alert"
        title="Space 权限决定能否使用看板；ACL 只决定能看哪一张看板。"
      />

      <el-form v-if="canManage" label-position="top" class="acl-form">
        <el-form-item label="空间成员">
          <el-select
            v-model="aclForm.username"
            filterable
            remote
            clearable
            reserve-keyword
            :remote-method="onCandidateSearch"
            :loading="aclCandidateLoading"
            placeholder="搜索用户名或显示名"
            style="width: 100%"
          >
            <el-option
              v-for="candidate in aclCandidates"
              :key="candidate.username"
              :label="candidate.displayName || candidate.username"
              :value="candidate.username"
            >
              <div class="acl-candidate">
                <span>{{ candidate.displayName || candidate.username }}</span>
                <span class="acl-candidate__meta">@{{ candidate.username }} · {{ candidate.status }}</span>
              </div>
            </el-option>
          </el-select>
          <div v-if="aclCandidateError" class="acl-candidate__error">{{ aclCandidateError }}</div>
          <div v-else class="form-hint">仅显示当前空间有效成员，已授权用户不会重复出现。</div>
        </el-form-item>
        <el-form-item label="权限">
          <el-select v-model="aclForm.permission" style="width: 180px">
            <el-option label="READ · 只读" value="READ" />
            <el-option label="MANAGE · 可管理" value="MANAGE" />
          </el-select>
        </el-form-item>
        <el-button type="primary" :loading="aclSubmitting" @click="addAcl">
          添加授权
        </el-button>
      </el-form>

      <el-table v-loading="aclLoading" :data="acl" size="small" border class="acl-table">
        <el-table-column prop="username" label="用户" min-width="140" />
        <el-table-column label="权限" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.permission === 'MANAGE' ? 'warning' : 'info'">
              {{ row.permission }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="canManage" label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeAcl(row.username)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!aclLoading && !acl.length" description="暂无单独授权" />
    </el-drawer>

    <el-dialog v-model="versionDialog" title="看板版本" width="520px">
      <el-alert
        type="info"
        show-icon
        :closable="false"
        class="version-alert"
        title="版本快照保存问题项顺序与布局；P0 只读，不支持回滚。"
      />
      <el-table :data="versions" size="small">
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column prop="itemCount" label="问题数" width="90" />
        <el-table-column prop="createdBy" label="创建人" width="100" />
        <el-table-column label="时间">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="新建看板" width="480px">
      <el-form label-position="top">
        <el-form-item label="名称">
          <el-input v-model="draft.name" maxlength="128" placeholder="例如：销售日看板" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="draft.description" type="textarea" :rows="2" maxlength="512" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="createDashboard">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.dashboard__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dashboard__title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 12px;
  gap: 12px;
}

.dashboard__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.dashboard__result {
  margin-top: 12px;
}

.dashboard__result-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dashboard__summary {
  margin-bottom: 8px;
  line-height: 1.6;
}

.dashboard__filter-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--dg-space-sm);
  margin: var(--dg-space-sm) 0;
  padding: 6px var(--dg-space-sm);
  background: var(--dg-surface-muted);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
  font-size: 12px;
}

.dashboard__title h3 {
  margin: 0 0 4px;
}

.dashboard__creator {
  margin-top: 4px;
  font-size: 12px;
}

.acl-alert,
.version-alert {
  margin-bottom: 12px;
}

.acl-form {
  padding-bottom: 12px;
  border-bottom: 1px solid var(--dg-border);
  margin-bottom: 12px;
}

.acl-table {
  margin-top: 4px;
}

.acl-candidate {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}

.acl-candidate__meta {
  font-size: 11px;
  color: var(--dg-text-muted);
}

.acl-candidate__error {
  margin-top: 4px;
  font-size: 12px;
  color: var(--dg-danger);
}

.form-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--dg-text-muted);
  line-height: 1.6;
}
</style>
