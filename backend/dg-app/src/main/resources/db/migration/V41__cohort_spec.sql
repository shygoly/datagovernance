-- W6: CohortSpec 定义态表（成员物化与 ClinicalOperator 执行分属 Runtime）

CREATE TABLE IF NOT EXISTS md_cohort_spec (
    id              VARCHAR(64) PRIMARY KEY,
    workspace_id    VARCHAR(64) NOT NULL,
    code            VARCHAR(128) NOT NULL,
    name            VARCHAR(256) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    spec_json       JSONB NOT NULL,
    content_digest  CHAR(64),
    version_no      INT NOT NULL DEFAULT 1,
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_md_cohort_ws_code UNIQUE (workspace_id, code),
    CONSTRAINT ck_md_cohort_status CHECK (status IN ('DRAFT', 'PROPOSED', 'PUBLISHED', 'DEPRECATED'))
);

CREATE INDEX IF NOT EXISTS idx_md_cohort_ws_status
    ON md_cohort_spec (workspace_id, status);

COMMENT ON TABLE md_cohort_spec IS '临床/分析队列定义（CohortSpec）；禁止内嵌裸 SQL';

ALTER TABLE md_cohort_spec ENABLE ROW LEVEL SECURITY;
ALTER TABLE md_cohort_spec FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'md_cohort_spec' AND policyname = 'md_cohort_spec_workspace_isolation'
    ) THEN
        CREATE POLICY md_cohort_spec_workspace_isolation ON md_cohort_spec
            USING (workspace_id = current_setting('app.workspace_id', true));
    END IF;
END $$;
