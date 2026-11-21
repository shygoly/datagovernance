<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { dataSourceApi } from '@/api/datasource'
import { useEtlSchema } from '@/composables/useEtlSchema'
import type { DataSource } from '@/types/datasource'
import type { EtlColumn, EtlWriteMode } from '@/types/etl'
import type { EtlFlowNode } from '@/utils/etl'
import { kindMeta } from '@/utils/etl'

/**
 * 节点配置抽屉(右侧)。
 *
 * 编辑是「即时生效」的:抽屉里改一个字段,画布节点与待保存的 IR 立刻跟着变,
 * 没有「应用」按钮 —— 这样底部预览永远预览的是你眼前看到的配置。
 * 真正落库仍然要点工具栏的「保存」。
 *
 * 表单只覆盖 Phase 1 最常用的五类节点(数据源 / 过滤 / 选列 / 自定义 SQL / 数据库目标);
 * 其它种类退化为 JSON 编辑,字段含义以后端 EtlPipelineCompiler 为准。
 */

// 节点 config 的结构由 kind 决定,是后端定义的松散 Map;这里刻意用宽松类型,
// 严格的形状校验由后端编译器负责并通过「编译诊断」页签回报。
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Cfg = Record<string, any>

const props = defineProps<{
  modelValue: boolean
  node: EtlFlowNode | null
  /** 直接上游节点的推演列;null 表示无法推断 */
  upstreamColumns: EtlColumn[] | null
  upstreamIds: string[]
  readonly?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  update: [payload: { id: string; name: string; config: Record<string, unknown> }]
  remove: [id: string]
}>()

const schema = useEtlSchema()

const draft = reactive<{ name: string; config: Cfg }>({ name: '', config: {} })
let syncing = false

const kind = computed(() => props.node?.data?.kind ?? null)
const meta = computed(() => (kind.value ? kindMeta(kind.value) : null))

// ── 数据源与库表级联 ───────────────────────────────────────────────

const dataSources = ref<DataSource[]>([])
let dataSourcesLoaded = false

async function loadDataSources() {
  if (dataSourcesLoaded) return
  dataSourcesLoaded = true
  try {
    dataSources.value = (await dataSourceApi.list({ page: 1, size: 200 })).records
  } catch {
    dataSourcesLoaded = false
  }
}

const databaseOptions = ref<string[]>([])
const schemaOptions = ref<string[]>([])
const tableOptions = ref<string[]>([])
const browsing = ref(false)

/**
 * 按已填的 database / schema 往下浏览一层。不预设数据源有没有 schema 这一层:
 * 后端返回哪层就填哪层,拿不到(权限 / 不支持)就保持空,表单仍可手填。
 */
async function browse() {
  const dsId = draft.config.dataSourceId
  if (!dsId) {
    databaseOptions.value = []
    schemaOptions.value = []
    tableOptions.value = []
    return
  }
  browsing.value = true
  try {
    const page = await dataSourceApi.browseCatalog(dsId, {
      database: draft.config.database || undefined,
      schema: draft.config.schema || undefined,
    })
    if (page.databases.length) databaseOptions.value = page.databases.map((d) => d.name)
    if (page.schemas.length) schemaOptions.value = page.schemas.map((s) => s.name)
    tableOptions.value = page.tables.map((t) => t.name)
  } catch {
    tableOptions.value = []
  } finally {
    browsing.value = false
  }
}

const isDbNode = computed(() => kind.value === 'SOURCE_JDBC' || kind.value === 'SINK_JDBC')

/** 当前节点所选表的列(源表 / 目标表) */
const tableColumns = computed<EtlColumn[]>(() => schema.columnsOf(draft.config) ?? [])

function onDataSourceChange() {
  draft.config.database = ''
  draft.config.schema = ''
  draft.config.table = ''
  databaseOptions.value = []
  schemaOptions.value = []
  tableOptions.value = []
  void browse()
}

function onDatabaseChange() {
  draft.config.schema = ''
  draft.config.table = ''
  void browse()
}

function onSchemaChange() {
  draft.config.table = ''
  void browse()
}

function onTableChange() {
  void schema.ensure(draft.config)
}

// ── 同步:node → draft ─────────────────────────────────────────────

const mapRows = ref<Array<{ src: string; target: string }>>([])

watch(
  () => [props.modelValue, props.node?.id] as const,
  () => {
    if (!props.modelValue || !props.node) return
    syncing = true
    draft.name = props.node.data!.name
    draft.config = JSON.parse(JSON.stringify(props.node.data!.config)) as Cfg
    mapRows.value = Object.entries((draft.config.columnMapping ?? {}) as Record<string, string>).map(
      ([src, target]) => ({ src, target: String(target ?? '') }),
    )
    jsonText.value = JSON.stringify(draft.config, null, 2)
    jsonError.value = ''
    databaseOptions.value = []
    schemaOptions.value = []
    tableOptions.value = []
    // 让 deep watch 的这一轮触发先过去,再放开写回
    queueMicrotask(() => {
      syncing = false
    })
    if (isDbNode.value) {
      void loadDataSources()
      void browse()
      void schema.ensure(draft.config)
    }
  },
  { immediate: true },
)

// ── 写回:draft → 画布 ─────────────────────────────────────────────

watch(
  draft,
  () => {
    if (syncing || !props.node || props.readonly) return
    emit('update', {
      id: props.node.id,
      name: draft.name,
      config: JSON.parse(JSON.stringify(draft.config)) as Record<string, unknown>,
    })
  },
  { deep: true },
)

// ── 数据源节点:增量 ───────────────────────────────────────────────

const incremental = computed<Cfg>(() => (draft.config.incremental ?? {}) as Cfg)
const incrementalOn = computed({
  get: () => !!draft.config.incremental,
  set: (on: boolean) => {
    draft.config.incremental = on ? { column: '', type: '' } : undefined
  },
})

function setIncremental(key: string, value: string) {
  draft.config.incremental = { ...incremental.value, [key]: value }
}

// ── 选列 / 字段映射 ───────────────────────────────────────────────

const selectRows = computed<Array<{ name: string; as?: string }>>(() => {
  if (!Array.isArray(draft.config.columns)) draft.config.columns = []
  return draft.config.columns as Array<{ name: string; as?: string }>
})

function addSelectRow() {
  selectRows.value.push({ name: '', as: '' })
}

function fillSelectFromUpstream() {
  if (!props.upstreamColumns) return
  draft.config.columns = props.upstreamColumns.map((c) => ({ name: c.name, as: '' }))
}

// ── 目标:字段映射 ────────────────────────────────────────────────

const writeMode = computed({
  get: () => (draft.config.writeMode ?? 'APPEND') as EtlWriteMode,
  set: (v: EtlWriteMode) => {
    draft.config.writeMode = v
  },
})

function syncMapping() {
  const out: Record<string, string> = {}
  for (const r of mapRows.value) {
    if (r.src && r.target) out[r.src] = r.target
  }
  draft.config.columnMapping = out
}

function addMapRow() {
  mapRows.value.push({ src: '', target: '' })
}

function removeMapRow(i: number) {
  mapRows.value.splice(i, 1)
  syncMapping()
}

/** 按同名(忽略大小写)把上游列映射到目标表的列;对不上的不猜,留给用户 */
function autoMap() {
  const targets = tableColumns.value
  if (!props.upstreamColumns || !targets.length) {
    ElMessage.warning('需要先选好目标表,且上游列可推演,才能自动映射')
    return
  }
  const byLower = new Map(targets.map((t) => [t.name.toLowerCase(), t.name]))
  mapRows.value = props.upstreamColumns
    .filter((c) => byLower.has(c.name.toLowerCase()))
    .map((c) => ({ src: c.name, target: byLower.get(c.name.toLowerCase())! }))
  syncMapping()
  const miss = props.upstreamColumns.length - mapRows.value.length
  ElMessage.success(miss > 0 ? `已映射 ${mapRows.value.length} 列,${miss} 列同名对不上` : '已按同名映射全部列')
}

// ── 自定义 SQL ───────────────────────────────────────────────────

/** 模板插值里写不出字面量的双花括号,所以占位符文本在脚本里拼 */
function placeholderOf(id: string): string {
  return `{{${id}}}`
}

function insertRef(id: string) {
  const sql = String(draft.config.sql ?? '')
  draft.config.sql = `${sql}${sql && !/\s$/.test(sql) ? ' ' : ''}${placeholderOf(id)}`
}

// ── 其它种类:JSON ────────────────────────────────────────────────

const jsonText = ref('')
const jsonError = ref('')

function onJsonInput(text: string) {
  jsonText.value = text
  try {
    const parsed = JSON.parse(text)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      jsonError.value = '配置必须是 JSON 对象'
      return
    }
    jsonError.value = ''
    draft.config = parsed as Cfg
  } catch (e) {
    jsonError.value = e instanceof Error ? e.message : 'JSON 无法解析'
  }
}

const upstreamNames = computed(() => (props.upstreamColumns ?? []).map((c) => c.name))
const disabled = computed(() => !!props.readonly)
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    direction="rtl"
    size="440px"
    :modal="false"
    :close-on-click-modal="false"
    :title="node ? `节点配置 · ${meta?.label}` : '节点配置'"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form v-if="node && meta" label-position="top" :disabled="disabled" class="etl-drawer">
      <el-form-item label="节点 ID">
        <el-input :model-value="node.id" disabled class="text-mono" />
        <div class="text-muted">ID 会出现在自定义 SQL 的 {{ placeholderOf('id') }} 占位符与血缘里,创建后不可改</div>
      </el-form-item>
      <el-form-item label="显示名称">
        <el-input v-model="draft.name" placeholder="留空则显示节点 ID" maxlength="64" />
      </el-form-item>

      <!-- 数据库源 / 目标共用的数据源 + 库表选择 -->
      <template v-if="isDbNode">
        <el-form-item label="数据源" required>
          <el-select
            v-model="draft.config.dataSourceId"
            filterable
            placeholder="选择数据源"
            style="width: 100%"
            @visible-change="(v: boolean) => v && loadDataSources()"
            @change="onDataSourceChange"
          >
            <el-option v-for="ds in dataSources" :key="ds.id" :label="ds.name" :value="ds.id">
              <span>{{ ds.name }}</span>
              <span class="text-muted etl-drawer__opt-hint">{{ ds.typeDisplayName }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-row :gutter="8">
          <el-col :span="12">
            <el-form-item label="库 (database)">
              <el-select
                v-model="draft.config.database"
                filterable
                allow-create
                clearable
                placeholder="可选"
                style="width: 100%"
                @change="onDatabaseChange"
              >
                <el-option v-for="d in databaseOptions" :key="d" :label="d" :value="d" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="模式 (schema)">
              <el-select
                v-model="draft.config.schema"
                filterable
                allow-create
                clearable
                placeholder="可选"
                style="width: 100%"
                @change="onSchemaChange"
              >
                <el-option v-for="s in schemaOptions" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item :label="kind === 'SOURCE_JDBC' ? '源表' : '目标表'" required>
          <el-select
            v-model="draft.config.table"
            filterable
            allow-create
            :loading="browsing"
            placeholder="选择或输入表名"
            style="width: 100%"
            @change="onTableChange"
          >
            <el-option v-for="t in tableOptions" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
      </template>

      <!-- Source -->
      <template v-if="kind === 'SOURCE_JDBC'">
        <el-form-item label="抽取列">
          <el-select
            v-model="draft.config.columns"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="留空表示抽取全部列"
            style="width: 100%"
          >
            <el-option v-for="c in tableColumns" :key="c.name" :label="c.name" :value="c.name">
              <span>{{ c.name }}</span>
              <span class="text-muted etl-drawer__opt-hint">{{ c.type }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="源端过滤 (whereClause)">
          <el-input
            v-model="draft.config.whereClause"
            type="textarea"
            :rows="2"
            placeholder="如 status = 'ACTIVE' —— 下推到源库执行,减少抽取量"
          />
        </el-form-item>
        <el-form-item label="增量抽取">
          <el-switch v-model="incrementalOn" />
          <div class="text-muted etl-drawer__block">
            开启后按水位线字段只抽新增数据;水位线在任务成功后推进
          </div>
        </el-form-item>
        <template v-if="incrementalOn">
          <el-form-item label="水位线字段" required>
            <el-select
              :model-value="incremental.column"
              filterable
              allow-create
              placeholder="如 updated_at"
              style="width: 100%"
              @update:model-value="setIncremental('column', $event)"
            >
              <el-option v-for="c in tableColumns" :key="c.name" :label="c.name" :value="c.name" />
            </el-select>
          </el-form-item>
          <el-row :gutter="8">
            <el-col :span="12">
              <el-form-item label="水位线类型">
                <el-select
                  :model-value="incremental.type"
                  clearable
                  placeholder="按字段类型推断"
                  style="width: 100%"
                  @update:model-value="setIncremental('type', $event ?? '')"
                >
                  <el-option label="TIMESTAMP" value="TIMESTAMP" />
                  <el-option label="NUMBER" value="NUMBER" />
                  <el-option label="STRING" value="STRING" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="初始值">
                <el-input
                  :model-value="incremental.initialValue"
                  placeholder="首次抽取起点"
                  @update:model-value="setIncremental('initialValue', $event)"
                />
              </el-form-item>
            </el-col>
          </el-row>
        </template>
      </template>

      <!-- Filter -->
      <template v-else-if="kind === 'TRANSFORM_FILTER'">
        <el-form-item label="过滤条件 (condition)" required>
          <el-input
            v-model="draft.config.condition"
            type="textarea"
            :rows="4"
            placeholder="DuckDB SQL 布尔表达式,如 amount > 0 AND status = 'PAID'"
          />
        </el-form-item>
        <div v-if="upstreamNames.length" class="etl-drawer__cols">
          <span class="text-muted">可用字段:</span>
          <el-tag v-for="c in upstreamNames" :key="c" size="small" type="info" effect="plain">{{ c }}</el-tag>
        </div>
      </template>

      <!-- Select / 字段映射 -->
      <template v-else-if="kind === 'TRANSFORM_SELECT'">
        <div class="etl-drawer__row-head">
          <span>字段映射(只保留列出的字段,可改名)</span>
          <el-button v-if="upstreamColumns" link type="primary" @click="fillSelectFromUpstream">
            带入全部上游字段
          </el-button>
        </div>
        <div v-for="(row, i) in selectRows" :key="i" class="etl-drawer__map-row">
          <el-select
            v-model="row.name"
            filterable
            allow-create
            placeholder="源字段"
            style="flex: 1"
          >
            <el-option v-for="c in upstreamNames" :key="c" :label="c" :value="c" />
          </el-select>
          <span class="text-muted">→</span>
          <el-input v-model="row.as" placeholder="改名(可选)" style="flex: 1" />
          <el-button link type="danger" @click="selectRows.splice(i, 1)">删除</el-button>
        </div>
        <el-button size="small" @click="addSelectRow">添加字段</el-button>
        <div v-if="!upstreamColumns" class="text-muted etl-drawer__block">
          上游字段无法静态推断(上游未配置完整或经过自定义 SQL),请手动输入字段名
        </div>
      </template>

      <!-- Custom SQL -->
      <template v-else-if="kind === 'TRANSFORM_CUSTOM_SQL'">
        <el-form-item label="SQL" required>
          <el-input
            v-model="draft.config.sql"
            type="textarea"
            :rows="10"
            class="etl-drawer__sql"
            placeholder="SELECT a.id, b.name FROM {{src_1}} a JOIN {{src_2}} b ON a.id = b.id"
          />
        </el-form-item>
        <div class="etl-drawer__cols">
          <span class="text-muted">点击插入上游引用:</span>
          <el-tag
            v-for="id in upstreamIds"
            :key="id"
            size="small"
            type="warning"
            effect="plain"
            class="etl-drawer__ref"
            @click="insertRef(id)"
          >
            {{ placeholderOf(id) }}
          </el-tag>
          <span v-if="!upstreamIds.length" class="text-muted">先把上游节点连到本节点</span>
        </div>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          class="etl-drawer__block"
          title="只允许单条 SELECT / WITH;不能访问文件或外部连接。是否合规以「编译诊断」为准"
        />
      </template>

      <!-- Sink -->
      <template v-else-if="kind === 'SINK_JDBC'">
        <el-form-item label="写入模式 (writeMode)" required>
          <el-radio-group v-model="writeMode">
            <el-radio-button value="APPEND">追加</el-radio-button>
            <el-radio-button value="UPSERT">按主键更新</el-radio-button>
            <el-radio-button value="OVERWRITE">覆盖</el-radio-button>
          </el-radio-group>
          <el-alert
            v-if="writeMode === 'OVERWRITE'"
            type="warning"
            :closable="false"
            show-icon
            class="etl-drawer__block"
            title="覆盖会在写入前清空目标表;增量抽取请用追加或按主键更新"
          />
        </el-form-item>
        <el-form-item v-if="writeMode === 'UPSERT'" label="主键字段 (primaryKeys)" required>
          <el-select
            v-model="draft.config.primaryKeys"
            multiple
            filterable
            allow-create
            placeholder="目标表主键列"
            style="width: 100%"
          >
            <el-option v-for="c in tableColumns" :key="c.name" :label="c.name" :value="c.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="写入批次 (batchSize)">
          <el-input-number v-model="draft.config.batchSize" :min="1" :max="100000" :step="500" controls-position="right" />
        </el-form-item>

        <div class="etl-drawer__row-head">
          <span>字段映射(留空 = 同名直写)</span>
          <el-button link type="primary" @click="autoMap">按同名自动映射</el-button>
        </div>
        <div v-for="(row, i) in mapRows" :key="i" class="etl-drawer__map-row">
          <el-select
            v-model="row.src"
            filterable
            allow-create
            placeholder="上游字段"
            style="flex: 1"
            @change="syncMapping"
          >
            <el-option v-for="c in upstreamNames" :key="c" :label="c" :value="c" />
          </el-select>
          <span class="text-muted">→</span>
          <el-select
            v-model="row.target"
            filterable
            allow-create
            placeholder="目标字段"
            style="flex: 1"
            @change="syncMapping"
          >
            <el-option v-for="c in tableColumns" :key="c.name" :label="c.name" :value="c.name">
              <span>{{ c.name }}</span>
              <span class="text-muted etl-drawer__opt-hint">{{ c.type }}</span>
            </el-option>
          </el-select>
          <el-button link type="danger" @click="removeMapRow(i)">删除</el-button>
        </div>
        <el-button size="small" @click="addMapRow">添加映射</el-button>
      </template>

      <!-- 无专用表单:JSON -->
      <template v-else>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="该节点暂无专用表单,请直接编辑配置 JSON;字段含义见 etl.ir/v1 规约"
          class="etl-drawer__block"
        />
        <el-form-item label="config (JSON)">
          <el-input
            :model-value="jsonText"
            type="textarea"
            :rows="14"
            class="etl-drawer__sql"
            @update:model-value="onJsonInput"
          />
          <div v-if="jsonError" class="etl-drawer__error">{{ jsonError }}</div>
        </el-form-item>
      </template>
    </el-form>

    <template #footer>
      <el-button
        v-if="node && !readonly"
        type="danger"
        plain
        @click="emit('remove', node.id)"
      >
        删除节点
      </el-button>
      <el-button @click="emit('update:modelValue', false)">收起</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.etl-drawer__opt-hint {
  float: right;
  margin-left: var(--dg-space-md);
}

.etl-drawer__block {
  margin-top: var(--dg-space-sm);
  width: 100%;
}

.etl-drawer__cols {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--dg-space-xs);
  margin-bottom: var(--dg-space-sm);
}

.etl-drawer__ref {
  cursor: pointer;
  font-family: var(--dg-font-mono);
}

.etl-drawer__row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--dg-space-sm);
  font-size: 13px;
  color: var(--dg-text-secondary);
}

.etl-drawer__map-row {
  display: flex;
  align-items: center;
  gap: var(--dg-space-sm);
  margin-bottom: var(--dg-space-sm);
}

.etl-drawer__sql :deep(textarea) {
  font-family: var(--dg-font-mono);
  font-size: 12px;
}

.etl-drawer__error {
  margin-top: var(--dg-space-xs);
  font-size: 12px;
  color: var(--dg-danger);
}
</style>
