import { http } from './request'
import type {
  EtlBadRowBatch,
  EtlPreviewRequest,
  EtlPreviewResponse,
  EtlResetWatermarkRequest,
  EtlWatermark,
} from '@/types/etl'

/**
 * 轻量级 ETL 管道。
 *
 * 管道本身就是 jobType=ETL_PIPELINE 的任务定义 —— CRUD / 编译 / 发布 / 执行
 * 全部复用 jobApi,这里只放 ETL 特有的接口(节点预览、增量水位线、脏数据摘要)。
 */
export const etlApi = {
  /**
   * 预览某个节点的输出(最多 100 行,15 秒超时,永不写目标端)。
   *
   * ir 与 jobId 二选一:编辑器里的预览传当前画布序列化出的 IR(含未保存的改动),
   * 这样不必先保存才能看样本。L3/L4 敏感列由后端按列名兜底脱敏。
   */
  preview(payload: EtlPreviewRequest): Promise<EtlPreviewResponse> {
    return http.post<EtlPreviewResponse>('/control/etl/preview', payload)
  },

  /** 某个已保存 ETL 任务的增量水位线(需要 control:etl:read) */
  watermarks(jobId: string): Promise<EtlWatermark[]> {
    return http.get<EtlWatermark[]>(`/control/etl/${encodeURIComponent(jobId)}/watermarks`)
  },

  /** 重置某个源节点的水位线(需要 control:etl:manage;后端强制必须有原因并写审计) */
  resetWatermark(
    jobId: string,
    sourceNodeId: string,
    payload: EtlResetWatermarkRequest,
  ): Promise<EtlWatermark> {
    return http.post<EtlWatermark>(
      `/control/etl/${encodeURIComponent(jobId)}/watermarks/${encodeURIComponent(sourceNodeId)}/reset`,
      payload,
    )
  },

  /** 某次 ETL 执行的脏数据批次摘要(需要 control:etl:read) */
  badRows(executionId: string): Promise<EtlBadRowBatch[]> {
    return http.get<EtlBadRowBatch[]>(`/control/etl/executions/${encodeURIComponent(executionId)}/bad-rows`)
  },
}
