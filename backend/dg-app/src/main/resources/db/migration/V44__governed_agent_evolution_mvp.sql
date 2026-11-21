-- governed-agent-evolution-mvp:
-- 1. 确定性数据质量剖析快照表 md_dataset_profile_run（含 PG RLS 租户隔离）
-- 2. 扩展 gv_governed_fact（V34）：补充 pattern_fingerprint 与 rejection_reason 支持驳回防重提（C9）

CREATE TABLE IF NOT EXISTS md_dataset_profile_run (
    id                          VARCHAR(40)  PRIMARY KEY,
    workspace_id                VARCHAR(40)  NOT NULL,
    dataset_id                  VARCHAR(40)  NOT NULL,
    dataset_name                VARCHAR(200) NOT NULL,
    -- OK / ANOMALIES_DETECTED / INCONCLUSIVE
    verdict                     VARCHAR(32)  NOT NULL,
    total_rows                  BIGINT       NOT NULL DEFAULT 0,
    sampled_rows                BIGINT       NOT NULL DEFAULT 0,
    truncated                   BOOLEAN      NOT NULL DEFAULT FALSE,
    anomaly_count               INT          NOT NULL DEFAULT 0,
    pattern_fingerprint         VARCHAR(128),
    profile_json                TEXT         NOT NULL,
    anomalies_json              TEXT         NOT NULL,
    standard_refs_json          TEXT,
    reproducibility_fingerprint TEXT,
    created_by                  VARCHAR(40)  NOT NULL,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_md_profile_run_ws_ds
    ON md_dataset_profile_run (workspace_id, dataset_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_md_profile_run_pattern
    ON md_dataset_profile_run (workspace_id, pattern_fingerprint);

ALTER TABLE md_dataset_profile_run ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_workspace_isolation ON md_dataset_profile_run;
CREATE POLICY p_workspace_isolation ON md_dataset_profile_run
    USING (COALESCE(current_setting('app.rls_bypass', true), 'false') = 'true'
           OR workspace_id = NULLIF(current_setting('app.workspace_id', true), ''))
    WITH CHECK (COALESCE(current_setting('app.rls_bypass', true), 'false') = 'true'
                OR workspace_id = NULLIF(current_setting('app.workspace_id', true), ''));

ALTER TABLE gv_governed_fact
    ADD COLUMN IF NOT EXISTS pattern_fingerprint VARCHAR(128);
ALTER TABLE gv_governed_fact
    ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(512);

CREATE INDEX IF NOT EXISTS idx_gv_fact_ws_pattern
    ON gv_governed_fact (workspace_id, pattern_fingerprint, state);
