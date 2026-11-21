import type { Edge, Node } from '@vue-flow/core'
import type { CompileDiagnostic } from '@/types/job'
import {
  ETL_SCHEMA_VERSION,
  type EtlColumn,
  type EtlErrorPolicy,
  type EtlFlowNodeData,
  type EtlIr,
  type EtlIrEdge,
  type EtlIrNode,
  type EtlNodeKind,
  type EtlVisualType,
} from '@/types/etl'

/**
 * ETL 画布 ⇄ etl.ir/v1 的转换与客户端护栏。
 *
 * 这里的校验只做「画连线时就能立刻否决」的几何问题(环路 / 入度 / 方向),
 * 让用户当场知道为什么连不上。语义层校验(列是否存在、SQL 是否合规)
 * 一律交给后端编译器 —— 前端不复制第二份规则,否则两份迟早不一致。
 */

export type EtlFlowNode = Node<EtlFlowNodeData>
export type EtlFlowEdge = Edge<{ port?: 'left' | 'right' }>

export interface EtlKindMeta {
  kind: EtlNodeKind
  label: string
  visual: EtlVisualType
  /** 入度上下界,与后端 EtlNodeKind 保持一致(编译器仍是最终裁判) */
  minInputs: number
  maxInputs: number
  idPrefix: string
  /** 是否出现在左侧算子面板。Phase 2 / 尚无表单的种类只在加载既有 IR 时出现 */
  palette: boolean
  /** 是否有专用表单;否则抽屉退化为 JSON 编辑 */
  form: boolean
  defaultConfig: () => Record<string, unknown>
}

export const ETL_KIND_META: Record<EtlNodeKind, EtlKindMeta> = {
  SOURCE_JDBC: {
    kind: 'SOURCE_JDBC', label: '数据库源', visual: 'source', minInputs: 0, maxInputs: 0,
    idPrefix: 'src', palette: true, form: true, defaultConfig: () => ({}),
  },
  SOURCE_FILE: {
    kind: 'SOURCE_FILE', label: '文件源', visual: 'source', minInputs: 0, maxInputs: 0,
    idPrefix: 'file', palette: false, form: false, defaultConfig: () => ({ format: 'PARQUET' }),
  },
  SOURCE_API: {
    kind: 'SOURCE_API', label: '接口源', visual: 'source', minInputs: 0, maxInputs: 0,
    idPrefix: 'api', palette: false, form: false, defaultConfig: () => ({}),
  },
  TRANSFORM_FILTER: {
    kind: 'TRANSFORM_FILTER', label: '过滤', visual: 'transform', minInputs: 1, maxInputs: 1,
    idPrefix: 'flt', palette: true, form: true, defaultConfig: () => ({}),
  },
  TRANSFORM_SELECT: {
    kind: 'TRANSFORM_SELECT', label: '选列 / 改名', visual: 'transform', minInputs: 1, maxInputs: 1,
    idPrefix: 'sel', palette: true, form: true, defaultConfig: () => ({ columns: [] }),
  },
  TRANSFORM_DERIVE: {
    kind: 'TRANSFORM_DERIVE', label: '派生列', visual: 'transform', minInputs: 1, maxInputs: 1,
    idPrefix: 'drv', palette: true, form: false,
    defaultConfig: () => ({ derivations: [{ name: 'new_column', expression: '' }] }),
  },
  TRANSFORM_CLEANSE_MASK: {
    kind: 'TRANSFORM_CLEANSE_MASK', label: '清洗 / 脱敏', visual: 'transform', minInputs: 1, maxInputs: 1,
    idPrefix: 'msk', palette: true, form: false,
    defaultConfig: () => ({ rules: [{ column: '', action: '' }] }),
  },
  TRANSFORM_JOIN: {
    kind: 'TRANSFORM_JOIN', label: '连接', visual: 'transform', minInputs: 2, maxInputs: 2,
    idPrefix: 'jn', palette: true, form: false,
    defaultConfig: () => ({ joinType: 'INNER', on: [{ left: '', right: '' }] }),
  },
  TRANSFORM_AGGREGATE: {
    kind: 'TRANSFORM_AGGREGATE', label: '聚合', visual: 'transform', minInputs: 1, maxInputs: 1,
    idPrefix: 'agg', palette: true, form: false,
    defaultConfig: () => ({ groupBy: [], aggregations: [{ func: 'COUNT', column: '*', as: 'cnt' }] }),
  },
  TRANSFORM_DEDUP: {
    kind: 'TRANSFORM_DEDUP', label: '去重', visual: 'transform', minInputs: 1, maxInputs: 1,
    idPrefix: 'dd', palette: true, form: false,
    defaultConfig: () => ({ keys: [], orderBy: '', order: 'DESC' }),
  },
  TRANSFORM_CUSTOM_SQL: {
    kind: 'TRANSFORM_CUSTOM_SQL', label: '自定义 SQL', visual: 'customSql', minInputs: 1, maxInputs: 8,
    idPrefix: 'sql', palette: true, form: true, defaultConfig: () => ({ sql: '' }),
  },
  TRANSFORM_CODESET_LOOKUP: {
    kind: 'TRANSFORM_CODESET_LOOKUP', label: '码表映射', visual: 'transform', minInputs: 1, maxInputs: 1,
    idPrefix: 'cs', palette: false, form: false, defaultConfig: () => ({}),
  },
  SINK_JDBC: {
    kind: 'SINK_JDBC', label: '数据库目标', visual: 'sink', minInputs: 1, maxInputs: 1,
    idPrefix: 'snk', palette: true, form: true, defaultConfig: () => ({ writeMode: 'APPEND' }),
  },
  SINK_PARQUET: {
    kind: 'SINK_PARQUET', label: 'Parquet 目标', visual: 'sink', minInputs: 1, maxInputs: 1,
    idPrefix: 'pq', palette: true, form: false,
    defaultConfig: () => ({ compression: 'SNAPPY', writeMode: 'OVERWRITE' }),
  },
}

/** 左侧面板的分组,顺序即展示顺序 */
export const ETL_PALETTE_GROUPS: Array<{ title: string; visual: EtlVisualType; kinds: EtlNodeKind[] }> = [
  { title: 'Source 数据源', visual: 'source', kinds: ['SOURCE_JDBC'] },
  {
    title: 'Transform 转换',
    visual: 'transform',
    kinds: [
      'TRANSFORM_FILTER', 'TRANSFORM_SELECT', 'TRANSFORM_DERIVE', 'TRANSFORM_CLEANSE_MASK',
      'TRANSFORM_JOIN', 'TRANSFORM_AGGREGATE', 'TRANSFORM_DEDUP',
    ],
  },
  { title: 'Custom SQL', visual: 'customSql', kinds: ['TRANSFORM_CUSTOM_SQL'] },
  { title: 'Sink 目标', visual: 'sink', kinds: ['SINK_JDBC', 'SINK_PARQUET'] },
]

export function kindMeta(kind: string): EtlKindMeta {
  return ETL_KIND_META[kind as EtlNodeKind] ?? {
    kind: kind as EtlNodeKind, label: kind, visual: 'transform', minInputs: 0, maxInputs: 8,
    idPrefix: 'n', palette: false, form: false, defaultConfig: () => ({}),
  }
}

/** 节点 id 要进 DuckDB 视图名与 {{id}} 占位符,后端限定 [A-Za-z][A-Za-z0-9_]{0,63} */
export const ETL_NODE_ID_PATTERN = /^[A-Za-z][A-Za-z0-9_]{0,63}$/

export function newNodeId(kind: EtlNodeKind, existing: Iterable<string>): string {
  const used = new Set(existing)
  const prefix = kindMeta(kind).idPrefix
  let n = 1
  while (used.has(`${prefix}_${n}`)) {
    n += 1
  }
  return `${prefix}_${n}`
}

export function emptyIr(): EtlIr {
  return {
    schemaVersion: ETL_SCHEMA_VERSION,
    errorPolicy: { mode: 'FAIL_FAST' },
    nodes: [],
    edges: [],
  }
}

// ── IR → 画布 ─────────────────────────────────────────────────────────

/** 没有保存过坐标的 IR(比如别处导入的)按最长路径分层摆放,避免全部叠在原点 */
function autoLayout(nodes: EtlIrNode[], edges: EtlIrEdge[]): Map<string, { x: number; y: number }> {
  const depth = new Map<string, number>()
  const incoming = new Map<string, string[]>()
  for (const e of edges) {
    incoming.set(e.to, [...(incoming.get(e.to) ?? []), e.from])
  }
  const visiting = new Set<string>()
  const depthOf = (id: string): number => {
    if (depth.has(id)) return depth.get(id)!
    if (visiting.has(id)) return 0 // 有环:交给后端编译报错,这里只求不死循环
    visiting.add(id)
    const d = Math.max(-1, ...(incoming.get(id) ?? []).map(depthOf)) + 1
    visiting.delete(id)
    depth.set(id, d)
    return d
  }
  const perColumn = new Map<number, number>()
  const result = new Map<string, { x: number; y: number }>()
  for (const n of nodes) {
    const d = depthOf(n.id)
    const row = perColumn.get(d) ?? 0
    perColumn.set(d, row + 1)
    result.set(n.id, { x: 40 + d * 280, y: 40 + row * 130 })
  }
  return result
}

export function irEdgeId(e: EtlIrEdge): string {
  return `${e.from}->${e.to}${e.port ? `:${e.port}` : ''}`
}

export function irToFlow(ir: EtlIr): { nodes: EtlFlowNode[]; edges: EtlFlowEdge[] } {
  const layout = autoLayout(ir.nodes, ir.edges)
  const nodes: EtlFlowNode[] = ir.nodes.map((n) => ({
    id: n.id,
    type: kindMeta(n.kind).visual,
    position: n.position ?? layout.get(n.id) ?? { x: 0, y: 0 },
    data: {
      kind: n.kind,
      name: n.name ?? '',
      config: { ...(n.config ?? {}) },
      diagnostic: null,
    },
  }))
  const edges: EtlFlowEdge[] = ir.edges.map((e) => ({
    id: irEdgeId(e),
    source: e.from,
    target: e.to,
    label: e.port,
    data: { port: e.port },
  }))
  return { nodes, edges }
}

/** 把宽松解出来的 job.config 规整成 IR;不是 etl.ir/v1 时返回空管道 */
export function parseIr(raw: Record<string, unknown> | null | undefined): EtlIr {
  if (!raw || raw.schemaVersion !== ETL_SCHEMA_VERSION) {
    return emptyIr()
  }
  const policy = (raw.errorPolicy ?? {}) as Partial<EtlErrorPolicy>
  const nodes = Array.isArray(raw.nodes) ? (raw.nodes as EtlIrNode[]) : []
  const edges = Array.isArray(raw.edges) ? (raw.edges as EtlIrEdge[]) : []
  return {
    schemaVersion: ETL_SCHEMA_VERSION,
    errorPolicy: {
      mode: policy.mode === 'SKIP_BAD_ROWS' || policy.mode === 'ROUTE_TO_DEAD_LETTER' ? policy.mode : 'FAIL_FAST',
      ...(policy.maxBadRows != null ? { maxBadRows: policy.maxBadRows } : {}),
      ...(policy.maxBadRatio != null ? { maxBadRatio: policy.maxBadRatio } : {}),
    },
    nodes: nodes.map((n) => ({ ...n, config: n.config ?? {} })),
    edges,
  }
}

// ── 画布 → IR ─────────────────────────────────────────────────────────

function isBlank(value: unknown): boolean {
  if (value === undefined || value === null) return true
  if (typeof value === 'string') return value.trim() === ''
  if (Array.isArray(value)) return value.length === 0
  if (typeof value === 'object') return Object.keys(value as object).length === 0
  return false
}

/**
 * 去掉表单留下的空值。空字符串发给后端会被当成「配了但是空的」,
 * 而不是「没配」—— 例如 whereClause: '' 与不写在编译器里走的是不同分支。
 */
export function pruneConfig(config: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(config)) {
    if (!isBlank(value)) out[key] = value
  }
  return out
}

export function flowToIr(
  nodes: EtlFlowNode[],
  edges: EtlFlowEdge[],
  errorPolicy: EtlErrorPolicy,
): EtlIr {
  const irNodes: EtlIrNode[] = nodes.map((n) => {
    const node: EtlIrNode = {
      id: n.id,
      kind: n.data!.kind,
      config: pruneConfig(n.data!.config),
      position: { x: Math.round(n.position.x), y: Math.round(n.position.y) },
    }
    if (n.data!.name.trim()) node.name = n.data!.name.trim()
    return node
  })
  const irEdges: EtlIrEdge[] = edges.map((e) => {
    const edge: EtlIrEdge = { from: e.source, to: e.target }
    if (e.data?.port) edge.port = e.data.port
    return edge
  })
  return { schemaVersion: ETL_SCHEMA_VERSION, errorPolicy: { ...errorPolicy }, nodes: irNodes, edges: irEdges }
}

// ── 连线护栏 ──────────────────────────────────────────────────────────

function reaches(from: string, target: string, edges: EtlFlowEdge[]): boolean {
  const seen = new Set<string>()
  const stack = [from]
  while (stack.length) {
    const cur = stack.pop()!
    if (cur === target) return true
    if (seen.has(cur)) continue
    seen.add(cur)
    for (const e of edges) {
      if (e.source === cur) stack.push(e.target)
    }
  }
  return false
}

/** 返回 null 表示可以连;否则是给用户看的拒绝原因 */
export function checkConnection(
  sourceId: string,
  targetId: string,
  nodes: EtlFlowNode[],
  edges: EtlFlowEdge[],
): string | null {
  const source = nodes.find((n) => n.id === sourceId)
  const target = nodes.find((n) => n.id === targetId)
  if (!source || !target) return '连线端点不存在'
  if (sourceId === targetId) return '节点不能连接自己'
  const sm = kindMeta(source.data!.kind)
  const tm = kindMeta(target.data!.kind)
  if (sm.visual === 'sink') return '目标节点没有输出,不能作为连线起点'
  if (tm.visual === 'source') return '数据源节点没有输入,不能作为连线终点'
  const incoming = edges.filter((e) => e.target === targetId)
  if (incoming.length >= tm.maxInputs) {
    return `「${tm.label}」最多接 ${tm.maxInputs} 路输入`
  }
  if (tm.kind !== 'TRANSFORM_JOIN' && incoming.some((e) => e.source === sourceId)) {
    return '这两个节点之间已经有连线'
  }
  if (reaches(targetId, sourceId, edges)) return '这条连线会形成环路,管道必须是有向无环图'
  return null
}

/** JOIN 的两路输入按连线先后依次占 left / right;其它节点没有端口概念 */
export function nextPort(targetId: string, nodes: EtlFlowNode[], edges: EtlFlowEdge[]): 'left' | 'right' | undefined {
  const target = nodes.find((n) => n.id === targetId)
  if (!target || target.data!.kind !== 'TRANSFORM_JOIN') return undefined
  const taken = new Set(edges.filter((e) => e.target === targetId).map((e) => e.data?.port))
  return taken.has('left') ? 'right' : 'left'
}

// ── 上游列推演(尽力而为)─────────────────────────────────────────────
//
// 仅用于在抽屉里给「选列 / 字段映射」提供候选。它可能比后端的推演弱
// (自定义 SQL 之后直接放弃),所以返回 null 时 UI 退化为自由输入,而不是报错。

function strList(v: unknown): string[] {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string' && x !== '') : []
}

function objList(v: unknown): Array<Record<string, unknown>> {
  return Array.isArray(v) ? v.filter((x): x is Record<string, unknown> => !!x && typeof x === 'object') : []
}

export function inferColumns(
  nodeId: string,
  nodes: EtlFlowNode[],
  edges: EtlFlowEdge[],
  sourceColumnsOf: (node: EtlFlowNode) => EtlColumn[] | null,
  guard: Set<string> = new Set(),
): EtlColumn[] | null {
  if (guard.has(nodeId)) return null
  guard.add(nodeId)
  const node = nodes.find((n) => n.id === nodeId)
  if (!node) return null
  const cfg = node.data!.config
  const upstreamOf = (): Array<EtlColumn[] | null> =>
    edges
      .filter((e) => e.target === nodeId)
      .map((e) => inferColumns(e.source, nodes, edges, sourceColumnsOf, new Set(guard)))
  const first = (): EtlColumn[] | null => upstreamOf()[0] ?? null

  switch (node.data!.kind) {
    case 'SOURCE_JDBC': {
      const all = sourceColumnsOf(node)
      if (!all) return null
      const picked = strList(cfg.columns)
      return picked.length ? all.filter((c) => picked.includes(c.name)) : all
    }
    case 'TRANSFORM_FILTER':
    case 'TRANSFORM_DEDUP':
    case 'SINK_JDBC':
    case 'SINK_PARQUET':
      return first()
    case 'TRANSFORM_SELECT': {
      const up = first()
      const items = objList(cfg.columns)
      if (items.length) {
        return items.map((i) => {
          const name = String(i.name ?? '')
          const as = typeof i.as === 'string' && i.as ? i.as : name
          return { name: as, type: up?.find((c) => c.name === name)?.type }
        })
      }
      if (!up) return null
      const exclude = new Set(strList(cfg.exclude))
      const rename = (cfg.rename ?? {}) as Record<string, string>
      return up.filter((c) => !exclude.has(c.name)).map((c) => ({ ...c, name: rename[c.name] ?? c.name }))
    }
    case 'TRANSFORM_DERIVE': {
      const up = first()
      if (!up) return null
      const added = objList(cfg.derivations)
        .map((d) => String(d.name ?? ''))
        .filter(Boolean)
        .map((name) => ({ name }))
      return [...up.filter((c) => !added.some((a) => a.name === c.name)), ...added]
    }
    case 'TRANSFORM_AGGREGATE': {
      const groupBy = strList(cfg.groupBy).map((name) => ({ name }))
      const aggs = objList(cfg.aggregations)
        .map((a) => String(a.as ?? ''))
        .filter(Boolean)
        .map((name) => ({ name }))
      return [...groupBy, ...aggs]
    }
    case 'TRANSFORM_JOIN': {
      const sides = upstreamOf()
      if (sides.length < 2 || !sides[0] || !sides[1]) return null
      const seen = new Set(sides[0].map((c) => c.name))
      return [...sides[0], ...sides[1].filter((c) => !seen.has(c.name))]
    }
    default:
      return null
  }
}

// ── 编译诊断回标 ──────────────────────────────────────────────────────

/** 诊断 location 形如 `nodes.src_1.table` / `edges[0]`;取出 nodes.<id> 这一段回标到节点上 */
export function diagnosticsByNode(
  diagnostics: CompileDiagnostic[],
): Map<string, 'error' | 'warning'> {
  const result = new Map<string, 'error' | 'warning'>()
  for (const d of diagnostics) {
    const m = d.location?.match(/^nodes\.([A-Za-z][A-Za-z0-9_]*)/)
    if (!m) continue
    const level = d.severity === 'ERROR' ? 'error' : 'warning'
    if (result.get(m[1]) !== 'error') result.set(m[1], level)
  }
  return result
}
