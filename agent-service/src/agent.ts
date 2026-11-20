import { Agent } from '@earendil-works/pi-agent-core'
import type { AgentEvent } from '@earendil-works/pi-agent-core'
import { createModels } from '@earendil-works/pi-ai'
import { deepseekProvider } from '@earendil-works/pi-ai/providers/deepseek'
import type { EventEmitter } from 'node:events'
import { config } from './config.js'
import { loadSkills, renderSkillIndex, type SkillDefinition } from './skills.js'
import { ConversationStore } from './store.js'
import { startGenAiSpan, type GenAiSpanHandle } from './telemetry.js'
import { buildTools } from './tools.js'
import type { Conversation, RunContext, UiToolStep, UiTurn } from './types.js'

const models = createModels()
models.setProvider(deepseekProvider())

interface ActiveRunEntry {
  agent: Agent
  workspaceId: string
}

const activeRuns = new Map<string, ActiveRunEntry>()
let skillRegistry: Map<string, SkillDefinition> = loadSkills(config.skillsDir)

export const agentRuntime = {
  modelId: config.deepseekModel,
  skills(): SkillDefinition[] {
    skillRegistry = loadSkills(config.skillsDir)
    return [...skillRegistry.values()].sort((a, b) => a.name.localeCompare(b.name))
  },
  abort(conversationId: string) {
    activeRuns.get(conversationId)?.agent.abort()
  },
  abortAll(workspaceId?: string): number {
    let count = 0
    for (const [convId, entry] of activeRuns.entries()) {
      if (!workspaceId || entry.workspaceId === workspaceId) {
        entry.agent.abort()
        activeRuns.delete(convId)
        count += 1
      }
    }
    return count
  },
}

interface Bus {
  emit: EventEmitter['emit']
}

export async function runTurn(
  conversation: Conversation,
  turn: UiTurn,
  text: string,
  context: RunContext,
  store: ConversationStore,
  bus: Bus,
) {
  const model = models.getModel('deepseek', config.deepseekModel)
  if (!model) {
    throw new Error(`Pi agent 模型不存在: deepseek/${config.deepseekModel}`)
  }

  skillRegistry = loadSkills(config.skillsDir)
  const activeSkills = new Set<string>(conversation.activeSkills ?? [])
  const maxToolCalls = context.maxToolCallsPerTurn ?? config.defaultMaxToolCallsPerTurn
  const maxConsecutiveErrors = context.maxConsecutiveErrors ?? config.defaultMaxConsecutiveErrors

  let finished = false
  let toolCallsInTurn = 0
  let consecutiveSameError = 0
  let lastErrorSignature = ''
  let circuitBrokenReason: string | null = null

  const rootSpan = startGenAiSpan({
    name: 'invoke_agent',
    workspaceId: context.workspaceId,
    userId: context.userId,
    conversationId: conversation.id,
    model: config.deepseekModel,
    inputPreview: text,
  })
  const toolSpans = new Map<string, GenAiSpanHandle>()

  const emit = (type: string, payload: Record<string, unknown>, persist = true) => {
    const event = store.appendEvent(conversation, type, turn.id, payload, persist)
    bus.emit(conversation.id, event)
    return event
  }

  const tools = buildTools(context, {
    skills: skillRegistry,
    activeSkills,
    onSkillActivated(skill) {
      conversation.activeSkills = [...activeSkills]
      store.save(conversation)
      emit('skill_activated', {
        name: skill.name,
        allowedTools: skill.allowedTools,
      })
    },
    onNavigate(target) {
      emit('navigate', {
        route: target.route,
        label: target.label,
        query: target.query ?? {},
      })
    },
    onConsentRequired(proposal) {
      emit('consent_required', { proposal })
    },
  })

  const agent = new Agent({
    initialState: {
      systemPrompt: systemPrompt(context, skillRegistry),
      model,
      tools,
      messages: conversation.messages as any[],
    },
    streamFn: models.streamSimple.bind(models),
    getApiKey: (provider) => provider === 'deepseek' ? config.deepseekApiKey : undefined,
    sessionId: conversation.id,
  })
  activeRuns.set(conversation.id, { agent, workspaceId: context.workspaceId })

  agent.subscribe((event: AgentEvent) => {
    switch (event.type) {
      case 'agent_start':
        emit('turn_start', { question: turn.question, traceId: rootSpan.traceId })
        break
      case 'message_update': {
        const update = event.assistantMessageEvent
        if (update.type === 'text_delta') {
          turn.text += update.delta
          emit('text_delta', { delta: update.delta }, false)
        } else if (update.type === 'thinking_delta') {
          turn.thinking += update.delta
          emit('thinking_delta', { delta: update.delta }, false)
        }
        break
      }
      case 'message_end': {
        if (event.message.role === 'assistant' && !turn.text) {
          turn.text = contentText(event.message.content)
        }
        break
      }
      case 'tool_execution_start': {
        toolCallsInTurn += 1
        const step: UiToolStep = {
          id: event.toolCallId,
          name: event.toolName,
          status: 'running',
          args: event.args,
          startedAt: new Date().toISOString(),
        }
        turn.tools.push(step)
        emit('tool_start', { toolCallId: event.toolCallId, name: event.toolName, args: event.args })

        toolSpans.set(event.toolCallId, startGenAiSpan({
          name: 'execute_tool',
          traceId: rootSpan.traceId,
          parentSpanId: rootSpan.spanId,
          workspaceId: context.workspaceId,
          userId: context.userId,
          conversationId: conversation.id,
          toolName: event.toolName,
          toolCallId: event.toolCallId,
        }))

        if (toolCallsInTurn > maxToolCalls) {
          circuitBrokenReason = `触发工具调用深度熔断：单轮调用已超上限 ${maxToolCalls} 次，请人工介入或缩小问题范围。`
          emit('circuit_breaker', { reason: circuitBrokenReason, code: 'MAX_TOOL_CALLS' })
          agent.abort()
        }
        break
      }
      case 'tool_execution_end': {
        const step = turn.tools.find((item) => item.id === event.toolCallId)
        if (step) {
          step.status = event.isError ? 'error' : 'ok'
          step.finishedAt = new Date().toISOString()
          step.summary = toolSummary(event.result)
        }
        const span = toolSpans.get(event.toolCallId)
        if (span) {
          span.end({
            status: event.isError ? 'ERROR' : 'OK',
            errorType: event.isError ? 'TOOL_EXECUTION_ERROR' : undefined,
          })
          toolSpans.delete(event.toolCallId)
        }

        if (event.isError) {
          const sig = `${event.toolName}:${step?.summary ?? ''}`
          consecutiveSameError = sig === lastErrorSignature ? consecutiveSameError + 1 : 1
          lastErrorSignature = sig
          if (consecutiveSameError >= maxConsecutiveErrors) {
            circuitBrokenReason = `检测到连续 ${consecutiveSameError} 次相同工具错误（${event.toolName}），已停止盲目重试（Raise-and-Replan），请检查权限或参数后人工处理。`
            emit('circuit_breaker', { reason: circuitBrokenReason, code: 'CONSECUTIVE_TOOL_ERRORS' })
            agent.abort()
          }
        } else {
          consecutiveSameError = 0
          lastErrorSignature = ''
        }

        const details = event.result?.details as { answer?: unknown; datasets?: any[] } | undefined
        if (event.toolName === 'ask_data' && details?.answer) {
          turn.answer = details.answer
        }
        if (event.toolName === 'list_datasets' && Array.isArray(details?.datasets)) {
          const datasets = details.datasets
          turn.answer = {
            state: 'METADATA',
            columns: [
              { name: '数据域', bizName: 'domain', type: 'STRING' },
              { name: '数据集', bizName: 'dataset', type: 'STRING' },
              { name: '数据集 ID', bizName: 'datasetId', type: 'LONG' },
            ],
            rows: datasets.map((item: any) => ({
              domain: item.domain ?? '',
              dataset: item.name ?? '',
              datasetId: item.id,
            })),
            rowCount: datasets.length,
            truncated: false,
            elapsedMs: 0,
            maskedColumns: [],
            grantIds: [],
            summary: `返回 ${datasets.length} 个数据集`,
            sql: null,
          }
        }
        emit('tool_end', {
          toolCallId: event.toolCallId,
          name: event.toolName,
          isError: event.isError,
          summary: step?.summary,
          answer: turn.answer,
        })
        break
      }
      case 'agent_end': {
        finished = true
        const last = [...event.messages].reverse()
          .find((message) => message.role === 'assistant') as any
        if (last && !turn.text) {
          turn.text = contentText(last.content)
        }
        const stopReason = last?.stopReason
        if (circuitBrokenReason) {
          turn.status = 'error'
          turn.error = circuitBrokenReason
        } else {
          turn.status = stopReason === 'aborted' ? 'aborted'
            : stopReason === 'error' ? 'error' : 'done'
          if (turn.status === 'error') {
            turn.error = last?.errorMessage || 'Agent 运行失败'
          }
        }
        turn.finishedAt = new Date().toISOString()
        conversation.running = false
        conversation.activeSkills = [...activeSkills]
        conversation.messages = filterMessages(agent.state.messages)
        store.save(conversation)

        rootSpan.end({
          status: turn.status === 'error' ? 'ERROR' : 'OK',
          errorType: turn.status === 'error' ? (circuitBrokenReason ? 'CIRCUIT_BREAKER' : 'AGENT_ERROR') : undefined,
          outputPreview: turn.text,
        })

        emit('done', {
          status: turn.status,
          text: turn.text,
          answer: turn.answer,
          error: turn.error,
          traceId: rootSpan.traceId,
        })
        break
      }
      default:
        break
    }
  })

  conversation.running = true
  if (conversation.title === '新对话') {
    conversation.title = text.length > 40 ? `${text.slice(0, 40)}…` : text
  }
  store.save(conversation)

  try {
    await agent.prompt(text)
  } catch (error) {
    turn.status = 'error'
    turn.error = circuitBrokenReason ?? (error instanceof Error ? error.message : String(error))
    turn.finishedAt = new Date().toISOString()
    conversation.running = false
    store.save(conversation)
    if (!finished) {
      rootSpan.end({
        status: 'ERROR',
        errorType: 'UNHANDLED_EXCEPTION',
      })
      emit('error', { message: turn.error })
      emit('done', { status: 'error', text: turn.text, error: turn.error, traceId: rootSpan.traceId })
    }
  } finally {
    activeRuns.delete(conversation.id)
    if (conversation.running) {
      conversation.running = false
      store.save(conversation)
    }
  }
}

/**
 * 静态稳定前缀 + A0.2 Level-1 技能索引（保持前缀缓存命中率，严禁把冗长多步业务 SOP 硬编码于此）。
 */
function systemPrompt(context: RunContext, skills: Map<string, SkillDefinition>): string {
  return [
    '你是数据治理平台的受治理智能体，严格遵守平台宪法（CONSTITUTION.md）。',
    '核心纪律：',
    '1. 只能使用工具返回的真实事实；工具失败或无数据时如实说明，严禁编造数字、表名或路径。',
    '2. 所有面向用户的文字（含过程过渡句与最终结论）必须使用中文；不要输出 Markdown 大表格、原始行数据或工具内部参数。',
    '3. 执行专业多步流程或写操作前，必须先调用 `activate_skill` 加载对应技能规程（Level 2 渐进披露）并严格遵循其步骤。',
    '4. 所有写操作工具只会生成“待宿主确认提案”，在用户在宿主确认界面点击确认前绝不宣称已完成。',
    '',
    '可用技能索引（A0.2 Level 1，按需通过 activate_skill 激活）：',
    renderSkillIndex(skills),
    '',
    `当前运行上下文：空间=${context.workspaceId}，用户=${context.username}，${
      context.datasetId
        ? `当前数据集=${context.datasetLabel ?? context.datasetId}（ID: ${context.datasetId}）`
        : '当前未指定数据集'
    }。`,
  ].join('\n')
}

function filterMessages(messages: any[]): unknown[] {
  return messages.filter((message) => message?.role !== 'system')
}

function contentText(content: any): string {
  if (!Array.isArray(content)) {
    return ''
  }
  return content
    .filter((block) => block?.type === 'text')
    .map((block) => block.text ?? '')
    .join('')
}

function toolSummary(result: any): string {
  if (result?.details?.summary) {
    return String(result.details.summary)
  }
  if (result?.details?.error) {
    return String(result.details.error)
  }
  if (result?.details?.answer?.summary) {
    return String(result.details.answer.summary)
  }
  if (Array.isArray(result?.details?.datasets)) {
    return `返回 ${result.details.datasets.length} 个数据集`
  }
  return result?.isError ? '工具执行失败' : '工具执行完成'
}
