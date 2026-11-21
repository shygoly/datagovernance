<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { lineageApi } from '@/api/lineage'
import type {
  LineageDependencyType,
  LineageEdge,
  LineageNodeRef,
} from '@/types/lineage'
import LineageNodePicker from './LineageNodePicker.vue'

/** 手工补录一条列级血缘。调用方负责用 metadata:lineage:manage 控制入口。 */
const emit = defineEmits<{ saved: [edge: LineageEdge] }>()

const visible = ref(false)
const submitting = ref(false)
const from = ref<LineageNodeRef | null>(null)
const to = ref<LineageNodeRef | null>(null)
const relation = ref('DERIVE')
const dependencyType = ref<LineageDependencyType>('DIRECT')
const transformExpr = ref('')
const confidence = ref(1)

const relationOptions = ['COPY', 'TRANSFORM', 'AGGREGATE', 'FILTER', 'JOIN', 'DERIVE']

const sameNode = computed(
  () =>
    !!from.value &&
    !!to.value &&
    from.value.qualifiedName.trim() === to.value.qualifiedName.trim(),
)
const canSubmit = computed(() => !!from.value && !!to.value && !sameNode.value)

function open() {
  from.value = null
  to.value = null
  relation.value = 'DERIVE'
  dependencyType.value = 'DIRECT'
  transformExpr.value = ''
  confidence.value = 1
  visible.value = true
}

async function onSubmit() {
  if (!from.value || !to.value || sameNode.value) {
    return
  }
  submitting.value = true
  try {
    const edge = await lineageApi.recordManualEdge({
      from: from.value,
      to: to.value,
      relation: relation.value.trim() || 'DERIVE',
      dependencyType: dependencyType.value,
      transformExpr: transformExpr.value.trim() || null,
      confidence: confidence.value,
      origin: 'MANUAL',
    })
    ElMessage.success('血缘已补录')
    visible.value = false
    emit('saved', edge)
  } finally {
    submitting.value = false
  }
}

defineExpose({ open })
</script>

<template>
  <el-dialog v-model="visible" title="手工补录血缘" width="640px" destroy-on-close>
    <el-form label-width="96px">
      <el-form-item label="源字段" required>
        <LineageNodePicker v-model="from" />
      </el-form-item>
      <el-form-item label="目标字段" required>
        <LineageNodePicker v-model="to" />
      </el-form-item>

      <el-alert
        v-if="sameNode"
        type="warning"
        show-icon
        :closable="false"
        title="源字段与目标字段不能相同"
        class="manual-lineage__alert"
      />

      <el-form-item label="关系类型">
        <el-select
          v-model="relation"
          filterable
          allow-create
          default-first-option
          placeholder="选择或输入关系"
        >
          <el-option v-for="item in relationOptions" :key="item" :label="item" :value="item" />
        </el-select>
      </el-form-item>

      <el-form-item label="依赖类型">
        <el-radio-group v-model="dependencyType">
          <el-radio-button value="DIRECT">DIRECT 直接依赖</el-radio-button>
          <el-radio-button value="INDIRECT">INDIRECT 间接依赖</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="转换表达式">
        <el-input
          v-model="transformExpr"
          type="textarea"
          :rows="3"
          placeholder="可选，例如 SUM(COALESCE(o.amount, 0))"
        />
      </el-form-item>

      <el-form-item label="置信度">
        <el-slider v-model="confidence" :min="0" :max="1" :step="0.05" show-input />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="onSubmit">
        补录
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.manual-lineage__alert {
  margin-bottom: var(--dg-space-md);
}
</style>
