-- data-driven-ops-profile：Target/ActionType 之外的动态控制环（Trigger / Flow IR / Durable Flow / DecisionTrace）。
--
-- 边界（C1）：
--   * au_filter_preset / Target / ActionType 定义态归 Metadata；
--   * au_trigger_def / au_flow_def / au_flow_version 归 Control；
--   * au_flow_instance / au_entity_lock_cooldown 归 Runtime；
--   * au_decision_trace 归 Governance。
-- 全部表强制 workspace_id + PostgreSQL RLS 兜底（C5）。

CREATE TABLE IF NOT EXISTS au_filter_preset (
    id              VARCHAR(40)  PRIMARY KEY,
    workspace_id    VARCHAR(40)  NOT NULL,
    code            VARCHAR(128) NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     VARCHAR(512),
    preset_type     VARCHAR(32)  NOT NULL DEFAULT 'SMQ_FILTER',
    spec_json       TEXT         NOT NULL DEFAULT '{}',
    status          VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(40),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by      VARCHAR(40)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_au_filter_preset_code
    ON au_filter_preset (workspace_id, lower(code));
CREATE INDEX IF NOT EXISTS idx_au_filter_preset_ws
    ON au_filter_preset (workspace_id, preset_type, status);

CREATE TABLE IF NOT EXISTS au_trigger_def (
    id              VARCHAR(40)  PRIMARY KEY,
    workspace_id    VARCHAR(40)  NOT NULL,
    code            VARCHAR(128) NOT NULL,
    name            VARCHAR(200) NOT NULL,
    -- METRIC_THRESHOLD / QUALITY_FAILURE / ETL_ANOMALY / DATA_ARRIVAL
    source_type     VARCHAR(32)  NOT NULL,
    condition_json  TEXT         NOT NULL DEFAULT '{}',
    debounce_sec    INT          NOT NULL DEFAULT 0,
    flow_id         VARCHAR(40)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    priority        INT          NOT NULL DEFAULT 100,
    last_fired_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(40),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by      VARCHAR(40)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_au_trigger_code
    ON au_trigger_def (workspace_id, lower(code));
CREATE INDEX IF NOT EXISTS idx_au_trigger_ws_source
    ON au_trigger_def (workspace_id, source_type, enabled);
CREATE INDEX IF NOT EXISTS idx_au_trigger_flow
    ON au_trigger_def (workspace_id, flow_id);

CREATE TABLE IF NOT EXISTS au_flow_def (
    id              VARCHAR(40)  PRIMARY KEY,
    workspace_id    VARCHAR(40)  NOT NULL,
    code            VARCHAR(128) NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     VARCHAR(512),
    -- DRAFT / PUBLISHED / DEPRECATED
    status          VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    current_version INT          NOT NULL DEFAULT 0,
    -- SHADOW / L2_ASSISTED / L1_AUTO
    run_mode        VARCHAR(24)  NOT NULL DEFAULT 'SHADOW',
    owner_username  VARCHAR(128),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(40),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by      VARCHAR(40)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_au_flow_code
    ON au_flow_def (workspace_id, lower(code));
CREATE INDEX IF NOT EXISTS idx_au_flow_ws_status
    ON au_flow_def (workspace_id, status);

CREATE TABLE IF NOT EXISTS au_flow_version (
    id                VARCHAR(40)  PRIMARY KEY,
    workspace_id      VARCHAR(40)  NOT NULL,
    flow_id           VARCHAR(40)  NOT NULL REFERENCES au_flow_def(id) ON DELETE CASCADE,
    version_no        INT          NOT NULL,
    spec_json         TEXT         NOT NULL,
    compiled_json     TEXT         NOT NULL,
    content_digest    VARCHAR(64)  NOT NULL,
    run_mode          VARCHAR(24)  NOT NULL DEFAULT 'SHADOW',
    promotion_note    VARCHAR(512),
    compiled_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    compiled_by       VARCHAR(40)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_au_flow_version
    ON au_flow_version (flow_id, version_no);
CREATE INDEX IF NOT EXISTS idx_au_flow_version_ws
    ON au_flow_version (workspace_id, flow_id);

CREATE TABLE IF NOT EXISTS au_flow_instance (
    id                VARCHAR(40)  PRIMARY KEY,
    workspace_id      VARCHAR(40)  NOT NULL,
    flow_id           VARCHAR(40)  NOT NULL,
    flow_code         VARCHAR(128) NOT NULL,
    flow_version      INT          NOT NULL,
    trigger_id        VARCHAR(40),
    trace_id          VARCHAR(40),
    -- RUNNING / WAITING_APPROVAL / WAITING_VERIFY / COMPENSATING /
    -- COMPLETED / SHADOW_COMPLETED / FAILED / CANCELLED / COMPENSATION_FAILED
    status            VARCHAR(32)  NOT NULL,
    current_step_id   VARCHAR(64),
    entity_key        VARCHAR(256) NOT NULL,
    run_mode          VARCHAR(24)  NOT NULL,
    plan_json         TEXT         NOT NULL DEFAULT '{}',
    checkpoint_json   TEXT         NOT NULL DEFAULT '{}',
    history_json      TEXT         NOT NULL DEFAULT '[]',
    wake_at           TIMESTAMPTZ,
    error_message     VARCHAR(1000),
    started_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_au_flow_instance_ws_status
    ON au_flow_instance (workspace_id, status, wake_at);
CREATE INDEX IF NOT EXISTS idx_au_flow_instance_trace
    ON au_flow_instance (workspace_id, trace_id);
CREATE INDEX IF NOT EXISTS idx_au_flow_instance_entity
    ON au_flow_instance (workspace_id, entity_key);

CREATE TABLE IF NOT EXISTS au_entity_lock_cooldown (
    id                VARCHAR(40)  PRIMARY KEY,
    workspace_id      VARCHAR(40)  NOT NULL,
    entity_key        VARCHAR(256) NOT NULL,
    instance_id       VARCHAR(40),
    locked_at         TIMESTAMPTZ,
    cooldown_until    TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_au_entity_lock
    ON au_entity_lock_cooldown (workspace_id, entity_key);
CREATE INDEX IF NOT EXISTS idx_au_entity_lock_cooldown
    ON au_entity_lock_cooldown (workspace_id, cooldown_until);

CREATE TABLE IF NOT EXISTS au_decision_trace (
    id                      VARCHAR(40) PRIMARY KEY,
    workspace_id            VARCHAR(40) NOT NULL,
    trace_id                VARCHAR(40) NOT NULL,
    instance_id             VARCHAR(40),
    flow_id                 VARCHAR(40),
    trigger_id              VARCHAR(40),
    run_mode                VARCHAR(24) NOT NULL,
    -- PENDING / IMPROVED / NO_EFFECT / DEGRADED / INCONCLUSIVE
    verify_status           VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    trigger_snapshot_json   TEXT        NOT NULL DEFAULT '{}',
    arbiter_decision_json   TEXT        NOT NULL DEFAULT '{}',
    dry_run_result_json     TEXT        NOT NULL DEFAULT '{}',
    proposal_id             VARCHAR(40),
    activity_result_json    TEXT        NOT NULL DEFAULT '{}',
    verify_result_json      TEXT        NOT NULL DEFAULT '{}',
    reproducibility_fingerprint TEXT    NOT NULL DEFAULT '{}',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_au_decision_trace_trace
    ON au_decision_trace (workspace_id, trace_id);
CREATE INDEX IF NOT EXISTS idx_au_decision_trace_ws_time
    ON au_decision_trace (workspace_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_au_decision_trace_instance
    ON au_decision_trace (workspace_id, instance_id);

-- ── PostgreSQL RLS 纵深隔离 ─────────────────────────────────────────
DO $$
DECLARE
    tbl TEXT;
    autonomy_tables TEXT[] := ARRAY[
        'au_filter_preset',
        'au_trigger_def',
        'au_flow_def',
        'au_flow_version',
        'au_flow_instance',
        'au_entity_lock_cooldown',
        'au_decision_trace'
    ];
BEGIN
    FOREACH tbl IN ARRAY autonomy_tables LOOP
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

-- ── 菜单与权限（幂等）────────────────────────────────────────────────
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space)
SELECT 'menu:autonomy', '数据自治', 'MENU', NULL, '/autonomy/flows', 'MagicStick', 60, 'CONTROL'
WHERE NOT EXISTS (SELECT 1 FROM pf_permission WHERE code = 'menu:autonomy');

INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space)
SELECT 'menu:autonomy:triggers', '自治触发器', 'MENU', 'menu:autonomy', '/autonomy/triggers', 'AlarmClock', 61, 'CONTROL'
WHERE NOT EXISTS (SELECT 1 FROM pf_permission WHERE code = 'menu:autonomy:triggers');

INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space)
SELECT 'menu:autonomy:flows', '自治流程', 'MENU', 'menu:autonomy', '/autonomy/flows', 'Share', 62, 'CONTROL'
WHERE NOT EXISTS (SELECT 1 FROM pf_permission WHERE code = 'menu:autonomy:flows');

INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space)
SELECT 'menu:autonomy:traces', '决策轨迹', 'MENU', 'menu:autonomy', '/autonomy/traces', 'Document', 63, 'GOVERNANCE'
WHERE NOT EXISTS (SELECT 1 FROM pf_permission WHERE code = 'menu:autonomy:traces');

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space)
SELECT 'autonomy:trigger:read', '查看自治触发器', 'ACTION', 'menu:autonomy:triggers', 1, 'CONTROL'
WHERE NOT EXISTS (SELECT 1 FROM pf_permission WHERE code = 'autonomy:trigger:read');
INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space)
SELECT 'autonomy:trigger:manage', '管理自治触发器', 'ACTION', 'menu:autonomy:triggers', 2, 'CONTROL'
WHERE NOT EXISTS (SELECT 1 FROM pf_permission WHERE code = 'autonomy:trigger:manage');

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space)
SELECT 'autonomy:flow:read', '查看自治流程', 'ACTION', 'menu:autonomy:flows', 1, 'CONTROL'
WHERE NOT EXISTS (SELECT 1 FROM pf_permission WHERE code = 'autonomy:flow:read');
INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space)
SELECT 'autonomy:flow:manage', '编辑与编译自治流程', 'ACTION', 'menu:autonomy:flows', 2, 'CONTROL'
WHERE NOT EXISTS (SELECT 1 FROM pf_permission WHERE code = 'autonomy:flow:manage');
INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space)
SELECT 'autonomy:flow:publish', '发布与灰度晋升自治流程', 'ACTION', 'menu:autonomy:flows', 3, 'CONTROL'
WHERE NOT EXISTS (SELECT 1 FROM pf_permission WHERE code = 'autonomy:flow:publish');
INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space)
SELECT 'autonomy:flow:approve', '审批 L2 自治动作', 'ACTION', 'menu:autonomy:flows', 4, 'GOVERNANCE'
WHERE NOT EXISTS (SELECT 1 FROM pf_permission WHERE code = 'autonomy:flow:approve');

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space)
SELECT 'autonomy:trace:read', '查看自治决策轨迹', 'ACTION', 'menu:autonomy:traces', 1, 'GOVERNANCE'
WHERE NOT EXISTS (SELECT 1 FROM pf_permission WHERE code = 'autonomy:trace:read');

INSERT INTO pf_role_permission (role_id, permission_code)
SELECT 'role_builtin_ws_admin', code FROM pf_permission
WHERE code IN ('menu:autonomy', 'menu:autonomy:triggers', 'menu:autonomy:flows', 'menu:autonomy:traces',
               'autonomy:trigger:read', 'autonomy:trigger:manage',
               'autonomy:flow:read', 'autonomy:flow:manage', 'autonomy:flow:publish', 'autonomy:flow:approve',
               'autonomy:trace:read')
  AND NOT EXISTS (SELECT 1 FROM pf_role_permission rp
                  WHERE rp.role_id = 'role_builtin_ws_admin' AND rp.permission_code = pf_permission.code);

INSERT INTO pf_role_permission (role_id, permission_code)
SELECT 'role_builtin_ws_developer', code FROM pf_permission
WHERE code IN ('menu:autonomy', 'menu:autonomy:triggers', 'menu:autonomy:flows', 'menu:autonomy:traces',
               'autonomy:trigger:read', 'autonomy:trigger:manage',
               'autonomy:flow:read', 'autonomy:flow:manage', 'autonomy:flow:publish',
               'autonomy:trace:read')
  AND NOT EXISTS (SELECT 1 FROM pf_role_permission rp
                  WHERE rp.role_id = 'role_builtin_ws_developer' AND rp.permission_code = pf_permission.code);

INSERT INTO pf_role_permission (role_id, permission_code)
SELECT 'role_builtin_ws_viewer', code FROM pf_permission
WHERE code IN ('menu:autonomy', 'menu:autonomy:triggers', 'menu:autonomy:flows', 'menu:autonomy:traces',
               'autonomy:trigger:read', 'autonomy:flow:read', 'autonomy:trace:read')
  AND NOT EXISTS (SELECT 1 FROM pf_role_permission rp
                  WHERE rp.role_id = 'role_builtin_ws_viewer' AND rp.permission_code = pf_permission.code);
