<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { chanceAnalyticsApi, type ChanceBoardResponse } from '@/api/chanceAnalytics'

defineOptions({ name: 'ChanceBoardsView' })

const tab = ref('cohort')
const loading = ref(false)
const cohort = ref<ChanceBoardResponse | null>(null)
const pathway = ref<ChanceBoardResponse | null>(null)
const survival = ref<ChanceBoardResponse | null>(null)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    ;[cohort.value, pathway.value, survival.value] = await Promise.all([
      chanceAnalyticsApi.cohortBaseline(),
      chanceAnalyticsApi.treatmentPathway(),
      chanceAnalyticsApi.survival('OS'),
    ])
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function rowsOf(board: ChanceBoardResponse | null, key: string): Array<Record<string, unknown>> {
  const v = board?.data?.[key]
  return Array.isArray(v) ? (v as Array<Record<string, unknown>>) : []
}
</script>

<template>
  <div class="page-container chance-boards">
    <header class="chance-boards__head">
      <h1>CHANCE 专病分析看板</h1>
      <p>队列基线 · 治疗路径 · 随访生存（synthea: 专病表；依赖 T₀ session_seq）</p>
    </header>

    <el-alert v-if="error" type="error" :title="error" show-icon class="chance-boards__alert" />
    <el-skeleton v-if="loading" animated :rows="6" />

    <el-tabs v-else v-model="tab">
      <el-tab-pane label="队列与基线" name="cohort">
        <el-alert
          v-for="(w, i) in cohort?.warnings || []"
          :key="'cw' + i"
          type="warning"
          :title="w"
          show-icon
          class="chance-boards__alert"
        />
        <h3>纳排漏斗</h3>
        <el-table :data="rowsOf(cohort, 'funnel')" size="small">
          <el-table-column prop="label" label="步骤" />
          <el-table-column prop="count" label="人数" width="120" />
        </el-table>
        <h3>CNLC 分布（Table 1）</h3>
        <el-table :data="rowsOf(cohort, 'cnlcDistribution')" size="small">
          <el-table-column prop="key" label="分期" />
          <el-table-column prop="count" label="例数" width="120" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="治疗路径" name="pathway">
        <el-alert
          v-for="(w, i) in pathway?.warnings || []"
          :key="'pw' + i"
          type="warning"
          :title="w"
          show-icon
          class="chance-boards__alert"
        />
        <h3>方式占比</h3>
        <el-table :data="rowsOf(pathway, 'modalityShare')" size="small">
          <el-table-column prop="key" label="modality" />
          <el-table-column prop="count" label="次数" width="120" />
        </el-table>
        <h3>Sankey 边（session_seq → modality）</h3>
        <el-table :data="rowsOf(pathway, 'sankeyLinks')" size="small">
          <el-table-column prop="source" label="source" />
          <el-table-column prop="target" label="target" />
          <el-table-column prop="value" label="value" width="100" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="随访与生存" name="survival">
        <el-alert
          v-for="(w, i) in survival?.warnings || []"
          :key="'sw' + i"
          type="warning"
          :title="w"
          show-icon
          class="chance-boards__alert"
        />
        <h3>风险集</h3>
        <pre class="chance-boards__pre">{{ survival?.data?.riskSet }}</pre>
        <h3>KM 阶梯（MVP）</h3>
        <el-table :data="rowsOf(survival, 'kmSteps')" size="small">
          <el-table-column prop="timeMonths" label="月" width="80" />
          <el-table-column prop="survival" label="S(t)" width="100" />
          <el-table-column prop="atRisk" label="atRisk" width="100" />
        </el-table>
        <h3>mRECIST / ORR</h3>
        <el-table :data="rowsOf(survival, 'orr')" size="small">
          <el-table-column prop="key" label="疗效" />
          <el-table-column prop="count" label="例次" width="120" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.chance-boards__head h1 {
  margin: 0 0 0.25rem;
  color: var(--dg-text-primary);
  font-size: var(--dg-font-size-xl, 1.25rem);
}
.chance-boards__head p {
  margin: 0 0 1rem;
  color: var(--dg-text-secondary);
}
.chance-boards__alert {
  margin-bottom: 0.75rem;
}
.chance-boards__pre {
  background: var(--dg-bg-muted, var(--el-fill-color-light));
  padding: 0.75rem;
  border-radius: var(--dg-radius-sm, 6px);
  color: var(--dg-text-primary);
}
h3 {
  margin: 1rem 0 0.5rem;
  color: var(--dg-text-primary);
  font-size: var(--dg-font-size-md, 1rem);
}
</style>
