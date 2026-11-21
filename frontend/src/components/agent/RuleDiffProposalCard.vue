<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { evolutionApi } from '@/api/evolution'

defineOptions({ name: 'RuleDiffProposalCard' })

const props = defineProps<{
  proposalId?: string
  title?: string
  assetRef?: string
  factType?: string
  state?: string
  patternFingerprint?: string | null
  evidenceRef?: string
  contentJson?: string
}>()

const currentState = ref(props.state || 'PROPOSED')
const reviewNote = ref('')
const submitting = ref(false)
const confirmMode = ref<'approve' | 'reject' | null>(null)

const formattedDelta = computed(() => {
  if (!props.contentJson) return '{}'
  try {
    return JSON.stringify(JSON.parse(props.contentJson), null, 2)
  } catch {
    return props.contentJson
  }
})

const stateTagType = computed(() => {
  if (currentState.value === 'APPROVED') return 'success'
  if (currentState.value === 'REJECTED') return 'danger'
  if (currentState.value === 'ROLLED_BACK') return 'info'
  return 'warning'
})

async function submitDecision() {
  if (!props.proposalId || !confirmMode.value) return
  if (confirmMode.value === 'reject' && !reviewNote.value.trim()) {
    ElMessage.warning('驳回演进提案必须填写理由（将写入防重提账本）')
    return
  }
  submitting.value = true
  try {
    if (confirmMode.value === 'approve') {
      const updated = await evolutionApi.approveProposal(
        props.proposalId,
        reviewNote.value.trim() || '宿主界面人工审校通过',
      )
      currentState.value = updated.state
      ElMessage.success('已双时间态晋升至权威层')
    } else {
      const updated = await evolutionApi.rejectProposal(props.proposalId, reviewNote.value.trim())
      currentState.value = updated.state
      ElMessage.success('已驳回并写入防重提账本')
    }
    confirmMode.value = null
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="dg-diff-card">
    <div class="dg-diff-card__header">
      <div class="dg-diff-card__heading">
        <span class="dg-diff-card__title">{{ title || '候选演进提案 Diff' }}</span>
        <el-tag size="small" :type="stateTagType">{{ currentState }}</el-tag>
      </div>
      <div class="dg-diff-card__meta">
        <span v-if="assetRef">{{ assetRef }}</span>
        <span v-if="patternFingerprint" class="dg-diff-card__fp">{{ patternFingerprint }}</span>
      </div>
    </div>

    <pre class="dg-diff-card__pre">{{ formattedDelta }}</pre>

    <div v-if="proposalId && currentState === 'PROPOSED'" class="dg-diff-card__footer">
      <div v-if="!confirmMode" class="dg-diff-card__actions">
        <el-button size="small" type="primary" @click="confirmMode = 'approve'">
          审校晋升
        </el-button>
        <el-button size="small" type="danger" plain @click="confirmMode = 'reject'">
          驳回并防重提
        </el-button>
      </div>
      <div v-else class="dg-diff-card__confirm">
        <el-input
          v-model="reviewNote"
          size="small"
          :placeholder="confirmMode === 'reject' ? '必填：驳回理由（写入 maintenance-log 防重提）' : '可选：审校备注'"
        />
        <div class="dg-diff-card__actions">
          <el-button
            size="small"
            :type="confirmMode === 'approve' ? 'primary' : 'danger'"
            :loading="submitting"
            @click="submitDecision"
          >
            确认{{ confirmMode === 'approve' ? '晋升' : '驳回' }}
          </el-button>
          <el-button size="small" link @click="confirmMode = null">取消</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dg-diff-card {
  border: 1px solid var(--dg-border);
  border-radius: var(--dg-radius);
  background: var(--dg-surface);
  padding: var(--dg-space-md);
}
.dg-diff-card__header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
  margin-bottom: var(--dg-space-sm);
}
.dg-diff-card__heading {
  display: inline-flex;
  align-items: center;
  gap: var(--dg-space-sm);
}
.dg-diff-card__title {
  font-weight: 600;
  font-size: 13px;
  color: var(--dg-text);
}
.dg-diff-card__meta {
  display: inline-flex;
  gap: var(--dg-space-sm);
  font-size: 12px;
  color: var(--dg-text-secondary);
  font-family: var(--dg-font-mono);
}
.dg-diff-card__fp {
  padding: 1px 6px;
  border-radius: var(--dg-radius-sm);
  background: var(--dg-surface-muted);
}
.dg-diff-card__pre {
  margin: 0 0 var(--dg-space-sm);
  padding: var(--dg-space-sm);
  max-height: 220px;
  overflow: auto;
  border-radius: var(--dg-radius-sm);
  background: var(--dg-surface-muted);
  border: 1px solid var(--dg-border);
  font-family: var(--dg-font-mono);
  font-size: 11px;
  color: var(--dg-text);
}
.dg-diff-card__footer,
.dg-diff-card__confirm {
  display: flex;
  flex-direction: column;
  gap: var(--dg-space-sm);
}
.dg-diff-card__actions {
  display: flex;
  gap: var(--dg-space-sm);
}
</style>
