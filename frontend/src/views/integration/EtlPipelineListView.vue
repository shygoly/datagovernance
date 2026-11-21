<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import PageHeader from '@/components/common/PageHeader.vue'
import { jobApi } from '@/api/job'
import { confirmAction, confirmDanger } from '@/utils/confirm'
import { formatDateTime } from '@/utils/format'
import { jobStatusMeta } from '@/utils/job-status'
import CompileDiagnosticsDialog from './CompileDiagnosticsDialog.vue'
import ScheduleDialog from './ScheduleDialog.vue'
import type { JobDefinition, JobDefinitionStatus } from '@/types/job'

defineOptions({ name: 'EtlPipelineListView' })

/**
 * ETL 管道列表 —— `/integration/etl`。
 *
 * 它是任务管理页(JobListView)钉死 jobType=ETL_PIPELINE 的一个投影:
 * 同一份任务定义、同一套编译 / 发布 / 调度 / 执行接口,只是新建与编辑
 * 进入的是画布而不是表单对话框。
 */

const router = useRouter()
const canManage = computed(() => useAuthStore().hasPermission('control:etl:manage'))

const loading = ref(false)
const rows = ref<JobDefinition[]>([])
const total = ref(0)
const query = reactive({
  page: 1,
  size: 20,
  status: '' as '' | JobDefinitionStatus,
  keyword: '',
})

const diagnosticsDialog = ref<InstanceType<typeof CompileDiagnosticsDialog>>()
const scheduleDialog = ref<InstanceType<typeof ScheduleDialog>>()

const STATUS_OPTIONS: JobDefinitionStatus[] = [
  'DRAFT', 'VALIDATED', 'PUBLISHED', 'SCHEDULING', 'PAUSED', 'OFFLINE', 'ARCHIVED',
]

const RUNNABLE: JobDefinitionStatus[] = ['PUBLISHED', 'SCHEDULING', 'PAUSED']

async function load() {
  loading.value = true
  try {
    const page = await jobApi.list({
      page: query.page,
      size: query.size,
      jobType: 'ETL_PIPELINE',
      status: query.status || undefined,
      keyword: query.keyword || undefined,
    })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

onMounted(load)

function onFilterChange() {
  query.page = 1
  void load()
}

function onCreate() {
  void router.push({ name: 'etl-editor', params: { id: 'new' } })
}

function onOpen(row: JobDefinition) {
  void router.push({ name: 'etl-editor', params: { id: row.id } })
}

async function onCompile(row: JobDefinition) {
  const result = await jobApi.compile(row.id)
  if (!result.succeeded || result.diagnostics.some((d) => d.severity === 'WARNING')) {
    diagnosticsDialog.value?.open(row.name, result)
  } else {
    ElMessage.success(result.summary)
  }
  await load()
}

async function onPublish(row: JobDefinition) {
  await jobApi.publish(row.id)
  ElMessage.success('已发布。可手工执行,也可绑定周期调度')
  await load()
}

async function onRun(row: JobDefinition) {
  if (!row.planUpToDate) {
    ElMessage.warning('定义已修改但尚未重新编译发布,不能执行 —— 否则跑的会是一份过期的计划')
    return
  }
  const execution = await jobApi.run(row.id)
  const go = await confirmAction(`执行已提交,记录 ID:${execution.id}`, '已提交', {
    confirmButtonText: '去看执行记录',
    cancelButtonText: '留在本页',
    type: 'success',
  })
  if (go) {
    void router.push({ name: 'executions', query: { jobRefId: row.id } })
  }
  await load()
}

function onSchedule(row: JobDefinition) {
  scheduleDialog.value?.open(row)
}

async function onOffline(row: JobDefinition) {
  if (!(await confirmAction(`下线「${row.name}」后不再触发,要重新编译并发布才能再跑。确定吗?`, '下线管道'))) {
    return
  }
  await jobApi.offline(row.id)
  ElMessage.success('已下线')
  await load()
}

async function onDelete(row: JobDefinition) {
  if (!(await confirmDanger(`确定删除管道「${row.name}」吗?已有的执行记录会保留。`, '删除管道'))) {
    return
  }
  await jobApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

function viewExecutions(row: JobDefinition) {
  void router.push({ name: 'executions', query: { jobRefId: row.id } })
}

/** 节点 / 连线数量:列表上一眼看出管道规模,config 是 etl.ir/v1 时才有 */
function shape(row: JobDefinition): string {
  const nodes = row.config?.nodes
  const edges = row.config?.edges
  if (!Array.isArray(nodes)) return '—'
  return `${nodes.length} 节点 / ${Array.isArray(edges) ? edges.length : 0} 连线`
}
</script>

<template>
  <div class="page-container">
    <PageHeader
      title="ETL 管道"
      description="拖拽式搭建 数据源 → 转换 → 目标 管道,DuckDB 向量化执行,编译期自带字段血缘"
    >
      <template #actions>
        <el-button v-permission="['control:etl:manage']" type="primary" @click="onCreate">
          新建管道
        </el-button>
      </template>
    </PageHeader>

    <el-card shadow="never">
      <div class="page-toolbar">
        <div class="page-toolbar__filters">
          <el-select
            v-model="query.status"
            placeholder="全部状态"
            clearable
            style="width: 140px"
            @change="onFilterChange"
          >
            <el-option
              v-for="s in STATUS_OPTIONS"
              :key="s"
              :label="jobStatusMeta(s).label"
              :value="s"
            />
          </el-select>
          <el-input
            v-model="query.keyword"
            placeholder="搜索管道名称"
            clearable
            style="width: 200px"
            @keyup.enter="onFilterChange"
            @clear="onFilterChange"
          />
          <el-button @click="onFilterChange">查询</el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="rows">
        <el-table-column label="管道名称" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link type="primary" @click="onOpen(row as JobDefinition)">
              {{ (row as JobDefinition).name }}
            </el-link>
            <div v-if="(row as JobDefinition).description" class="text-muted">
              {{ (row as JobDefinition).description }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="规模" width="150">
          <template #default="{ row }">
            <span class="text-muted">{{ shape(row as JobDefinition) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tooltip :content="jobStatusMeta((row as JobDefinition).status).action">
              <el-tag :type="jobStatusMeta((row as JobDefinition).status).tagType" size="small">
                {{ (row as JobDefinition).statusDisplayName }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="编译" width="120">
          <template #default="{ row }">
            <el-tag
              v-if="!(row as JobDefinition).planUpToDate"
              type="warning"
              size="small"
              effect="plain"
            >
              待重新编译
            </el-tag>
            <el-tag
              v-else-if="(row as JobDefinition).lastCompileSucceeded"
              type="success"
              size="small"
              effect="plain"
            >
              已通过
            </el-tag>
            <span v-else class="text-muted">未编译</span>
          </template>
        </el-table-column>
        <el-table-column label="调度" min-width="170">
          <template #default="{ row }">
            <template v-if="(row as JobDefinition).cronExpression">
              <div class="text-mono">{{ (row as JobDefinition).cronExpression }}</div>
              <div class="text-muted">下次 {{ formatDateTime((row as JobDefinition).nextFireAt) }}</div>
            </template>
            <span v-else class="text-muted">未配置</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">{{ formatDateTime((row as JobDefinition).updatedAt) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="420" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onOpen(row as JobDefinition)">
              {{ canManage ? '画布' : '查看' }}
            </el-button>
            <el-button
              v-permission="'control:job:compile'"
              link
              type="primary"
              @click="onCompile(row as JobDefinition)"
            >
              编译
            </el-button>
            <el-button
              v-permission="'control:job:publish'"
              link
              type="primary"
              :disabled="!['VALIDATED', 'DRAFT'].includes((row as JobDefinition).status)"
              @click="onPublish(row as JobDefinition)"
            >
              发布
            </el-button>
            <el-button
              v-permission="'control:job:trigger'"
              link
              type="success"
              :disabled="!RUNNABLE.includes((row as JobDefinition).status)"
              @click="onRun(row as JobDefinition)"
            >
              执行
            </el-button>
            <el-button
              v-permission="'control:job:schedule'"
              link
              type="primary"
              :disabled="!RUNNABLE.includes((row as JobDefinition).status)"
              @click="onSchedule(row as JobDefinition)"
            >
              调度
            </el-button>
            <el-button link type="primary" @click="viewExecutions(row as JobDefinition)">记录</el-button>
            <el-button
              v-permission="'control:job:publish'"
              link
              type="warning"
              :disabled="!RUNNABLE.includes((row as JobDefinition).status)"
              @click="onOffline(row as JobDefinition)"
            >
              下线
            </el-button>
            <el-button
              v-permission="'control:job:delete'"
              link
              type="danger"
              @click="onDelete(row as JobDefinition)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        :current-page="query.page"
        :page-size="query.size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="(p: number) => { query.page = p; load() }"
        @size-change="(s: number) => { query.size = s; query.page = 1; load() }"
      />
    </el-card>

    <ScheduleDialog ref="scheduleDialog" @saved="load" />
    <CompileDiagnosticsDialog ref="diagnosticsDialog" />
  </div>
</template>
