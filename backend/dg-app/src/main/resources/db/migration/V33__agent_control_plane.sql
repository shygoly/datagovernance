-- Phase B (b3-agent-control-plane-guard): 智能体控制面策略与 Kill Switch（闭合 OWASP ASI08 / ASI10）。
--
-- workspace_id = '__GLOBAL__' 表示全局策略；具体 workspace_id 表示租户级覆盖策略。
-- 全局 Kill Switch 与租户级 Kill Switch 取逻辑或，任一开启立即阻断并中止在途会话。
CREATE TABLE ig_agent_control_policy (
    id                      VARCHAR(40)  PRIMARY KEY,
    workspace_id            VARCHAR(40)  NOT NULL UNIQUE,
    kill_switch_enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    kill_switch_reason      VARCHAR(512),
    max_tool_calls_per_turn INT          NOT NULL DEFAULT 12,
    max_consecutive_errors  INT          NOT NULL DEFAULT 2,
    daily_turn_quota        INT          NOT NULL DEFAULT 200,
    updated_by              VARCHAR(40),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO ig_agent_control_policy (
    id, workspace_id, kill_switch_enabled, max_tool_calls_per_turn, max_consecutive_errors, daily_turn_quota
) VALUES (
    'agp_global_default', '__GLOBAL__', FALSE, 12, 2, 200
);

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('governance:agent:manage', '管理智能体控制面与急停开关', 'ACTION', 'menu:workbench', 22, 'GOVERNANCE');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin', 'governance:agent:manage');
