-- column-lineage-e2e：双态血缘、执行证据、脱敏阻断、治理外部引用

ALTER TABLE md_lineage_edge
  ADD COLUMN IF NOT EXISTS execution_id     VARCHAR(40),
  ADD COLUMN IF NOT EXISTS dependency_type  VARCHAR(16) NOT NULL DEFAULT 'DIRECT',
  ADD COLUMN IF NOT EXISTS masking_applied  BOOLEAN     NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS masking_evidence VARCHAR(512),
  ADD COLUMN IF NOT EXISTS superseded_at    TIMESTAMPTZ;

COMMENT ON COLUMN md_lineage_edge.dependency_type IS 'DIRECT / INDIRECT';
COMMENT ON COLUMN md_lineage_edge.status IS 'PLANNED / ACTIVE / SUPERSEDED';

CREATE INDEX IF NOT EXISTS idx_md_lineage_edge_status
  ON md_lineage_edge (workspace_id, status);
CREATE INDEX IF NOT EXISTS idx_md_lineage_edge_job_status
  ON md_lineage_edge (workspace_id, job_id, status);

ALTER TABLE md_lineage_job
  ADD COLUMN IF NOT EXISTS last_execution_id VARCHAR(40),
  ADD COLUMN IF NOT EXISTS parse_status      VARCHAR(16) NOT NULL DEFAULT 'OK',
  ADD COLUMN IF NOT EXISTS parse_message     VARCHAR(2048);

ALTER TABLE md_lineage_node
  ADD COLUMN IF NOT EXISTS external_ref_type VARCHAR(32),
  ADD COLUMN IF NOT EXISTS external_ref_id   VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_md_lineage_node_ext
  ON md_lineage_node (workspace_id, external_ref_type, external_ref_id)
  WHERE external_ref_id IS NOT NULL;
