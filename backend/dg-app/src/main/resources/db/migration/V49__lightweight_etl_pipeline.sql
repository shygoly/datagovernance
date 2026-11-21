-- lightweight-etl-pipeline：水位线 + 脏数据批次 + 菜单/权限

CREATE TABLE IF NOT EXISTS etl_watermark (
    id                VARCHAR(40)  PRIMARY KEY,
    workspace_id      VARCHAR(40)  NOT NULL,
    job_id            VARCHAR(40)  NOT NULL,
    source_node_id    VARCHAR(64)  NOT NULL,
    watermark_column  VARCHAR(128) NOT NULL,
    watermark_type    VARCHAR(24)  NOT NULL,
    last_value        VARCHAR(256),
    last_execution_id VARCHAR(40),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by        VARCHAR(40),
    CONSTRAINT uk_etl_watermark UNIQUE (workspace_id, job_id, source_node_id)
);

CREATE INDEX IF NOT EXISTS idx_etl_watermark_ws_job
  ON etl_watermark (workspace_id, job_id);

CREATE TABLE IF NOT EXISTS etl_bad_row_batch (
    id                VARCHAR(40)  PRIMARY KEY,
    workspace_id      VARCHAR(40)  NOT NULL,
    job_id            VARCHAR(40)  NOT NULL,
    execution_id      VARCHAR(40)  NOT NULL,
    node_id           VARCHAR(64)  NOT NULL,
    bad_row_count     BIGINT       NOT NULL DEFAULT 0,
    storage_ref       VARCHAR(1024),
    sample_error_json TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_etl_bad_row_ws_exec
  ON etl_bad_row_batch (workspace_id, execution_id);

-- 行级安全（与 V35 同策略）
DO $$
DECLARE
    tbl TEXT;
    tables TEXT[] := ARRAY['etl_watermark', 'etl_bad_row_batch'];
BEGIN
    FOREACH tbl IN ARRAY tables LOOP
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
    END LOOP;
END $$;

-- 菜单与权限（幂等；菜单即 type=MENU 的 pf_permission）
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space)
SELECT 'menu:integration:etl', 'ETL 管道', 'MENU', 'menu:integration', '/integration/etl', 'Share', 33, 'CONTROL'
WHERE NOT EXISTS (SELECT 1 FROM pf_permission WHERE code = 'menu:integration:etl');

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space)
SELECT 'control:etl:read', '查看 ETL 管道', 'ACTION', 'menu:integration:etl', 1, 'CONTROL'
WHERE NOT EXISTS (SELECT 1 FROM pf_permission WHERE code = 'control:etl:read');

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space)
SELECT 'control:etl:manage', '管理 ETL 管道', 'ACTION', 'menu:integration:etl', 2, 'CONTROL'
WHERE NOT EXISTS (SELECT 1 FROM pf_permission WHERE code = 'control:etl:manage');

INSERT INTO pf_role_permission (role_id, permission_code)
SELECT 'role_builtin_ws_admin', code FROM pf_permission
WHERE code IN ('menu:integration:etl', 'control:etl:read', 'control:etl:manage')
  AND NOT EXISTS (
      SELECT 1 FROM pf_role_permission rp
      WHERE rp.role_id = 'role_builtin_ws_admin' AND rp.permission_code = pf_permission.code
  );

INSERT INTO pf_role_permission (role_id, permission_code)
SELECT 'role_builtin_ws_developer', code FROM pf_permission
WHERE code IN ('menu:integration:etl', 'control:etl:read', 'control:etl:manage')
  AND NOT EXISTS (
      SELECT 1 FROM pf_role_permission rp
      WHERE rp.role_id = 'role_builtin_ws_developer' AND rp.permission_code = pf_permission.code
  );

INSERT INTO pf_role_permission (role_id, permission_code)
SELECT 'role_builtin_ws_viewer', code FROM pf_permission
WHERE code IN ('menu:integration:etl', 'control:etl:read')
  AND NOT EXISTS (
      SELECT 1 FROM pf_role_permission rp
      WHERE rp.role_id = 'role_builtin_ws_viewer' AND rp.permission_code = pf_permission.code
  );
