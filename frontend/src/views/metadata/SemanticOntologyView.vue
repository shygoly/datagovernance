<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { semanticApi } from '@/api/semantic'
import { useAuthStore } from '@/stores/auth'
import { confirmAction } from '@/utils/confirm'
import PageHeader from '@/components/common/PageHeader.vue'
import SemanticObjectDrawer from '@/components/semantic/SemanticObjectDrawer.vue'
import SemanticGraphPanel from '@/components/semantic/SemanticGraphPanel.vue'
import SemanticPlaygroundPanel from '@/components/semantic/SemanticPlaygroundPanel.vue'
import WarehouseCatalogPanel from '@/components/semantic/WarehouseCatalogPanel.vue'
import { formatDateTime } from '@/utils/format'
import type {
  SemanticObject,
  SemanticObjectType,
  SemanticProposalItem,
  SemanticSummaryStats,
} from '@/types/semantic'

defineOptions({ name: 'SemanticOntologyView' })

const route = useRoute()
const auth = useAuthStore()

const canManage = computed(() => auth.hasPermission('metadata:semantic:manage'))
const canPublish = computed(() => auth.hasPermission('metadata:semantic:publish'))

const activeTab = ref('catalog')
const loading = ref(false)
const stats = ref<SemanticSummaryStats>({
  publishedMetrics: 0,
  publishedObjects: 0,
  mappedDatasets: 0,
  totalLinks: 0,
  pendingProposals: 0,
})

const objects = ref<SemanticObject[]>([])
const allObjects = ref<SemanticObject[]>([])
const total = ref(0)
const proposals = ref<SemanticProposalItem[]>([])

const query = reactive({
  page: 1,
  size: 20,
  keyword: '',
  objectType: '' as SemanticObjectType | '',
  status: '',
  scope: '',
})

const drawerVisible = ref(false)
const editingObject = ref<SemanticObject | null>(null)

const typeGroups: Array<{ label: string; items: Array<{ value: SemanticObjectType; label: string }> }> = [
  {
    label: 'Layer 1 · 计算语义',
    items: [
      { value: 'ENTITY', label: '业务实体 (ENTITY)' },
      { value: 'ATTRIBUTE', label: '属性 (ATTRIBUTE)' },
      { value: 'DIMENSION', label: '维度 (DIMENSION)' },
      { value: 'FACT', label: '事实 (FACT)' },
      { value: 'METRIC', label: '标准指标 (METRIC)' },
    ],
  },
  {
    label: 'Layer 2 · 业务知识图谱',
    items: [
      { value: 'CONCEPT', label: '领域概念 (CONCEPT)' },
      { value: 'PROCESS', label: '业务流程 (PROCESS)' },
    ],
  },
]

async function loadAll() {
  loading.value = true
  try {
    const [sum, page, full, propsList] = await Promise.all([
      semanticApi.summary(),
      semanticApi.listObjects({
        page: query.page,
        size: query.size,
        keyword: query.keyword.trim() || undefined,
        objectType: query.objectType || undefined,
        status: query.status || undefined,
        scope: query.scope || undefined,
      }),
      semanticApi.listObjects({ page: 1, size: 200 }),
      semanticApi.listProposals('PROPOSED'),
    ])
    stats.value = sum
    objects.value = page.records
    total.value = page.total
    allObjects.value = full.records
    proposals.value = propsList
  } finally {
    loading.value = false
  }
}

function selectType(type: SemanticObjectType | '') {
  query.objectType = type
  query.page = 1
  void loadAll()
}

function openCreate() {
  editingObject.value = null
  drawerVisible.value = true
}

function openEdit(obj: SemanticObject) {
  editingObject.value = obj
  drawerVisible.value = true
}

async function openEditById(id: string) {
  const obj = await semanticApi.getObject(id)
  openEdit(obj)
}

async function publish(obj: SemanticObject) {
  await semanticApi.publishObject(obj.id)
  ElMessage.success(`已发布 ${obj.name}`)
  await loadAll()
}

async function deprecate(obj: SemanticObject) {
  await semanticApi.deprecateObject(obj.id)
  ElMessage.success(`已废弃 ${obj.name}`)
  await loadAll()
}

async function approveProposal(item: SemanticProposalItem) {
  const confirmed = await confirmAction(
    `确认将暂存提案「${item.title}」晋升至正式发布态（双时间态版本记录，可一键回滚）？`,
    '宿主独立审校确认（CONSTITUTION C7/C9）',
    { confirmButtonText: '确认批准发布', cancelButtonText: '取消' },
  )
  if (!confirmed) {
    return
  }
  await semanticApi.approveProposal(item.kind, item.id)
  ElMessage.success('提案已批准并晋升发布')
  await loadAll()
}

async function rejectProposal(item: SemanticProposalItem) {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(
      '请填写驳回理由（将写入防重提记录，抑制后续重复告警/提案）：',
      `驳回提案：${item.title}`,
      {
        confirmButtonText: '确认驳回',
        cancelButtonText: '取消',
        inputValidator: (v) => (v && v.trim().length > 0 ? true : '驳回理由必填'),
      },
    )
    reason = value.trim()
  } catch {
    return
  }
  await semanticApi.rejectProposal(item.kind, item.id, reason)
  ElMessage.success('已驳回并登记负反馈抑制')
  await loadAll()
}

function statusTagType(status: string): 'success' | 'warning' | 'info' | 'danger' {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'PROPOSED') return 'warning'
  if (status === 'REJECTED') return 'danger'
  return 'info'
}

onMounted(async () => {
  if (typeof route.query.tab === 'string') {
    activeTab.value = route.query.tab
  }
  await loadAll()
  if (typeof route.query.objectId === 'string' && route.query.objectId) {
    await openEditById(route.query.objectId)
  }
})
</script>

<template>
  <div class="page-container">
    <PageHeader
      title="语义本体"
      subtitle="人机共用单一语义真理源 · 逻辑元数据、物理映射与业务知识图谱"
    >
      <template #actions>
        <el-button size="small" @click="activeTab = 'playground'">编译与召回调试</el-button>
        <el-button v-if="canManage" type="primary" size="small" @click="openCreate">
          新建语义对象
        </el-button>
      </template>
    </PageHeader>

    <div class="stats-strip">
      <div class="stat-card" @click="activeTab = 'catalog'; selectType('METRIC')">
        <div class="stat-label">已发布标准指标</div>
        <div class="stat-value">{{ stats.publishedMetrics }}</div>
      </div>
      <div class="stat-card" @click="activeTab = 'catalog'; selectType('')">
        <div class="stat-label">已发布语义对象</div>
        <div class="stat-value">{{ stats.publishedObjects }}</div>
      </div>
      <div class="stat-card" @click="activeTab = 'graph'">
        <div class="stat-label">覆盖物理表 / 知识边</div>
        <div class="stat-value">{{ stats.mappedDatasets }} / {{ stats.totalLinks }}</div>
      </div>
      <div class="stat-card" @click="activeTab = 'proposals'">
        <div class="stat-label">待审演进提案 (PROPOSED)</div>
        <div class="stat-value" :class="{ warn: stats.pendingProposals > 0 }">
          {{ stats.pendingProposals }}
        </div>
      </div>
    </div>

    <el-card shadow="never">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="语义对象目录" name="catalog">
          <div class="catalog-split">
            <aside class="type-tree">
              <div
                class="tree-item"
                :class="{ active: query.objectType === '' }"
                @click="selectType('')"
              >
                全部语义对象
              </div>
              <div v-for="group in typeGroups" :key="group.label" class="tree-group">
                <div class="tree-group-label">{{ group.label }}</div>
                <div
                  v-for="item in group.items"
                  :key="item.value"
                  class="tree-item"
                  :class="{ active: query.objectType === item.value }"
                  @click="selectType(item.value)"
                >
                  {{ item.label }}
                </div>
              </div>
            </aside>

            <div class="table-pane">
              <div class="filter-row">
                <el-input
                  v-model="query.keyword"
                  size="small"
                  clearable
                  placeholder="搜索名称、编码或别名"
                  style="width: 240px"
                  @keyup.enter="query.page = 1; loadAll()"
                />
                <el-select
                  v-model="query.status"
                  size="small"
                  clearable
                  placeholder="发布状态"
                  style="width: 130px"
                  @change="query.page = 1; loadAll()"
                >
                  <el-option label="DRAFT" value="DRAFT" />
                  <el-option label="PROPOSED" value="PROPOSED" />
                  <el-option label="PUBLISHED" value="PUBLISHED" />
                  <el-option label="DEPRECATED" value="DEPRECATED" />
                  <el-option label="REJECTED" value="REJECTED" />
                </el-select>
                <el-select
                  v-model="query.scope"
                  size="small"
                  clearable
                  placeholder="共享作用域"
                  style="width: 150px"
                  @change="query.page = 1; loadAll()"
                >
                  <el-option label="GLOBAL（权威共享）" value="GLOBAL" />
                  <el-option label="WORKSPACE（空间私有）" value="WORKSPACE" />
                </el-select>
                <el-button size="small" @click="query.page = 1; loadAll()">查询</el-button>
              </div>

              <el-table v-loading="loading" :data="objects" size="small" border>
                <el-table-column label="语义对象" min-width="220">
                  <template #default="{ row }">
                    <div class="obj-name" @click="openEdit(row as SemanticObject)">
                      {{ row.name }}
                      <el-tag
                        v-if="row.scope === 'GLOBAL'"
                        size="small"
                        type="success"
                        style="margin-left: 6px"
                      >
                        GLOBAL 共享
                      </el-tag>
                    </div>
                    <div class="obj-code">{{ row.code }}</div>
                  </template>
                </el-table-column>
                <el-table-column prop="objectType" label="类型" width="110">
                  <template #default="{ row }">
                    <el-tag size="small" type="info">{{ row.objectType }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="Standard (C2)" width="130">
                  <template #default="{ row }">
                    <span v-if="row.standardRef" class="mono">{{ row.standardRef }}</span>
                    <el-tag v-else-if="row.objectType === 'METRIC'" size="small" type="warning">
                      缺失口径
                    </el-tag>
                    <span v-else class="muted">-</span>
                  </template>
                </el-table-column>
                <el-table-column label="映射 / 边" width="90">
                  <template #default="{ row }">{{ row.mappingCount }} / {{ row.linkCount }}</template>
                </el-table-column>
                <el-table-column prop="maxSensitivity" label="敏感级" width="76">
                  <template #default="{ row }">
                    <el-tag
                      size="small"
                      :type="row.maxSensitivity === 'L3' || row.maxSensitivity === 'L4' ? 'danger' : 'info'"
                    >
                      {{ row.maxSensitivity || 'L1' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="状态与版本" width="130">
                  <template #default="{ row }">
                    <el-tag size="small" :type="statusTagType(row.status)">
                      {{ row.status }} · v{{ row.versionNo }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="更新时间" width="155">
                  <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
                </el-table-column>
                <el-table-column label="操作" width="140" align="right">
                  <template #default="{ row }">
                    <el-button link type="primary" size="small" @click="openEdit(row as SemanticObject)">
                      配置
                    </el-button>
                    <el-button
                      v-if="canPublish && row.status !== 'PUBLISHED' && row.status !== 'REJECTED'"
                      link
                      type="success"
                      size="small"
                      @click="publish(row as SemanticObject)"
                    >
                      发布
                    </el-button>
                    <el-button
                      v-if="canPublish && row.status === 'PUBLISHED'"
                      link
                      type="warning"
                      size="small"
                      @click="deprecate(row as SemanticObject)"
                    >
                      废弃
                    </el-button>
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
                  @current-change="loadAll"
                />
              </div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="多仓库目录（Warehouse Catalog）" name="warehouses">
          <WarehouseCatalogPanel :can-manage="canManage" />
        </el-tab-pane>

        <el-tab-pane label="本体与知识图谱画布" name="graph">
          <SemanticGraphPanel :all-objects="allObjects" @select-object="openEditById" />
        </el-tab-pane>

        <el-tab-pane label="AI 召回与编译调试台" name="playground">
          <SemanticPlaygroundPanel :all-objects="allObjects" />
        </el-tab-pane>

        <el-tab-pane :label="`待审演进提案 (${proposals.length})`" name="proposals">
          <el-empty v-if="!proposals.length" description="当前没有待审的语义/图谱暂存提案" />
          <el-table v-else :data="proposals" size="small" border>
            <el-table-column prop="kind" label="类型" width="88">
              <template #default="{ row }">
                <el-tag size="small" type="warning">{{ row.kind }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="title" label="提案标题" min-width="200" />
            <el-table-column prop="summary" label="摘要与依据" min-width="220" show-overflow-tooltip />
            <el-table-column prop="sourceKind" label="来源" width="115" />
            <el-table-column label="提交时间" width="155">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column v-if="canPublish" label="宿主审校" width="140" align="right">
              <template #default="{ row }">
                <el-button link type="success" size="small" @click="approveProposal(row as SemanticProposalItem)">
                  批准发布
                </el-button>
                <el-button link type="danger" size="small" @click="rejectProposal(row as SemanticProposalItem)">
                  驳回
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <SemanticObjectDrawer
      v-model:visible="drawerVisible"
      :semantic-object="editingObject"
      :all-objects="allObjects"
      :can-manage="canManage"
      :can-publish="canPublish"
      @updated="loadAll"
    />
  </div>
</template>

<style scoped>
.stats-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--dg-space-md);
  margin-bottom: var(--dg-space-lg);
}
.stat-card {
  padding: var(--dg-space-md);
  background: var(--dg-surface);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
  cursor: pointer;
}
.stat-card:hover {
  border-color: var(--dg-primary);
}
.stat-label {
  font-size: 12px;
  color: var(--dg-text-muted);
}
.stat-value {
  margin-top: 4px;
  font-size: 20px;
  font-weight: 600;
  color: var(--dg-text);
  font-variant-numeric: tabular-nums;
}
.stat-value.warn {
  color: var(--dg-warning);
}
.catalog-split {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: var(--dg-space-md);
}
.type-tree {
  padding: var(--dg-space-sm);
  background: var(--dg-surface-muted);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
}
.tree-group {
  margin-top: var(--dg-space-md);
}
.tree-group-label {
  padding: 4px 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--dg-text-muted);
}
.tree-item {
  padding: 6px 10px;
  font-size: 13px;
  border-radius: var(--dg-radius);
  cursor: pointer;
  color: var(--dg-text-secondary);
}
.tree-item:hover {
  background: var(--dg-surface);
}
.tree-item.active {
  background: var(--dg-surface);
  color: var(--dg-primary);
  font-weight: 600;
}
.filter-row {
  display: flex;
  gap: var(--dg-space-sm);
  margin-bottom: var(--dg-space-md);
}
.obj-name {
  font-weight: 600;
  color: var(--dg-primary);
  cursor: pointer;
}
.obj-code,
.mono {
  font-family: var(--dg-font-mono);
  font-size: 12px;
  color: var(--dg-text-muted);
}
.muted {
  color: var(--dg-text-muted);
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--dg-space-md);
}
</style>
