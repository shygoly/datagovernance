-- Phase C (c2-governed-learn-bitemporal-fact):
-- 激活 GOVERNANCE-MODEL 的 Learn 闭环（A0.1 双时间态 Fact + A0.3 暂存隔离/审校晋升/可回滚）。
--
-- 不变量（CONSTITUTION C9）：
-- 1. 严禁无外部反馈信号直接覆写权威知识；所有新沉淀事实先落 PROPOSED 暂存区；
-- 2. 晋升 APPROVED 时不物理删除旧事实，而是写旧事实 valid_to = now() 并置 SUPERSEDED；
-- 3. 发生投毒或纠偏时支持一键 ROLLED_BACK 并恢复被取代的前驱版本。
CREATE TABLE gv_governed_fact (
    id              VARCHAR(40)  PRIMARY KEY,
    workspace_id    VARCHAR(40)  NOT NULL,
    asset_ref       VARCHAR(256) NOT NULL,
    -- structure / glossary / quality_convention / case / lineage / classification
    fact_type       VARCHAR(32)  NOT NULL,
    title           VARCHAR(200) NOT NULL,
    content_json    TEXT         NOT NULL,
    -- metadata_snapshot / human_register / doc_extract / attribution / execution_event
    source          VARCHAR(32)  NOT NULL,
    -- 外部反馈前置信号：ANALYSIS_REPORT_CONFIRMED / SQL_VERIFIED / HUMAN_CORRECTION / TEST_PASSED
    feedback_source VARCHAR(64)  NOT NULL,
    evidence_ref    VARCHAR(256) NOT NULL,
    confidence      DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    version         INT          NOT NULL DEFAULT 1,
    supersedes_id   VARCHAR(40),
    -- PROPOSED / APPROVED / REJECTED / SUPERSEDED / ROLLED_BACK
    state           VARCHAR(16)  NOT NULL DEFAULT 'PROPOSED',
    valid_from      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    valid_to        TIMESTAMPTZ,
    tx_recorded_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(40)  NOT NULL,
    reviewed_by     VARCHAR(40),
    reviewed_at     TIMESTAMPTZ,
    review_note     VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_gv_fact_ws_asset
    ON gv_governed_fact (workspace_id, asset_ref, fact_type, state);
CREATE INDEX idx_gv_fact_bitemporal
    ON gv_governed_fact (workspace_id, state, valid_from, valid_to);

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('governance:knowledge:read',   '查看治理事实与知识沉淀', 'ACTION', 'menu:workbench', 23, 'GOVERNANCE'),
  ('governance:knowledge:review', '审校与回滚治理知识沉淀', 'ACTION', 'menu:workbench', 24, 'GOVERNANCE');

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'governance:knowledge:read'),
  ('role_builtin_ws_admin',     'governance:knowledge:review'),
  ('role_builtin_ws_developer', 'governance:knowledge:read'),
  ('role_builtin_ws_developer', 'governance:knowledge:review'),
  ('role_builtin_ws_viewer',    'governance:knowledge:read');
