<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { etlApi } from '@/api/etl'
import { useAuthStore } from '@/stores/auth'
import type { EtlIr, EtlPreviewResponse, EtlWatermark } from '@/types/etl'
import type { CompileDiagnostic } from '@/types/job'
import type { EtlFlowNode } from '@/utils/etl'
import { kindMeta } from '@/utils/etl'
import { formatDateTime } from '@/utils/format'

/**
 * 画布底部三页签:样本预览 / 编译诊断 / 增量水位线。
 *
 * - 预览:把<b>当前画布</b>序列化出的 IR 直接送后端(含未保存改动),不必先保存;
 *   后端强制 100 行、15 秒、永不写目标端,并对 L3/L4 敏感列按列名兜底脱敏。
 * - 编译诊断:复用任务编译接口。编译针对「已保存的定义」,所以由父组件负责
 *   「先保存再编译」,这里只展示结果并支持点击定位到节点。
 * - 水位线:声明的增量配置(来自画布 IR)与运行态(GET /control/etl/{jobId}/watermarks:
 *   当前水位、最近推进的执行)并排展示;有 control:etl:manage 权限时可手工重置(必须填原因,
 *   后端写审计)。任务尚未保存时没有运行态,明确说明而不是显示空表。
 */

const props = defineProps<{
  modelValue: string
  /** 已保存任务的 ID;新建未保存时为 null(没有水位线运行态) */
  jobId: string | null
  nodes: EtlFlowNode[]
  selectedNodeId: string | null
  buildIr: () => EtlIr
  diagnostics: CompileDiagnostic[]
  compileSummary: string
  compileSucceeded: boolean | null
  compiling: boolean
  canCompile: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [tab: string]
  compile: []
  focusNode: [nodeId: string]
}>()

const auth = useAuthStore()
const canManage = computed(() => auth.hasPermission('control:etl:manage'))
const canPreview = computed(
  () => auth.hasPermission('control:etl:read') || auth.hasPermission('control:etl:manage'),
)

// ── 预览 ──────────────────────────────────────────────────────────

const previewNodeId = ref<string>('')
const previewing = ref(false)
const preview = ref<EtlPreviewResponse | null>(null)
const previewError = ref('')

/** 跟随画布选中:点哪个节点,预览目标就切到哪个节点;没选中时留空 = 后端默认预览 Sink 的上游 */
watch(
  () => props.selectedNodeId,
  (id) => {
    if (id) previewNodeId.value = id
  },
)

watch(
  () => props.nodes.map((n) => n.id).join(','),
  () => {
    if (previewNodeId.value && !props.nodes.some((n) => n.id === previewNodeId.value)) {
      previewNodeId.value = ''
    }
  },
)

async function runPreview() {
  if (!props.nodes.length) {
    ElMessage.warning('画布上还没有节点')
    return
  }
  previewing.value = true
  previewError.value = ''
  try {
    preview.value = await etlApi.preview({
      ir: props.buildIr() as unknown as Record<string, unknown>,
      nodeId: previewNodeId.value || undefined,
    })
  } catch (e) {
    // 请求拦截器已弹出错误;这里留一份在面板里,弹窗消失后用户还能回看原因
    preview.value = null
    previewError.value = (e as { message?: string })?.message ?? '预览失败'
  } finally {
    previewing.value = false
  }
}

function cell(value: unknown): string {
  if (value === null || value === undefined) return ''
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

const maskedSet = computed(() => new Set(preview.value?.maskedColumns ?? []))

// ── 诊断 ──────────────────────────────────────────────────────────

const errors = computed(() => props.diagnostics.filter((d) => d.severity === 'ERROR'))
const warnings = computed(() => props.diagnostics.filter((d) => d.severity === 'WARNING'))

function nodeIdOf(d: CompileDiagnostic): string | null {
  const m = d.location?.match(/^nodes\.([A-Za-z][A-Za-z0-9_]*)/)
  return m && props.nodes.some((n) => n.id === m[1]) ? m[1] : null
}

// ── 水位线 ────────────────────────────────────────────────────────

const watermarks = ref<EtlWatermark[]>([])
const watermarkLoading = ref(false)
const watermarkError = ref('')
const resettingNodeId = ref('')

async function loadWatermarks() {
  if (!props.jobId || !canPreview.value) {
    watermarks.value = []
    return
  }
  watermarkLoading.value = true
  watermarkError.value = ''
  try {
    watermarks.value = await etlApi.watermarks(props.jobId)
  } catch (e) {
    watermarks.value = []
    watermarkError.value = (e as { message?: string })?.message ?? '水位线加载失败'
  } finally {
    watermarkLoading.value = false
  }
}

// 任务切换 / 保存后(jobId 从 null 变成 ID)重新取;切到水位线页签时也刷新一次,看到的是最新推进
watch(() => props.jobId, () => void loadWatermarks(), { immediate: true })
watch(
  () => props.modelValue,
  (tab) => {
    if (tab === 'watermark') void loadWatermarks()
  },
)

interface WatermarkRow {
  nodeId: string
  table: string
  column: string
  type: string
  initialValue: string
  /** 运行态;null = 尚无记录(从未成功推进过) */
  runtime: EtlWatermark | null
  /** 画布上已没有对应的增量源节点(水位线记录还在) */
  orphan: boolean
}

const incrementalRows = computed<WatermarkRow[]>(() => {
  const byNode = new Map(watermarks.value.map((w) => [w.sourceNodeId, w]))
  const rows: WatermarkRow[] = props.nodes
    .filter((n) => n.data!.kind === 'SOURCE_JDBC' && n.data!.config.incremental)
    .map((n) => {
      const inc = n.data!.config.incremental as Record<string, string>
      return {
        nodeId: n.id,
        table: String(n.data!.config.table ?? ''),
        column: inc.column ?? '',
        type: inc.type || '自动推断',
        initialValue: inc.initialValue ?? '',
        runtime: byNode.get(n.id) ?? null,
        orphan: false,
      }
    })
  const declared = new Set(rows.map((r) => r.nodeId))
  for (const w of watermarks.value) {
    if (!declared.has(w.sourceNodeId)) {
      rows.push({
        nodeId: w.sourceNodeId,
        table: '',
        column: w.watermarkColumn ?? '',
        type: w.watermarkType ?? '',
        initialValue: '',
        runtime: w,
        orphan: true,
      })
    }
  }
  return rows
})

async function resetWatermark(row: WatermarkRow) {
  if (!props.jobId) return
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      `重置后,节点「${row.nodeId}」下次执行会从头抽取(或重抽重置点之后的数据)。请说明原因,将写入审计。`,
      '重置水位线',
      {
        confirmButtonText: '重置',
        confirmButtonClass: 'el-button--danger',
        cancelButtonText: '取消',
        inputPlaceholder: '例如:源表补数后需要全量重抽',
        inputValidator: (v: string) => (v && v.trim() ? true : '必须填写原因'),
        type: 'warning',
      },
    )
    reason = result.value.trim()
  } catch {
    return // 取消
  }
  resettingNodeId.value = row.nodeId
  try {
    await etlApi.resetWatermark(props.jobId, row.nodeId, { reason })
    ElMessage.success('水位线已重置')
    await loadWatermarks()
  } finally {
    resettingNodeId.value = ''
  }
}
</script>

<template>
  <el-tabs
    :model-value="modelValue"
    class="etl-panel"
    @update:model-value="emit('update:modelValue', String($event))"
  >
    <!-- 预览 -->
    <el-tab-pane label="样本预览" name="preview">
      <div class="etl-panel__bar">
        <el-select
          v-model="previewNodeId"
          clearable
          placeholder="默认预览 Sink 上游"
          style="width: 240px"
        >
          <el-option v-for="n in nodes" :key="n.id" :value="n.id" :label="`${n.data!.name || n.id}(${n.id})`">
            <span>{{ n.data!.name || n.id }}</span>
            <span class="text-muted etl-panel__opt-hint">{{ kindMeta(n.data!.kind).label }}</span>
          </el-option>
        </el-select>
        <el-button type="primary" :loading="previewing" :disabled="!canPreview" @click="runPreview">
          运行预览
        </el-button>
        <span class="text-muted">最多 100 行 · 15 秒超时 · 不写目标端 · L3/L4 敏感列自动脱敏</span>
        <span v-if="!canPreview" class="text-muted">(缺少 control:etl:read 权限)</span>
      </div>

      <el-alert v-if="previewError" type="error" :closable="false" show-icon :title="previewError" />

      <template v-if="preview">
        <div class="etl-panel__meta">
          <span>节点 <span class="text-mono">{{ preview.nodeId }}</span></span>
          <span>{{ preview.rows.length }} 行{{ preview.truncated ? '(已截断)' : '' }}</span>
          <span>耗时 {{ preview.elapsedMillis }} ms</span>
          <el-tag v-for="c in preview.maskedColumns" :key="c" size="small" type="warning">
            {{ c }} 已脱敏
          </el-tag>
        </div>
        <el-table :data="preview.rows" size="small" max-height="200" border>
          <el-table-column
            v-for="c in preview.columns"
            :key="c.name"
            :prop="c.name"
            :min-width="120"
            show-overflow-tooltip
          >
            <template #header>
              <div>{{ c.name }}</div>
              <div class="text-muted">{{ c.type }}</div>
            </template>
            <template #default="{ row }">
              <span :class="{ 'etl-panel__masked': maskedSet.has(c.name) }">
                {{ cell((row as Record<string, unknown>)[c.name]) }}
              </span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty
        v-else-if="!previewError"
        :image-size="48"
        description="选中画布上的节点,点击「运行预览」查看该节点的输出样本"
      />
    </el-tab-pane>

    <!-- 编译诊断 -->
    <el-tab-pane name="diagnostics">
      <template #label>
        编译诊断
        <el-badge v-if="errors.length" :value="errors.length" type="danger" class="etl-panel__badge" />
        <el-badge
          v-else-if="warnings.length"
          :value="warnings.length"
          type="warning"
          class="etl-panel__badge"
        />
      </template>
      <div class="etl-panel__bar">
        <el-button type="primary" :loading="compiling" :disabled="!canCompile" @click="emit('compile')">
          保存并编译
        </el-button>
        <span class="text-muted">编译针对已保存的定义,点击后会先保存当前画布</span>
      </div>

      <el-alert
        v-if="compileSummary"
        :type="compileSucceeded ? (warnings.length ? 'warning' : 'success') : 'error'"
        :closable="false"
        show-icon
        :title="compileSummary"
        class="etl-panel__summary"
      />
      <el-empty v-else :image-size="48" description="尚未编译。编译会校验图结构、列是否存在与自定义 SQL 合规性" />

      <div class="etl-panel__diags">
        <div
          v-for="(d, i) in diagnostics"
          :key="i"
          class="etl-diag"
          :class="d.severity === 'ERROR' ? 'etl-diag--error' : 'etl-diag--warning'"
        >
          <div class="etl-diag__head">
            <el-tag :type="d.severity === 'ERROR' ? 'danger' : 'warning'" size="small">{{ d.stage }}</el-tag>
            <el-link
              v-if="nodeIdOf(d)"
              type="primary"
              class="text-mono"
              @click="emit('focusNode', nodeIdOf(d)!)"
            >
              {{ d.location }}
            </el-link>
            <span v-else-if="d.location" class="text-mono">{{ d.location }}</span>
          </div>
          <div>{{ d.message }}</div>
          <div v-if="d.hint" class="text-muted">建议:{{ d.hint }}</div>
        </div>
      </div>
    </el-tab-pane>

    <!-- 水位线 -->
    <el-tab-pane label="增量水位线" name="watermark">
      <div class="etl-panel__bar">
        <el-button :loading="watermarkLoading" :disabled="!jobId || !canPreview" @click="loadWatermarks">
          刷新
        </el-button>
        <span v-if="!jobId" class="text-muted">任务尚未保存,保存并成功执行一次后才会产生水位线</span>
        <span v-else-if="!canPreview" class="text-muted">(缺少 control:etl:read 权限,无法查看当前水位)</span>
        <span v-else class="text-muted">水位线只在整条管道成功写入目标后推进;重置需要原因并写入审计</span>
      </div>
      <el-alert v-if="watermarkError" type="error" :closable="false" show-icon :title="watermarkError" />
      <el-table :data="incrementalRows" size="small" class="etl-panel__wm" border v-loading="watermarkLoading">
        <el-table-column prop="nodeId" label="数据源节点" width="140" />
        <el-table-column prop="table" label="源表" min-width="140" />
        <el-table-column prop="column" label="水位线字段" min-width="120" />
        <el-table-column prop="type" label="类型" width="100" />
        <el-table-column prop="initialValue" label="初始值" min-width="120" />
        <el-table-column label="当前水位" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="(row as WatermarkRow).runtime">
              <span v-if="(row as WatermarkRow).runtime!.lastValue" class="text-mono">
                {{ (row as WatermarkRow).runtime!.lastValue }}
              </span>
              <span v-else class="text-muted">已清空(下次全量)</span>
            </template>
            <span v-else class="text-muted">{{ jobId ? '尚无记录' : '-' }}</span>
            <el-tag v-if="(row as WatermarkRow).orphan" size="small" type="warning" class="etl-panel__badge">
              画布无此节点
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近推进" min-width="170">
          <template #default="{ row }">
            <template v-if="(row as WatermarkRow).runtime">
              <div>{{ formatDateTime((row as WatermarkRow).runtime!.updatedAt) }}</div>
              <div v-if="(row as WatermarkRow).runtime!.lastExecutionId" class="text-muted text-mono">
                {{ (row as WatermarkRow).runtime!.lastExecutionId }}
              </div>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="danger"
              :disabled="!canManage || !(row as WatermarkRow).runtime"
              :loading="resettingNodeId === (row as WatermarkRow).nodeId"
              @click="resetWatermark(row as WatermarkRow)"
            >
              重置
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <span class="text-muted">没有开启增量抽取的数据源节点(全量抽取不产生水位线)</span>
        </template>
      </el-table>
    </el-tab-pane>
  </el-tabs>
</template>

<style scoped>
.etl-panel {
  height: 100%;
  padding: 0 var(--dg-space-md);
}

.etl-panel :deep(.el-tabs__content) {
  height: calc(100% - 55px);
  overflow: auto;
}

.etl-panel__bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--dg-space-sm);
  margin-bottom: var(--dg-space-sm);
}

.etl-panel__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--dg-space-md);
  margin: var(--dg-space-sm) 0;
  font-size: 12px;
  color: var(--dg-text-secondary);
}

.etl-panel__opt-hint {
  float: right;
  margin-left: var(--dg-space-md);
}

.etl-panel__masked {
  color: var(--dg-warning);
}

.etl-panel__badge {
  margin-left: var(--dg-space-xs);
}

.etl-panel__summary {
  margin-bottom: var(--dg-space-sm);
}

.etl-panel__diags {
  max-height: 160px;
  overflow: auto;
}

.etl-panel__wm {
  margin-top: var(--dg-space-sm);
}

.etl-diag {
  padding: var(--dg-space-sm) var(--dg-space-md);
  margin-bottom: var(--dg-space-sm);
  background: var(--dg-surface-muted);
  border-left: 3px solid var(--dg-border-strong);
  border-radius: 0 var(--dg-radius-sm) var(--dg-radius-sm) 0;
  font-size: 13px;
}

.etl-diag--error {
  border-left-color: var(--dg-danger);
}

.etl-diag--warning {
  border-left-color: var(--dg-warning);
}

.etl-diag__head {
  display: flex;
  align-items: center;
  gap: var(--dg-space-sm);
  margin-bottom: var(--dg-space-xs);
}
</style>
