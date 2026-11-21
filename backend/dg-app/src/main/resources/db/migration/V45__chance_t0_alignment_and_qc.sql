-- chance-t0-alignment-and-qc-rules:
-- 锚点规格、对齐运行/命中、临床质控发现（PG RLS）

CREATE TABLE IF NOT EXISTS md_anchor_event_spec (
    id                          VARCHAR(40)  PRIMARY KEY,
    workspace_id                VARCHAR(40)  NOT NULL,
    code                        VARCHAR(64)  NOT NULL,
    name                        VARCHAR(200) NOT NULL,
    anchor_type                 VARCHAR(64)  NOT NULL,
    -- 逻辑绑定：logical_table_id / semantic 列 URN 等，禁止硬编码物理表名
    binding_json                TEXT         NOT NULL,
    window_config_json          TEXT         NOT NULL DEFAULT '{}',
    standard_ref                VARCHAR(128),
    status                      VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_by                  VARCHAR(40)  NOT NULL,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, code)
);

CREATE TABLE IF NOT EXISTS md_anchor_alignment_run (
    id                          VARCHAR(40)  PRIMARY KEY,
    workspace_id                VARCHAR(40)  NOT NULL,
    spec_id                     VARCHAR(40),
    operator_id                 VARCHAR(64)  NOT NULL,
    operator_version            VARCHAR(32)  NOT NULL,
    status                      VARCHAR(32)  NOT NULL,
    anchor_count                INT          NOT NULL DEFAULT 0,
    hit_count                   INT          NOT NULL DEFAULT 0,
    ambiguous_count             INT          NOT NULL DEFAULT 0,
    missing_count               INT          NOT NULL DEFAULT 0,
    input_digest                VARCHAR(128),
    operator_digest             VARCHAR(128),
    reproducibility_fingerprint TEXT,
    created_by                  VARCHAR(40)  NOT NULL,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS md_anchor_alignment_hit (
    id                          VARCHAR(40)  PRIMARY KEY,
    workspace_id                VARCHAR(40)  NOT NULL,
    run_id                      VARCHAR(40)  NOT NULL,
    patient_ref                 VARCHAR(128) NOT NULL,
    anchor_id                   VARCHAR(128) NOT NULL,
    session_seq                 INT          NOT NULL,
    role                        VARCHAR(64)  NOT NULL,
    window_label                VARCHAR(64),
    candidate_ref               VARCHAR(128),
    event_ts                    TIMESTAMPTZ,
    status                      VARCHAR(32)  NOT NULL,
    detail_json                 TEXT
);

CREATE INDEX IF NOT EXISTS idx_md_anchor_hit_run
    ON md_anchor_alignment_hit (workspace_id, run_id);
CREATE INDEX IF NOT EXISTS idx_md_anchor_run_ws
    ON md_anchor_alignment_run (workspace_id, created_at DESC);

CREATE TABLE IF NOT EXISTS gv_clinical_qc_finding (
    id                          VARCHAR(40)  PRIMARY KEY,
    workspace_id                VARCHAR(40)  NOT NULL,
    run_id                      VARCHAR(40),
    ruleset_ref                 VARCHAR(128) NOT NULL,
    rule_id                     VARCHAR(64)  NOT NULL,
    severity                    VARCHAR(32)  NOT NULL,
    patient_ref                 VARCHAR(128),
    anchor_ref                  VARCHAR(128),
    session_seq                 INT,
    message                     VARCHAR(1000) NOT NULL,
    evidence_json               TEXT         NOT NULL,
    status                      VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    resolution_note             VARCHAR(1000),
    resolved_by                 VARCHAR(40),
    resolved_at                 TIMESTAMPTZ,
    created_by                  VARCHAR(40)  NOT NULL,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_gv_qc_finding_ws_status
    ON gv_clinical_qc_finding (workspace_id, status, created_at DESC);

DO $$
DECLARE
    tbl TEXT;
BEGIN
    FOREACH tbl IN ARRAY ARRAY[
        'md_anchor_event_spec',
        'md_anchor_alignment_run',
        'md_anchor_alignment_hit',
        'gv_clinical_qc_finding'
    ]
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tbl);
        EXECUTE format('DROP POLICY IF EXISTS p_workspace_isolation ON %I', tbl);
        EXECUTE format(
            'CREATE POLICY p_workspace_isolation ON %I '
            || 'USING (COALESCE(current_setting(''app.rls_bypass'', true), ''false'') = ''true'' '
            || 'OR workspace_id = NULLIF(current_setting(''app.workspace_id'', true), '''')) '
            || 'WITH CHECK (COALESCE(current_setting(''app.rls_bypass'', true), ''false'') = ''true'' '
            || 'OR workspace_id = NULLIF(current_setting(''app.workspace_id'', true), ''''))',
            tbl);
    END LOOP;
END $$;
