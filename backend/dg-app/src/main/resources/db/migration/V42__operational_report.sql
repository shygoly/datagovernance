-- W5: OperationalReport（运营报表；非 AnalysisReport）

CREATE TABLE IF NOT EXISTS gv_operational_report_def (
    id              VARCHAR(64) PRIMARY KEY,
    workspace_id    VARCHAR(64) NOT NULL,
    code            VARCHAR(128) NOT NULL,
    name            VARCHAR(256) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    latest_version  INT NOT NULL DEFAULT 0,
    param_schema_json JSONB,
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_gv_op_report_ws_code UNIQUE (workspace_id, code),
    CONSTRAINT ck_gv_op_report_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'))
);

CREATE TABLE IF NOT EXISTS gv_operational_report_version (
    id              VARCHAR(64) PRIMARY KEY,
    report_id       VARCHAR(64) NOT NULL REFERENCES gv_operational_report_def(id),
    workspace_id    VARCHAR(64) NOT NULL,
    version         INT NOT NULL,
    supersedes_version INT,
    blocks_json     JSONB NOT NULL,
    content_digest  CHAR(64) NOT NULL,
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_gv_op_report_ver UNIQUE (report_id, version)
);

CREATE TABLE IF NOT EXISTS gv_operational_report_run (
    id              VARCHAR(64) PRIMARY KEY,
    report_id       VARCHAR(64) NOT NULL REFERENCES gv_operational_report_def(id),
    workspace_id    VARCHAR(64) NOT NULL,
    version         INT NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    params_json     JSONB,
    reproducibility_fingerprint TEXT,
    artifact_md     TEXT,
    artifact_csv    TEXT,
    error_message   VARCHAR(1000),
    triggered_by    VARCHAR(64),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_gv_op_report_run_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED'))
);

CREATE TABLE IF NOT EXISTS gv_operational_report_schedule (
    id              VARCHAR(64) PRIMARY KEY,
    report_id       VARCHAR(64) NOT NULL REFERENCES gv_operational_report_def(id),
    workspace_id    VARCHAR(64) NOT NULL,
    cron_expr       VARCHAR(128) NOT NULL,
    recipients_json JSONB,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    require_export_confirm BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_gv_op_report_ws ON gv_operational_report_def (workspace_id, status);
CREATE INDEX IF NOT EXISTS idx_gv_op_run_report ON gv_operational_report_run (report_id, created_at DESC);

COMMENT ON TABLE gv_operational_report_def IS '运营报表定义（参数化块编排；≠ AnalysisReport）';
COMMENT ON TABLE gv_operational_report_run IS '运营报表运行快照 + reproducibility_fingerprint + 制品';
