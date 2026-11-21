-- metric-6d-ontology-verification · W2 图闸门前置
-- 扩展语义对象 Provenance/Verified、引用边 content hash、验证记录表

ALTER TABLE md_semantic_object
    ADD COLUMN IF NOT EXISTS provenance_json JSONB,
    ADD COLUMN IF NOT EXISTS verified_json JSONB,
    ADD COLUMN IF NOT EXISTS requires_human_review BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN md_semantic_object.provenance_json IS '字段级 Provenance（来源优先级与证据）';
COMMENT ON COLUMN md_semantic_object.verified_json IS '四层验证摘要（G1-G5 / 反模式 / 统计 / 探针）';
COMMENT ON COLUMN md_semantic_object.requires_human_review IS 'llm_inference 等字段强制人审';

ALTER TABLE md_semantic_link
    ADD COLUMN IF NOT EXISTS target_content_hash VARCHAR(64);

COMMENT ON COLUMN md_semantic_link.target_content_hash IS '引用目标 content_digest 快照，驱动 G5 STALE_REVIEW';

CREATE TABLE IF NOT EXISTS md_semantic_verification_record (
    id              VARCHAR(64) PRIMARY KEY,
    workspace_id    VARCHAR(64) NOT NULL,
    object_id       VARCHAR(64) NOT NULL REFERENCES md_semantic_object(id) ON DELETE CASCADE,
    version_no      INT,
    layer           VARCHAR(32) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    details_json    JSONB,
    executed_by     VARCHAR(128),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_md_semantic_verification_ws_obj
    ON md_semantic_verification_record (workspace_id, object_id, created_at DESC);

ALTER TABLE md_semantic_verification_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE md_semantic_verification_record FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'md_semantic_verification_record'
          AND policyname = 'md_semantic_verification_record_workspace_isolation'
    ) THEN
        CREATE POLICY md_semantic_verification_record_workspace_isolation
            ON md_semantic_verification_record
            USING (workspace_id = current_setting('app.workspace_id', true))
            WITH CHECK (workspace_id = current_setting('app.workspace_id', true));
    END IF;
END $$;
