-- data-driven-ops-profile：ActionType.paramEnvelope.maxExecutionsPerDay 的跨实例日计数。
--
-- 计数按 workspace + action_code + UTC 日期唯一；只记录“实际尝试执行”的副作用动作，
-- SHADOW / L0_ADVISORY 不消耗额度。

CREATE TABLE IF NOT EXISTS au_action_execution_counter (
    id              VARCHAR(40) PRIMARY KEY,
    workspace_id    VARCHAR(40) NOT NULL,
    action_code     VARCHAR(128) NOT NULL,
    counter_day     DATE        NOT NULL,
    execution_count INT         NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_au_action_counter
    ON au_action_execution_counter (workspace_id, action_code, counter_day);

DO $$
DECLARE
    tbl TEXT := 'au_action_execution_counter';
BEGIN
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tbl);
    EXECUTE format('DROP POLICY IF EXISTS p_workspace_isolation ON %I', tbl);
    EXECUTE format(
        'CREATE POLICY p_workspace_isolation ON %I '
        || 'USING (COALESCE(current_setting(''app.rls_bypass'', true), ''false'') = ''true'' '
        || '       OR workspace_id = NULLIF(current_setting(''app.workspace_id'', true), '''')) '
        || 'WITH CHECK (COALESCE(current_setting(''app.rls_bypass'', true), ''false'') = ''true'' '
        || '            OR workspace_id = NULLIF(current_setting(''app.workspace_id'', true), ''''))',
        tbl
    );
END $$;
