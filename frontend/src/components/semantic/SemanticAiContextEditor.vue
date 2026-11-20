<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { semanticApi } from '@/api/semantic'
import type { SemanticObject, SemanticValueDictEntry } from '@/types/semantic'

const props = defineProps<{
  semanticObject: SemanticObject
  canManage: boolean
}>()

const emit = defineEmits<{
  saved: [obj: SemanticObject]
}>()

const saving = ref(false)
const dictLoading = ref(false)
const dictEntries = ref<SemanticValueDictEntry[]>([])

const aiForm = reactive({
  aliasesText: '',
  disambiguation: '',
  grain: '',
  caveatsText: '',
  typicalQuestionsText: '',
  safeEnumExamplesText: '',
})

const dictForm = reactive({
  rawValue: '',
  displayLabel: '',
  synonymsText: '',
})

const isHighSensitivity = computed(() => {
  const s = (props.semanticObject.maxSensitivity || 'L1').toUpperCase()
  return s === 'L3' || s === 'L4'
})

function syncFromObject(obj: SemanticObject) {
  const ctx = obj.aiContext || {}
  aiForm.aliasesText = Array.isArray(ctx.aliases) ? ctx.aliases.join(', ') : ''
  aiForm.disambiguation = typeof ctx.disambiguation === 'string' ? ctx.disambiguation : ''
  aiForm.grain = typeof ctx.grain === 'string' ? ctx.grain : ''
  aiForm.caveatsText = Array.isArray(ctx.caveats) ? ctx.caveats.join('\n') : ''
  aiForm.typicalQuestionsText = Array.isArray(ctx.typicalQuestions)
    ? ctx.typicalQuestions.join('\n')
    : ''
  aiForm.safeEnumExamplesText = Array.isArray(ctx.safeEnumExamples)
    ? ctx.safeEnumExamples.join(', ')
    : ''
}

async function loadDict() {
  if (!props.semanticObject?.id) return
  dictLoading.value = true
  try {
    dictEntries.value = await semanticApi.listValueDict(props.semanticObject.id)
  } finally {
    dictLoading.value = false
  }
}

async function saveAiContext() {
  saving.value = true
  try {
    const aiContext: Record<string, unknown> = {
      aliases: splitComma(aiForm.aliasesText),
      disambiguation: aiForm.disambiguation.trim(),
      grain: aiForm.grain.trim(),
      caveats: splitLines(aiForm.caveatsText),
      typicalQuestions: splitLines(aiForm.typicalQuestionsText),
    }
    if (!isHighSensitivity.value) {
      aiContext.safeEnumExamples = splitComma(aiForm.safeEnumExamplesText)
    }
    const updated = await semanticApi.updateObject(props.semanticObject.id, {
      code: props.semanticObject.code,
      name: props.semanticObject.name,
      objectType: props.semanticObject.objectType,
      domainId: props.semanticObject.domainId,
      standardRef: props.semanticObject.standardRef,
      glossaryTermId: props.semanticObject.glossaryTermId,
      spec: props.semanticObject.spec,
      aiContext,
      ownerUsername: props.semanticObject.ownerUsername,
    })
    ElMessage.success('AI 业务理解卡已保存')
    emit('saved', updated)
  } finally {
    saving.value = false
  }
}

async function addDictEntry() {
  if (!dictForm.rawValue.trim() || !dictForm.displayLabel.trim()) {
    ElMessage.warning('请填写物理码值与业务展示名')
    return
  }
  await semanticApi.upsertValueDict(props.semanticObject.id, {
    rawValue: dictForm.rawValue.trim(),
    displayLabel: dictForm.displayLabel.trim(),
    synonyms: splitComma(dictForm.synonymsText),
  })
  ElMessage.success('安全值字典条目已保存')
  dictForm.rawValue = ''
  dictForm.displayLabel = ''
  dictForm.synonymsText = ''
  await loadDict()
}

async function deleteDictEntry(entryId: string) {
  await semanticApi.deleteValueDict(props.semanticObject.id, entryId)
  ElMessage.success('已删除')
  await loadDict()
}

function splitComma(raw: string): string[] {
  return raw
    .split(/[,，]/)
    .map((s) => s.trim())
    .filter(Boolean)
}

function splitLines(raw: string): string[] {
  return raw
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
}

watch(
  () => props.semanticObject,
  (obj) => {
    if (obj) {
      syncFromObject(obj)
      void loadDict()
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="ai-context-editor">
    <el-form label-position="top">
      <el-form-item label="业务别名（逗号分隔，供 AI 子图召回）">
        <el-input
          v-model="aiForm.aliasesText"
          :disabled="!canManage"
          placeholder="例如 有效成交额, 净GMV, 剔除退单销售额"
        />
      </el-form-item>
      <el-form-item label="歧义辨析（何时用 / 何时不要用）">
        <el-input
          v-model="aiForm.disambiguation"
          :disabled="!canManage"
          type="textarea"
          :rows="2"
          placeholder="例如 统计实际经营收入使用本指标；若含未支付订单请改用 metric.order.gross_gmv"
        />
      </el-form-item>
      <el-form-item label="数据粒度与防扇出提醒（Grain）">
        <el-input
          v-model="aiForm.grain"
          :disabled="!canManage"
          placeholder="例如 订单粒度聚合，严禁与 1:N 物流明细直接关联求和"
        />
      </el-form-item>
      <el-form-item label="已知口径坑与历史变更（每行一条）">
        <el-input
          v-model="aiForm.caveatsText"
          :disabled="!canManage"
          type="textarea"
          :rows="2"
          placeholder="严禁录入任何 L3/L4 患者/客户明文 PII（系统保存时自动拦截）"
        />
      </el-form-item>
      <el-form-item label="典型业务问法（每行一条）">
        <el-input v-model="aiForm.typicalQuestionsText" :disabled="!canManage" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item v-if="!isHighSensitivity" label="安全枚举样例（逗号分隔，仅限 L1/L2）">
        <el-input v-model="aiForm.safeEnumExamplesText" :disabled="!canManage" placeholder="PAID, COMPLETED" />
      </el-form-item>
      <div v-if="canManage" class="save-row">
        <el-button type="primary" size="small" :loading="saving" @click="saveAiContext">
          保存 AI 业务理解卡
        </el-button>
      </div>
    </el-form>

    <el-divider />

    <div class="dict-header">
      <span class="section-title">Value Linking 安全值字典（维度口语值 → 物理码值）</span>
      <el-tag size="small" :type="isHighSensitivity ? 'danger' : 'success'">
        敏感级 {{ semanticObject.maxSensitivity || 'L1' }}
      </el-tag>
    </div>

    <el-alert
      v-if="isHighSensitivity"
      type="warning"
      :closable="false"
      show-icon
      title="CONSTITUTION C5 红线：当前对象投影敏感级为 L3/L4，已禁止采集或维护维度值字典。"
      style="margin-bottom: var(--dg-space-md)"
    />

    <template v-else>
      <div v-if="canManage" class="dict-add-row">
        <el-input v-model="dictForm.rawValue" size="small" placeholder="物理码值，如 EAST" />
        <el-input v-model="dictForm.displayLabel" size="small" placeholder="业务展示名，如 华东" />
        <el-input v-model="dictForm.synonymsText" size="small" placeholder="同义词，如 华东区, 华东大区" />
        <el-button type="primary" size="small" @click="addDictEntry">添加</el-button>
      </div>
      <el-table v-loading="dictLoading" :data="dictEntries" size="small" border>
        <el-table-column prop="rawValue" label="物理码值" width="140">
          <template #default="{ row }">
            <span class="mono">{{ row.rawValue }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="displayLabel" label="展示名" width="140" />
        <el-table-column label="同义词">
          <template #default="{ row }">
            <el-tag
              v-for="s in row.synonyms || []"
              :key="s"
              size="small"
              type="info"
              style="margin-right: 4px"
            >
              {{ s }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="canManage" label="操作" width="72" align="right">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="deleteDictEntry(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </div>
</template>

<style scoped>
.save-row {
  display: flex;
  justify-content: flex-end;
}
.dict-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--dg-space-sm);
}
.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--dg-text);
}
.dict-add-row {
  display: grid;
  grid-template-columns: 1fr 1fr 1.5fr auto;
  gap: var(--dg-space-sm);
  margin-bottom: var(--dg-space-md);
}
.mono {
  font-family: var(--dg-font-mono);
}
</style>
