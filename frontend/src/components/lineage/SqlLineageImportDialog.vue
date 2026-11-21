<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { lineageApi } from '@/api/lineage'
import type {
  SqlLineageImportResult,
  SqlLineagePreview,
  SqlLineageRequest,
  SqlParsedEdge,
  SqlParseDiagnostic,
} from '@/types/lineage'

/**
 * 粘贴 SQL → 解析预览 → 确认入库。
 * INCONCLUSIVE / ERROR 时只展示诊断，不允许导入（解析器不猜列）。
 * 调用方负责用 metadata:lineage:manage 控制入口。
 */
const emit = defineEmits<{ imported: [result: SqlLineageImportResult] }>()

const dialects = [
  { value: 'postgres', label: 'PostgreSQL' },
  { value: 'mysql', label: 'MySQL' },
  { value: 'doris', label: 'Doris' },
  { value: 'starrocks', label: 'StarRocks' },
  { value: 'oracle', label: 'Oracle' },
  { value: 'spark', label: 'Spark SQL' },
  { value: 'hive', label: 'Hive' },
  { value: 'tsql', label: 'SQL Server (T-SQL)' },
]

const visible = ref(false)
const previewing = ref(false)
const importing = ref(false)
const dialect = ref('postgres')
const jobName = ref('')
const defaultSchema = ref('')
const sql = ref('')
const preview = ref<SqlLineagePreview | null>(null)
/** 预览对应的请求指纹；SQL/方言/Schema 变化后预览失效，必须重新预览才能导入。 */
const previewFingerprint = ref('')

const fingerprint = computed(
  () => `${dialect.value}\u0000${defaultSchema.value.trim()}\u0000${sql.value}`,
)
const previewStale = computed(
  () => !!preview.value && previewFingerprint.value !== fingerprint.value,
)
const flatEdges = computed<Array<SqlParsedEdge & { statement: string }>>(() =>
  (preview.value?.statements ?? []).flatMap((statement) =>
    (statement.edges ?? []).map((edge) => ({
      ...edge,
      statement: statement.targetTable || edge.targetTable,
    })),
  ),
)
const warnings = computed(() =>
  (preview.value?.statements ?? []).flatMap((statement) => statement.warnings ?? []),
)
const diagnostics = computed(() =>
  (preview.value?.diagnostics ?? []).map((item) => diagnosticText(item)),
)
const statusType = computed<'success' | 'warning' | 'error'>(() => {
  switch (preview.value?.status) {
    case 'OK':
      return 'success'
    case 'INCONCLUSIVE':
      return 'warning'
    default:
      return 'error'
  }
})
const statusTitle = computed(() => {
  switch (preview.value?.status) {
    case 'OK':
      return `解析成功：${flatEdges.value.length} 条列级血缘`
    case 'INCONCLUSIVE':
      return '无法确定列映射（INCONCLUSIVE），系统不会猜测列，因此不能导入'
    default:
      return 'SQL 解析失败，不能导入'
  }
})
const canPreview = computed(() => !!sql.value.trim() && !previewing.value)
const canImport = computed(
  () =>
    preview.value?.status === 'OK' &&
    flatEdges.value.length > 0 &&
    !previewStale.value &&
    !!jobName.value.trim(),
)

function diagnosticText(item: string | SqlParseDiagnostic): string {
  if (typeof item === 'string') {
    return item
  }
  return item.code ? `${item.code}: ${item.message}` : item.message
}

function request(): SqlLineageRequest {
  return {
    dialect: dialect.value,
    sql: sql.value,
    jobName: jobName.value.trim() || undefined,
    defaultSchema: defaultSchema.value.trim() || undefined,
  }
}

function open() {
  dialect.value = 'postgres'
  jobName.value = ''
  defaultSchema.value = ''
  sql.value = ''
  preview.value = null
  previewFingerprint.value = ''
  visible.value = true
}

async function onPreview() {
  if (!sql.value.trim()) {
    return
  }
  previewing.value = true
  try {
    preview.value = await lineageApi.parseSqlPreview(request())
    previewFingerprint.value = fingerprint.value
  } catch {
    // 错误提示由 request 拦截器统一弹出；保留旧预览会误导，所以清掉。
    preview.value = null
  } finally {
    previewing.value = false
  }
}

async function onImport() {
  if (!canImport.value) {
    return
  }
  importing.value = true
  try {
    const result = await lineageApi.importSql(request())
    ElMessage.success(`已导入 ${result.edges} 条列级血缘`)
    visible.value = false
    emit('imported', result)
  } finally {
    importing.value = false
  }
}

watch(visible, (value) => {
  if (!value) {
    preview.value = null
  }
})

defineExpose({ open })
</script>

<template>
  <el-dialog v-model="visible" title="SQL 解析导入血缘" width="860px" top="6vh" destroy-on-close>
    <el-form label-width="96px">
      <div class="sql-import__row">
        <el-form-item label="SQL 方言" required>
          <el-select v-model="dialect">
            <el-option
              v-for="item in dialects"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="默认 Schema">
          <el-input v-model="defaultSchema" placeholder="可选，例如 public" clearable />
        </el-form-item>
      </div>
      <el-form-item label="作业名称" required>
        <el-input
          v-model="jobName"
          maxlength="120"
          placeholder="导入后作为血缘作业名称，例如 dws_order_summary_daily"
        />
      </el-form-item>
      <el-form-item label="SQL" required>
        <el-input
          v-model="sql"
          type="textarea"
          :rows="9"
          class="sql-import__editor"
          placeholder="粘贴 INSERT INTO ... SELECT / CREATE VIEW AS SELECT 等语句"
        />
      </el-form-item>
    </el-form>

    <div v-if="preview" class="sql-import__result">
      <el-alert
        :type="statusType"
        show-icon
        :closable="false"
        :title="statusTitle"
      />
      <el-alert
        v-if="previewStale"
        type="info"
        show-icon
        :closable="false"
        class="sql-import__gap"
        title="SQL 或方言已修改，请重新解析预览后再导入"
      />

      <ul v-if="diagnostics.length" class="sql-import__diagnostics">
        <li v-for="(text, index) in diagnostics" :key="index">{{ text }}</li>
      </ul>
      <ul v-if="warnings.length" class="sql-import__diagnostics sql-import__diagnostics--warn">
        <li v-for="(text, index) in warnings" :key="index">{{ text }}</li>
      </ul>

      <el-table
        v-if="flatEdges.length"
        :data="flatEdges"
        size="small"
        border
        max-height="280"
        class="sql-import__gap"
      >
        <el-table-column label="源字段" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">{{ row.sourceTable }}.{{ row.sourceColumn }}</template>
        </el-table-column>
        <el-table-column label="目标字段" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">{{ row.targetTable }}.{{ row.targetColumn }}</template>
        </el-table-column>
        <el-table-column label="依赖" width="96">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="(row.dependencyType || 'DIRECT') === 'INDIRECT' ? 'info' : 'primary'"
            >
              {{ row.dependencyType || 'DIRECT' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="关系" width="96">
          <template #default="{ row }">{{ row.relation || '-' }}</template>
        </el-table-column>
        <el-table-column label="转换表达式" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <code v-if="row.transformExpr" class="sql-import__code">{{ row.transformExpr }}</code>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="脱敏" width="72" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.maskingApplied" size="small" type="warning">是</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button :loading="previewing" :disabled="!canPreview" @click="onPreview">
        解析预览
      </el-button>
      <el-button
        type="primary"
        :loading="importing"
        :disabled="!canImport"
        @click="onImport"
      >
        确认导入
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.sql-import__row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: var(--dg-space-lg);
}

.sql-import__editor :deep(textarea) {
  font-family: var(--dg-font-mono);
  font-size: 12px;
  line-height: 1.6;
}

.sql-import__gap {
  margin-top: var(--dg-space-md);
}

.sql-import__diagnostics {
  margin: var(--dg-space-sm) 0 0;
  padding: var(--dg-space-sm) var(--dg-space-md) var(--dg-space-sm) var(--dg-space-xl);
  background: var(--dg-warning-soft);
  border-radius: var(--dg-radius);
  font-size: 12px;
  color: var(--dg-text-secondary);
  line-height: 1.7;
}

.sql-import__diagnostics--warn {
  background: var(--dg-surface-muted);
}

.sql-import__code {
  font-family: var(--dg-font-mono);
  font-size: 12px;
  color: var(--dg-text);
}
</style>
