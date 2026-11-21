<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { mappingProposalApi, type MappingProposal } from '@/api/mappingProposal'
import { confirmAction } from '@/utils/confirm'
import PageHeader from '@/components/common/PageHeader.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'

defineOptions({ name: 'MappingInboxView' })

const router = useRouter()
const auth = useAuthStore()

const rows = ref<MappingProposal[]>([])
const loading = ref(false)
const error = ref('')
const stateFilter = ref('PROPOSED')
const amendUrn = ref<Record<string, string>>({})
const decisionNote = ref<Record<string, string>>({})

const canReview = computed(() => auth.hasPermission('governance:knowledge:review'))

function parseEvidence(json?: string): Record<string, unknown> {
  if (!json) return {}
  try {
    return JSON.parse(json) as Record<string, unknown>
  } catch {
    return {}
  }
}

function asProposal(row: unknown): MappingProposal {
  return row as MappingProposal
}

function t0Warning(row: unknown): string {
  const ev = parseEvidence(asProposal(row).evidenceJson)
  const w = ev.t0_trap_warning
  return typeof w === 'string' && w.trim() ? w : ''
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = await mappingProposalApi.list(stateFilter.value || undefined)
  } catch (e) {
    rows.value = []
    error.value = e instanceof Error ? e.message : '加载映射收件箱失败'
  } finally {
    loading.value = false
  }
}

async function approve(raw: unknown) {
  if (!canReview.value) return
  const row = asProposal(raw)
  const note = (decisionNote.value[row.id] || '').trim()
  if (row.routingTier === 'block_and_escalate' && !note) {
    ElMessage.warning('block_and_escalate 必须填写宿主确认备注（C7）')
    return
  }
  const ok = await confirmAction(
    `确认采纳「${row.sourceTable}.${row.sourceField}」→ ${amendUrn.value[row.id] || row.recommendedBusinessUrn || '（空）'}？`,
    '审校通过映射建议',
  )
  if (!ok) return
  try {
    await mappingProposalApi.approve(row.id, note || undefined, amendUrn.value[row.id] || undefined)
    ElMessage.success('已通过')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '通过失败')
  }
}

async function reject(raw: unknown) {
  if (!canReview.value) return
  const row = asProposal(raw)
  const note = (decisionNote.value[row.id] || '').trim()
  if (!note) {
    ElMessage.warning('驳回必须填写理由（写入防重提账本）')
    return
  }
  const ok = await confirmAction(`驳回「${row.sourceTable}.${row.sourceField}」并记录指纹防重提？`, '驳回映射建议')
  if (!ok) return
  try {
    await mappingProposalApi.reject(row.id, note)
    ElMessage.success('已驳回')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '驳回失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="page-container mapping-inbox">
    <PageHeader
      title="映射建议收件箱"
      description="AI/启发式字段→本体映射工单；确认后才沉淀，驳回指纹防重提（C7/C9）"
    >
      <template #actions>
        <el-button @click="router.push('/workbench/inbox')">返回治理收件箱</el-button>
        <el-button type="primary" :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <el-select v-model="stateFilter" style="width: 160px; margin-bottom: var(--dg-space-md)" @change="load">
      <el-option label="待审校 PROPOSED" value="PROPOSED" />
      <el-option label="已通过 APPROVED" value="APPROVED" />
      <el-option label="已驳回 REJECTED" value="REJECTED" />
      <el-option label="全部" value="" />
    </el-select>

    <el-alert v-if="error" type="error" :title="error" show-icon class="mapping-inbox__alert" />

    <el-table v-if="loading || rows.length" v-loading="loading" :data="rows" size="small" border>
      <el-table-column label="源字段" min-width="160">
        <template #default="{ row }">
          <code>{{ row.sourceTable }}.{{ row.sourceField }}</code>
        </template>
      </el-table-column>
      <el-table-column label="推荐 URN" min-width="220">
        <template #default="{ row }">
          <el-input
            v-if="row.state === 'PROPOSED' && canReview"
            v-model="amendUrn[row.id]"
            :placeholder="row.recommendedBusinessUrn || '可改写 URN'"
            size="small"
          />
          <span v-else>{{ row.recommendedBusinessUrn || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="confidence" label="置信度" width="90" />
      <el-table-column prop="routingTier" label="路由" width="150" />
      <el-table-column prop="state" label="状态" width="110" />
      <el-table-column label="T₀ 陷阱" min-width="180">
        <template #default="{ row }">
          <el-tag v-if="t0Warning(row)" type="danger" size="small" effect="plain">
            {{ t0Warning(row) }}
          </el-tag>
          <span v-else class="mapping-inbox__muted">无</span>
        </template>
      </el-table-column>
      <el-table-column label="审校备注" min-width="160">
        <template #default="{ row }">
          <el-input
            v-if="asProposal(row).state === 'PROPOSED' && canReview"
            v-model="decisionNote[asProposal(row).id]"
            type="textarea"
            :rows="2"
            placeholder="通过备注 / 驳回理由"
            size="small"
          />
          <span v-else>{{ asProposal(row).decisionNote || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="canReview" label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <template v-if="asProposal(row).state === 'PROPOSED'">
            <el-button link type="primary" @click="approve(row)">通过</el-button>
            <el-button link type="danger" @click="reject(row)">驳回</el-button>
          </template>
          <span v-else class="mapping-inbox__muted">—</span>
        </template>
      </el-table-column>
    </el-table>

    <EmptyState v-else description="当前筛选下没有映射建议" />
  </div>
</template>

<style scoped>
.mapping-inbox__alert {
  margin-bottom: var(--dg-space-md);
}

.mapping-inbox__muted {
  color: var(--dg-text-muted);
  font-size: 12px;
}
</style>
