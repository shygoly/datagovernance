<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dataSourceApi } from '@/api/datasource'
import { semanticApi } from '@/api/semantic'
import DatasetSearchSelect from '@/components/metadata/DatasetSearchSelect.vue'
import { confirmDanger } from '@/utils/confirm'
import type { DataSource } from '@/types/datasource'
import type {
  FederationEngineType,
  LogicalTable,
  LogicalTableRole,
  WarehouseCombineMode,
  WarehouseRole,
  WorkspaceWarehouse,
} from '@/types/semantic'

const props = defineProps<{
  canManage: boolean
}>()

const loading = ref(false)
const warehouses = ref<WorkspaceWarehouse[]>([])
const logicalTables = ref<LogicalTable[]>([])
const datasources = ref<DataSource[]>([])
const selectedWarehouseId = ref('')

const whDialog = ref(false)
const whSaving = ref(false)
const whForm = reactive({
  id: '',
  datasourceId: '',
  code: '',
  name: '',
  warehouseRole: 'SECONDARY' as WarehouseRole,
  cohortCode: '',
  dialect: 'POSTGRESQL',
  idNamespacePrefix: '',
  combineMode: 'COHORT_UNION' as WarehouseCombineMode,
  federationEngine: 'AUTO' as FederationEngineType,
  starrocksCatalogName: '',
  priority: 100,
})

const ltDialog = ref(false)
const ltSaving = ref(false)
const ltForm = reactive({
  id: '',
  warehouseId: '',
  catalogDatasetId: '',
  logicalName: '',
  tableRole: 'FACT' as LogicalTableRole,
  logicalSize: 500,
  customViewSql: '',
})

const filteredLogicalTables = computed(() => {
  if (!selectedWarehouseId.value) return logicalTables.value
  return logicalTables.value.filter((t) => t.warehouseId === selectedWarehouseId.value)
})

async function loadAll() {
  loading.value = true
  try {
    const [whList, ltList, dsPage] = await Promise.all([
      semanticApi.listWarehouses(),
      semanticApi.listLogicalTables(),
      dataSourceApi.list({ page: 1, size: 100 }).catch(() => ({ records: [] as DataSource[] })),
    ])
    warehouses.value = whList
    logicalTables.value = ltList
    datasources.value = dsPage.records ?? []
  } finally {
    loading.value = false
  }
}

function openCreateWarehouse() {
  whForm.id = ''
  whForm.datasourceId = datasources.value[0]?.id ?? ''
  whForm.code = ''
  whForm.name = ''
  whForm.warehouseRole = warehouses.value.length === 0 ? 'PRIMARY' : 'SECONDARY'
  whForm.cohortCode = ''
  whForm.dialect = 'POSTGRESQL'
  whForm.idNamespacePrefix = ''
  whForm.combineMode = 'COHORT_UNION'
  whForm.federationEngine = 'AUTO'
  whForm.starrocksCatalogName = ''
  whForm.priority = (warehouses.value.length + 1) * 10
  whDialog.value = true
}

function openEditWarehouse(row: WorkspaceWarehouse) {
  whForm.id = row.id
  whForm.datasourceId = row.datasourceId
  whForm.code = row.code
  whForm.name = row.name
  whForm.warehouseRole = row.warehouseRole
  whForm.cohortCode = row.cohortCode ?? ''
  whForm.dialect = row.dialect || 'POSTGRESQL'
  whForm.idNamespacePrefix = row.idNamespacePrefix ?? ''
  whForm.combineMode = row.combineMode
  whForm.federationEngine = row.federationEngine
  whForm.starrocksCatalogName = row.starrocksCatalogName ?? ''
  whForm.priority = row.priority
  whDialog.value = true
}

async function saveWarehouse() {
  if (!whForm.datasourceId || !whForm.code.trim() || !whForm.name.trim()) {
    ElMessage.warning('请选择数据源并填写仓库编码与名称')
    return
  }
  whSaving.value = true
  try {
    await semanticApi.upsertWarehouse({
      id: whForm.id || undefined,
      datasourceId: whForm.datasourceId,
      code: whForm.code.trim(),
      name: whForm.name.trim(),
      warehouseRole: whForm.warehouseRole,
      cohortCode: whForm.cohortCode.trim() || null,
      dialect: whForm.dialect,
      idNamespacePrefix: whForm.idNamespacePrefix.trim() || null,
      combineMode: whForm.combineMode,
      federationEngine: whForm.federationEngine,
      starrocksCatalogName: whForm.starrocksCatalogName.trim() || null,
      priority: whForm.priority,
    })
    ElMessage.success('仓库挂载已保存')
    whDialog.value = false
    await loadAll()
  } finally {
    whSaving.value = false
  }
}

async function removeWarehouse(row: WorkspaceWarehouse) {
  const ok = await confirmDanger(`确定移除仓库挂载「${row.name}」吗？其下逻辑表也会被级联移除。`, '移除仓库挂载')
  if (!ok) return
  await semanticApi.deleteWarehouse(row.id)
  ElMessage.success('已移除仓库挂载')
  if (selectedWarehouseId.value === row.id) {
    selectedWarehouseId.value = ''
  }
  await loadAll()
}

function openCreateLogicalTable() {
  ltForm.id = ''
  ltForm.warehouseId = selectedWarehouseId.value || warehouses.value[0]?.id || ''
  ltForm.catalogDatasetId = ''
  ltForm.logicalName = ''
  ltForm.tableRole = 'FACT'
  ltForm.logicalSize = 500
  ltForm.customViewSql = ''
  ltDialog.value = true
}

async function saveLogicalTable() {
  if (!ltForm.warehouseId || !ltForm.catalogDatasetId || !ltForm.logicalName.trim()) {
    ElMessage.warning('请选择所属仓库、物理表并填写逻辑表名')
    return
  }
  ltSaving.value = true
  try {
    await semanticApi.upsertLogicalTable({
      id: ltForm.id || undefined,
      warehouseId: ltForm.warehouseId,
      catalogDatasetId: ltForm.catalogDatasetId,
      logicalName: ltForm.logicalName.trim(),
      tableRole: ltForm.tableRole,
      logicalSize: ltForm.logicalSize,
      customViewSql: ltForm.customViewSql.trim() || null,
    })
    ElMessage.success('逻辑表已保存')
    ltDialog.value = false
    await loadAll()
  } finally {
    ltSaving.value = false
  }
}

async function createAlias(row: LogicalTable) {
  let aliasName = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `为逻辑表「${row.logicalName}」创建别名（解决 Attribute Role，如入院日期 / 出院日期）：`,
      '创建 Logical Table Alias',
      {
        confirmButtonText: '创建别名',
        cancelButtonText: '取消',
        inputPlaceholder: `${row.logicalName}_alias`,
        inputValidator: (v) => (v && v.trim().length > 0 ? true : '别名名称必填'),
      },
    )
    aliasName = value.trim()
  } catch {
    return
  }
  await semanticApi.createLogicalTableAlias(row.id, { logicalName: aliasName })
  ElMessage.success('逻辑表别名已创建')
  await loadAll()
}

async function removeLogicalTable(row: LogicalTable) {
  const ok = await confirmDanger(`确定删除逻辑表「${row.logicalName}」吗？`, '删除逻辑表')
  if (!ok) return
  await semanticApi.deleteLogicalTable(row.id)
  ElMessage.success('逻辑表已删除')
  await loadAll()
}

onMounted(() => void loadAll())
</script>

<template>
  <div v-loading="loading" class="wh-catalog">
    <el-alert
      type="info"
      show-icon
      :closable="false"
      class="wh-alert"
      title="MicroStrategy 风格多仓库目录：当前 Workspace 可同时挂载 1..N 个数据仓库（如 MIMIC-IV + eICU-CRD 队列联合，或 HIS + LIS 经 StarRocks / DuckDB 跨仓任意 Join）。"
    />

    <section class="wh-section">
      <div class="wh-section-head">
        <div>
          <h4>1. Workspace 挂载的数据仓库（DBInstances）</h4>
          <span class="muted">支持单仓路由、互斥队列水平联合（COHORT_UNION）与共享实体联邦联结（ENTITY_FEDERATED_JOIN）</span>
        </div>
        <el-button v-if="canManage" type="primary" size="small" @click="openCreateWarehouse">
          挂载数据仓库
        </el-button>
      </div>

      <el-table :data="warehouses" size="small" border>
        <el-table-column label="仓库名称 / 标识" min-width="180">
          <template #default="{ row }">
            <div class="bold">{{ row.name }}</div>
            <div class="mono muted">{{ row.code }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="datasourceName" label="关联数据源" min-width="140" show-overflow-tooltip />
        <el-table-column label="角色 / 队列" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="row.warehouseRole === 'PRIMARY' ? 'primary' : 'info'">
              {{ row.warehouseRole }}
            </el-tag>
            <el-tag v-if="row.cohortCode" size="small" type="success" style="margin-left: 4px">
              {{ row.cohortCode }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dialect" label="方言" width="110" />
        <el-table-column label="多仓组合 / 联邦引擎" min-width="200">
          <template #default="{ row }">
            <el-tag size="small" type="warning">{{ row.combineMode }}</el-tag>
            <el-tag size="small" type="info" style="margin-left: 4px">{{ row.federationEngine }}</el-tag>
            <div v-if="row.idNamespacePrefix" class="mono muted">
              前缀: {{ row.idNamespacePrefix }}
            </div>
            <div v-if="row.starrocksCatalogName" class="mono muted">
              SR Catalog: {{ row.starrocksCatalogName }}
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="logicalTableCount" label="逻辑表数" width="84" />
        <el-table-column label="操作" width="140" align="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              size="small"
              @click="selectedWarehouseId = selectedWarehouseId === row.id ? '' : row.id"
            >
              {{ selectedWarehouseId === row.id ? '看全部表' : '筛选表' }}
            </el-button>
            <el-button v-if="canManage" link type="primary" size="small" @click="openEditWarehouse(row as WorkspaceWarehouse)">
              编辑
            </el-button>
            <el-button v-if="canManage" link type="danger" size="small" @click="removeWarehouse(row as WorkspaceWarehouse)">
              移除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !warehouses.length" description="尚未挂载数据仓库（当前默认单源兼容模式）" :image-size="64" />
    </section>

    <section class="wh-section">
      <div class="wh-section-head">
        <div>
          <h4>2. 逻辑表与别名目录（Logical Tables & Aliases）</h4>
          <span class="muted">Logical Size 越小越优先被选表器选中（如汇总表设为 50，明细事实表设为 500）</span>
        </div>
        <div class="wh-tools">
          <el-select
            v-model="selectedWarehouseId"
            size="small"
            clearable
            placeholder="按仓库筛选"
            style="width: 180px"
          >
            <el-option v-for="w in warehouses" :key="w.id" :label="`${w.name} (${w.code})`" :value="w.id" />
          </el-select>
          <el-button
            v-if="canManage"
            type="primary"
            size="small"
            :disabled="!warehouses.length"
            @click="openCreateLogicalTable"
          >
            导入逻辑表
          </el-button>
        </div>
      </div>

      <el-table :data="filteredLogicalTables" size="small" border>
        <el-table-column label="逻辑表名" min-width="180">
          <template #default="{ row }">
            <span class="mono bold">{{ row.logicalName }}</span>
            <el-tag v-if="row.aliasOfName" size="small" type="warning" style="margin-left: 6px">
              Alias → {{ row.aliasOfName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="所属仓库" width="150">
          <template #default="{ row }">
            <span>{{ row.warehouseName || row.warehouseCode || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="datasetQualifiedName" label="底层物理表" min-width="200" show-overflow-tooltip />
        <el-table-column prop="tableRole" label="表角色" width="95">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ row.tableRole }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="logicalSize" label="Logical Size" width="105" />
        <el-table-column v-if="canManage" label="操作" width="150" align="right">
          <template #default="{ row }">
            <el-button
              v-if="!row.aliasOfTableId"
              link
              type="primary"
              size="small"
              @click="createAlias(row as LogicalTable)"
            >
              建别名
            </el-button>
            <el-button link type="danger" size="small" @click="removeLogicalTable(row as LogicalTable)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="whDialog" :title="whForm.id ? '编辑仓库挂载' : '挂载数据仓库'" width="540px">
      <el-form label-position="top">
        <el-form-item label="物理数据源（DataSource）" required>
          <el-select v-model="whForm.datasourceId" filterable placeholder="选择已有数据源" style="width: 100%">
            <el-option
              v-for="ds in datasources"
              :key="ds.id"
              :label="`${ds.name} (${ds.type} · ${ds.status})`"
              :value="ds.id"
            />
          </el-select>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="仓库标识（code）" required>
            <el-input v-model="whForm.code" placeholder="如 mimic_iv / eicu_crd / his" />
          </el-form-item>
          <el-form-item label="展示名称" required>
            <el-input v-model="whForm.name" placeholder="如 MIMIC-IV 重症仓" />
          </el-form-item>
          <el-form-item label="仓库角色">
            <el-select v-model="whForm.warehouseRole" style="width: 100%">
              <el-option label="PRIMARY（主仓）" value="PRIMARY" />
              <el-option label="SECONDARY（次仓）" value="SECONDARY" />
            </el-select>
          </el-form-item>
          <el-form-item label="SQL 方言">
            <el-select v-model="whForm.dialect" style="width: 100%">
              <el-option label="POSTGRESQL" value="POSTGRESQL" />
              <el-option label="STARROCKS" value="STARROCKS" />
              <el-option label="MYSQL" value="MYSQL" />
              <el-option label="CLICKHOUSE" value="CLICKHOUSE" />
              <el-option label="DUCKDB" value="DUCKDB" />
            </el-select>
          </el-form-item>
          <el-form-item label="队列标识（cohortCode）">
            <el-input v-model="whForm.cohortCode" placeholder="如 MIMIC_IV / EICU_CRD" />
          </el-form-item>
          <el-form-item label="主键命名空间前缀（防碰撞）">
            <el-input v-model="whForm.idNamespacePrefix" placeholder="如 mimic: 或 eicu:" />
          </el-form-item>
          <el-form-item label="多仓组合模式">
            <el-select v-model="whForm.combineMode" style="width: 100%">
              <el-option label="SINGLE_ROUTE（单仓路由）" value="SINGLE_ROUTE" />
              <el-option label="COHORT_UNION（互斥队列联合）" value="COHORT_UNION" />
              <el-option label="ENTITY_FEDERATED_JOIN（共享主键任意 Join）" value="ENTITY_FEDERATED_JOIN" />
            </el-select>
          </el-form-item>
          <el-form-item label="联邦引擎">
            <el-select v-model="whForm.federationEngine" style="width: 100%">
              <el-option label="AUTO（自动优选）" value="AUTO" />
              <el-option label="STARROCKS（External Catalog）" value="STARROCKS" />
              <el-option label="DUCKDB（嵌入式内存联邦）" value="DUCKDB" />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-grid">
          <el-form-item label="StarRocks External Catalog 名（可选）">
            <el-input v-model="whForm.starrocksCatalogName" placeholder="如 pg_his_cat" />
          </el-form-item>
          <el-form-item label="选仓优先级（越小越优先）">
            <el-input-number v-model="whForm.priority" :min="1" :max="999" style="width: 100%" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="whDialog = false">取消</el-button>
        <el-button type="primary" :loading="whSaving" @click="saveWarehouse">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="ltDialog" title="导入逻辑表（Warehouse Catalog）" width="520px">
      <el-form label-position="top">
        <el-form-item label="所属仓库" required>
          <el-select v-model="ltForm.warehouseId" style="width: 100%">
            <el-option v-for="w in warehouses" :key="w.id" :label="`${w.name} (${w.code})`" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="底层物理表（Catalog Dataset）" required>
          <DatasetSearchSelect v-model="ltForm.catalogDatasetId" placeholder="搜索物理表" />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="逻辑表名" required>
            <el-input v-model="ltForm.logicalName" placeholder="如 lt_mimic_icustays" />
          </el-form-item>
          <el-form-item label="表角色">
            <el-select v-model="ltForm.tableRole" style="width: 100%">
              <el-option label="FACT（事实表）" value="FACT" />
              <el-option label="LOOKUP（主维表）" value="LOOKUP" />
              <el-option label="BRIDGE（桥接表）" value="BRIDGE" />
              <el-option label="VIEW_SQL（受限逻辑视图）" value="VIEW_SQL" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="Logical Size（选表优先级权重，越小越优先）">
          <el-input-number v-model="ltForm.logicalSize" :min="1" :max="99999" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ltDialog = false">取消</el-button>
        <el-button type="primary" :loading="ltSaving" @click="saveLogicalTable">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.wh-catalog {
  display: flex;
  flex-direction: column;
  gap: var(--dg-space-lg);
}

.wh-section {
  display: flex;
  flex-direction: column;
  gap: var(--dg-space-sm);
}

.wh-section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  flex-wrap: wrap;
}

.wh-section-head h4 {
  margin: 0 0 2px;
  font-size: 14px;
  color: var(--dg-text);
}

.wh-tools {
  display: flex;
  align-items: center;
  gap: var(--dg-space-sm);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--dg-space-md);
}

.bold {
  font-weight: 600;
  color: var(--dg-text);
}

.mono {
  font-family: var(--dg-font-mono);
  font-size: 12px;
}

.muted {
  font-size: 12px;
  color: var(--dg-text-muted);
}
</style>
