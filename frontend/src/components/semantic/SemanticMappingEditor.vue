<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { catalogAssetApi } from '@/api/catalog-assets'
import { semanticApi } from '@/api/semantic'
import DatasetSearchSelect from '@/components/metadata/DatasetSearchSelect.vue'
import { confirmDanger } from '@/utils/confirm'
import type { CatalogColumn } from '@/types/catalog-assets'
import type {
  HeterogeneousMappingMatrix,
  LogicalTable,
  SemanticMapping,
  SemanticMappingRole,
  WorkspaceWarehouse,
} from '@/types/semantic'

const props = defineProps<{
  objectId: string
  canManage: boolean
}>()

const emit = defineEmits<{
  changed: []
}>()

const loading = ref(false)
const saving = ref(false)
const mappings = ref<SemanticMapping[]>([])
const warehouses = ref<WorkspaceWarehouse[]>([])
const logicalTables = ref<LogicalTable[]>([])
const matrix = ref<HeterogeneousMappingMatrix | null>(null)
const columns = ref<CatalogColumn[]>([])

const form = reactive({
  warehouseId: '',
  logicalTableId: '',
  mappingRole: 'MEASURE' as SemanticMappingRole,
  catalogDatasetId: '',
  catalogColumnId: '',
  expressionSql: '',
  valueNormalizationText: '',
  sourceUnit: '',
  targetUnit: '',
  isLookupTable: false,
  priority: 100,
})

const roleOptions: Array<{ value: SemanticMappingRole; label: string }> = [
  { value: 'ID', label: 'ID（主键形态 / 跨仓连接键）' },
  { value: 'DESC', label: 'DESC（展示名形态）' },
  { value: 'CODE', label: 'CODE（标准码形态）' },
  { value: 'MEASURE', label: 'MEASURE（度量源列）' },
  { value: 'TIME_KEY', label: 'TIME_KEY（时间键）' },
  { value: 'FILTER_KEY', label: 'FILTER_KEY（过滤列）' },
]

const filteredLogicalTables = computed(() => {
  if (!form.warehouseId) return logicalTables.value
  return logicalTables.value.filter((t) => t.warehouseId === form.warehouseId)
})

const warehouseNameById = computed(() => {
  const map = new Map<string, string>()
  for (const w of warehouses.value) {
    map.set(w.id, w.cohortCode ? `${w.name} (${w.cohortCode})` : w.name)
  }
  return map
})

async function load() {
  if (!props.objectId) return
  loading.value = true
  try {
    const [mList, whList, ltList, mat] = await Promise.all([
      semanticApi.listMappings(props.objectId),
      semanticApi.listWarehouses().catch(() => [] as WorkspaceWarehouse[]),
      semanticApi.listLogicalTables().catch(() => [] as LogicalTable[]),
      semanticApi.mappingMatrix(props.objectId).catch(() => null),
    ])
    mappings.value = mList
    warehouses.value = whList
    logicalTables.value = ltList
    matrix.value = mat
  } finally {
    loading.value = false
  }
}

async function onDatasetChange(datasetId: string) {
  form.catalogColumnId = ''
  columns.value = []
  if (!datasetId) return
  try {
    const detail = await catalogAssetApi.getDataset(datasetId)
    columns.value = detail.columns ?? []
  } catch {
    columns.value = []
  }
}

function onLogicalTableChange(logicalTableId: string) {
  if (!logicalTableId) return
  const lt = logicalTables.value.find((t) => t.id === logicalTableId)
  if (lt) {
    form.warehouseId = lt.warehouseId
    form.catalogDatasetId = lt.catalogDatasetId
    void onDatasetChange(lt.catalogDatasetId)
  }
}

async function addMapping() {
  if (!form.catalogDatasetId) {
    ElMessage.warning('请先选择逻辑表或物理数据集')
    return
  }
  let valueNormalization: Record<string, string> | null = null
  if (form.valueNormalizationText.trim()) {
    try {
      const parsed = JSON.parse(form.valueNormalizationText.trim())
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        valueNormalization = parsed as Record<string, string>
      } else {
        ElMessage.warning('值归一化 JSON 必须是键值对对象，例如 {"Male":"M","Female":"F"}')
        return
      }
    } catch {
      ElMessage.warning('值归一化 JSON 格式错误')
      return
    }
  }
  saving.value = true
  try {
    await semanticApi.addMapping(props.objectId, {
      mappingRole: form.mappingRole,
      catalogDatasetId: form.catalogDatasetId,
      catalogColumnId: form.catalogColumnId || null,
      expressionSql: form.expressionSql.trim() || null,
      isLookupTable: form.isLookupTable,
      priority: form.priority,
      warehouseId: form.warehouseId || null,
      logicalTableId: form.logicalTableId || null,
      valueNormalization,
      sourceUnit: form.sourceUnit.trim() || null,
      targetUnit: form.targetUnit.trim() || null,
    })
    ElMessage.success('跨仓物理映射已添加')
    form.catalogColumnId = ''
    form.expressionSql = ''
    form.valueNormalizationText = ''
    form.sourceUnit = ''
    form.targetUnit = ''
    await load()
    emit('changed')
  } finally {
    saving.value = false
  }
}

async function removeMapping(mappingId: string) {
  const ok = await confirmDanger('确定删除该物理映射吗？', '删除物理映射')
  if (!ok) return
  await semanticApi.deleteMapping(props.objectId, mappingId)
  ElMessage.success('物理映射已删除')
  await load()
  emit('changed')
}

watch(() => props.objectId, () => void load(), { immediate: true })
</script>

<template>
  <div class="semantic-mapping-editor">
    <div v-if="matrix && matrix.warehouses.length" class="matrix-box">
      <div class="matrix-title">跨仓库异构映射矩阵（同一本体绑多仓/多表/多列）</div>
      <div class="matrix-grid">
        <div v-for="wh in matrix.warehouses" :key="wh.warehouseId" class="matrix-card">
          <div class="matrix-card-head">
            <span class="bold">{{ wh.warehouseName }}</span>
            <el-tag size="small" type="info">{{ wh.cohortCode || wh.dialect }}</el-tag>
          </div>
          <div v-if="!wh.mappings.length" class="muted">未配置映射</div>
          <div v-for="cell in wh.mappings" :key="cell.mappingId" class="matrix-cell">
            <el-tag size="small" type="primary">{{ cell.mappingRole }}</el-tag>
            <span class="mono">{{ cell.logicalTableName || cell.datasetQualifiedName }}</span>
            <span class="mono muted">.{{ cell.columnName || cell.expressionSql || '*' }}</span>
            <el-tag v-if="cell.sourceUnit && cell.targetUnit" size="small" type="warning">
              {{ cell.sourceUnit }} → {{ cell.targetUnit }}
            </el-tag>
          </div>
        </div>
      </div>
    </div>

    <el-form v-if="canManage" label-position="top" class="mapping-form">
      <div class="mapping-grid">
        <el-form-item label="目标数据仓库（多仓异构可选）">
          <el-select
            v-model="form.warehouseId"
            clearable
            placeholder="选择仓库（如 MIMIC-IV / eICU）"
            style="width: 100%"
            @change="form.logicalTableId = ''"
          >
            <el-option
              v-for="w in warehouses"
              :key="w.id"
              :label="`${w.name} (${w.cohortCode || w.code})`"
              :value="w.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="逻辑表（Warehouse Catalog）">
          <el-select
            v-model="form.logicalTableId"
            clearable
            filterable
            placeholder="选择逻辑表（自动带出物理表）"
            style="width: 100%"
            @change="onLogicalTableChange"
          >
            <el-option
              v-for="lt in filteredLogicalTables"
              :key="lt.id"
              :label="`${lt.logicalName} [Size=${lt.logicalSize}]`"
              :value="lt.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="物理数据集（Catalog）" required>
          <DatasetSearchSelect
            v-model="form.catalogDatasetId"
            placeholder="搜索并选择物理表"
            @change="onDatasetChange"
          />
        </el-form-item>
        <el-form-item label="物理字段">
          <el-select
            v-model="form.catalogColumnId"
            clearable
            filterable
            placeholder="选择字段（复合表达式可留空）"
            style="width: 100%"
          >
            <el-option
              v-for="col in columns"
              :key="col.id"
              :label="`${col.name} (${col.canonicalType || col.rawType || ''})`"
              :value="col.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="映射角色">
          <el-select v-model="form.mappingRole" style="width: 100%">
            <el-option
              v-for="opt in roleOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="1" :max="999" style="width: 100%" />
        </el-form-item>
      </div>
      <el-form-item label="只读列级/单位换算表达式（如 los * 24.0 或 unitdischargeoffset / 60.0）">
        <el-input
          v-model="form.expressionSql"
          placeholder="例如 los * 24.0"
          class="mono-input"
        />
      </el-form-item>
      <div class="mapping-grid">
        <el-form-item label="异构值归一化 JSON（编译期自动生成 CASE WHEN）">
          <el-input
            v-model="form.valueNormalizationText"
            placeholder='例如 {"Male":"M","Female":"F"}'
            class="mono-input"
          />
        </el-form-item>
        <div class="unit-pair">
          <el-form-item label="源单位">
            <el-input v-model="form.sourceUnit" placeholder="如 天 / 分钟" />
          </el-form-item>
          <el-form-item label="目标标准单位">
            <el-input v-model="form.targetUnit" placeholder="如 小时" />
          </el-form-item>
        </div>
      </div>
      <div class="mapping-actions">
        <el-checkbox v-model="form.isLookupTable">主维表 / Lookup 表</el-checkbox>
        <el-button type="primary" size="small" :loading="saving" @click="addMapping">
          添加物理映射
        </el-button>
      </div>
    </el-form>

    <el-table v-loading="loading" :data="mappings" size="small" border>
      <el-table-column label="所属仓库" width="135" show-overflow-tooltip>
        <template #default="{ row }">
          <el-tag v-if="row.warehouseId" size="small" type="success">
            {{ warehouseNameById.get(row.warehouseId) || row.warehouseId }}
          </el-tag>
          <span v-else class="muted">默认源</span>
        </template>
      </el-table-column>
      <el-table-column prop="mappingRole" label="角色" width="96">
        <template #default="{ row }">
          <el-tag size="small" type="info">{{ row.mappingRole }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="datasetQualifiedName" label="物理表" min-width="150" show-overflow-tooltip />
      <el-table-column prop="columnName" label="字段 / 表达式" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="mono">{{ row.expressionSql || row.columnName || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="columnSensitivity" label="敏感级" width="76">
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="row.columnSensitivity === 'L3' || row.columnSensitivity === 'L4' ? 'danger' : 'info'"
          >
            {{ row.columnSensitivity || 'L1' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="canManage" label="操作" width="74" align="right">
        <template #default="{ row }">
          <el-button link type="danger" size="small" @click="removeMapping(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.semantic-mapping-editor {
  display: flex;
  flex-direction: column;
  gap: var(--dg-space-md);
}

.matrix-box {
  padding: var(--dg-space-sm) var(--dg-space-md);
  background: var(--dg-surface-muted);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
}

.matrix-title {
  margin-bottom: var(--dg-space-sm);
  font-size: 12px;
  font-weight: 600;
  color: var(--dg-text);
}

.matrix-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--dg-space-sm);
}

.matrix-card {
  padding: var(--dg-space-sm);
  background: var(--dg-surface);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.matrix-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.matrix-cell {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  font-size: 12px;
}

.mapping-form {
  padding: var(--dg-space-md);
  background: var(--dg-surface-muted);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
}

.mapping-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--dg-space-md);
}

.unit-pair {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--dg-space-sm);
}

.mapping-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.bold {
  font-weight: 600;
  color: var(--dg-text);
}

.mono,
.mono-input :deep(input) {
  font-family: var(--dg-font-mono);
  font-size: 12px;
}

.muted {
  font-size: 12px;
  color: var(--dg-text-muted);
}
</style>
