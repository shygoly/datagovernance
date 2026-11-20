<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { semanticApi } from '@/api/semantic'
import { insightApi } from '@/api/insight'
import type { InsightAnswer } from '@/types/insight'
import type {
  CompiledSemanticQuery,
  MultiWarehousePlan,
  SemanticObject,
  SemanticSubgraphResult,
} from '@/types/semantic'

const props = defineProps<{
  allObjects: SemanticObject[]
}>()

const mode = ref<'subgraph' | 'compile' | 'multi'>('subgraph')
const loading = ref(false)
const executing = ref(false)

const subgraphForm = reactive({
  query: '',
  maxHops: 2,
  externalModel: false,
})

const compileForm = reactive({
  metrics: [] as string[],
  dimensions: [] as string[],
  includeCohort: true,
  limit: 50,
})

const subgraphResult = ref<SemanticSubgraphResult | null>(null)
const compiledResult = ref<CompiledSemanticQuery | null>(null)
const multiPlanResult = ref<MultiWarehousePlan | null>(null)
const previewAnswer = ref<InsightAnswer | null>(null)

const publishedMetrics = computed(() =>
  props.allObjects.filter((o) => o.status === 'PUBLISHED' && o.objectType === 'METRIC'),
)
const publishedDims = computed(() =>
  props.allObjects.filter(
    (o) =>
      o.status === 'PUBLISHED' &&
      (o.objectType === 'DIMENSION' || o.objectType === 'ATTRIBUTE'),
  ),
)

async function runSubgraph() {
  if (!subgraphForm.query.trim()) {
    ElMessage.warning('请输入测试问题或关键词')
    return
  }
  loading.value = true
  try {
    subgraphResult.value = await semanticApi.searchSubgraph({
      query: subgraphForm.query.trim(),
      maxHops: subgraphForm.maxHops,
      topK: 6,
      externalModel: subgraphForm.externalModel,
    })
  } finally {
    loading.value = false
  }
}

async function runCompile() {
  if (!compileForm.metrics.length && !compileForm.dimensions.length) {
    ElMessage.warning('请至少选择一个已发布指标或维度')
    return
  }
  loading.value = true
  previewAnswer.value = null
  try {
    compiledResult.value = await semanticApi.compileSemantic({
      metrics: compileForm.metrics,
      dimensions: compileForm.dimensions,
      limit: compileForm.limit,
    })
  } finally {
    loading.value = false
  }
}

async function runMultiPlan() {
  if (!compileForm.metrics.length && !compileForm.dimensions.length) {
    ElMessage.warning('请至少选择一个已发布指标或维度')
    return
  }
  loading.value = true
  try {
    const dims = [...compileForm.dimensions]
    if (compileForm.includeCohort && !dims.includes('attr.system.cohort')) {
      dims.unshift('attr.system.cohort')
    }
    multiPlanResult.value = await semanticApi.compileMultiWarehousePlan({
      metrics: compileForm.metrics,
      dimensions: dims,
      limit: compileForm.limit,
    })
  } finally {
    loading.value = false
  }
}

async function runPreview() {
  if (!compileForm.metrics.length && !compileForm.dimensions.length) {
    return
  }
  executing.value = true
  try {
    previewAnswer.value = await insightApi.ask({
      question: `调试台试跑: ${compileForm.metrics.join(', ')}`,
      semanticSpec: {
        metrics: compileForm.metrics,
        dimensions: compileForm.dimensions,
        limit: Math.min(20, compileForm.limit),
      },
    })
  } finally {
    executing.value = false
  }
}
</script>

<template>
  <div class="playground">
    <div class="left-pane">
      <el-radio-group v-model="mode" size="small" style="margin-bottom: var(--dg-space-md)">
        <el-radio-button value="subgraph">AI 子图召回</el-radio-button>
        <el-radio-button value="compile">单仓 SQL 编译</el-radio-button>
        <el-radio-button value="multi">多仓 / 联邦计划</el-radio-button>
      </el-radio-group>

      <el-form v-if="mode === 'subgraph'" label-position="top">
        <el-form-item label="自然语言问数 / 业务词">
          <el-input
            v-model="subgraphForm.query"
            type="textarea"
            :rows="3"
            placeholder="例如：上月华东区的有效GMV按区域统计"
          />
        </el-form-item>
        <el-form-item label="最大跳数（1-3 跳）">
          <el-input-number v-model="subgraphForm.maxHops" :min="1" :max="3" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="subgraphForm.externalModel">
            模拟外部公网模型（自动剥离 L3/L4 样例，验证 C5）
          </el-checkbox>
        </el-form-item>
        <el-button type="primary" size="small" :loading="loading" @click="runSubgraph">
          运行逐跳子图召回
        </el-button>
      </el-form>

      <el-form v-else label-position="top">
        <el-form-item label="已发布指标（METRIC）">
          <el-select
            v-model="compileForm.metrics"
            multiple
            filterable
            placeholder="选择指标"
            style="width: 100%"
          >
            <el-option
              v-for="m in publishedMetrics"
              :key="m.code"
              :label="`${m.name} (${m.code})`"
              :value="m.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="分组维度 / 属性">
          <el-select
            v-model="compileForm.dimensions"
            multiple
            filterable
            placeholder="选择维度或属性"
            style="width: 100%"
          >
            <el-option
              v-for="d in publishedDims"
              :key="d.code"
              :label="`${d.name} (${d.code})`"
              :value="d.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="mode === 'multi'">
          <el-checkbox v-model="compileForm.includeCohort">
            自动附带队列分组维度（attr.system.cohort · 对比 MIMIC / eICU 等）
          </el-checkbox>
        </el-form-item>
        <el-form-item label="返回行数上限">
          <el-input-number v-model="compileForm.limit" :min="1" :max="500" />
        </el-form-item>
        <div class="btn-row">
          <el-button
            v-if="mode === 'compile'"
            type="primary"
            size="small"
            :loading="loading"
            @click="runCompile"
          >
            确定性编译 SQL
          </el-button>
          <el-button
            v-else
            type="primary"
            size="small"
            :loading="loading"
            @click="runMultiPlan"
          >
            生成多仓 / 联邦编译计划
          </el-button>
          <el-button
            size="small"
            :disabled="!compiledResult && !multiPlanResult"
            :loading="executing"
            @click="runPreview"
          >
            只读试跑（前 20 行）
          </el-button>
        </div>
      </el-form>
    </div>

    <div class="right-pane">
      <template v-if="mode === 'subgraph'">
        <el-empty v-if="!subgraphResult" description="输入业务问题后点击“运行逐跳子图召回”" />
        <div v-else class="result-stack">
          <div class="meta-bar">
            <el-tag size="small">命中节点 {{ subgraphResult.nodes.length }}</el-tag>
            <el-tag size="small" type="info">关系边 {{ subgraphResult.links.length }}</el-tag>
            <el-tag v-if="subgraphResult.prunedNodeCount > 0" size="small" type="warning">
              ACL 剪枝 {{ subgraphResult.prunedNodeCount }}
            </el-tag>
          </div>
          <el-table :data="subgraphResult.nodes" size="small" border>
            <el-table-column prop="hopDistance" label="Hop" width="60" />
            <el-table-column prop="name" label="名称" width="140" />
            <el-table-column prop="code" label="编码" min-width="160">
              <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
            </el-table-column>
            <el-table-column prop="objectType" label="类型" width="100" />
            <el-table-column prop="maxSensitivity" label="敏感级" width="76" />
          </el-table>
        </div>
      </template>

      <template v-else-if="mode === 'multi'">
        <el-empty v-if="!multiPlanResult" description="选择指标与维度后点击“生成多仓 / 联邦编译计划”" />
        <div v-else class="result-stack">
          <div class="meta-bar">
            <el-tag size="small" type="primary">{{ multiPlanResult.mode }}</el-tag>
            <el-tag size="small" type="warning">引擎: {{ multiPlanResult.federationEngine }}</el-tag>
            <el-tag size="small" type="success" class="mono">
              SHA-256: {{ multiPlanResult.auditFingerprint.slice(0, 14) }}…
            </el-tag>
          </div>
          <div v-for="sq in multiPlanResult.subQueries" :key="sq.warehouseId" class="excl-box">
            <div class="sub-label">
              分仓 SQL · {{ sq.warehouseName }}（{{ sq.cohortCode || sq.warehouseCode }} · {{ sq.dialect }}）
            </div>
            <pre class="sql-box">{{ sq.sql }}</pre>
          </div>
          <template v-if="multiPlanResult.federatedJoinPlan">
            <div
              v-for="ext in multiPlanResult.federatedJoinPlan.extracts"
              :key="ext.alias"
              class="excl-box"
            >
              <div class="sub-label">
                源仓提取 · {{ ext.warehouseName }}（{{ ext.dialect }} → {{ ext.alias }}）
              </div>
              <pre class="sql-box">{{ ext.extractSql }}</pre>
            </div>
            <div class="excl-box">
              <div class="sub-label">
                最终联邦 JOIN SQL（{{ multiPlanResult.federatedJoinPlan.engine }}）
              </div>
              <pre class="sql-box">{{ multiPlanResult.federatedJoinPlan.federatedSql }}</pre>
            </div>
          </template>
        </div>
      </template>

      <template v-else>
        <el-empty v-if="!compiledResult" description="选择指标与维度后点击“确定性编译 SQL”" />
        <div v-else class="result-stack">
          <div class="meta-bar">
            <el-tag size="small">{{ compiledResult.dialect }}</el-tag>
            <el-tag v-if="compiledResult.fanoutCteUsed" size="small" type="warning">
              已启用防扇出 CTE
            </el-tag>
            <el-tag size="small" type="success" class="mono">
              SHA-256: {{ compiledResult.auditFingerprint.slice(0, 14) }}…
            </el-tag>
          </div>
          <div v-if="compiledResult.appliedExclusions.length" class="excl-box">
            <div class="sub-label">自动生效口径排除/默认过滤</div>
            <el-tag
              v-for="ex in compiledResult.appliedExclusions"
              :key="ex"
              size="small"
              type="warning"
              style="margin: 2px 4px 2px 0"
            >
              {{ ex }}
            </el-tag>
          </div>
          <pre class="sql-box">{{ compiledResult.sql }}</pre>
          <div v-if="previewAnswer" class="preview-box">
            <div class="sub-label">试跑结果（{{ previewAnswer.state }} · {{ previewAnswer.summary }}）</div>
            <el-table
              v-if="previewAnswer.rows.length"
              :data="previewAnswer.rows"
              size="small"
              border
              max-height="240"
            >
              <el-table-column
                v-for="col in previewAnswer.columns"
                :key="col.bizName || col.name"
                :prop="col.bizName || col.name"
                :label="col.name"
                show-overflow-tooltip
              />
            </el-table>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.playground {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: var(--dg-space-lg);
}
.left-pane,
.right-pane {
  padding: var(--dg-space-md);
  background: var(--dg-surface-muted);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
}
.btn-row {
  display: flex;
  gap: var(--dg-space-sm);
}
.meta-bar {
  display: flex;
  flex-wrap: wrap;
  gap: var(--dg-space-sm);
  margin-bottom: var(--dg-space-md);
}
.sub-label {
  font-size: 12px;
  color: var(--dg-text-muted);
  margin-bottom: 4px;
}
.sql-box {
  margin: var(--dg-space-sm) 0;
  padding: var(--dg-space-md);
  background: var(--dg-surface);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
  font-family: var(--dg-font-mono);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--dg-text);
}
.excl-box,
.preview-box {
  margin-top: var(--dg-space-sm);
}
.mono {
  font-family: var(--dg-font-mono);
}
</style>
