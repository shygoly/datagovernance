<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { agentApi } from '@/api/agent'
import type { AgentActionProposal } from '@/types/agent'
import { formatDateTime } from '@/utils/format'

/**
 * 宿主独立高危操作确认弹窗（Host Consent UI，闭合 OWASP ASI09）。
 *
 * 所有字段均直接来源于后端落库的不可篡改提案对象，而非 Agent 聊天流自然语言；
 * 点击确认时携当前用户真实 JWT + payloadDigest 交由 Java 后端二次核验后执行。
 */
defineOptions({ name: 'AgentConsentDialog' })

const props = defineProps<{
  proposal: AgentActionProposal | null
}>()

const emit = defineEmits<{
  (e: 'resolved', proposal: AgentActionProposal): void
  (e: 'dismiss'): void
}>()

const note = ref('')
const submitting = ref(false)
const errorText = ref('')

const visible = computed({
  get: () => Boolean(props.proposal && props.proposal.status === 'PENDING'),
  set: (value: boolean) => {
    if (!value) {
      emit('dismiss')
    }
  },
})

const payloadRows = computed(() => {
  const payload = props.proposal?.payload ?? {}
  return Object.entries(payload).map(([key, value]) => ({
    key,
    value: typeof value === 'string' ? value : JSON.stringify(value),
  }))
})

const riskTagType = computed<'danger' | 'warning' | 'info'>(() => {
  if (props.proposal?.riskLevel === 'HIGH') return 'danger'
  if (props.proposal?.riskLevel === 'MEDIUM') return 'warning'
  return 'info'
})

async function handleConfirm() {
  if (!props.proposal || submitting.value) {
    return
  }
  submitting.value = true
  errorText.value = ''
  try {
    const result = await agentApi.confirmProposal(props.proposal.id, {
      payloadDigest: props.proposal.payloadDigest,
      note: note.value.trim() || undefined,
    })
    ElMessage.success(`已确认并执行：${result.title}`)
    note.value = ''
    emit('resolved', result)
  } catch (err) {
    errorText.value = err instanceof Error ? err.message : '确认执行失败，请重试'
  } finally {
    submitting.value = false
  }
}

async function handleReject() {
  if (!props.proposal || submitting.value) {
    return
  }
  submitting.value = true
  errorText.value = ''
  try {
    const result = await agentApi.rejectProposal(props.proposal.id, {
      note: note.value.trim() || '用户在宿主确认界面拒绝',
    })
    ElMessage.info(`已拒绝提案：${result.title}`)
    note.value = ''
    emit('resolved', result)
  } catch (err) {
    errorText.value = err instanceof Error ? err.message : '拒绝提案失败'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="宿主安全确认（非聊天流授权）"
    width="560px"
    :close-on-click-modal="false"
    append-to-body
  >
    <div v-if="proposal" class="consent">
      <el-alert
        type="warning"
        show-icon
        :closable="false"
        title="该操作将修改平台配置或元数据，请核对下列后端客观参数后再确认执行。"
      />

      <div class="consent__header">
        <span class="consent__title">{{ proposal.title }}</span>
        <el-tag :type="riskTagType" size="small">风险：{{ proposal.riskLevel }}</el-tag>
      </div>

      <p class="consent__summary">{{ proposal.summary }}</p>

      <el-descriptions :column="1" size="small" border class="consent__meta">
        <el-descriptions-item label="动作类型">
          <code class="consent__mono">{{ proposal.actionType }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="所需权限">
          <code class="consent__mono">{{ proposal.requiredPermission }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="有效期至">
          {{ formatDateTime(proposal.expiresAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="载荷摘要 (SHA-256)">
          <code class="consent__mono consent__digest">{{ proposal.payloadDigest }}</code>
        </el-descriptions-item>
      </el-descriptions>

      <div class="consent__section-title">待执行参数</div>
      <el-table :data="payloadRows" size="small" border class="consent__table">
        <el-table-column prop="key" label="参数" width="150" />
        <el-table-column prop="value" label="值" show-overflow-tooltip>
          <template #default="{ row }">
            <code class="consent__mono">{{ row.value }}</code>
          </template>
        </el-table-column>
      </el-table>

      <el-input
        v-model="note"
        type="textarea"
        :rows="2"
        maxlength="300"
        placeholder="可选备注（将写入不可变审计日志）"
      />

      <el-alert
        v-if="errorText"
        type="error"
        show-icon
        :closable="false"
        :title="errorText"
      />
    </div>

    <template #footer>
      <el-button :disabled="submitting" @click="handleReject">拒绝</el-button>
      <el-button type="primary" :loading="submitting" @click="handleConfirm">
        确认并执行
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.consent {
  display: flex;
  flex-direction: column;
  gap: var(--dg-space-md);
}

.consent__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dg-space-sm);
}

.consent__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--dg-text);
}

.consent__summary {
  margin: 0;
  font-size: 13px;
  color: var(--dg-text-secondary);
}

.consent__section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--dg-text);
}

.consent__mono {
  font-family: var(--dg-font-mono);
  font-size: 12px;
  color: var(--dg-text);
}

.consent__digest {
  word-break: break-all;
}
</style>
