-- chance-ai-mapping-inbox: AI 字段→本体映射建议工单（PG RLS）

CREATE TABLE IF NOT EXISTS gv_mapping_proposal (
    id                          VARCHAR(40)  PRIMARY KEY,
    workspace_id                VARCHAR(40)  NOT NULL,
    source_table                VARCHAR(200) NOT NULL,
    source_field                VARCHAR(200) NOT NULL,
    recommended_business_urn    VARCHAR(512),
    alternative_candidates_json TEXT         NOT NULL DEFAULT '[]',
    confidence                  NUMERIC(5, 4) NOT NULL DEFAULT 0,
    transformation_type         VARCHAR(32)  NOT NULL DEFAULT 'IDENTITY',
    transform_expression        TEXT,
    join_condition_proposal     TEXT,
    evidence_json               TEXT         NOT NULL DEFAULT '{}',
    routing_tier                VARCHAR(64)  NOT NULL,
    suggestion_fingerprint      VARCHAR(128) NOT NULL,
    state                       VARCHAR(32)  NOT NULL DEFAULT 'PROPOSED',
    decision_note               VARCHAR(1000),
    decided_by                  VARCHAR(40),
    decided_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(40)  NOT NULL,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_gv_map_prop_ws_state
    ON gv_mapping_proposal (workspace_id, state, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_gv_map_prop_fp
    ON gv_mapping_proposal (workspace_id, suggestion_fingerprint, state);

ALTER TABLE gv_mapping_proposal ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_workspace_isolation ON gv_mapping_proposal;
CREATE POLICY p_workspace_isolation ON gv_mapping_proposal
    USING (COALESCE(current_setting('app.rls_bypass', true), 'false') = 'true'
           OR workspace_id = NULLIF(current_setting('app.workspace_id', true), ''))
    WITH CHECK (COALESCE(current_setting('app.rls_bypass', true), 'false') = 'true'
                OR workspace_id = NULLIF(current_setting('app.workspace_id', true), ''));
