import type { UiBlock, UiBlockComponent } from './types.js'

/**
 * C7 宿主白名单声明式组件注册表：
 * 仅允许白名单内的结构化视图块通过 SSE `ui_block` 下发，
 * 严禁任何可执行 HTML / JS / Vue 代码注入。
 */
export const ALLOWED_UI_BLOCK_COMPONENTS: ReadonlySet<UiBlockComponent> = new Set([
  'FilterFunnelCard',
  'DistributionProfileCard',
  'RuleDiffProposalCard',
])

const FORBIDDEN_EXECUTABLE_PATTERN =
  /<\s*script\b|javascript:|on(?:error|load|click)\s*=|v-html\b|<\s*iframe\b/i

const RAW_PHONE_PATTERN = /(?<!\d)1[3-9]\d{9}(?!\d)/
const RAW_IDCARD_PATTERN = /(?<!\d)\d{17}[0-9Xx](?!\d)/

export function containsForbiddenCode(value: unknown): boolean {
  if (typeof value === 'string') {
    return FORBIDDEN_EXECUTABLE_PATTERN.test(value)
  }
  if (Array.isArray(value)) {
    return value.some(containsForbiddenCode)
  }
  if (value && typeof value === 'object') {
    return Object.values(value as Record<string, unknown>).some(containsForbiddenCode)
  }
  return false
}

export function containsRawPii(text: string): boolean {
  return RAW_PHONE_PATTERN.test(text) || RAW_IDCARD_PATTERN.test(text)
}

/**
 * 校验并净化声明式 UI 块；不合法或不在白名单内的组件一律返回 null（降级为纯文本摘要）。
 */
export function validateUiBlock(raw: unknown): UiBlock | null {
  if (!raw || typeof raw !== 'object') {
    return null
  }
  const candidate = raw as Record<string, unknown>
  const component = String(candidate.component ?? '') as UiBlockComponent
  if (!ALLOWED_UI_BLOCK_COMPONENTS.has(component)) {
    return null
  }
  const props = candidate.props
  if (!props || typeof props !== 'object' || Array.isArray(props)) {
    return null
  }
  if (containsForbiddenCode(props)) {
    return null
  }
  return {
    component,
    version: '1',
    props: props as Record<string, unknown>,
  }
}

export function buildDistributionProfileBlock(profile: Record<string, unknown>): UiBlock | null {
  const anomalies = Array.isArray(profile.anomalies) ? profile.anomalies : []
  const nullRates = Array.isArray(profile.nullRates) ? profile.nullRates : []
  return validateUiBlock({
    component: 'DistributionProfileCard',
    version: '1',
    props: {
      profileRunId: profile.id ?? '',
      datasetId: profile.datasetId ?? '',
      datasetName: profile.datasetName ?? '',
      verdict: profile.verdict ?? 'INCONCLUSIVE',
      totalRows: Number(profile.totalRows ?? 0),
      truncated: Boolean(profile.truncated),
      anomalyCount: Number(profile.anomalyCount ?? anomalies.length),
      patternFingerprint: profile.patternFingerprint ?? null,
      nullRates: nullRates.slice(0, 12),
      anomalies: anomalies.slice(0, 10),
      standardRefs: Array.isArray(profile.standardRefs) ? profile.standardRefs : [],
    },
  })
}

export function buildRuleDiffProposalBlock(proposal: Record<string, unknown>): UiBlock | null {
  return validateUiBlock({
    component: 'RuleDiffProposalCard',
    version: '1',
    props: {
      proposalId: proposal.id ?? '',
      title: proposal.title ?? '候选演进提案',
      assetRef: proposal.assetRef ?? '',
      factType: proposal.factType ?? 'quality_convention',
      state: proposal.state ?? 'PROPOSED',
      patternFingerprint: proposal.patternFingerprint ?? null,
      evidenceRef: proposal.evidenceRef ?? '',
      contentJson: proposal.contentJson ?? '{}',
    },
  })
}
