import { Type } from '@earendil-works/pi-ai'
import type { AgentTool } from '@earendil-works/pi-agent-core'
import { config } from './config.js'
import { isToolPermittedBySkills, type SkillDefinition } from './skills.js'
import type { ProposalSummary, RunContext } from './types.js'

interface ApiEnvelope<T> {
  success: boolean
  code?: string
  message?: string
  data?: T
}

export interface NavigateTarget {
  route: string
  label: string
  query?: Record<string, string>
}

export interface ToolCallbacks {
  skills: Map<string, SkillDefinition>
  activeSkills: Set<string>
  onSkillActivated?: (skill: SkillDefinition) => void
  onNavigate?: (target: NavigateTarget) => void
  onConsentRequired?: (proposal: ProposalSummary) => void
}

const NAVIGABLE_ROUTES = new Set([
  '/metadata/datasources',
  '/metadata/catalog',
  '/metadata/semantic',
  '/insight/ask',
])

async function callBackend<T>(context: RunContext, path: string,
                              init: RequestInit = {}): Promise<T> {
  if (!context.userId || !context.userId.trim()) {
    throw new Error('缺少真实用户上下文 userId，拒绝调用平台工具（CONSTITUTION C4）')
  }
  const response = await fetch(config.backendUrl + path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      'X-Agent-Token': config.agentToken,
      // 工具端点的调用者不是 Node 服务，而是发起这次对话的真实用户。
      // 这些头由 Java 代理注入 userId 后随会话带下来，Java 侧再按 userId 重新解析权限。
      'X-Agent-User-Id': context.userId,
      'X-Agent-Username': context.username,
      'X-Agent-Workspace-Id': context.workspaceId,
      'X-Agent-Admin': String(Boolean(context.platformAdmin)),
      ...(init.headers ?? {}),
    },
    signal: AbortSignal.timeout(180_000),
  })
  const body = (await response.json()) as ApiEnvelope<T>
  if (!response.ok || !body.success) {
    throw new Error(body.message || `平台接口返回 ${response.status}`)
  }
  return body.data as T
}

function text(value: unknown) {
  return [{ type: 'text' as const, text: typeof value === 'string' ? value : JSON.stringify(value) }]
}

function toolResult(summary: string, data: Record<string, unknown> = {}) {
  return {
    content: text({ summary, ...data }),
    details: { summary, ...data },
  }
}

function toolError(message: string) {
  return {
    content: text(message),
    details: { error: message },
    isError: true,
  }
}

/**
 * 写操作统一生成 PENDING 提案（闭合 OWASP ASI09）：
 * 1. 先检查当前会话是否已激活授权该工具的 SKILL.md（A0.2 allowed-tools）；
 * 2. 调用 Java /api/v1/internal/agent/proposals 落库 PENDING 提案（不直接修改平台数据）；
 * 3. 通过 SSE consent_required 推给前端宿主渲染独立确认弹窗；
 * 4. 返回 PENDING_CONSENT 结果要求模型停下等待用户在宿主 UI 点击确认。
 */
async function proposeWriteAction(
  toolName: string,
  actionType: string,
  payload: Record<string, unknown>,
  context: RunContext,
  callbacks: ToolCallbacks,
) {
  const gate = isToolPermittedBySkills(toolName, callbacks.activeSkills, callbacks.skills)
  if (!gate.allowed) {
    const hint = gate.requiredSkillHint
      ? `请先调用 activate_skill("${gate.requiredSkillHint}") 加载对应技能规程后再发起操作。`
      : '当前会话未激活包含该写工具的技能包。'
    return toolError(`工具 ${toolName} 尚未解锁（A0.2 最小工具作用域）：${hint}`)
  }
  try {
    const proposal = await callBackend<ProposalSummary>(
      context,
      '/api/v1/internal/agent/proposals',
      {
        method: 'POST',
        body: JSON.stringify({
          conversationId: context.conversationId,
          actionType,
          payload,
        }),
      },
    )
    callbacks.onConsentRequired?.(proposal)
    return toolResult(
      `已生成待宿主确认提案「${proposal.title}」（ID: ${proposal.id}），等待用户在宿主界面确认后执行`,
      {
        pendingConsent: true,
        proposal,
        instruction: '写操作尚未落库执行。请用简洁中文向用户展示待确认参数，并提示用户在右侧/弹窗的宿主确认框中点击“确认执行”或“拒绝”。严禁在未确认前声称已完成。',
      },
    )
  } catch (error) {
    return toolError(`生成写操作提案失败：${messageOf(error)}`)
  }
}

/**
 * Agent 工具只调用平台内部工具端点，不直接碰数据库/业务数据源。
 * 权限、脱敏、审计、宿主确认和 workspace 校验都在 Java 侧完成。
 */
export function buildTools(context: RunContext, callbacks: ToolCallbacks): AgentTool<any>[] {
  const activateSkill: AgentTool<any> = {
    name: 'activate_skill',
    label: '激活技能规程',
    description: '按需加载指定 SKILL.md 技能的完整操作步骤（Level 2 渐进披露）并解锁其最小作用域工具。',
    parameters: Type.Object({
      name: Type.String({ description: '技能名称（kebab-case，来自系统提示中的技能索引）' }),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const name = String(rawParams?.name ?? '').trim()
      const skill = callbacks.skills.get(name)
      if (!skill) {
        return toolError(`未找到技能「${name}」，可用技能：${[...callbacks.skills.keys()].join(', ') || '无'}`)
      }
      callbacks.activeSkills.add(skill.name)
      callbacks.onSkillActivated?.(skill)
      return toolResult(`已加载技能「${skill.name}」`, {
        name: skill.name,
        allowedTools: skill.allowedTools,
        instructions: skill.body,
      })
    },
  }

  const listDatasets: AgentTool<any> = {
    name: 'list_datasets',
    label: '列出数据集',
    description: '列出当前空间可用的数据集/主题域。回答“有哪些表/数据集/数据域”时使用。',
    parameters: Type.Object({
      keyword: Type.Optional(Type.String({ description: '可选关键词，模糊过滤数据集名称或数据域' })),
      domain: Type.Optional(Type.String({ description: '可选精确数据域名；问“这个数据域”时传入' })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as { keyword?: string; domain?: string }
      try {
        const query = new URLSearchParams({
          workspaceId: context.workspaceId,
          username: context.username,
        })
        if (params.keyword?.trim()) {
          query.set('keyword', params.keyword.trim())
        }
        const datasets = await callBackend<Array<{ id: number; name: string; domain: string }>>(
          context,
          `/api/v1/internal/agent/datasets?${query.toString()}`,
        )
        let filtered = datasets
        if (params.domain?.trim()) {
          filtered = datasets.filter((item) => item.domain === params.domain!.trim())
        } else if (params.keyword?.trim()) {
          filtered = datasets.filter((item) =>
            `${item.domain} ${item.name}`.toLowerCase().includes(params.keyword!.trim().toLowerCase()))
        }
        return {
          content: text({ count: filtered.length, datasets: filtered }),
          details: { datasets: filtered },
        }
      } catch (error) {
        return toolError(`列出数据集失败：${messageOf(error)}`)
      }
    },
  }

  const askData: AgentTool<any> = {
    name: 'ask_data',
    label: '数据查询',
    description: '经平台原生语义本体引擎发起受治理的自然语言数据查询（确定性编译 + 脱敏 + 审计指纹）。回答统计、趋势、排名、金额等问题时使用。',
    parameters: Type.Object({
      question: Type.String({ description: '完整、明确的数据问题' }),
      dataSetId: Type.Optional(Type.Number({ description: '可选语义主题 ID；不填使用当前选中的主题' })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as { question: string; dataSetId?: number }
      try {
        const payload = {
          workspaceId: context.workspaceId,
          username: context.username,
          question: params.question,
          dataSetId: params.dataSetId ?? context.datasetId,
        }
        const answer = await callBackend<any>(context, '/api/v1/internal/agent/ask', {
          method: 'POST',
          body: JSON.stringify(payload),
        })
        const rows = Array.isArray(answer?.rows) ? answer.rows.slice(0, 20) : []
        return {
          content: text({
            state: answer?.state ?? 'SUCCESS',
            summary: answer?.summary ?? '查询完成',
            rowCount: answer?.rowCount ?? rows.length,
            columns: answer?.columns ?? [],
            rows,
            maskedColumns: answer?.maskedColumns ?? [],
            semanticSnapshotRefs: answer?.semanticSnapshotRefs ?? [],
            auditFingerprint: answer?.auditFingerprint ?? null,
            appliedExclusions: answer?.appliedExclusions ?? [],
            clarifications: answer?.clarifications ?? [],
            executedSpec: answer?.executedSpec ?? null,
            chartSpec: answer?.chartSpec ?? null,
          }),
          details: { answer },
        }
      } catch (error) {
        return toolError(`数据查询失败：${messageOf(error)}`)
      }
    },
  }

  const searchSemanticSubgraph: AgentTool<any> = {
    name: 'search_semantic_subgraph',
    label: '检索语义知识子图',
    description: '在平台单一语义本体与业务知识图谱中做 k-Hop 受控子图检索（逐跳 ACL 剪枝），获取已发布指标、维度、物理映射、口径避坑与排除关系。',
    parameters: Type.Object({
      query: Type.String({ description: '业务关键词、指标名或用户自然语言问题' }),
      maxHops: Type.Optional(Type.Number({ description: '最大跳数（1-3，默认 2）' })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as { query: string; maxHops?: number }
      try {
        const result = await callBackend<any>(context, '/api/v1/internal/agent/semantic/subgraph', {
          method: 'POST',
          body: JSON.stringify({
            query: params.query,
            maxHops: params.maxHops ?? 2,
            topK: 6,
            externalModel: true,
          }),
        })
        return {
          content: text(result),
          details: { subgraph: result },
        }
      } catch (error) {
        return toolError(`语义子图检索失败：${messageOf(error)}`)
      }
    },
  }

  const getSemanticObject: AgentTool<any> = {
    name: 'get_semantic_object',
    label: '查看语义对象详情',
    description: '按 Object ID 读取单个语义对象的完整定义、Standard 绑定与 AI 业务理解卡（只读）。',
    parameters: Type.Object({
      objectId: Type.String({ description: '语义对象 ID' }),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as { objectId: string }
      try {
        const obj = await callBackend<any>(
          context,
          `/api/v1/internal/agent/semantic/objects/${encodeURIComponent(params.objectId)}`,
        )
        return {
          content: text(obj),
          details: { semanticObject: obj },
        }
      } catch (error) {
        return toolError(`读取语义对象失败：${messageOf(error)}`)
      }
    },
  }

  const executeSemanticQuery: AgentTool<any> = {
    name: 'execute_semantic_query',
    label: '执行结构化语义查询',
    description: '基于已发布语义对象编码（SemanticQuerySpec）发起确定性编译与只读查询，严禁心算指标或直写 SQL（CONSTITUTION C3）。',
    parameters: Type.Object({
      metrics: Type.Array(Type.String({ description: '已发布指标 code 列表' })),
      dimensions: Type.Optional(Type.Array(Type.String({ description: '可选分组维度/属性 code 列表' }))),
      filters: Type.Optional(Type.Array(Type.Object({
        objectCode: Type.String(),
        op: Type.String(),
        values: Type.Array(Type.String()),
      }))),
      timeRange: Type.Optional(Type.Object({
        dimensionCode: Type.String(),
        start: Type.String(),
        end: Type.String(),
      })),
      limit: Type.Optional(Type.Number()),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      try {
        const answer = await callBackend<any>(context, '/api/v1/internal/agent/semantic/query', {
          method: 'POST',
          body: JSON.stringify(rawParams ?? {}),
        })
        const rows = Array.isArray(answer?.rows) ? answer.rows.slice(0, 20) : []
        return {
          content: text({
            state: answer?.state ?? 'SUCCESS',
            summary: answer?.summary ?? '执行完成',
            sql: answer?.sql ?? null,
            rowCount: answer?.rowCount ?? rows.length,
            columns: answer?.columns ?? [],
            rows,
            maskedColumns: answer?.maskedColumns ?? [],
            semanticSnapshotRefs: answer?.semanticSnapshotRefs ?? [],
            auditFingerprint: answer?.auditFingerprint ?? null,
            appliedExclusions: answer?.appliedExclusions ?? [],
            executedSpec: answer?.executedSpec ?? null,
            chartSpec: answer?.chartSpec ?? null,
          }),
          details: { answer },
        }
      } catch (error) {
        return toolError(`结构化语义查询失败：${messageOf(error)}`)
      }
    },
  }

  const createLocalDataSource: AgentTool<any> = {
    name: 'create_local_datasource',
    label: '提案：创建本地数据源',
    description: '提交创建 LOCAL 数据源的待确认提案（需先激活 parquet-local-onboarding 技能，并经宿主界面确认后才执行）。',
    parameters: Type.Object({
      name: Type.String({ description: '数据源名称，空间内唯一' }),
      rootPath: Type.String({ description: '后端进程能访问的绝对目录，例如 /data/lakehouse 或 /mnt/lake' }),
      description: Type.Optional(Type.String({ description: '可选描述' })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as { name: string; rootPath: string; description?: string }
      return proposeWriteAction(
        'create_local_datasource',
        'CREATE_LOCAL_DATASOURCE',
        { name: params.name, rootPath: params.rootPath, description: params.description },
        context,
        callbacks,
      )
    },
  }

  const updateLocalDataSource: AgentTool<any> = {
    name: 'update_local_datasource',
    label: '提案：修改本地数据源',
    description: '提交修改 LOCAL 数据源配置的待确认提案（需经宿主确认后执行）。',
    parameters: Type.Object({
      dataSourceId: Type.String({ description: '数据源 ID' }),
      name: Type.Optional(Type.String({ description: '新名称；不填保持原值' })),
      rootPath: Type.Optional(Type.String({ description: '新的绝对目录；不填保持原值' })),
      description: Type.Optional(Type.String({ description: '新描述' })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as {
        dataSourceId: string
        name?: string
        rootPath?: string
        description?: string
      }
      return proposeWriteAction(
        'update_local_datasource',
        'UPDATE_LOCAL_DATASOURCE',
        {
          dataSourceId: params.dataSourceId,
          name: params.name,
          rootPath: params.rootPath,
          description: params.description,
        },
        context,
        callbacks,
      )
    },
  }

  const testDataSource: AgentTool<any> = {
    name: 'test_datasource',
    label: '测试数据源连接',
    description: '对数据源执行连通性测试。LOCAL 类型会检查目录是否存在且是目录；成功后才进入可用状态。',
    parameters: Type.Object({
      dataSourceId: Type.String({ description: '数据源 ID' }),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as { dataSourceId: string }
      try {
        const result = await callBackend<any>(context,
          `/api/v1/internal/agent/onboarding/local-datasource/${encodeURIComponent(params.dataSourceId)}/test`, {
            method: 'POST',
          })
        return {
          content: text({
            success: result.success,
            message: result.message,
            latencyMillis: result.latencyMillis,
            serverVersion: result.serverVersion,
          }),
          details: {
            summary: result.success
              ? `连接成功（${result.latencyMillis} ms）`
              : `连接失败：${result.message}`,
            result,
          },
          isError: !result.success,
        }
      } catch (error) {
        return toolError(`测试数据源连接失败：${messageOf(error)}`)
      }
    },
  }

  const listCatalogSources: AgentTool<any> = {
    name: 'list_catalog_sources',
    label: '列出目录来源',
    description: '列出当前空间已有的数据目录来源，用于判断名称是否重复或复用已有来源。',
    parameters: Type.Object({}),
    execute: async () => {
      try {
        const sources = await callBackend<any[]>(context,
          '/api/v1/internal/agent/onboarding/catalog-sources')
        return toolResult(`返回 ${sources.length} 个目录来源`, {
          sources: sources.map((source) => ({
            id: source.id,
            name: source.name,
            sourceType: source.sourceType,
            dataSourceId: source.dataSourceId,
            lastRefreshedAt: source.lastRefreshedAt,
          })),
        })
      } catch (error) {
        return toolError(`列出目录来源失败：${messageOf(error)}`)
      }
    },
  }

  const createParquetCatalog: AgentTool<any> = {
    name: 'create_parquet_catalog_source',
    label: '提案：创建 Parquet 目录来源',
    description: '提交创建 PARQUET_DIRECTORY 目录来源的待确认提案（需经宿主界面确认后执行）。',
    parameters: Type.Object({
      name: Type.String({ description: '目录来源名称，空间内唯一' }),
      dataSourceId: Type.String({ description: 'LOCAL 数据源 ID' }),
      rootPath: Type.Optional(Type.String({ description: '可选子目录；留空使用数据源根目录' })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as { name: string; dataSourceId: string; rootPath?: string }
      return proposeWriteAction(
        'create_parquet_catalog_source',
        'CREATE_PARQUET_CATALOG',
        {
          name: params.name,
          dataSourceId: params.dataSourceId,
          rootPath: params.rootPath,
        },
        context,
        callbacks,
      )
    },
  }

  const refreshCatalogSource: AgentTool<any> = {
    name: 'refresh_catalog_source',
    label: '提案：采集目录',
    description: '提交采集 Parquet 目录元数据的待确认提案（需经宿主确认后执行；只读元数据、不复制数据）。',
    parameters: Type.Object({
      sourceId: Type.String({ description: '目录来源 ID' }),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as { sourceId: string }
      return proposeWriteAction(
        'refresh_catalog_source',
        'REFRESH_PARQUET_CATALOG',
        { sourceId: params.sourceId },
        context,
        callbacks,
      )
    },
  }

  const navigateWorkspace: AgentTool<any> = {
    name: 'navigate_workspace',
    label: '切换工作页面',
    description: '把用户的工作页面切换到指定路由，用于让用户看到数据源/数据目录等真实页面。'
      + '只允许 /metadata/datasources、/metadata/catalog、/insight/ask。',
    parameters: Type.Object({
      route: Type.String({ description: '目标路由' }),
      label: Type.String({ description: '给用户看的页面名称，例如“数据源”或“数据目录”' }),
      query: Type.Optional(Type.Object({
        sourceId: Type.Optional(Type.String({ description: '数据目录来源 ID，用于自动选中来源' })),
        highlight: Type.Optional(Type.String({ description: '数据源 ID，用于高亮刚创建的数据源' })),
      })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as {
        route: string
        label: string
        query?: { sourceId?: string; highlight?: string }
      }
      if (!NAVIGABLE_ROUTES.has(params.route)) {
        return toolError(`不允许切换到路由：${params.route}`)
      }
      const query: Record<string, string> = {}
      if (params.query?.sourceId) {
        query.sourceId = params.query.sourceId
      }
      if (params.query?.highlight) {
        query.highlight = params.query.highlight
      }
      callbacks.onNavigate?.({ route: params.route, label: params.label, query })
      return toolResult(`已切换到${params.label}`, {
        navigated: true,
        route: params.route,
        label: params.label,
        query,
      })
    },
  }

  return [
    activateSkill,
    listDatasets,
    askData,
    searchSemanticSubgraph,
    getSemanticObject,
    executeSemanticQuery,
    createLocalDataSource,
    updateLocalDataSource,
    testDataSource,
    listCatalogSources,
    createParquetCatalog,
    refreshCatalogSource,
    navigateWorkspace,
  ]
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
