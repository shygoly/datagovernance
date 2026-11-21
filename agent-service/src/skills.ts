import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'

/**
 * A0.2 渐进式技能包（AgentSkills.io SKILL.md）运行时加载器。
 *
 * Level 1（常驻系统前缀）：仅 name + description；
 * Level 2（按需激活）：调用 activate_skill 后加载正文并解锁该技能声明的 allowed-tools。
 */
export interface SkillDefinition {
  name: string
  description: string
  allowedTools: string[]
  body: string
  filePath: string
}

const DEFAULT_READ_ONLY_TOOLS = new Set([
  'activate_skill',
  'list_datasets',
  'ask_data',
  'test_datasource',
  'list_catalog_sources',
  'navigate_workspace',
  'list_mapping_proposals',
  'list_evolution_proposals',
  'list_clinical_qc_findings',
  'lineage_trace_column',
  'lineage_impact_analysis',
  'autonomy_compile_flow',
  'autonomy_trace_explain',
])

export function loadSkills(skillsDir: string): Map<string, SkillDefinition> {
  const result = new Map<string, SkillDefinition>()
  const root = resolve(skillsDir)
  if (!existsSync(root)) {
    return result
  }
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    if (!entry.isDirectory()) {
      continue
    }
    const skillPath = join(root, entry.name, 'SKILL.md')
    if (!existsSync(skillPath)) {
      continue
    }
    const parsed = parseSkillFile(skillPath)
    if (parsed && parsed.name === entry.name) {
      result.set(parsed.name, parsed)
    }
  }
  return result
}

export function parseSkillFile(filePath: string): SkillDefinition | null {
  const raw = readFileSync(filePath, 'utf-8')
  if (!raw.startsWith('---\n') && !raw.startsWith('---\r\n')) {
    return null
  }
  const normalized = raw.replace(/\r\n/g, '\n')
  const endIdx = normalized.indexOf('\n---\n', 4)
  if (endIdx < 0) {
    return null
  }
  const frontmatter = normalized.slice(4, endIdx)
  const body = normalized.slice(endIdx + 5).trim()
  const meta = parseSimpleYaml(frontmatter)
  const name = typeof meta.name === 'string' ? meta.name.trim() : ''
  const description = typeof meta.description === 'string' ? meta.description.trim() : ''
  if (!name || !description) {
    return null
  }
  const allowedTools = Array.isArray(meta['allowed-tools'])
    ? meta['allowed-tools'].map((item) => String(item).trim()).filter(Boolean)
    : typeof meta['allowed-tools'] === 'string'
      ? meta['allowed-tools'].split(',').map((item) => item.trim()).filter(Boolean)
      : []

  return {
    name,
    description,
    allowedTools,
    body,
    filePath,
  }
}

export function renderSkillIndex(skills: Map<string, SkillDefinition>): string {
  if (skills.size === 0) {
    return '（当前未注册技能包）'
  }
  const sorted = [...skills.values()].sort((a, b) => a.name.localeCompare(b.name))
  return sorted
    .map((skill) => `- \`${skill.name}\`: ${skill.description}`)
    .join('\n')
}

/**
 * 校验某工具在当前已激活技能集合下是否被允许调用。
 * 基础只读工具默认可用；写工具必须由当前会话已激活的技能显式声明于 allowed-tools。
 */
export function isToolPermittedBySkills(
  toolName: string,
  activeSkills: Iterable<string>,
  registry: Map<string, SkillDefinition>,
): { allowed: boolean; requiredSkillHint?: string } {
  if (DEFAULT_READ_ONLY_TOOLS.has(toolName)) {
    return { allowed: true }
  }
  for (const name of activeSkills) {
    const skill = registry.get(name)
    if (skill?.allowedTools.includes(toolName)) {
      return { allowed: true }
    }
  }
  const provider = [...registry.values()].find((s) => s.allowedTools.includes(toolName))
  return {
    allowed: false,
    requiredSkillHint: provider?.name,
  }
}

function parseSimpleYaml(src: string): Record<string, string | string[]> {
  const out: Record<string, string | string[]> = {}
  let currentListKey: string | null = null
  for (const rawLine of src.split('\n')) {
    const line = rawLine.trimEnd()
    if (!line.trim() || line.trim().startsWith('#')) {
      continue
    }
    const listMatch = line.match(/^\s*-\s+(.+)$/)
    if (listMatch && currentListKey) {
      const existing = out[currentListKey]
      const list = Array.isArray(existing) ? existing : []
      list.push(stripQuotes(listMatch[1].trim()))
      out[currentListKey] = list
      continue
    }
    const kvMatch = line.match(/^([a-zA-Z0-9_-]+):\s*(.*)$/)
    if (kvMatch) {
      const key = kvMatch[1]
      const val = kvMatch[2].trim()
      if (!val) {
        out[key] = []
        currentListKey = key
      } else {
        out[key] = stripQuotes(val)
        currentListKey = null
      }
    }
  }
  return out
}

function stripQuotes(value: string): string {
  if ((value.startsWith('"') && value.endsWith('"'))
      || (value.startsWith("'") && value.endsWith("'"))) {
    return value.slice(1, -1)
  }
  return value
}
