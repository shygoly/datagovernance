-- column-lineage-e2e P3-2：血缘历史版本 Diff 的批次标识与改写前快照。
--
-- 同一作业的同一 from/to/relation 边在重新上报时原地更新（唯一索引不含 status，不能为旧版本再插一行），
-- 所以「表达式被改写」这一类变化在不加列的情况下无法回看。这里只加三类可空列：
--   introduced_at            —— 边被某次上报引入/复活的批次时间；
--   previous_transform_expr / previous_dependency_type —— 被改写前的值；
--   previous_changed_at      —— 改写发生的批次时间（与同批次 introduced_at / superseded_at 同值）。
-- 历史行全部为 NULL：Diff 对它们只能提供 removed（来自 superseded_at），不会伪造 added / modified。

ALTER TABLE md_lineage_edge
  ADD COLUMN IF NOT EXISTS introduced_at             TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS previous_transform_expr   TEXT,
  ADD COLUMN IF NOT EXISTS previous_dependency_type  VARCHAR(16),
  ADD COLUMN IF NOT EXISTS previous_changed_at       TIMESTAMPTZ;

COMMENT ON COLUMN md_lineage_edge.introduced_at IS '被某次作业上报引入/复活的批次时间（Diff 批次标识）';
COMMENT ON COLUMN md_lineage_edge.previous_changed_at IS '转换表达式/依赖类型被改写的批次时间';

CREATE INDEX IF NOT EXISTS idx_md_lineage_edge_job_superseded
  ON md_lineage_edge (workspace_id, job_id, superseded_at)
  WHERE status = 'SUPERSEDED';
