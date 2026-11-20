import { randomUUID } from 'node:crypto'
import { config } from './config.js'

/**
 * OpenTelemetry GenAI 语义规约适配层（Phase C / c1）。
 *
 * 纪律（遵循 9+1 P9.1 与 AGENT-PRIMITIVES.md）：
 * 1. 版本锁定单一常量（GEN_AI_SEMCONV_VERSION = 1.42.0），禁止各业务文件散写属性名；
 * 2. 生产默认关闭原始 Prompt 与回复文本采集（DG_OTEL_CAPTURE_CONTENT=false），防患者 PII 泄露；
 * 3. 输出标准化 JSON Span（invoke_agent / chat / execute_tool），可由 OTel Collector 采集归一化。
 */
export const GEN_AI_SEMCONV_VERSION = '1.42.0'

export const GenAiAttr = {
  SEMCONV_VERSION: 'gen_ai.semconv.version',
  OPERATION_NAME: 'gen_ai.operation.name',
  SYSTEM: 'gen_ai.system',
  REQUEST_MODEL: 'gen_ai.request.model',
  CONVERSATION_ID: 'gen_ai.conversation.id',
  TOOL_NAME: 'gen_ai.tool.name',
  TOOL_CALL_ID: 'gen_ai.tool.call.id',
  OPERATION_DURATION: 'gen_ai.client.operation.duration',
  INPUT_TOKENS: 'gen_ai.usage.input_tokens',
  OUTPUT_TOKENS: 'gen_ai.usage.output_tokens',
  WORKSPACE_ID: 'dg.workspace.id',
  USER_ID: 'dg.user.id',
  INPUT_MESSAGES: 'gen_ai.input.messages',
  OUTPUT_MESSAGES: 'gen_ai.output.messages',
} as const

export interface GenAiSpanHandle {
  traceId: string
  spanId: string
  end: (extra?: {
    status?: 'OK' | 'ERROR'
    errorType?: string
    inputTokens?: number
    outputTokens?: number
    outputPreview?: string
  }) => GenAiSpanRecord
}

export interface GenAiSpanRecord {
  traceId: string
  spanId: string
  parentSpanId?: string
  name: 'invoke_agent' | 'chat' | 'execute_tool'
  startTime: string
  endTime: string
  durationMs: number
  status: 'OK' | 'ERROR'
  attributes: Record<string, string | number | boolean>
}

export function startGenAiSpan(input: {
  name: 'invoke_agent' | 'chat' | 'execute_tool'
  traceId?: string
  parentSpanId?: string
  workspaceId: string
  userId?: string
  conversationId: string
  model?: string
  toolName?: string
  toolCallId?: string
  inputPreview?: string
}): GenAiSpanHandle {
  const traceId = input.traceId ?? randomUUID().replaceAll('-', '')
  const spanId = randomUUID().replaceAll('-', '').slice(0, 16)
  const startedAt = Date.now()
  const startIso = new Date(startedAt).toISOString()

  return {
    traceId,
    spanId,
    end(extra = {}) {
      const durationMs = Math.max(0, Date.now() - startedAt)
      const attributes: Record<string, string | number | boolean> = {
        [GenAiAttr.SEMCONV_VERSION]: GEN_AI_SEMCONV_VERSION,
        [GenAiAttr.OPERATION_NAME]: input.name,
        [GenAiAttr.SYSTEM]: 'deepseek',
        [GenAiAttr.CONVERSATION_ID]: input.conversationId,
        [GenAiAttr.WORKSPACE_ID]: input.workspaceId,
        [GenAiAttr.OPERATION_DURATION]: Number((durationMs / 1000).toFixed(4)),
      }
      if (input.userId) {
        attributes[GenAiAttr.USER_ID] = input.userId
      }
      if (input.model) {
        attributes[GenAiAttr.REQUEST_MODEL] = input.model
      }
      if (input.toolName) {
        attributes[GenAiAttr.TOOL_NAME] = input.toolName
      }
      if (input.toolCallId) {
        attributes[GenAiAttr.TOOL_CALL_ID] = input.toolCallId
      }
      if (typeof extra.inputTokens === 'number') {
        attributes[GenAiAttr.INPUT_TOKENS] = extra.inputTokens
      }
      if (typeof extra.outputTokens === 'number') {
        attributes[GenAiAttr.OUTPUT_TOKENS] = extra.outputTokens
      }
      if (extra.errorType) {
        attributes['error.type'] = extra.errorType
      }
      // 生产默认关闭原始文本采集，防 PII 进入遥测索引
      if (config.otelCaptureContent) {
        if (input.inputPreview) {
          attributes[GenAiAttr.INPUT_MESSAGES] = input.inputPreview.slice(0, 1000)
        }
        if (extra.outputPreview) {
          attributes[GenAiAttr.OUTPUT_MESSAGES] = extra.outputPreview.slice(0, 1000)
        }
      }

      const record: GenAiSpanRecord = {
        traceId,
        spanId,
        parentSpanId: input.parentSpanId,
        name: input.name,
        startTime: startIso,
        endTime: new Date().toISOString(),
        durationMs,
        status: extra.status ?? 'OK',
        attributes,
      }
      if (process.env.DG_OTEL_LOG_SPANS !== 'false') {
        console.info(JSON.stringify({ otel_genai_span: record }))
      }
      return record
    },
  }
}
