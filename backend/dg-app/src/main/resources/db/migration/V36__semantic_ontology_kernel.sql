-- OpenSpec Change: semantic-ontology-kernel
-- 统一本体语义元数据内核 + 轻量业务知识图谱 + L1/L2 安全值字典（替代外挂 SuperSonic 语义源）。
--
-- 不变量：
-- 1. 全部 5 张核心表强制包含 workspace_id 并启用 PostgreSQL RLS 纵深兜底（对齐 V35 / CONSTITUTION C5）；
-- 2. METRIC/编码类 ATTRIBUTE 发布绑定 Standard（C2）；
-- 3. AI 发现的别名、映射与图谱边仅能落 PROPOSED 暂存区，经人工审校双时间态晋升（C9）；
-- 4. md_semantic_value_dict 严禁采集 L3/L4 敏感列值（C5）。

CREATE TABLE md_semantic_object (
    id                VARCHAR(64)  PRIMARY KEY,
    workspace_id      VARCHAR(64)  NOT NULL,
    code              VARCHAR(128) NOT NULL,
    name              VARCHAR(200) NOT NULL,
    -- Layer 1: ENTITY / ATTRIBUTE / DIMENSION / FACT / METRIC
    -- Layer 2: CONCEPT / PROCESS
    object_type       VARCHAR(32)  NOT NULL,
    domain_id         VARCHAR(64),
    standard_ref      VARCHAR(128),
    glossary_term_id  VARCHAR(64),
    spec_json         TEXT         NOT NULL DEFAULT '{}',
    ai_context_json   TEXT         NOT NULL DEFAULT '{}',
    max_sensitivity   VARCHAR(16)  NOT NULL DEFAULT 'L1',
    -- DRAFT / PROPOSED / PUBLISHED / DEPRECATED / REJECTED
    status            VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    version_no        INT          NOT NULL DEFAULT 1,
    valid_from        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    valid_to          TIMESTAMPTZ,
    owner_username    VARCHAR(128),
    content_digest    VARCHAR(64),
    -- HUMAN / SEED / AI_PROPOSED
    source_kind       VARCHAR(24)  NOT NULL DEFAULT 'HUMAN',
    rejection_reason  VARCHAR(512),
    published_by      VARCHAR(64),
    published_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        VARCHAR(64),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by        VARCHAR(64)
);

CREATE UNIQUE INDEX uk_md_semantic_object_code
    ON md_semantic_object (workspace_id, lower(code));
CREATE INDEX idx_md_semantic_object_ws_type_status
    ON md_semantic_object (workspace_id, object_type, status);
CREATE INDEX idx_md_semantic_object_domain
    ON md_semantic_object (workspace_id, domain_id);

CREATE TABLE md_semantic_mapping (
    id                  VARCHAR(64)  PRIMARY KEY,
    workspace_id        VARCHAR(64)  NOT NULL,
    object_id           VARCHAR(64)  NOT NULL REFERENCES md_semantic_object(id) ON DELETE CASCADE,
    -- ID / DESC / CODE / MEASURE / TIME_KEY / FILTER_KEY
    mapping_role        VARCHAR(32)  NOT NULL DEFAULT 'MEASURE',
    catalog_dataset_id  VARCHAR(64)  NOT NULL,
    catalog_column_id   VARCHAR(64),
    expression_sql      TEXT,
    is_lookup_table     BOOLEAN      NOT NULL DEFAULT FALSE,
    priority            INT          NOT NULL DEFAULT 100,
    status              VARCHAR(24)  NOT NULL DEFAULT 'PUBLISHED',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by          VARCHAR(64)
);

CREATE INDEX idx_md_semantic_mapping_obj
    ON md_semantic_mapping (workspace_id, object_id);
CREATE INDEX idx_md_semantic_mapping_dataset
    ON md_semantic_mapping (workspace_id, catalog_dataset_id);
CREATE INDEX idx_md_semantic_mapping_column
    ON md_semantic_mapping (workspace_id, catalog_column_id);

CREATE TABLE md_semantic_link (
    id                VARCHAR(64)  PRIMARY KEY,
    workspace_id      VARCHAR(64)  NOT NULL,
    from_object_id    VARCHAR(64)  NOT NULL REFERENCES md_semantic_object(id) ON DELETE CASCADE,
    to_object_id      VARCHAR(64)  NOT NULL REFERENCES md_semantic_object(id) ON DELETE CASCADE,
    -- STRUCTURAL / SEMANTIC / CAUSAL
    edge_category     VARCHAR(16)  NOT NULL,
    -- STRUCTURAL: HAS_ATTRIBUTE / HAS_FACT / HIERARCHY_PARENT / RELATES_TO / DERIVED_FROM
    -- SEMANTIC:   IS_A / PART_OF / SYNONYM_OF / MAPS_TO_CONCEPT / PRECEDES
    -- CAUSAL:     CONSTRAINS / EXCLUDES / AFFECTS
    link_type         VARCHAR(32)  NOT NULL,
    cardinality       VARCHAR(16),
    join_spec_json    TEXT,
    rationale         TEXT,
    -- HUMAN / LINEAGE_DERIVED / AI_PROPOSED
    source_kind       VARCHAR(24)  NOT NULL DEFAULT 'HUMAN',
    -- DRAFT / PROPOSED / PUBLISHED / REJECTED
    status            VARCHAR(24)  NOT NULL DEFAULT 'PUBLISHED',
    rejection_reason  VARCHAR(512),
    valid_from        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    valid_to          TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        VARCHAR(64),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by        VARCHAR(64)
);

CREATE INDEX idx_md_semantic_link_from
    ON md_semantic_link (workspace_id, from_object_id, status);
CREATE INDEX idx_md_semantic_link_to
    ON md_semantic_link (workspace_id, to_object_id, status);

CREATE TABLE md_semantic_object_version (
    id              VARCHAR(64)  PRIMARY KEY,
    workspace_id    VARCHAR(64)  NOT NULL,
    object_id       VARCHAR(64)  NOT NULL REFERENCES md_semantic_object(id) ON DELETE CASCADE,
    version_no      INT          NOT NULL,
    snapshot_json   TEXT         NOT NULL,
    content_digest  VARCHAR(64)  NOT NULL,
    valid_from      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    valid_to        TIMESTAMPTZ,
    published_by    VARCHAR(64),
    published_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_md_semantic_object_version
    ON md_semantic_object_version (object_id, version_no);
CREATE INDEX idx_md_semantic_object_version_ws
    ON md_semantic_object_version (workspace_id, object_id);

CREATE TABLE md_semantic_value_dict (
    id              VARCHAR(64)  PRIMARY KEY,
    workspace_id    VARCHAR(64)  NOT NULL,
    object_id       VARCHAR(64)  NOT NULL REFERENCES md_semantic_object(id) ON DELETE CASCADE,
    raw_value       VARCHAR(256) NOT NULL,
    display_label   VARCHAR(256) NOT NULL,
    synonyms_json   TEXT         NOT NULL DEFAULT '[]',
    source_kind     VARCHAR(24)  NOT NULL DEFAULT 'HUMAN',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)
);

CREATE UNIQUE INDEX uk_md_semantic_value_dict
    ON md_semantic_value_dict (workspace_id, object_id, raw_value);
CREATE INDEX idx_md_semantic_value_dict_obj
    ON md_semantic_value_dict (workspace_id, object_id);

-- ── PostgreSQL RLS 纵深隔离（与 V35 保持一致）────────────────────────
DO $$
DECLARE
    tbl TEXT;
    semantic_tables TEXT[] := ARRAY[
        'md_semantic_object',
        'md_semantic_mapping',
        'md_semantic_link',
        'md_semantic_object_version',
        'md_semantic_value_dict'
    ];
BEGIN
    FOREACH tbl IN ARRAY semantic_tables LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tbl);
        EXECUTE format('DROP POLICY IF EXISTS p_workspace_isolation ON %I', tbl);
        EXECUTE format(
            'CREATE POLICY p_workspace_isolation ON %I ' ||
            'USING (COALESCE(current_setting(''app.rls_bypass'', true), ''false'') = ''true'' ' ||
            '       OR workspace_id = NULLIF(current_setting(''app.workspace_id'', true), '''')) ' ||
            'WITH CHECK (COALESCE(current_setting(''app.rls_bypass'', true), ''false'') = ''true'' ' ||
            '            OR workspace_id = NULLIF(current_setting(''app.workspace_id'', true), ''''))',
            tbl
        );
    END LOOP;
END $$;

-- ── 权限与菜单种子（幂等写入，兼容既有权限码）───────────────────────
INSERT INTO pf_permission (code, name, type, parent_code, route_path, icon, sort_order, owner_space) VALUES
  ('menu:metadata:semantic', '语义本体', 'MENU', 'menu:metadata', '/metadata/semantic', 'Share', 24, 'METADATA')
ON CONFLICT (code) DO NOTHING;

INSERT INTO pf_permission (code, name, type, parent_code, sort_order, owner_space) VALUES
  ('metadata:semantic:read',    '查看语义本体与知识图谱', 'ACTION', 'menu:metadata:semantic', 1, 'METADATA'),
  ('metadata:semantic:manage',  '维护语义本体与映射关系', 'ACTION', 'menu:metadata:semantic', 2, 'METADATA'),
  ('metadata:semantic:publish', '发布与审校语义本体提案', 'ACTION', 'menu:metadata:semantic', 3, 'METADATA')
ON CONFLICT (code) DO NOTHING;

INSERT INTO pf_role_permission (role_id, permission_code) VALUES
  ('role_builtin_ws_admin',     'menu:metadata:semantic'),
  ('role_builtin_ws_admin',     'metadata:semantic:read'),
  ('role_builtin_ws_admin',     'metadata:semantic:manage'),
  ('role_builtin_ws_admin',     'metadata:semantic:publish'),

  ('role_builtin_ws_developer', 'menu:metadata:semantic'),
  ('role_builtin_ws_developer', 'metadata:semantic:read'),
  ('role_builtin_ws_developer', 'metadata:semantic:manage'),
  ('role_builtin_ws_developer', 'metadata:semantic:publish'),

  ('role_builtin_ws_viewer',    'menu:metadata:semantic'),
  ('role_builtin_ws_viewer',    'metadata:semantic:read')
ON CONFLICT (role_id, permission_code) DO NOTHING;
