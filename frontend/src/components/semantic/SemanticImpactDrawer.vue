<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { semanticApi } from '@/api/semantic'
import type { SemanticImpactResult } from '@/types/semantic'

const props = defineProps<{
  visible: boolean
  datasetId?: string
  columnId?: string
  title?: string
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
}>()

const router = useRouter()
const loading = ref(false)
const result = ref<SemanticImpactResult | null>(null)

async function load() {
  if (!props.columnId && !props.datasetId) {
    result.value = null
    return
  }
  loading.value = true
  try {
    result.value = await semanticApi.impact({
      columnId: props.columnId || undefined,
      datasetId: props.datasetId || undefined,
    })
  } finally {
    loading.value = false
  }
}

function goSemantic(objectId: string) {
  emit('update:visible', false)
  void router.push({ path: '/metadata/semantic', query: { objectId } })
}

watch(
  () => [props.visible, props.datasetId, props.columnId] as const,
  ([vis]) => {
    if (vis) void load()
  },
  { immediate: true },
)
</script>

<template>
  <el-drawer
    :model-value="visible"
    size="48%"
    :title="title || '语义影响分析'"
    @update:model-value="(val: boolean) => emit('update:visible', val)"
  >
    <div v-loading="loading">
      <el-empty
        v-if="!result?.impactedObjects?.length"
        description="当前物理资产尚未绑定任何逻辑语义对象"
      />
      <el-table v-else :data="result.impactedObjects" size="small" border>
        <el-table-column prop="depth" label="层级" width="66">
          <template #default="{ row }">
            <el-tag size="small" :type="row.depth === 0 ? 'primary' : 'warning'">
              +{{ row.depth }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="受影响语义对象" min-width="140" />
        <el-table-column prop="code" label="编码" min-width="160">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column prop="objectType" label="类型" width="100" />
        <el-table-column prop="relationPath" label="影响链路" min-width="170" show-overflow-tooltip />
        <el-table-column label="操作" width="76" align="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="goSemantic(row.objectId)">
              查看
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </el-drawer>
</template>

<style scoped>
.mono {
  font-family: var(--dg-font-mono);
}
</style>
