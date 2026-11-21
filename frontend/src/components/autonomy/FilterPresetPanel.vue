<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { autonomyApi } from '@/api/autonomy'
import { confirmDanger } from '@/utils/confirm'
import type { FilterPreset } from '@/types/autonomy'

const props = defineProps<{ canManage: boolean }>()

const loading = ref(false)
const rows = ref<FilterPreset[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 20, keyword: '', presetType: '', status: '' })

const dialogVisible = ref(false)
const editingId = ref('')
const saving = ref(false)
const form = reactive({
  code: '',
  name: '',
  description: '',
  presetType: 'SMQ_FILTER',
  status: 'ACTIVE',
  specText: '{\n  "where": []\n}',
})

async function load() {
  loading.value = true
  try {
    const page = await autonomyApi.listFilterPresets({
      page: query.page,
      size: query.size,
      keyword: query.keyword.trim() || undefined,
      presetType: query.presetType || undefined,
      status: query.status || undefined,
    })
    rows.value = page.records
    total.value = page.total
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
  form.presetType = 'SMQ_FILTER'
  form.status = 'ACTIVE'
  form.specText = '{\n  "where": []\n}'
  dialogVisible.value = true
}

function openEdit(row: FilterPreset) {
  editingId.value = row.id
  form.code = row.code
  form.name = row.name
  form.description = row.description || ''
  form.presetType = row.presetType
  form.status = row.status
  form.specText = JSON.stringify(row.spec || {}, null, 2)
  dialogVisible.value = true
}

async function save() {
  let spec: Record<string, unknown>
  try {
    spec = JSON.parse(form.specText || '{}')
  } catch {
    ElMessage.error('预设条件不是合法 JSON')
    return
  }
  saving.value = true
  try {
    const payload = {
      code: form.code.trim(),
      name: form.name.trim(),
      description: form.description.trim(),
      presetType: form.presetType,
      status: form.status,
      spec,
    }
    if (editingId.value) {
      await autonomyApi.updateFilterPreset(editingId.value, payload)
      ElMessage.success('过滤器预设已更新')
    } else {
      await autonomyApi.createFilterPreset(payload)
      ElMessage.success('过滤器预设已创建')
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: FilterPreset) {
  if (!(await confirmDanger(`删除过滤器预设「${row.name}」？`, '删除预设'))) return
  await autonomyApi.deleteFilterPreset(row.id)
  ElMessage.success('已删除')
  await load()
}

function search() {
  query.page = 1
  void load()
}
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="head">
        <div class="title">命名过滤器预设</div>
        <div class="actions">
          <el-input v-model="query.keyword" size="small" clearable placeholder="搜索编码/名称" style="width: 180px" @keyup.enter="search" />
          <el-select v-model="query.presetType" size="small" clearable placeholder="类型" style="width: 150px" @change="search">
            <el-option label="SMQ_FILTER" value="SMQ_FILTER" />
            <el-option label="COHORT" value="COHORT" />
            <el-option label="QUALITY_SCOPE" value="QUALITY_SCOPE" />
          </el-select>
          <el-button v-if="props.canManage" size="small" type="primary" @click="openCreate">新建预设</el-button>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-table v-loading="loading" :data="rows" size="small" border>
      <el-table-column label="预设" min-width="200">
        <template #default="{ row }">
          <div class="strong">{{ row.name }}</div>
          <div class="muted mono">{{ row.code }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="presetType" label="类型" width="150" />
      <el-table-column label="条件" min-width="280">
        <template #default="{ row }"><code class="condition">{{ JSON.stringify(row.spec) }}</code></template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="props.canManage" label="操作" width="140" align="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row as FilterPreset)">编辑</el-button>
          <el-button link type="danger" size="small" @click="remove(row as FilterPreset)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑过滤器预设' : '新建过滤器预设'" width="620px">
      <el-form label-width="100px">
        <el-form-item label="编码" required><el-input v-model="form.code" placeholder="如 smq_region_east" /></el-form-item>
        <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.presetType" style="width: 100%">
            <el-option label="SMQ_FILTER" value="SMQ_FILTER" />
            <el-option label="COHORT" value="COHORT" />
            <el-option label="QUALITY_SCOPE" value="QUALITY_SCOPE" />
          </el-select>
        </el-form-item>
        <el-form-item label="条件 JSON" required>
          <el-input v-model="form.specText" type="textarea" :rows="9" class="mono" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="ACTIVE" value="ACTIVE" />
            <el-option label="DISABLED" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; gap: var(--dg-space-sm); flex-wrap: wrap; }
.title { font-weight: 600; color: var(--dg-text); }
.actions { display: flex; gap: var(--dg-space-sm); flex-wrap: wrap; }
.strong { font-weight: 600; color: var(--dg-text); }
.muted { color: var(--dg-text-muted); font-size: 12px; }
.mono { font-family: var(--dg-font-mono); }
.condition { font-size: 11px; color: var(--dg-text-muted); word-break: break-all; }
.pager { display: flex; justify-content: flex-end; margin-top: var(--dg-space-md); }
</style>
