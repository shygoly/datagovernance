import { reactive } from 'vue'
import { dataSourceApi } from '@/api/datasource'
import type { EtlColumn } from '@/types/etl'

/**
 * ETL 编辑器的「表 → 列」缓存。
 *
 * 上游列推演(utils/etl.inferColumns)要同步取到源表的列,而列需要走
 * 目录浏览接口异步拉取 —— 所以把结果放进一个响应式缓存:画布里任何
 * 数据源 / 目标节点改了表,都会 ensure 一次,下游抽屉里的候选随之刷新。
 * 缓存是模块级的,编辑器每次打开时 reset(),避免看到上一条管道遗留的过期结构。
 */

export interface EtlTableRef {
  dataSourceId?: unknown
  database?: unknown
  schema?: unknown
  table?: unknown
}

const cache = reactive<Record<string, EtlColumn[]>>({})
const inflight = new Map<string, Promise<void>>()

function text(v: unknown): string {
  return typeof v === 'string' ? v : ''
}

export function etlTableKey(ref: EtlTableRef): string | null {
  const ds = text(ref.dataSourceId)
  const table = text(ref.table)
  if (!ds || !table) return null
  return [ds, text(ref.database), text(ref.schema), table].join('|')
}

export function useEtlSchema() {
  function columnsOf(ref: EtlTableRef): EtlColumn[] | null {
    const key = etlTableKey(ref)
    return key ? (cache[key] ?? null) : null
  }

  /** 拉取一次并缓存;失败静默(请求拦截器已提示),调用方拿到 null 时退化为自由输入 */
  function ensure(ref: EtlTableRef): Promise<void> {
    const key = etlTableKey(ref)
    if (!key || cache[key]) return Promise.resolve()
    const pending = inflight.get(key)
    if (pending) return pending
    const task = dataSourceApi
      .browseCatalog(text(ref.dataSourceId), {
        database: text(ref.database) || undefined,
        schema: text(ref.schema) || undefined,
        table: text(ref.table),
      })
      .then((page) => {
        if (page.columns.length) {
          cache[key] = page.columns.map((c) => ({ name: c.name, type: c.canonicalType }))
        }
      })
      .catch(() => undefined)
      .finally(() => inflight.delete(key))
    inflight.set(key, task)
    return task
  }

  function reset() {
    for (const key of Object.keys(cache)) delete cache[key]
  }

  return { columnsOf, ensure, reset }
}
