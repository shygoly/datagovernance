/**
 * 轻量级 ETL 管道(lightweight-etl-pipeline Phase 1)—— etl.ir/v1 的前端类型。
 *
 * 与 dg-control 的 EtlPipelineIr / EtlNodeKind / EtlErrorPolicy 一一对应。
 * IR 是 JobDefinition.config 的根结构;画布只是它的一种可视化编辑器,
 * 保存时画布状态被序列化成 IR,打开时 IR 被还原成画布 —— 画布本身不是事实源。
 */

export const ETL_SCHEMA_VERSION = 'etl.ir/v1'

/** 与后端 EtlNodeKind 枚举值逐一对应 */
export type EtlNodeKind =
  | 'SOURCE_JDBC'
  | 'SOURCE_FILE'
  | 'SOURCE_API'
  | 'TRANSFORM_FILTER'
  | 'TRANSFORM_SELECT'
  | 'TRANSFORM_DERIVE'
  | 'TRANSFORM_CLEANSE_MASK'
  | 'TRANSFORM_JOIN'
  | 'TRANSFORM_AGGREGATE'
  | 'TRANSFORM_DEDUP'
  | 'TRANSFORM_CUSTOM_SQL'
  | 'TRANSFORM_CODESET_LOOKUP'
  | 'SINK_JDBC'
  | 'SINK_PARQUET'

/** 画布上的四类节点外观:Source / Transform / CustomSQL / Sink */
export type EtlVisualType = 'source' | 'transform' | 'customSql' | 'sink'

export type EtlWriteMode = 'APPEND' | 'OVERWRITE' | 'UPSERT'

export interface EtlErrorPolicy {
  mode: 'FAIL_FAST' | 'SKIP_BAD_ROWS' | 'ROUTE_TO_DEAD_LETTER'
  maxBadRows?: number
  maxBadRatio?: number
}

export interface EtlIrNode {
  id: string
  kind: EtlNodeKind
  name?: string
  config: Record<string, unknown>
  /** 画布坐标。后端只把它当作已知键放过,不参与编译 */
  position?: { x: number; y: number }
}

export interface EtlIrEdge {
  from: string
  to: string
  /** 仅 JOIN:left / right */
  port?: 'left' | 'right'
}

export interface EtlIr {
  schemaVersion: typeof ETL_SCHEMA_VERSION
  errorPolicy: EtlErrorPolicy
  nodes: EtlIrNode[]
  edges: EtlIrEdge[]
}

/** 节点列推演结果。null 表示无法静态推断(如自定义 SQL 之后) */
export interface EtlColumn {
  name: string
  type?: string
}

/** POST /control/etl/preview */
export interface EtlPreviewRequest {
  ir?: Record<string, unknown>
  jobId?: string
  nodeId?: string
}

export interface EtlPreviewColumn {
  name: string
  type: string
}

export interface EtlPreviewResponse {
  nodeId: string
  columns: EtlPreviewColumn[]
  rows: Array<Record<string, unknown>>
  truncated: boolean
  /** L3/L4 敏感列被兜底脱敏的列名 */
  maskedColumns: string[]
  elapsedMillis: number
}

/** GET /control/etl/{jobId}/watermarks —— 每个增量源节点一行 */
export interface EtlWatermark {
  sourceNodeId: string
  watermarkColumn: string | null
  /** TIMESTAMP / NUMBER / STRING */
  watermarkType: string | null
  /** null = 已被清空(下次全量抽取) */
  lastValue: string | null
  lastExecutionId: string | null
  updatedAt: string | null
  updatedBy: string | null
}

/** POST /control/etl/{jobId}/watermarks/{sourceNodeId}/reset */
export interface EtlResetWatermarkRequest {
  /** 必填:重置意味着重抽或漏抽,后端要求留痕 */
  reason: string
  /** 缺省 / 空 = 清空 */
  newValue?: string
}

/** GET /control/etl/executions/{executionId}/bad-rows —— 只有摘要,没有行内容 */
export interface EtlBadRowBatch {
  id: string
  jobId: string
  executionId: string
  nodeId: string
  badRowCount: number
  /** 死信存储引用(相对引用),行本体不经此接口返回 */
  storageRef: string | null
  /** 已清洗的错误类别 / SQLSTATE 样例(JSON 字符串) */
  sampleErrorJson: string | null
  createdAt: string
}

/** 画布节点 data 载荷(Vue Flow node.data) */
export interface EtlFlowNodeData {
  kind: EtlNodeKind
  name: string
  config: Record<string, unknown>
  /** 编译诊断回标:有 ERROR 为 error,仅 WARNING 为 warning */
  diagnostic?: 'error' | 'warning' | null
}
