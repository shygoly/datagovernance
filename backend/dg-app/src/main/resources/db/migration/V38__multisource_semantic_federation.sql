-- OpenSpec Change: multisource-semantic-federation
-- 借鉴 MicroStrategy MultiSource Option / Warehouse Catalog / Logical Table / Heterogeneous Mapping，
-- 不新增 Project 层级，直接以 Workspace 为项目/租户边界：
-- 1. md_semantic_object 支持 scope = GLOBAL（平台级权威标准本体，全 Workspace 只读共享）| WORKSPACE；
-- 2. md_workspace_warehouse：一个 Workspace 挂载 1..N 个 DataSource（PRIMARY/SECONDARY、cohort_code、命名空间、联邦引擎）；
-- 3. md_logical_table：Workspace 内逻辑表目录（Logical Size 选表权重 + Alias 解决 Attribute Role）；
-- 4. md_semantic_mapping 增强：绑定 warehouse_id / logical_table_id / value_normalization_json / 源与目标单位。

-- ── 1. 语义本体增加 scope（GLOBAL | WORKSPACE）与细粒度 PG RLS ──────────
ALTER TABLE md_semantic_object
    ADD COLUMN IF NOT EXISTS scope VARCHAR(16) NOT NULL DEFAULT 'WORKSPACE';

CREATE INDEX IF NOT EXISTS idx_md_semantic_object_scope_status
    ON md_semantic_object (scope, status);

-- 替换 V36 单一 FOR ALL 策略为读写分离策略：
-- - 未设 app.workspace_id 时仍严格返回 0 行；
-- - 合法 Workspace 会话可读本空间行 + 已发布的 GLOBAL 权威标准本体；
-- - 任何写入/修改/删除仍严格限定本 workspace_id（禁止租户篡改 GLOBAL 或跨租户行）。
DROP POLICY IF EXISTS p_workspace_isolation ON md_semantic_object;
DROP POLICY IF EXISTS p_semantic_object_select ON md_semantic_object;
DROP POLICY IF EXISTS p_semantic_object_insert ON md_semantic_object;
DROP POLICY IF EXISTS p_semantic_object_update ON md_semantic_object;
DROP POLICY IF EXISTS p_semantic_object_delete ON md_semantic_object;

CREATE POLICY p_semantic_object_select ON md_semantic_object
    FOR SELECT
    USING (
        COALESCE(current_setting('app.rls_bypass', true), 'false') = 'true'
        OR workspace_id = NULLIF(current_setting('app.workspace_id', true), '')
        OR (
            NULLIF(current_setting('app.workspace_id', true), '') IS NOT NULL
            AND scope = 'GLOBAL'
            AND status = 'PUBLISHED'
        )
    );

CREATE POLICY p_semantic_object_insert ON md_semantic_object
    FOR INSERT
    WITH CHECK (
        COALESCE(current_setting('app.rls_bypass', true), 'false') = 'true'
        OR workspace_id = NULLIF(current_setting('app.workspace_id', true), '')
    );

CREATE POLICY p_semantic_object_update ON md_semantic_object
    FOR UPDATE
    USING (
        COALESCE(current_setting('app.rls_bypass', true), 'false') = 'true'
        OR workspace_id = NULLIF(current_setting('app.workspace_id', true), '')
    )
    WITH CHECK (
        COALESCE(current_setting('app.rls_bypass', true), 'false') = 'true'
        OR workspace_id = NULLIF(current_setting('app.workspace_id', true), '')
    );

CREATE POLICY p_semantic_object_delete ON md_semantic_object
    FOR DELETE
    USING (
        COALESCE(current_setting('app.rls_bypass', true), 'false') = 'true'
        OR workspace_id = NULLIF(current_setting('app.workspace_id', true), '')
    );

-- ── 2. Workspace 多仓库挂载表（MicroStrategy Project DBInstances）─────────
CREATE TABLE IF NOT EXISTS md_workspace_warehouse (
    id                      VARCHAR(64)  PRIMARY KEY,
    workspace_id            VARCHAR(64)  NOT NULL,
    datasource_id           VARCHAR(64)  NOT NULL,
    code                    VARCHAR(64)  NOT NULL,
    name                    VARCHAR(160) NOT NULL,
    -- PRIMARY / SECONDARY
    warehouse_role          VARCHAR(16)  NOT NULL DEFAULT 'SECONDARY',
    -- 队列/分区代码，例如 MIMIC_IV / EICU_CRD / HIS / LIS
    cohort_code             VARCHAR(64),
    -- POSTGRESQL / STARROCKS / MYSQL / CLICKHOUSE / DUCKDB
    dialect                 VARCHAR(32)  NOT NULL DEFAULT 'POSTGRESQL',
    -- 互斥队列主键命名空间前缀，如 'mimic:' / 'eicu:'（防 COUNT(DISTINCT) 跨库碰撞）
    id_namespace_prefix     VARCHAR(64),
    -- SINGLE_ROUTE / COHORT_UNION / ENTITY_FEDERATED_JOIN
    combine_mode            VARCHAR(32)  NOT NULL DEFAULT 'SINGLE_ROUTE',
    -- AUTO / STARROCKS / DUCKDB
    federation_engine       VARCHAR(24)  NOT NULL DEFAULT 'AUTO',
    -- 若通过 StarRocks External Catalog 联邦，填写注册的 catalog 名称
    starrocks_catalog_name  VARCHAR(128),
    priority                INT          NOT NULL DEFAULT 100,
    status                  VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by              VARCHAR(64),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by              VARCHAR(64)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_md_workspace_warehouse_code
    ON md_workspace_warehouse (workspace_id, lower(code));
CREATE INDEX IF NOT EXISTS idx_md_workspace_warehouse_ws
    ON md_workspace_warehouse (workspace_id, status, priority);
CREATE INDEX IF NOT EXISTS idx_md_workspace_warehouse_ds
    ON md_workspace_warehouse (workspace_id, datasource_id);

-- ── 3. 逻辑表与别名目录（MicroStrategy Warehouse Catalog / Logical Table）─
CREATE TABLE IF NOT EXISTS md_logical_table (
    id                  VARCHAR(64)  PRIMARY KEY,
    workspace_id        VARCHAR(64)  NOT NULL,
    warehouse_id        VARCHAR(64)  NOT NULL REFERENCES md_workspace_warehouse(id) ON DELETE CASCADE,
    catalog_dataset_id  VARCHAR(64)  NOT NULL,
    logical_name        VARCHAR(160) NOT NULL,
    -- LOOKUP / FACT / BRIDGE / VIEW_SQL
    table_role          VARCHAR(24)  NOT NULL DEFAULT 'FACT',
    -- 非空表示 Logical Table Alias（解决 Attribute Role，如入院日期/出院日期）
    alias_of_table_id   VARCHAR(64)  REFERENCES md_logical_table(id) ON DELETE SET NULL,
    -- Logical Size：越小越优先被编译器选中（汇总表可设为小于明细事实表）
    logical_size        INT          NOT NULL DEFAULT 1000,
    custom_view_sql     TEXT,
    status              VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by          VARCHAR(64)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_md_logical_table_name
    ON md_logical_table (workspace_id, lower(logical_name));
CREATE INDEX IF NOT EXISTS idx_md_logical_table_wh
    ON md_logical_table (workspace_id, warehouse_id, status);
CREATE INDEX IF NOT EXISTS idx_md_logical_table_ds
    ON md_logical_table (workspace_id, catalog_dataset_id);

-- ── 4. 异构物理映射增强（支持多仓/逻辑表/值归一化/单位换算）─────────────
ALTER TABLE md_semantic_mapping
    ADD COLUMN IF NOT EXISTS warehouse_id             VARCHAR(64),
    ADD COLUMN IF NOT EXISTS logical_table_id         VARCHAR(64),
    ADD COLUMN IF NOT EXISTS value_normalization_json TEXT,
    ADD COLUMN IF NOT EXISTS source_unit              VARCHAR(64),
    ADD COLUMN IF NOT EXISTS target_unit              VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_md_semantic_mapping_wh
    ON md_semantic_mapping (workspace_id, warehouse_id);
CREATE INDEX IF NOT EXISTS idx_md_semantic_mapping_lt
    ON md_semantic_mapping (workspace_id, logical_table_id);

-- ── 5. 新增表启用 PostgreSQL RLS 纵深隔离 ────────────────────────────────
DO $$
DECLARE
    tbl TEXT;
    new_tables TEXT[] := ARRAY[
        'md_workspace_warehouse',
        'md_logical_table'
    ];
BEGIN
    FOREACH tbl IN ARRAY new_tables LOOP
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
