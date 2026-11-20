-- Phase C (c3-pg-rls-defense-in-depth):
-- 在 8 张核心治理/元数据/智能体表启用 PostgreSQL 行级安全（RLS）纵深兜底。
--
-- 工作机制：
-- 1. 应用连接由 PgRlsMybatisInterceptor 注入会话参数 app.workspace_id；
-- 2. 未设置 app.workspace_id 且未显式 app.rls_bypass='true' 时默认拒绝所有行（Deny-by-Default）；
-- 3. 平台级非超管运行角色（如 datagov / dg_app_rls_role）即使绕过应用层 WHERE 条件也无法跨空间读写。

DO $$
DECLARE
    tbl TEXT;
    pilot_tables TEXT[] := ARRAY[
        'md_datasource',
        'md_catalog_dataset',
        'md_column_sensitivity',
        'gv_audit_record',
        'gv_analysis_report',
        'ig_query_log',
        'ig_agent_action_proposal',
        'gv_governed_fact'
    ];
BEGIN
    FOREACH tbl IN ARRAY pilot_tables LOOP
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
