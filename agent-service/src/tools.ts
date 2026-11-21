import { Type } from '@earendil-works/pi-ai'
import type { AgentTool } from '@earendil-works/pi-agent-core'
import { config } from './config.js'
import { isToolPermittedBySkills, type SkillDefinition } from './skills.js'
import {
  buildDistributionProfileBlock,
  buildRuleDiffProposalBlock,
  validateUiBlock,
} from './uiBlocks.js'
import type { ProposalSummary, RunContext, UiBlock } from './types.js'

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
  onUiBlock?: (block: UiBlock) => void
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
      'X-Agent-Model-Endpoint-Kind': config.modelEndpointKind,
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

  const smqBodySchema = Type.Object({
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
    grain: Type.Optional(Type.String({ description: '可选时间/分析粒度，须 ∈ 度量 allowedGrains' })),
    atModifiers: Type.Optional(Type.Array(Type.Object({
      op: Type.String({ description: 'USING | SET_GRAIN' }),
      rel: Type.Optional(Type.String()),
      grain: Type.Optional(Type.String()),
    }))),
  })

  const getCatalogGrammar: AgentTool<any> = {
    name: 'get_catalog_grammar',
    label: '获取 Catalog Grammar',
    description: '拉取 ACL 裁剪后的问数目录约束（metrics.allowedDimensions/allowedGrains、dimensions、atModifiers）。生产默认仅 verified 度量；组装 SMQ 前优先调用（CONSTITUTION C5）。',
    parameters: Type.Object({
      productionOnly: Type.Optional(Type.Boolean({ description: '默认 true：仅返回已 verified 度量' })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = (rawParams ?? {}) as { productionOnly?: boolean }
      const productionOnly = params.productionOnly !== false
      try {
        const grammar = await callBackend<any>(
          context,
          `/api/v1/internal/agent/semantic/catalog_grammar?productionOnly=${productionOnly}`,
          { method: 'GET' },
        )
        return {
          content: text({
            metricCount: Array.isArray(grammar?.metrics) ? grammar.metrics.length : 0,
            dimensionCount: Array.isArray(grammar?.dimensions) ? grammar.dimensions.length : 0,
            filterOps: grammar?.filterOps ?? [],
            atModifiers: grammar?.atModifiers ?? [],
            metrics: grammar?.metrics ?? [],
            dimensions: grammar?.dimensions ?? [],
          }),
          details: { catalogGrammar: grammar },
        }
      } catch (error) {
        return toolError(`获取 Catalog Grammar 失败：${messageOf(error)}`)
      }
    },
  }

  const listMetrics: AgentTool<any> = {
    name: 'list_metrics',
    label: '列出规范度量',
    description: '从 Catalog Grammar 列出可枚举度量（含 allowedDimensions/allowedGrains/verified）。优先于盲猜 code。',
    parameters: Type.Object({
      productionOnly: Type.Optional(Type.Boolean({ description: '默认 true' })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const productionOnly = (rawParams ?? {}).productionOnly !== false
      try {
        const grammar = await callBackend<any>(
          context,
          `/api/v1/internal/agent/semantic/catalog_grammar?productionOnly=${productionOnly}`,
          { method: 'GET' },
        )
        return { content: text(grammar?.metrics ?? []), details: { metrics: grammar?.metrics ?? [] } }
      } catch (error) {
        return toolError(`list_metrics 失败：${messageOf(error)}`)
      }
    },
  }

  const listDimensions: AgentTool<any> = {
    name: 'list_dimensions',
    label: '列出规范维度',
    description: '从 Catalog Grammar 列出可枚举维度/属性/实体 code。',
    parameters: Type.Object({
      productionOnly: Type.Optional(Type.Boolean({ description: '默认 true' })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const productionOnly = (rawParams ?? {}).productionOnly !== false
      try {
        const grammar = await callBackend<any>(
          context,
          `/api/v1/internal/agent/semantic/catalog_grammar?productionOnly=${productionOnly}`,
          { method: 'GET' },
        )
        return { content: text(grammar?.dimensions ?? []), details: { dimensions: grammar?.dimensions ?? [] } }
      } catch (error) {
        return toolError(`list_dimensions 失败：${messageOf(error)}`)
      }
    },
  }

  const compileAndValidate: AgentTool<any> = {
    name: 'compile_and_validate',
    label: '干跑校验结构化语义查询',
    description: '编译 SMQ 但不执行。成功返回 SQL；失败返回类型化 error_code / allowed / repair_hint，请只改相关字段后重试（CONSTITUTION C3/C8）。',
    parameters: smqBodySchema,
    execute: async (_toolCallId, rawParams: any) => {
      try {
        const result = await callBackend<any>(context, '/api/v1/internal/agent/semantic/compile_and_validate', {
          method: 'POST',
          body: JSON.stringify(rawParams ?? {}),
        })
        const reject = result?.reject ?? result?.data?.reject
        if (result?.ok === false || reject) {
          const code = reject?.errorCode || reject?.error_code || 'COMPILE_REJECT'
          const hint = reject?.repairHint || reject?.repair_hint || ''
          const msg = reject?.message || 'SMQ 编译拒绝'
          return {
            content: text({
              ok: false,
              errorCode: code,
              message: msg,
              repairHint: hint,
              location: reject?.location,
              allowed: reject?.allowed,
              reject,
            }),
            details: {
              compileAndValidate: result,
              compileReject: {
                errorCode: code,
                message: msg,
                repairHint: hint,
                location: reject?.location,
                allowed: reject?.allowed,
              },
            },
            isError: true,
          }
        }
        return {
          content: text(result),
          details: { compileAndValidate: result },
        }
      } catch (error) {
        return toolError(`compile_and_validate 失败：${messageOf(error)}`)
      }
    },
  }

  const resolveRef: AgentTool<any> = {
    name: 'resolve_ref',
    label: '解析语义短名引用',
    description:
      '将短名解析为规范语义对象 ID/code；返回 RESOLVED / UNRESOLVED_REF / AMBIGUOUS_REF 及候选列表。写 SMQ 前优先调用，避免盲猜 code。',
    parameters: Type.Object({
      shortName: Type.String({ description: '短名或局部 code，例如 gmv、region、icu.readmit' }),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const shortName = String((rawParams ?? {}).shortName ?? '').trim()
      if (!shortName) {
        return toolError('shortName 必填')
      }
      try {
        const result = await callBackend<any>(
          context,
          `/api/v1/internal/agent/semantic/resolve_ref?shortName=${encodeURIComponent(shortName)}`,
          { method: 'GET' },
        )
        return { content: text(result), details: { resolveRef: result } }
      } catch (error) {
        return toolError(`resolve_ref 失败：${messageOf(error)}`)
      }
    },
  }

  const executeSemanticQuery: AgentTool<any> = {
    name: 'execute_semantic_query',
    label: '执行结构化语义查询',
    description: '基于已发布语义对象编码（SemanticQuerySpec）发起确定性编译与只读查询，严禁心算指标或直写 SQL（CONSTITUTION C3）。建议先 compile_and_validate。',
    parameters: smqBodySchema,
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

  const profileDatasetQuality: AgentTool<any> = {
    name: 'profile_dataset_quality',
    label: '确定性数据质量剖析',
    description: '对受治理目录数据集执行确定性质量剖析（空值率、单位/枚举异构占比、码表漂移、时间倒挂与阈值离群）。'
      + '严禁由 LLM 心算统计值；结果自动以白名单 DistributionProfileCard 渲染。',
    parameters: Type.Object({
      datasetId: Type.String({ description: '目录数据集 ID（如 cd_xxx）' }),
      minSampleSize: Type.Optional(Type.Number({ description: '最小有效样本行数（不足返回 INCONCLUSIVE）' })),
      maxNullRatio: Type.Optional(Type.Number({ description: '允许的最大空值率阈值（0-1，默认 0.05）' })),
      unitSpecs: Type.Optional(Type.Array(Type.Object({
        column: Type.String(),
        expectedValues: Type.Array(Type.String()),
        standardRef: Type.String({ description: 'C2 必选：已登记 Standard 标识' }),
      }))),
      codeTableSpecs: Type.Optional(Type.Array(Type.Object({
        column: Type.String(),
        allowedCodes: Type.Array(Type.String()),
        standardRef: Type.String({ description: 'C2 必选：已登记 Standard 标识' }),
      }))),
      timestampPairs: Type.Optional(Type.Array(Type.Object({
        startColumn: Type.String(),
        endColumn: Type.String(),
        standardRef: Type.Optional(Type.String()),
      }))),
      thresholdSpecs: Type.Optional(Type.Array(Type.Object({
        column: Type.String(),
        min: Type.Optional(Type.Number()),
        max: Type.Optional(Type.Number()),
        standardRef: Type.String({ description: 'C2 必选：已登记 Standard 标识' }),
      }))),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as Record<string, unknown>
      const datasetId = String(params.datasetId ?? '').trim()
      if (!datasetId) {
        return toolError('datasetId 必填')
      }
      try {
        const profile = await callBackend<Record<string, unknown>>(
          context,
          `/api/v1/internal/agent/quality/datasets/${encodeURIComponent(datasetId)}/profile`,
          {
            method: 'POST',
            body: JSON.stringify({
              minSampleSize: params.minSampleSize,
              maxNullRatio: params.maxNullRatio,
              unitSpecs: params.unitSpecs ?? [],
              codeTableSpecs: params.codeTableSpecs ?? [],
              timestampPairs: params.timestampPairs ?? [],
              thresholdSpecs: params.thresholdSpecs ?? [],
            }),
          },
        )
        const block = buildDistributionProfileBlock(profile)
        if (block) {
          callbacks.onUiBlock?.(block)
        }
        return toolResult(
          `质量剖析完成：结论 ${String(profile.verdict ?? 'INCONCLUSIVE')}，总行数 ${String(profile.totalRows ?? 0)}，异常 ${String(profile.anomalyCount ?? 0)} 项`,
          { profile },
        )
      } catch (error) {
        return toolError(messageOf(error))
      }
    },
  }

  const listEvolutionProposals: AgentTool<any> = {
    name: 'list_evolution_proposals',
    label: '查看受控演进提案',
    description: '查询 Staging 暂存区（PROPOSED）或历史演进提案，自动以白名单 RuleDiffProposalCard 预览。',
    parameters: Type.Object({
      state: Type.Optional(Type.String({ description: '状态：PROPOSED / APPROVED / REJECTED / ROLLED_BACK' })),
      assetRef: Type.Optional(Type.String({ description: '按资产引用过滤，如 dataset:cd_1' })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as { state?: string; assetRef?: string }
      const qs = new URLSearchParams()
      if (params.state) qs.set('state', params.state)
      if (params.assetRef) qs.set('assetRef', params.assetRef)
      const suffix = qs.toString() ? `?${qs.toString()}` : ''
      try {
        const proposals = await callBackend<Array<Record<string, unknown>>>(
          context,
          `/api/v1/internal/agent/evolution/proposals${suffix}`,
        )
        if (Array.isArray(proposals) && proposals.length > 0) {
          const block = buildRuleDiffProposalBlock(proposals[0])
          if (block) {
            callbacks.onUiBlock?.(block)
          }
        }
        return toolResult(`共查到 ${proposals.length} 条演进提案`, { proposals })
      } catch (error) {
        return toolError(messageOf(error))
      }
    },
  }

  const alignTreatmentAnchors: AgentTool<any> = {
    name: 'align_treatment_anchors',
    label: 'T0 治疗锚点对齐',
    description: '对结构化锚点与候选事件行执行多程切分与 T0 窗口对齐。严禁由 LLM 自行归因程次。',
    parameters: Type.Object({
      rows: Type.Array(Type.Record(Type.String(), Type.Unknown()), {
        description: '含 row_kind=ANCHOR|CANDIDATE 的事件行',
      }),
      params: Type.Optional(Type.Record(Type.String(), Type.Unknown())),
      specId: Type.Optional(Type.String()),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      try {
        const result = await callBackend<Record<string, unknown>>(
          context,
          '/api/v1/internal/agent/clinical/anchors/align',
          { method: 'POST', body: JSON.stringify(rawParams) },
        )
        return toolResult(`对齐完成 hit=${result.hitCount} ambiguous=${result.ambiguousCount}`, { result })
      } catch (error) {
        return toolError(messageOf(error))
      }
    },
  }

  const runHccClinicalQc: AgentTool<any> = {
    name: 'run_hcc_clinical_qc',
    label: 'HCC 专病质控',
    description: '运行 STD_CHANCE_HCC_QC_V1（CNLC/时序/单位）。输入为已对齐结构化行，禁止心算。',
    parameters: Type.Object({
      rows: Type.Array(Type.Record(Type.String(), Type.Unknown())),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      try {
        const result = await callBackend<Record<string, unknown>>(
          context,
          '/api/v1/internal/agent/clinical/qc/STD_CHANCE_HCC_QC_V1/run',
          { method: 'POST', body: JSON.stringify(rawParams) },
        )
        return toolResult(`质控完成 findings=${result.findingCount}`, { result })
      } catch (error) {
        return toolError(messageOf(error))
      }
    },
  }

  const listClinicalQcFindings: AgentTool<any> = {
    name: 'list_clinical_qc_findings',
    label: '查询临床质控发现',
    description: '列出 OPEN/RESOLVED 临床质控发现。',
    parameters: Type.Object({
      status: Type.Optional(Type.String()),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const status = rawParams?.status ? `?status=${encodeURIComponent(String(rawParams.status))}` : ''
      try {
        const findings = await callBackend<unknown[]>(
          context,
          `/api/v1/internal/agent/clinical/qc/findings${status}`,
        )
        return toolResult(`共 ${Array.isArray(findings) ? findings.length : 0} 条发现`, { findings })
      } catch (error) {
        return toolError(messageOf(error))
      }
    },
  }

  const suggestFieldMappings: AgentTool<any> = {
    name: 'suggest_field_mappings',
    label: '生成字段映射建议',
    description: '基于脱敏字段画像生成字段→业务本体映射建议并写入 PROPOSED 收件箱。'
      + '禁止传入行级 PHI；建议不自动生效，须宿主审校。',
    parameters: Type.Object({
      fields: Type.Array(Type.Object({
        sourceTable: Type.String(),
        sourceField: Type.String(),
        comment: Type.Optional(Type.String()),
        dataType: Type.Optional(Type.String()),
        nullRatio: Type.Optional(Type.Number()),
        unitHint: Type.Optional(Type.String()),
        sampleEnums: Type.Optional(Type.Array(Type.String())),
      })),
      businessUrns: Type.Optional(Type.Array(Type.String())),
      llmSuggestions: Type.Optional(Type.Array(Type.Record(Type.String(), Type.Unknown()))),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const gate = isToolPermittedBySkills('suggest_field_mappings', callbacks.activeSkills, callbacks.skills)
      if (!gate.allowed) {
        return toolError(
          `工具 suggest_field_mappings 尚未解锁：请先 activate_skill("${gate.requiredSkillHint ?? 'chance-ai-mapping'}")`,
        )
      }
      try {
        const created = await callBackend<unknown[]>(
          context,
          '/api/v1/internal/agent/mapping-proposals/suggest',
          { method: 'POST', body: JSON.stringify(rawParams ?? {}) },
        )
        return toolResult(
          `已写入 ${Array.isArray(created) ? created.length : 0} 条映射建议（PROPOSED），请到治理映射收件箱审校`,
          { proposals: created },
        )
      } catch (error) {
        return toolError(messageOf(error))
      }
    },
  }

  const listMappingProposals: AgentTool<any> = {
    name: 'list_mapping_proposals',
    label: '查看映射建议收件箱',
    description: '查询字段→本体映射建议工单（PROPOSED/APPROVED/REJECTED）。',
    parameters: Type.Object({
      state: Type.Optional(Type.String({ description: 'PROPOSED / APPROVED / REJECTED' })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const qs = rawParams?.state ? `?state=${encodeURIComponent(String(rawParams.state))}` : ''
      try {
        const proposals = await callBackend<unknown[]>(
          context,
          `/api/v1/internal/agent/mapping-proposals${qs}`,
        )
        return toolResult(`共 ${Array.isArray(proposals) ? proposals.length : 0} 条映射提案`, { proposals })
      } catch (error) {
        return toolError(messageOf(error))
      }
    },
  }

  const submitMappingDecision: AgentTool<any> = {
    name: 'submit_mapping_decision',
    label: '提交映射审校决策',
    description: '对映射建议执行 approve/reject（经提案 API，不直写生产语义映射）。'
      + 'block_and_escalate 必须附 note；优先引导用户在宿主映射收件箱确认。',
    parameters: Type.Object({
      id: Type.String({ description: '映射提案 ID' }),
      decision: Type.String({ description: 'approve 或 reject' }),
      note: Type.Optional(Type.String()),
      amendedUrn: Type.Optional(Type.String()),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const gate = isToolPermittedBySkills('submit_mapping_decision', callbacks.activeSkills, callbacks.skills)
      if (!gate.allowed) {
        return toolError(
          `工具 submit_mapping_decision 尚未解锁：请先 activate_skill("${gate.requiredSkillHint ?? 'chance-ai-mapping'}")`,
        )
      }
      const id = String(rawParams?.id ?? '').trim()
      if (!id) {
        return toolError('id 必填')
      }
      try {
        const updated = await callBackend<Record<string, unknown>>(
          context,
          `/api/v1/internal/agent/mapping-proposals/${encodeURIComponent(id)}/decision`,
          {
            method: 'POST',
            body: JSON.stringify({
              decision: rawParams?.decision,
              note: rawParams?.note,
              amendedUrn: rawParams?.amendedUrn,
            }),
          },
        )
        return toolResult(`映射提案 ${id} → ${String(updated.state ?? '')}`, { proposal: updated })
      } catch (error) {
        return toolError(messageOf(error))
      }
    },
  }

  const lineageTraceColumn: AgentTool<any> = {
    name: 'lineage_trace_column',
    label: '字段血缘溯源',
    description: '只读：追溯某个字段的上游来源/下游去向（字段级血缘，含转换表达式、作业与执行证据）。'
      + '平台按发起人身份实时逐跳鉴权，无权资产以 [受限资产] 占位并计入 restrictedCount。'
      + '返回 status=INCONCLUSIVE 时说明血缘证据不足（reason 为原因码），必须如实告知用户，严禁据此推断“没有依赖”或自行猜测来源。',
    parameters: Type.Object({
      columnId: Type.String({ description: 'Catalog 字段 ID 或血缘节点 ID' }),
      direction: Type.Optional(Type.String({ description: 'upstream | downstream | both（默认 both）' })),
      depth: Type.Optional(Type.Number({ description: '遍历跳数 1-5，默认 3' })),
      includePlanned: Type.Optional(Type.Boolean({ description: '是否包含编译期 PLANNED 预估边，默认 false（仅执行确认的 ACTIVE 边）' })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = (rawParams ?? {}) as {
        columnId?: string
        direction?: string
        depth?: number
        includePlanned?: boolean
      }
      const columnId = String(params.columnId ?? '').trim()
      if (!columnId) {
        return toolError('columnId 必填')
      }
      const qs = new URLSearchParams({ columnId })
      if (params.direction?.trim()) qs.set('direction', params.direction.trim())
      if (params.depth !== undefined && Number.isFinite(params.depth)) qs.set('depth', String(params.depth))
      if (params.includePlanned) qs.set('includePlanned', 'true')
      try {
        const result = await callBackend<Record<string, any>>(
          context,
          `/api/v1/internal/agent/lineage/trace_column?${qs.toString()}`,
        )
        return toolResult(lineageSummary('溯源', result, (r) => `涉及 ${r.nodes?.length ?? 0} 个节点、${r.edges?.length ?? 0} 条边`), {
          status: result.status,
          reason: result.reason ?? null,
          trace: result,
          instruction: result.status === 'INCONCLUSIVE'
            ? '血缘证据不足：请原样说明原因，并引导用户到血缘工作台补录或导入 SQL；不得推断来源。'
            : undefined,
        })
      } catch (error) {
        return toolError(`字段血缘溯源失败：${messageOf(error)}`)
      }
    },
  }

  const lineageImpactAnalysis: AgentTool<any> = {
    name: 'lineage_impact_analysis',
    label: '血缘影响评估',
    description: '只读：评估修改某字段/数据集会影响哪些下游表、字段，以及依赖它们的指标、分析报告与质检规则。'
      + '逐跳 ACL 与发起人身份由平台实时校验。status=INCONCLUSIVE 表示没有足够的下游血缘证据，'
      + '此时严禁回答“没有影响”，只能说明证据不足及 reason。',
    parameters: Type.Object({
      columnId: Type.Optional(Type.String({ description: 'Catalog 字段 ID 或血缘节点 ID（与 datasetId 二选一）' })),
      datasetId: Type.Optional(Type.String({ description: 'Catalog 数据集 ID（表级影响，与 columnId 二选一）' })),
      depth: Type.Optional(Type.Number({ description: '遍历跳数 1-5，默认 5' })),
      includePlanned: Type.Optional(Type.Boolean({ description: '是否包含 PLANNED 预估边，默认 false' })),
      includeGovernance: Type.Optional(Type.Boolean({ description: '是否包含指标/报告/质检规则等治理消费方，默认 true' })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = (rawParams ?? {}) as {
        columnId?: string
        datasetId?: string
        depth?: number
        includePlanned?: boolean
        includeGovernance?: boolean
      }
      const columnId = String(params.columnId ?? '').trim()
      const datasetId = String(params.datasetId ?? '').trim()
      if (!columnId && !datasetId) {
        return toolError('columnId 或 datasetId 至少提供一个')
      }
      const qs = new URLSearchParams()
      if (columnId) qs.set('columnId', columnId)
      else qs.set('datasetId', datasetId)
      if (params.depth !== undefined && Number.isFinite(params.depth)) qs.set('depth', String(params.depth))
      if (params.includePlanned) qs.set('includePlanned', 'true')
      if (params.includeGovernance === false) qs.set('includeGovernance', 'false')
      try {
        const result = await callBackend<Record<string, any>>(
          context,
          `/api/v1/internal/agent/lineage/impact_analysis?${qs.toString()}`,
        )
        return toolResult(lineageSummary('影响评估', result, (r) => {
          const s = r.summary ?? {}
          return `下游字段 ${s.downstreamColumns ?? 0}、表 ${s.downstreamTables ?? 0}、指标 ${s.metrics ?? 0}、报告 ${s.reports ?? 0}、质检规则 ${s.qualityRules ?? 0}`
        }), {
          status: result.status,
          reason: result.reason ?? null,
          impact: result,
          instruction: result.status === 'INCONCLUSIVE'
            ? '下游证据不足：不得宣称“无影响”，请说明原因并建议先补全血缘。'
            : undefined,
        })
      } catch (error) {
        return toolError(`血缘影响评估失败：${messageOf(error)}`)
      }
    },
  }

  const autonomyCompileFlow: AgentTool<any> = {
    name: 'autonomy_compile_flow',
    label: '自治 FlowSpec 静态编译',
    description: '只读校验：把自然语言生成的 flow.ir/v1 草案交给 FlowCompiler 做存在性、C6 作用域、参数包络、补偿与防震荡检查。'
      + '不得直接启动流程；草案必须由用户在自治控制台保存，并以 SHADOW 启动后逐级晋升。',
    parameters: Type.Object({
      spec: Type.Any({ description: 'flow.ir/v1 JSON 对象（schemaVersion / flowCode / entityKeyExpr / steps）' }),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const spec = (rawParams ?? {}).spec
      if (!spec || typeof spec !== 'object') {
        return toolError('spec 必填且必须是 JSON 对象')
      }
      try {
        const result = await callBackend<Record<string, any>>(
          context,
          '/api/v1/internal/agent/autonomy/flow/compile',
          { method: 'POST', body: JSON.stringify({ spec }) },
        )
        const diagnostics = Array.isArray(result.diagnostics) ? result.diagnostics : []
        return toolResult(
          result.ok
            ? `FlowSpec 编译通过（${Array.isArray((result.plan as any)?.steps) ? (result.plan as any).steps.length : 0} 个步骤），仍须用户保存并以 SHADOW 启动`
            : `FlowSpec 编译失败，共 ${diagnostics.length} 条诊断`,
          {
            ok: result.ok,
            diagnostics,
            plan: result.plan ?? null,
            instruction: result.ok
              ? '请把草案展示给用户，并说明必须走 SHADOW → L2_ASSISTED → L1_AUTO 的受控晋升；不要声称已经部署。'
              : '请逐条解释诊断并修正草案；不要绕过 FlowCompiler。',
          },
        )
      } catch (error) {
        return toolError(`FlowSpec 编译失败：${messageOf(error)}`)
      }
    },
  }

  const autonomyTraceExplain: AgentTool<any> = {
    name: 'autonomy_trace_explain',
    label: '自治决策轨迹归因',
    description: '只读：读取一条 DecisionTrace（触发快照、Arbiter 裁决、Dry-Run、审批、执行/补偿、T+1 回检），'
      + '用于向用户解释“系统为什么在当时做了这个动作”。不得据此直接拍板或修改策略。',
    parameters: Type.Object({
      traceId: Type.String({ description: 'DecisionTrace 的 traceId' }),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const traceId = String((rawParams ?? {}).traceId ?? '').trim()
      if (!traceId) return toolError('traceId 必填')
      try {
        const trace = await callBackend<Record<string, any>>(
          context,
          `/api/v1/internal/agent/autonomy/traces/${encodeURIComponent(traceId)}`,
        )
        const arbiter = trace.arbiterDecision ?? {}
        return toolResult(
          `决策轨迹 ${traceId}：模式 ${String(trace.runMode ?? '')}，Arbiter ${String(arbiter.status ?? 'UNKNOWN')}，回检 ${String(trace.verifyStatus ?? 'PENDING')}`,
          {
            trace,
            instruction: '请按“触发→裁决→预演→审批→执行→回检”顺序解释；证据不足时明确说 INCONCLUSIVE。',
          },
        )
      } catch (error) {
        return toolError(`读取决策轨迹失败：${messageOf(error)}`)
      }
    },
  }

  const renderFilterFunnel: AgentTool<any> = {
    name: 'render_filter_funnel',
    label: '渲染纳排漏斗卡片',
    description: '将已通过工具核验的多步筛选/纳排计数渲染为白名单 FilterFunnelCard（仅接受确定性计数，严禁编造数字）。',
    parameters: Type.Object({
      title: Type.String({ description: '漏斗标题' }),
      steps: Type.Array(Type.Object({
        label: Type.String({ description: '步骤名称' }),
        count: Type.Number({ description: '该步留存计数' }),
        excludedReason: Type.Optional(Type.String({ description: '剔除原因说明' })),
      })),
    }),
    execute: async (_toolCallId, rawParams: any) => {
      const params = rawParams as {
        title: string
        steps: Array<{ label: string; count: number; excludedReason?: string }>
      }
      const steps = Array.isArray(params.steps) ? params.steps : []
      if (steps.length === 0) {
        return toolError('steps 不能为空')
      }
      const base = Math.max(1, Number(steps[0].count ?? 0))
      const enriched = steps.map((s) => ({
        label: s.label,
        count: Number(s.count ?? 0),
        retentionRatio: Number((Number(s.count ?? 0) / base).toFixed(4)),
        excludedReason: s.excludedReason,
      }))
      const block = validateUiBlock({
        component: 'FilterFunnelCard',
        version: '1',
        props: { title: params.title, steps: enriched },
      })
      if (!block) {
        return toolError('声明式卡片未通过 C7 白名单安全校验')
      }
      callbacks.onUiBlock?.(block)
      return toolResult(`已渲染筛选漏斗「${params.title}」（${enriched.length} 步）`, { block })
    },
  }

  return [
    activateSkill,
    listDatasets,
    askData,
    searchSemanticSubgraph,
    getSemanticObject,
    getCatalogGrammar,
    listMetrics,
    listDimensions,
    resolveRef,
    compileAndValidate,
    executeSemanticQuery,
    profileDatasetQuality,
    listEvolutionProposals,
    alignTreatmentAnchors,
    runHccClinicalQc,
    listClinicalQcFindings,
    lineageTraceColumn,
    lineageImpactAnalysis,
    autonomyCompileFlow,
    autonomyTraceExplain,
    suggestFieldMappings,
    listMappingProposals,
    submitMappingDecision,
    renderFilterFunnel,
    createLocalDataSource,
    updateLocalDataSource,
    testDataSource,
    listCatalogSources,
    createParquetCatalog,
    refreshCatalogSource,
    navigateWorkspace,
  ]
}

function lineageSummary(
  label: string,
  result: Record<string, any>,
  detail: (result: Record<string, any>) => string,
): string {
  if (result?.status === 'INCONCLUSIVE') {
    return `${label}结论为 INCONCLUSIVE（${String(result.reason ?? 'UNKNOWN')}）：${String(result.message ?? '血缘证据不足')}`
  }
  const acl = result?.truncatedByAcl ? `；已按最小权限截断 ${String(result.restrictedCount ?? 0)} 个受限分支` : ''
  return `${label}完成：${detail(result)}${acl}`
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
