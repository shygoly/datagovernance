<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import type { Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { MarkerType, VueFlow, useVueFlow } from '@vue-flow/core'
import type { GraphNode, NodeMouseEvent } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'
import { jobApi } from '@/api/job'
import EtlBottomPanel from '@/components/etl/EtlBottomPanel.vue'
import EtlFlowNodeView from '@/components/etl/EtlFlowNode.vue'
import EtlNodeDrawer from '@/components/etl/EtlNodeDrawer.vue'
import { useEtlSchema } from '@/composables/useEtlSchema'
import { useAuthStore } from '@/stores/auth'
import type { EtlColumn, EtlErrorPolicy, EtlIr, EtlNodeKind } from '@/types/etl'
import type { CompileDiagnostic, JobDefinition } from '@/types/job'
import { confirmAction } from '@/utils/confirm'
import {
  ETL_KIND_META,
  ETL_PALETTE_GROUPS,
  checkConnection,
  diagnosticsByNode,
  flowToIr,
  inferColumns,
  irEdgeId,
  irToFlow,
  kindMeta,
  newNodeId,
  nextPort,
  parseIr,
} from '@/utils/etl'
import type { EtlFlowEdge, EtlFlowNode } from '@/utils/etl'
import { jobStatusMeta } from '@/utils/job-status'

defineOptions({ name: 'EtlPipelineEditorView' })

/**
 * ETL 画布编辑器 —— `/integration/etl/:id`(id=new 表示新建)。
 *
 * 画布不是事实源:保存时序列化成 etl.ir/v1 写进 JobDefinition.config(jobType=ETL_PIPELINE),
 * 编译 / 发布 / 执行全部复用任务接口,和其它任务类型走同一条生命周期。
 */

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const schema = useEtlSchema()
const { screenToFlowCoordinate, fitView, removeNodes } = useVueFlow()

const jobId = computed(() => String(route.params.id ?? 'new'))
const isNew = computed(() => jobId.value === 'new')

const canEdit = computed(
  () =>
    auth.hasPermission('control:etl:manage')
    && auth.hasPermission(isNew.value ? 'control:job:create' : 'control:job:update'),
)
const readonly = computed(() => !canEdit.value)

// ── 状态 ──────────────────────────────────────────────────────────

const loading = ref(false)
const saving = ref(false)
const job = ref<JobDefinition | null>(null)
const name = ref('未命名 ETL 管道')
const description = ref('')
// Vue Flow 的 Node 泛型很深,ref<Node[]> 的 UnwrapRef 展开会触发 TS2589;
// 断言成 Ref<T[]> 绕过类型展开,运行时仍是普通的深响应式数组。
const nodes = ref([]) as unknown as Ref<EtlFlowNode[]>
const edges = ref([]) as unknown as Ref<EtlFlowEdge[]>
const errorPolicy = ref<EtlErrorPolicy>({ mode: 'FAIL_FAST' })

const selectedId = ref<string | null>(null)
const drawerOpen = ref(false)

const panelTab = ref('preview')
const panelCollapsed = ref(false)
const diagnostics = ref<CompileDiagnostic[]>([])
const compileSummary = ref('')
const compileSucceeded = ref<boolean | null>(null)
const compiling = ref(false)

function buildIr(): EtlIr {
  return flowToIr(nodes.value, edges.value, errorPolicy.value)
}

/**
 * 脏标记:把「当前应保存的内容」与「上次保存 / 加载的内容」序列化后比较,
 * 而不是在每个修改点手动置位 —— 修改点太多(拖动、抽屉、连线、删除、改名),总会漏一处。
 */
function snapshot(): string {
  return JSON.stringify([name.value, description.value, buildIr()])
}
const saved = ref('')
const dirty = computed(() => snapshot() !== saved.value)

// ── 加载 ──────────────────────────────────────────────────────────

function applyJob(def: JobDefinition) {
  job.value = def
  name.value = def.name
  description.value = def.description ?? ''
  compileSummary.value = def.lastCompileMessage ?? ''
  compileSucceeded.value = def.lastCompileSucceeded
}

async function load() {
  schema.reset()
  diagnostics.value = []
  compileSummary.value = ''
  compileSucceeded.value = null
  selectedId.value = null
  drawerOpen.value = false
  if (isNew.value) {
    job.value = null
    nodes.value = []
    edges.value = []
    errorPolicy.value = { mode: 'FAIL_FAST' }
    name.value = '未命名 ETL 管道'
    description.value = ''
    saved.value = snapshot()
    return
  }
  loading.value = true
  try {
    const def = await jobApi.get(jobId.value)
    if (def.jobType !== 'ETL_PIPELINE') {
      ElMessage.warning('该任务不是 ETL 管道,已返回列表')
      void router.replace({ name: 'etl-pipelines' })
      return
    }
    applyJob(def)
    const ir = parseIr(def.config)
    if (Object.keys(def.config ?? {}).length && !ir.nodes.length) {
      ElMessage.warning('任务配置不是 etl.ir/v1,画布以空管道打开;保存会覆盖原配置')
    }
    const flow = irToFlow(ir)
    nodes.value = flow.nodes
    edges.value = flow.edges
    errorPolicy.value = ir.errorPolicy
    saved.value = snapshot()
    try {
      diagnostics.value = await jobApi.diagnostics(def.id)
      markDiagnostics()
    } catch {
      /* 没有历史诊断不是错误 */
    }
    await nextTick()
    requestAnimationFrame(() => void fitView({ padding: 0.2 }))
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  resolveColors()
  await load()
  if (route.query.compile === '1' && job.value) {
    void router.replace({ query: {} })
    void onCompile()
  }
})

watch(jobId, () => void load())

onBeforeRouteLeave(async () => {
  if (!dirty.value || readonly.value) return true
  return confirmAction('画布有未保存的修改,离开会丢失。确定离开吗?', '放弃修改', {
    confirmButtonText: '放弃并离开',
    cancelButtonText: '留下',
  })
})

// 数据源节点改了表,就去拉一次列:下游抽屉里的候选列由它推演而来
watch(
  () =>
    nodes.value
      .filter((n) => n.data!.kind === 'SOURCE_JDBC')
      .map((n) => `${n.data!.config.dataSourceId}|${n.data!.config.database}|${n.data!.config.schema}|${n.data!.config.table}`)
      .join(';'),
  () => {
    for (const n of nodes.value) {
      if (n.data!.kind === 'SOURCE_JDBC') void schema.ensure(n.data!.config)
    }
  },
)

// ── 画布操作 ──────────────────────────────────────────────────────

const canvasRef = ref<HTMLElement>()

function addNode(kind: EtlNodeKind, position?: { x: number; y: number }) {
  if (readonly.value) return
  const id = newNodeId(kind, nodes.value.map((n) => n.id))
  let pos = position
  if (!pos) {
    // 面板点击添加:落在画布可视区中心附近,错开一点避免新节点叠在一起
    const rect = canvasRef.value?.getBoundingClientRect()
    const base = rect
      ? screenToFlowCoordinate({ x: rect.left + rect.width / 2 - 110, y: rect.top + rect.height / 2 - 40 })
      : { x: 80, y: 80 }
    const shift = (nodes.value.length % 6) * 24
    pos = { x: base.x + shift, y: base.y + shift }
  }
  nodes.value = [
    ...nodes.value,
    {
      id,
      type: kindMeta(kind).visual,
      position: pos,
      data: { kind, name: '', config: ETL_KIND_META[kind].defaultConfig(), diagnostic: null },
    },
  ]
  selectNode(id)
}

function onPaletteDragStart(e: DragEvent, kind: EtlNodeKind) {
  e.dataTransfer?.setData('application/etl-kind', kind)
  if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
}

function onDrop(e: DragEvent) {
  const kind = e.dataTransfer?.getData('application/etl-kind') as EtlNodeKind | undefined
  if (!kind || !ETL_KIND_META[kind]) return
  const pos = screenToFlowCoordinate({ x: e.clientX - 110, y: e.clientY - 30 })
  addNode(kind, pos)
}

function onConnect(conn: { source: string; target: string }) {
  if (readonly.value) return
  const reason = checkConnection(conn.source, conn.target, nodes.value, edges.value)
  if (reason) {
    ElMessage.warning(reason)
    return
  }
  const port = nextPort(conn.target, nodes.value, edges.value)
  edges.value = [
    ...edges.value,
    {
      id: irEdgeId({ from: conn.source, to: conn.target, port }),
      source: conn.source,
      target: conn.target,
      label: port,
      data: { port },
    },
  ]
}

function selectNode(id: string | null) {
  selectedId.value = id
  drawerOpen.value = !!id
}

function onNodeClick(e: NodeMouseEvent) {
  selectNode(e.node.id)
}

function onNodeDragStop(e: { node: GraphNode }) {
  selectedId.value = e.node.id
}

function onPaneClick() {
  selectedId.value = null
  drawerOpen.value = false
}

function onDrawerUpdate(p: { id: string; name: string; config: Record<string, unknown> }) {
  const node = nodes.value.find((n) => n.id === p.id)
  if (!node) return
  node.data = { ...node.data!, name: p.name, config: p.config }
}

function onRemoveNode(id: string) {
  removeNodes([id])
  selectNode(null)
}

// 节点被键盘删除时,抽屉别还挂着一个已不存在的节点
watch(
  () => nodes.value.map((n) => n.id).join(','),
  () => {
    if (selectedId.value && !nodes.value.some((n) => n.id === selectedId.value)) {
      selectNode(null)
    }
  },
)

const selectedNode = computed(() => nodes.value.find((n) => n.id === selectedId.value) ?? null)
const upstreamIds = computed(() =>
  selectedNode.value ? edges.value.filter((e) => e.target === selectedNode.value!.id).map((e) => e.source) : [],
)

function sourceColumnsOf(node: EtlFlowNode): EtlColumn[] | null {
  return schema.columnsOf(node.data!.config)
}

const upstreamColumns = computed<EtlColumn[] | null>(() => {
  const first = upstreamIds.value[0]
  return first ? inferColumns(first, nodes.value, edges.value, sourceColumnsOf) : null
})

// ── 保存 / 编译 / 发布 / 执行 ───────────────────────────────────────

function validateBeforeSave(): string | null {
  if (!name.value.trim()) return '请填写管道名称'
  if (name.value.length > 128) return '管道名称不得超过 128 字符'
  return null
}

/** 保存并返回最新定义;失败返回 null(拦截器已提示服务端错误) */
async function save(options: { silent?: boolean } = {}): Promise<JobDefinition | null> {
  const problem = validateBeforeSave()
  if (problem) {
    ElMessage.warning(problem)
    return null
  }
  saving.value = true
  try {
    const payload = {
      name: name.value.trim(),
      jobType: 'ETL_PIPELINE' as const,
      description: description.value.trim() || undefined,
      catalogId: job.value?.catalogId,
      config: buildIr() as unknown as Record<string, unknown>,
      timeoutMs: job.value?.timeoutMs ?? undefined,
      retryMaxAttempts: job.value?.retryMaxAttempts ?? undefined,
      retryBackoffSeconds: job.value?.retryBackoffSeconds ?? undefined,
    }
    const result = job.value
      ? await jobApi.update(job.value.id, payload)
      : await jobApi.create(payload)
    applyJob(result)
    saved.value = snapshot()
    if (!options.silent) {
      ElMessage.success(isNew.value ? '已创建,状态为草稿。请编译并发布后再执行' : '已保存。需重新编译并发布后才能执行')
    }
    return result
  } catch {
    return null
  } finally {
    saving.value = false
  }
}

async function onSave() {
  const wasNew = isNew.value
  const result = await save()
  if (result && wasNew) {
    await router.replace({ name: 'etl-editor', params: { id: result.id } })
  }
}

function markDiagnostics() {
  const marks = diagnosticsByNode(diagnostics.value)
  for (const n of nodes.value) {
    n.data = { ...n.data!, diagnostic: marks.get(n.id) ?? null }
  }
}

async function onCompile() {
  compiling.value = true
  try {
    const wasNew = isNew.value
    const result = await save({ silent: true })
    if (!result) return
    if (wasNew) {
      // 新建后路由要换成真实 id,组件会重建;让新实例接着编译,用户不必再点一次
      await router.replace({ name: 'etl-editor', params: { id: result.id }, query: { compile: '1' } })
      return
    }
    // 后端编译失败也返回 200,成败看 succeeded 而不是 catch
    const res = await jobApi.compile(result.id)
    diagnostics.value = res.diagnostics
    compileSummary.value = res.summary
    compileSucceeded.value = res.succeeded
    markDiagnostics()
    panelTab.value = 'diagnostics'
    panelCollapsed.value = false
    applyJob(await jobApi.get(result.id))
    if (res.succeeded) {
      ElMessage.success(res.diagnostics.length ? '编译通过,但有提醒,请查看诊断' : res.summary)
    } else {
      ElMessage.error('编译未通过,请查看诊断')
    }
  } finally {
    compiling.value = false
  }
}

function focusNode(id: string) {
  selectNode(id)
  const node = nodes.value.find((n) => n.id === id)
  if (node) {
    void fitView({ nodes: [id], padding: 1.5, duration: 300 })
  }
}

const canPublish = computed(
  () => !!job.value && !dirty.value && ['VALIDATED', 'DRAFT'].includes(job.value.status),
)
const canRun = computed(
  () =>
    !!job.value
    && !dirty.value
    && job.value.planUpToDate
    && ['PUBLISHED', 'SCHEDULING', 'PAUSED'].includes(job.value.status),
)

async function onPublish() {
  if (!job.value) return
  applyJob(await jobApi.publish(job.value.id))
  ElMessage.success('已发布。可手工执行,也可在列表页绑定周期调度')
}

async function onRun() {
  if (!job.value) return
  const execution = await jobApi.run(job.value.id)
  const go = await confirmAction(`执行已提交,记录 ID:${execution.id}`, '已提交', {
    confirmButtonText: '去看执行记录',
    cancelButtonText: '留在画布',
    type: 'success',
  })
  if (go) {
    void router.push({ name: 'executions', query: { jobRefId: job.value.id } })
  }
}

async function onBack() {
  await router.push({ name: 'etl-pipelines' })
}

// ── 画布颜色:运行时读 token,避免在代码里写色值 ─────────────────────

const colors = ref({ edge: '', grid: '', mask: '', source: '', transform: '', customSql: '', sink: '' })

function cssVar(token: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(token).trim()
}

function resolveColors() {
  colors.value = {
    edge: cssVar('--dg-text-muted'),
    grid: cssVar('--dg-border-strong'),
    mask: cssVar('--dg-surface-muted'),
    source: cssVar('--dg-primary'),
    transform: cssVar('--dg-info'),
    customSql: cssVar('--dg-warning'),
    sink: cssVar('--dg-success'),
  }
}

const defaultEdgeOptions = computed(() => ({
  markerEnd: { type: MarkerType.ArrowClosed, color: colors.value.edge || undefined, width: 16, height: 16 },
}))

function miniMapColor(node: GraphNode): string {
  const key = (node.type ?? 'transform') as 'source' | 'transform' | 'customSql' | 'sink'
  return colors.value[key] || colors.value.transform
}

const statusMeta = computed(() => (job.value ? jobStatusMeta(job.value.status) : null))
const panelHeight = computed(() => (panelCollapsed.value ? '40px' : '300px'))
</script>

<template>
  <div v-loading="loading" class="etl-editor">
    <!-- 顶栏 -->
    <div class="etl-editor__bar">
      <el-button link @click="onBack">← 返回列表</el-button>
      <el-input
        v-model="name"
        :disabled="readonly"
        class="etl-editor__name"
        placeholder="管道名称"
        maxlength="128"
      />
      <el-input
        v-model="description"
        :disabled="readonly"
        class="etl-editor__desc"
        placeholder="描述(可选)"
        maxlength="512"
      />
      <el-tag v-if="statusMeta && job" :type="statusMeta.tagType" size="small">
        {{ job.statusDisplayName }}
      </el-tag>
      <el-tag v-else size="small" type="info">未保存</el-tag>
      <el-tag v-if="job && !job.planUpToDate" size="small" type="warning" effect="plain">待重新编译</el-tag>
      <el-tag v-if="dirty && !readonly" size="small" type="warning" effect="plain">有未保存修改</el-tag>
      <el-tag v-if="readonly" size="small" type="info" effect="plain">只读</el-tag>

      <span class="etl-editor__spacer" />

      <el-popover trigger="click" :width="300" placement="bottom-end">
        <template #reference>
          <el-button>管道设置</el-button>
        </template>
        <el-form label-position="top" :disabled="readonly">
          <el-form-item label="脏数据策略 (errorPolicy)">
            <el-radio-group v-model="errorPolicy.mode">
              <el-radio-button value="FAIL_FAST">遇错即停</el-radio-button>
              <el-radio-button value="SKIP_BAD_ROWS">跳过坏行</el-radio-button>
              <el-radio-button value="ROUTE_TO_DEAD_LETTER">路由死信</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <template v-if="errorPolicy.mode !== 'FAIL_FAST'">
            <el-form-item label="坏行上限 (maxBadRows)">
              <el-input-number v-model="errorPolicy.maxBadRows" :min="0" controls-position="right" />
            </el-form-item>
            <el-form-item label="坏行比例上限 (0~1)">
              <el-input-number
                v-model="errorPolicy.maxBadRatio"
                :min="0"
                :max="1"
                :step="0.01"
                :precision="3"
                controls-position="right"
              />
            </el-form-item>
          </template>
          <div class="text-muted">超出上限仍会失败;坏行批次记录在 etl_bad_row_batch</div>
        </el-form>
      </el-popover>
      <el-button v-if="!readonly" :loading="saving" :type="dirty ? 'primary' : 'default'" @click="onSave">
        保存
      </el-button>
      <el-button
        v-if="!readonly"
        v-permission="'control:job:compile'"
        :loading="compiling"
        @click="onCompile"
      >
        保存并编译
      </el-button>
      <el-button
        v-permission="'control:job:publish'"
        :disabled="!canPublish"
        @click="onPublish"
      >
        发布
      </el-button>
      <el-button
        v-permission="'control:job:trigger'"
        type="success"
        :disabled="!canRun"
        @click="onRun"
      >
        执行
      </el-button>
    </div>

    <!-- 主体:算子面板 + 画布 -->
    <div class="etl-editor__main">
      <aside v-if="!readonly" class="etl-palette">
        <div v-for="g in ETL_PALETTE_GROUPS" :key="g.title" class="etl-palette__group">
          <div class="etl-palette__title">{{ g.title }}</div>
          <div
            v-for="k in g.kinds"
            :key="k"
            class="etl-palette__item"
            :class="`etl-palette__item--${g.visual}`"
            draggable="true"
            :title="`拖到画布,或点击添加:${ETL_KIND_META[k].label}`"
            @dragstart="onPaletteDragStart($event, k)"
            @click="addNode(k)"
          >
            {{ ETL_KIND_META[k].label }}
          </div>
        </div>
        <div class="text-muted etl-palette__tip">
          拖拽或点击添加节点;从节点右侧圆点拖到另一节点左侧圆点即可连线。选中后按 Backspace 删除。
        </div>
      </aside>

      <div ref="canvasRef" class="etl-canvas" @dragover.prevent @drop.prevent="onDrop">
        <VueFlow
          v-model:nodes="nodes"
          v-model:edges="edges"
          :default-edge-options="defaultEdgeOptions"
          :min-zoom="0.2"
          :max-zoom="1.6"
          :nodes-draggable="!readonly"
          :nodes-connectable="!readonly"
          :delete-key-code="readonly ? null : ['Backspace', 'Delete']"
          :zoom-on-double-click="false"
          @connect="onConnect"
          @node-click="onNodeClick"
          @node-drag-stop="onNodeDragStop"
          @pane-click="onPaneClick"
        >
          <template #node-source="p">
            <EtlFlowNodeView :id="p.id" :data="p.data" :selected="p.selected" />
          </template>
          <template #node-transform="p">
            <EtlFlowNodeView :id="p.id" :data="p.data" :selected="p.selected" />
          </template>
          <template #node-customSql="p">
            <EtlFlowNodeView :id="p.id" :data="p.data" :selected="p.selected" />
          </template>
          <template #node-sink="p">
            <EtlFlowNodeView :id="p.id" :data="p.data" :selected="p.selected" />
          </template>
          <Background :gap="18" :pattern-color="colors.grid || undefined" />
          <MiniMap
            pannable
            zoomable
            :node-color="miniMapColor"
            :mask-color="colors.mask || undefined"
          />
          <Controls :show-interactive="false" />
        </VueFlow>
        <div v-if="!nodes.length && !loading" class="etl-canvas__empty">
          <el-empty
            :image-size="64"
            :description="readonly ? '该管道还没有节点' : '从左侧添加「数据源」开始搭建管道:数据源 → 转换 → 目标'"
          />
        </div>
      </div>
    </div>

    <!-- 底部面板 -->
    <div class="etl-editor__bottom" :style="{ height: panelHeight }">
      <el-button
        link
        class="etl-editor__collapse"
        @click="panelCollapsed = !panelCollapsed"
      >
        {{ panelCollapsed ? '展开 ▲' : '收起 ▼' }}
      </el-button>
      <EtlBottomPanel
        v-show="!panelCollapsed"
        v-model="panelTab"
        :job-id="isNew ? null : jobId"
        :nodes="nodes"
        :selected-node-id="selectedId"
        :build-ir="buildIr"
        :diagnostics="diagnostics"
        :compile-summary="compileSummary"
        :compile-succeeded="compileSucceeded"
        :compiling="compiling"
        :can-compile="!readonly"
        @compile="onCompile"
        @focus-node="focusNode"
      />
      <div v-if="panelCollapsed" class="etl-editor__collapsed-hint text-muted">
        样本预览 · 编译诊断 · 增量水位线
      </div>
    </div>

    <EtlNodeDrawer
      v-model="drawerOpen"
      :node="selectedNode"
      :upstream-columns="upstreamColumns"
      :upstream-ids="upstreamIds"
      :readonly="readonly"
      @update="onDrawerUpdate"
      @remove="onRemoveNode"
    />
  </div>
</template>

<style scoped>
.etl-editor {
  display: flex;
  flex-direction: column;
  gap: var(--dg-space-sm);
  height: calc(100vh - var(--dg-header-height));
  padding: var(--dg-space-md);
}

.etl-editor__bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--dg-space-sm);
  padding: var(--dg-space-sm) var(--dg-space-md);
  background: var(--dg-surface);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
}

.etl-editor__name {
  width: 240px;
}

.etl-editor__desc {
  width: 260px;
}

.etl-editor__spacer {
  flex: 1;
}

.etl-editor__main {
  display: flex;
  flex: 1;
  min-height: 0;
  gap: var(--dg-space-sm);
}

.etl-palette {
  width: 168px;
  padding: var(--dg-space-md);
  overflow: auto;
  background: var(--dg-surface);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
}

.etl-palette__group {
  margin-bottom: var(--dg-space-lg);
}

.etl-palette__title {
  margin-bottom: var(--dg-space-sm);
  font-size: 12px;
  font-weight: 600;
  color: var(--dg-text-secondary);
}

.etl-palette__item {
  padding: 6px var(--dg-space-sm);
  margin-bottom: var(--dg-space-xs);
  font-size: 13px;
  color: var(--dg-text);
  cursor: grab;
  user-select: none;
  background: var(--dg-surface-muted);
  border-left: 3px solid var(--dg-info);
  border-radius: var(--dg-radius-sm);
  transition: background var(--dg-duration-fast) var(--dg-ease-out);
}

.etl-palette__item:hover {
  background: var(--dg-primary-soft);
}

.etl-palette__item--source {
  border-left-color: var(--dg-primary);
}

.etl-palette__item--transform {
  border-left-color: var(--dg-info);
}

.etl-palette__item--customSql {
  border-left-color: var(--dg-warning);
}

.etl-palette__item--sink {
  border-left-color: var(--dg-success);
}

.etl-palette__tip {
  line-height: 1.6;
}

.etl-canvas {
  position: relative;
  flex: 1;
  min-width: 0;
  background: var(--dg-surface);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
  overflow: hidden;
}

.etl-canvas__empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}

.etl-editor__bottom {
  position: relative;
  flex: none;
  overflow: hidden;
  background: var(--dg-surface);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
  transition: height var(--dg-duration-base) var(--dg-ease-out);
}

.etl-editor__collapse {
  position: absolute;
  top: var(--dg-space-sm);
  right: var(--dg-space-md);
  z-index: 1;
}

.etl-editor__collapsed-hint {
  padding: var(--dg-space-sm) var(--dg-space-md);
  line-height: 24px;
}

.etl-canvas :deep(.vue-flow__edge-path) {
  stroke: var(--dg-text-muted);
}

.etl-canvas :deep(.vue-flow__edge.selected .vue-flow__edge-path) {
  stroke: var(--dg-primary);
}

.etl-canvas :deep(.vue-flow__edge-text) {
  font-size: 11px;
  fill: var(--dg-text-secondary);
}

.etl-canvas :deep(.vue-flow__handle) {
  width: 10px;
  height: 10px;
  background: var(--dg-surface);
  border: 2px solid var(--dg-text-muted);
}

.etl-canvas :deep(.vue-flow__handle:hover),
.etl-canvas :deep(.vue-flow__handle.connectingfrom) {
  border-color: var(--dg-primary);
}

.etl-canvas :deep(.vue-flow__node) {
  cursor: pointer;
}
</style>
