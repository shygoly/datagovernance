<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Lock } from '@element-plus/icons-vue'
import { lineageApi } from '@/api/lineage'
import type { LineageDiffItem, LineageJobDiff } from '@/types/lineage'

/**
 * 作业血缘历史变更对比（ACTIVE vs 最近一批 SUPERSEDED / 表达式改写）。
 *
 * 数据来自 GET /metadata/lineage/jobs/{jobId}/diff；后端已按逐跳 ACL 处理受限行
 * （名称为「[受限资产]」、表达式隐藏），这里只做展示，不再自行判断权限。
 */
const props = defineProps<{
  modelValue: boolean
  jobId: string
  jobName?: string | null
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const loading = ref(false)
const error = ref('')
const diff = ref<LineageJobDiff | null>(null)
let requestSeq = 0

async function load() {
  if (!props.jobId) {
    diff.value = null
    return
  }
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  diff.value = null
  try {
    const result = await lineageApi.jobDiff(props.jobId)
    if (seq === requestSeq) {
      diff.value = result
    }
  } catch {
    if (seq === requestSeq) {
      error.value = '历史变更加载失败，请稍后重试'
    }
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

watch(
  () => [props.modelValue, props.jobId] as const,
  ([open]) => {
    if (open) {
      void load()
    }
  },
  { immediate: true },
)

const counts = computed(() => ({
  added: diff.value?.added.length ?? 0,
  removed: diff.value?.removed.length ?? 0,
  modified: diff.value?.modifiedTransform.length ?? 0,
}))

const noChange = computed(
  () => !!diff.value && diff.value.hasBaseline && !counts.value.added && !counts.value.removed && !counts.value.modified,
)

function formatTime(value?: string | null): string {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}

function edgeTitle(item: LineageDiffItem): string {
  return `${item.fromName || item.fromNodeId} → ${item.toName || item.toNodeId}`
}

/** 修改行里真正发生变化的维度，用于小标签提示。 */
function changedFields(item: LineageDiffItem): string[] {
  const fields: string[] = []
  if ((item.previousRelation || item.relation) !== item.relation) {
    fields.push('关系')
  }
  if ((item.previousDependencyType || item.dependencyType) !== item.dependencyType) {
    fields.push('依赖类型')
  }
  if ((item.previousTransformExpr ?? '') !== (item.transformExpr ?? '')) {
    fields.push('转换表达式')
  }
  return fields
}
</script>

<template>
  <el-drawer
    v-model="visible"
    size="640px"
    :title="`历史变更对比 · ${jobName || jobId}`"
    destroy-on-close
    append-to-body
  >
    <div v-loading="loading" class="job-diff">
      <el-alert
        v-if="error"
        type="error"
        show-icon
        :closable="false"
        :title="error"
        class="job-diff__alert"
      >
        <el-button link type="primary" @click="load">重试</el-button>
      </el-alert>

      <template v-else-if="diff">
        <div class="job-diff__summary">
          <el-tag type="success" effect="plain">新增 {{ counts.added }}</el-tag>
          <el-tag type="danger" effect="plain">下线 {{ counts.removed }}</el-tag>
          <el-tag type="warning" effect="plain">修改 {{ counts.modified }}</el-tag>
          <span class="job-diff__meta">
            当前 ACTIVE 边 {{ diff.activeEdges }} 条
            <template v-if="diff.hasBaseline"> · 变更批次 {{ formatTime(diff.baselineAt) }}</template>
          </span>
        </div>

        <el-alert
          v-if="!diff.hasBaseline"
          type="info"
          show-icon
          :closable="false"
          title="该作业尚无历史改动可对比"
          description="只有当某次上报让边下线、或改写了转换表达式后，才会产生可对比的版本；首次上报的边不会被当作“新增”。"
          class="job-diff__alert"
        />
        <el-alert
          v-else-if="noChange"
          type="info"
          show-icon
          :closable="false"
          title="最近一批改动已被后续上报抵消，当前与历史一致"
          class="job-diff__alert"
        />
        <el-alert
          v-if="diff.truncatedByAcl"
          type="warning"
          show-icon
          :closable="false"
          :title="`有 ${diff.restrictedCount} 条变更触及受限资产，已按最小权限隐藏名称与表达式`"
          class="job-diff__alert"
        />

        <section v-if="counts.added" class="job-diff__section">
          <h4 class="job-diff__title job-diff__title--added">新增（{{ counts.added }}）</h4>
          <div v-for="item in diff.added" :key="item.edgeId" class="diff-row diff-row--added">
            <div class="diff-row__edge">
              <el-icon v-if="item.restricted"><Lock /></el-icon>
              {{ edgeTitle(item) }}
            </div>
            <div v-if="!item.restricted" class="diff-row__meta">
              {{ item.relation }} · {{ item.dependencyType }}
            </div>
            <pre v-if="item.transformExpr" class="diff-row__code">{{ item.transformExpr }}</pre>
          </div>
        </section>

        <section v-if="counts.removed" class="job-diff__section">
          <h4 class="job-diff__title job-diff__title--removed">下线（{{ counts.removed }}）</h4>
          <div v-for="item in diff.removed" :key="item.edgeId" class="diff-row diff-row--removed">
            <div class="diff-row__edge">
              <el-icon v-if="item.restricted"><Lock /></el-icon>
              {{ edgeTitle(item) }}
            </div>
            <div v-if="!item.restricted" class="diff-row__meta">
              {{ item.relation }} · {{ item.dependencyType }}
            </div>
            <pre v-if="item.transformExpr" class="diff-row__code">{{ item.transformExpr }}</pre>
          </div>
        </section>

        <section v-if="counts.modified" class="job-diff__section">
          <h4 class="job-diff__title job-diff__title--modified">修改（{{ counts.modified }}）</h4>
          <div v-for="item in diff.modifiedTransform" :key="item.edgeId" class="diff-row diff-row--modified">
            <div class="diff-row__edge">
              <el-icon v-if="item.restricted"><Lock /></el-icon>
              {{ edgeTitle(item) }}
              <el-tag
                v-for="field in changedFields(item)"
                :key="field"
                size="small"
                type="warning"
                effect="plain"
              >
                {{ field }}
              </el-tag>
            </div>
            <template v-if="!item.restricted">
              <div class="diff-pair">
                <div class="diff-pair__side">
                  <div class="diff-pair__label">变更前</div>
                  <div class="diff-row__meta">
                    {{ item.previousRelation || item.relation }} ·
                    {{ item.previousDependencyType || item.dependencyType }}
                  </div>
                  <pre class="diff-row__code diff-row__code--before">{{ item.previousTransformExpr || '（无表达式）' }}</pre>
                </div>
                <div class="diff-pair__side">
                  <div class="diff-pair__label">变更后</div>
                  <div class="diff-row__meta">{{ item.relation }} · {{ item.dependencyType }}</div>
                  <pre class="diff-row__code diff-row__code--after">{{ item.transformExpr || '（无表达式）' }}</pre>
                </div>
              </div>
            </template>
          </div>
        </section>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.job-diff {
  min-height: 160px;
}

.job-diff__alert {
  margin-bottom: var(--dg-space-md);
}

.job-diff__summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--dg-space-sm);
  margin-bottom: var(--dg-space-md);
}

.job-diff__meta {
  margin-left: auto;
  font-size: 12px;
  color: var(--dg-text-muted);
}

.job-diff__section {
  margin-bottom: var(--dg-space-lg);
}

.job-diff__title {
  margin: 0 0 var(--dg-space-sm);
  font-size: 13px;
  font-weight: 600;
}

.job-diff__title--added {
  color: var(--dg-success);
}

.job-diff__title--removed {
  color: var(--dg-danger);
}

.job-diff__title--modified {
  color: var(--dg-warning);
}

.diff-row {
  margin-bottom: var(--dg-space-sm);
  padding: var(--dg-space-sm) var(--dg-space-md);
  border: 1px solid var(--dg-border);
  border-left-width: 3px;
  border-radius: var(--dg-radius);
  background: var(--dg-surface);
}

.diff-row--added {
  border-left-color: var(--dg-success);
}

.diff-row--removed {
  border-left-color: var(--dg-danger);
}

.diff-row--modified {
  border-left-color: var(--dg-warning);
}

.diff-row__edge {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--dg-space-xs);
  font-size: 13px;
  font-weight: 600;
  color: var(--dg-text);
  word-break: break-all;
}

.diff-row__meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--dg-text-muted);
}

.diff-row__code {
  margin: var(--dg-space-xs) 0 0;
  padding: var(--dg-space-xs) var(--dg-space-sm);
  max-height: 160px;
  overflow: auto;
  background: var(--dg-surface-muted);
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
  font-family: var(--dg-font-mono);
  font-size: 12px;
  line-height: 1.6;
  color: var(--dg-text);
  white-space: pre-wrap;
  word-break: break-all;
}

.diff-row__code--before {
  background: var(--dg-danger-soft, var(--dg-surface-muted));
}

.diff-row__code--after {
  background: var(--dg-success-soft, var(--dg-surface-muted));
}

.diff-pair {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--dg-space-sm);
  margin-top: var(--dg-space-xs);
}

.diff-pair__label {
  font-size: 12px;
  font-weight: 600;
  color: var(--dg-text-secondary);
}
</style>
