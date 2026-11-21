<script setup lang="ts">
import type { Component } from 'vue'
import type { AgentUiBlock, AgentUiBlockComponent } from '@/types/agent'
import DistributionProfileCard from './DistributionProfileCard.vue'
import FilterFunnelCard from './FilterFunnelCard.vue'
import RuleDiffProposalCard from './RuleDiffProposalCard.vue'

/**
 * C7 宿主白名单声明式画布渲染器。
 * 严格限定仅渲染白名单注册表内的 Vue 组件，拒绝任何未知组件或可执行脚本注入。
 */
defineOptions({ name: 'AgentUiBlockRenderer' })

const props = defineProps<{
  blocks?: AgentUiBlock[]
}>()

const WHITELIST: Record<AgentUiBlockComponent, Component> = {
  FilterFunnelCard,
  DistributionProfileCard,
  RuleDiffProposalCard,
}

function resolveComponent(name: string): Component | null {
  return (WHITELIST as Record<string, Component>)[name] ?? null
}
</script>

<template>
  <div v-if="props.blocks?.length" class="dg-ui-block-list">
    <template v-for="(block, idx) in props.blocks" :key="`${block.component}-${idx}`">
      <component
        :is="resolveComponent(block.component)"
        v-if="resolveComponent(block.component)"
        v-bind="block.props"
      />
    </template>
  </div>
</template>

<style scoped>
.dg-ui-block-list {
  display: flex;
  flex-direction: column;
  gap: var(--dg-space-sm);
  margin: var(--dg-space-sm) 0;
}
</style>
