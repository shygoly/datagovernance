<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { semanticApi } from '@/api/semantic'
import SemanticMappingEditor from './SemanticMappingEditor.vue'
import SemanticAiContextEditor from './SemanticAiContextEditor.vue'
import { formatDateTime } from '@/utils/format'
import type {
  SemanticEdgeCategory,
  SemanticLink,
  SemanticObject,
  SemanticObjectType,
  SemanticObjectVersion,
  SemanticScope,
} from '@/types/semantic'

const props = defineProps<{
  visible: boolean
  semanticObject: SemanticObject | null
  allObjects: SemanticObject[]
  canManage: boolean
  canPublish: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  updated: []
}>()

const activeTab = ref('spec')
const saving = ref(false)
const publishing = ref(false)
const links = ref<SemanticLink[]>([])
const versions = ref<SemanticObjectVersion[]>([])
const currentObj = ref<SemanticObject | null>(null)

const form = reactive({
  code: '',
  name: '',
  objectType: 'METRIC' as SemanticObjectType,
  scope: 'WORKSPACE' as SemanticScope,
  standardRef: '',
  ownerUsername: '',
  metricKind: 'BASE',
  aggregation: 'SUM',
  unit: '',
  numeratorObjectCode: '',
  denominatorObjectCode: '',
  allowedDimensionsText: '',
  hierarchyText: '',
  isTimeDimension: false,
  specExtraJson: '',
})

const linkForm = reactive({
  toObjectId: '',
  edgeCategory: 'STRUCTURAL' as SemanticEdgeCategory,
  linkType: 'RELATES_TO',
  cardinality: '1:N',
  leftColumn: '',
  rightColumn: '',
  joinType: 'LEFT',
  rationale: '',
})

const linkTypeOptions = computed(() => {
  if (linkForm.edgeCategory === 'STRUCTURAL') {
    return ['HAS_ATTRIBUTE', 'HAS_FACT', 'HIERARCHY_PARENT', 'RELATES_TO', 'DERIVED_FROM']
  }
  if (linkForm.edgeCategory === 'SEMANTIC') {
    return ['IS_A', 'PART_OF', 'SYNONYM_OF', 'MAPS_TO_CONCEPT', 'PRECEDES']
  }
  return ['CONSTRAINS', 'EXCLUDES', 'AFFECTS']
})

const isCreate = computed(() => !currentObj.value?.id)

function hydrateForm(obj: SemanticObject | null) {
  currentObj.value = obj
  if (!obj) {
    form.code = ''
    form.name = ''
    form.objectType = 'METRIC'
    form.scope = 'WORKSPACE'
    form.standardRef = ''
    form.ownerUsername = ''
    form.metricKind = 'BASE'
    form.aggregation = 'SUM'
    form.unit = ''
    form.numeratorObjectCode = ''
    form.denominatorObjectCode = ''
    form.allowedDimensionsText = ''
    form.hierarchyText = ''
    form.isTimeDimension = false
    form.specExtraJson = '{}'
    links.value = []
    versions.value = []
    activeTab.value = 'spec'
    return
  }
  const spec = obj.spec || {}
  form.code = obj.code
  form.name = obj.name
  form.objectType = obj.objectType
  form.scope = obj.scope || 'WORKSPACE'
  form.standardRef = obj.standardRef || ''
  form.ownerUsername = obj.ownerUsername || ''
  form.metricKind = String(spec.metricKind || 'BASE')
  form.aggregation = String(spec.aggregation || 'SUM')
  form.unit = String(spec.unit || '')
  form.numeratorObjectCode = String(spec.numeratorObjectCode || '')
  form.denominatorObjectCode = String(spec.denominatorObjectCode || '')
  form.allowedDimensionsText = Array.isArray(spec.allowedDimensions)
    ? spec.allowedDimensions.join(', ')
    : ''
  form.hierarchyText = Array.isArray(spec.hierarchy) ? spec.hierarchy.join(', ') : ''
  form.isTimeDimension = Boolean(spec.isTimeDimension)
  form.specExtraJson = JSON.stringify(spec, null, 2)
  void loadSubResources(obj.id)
}

async function loadSubResources(id: string) {
  const [lnk, ver] = await Promise.all([
    semanticApi.listLinks({ objectId: id }),
    semanticApi.listVersions(id),
  ])
  links.value = lnk
  versions.value = ver
}

function buildSpecPayload(): Record<string, unknown> {
  if (form.objectType === 'METRIC') {
    const out: Record<string, unknown> = {
      metricKind: form.metricKind,
      aggregation: form.metricKind === 'DERIVED' ? 'RATIO' : form.aggregation,
      unit: form.unit.trim() || undefined,
    }
    if (form.metricKind === 'DERIVED') {
      out.numeratorObjectCode = form.numeratorObjectCode.trim()
      out.denominatorObjectCode = form.denominatorObjectCode.trim()
    }
    const allowed = form.allowedDimensionsText
      .split(/[,，]/)
      .map((s) => s.trim())
      .filter(Boolean)
    if (allowed.length) out.allowedDimensions = allowed
    if (currentObj.value?.spec?.defaultFilters) {
      out.defaultFilters = currentObj.value.spec.defaultFilters
    }
    return out
  }
  if (form.objectType === 'DIMENSION') {
    return {
      isTimeDimension: form.isTimeDimension,
      hierarchy: form.hierarchyText
        .split(/[,，]/)
        .map((s) => s.trim())
        .filter(Boolean),
    }
  }
  try {
    return JSON.parse(form.specExtraJson || '{}')
  } catch {
    return {}
  }
}

async function saveSpec() {
  if (!form.code.trim() || !form.name.trim()) {
    ElMessage.warning('编码与名称为必填项')
    return
  }
  saving.value = true
  try {
    const payload = {
      code: form.code.trim(),
      name: form.name.trim(),
      objectType: form.objectType,
      scope: form.scope,
      standardRef: form.standardRef.trim() || null,
      ownerUsername: form.ownerUsername.trim() || null,
      spec: buildSpecPayload(),
      aiContext: currentObj.value?.aiContext || {},
    }
    const saved = isCreate.value
      ? await semanticApi.createObject(payload)
      : await semanticApi.updateObject(currentObj.value!.id, payload)
    currentObj.value = saved
    ElMessage.success(isCreate.value ? '语义对象已创建（DRAFT）' : '语义定义已保存')
    emit('updated')
  } finally {
    saving.value = false
  }
}

async function publish() {
  if (!currentObj.value?.id) return
  publishing.value = true
  try {
    currentObj.value = await semanticApi.publishObject(currentObj.value.id)
    ElMessage.success(`已发布 v${currentObj.value.versionNo} 并冻结不可变快照`)
    await loadSubResources(currentObj.value.id)
    emit('updated')
  } finally {
    publishing.value = false
  }
}

async function rollback(versionNo: number) {
  if (!currentObj.value?.id) return
  currentObj.value = await semanticApi.rollbackObject(currentObj.value.id, versionNo)
  ElMessage.success(`已回滚至 v${versionNo} 口径并发布新版本 v${currentObj.value.versionNo}`)
  hydrateForm(currentObj.value)
  emit('updated')
}

async function addLink() {
  if (!currentObj.value?.id || !linkForm.toObjectId) {
    ElMessage.warning('请选择关联目标对象')
    return
  }
  const joinSpec =
    linkForm.edgeCategory === 'STRUCTURAL' && linkForm.leftColumn.trim() && linkForm.rightColumn.trim()
      ? [
          {
            leftColumn: linkForm.leftColumn.trim(),
            rightColumn: linkForm.rightColumn.trim(),
            joinType: linkForm.joinType,
          },
        ]
      : undefined
  await semanticApi.createLink({
    fromObjectId: currentObj.value.id,
    toObjectId: linkForm.toObjectId,
    edgeCategory: linkForm.edgeCategory,
    linkType: linkForm.linkType,
    cardinality: linkForm.cardinality,
    joinSpec,
    rationale: linkForm.rationale.trim() || null,
  })
  ElMessage.success('关系边已添加')
  linkForm.toObjectId = ''
  linkForm.leftColumn = ''
  linkForm.rightColumn = ''
  linkForm.rationale = ''
  await loadSubResources(currentObj.value.id)
  emit('updated')
}

async function deleteLink(linkId: string) {
  await semanticApi.deleteLink(linkId)
  ElMessage.success('关系边已删除')
  if (currentObj.value?.id) {
    await loadSubResources(currentObj.value.id)
  }
  emit('updated')
}

watch(
  () => linkForm.edgeCategory,
  () => {
    linkForm.linkType = linkTypeOptions.value[0]
  },
)

watch(
  () => [props.visible, props.semanticObject] as const,
  ([vis, obj]) => {
    if (vis) {
      hydrateForm(obj)
    }
  },
  { immediate: true },
)
</script>

<template>
  <el-drawer
    :model-value="visible"
    size="68%"
    :title="isCreate ? '新建语义对象' : `${currentObj?.name} (${currentObj?.code})`"
    destroy-on-close
    @update:model-value="(val: boolean) => emit('update:visible', val)"
  >
    <template #header>
      <div class="drawer-head">
        <div>
          <span class="drawer-title">
            {{ isCreate ? '新建语义对象' : currentObj?.name }}
          </span>
          <span v-if="currentObj" class="drawer-code">{{ currentObj.code }}</span>
          <el-tag v-if="currentObj" size="small" style="margin-left: 8px">
            {{ currentObj.status }} · v{{ currentObj.versionNo }}
          </el-tag>
        </div>
        <div v-if="!isCreate && canPublish" class="drawer-actions">
          <el-button type="primary" size="small" :loading="publishing" @click="publish">
            发布快照
          </el-button>
        </div>
      </div>
    </template>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="定义与口径" name="spec">
        <el-form label-position="top">
          <div class="two-col">
            <el-form-item label="语义编码（空间内唯一）" required>
              <el-input
                v-model="form.code"
                :disabled="!canManage"
                placeholder="例如 metric.order.gmv_valid"
                class="mono-input"
              />
            </el-form-item>
            <el-form-item label="业务名称" required>
              <el-input v-model="form.name" :disabled="!canManage" placeholder="例如 有效GMV" />
            </el-form-item>
            <el-form-item label="对象类型" required>
              <el-select v-model="form.objectType" :disabled="!canManage" style="width: 100%">
                <el-option-group label="Layer 1 · 计算语义">
                  <el-option label="ENTITY（业务实体）" value="ENTITY" />
                  <el-option label="ATTRIBUTE（属性）" value="ATTRIBUTE" />
                  <el-option label="DIMENSION（分析维度）" value="DIMENSION" />
                  <el-option label="FACT（事实）" value="FACT" />
                  <el-option label="METRIC（标准指标）" value="METRIC" />
                </el-option-group>
                <el-option-group label="Layer 2 · 业务知识图谱">
                  <el-option label="CONCEPT（领域概念）" value="CONCEPT" />
                  <el-option label="PROCESS（业务流程）" value="PROCESS" />
                </el-option-group>
              </el-select>
            </el-form-item>
            <el-form-item label="权威 Standard 引用（METRIC 发布必填 · C2）">
              <el-input
                v-model="form.standardRef"
                :disabled="!canManage"
                placeholder="例如 STD-ORDER-GMV-001"
                class="mono-input"
              />
            </el-form-item>
            <el-form-item label="共享作用域（权威标准本体可跨 Workspace 复用）">
              <el-select v-model="form.scope" :disabled="!canManage" style="width: 100%">
                <el-option label="WORKSPACE（当前空间私有）" value="WORKSPACE" />
                <el-option label="GLOBAL（平台权威共享本体）" value="GLOBAL" />
              </el-select>
            </el-form-item>
          </div>

          <template v-if="form.objectType === 'METRIC'">
            <div class="two-col">
              <el-form-item label="指标类型">
                <el-select v-model="form.metricKind" :disabled="!canManage" style="width: 100%">
                  <el-option label="BASE（基础聚合指标）" value="BASE" />
                  <el-option label="DERIVED（复合衍生比率）" value="DERIVED" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="form.metricKind === 'BASE'" label="聚合函数">
                <el-select v-model="form.aggregation" :disabled="!canManage" style="width: 100%">
                  <el-option label="SUM" value="SUM" />
                  <el-option label="COUNT" value="COUNT" />
                  <el-option label="COUNT_DISTINCT" value="COUNT_DISTINCT" />
                  <el-option label="AVG" value="AVG" />
                  <el-option label="MIN" value="MIN" />
                  <el-option label="MAX" value="MAX" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="form.metricKind === 'DERIVED'" label="分子指标 code">
                <el-input v-model="form.numeratorObjectCode" :disabled="!canManage" class="mono-input" />
              </el-form-item>
              <el-form-item v-if="form.metricKind === 'DERIVED'" label="分母指标 code">
                <el-input v-model="form.denominatorObjectCode" :disabled="!canManage" class="mono-input" />
              </el-form-item>
              <el-form-item label="度量单位">
                <el-input v-model="form.unit" :disabled="!canManage" placeholder="元 / % / 人次" />
              </el-form-item>
              <el-form-item label="允许分析维度（逗号分隔，空表示不限）">
                <el-input
                  v-model="form.allowedDimensionsText"
                  :disabled="!canManage"
                  placeholder="attr.org.region, dim.time.biz_date"
                  class="mono-input"
                />
              </el-form-item>
            </div>
          </template>

          <template v-else-if="form.objectType === 'DIMENSION'">
            <el-form-item label="层级钻取路径（对象 code，逗号分隔）">
              <el-input
                v-model="form.hierarchyText"
                :disabled="!canManage"
                placeholder="attr.org.hospital, attr.org.dept, attr.org.ward"
                class="mono-input"
              />
            </el-form-item>
            <el-form-item>
              <el-checkbox v-model="form.isTimeDimension" :disabled="!canManage">
                标记为时间维度（启用相对时间自动归一化）
              </el-checkbox>
            </el-form-item>
          </template>

          <template v-else>
            <el-form-item label="扩展规格（spec_json）">
              <el-input
                v-model="form.specExtraJson"
                :disabled="!canManage"
                type="textarea"
                :rows="4"
                class="mono-input"
              />
            </el-form-item>
          </template>

          <div v-if="canManage" class="footer-right">
            <el-button type="primary" size="small" :loading="saving" @click="saveSpec">
              {{ isCreate ? '创建草稿' : '保存定义' }}
            </el-button>
          </div>
        </el-form>
      </el-tab-pane>

      <el-tab-pane v-if="!isCreate && currentObj" label="物理表列映射" name="mapping">
        <SemanticMappingEditor
          :object-id="currentObj.id"
          :can-manage="canManage"
          @changed="emit('updated')"
        />
      </el-tab-pane>

      <el-tab-pane v-if="!isCreate && currentObj" label="AI 理解卡与值字典" name="ai">
        <SemanticAiContextEditor
          :semantic-object="currentObj"
          :can-manage="canManage"
          @saved="(obj) => { currentObj = obj; emit('updated') }"
        />
      </el-tab-pane>

      <el-tab-pane v-if="!isCreate && currentObj" label="关系与图谱边" name="links">
        <el-form v-if="canManage" label-position="top" class="sub-box">
          <div class="two-col">
            <el-form-item label="关系大类">
              <el-select v-model="linkForm.edgeCategory" style="width: 100%">
                <el-option label="STRUCTURAL（结构 / Join 边）" value="STRUCTURAL" />
                <el-option label="SEMANTIC（语义概念边）" value="SEMANTIC" />
                <el-option label="CAUSAL（约束 / 排除 / 因果边）" value="CAUSAL" />
              </el-select>
            </el-form-item>
            <el-form-item label="边类型">
              <el-select v-model="linkForm.linkType" style="width: 100%">
                <el-option v-for="t in linkTypeOptions" :key="t" :label="t" :value="t" />
              </el-select>
            </el-form-item>
            <el-form-item label="目标对象">
              <el-select v-model="linkForm.toObjectId" filterable style="width: 100%">
                <el-option
                  v-for="o in allObjects.filter((item) => item.id !== currentObj?.id)"
                  :key="o.id"
                  :label="`${o.name} (${o.code})`"
                  :value="o.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="基数（Cardinality）">
              <el-select v-model="linkForm.cardinality" clearable style="width: 100%">
                <el-option label="1:1" value="1:1" />
                <el-option label="1:N" value="1:N" />
                <el-option label="N:1" value="N:1" />
                <el-option label="N:M" value="N:M" />
              </el-select>
            </el-form-item>
          </div>
          <div v-if="linkForm.edgeCategory === 'STRUCTURAL'" class="three-col">
            <el-form-item label="左表 Join 列">
              <el-input v-model="linkForm.leftColumn" placeholder="如 dept_id" class="mono-input" />
            </el-form-item>
            <el-form-item label="右表 Join 列">
              <el-input v-model="linkForm.rightColumn" placeholder="如 id" class="mono-input" />
            </el-form-item>
            <el-form-item label="Join 类型">
              <el-select v-model="linkForm.joinType" style="width: 100%">
                <el-option label="LEFT" value="LEFT" />
                <el-option label="INNER" value="INNER" />
              </el-select>
            </el-form-item>
          </div>
          <el-form-item label="业务理由（Rationale · 辅助 AI 理解）">
            <el-input v-model="linkForm.rationale" placeholder="说明两者关联、互斥或因果原因" />
          </el-form-item>
          <div class="footer-right">
            <el-button type="primary" size="small" @click="addLink">新增关系边</el-button>
          </div>
        </el-form>

        <el-table :data="links" size="small" border>
          <el-table-column prop="edgeCategory" label="大类" width="110">
            <template #default="{ row }">
              <el-tag
                size="small"
                :type="row.edgeCategory === 'CAUSAL' ? 'warning' : row.edgeCategory === 'SEMANTIC' ? 'info' : 'primary'"
              >
                {{ row.edgeCategory }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="linkType" label="边类型" width="145" />
          <el-table-column label="方向与对象" min-width="200">
            <template #default="{ row }">
              <span>{{ row.fromName }} → {{ row.toName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="cardinality" label="基数" width="68" />
          <el-table-column prop="rationale" label="业务理由" min-width="160" show-overflow-tooltip />
          <el-table-column v-if="canManage" label="操作" width="70" align="right">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="deleteLink(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane v-if="!isCreate && currentObj" label="版本与回滚" name="versions">
        <el-table :data="versions" size="small" border>
          <el-table-column prop="versionNo" label="版本" width="76">
            <template #default="{ row }">
              <el-tag size="small">v{{ row.versionNo }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="contentDigest" label="SHA-256 摘要" min-width="180">
            <template #default="{ row }">
              <span class="mono">{{ row.contentDigest.slice(0, 16) }}…</span>
            </template>
          </el-table-column>
          <el-table-column label="生效区间（双时间态）" min-width="220">
            <template #default="{ row }">
              {{ formatDateTime(row.validFrom) }} ~ {{ row.validTo ? formatDateTime(row.validTo) : '现行' }}
            </template>
          </el-table-column>
          <el-table-column v-if="canPublish" label="操作" width="90" align="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="rollback(row.versionNo)">
                回滚此版
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-drawer>
</template>

<style scoped>
.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding-right: var(--dg-space-md);
}
.drawer-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--dg-text);
}
.drawer-code {
  margin-left: var(--dg-space-sm);
  font-family: var(--dg-font-mono);
  font-size: 12px;
  color: var(--dg-text-muted);
}
.two-col {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--dg-space-md);
}
.three-col {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--dg-space-md);
}
.sub-box {
  padding: var(--dg-space-md);
  margin-bottom: var(--dg-space-md);
  background: var(--dg-surface-muted);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
}
.footer-right {
  display: flex;
  justify-content: flex-end;
}
.mono,
.mono-input :deep(input),
.mono-input :deep(textarea) {
  font-family: var(--dg-font-mono);
}
</style>
