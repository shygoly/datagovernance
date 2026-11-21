<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { catalogAssetApi } from '@/api/catalog-assets'
import type { CatalogColumn, CatalogDataset, CatalogSource } from '@/types/catalog-assets'
import type { LineageNodeRef } from '@/types/lineage'

/**
 * 选择一个字段级血缘节点：数据集 → 字段，或手工输入限定名（外部节点，如 file://）。
 * v-model 为后端 NodeRef 形状，未选完整时为 null。
 */
const model = defineModel<LineageNodeRef | null>({ default: null })

const external = ref(false)
const externalName = ref('')
const sources = ref<CatalogSource[]>([])
const datasetOptions = ref<CatalogDataset[]>([])
const columns = ref<CatalogColumn[]>([])
const datasetId = ref('')
const columnId = ref('')
const searching = ref(false)
const columnsLoading = ref(false)

let timer: ReturnType<typeof setTimeout> | null = null
let sequence = 0
let sourcesLoaded = false

const selectedDataset = computed(
  () => datasetOptions.value.find((item) => item.id === datasetId.value) || null,
)

async function ensureSources() {
  if (sourcesLoaded) {
    return
  }
  sourcesLoaded = true
  try {
    sources.value = await catalogAssetApi.listSources()
  } catch {
    sources.value = []
  }
}

async function search(keyword: string) {
  const current = ++sequence
  searching.value = true
  try {
    await ensureSources()
    const term = keyword.trim()
    const results = await Promise.allSettled(
      sources.value.map((source) =>
        catalogAssetApi.listDatasets(source.id, {
          page: 1,
          size: term ? 30 : 15,
          keyword: term || undefined,
        }),
      ),
    )
    if (current !== sequence) {
      return
    }
    const next = new Map<string, CatalogDataset>()
    if (selectedDataset.value) {
      next.set(selectedDataset.value.id, selectedDataset.value)
    }
    for (const result of results) {
      if (result.status === 'fulfilled') {
        for (const item of result.value.records) {
          next.set(item.id, item)
        }
      }
    }
    datasetOptions.value = [...next.values()]
      .sort((a, b) => a.qualifiedName.localeCompare(b.qualifiedName))
      .slice(0, 80)
  } finally {
    if (current === sequence) {
      searching.value = false
    }
  }
}

function onSearch(keyword: string) {
  if (timer) {
    clearTimeout(timer)
  }
  timer = setTimeout(() => void search(keyword), 260)
}

async function onDatasetChange(id: string) {
  columnId.value = ''
  columns.value = []
  model.value = null
  if (!id) {
    return
  }
  columnsLoading.value = true
  try {
    columns.value = await catalogAssetApi.listColumns(id)
  } catch {
    columns.value = []
  } finally {
    columnsLoading.value = false
  }
}

function emitSelection() {
  if (external.value) {
    const name = externalName.value.trim()
    model.value = name ? { nodeType: 'COLUMN', qualifiedName: name, displayName: name } : null
    return
  }
  const dataset = selectedDataset.value
  const column = columns.value.find((item) => item.id === columnId.value)
  model.value =
    dataset && column
      ? {
          nodeType: 'COLUMN',
          qualifiedName: `${dataset.qualifiedName}.${column.name}`,
          catalogDatasetId: dataset.id,
          catalogColumnId: column.id,
          displayName: `${dataset.name}.${column.name}`,
        }
      : null
}

function onOpen(visible: boolean) {
  if (visible && !datasetOptions.value.length) {
    void search('')
  }
}

/** 外部重置（对话框重新打开）。 */
function reset() {
  external.value = false
  externalName.value = ''
  datasetId.value = ''
  columnId.value = ''
  columns.value = []
  model.value = null
}

watch(external, emitSelection)

onBeforeUnmount(() => {
  if (timer) {
    clearTimeout(timer)
  }
})

defineExpose({ reset })
</script>

<template>
  <div class="node-picker">
    <template v-if="!external">
      <el-select
        v-model="datasetId"
        filterable
        remote
        clearable
        reserve-keyword
        placeholder="搜索并选择数据集"
        :remote-method="onSearch"
        :loading="searching"
        @visible-change="onOpen"
        @change="onDatasetChange"
      >
        <el-option
          v-for="item in datasetOptions"
          :key="item.id"
          :label="item.qualifiedName"
          :value="item.id"
        />
      </el-select>
      <el-select
        v-model="columnId"
        filterable
        placeholder="选择字段"
        :disabled="!datasetId"
        :loading="columnsLoading"
        @change="emitSelection"
      >
        <el-option
          v-for="column in columns"
          :key="column.id"
          :label="`${column.name} · ${column.rawType || column.canonicalType || '-'}`"
          :value="column.id"
        />
      </el-select>
    </template>
    <el-input
      v-else
      v-model="externalName"
      placeholder="外部节点限定名，例如 file://source/field 或 db.schema.table.column"
      @input="emitSelection"
    />
    <el-checkbox v-model="external" class="node-picker__external">
      目录中没有该字段，手工输入限定名
    </el-checkbox>
  </div>
</template>

<style scoped>
.node-picker {
  display: flex;
  flex-direction: column;
  gap: var(--dg-space-sm);
  width: 100%;
}

.node-picker__external {
  height: auto;
  color: var(--dg-text-muted);
}
</style>
