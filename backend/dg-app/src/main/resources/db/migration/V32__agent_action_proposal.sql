-- Phase B (b2-agent-host-consent-ui): Agent 写操作宿主独立确认提案表（闭合 OWASP ASI09）。
--
-- Agent 写工具不得直接修改平台状态；只能先生成 PENDING 提案并交由宿主前端
-- 渲染不可篡改确认视图，由发起人在执行瞬间携真实 JWT 二次校验后执行。
CREATE TABLE ig_agent_action_proposal (
    id              VARCHAR(40)  PRIMARY KEY,
    workspace_id    VARCHAR(40)  NOT NULL,
    user_id         VARCHAR(40)  NOT NULL,
    username        VARCHAR(128) NOT NULL,
    conversation_id VARCHAR(64),
    -- CREATE_LOCAL_DATASOURCE / UPDATE_LOCAL_DATASOURCE / CREATE_PARQUET_CATALOG / REFRESH_PARQUET_CATALOG
    action_type     VARCHAR(48)  NOT NULL,
    required_perm   VARCHAR(64)  NOT NULL,
    title           VARCHAR(200) NOT NULL,
    summary         VARCHAR(512) NOT NULL,
    payload_json    TEXT         NOT NULL,
    payload_digest  VARCHAR(64)  NOT NULL,
    -- LOW / MEDIUM / HIGH
    risk_level      VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM',
    -- PENDING / EXECUTED / REJECTED / FAILED / EXPIRED
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    result_json     TEXT,
    error_message   VARCHAR(512),
    expires_at      TIMESTAMPTZ  NOT NULL,
    decided_by      VARCHAR(40),
    decided_at      TIMESTAMPTZ,
    decision_note   VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_ig_agent_proposal_ws_user
    ON ig_agent_action_proposal (workspace_id, user_id, status, created_at DESC);
CREATE INDEX idx_ig_agent_proposal_conv
    ON ig_agent_action_proposal (workspace_id, conversation_id, created_at DESC);
