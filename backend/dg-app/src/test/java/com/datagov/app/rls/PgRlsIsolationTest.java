package com.datagov.app.rls;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase C (c3-pg-rls-defense-in-depth) 真库集成证明测试。
 *
 * <p>启动真实 EmbeddedPostgres 并执行全部 Flyway V1–V35 迁移，以非超管角色
 * {@code dg_rls_tester} 逐表证明：即使绕过应用层 WHERE workspace_id 条件，
 * PostgreSQL 内核 RLS 依然 100% 拦截跨租户读写与未设上下文的默认读取。
 */
class PgRlsIsolationTest {

    private static EmbeddedPostgres pg;
    private static String jdbcUrl;

    private static final List<String> PILOT_TABLES = List.of(
            "md_datasource",
            "md_catalog_dataset",
            "md_column_sensitivity",
            "gv_audit_record",
            "gv_analysis_report",
            "ig_query_log",
            "ig_agent_action_proposal",
            "gv_governed_fact",
            "md_semantic_object",
            "md_semantic_mapping",
            "md_semantic_link",
            "md_semantic_object_version",
            "md_semantic_value_dict",
            "md_workspace_warehouse",
            "md_logical_table",
            "md_dataset_profile_run",
            "md_anchor_event_spec",
            "md_anchor_alignment_run",
            "md_anchor_alignment_hit",
            "gv_clinical_qc_finding",
            "gv_mapping_proposal",
            // data-driven-ops-profile（V50/V51）
            "au_filter_preset",
            "au_trigger_def",
            "au_flow_def",
            "au_flow_version",
            "au_flow_instance",
            "au_entity_lock_cooldown",
            "au_decision_trace",
            "au_action_execution_counter"
    );

    @BeforeAll
    static void startPostgresAndMigrate() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        DataSource ds = pg.getPostgresDatabase();
        jdbcUrl = pg.getJdbcUrl("dg_rls_tester", "postgres");

        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            // 创建非超管运行角色，模拟生产 dg_app 受控数据库账号
            st.execute("CREATE ROLE dg_rls_tester LOGIN PASSWORD 'rls_pass' NOSUPERUSER NOBYPASSRLS");
            st.execute("GRANT USAGE ON SCHEMA public TO dg_rls_tester");
            for (String tbl : PILOT_TABLES) {
                st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE " + tbl + " TO dg_rls_tester");
            }

            // 向两个租户（ws_alpha / ws_beta）分别写入最小测试行
            seedWorkspaceRows(st, "ws_alpha", "a");
            seedWorkspaceRows(st, "ws_beta", "b");
        }
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    private static void seedWorkspaceRows(Statement st, String ws, String tag) throws SQLException {
        st.execute("""
                INSERT INTO md_datasource (id, workspace_id, name, type, family, status)
                VALUES ('ds_%s', '%s', 'ds-%s', 'LOCAL', 'FILE', 'AVAILABLE')
                """.formatted(tag, ws, tag));

        st.execute("""
                INSERT INTO md_catalog_source (id, workspace_id, name, source_type, config_json, status)
                VALUES ('cs_%s', '%s', 'cs-%s', 'PARQUET_DIRECTORY', '{}', 'ACTIVE')
                """.formatted(tag, ws, tag));
        st.execute("""
                INSERT INTO md_catalog_namespace (id, source_id, name, kind)
                VALUES ('ns_%s', 'cs_%s', 'ns-%s', 'DIRECTORY')
                """.formatted(tag, tag, tag));
        st.execute("""
                INSERT INTO md_catalog_dataset (id, workspace_id, source_id, namespace_id, name, qualified_name, kind, format)
                VALUES ('cd_%s', '%s', 'cs_%s', 'ns_%s', 'tbl_%s', 'ns_%s.tbl_%s', 'TABLE', 'PARQUET')
                """.formatted(tag, ws, tag, tag, tag, tag, tag));
        st.execute("""
                INSERT INTO md_catalog_column (id, dataset_id, name, ordinal, raw_type, canonical_type)
                VALUES ('col_%s', 'cd_%s', 'phone', 1, 'varchar', 'STRING')
                """.formatted(tag, tag));
        st.execute("""
                INSERT INTO md_column_sensitivity (id, workspace_id, dataset_id, column_id, category, level, confidence, detected_by)
                VALUES ('sens_%s', '%s', 'cd_%s', 'col_%s', 'PII', 'L3', 0.99, 'RULE')
                """.formatted(tag, ws, tag, tag));

        st.execute("""
                INSERT INTO gv_audit_record (id, workspace_id, action, resource_type, request_summary)
                VALUES ('aud_%s', '%s', 'CREATE', 'DATASOURCE', 'seed-%s')
                """.formatted(tag, ws, tag));

        st.execute("""
                INSERT INTO gv_analysis_report (id, workspace_id, subject_type, title, correlation_id, created_by)
                VALUES ('rep_%s', '%s', 'NONE', 'report-%s', 'corr_%s', 'usr_%s')
                """.formatted(tag, ws, tag, tag, tag));

        st.execute("""
                INSERT INTO ig_query_log (id, workspace_id, question, succeeded)
                VALUES ('ql_%s', '%s', 'question-%s', true)
                """.formatted(tag, ws, tag));

        st.execute("""
                INSERT INTO ig_agent_action_proposal (id, workspace_id, user_id, username, action_type, required_perm, title, summary, payload_json, payload_digest, expires_at)
                VALUES ('agpr_%s', '%s', 'usr_%s', 'u_%s', 'CREATE_LOCAL_DATASOURCE', 'metadata:datasource:create', 't', 's', '{}', 'd', now() + interval '15 minute')
                """.formatted(tag, ws, tag, tag));

        st.execute("""
                INSERT INTO gv_governed_fact (id, workspace_id, asset_ref, fact_type, title, content_json, source, feedback_source, evidence_ref, created_by)
                VALUES ('gvf_%s', '%s', 'table:orders', 'case', 'fact-%s', '{}', 'attribution', 'SQL_VERIFIED', 'ref:%s', 'usr_%s')
                """.formatted(tag, ws, tag, tag, tag));

        st.execute("""
                INSERT INTO md_semantic_object (id, workspace_id, code, name, object_type, standard_ref, status)
                VALUES ('sem_%s', '%s', 'metric.order.gmv_%s', 'GMV-%s', 'METRIC', 'STD-1', 'PUBLISHED')
                """.formatted(tag, ws, tag, tag));
        st.execute("""
                INSERT INTO md_semantic_object (id, workspace_id, code, name, object_type, status)
                VALUES ('sem2_%s', '%s', 'attr.org.region_%s', 'Region-%s', 'ATTRIBUTE', 'PUBLISHED')
                """.formatted(tag, ws, tag, tag));
        st.execute("""
                INSERT INTO md_semantic_mapping (id, workspace_id, object_id, mapping_role, catalog_dataset_id, catalog_column_id)
                VALUES ('semm_%s', '%s', 'sem_%s', 'MEASURE', 'cd_%s', 'col_%s')
                """.formatted(tag, ws, tag, tag, tag));
        st.execute("""
                INSERT INTO md_semantic_link (id, workspace_id, from_object_id, to_object_id, edge_category, link_type, status)
                VALUES ('seml_%s', '%s', 'sem_%s', 'sem2_%s', 'STRUCTURAL', 'HAS_ATTRIBUTE', 'PUBLISHED')
                """.formatted(tag, ws, tag, tag));
        st.execute("""
                INSERT INTO md_semantic_object_version (id, workspace_id, object_id, version_no, snapshot_json, content_digest)
                VALUES ('semv_%s', '%s', 'sem_%s', 1, '{}', 'digest_%s')
                """.formatted(tag, ws, tag, tag));
        st.execute("""
                INSERT INTO md_semantic_value_dict (id, workspace_id, object_id, raw_value, display_label)
                VALUES ('semval_%s', '%s', 'sem2_%s', 'EAST', '华东')
                """.formatted(tag, ws, tag));
        st.execute("""
                INSERT INTO md_workspace_warehouse (id, workspace_id, datasource_id, code, name, warehouse_role, cohort_code, dialect)
                VALUES ('wh_%s', '%s', 'ds_%s', 'wh_%s', 'WH-%s', 'PRIMARY', 'COHORT_%s', 'POSTGRESQL')
                """.formatted(tag, ws, tag, tag, tag, tag));
        st.execute("""
                INSERT INTO md_logical_table (id, workspace_id, warehouse_id, catalog_dataset_id, logical_name, table_role, logical_size)
                VALUES ('lt_%s', '%s', 'wh_%s', 'cd_%s', 'lt_%s', 'FACT', 100)
                """.formatted(tag, ws, tag, tag, tag));
        st.execute("""
                INSERT INTO md_dataset_profile_run (id, workspace_id, dataset_id, dataset_name, verdict, total_rows, sampled_rows, truncated, anomaly_count, pattern_fingerprint, profile_json, anomalies_json, created_by)
                VALUES ('dpr_%s', '%s', 'cd_%s', 'ds_%s', 'OK', 100, 100, false, 0, 'pat_%s', '{}', '[]', 'usr_%s')
                """.formatted(tag, ws, tag, tag, tag, tag));
        st.execute("""
                INSERT INTO md_anchor_event_spec (id, workspace_id, code, name, anchor_type, binding_json, window_config_json, standard_ref, status, created_by)
                VALUES ('anch_%s', '%s', 't0_%s', 'T0-%s', 'INTERVENTIONAL_TACE', '{"anchorTsUrn":"semantic://x"}', '{}', 'STD_CHANCE_HCC_QC_V1', 'ACTIVE', 'usr_%s')
                """.formatted(tag, ws, tag, tag, tag));
        st.execute("""
                INSERT INTO md_anchor_alignment_run (id, workspace_id, operator_id, operator_version, status, anchor_count, hit_count, ambiguous_count, missing_count, created_by)
                VALUES ('aar_%s', '%s', 'align_to_anchor_v1', '1.0.0', 'COMPLETED', 1, 1, 0, 0, 'usr_%s')
                """.formatted(tag, ws, tag));
        st.execute("""
                INSERT INTO md_anchor_alignment_hit (id, workspace_id, run_id, patient_ref, anchor_id, session_seq, role, status)
                VALUES ('aah_%s', '%s', 'aar_%s', 'synthea:p_%s', 'a_%s', 1, 'FOLLOWUP', 'MATCHED')
                """.formatted(tag, ws, tag, tag, tag));
        st.execute("""
                INSERT INTO gv_clinical_qc_finding (id, workspace_id, ruleset_ref, rule_id, severity, message, evidence_json, status, created_by)
                VALUES ('qcf_%s', '%s', 'STD_CHANCE_HCC_QC_V1', 'CNLC_CONFLICT', 'Blocker', 'demo', '{}', 'OPEN', 'usr_%s')
                """.formatted(tag, ws, tag));

        // ── data-driven-ops-profile（V50）──────────────────────────────
        st.execute("""
                INSERT INTO au_filter_preset (id, workspace_id, code, name, preset_type)
                VALUES ('afp_%s', '%s', 'preset_%s', 'preset-%s', 'SMQ_FILTER')
                """.formatted(tag, ws, tag, tag));
        st.execute("""
                INSERT INTO au_flow_def (id, workspace_id, code, name, status, current_version, run_mode)
                VALUES ('afl_%s', '%s', 'flow_%s', 'flow-%s', 'PUBLISHED', 1, 'SHADOW')
                """.formatted(tag, ws, tag, tag));
        st.execute("""
                INSERT INTO au_flow_version (id, workspace_id, flow_id, version_no, spec_json, compiled_json, content_digest, run_mode)
                VALUES ('afv_%s', '%s', 'afl_%s', 1, '{}', '{}', 'digest_%s', 'SHADOW')
                """.formatted(tag, ws, tag, tag));
        st.execute("""
                INSERT INTO au_trigger_def (id, workspace_id, code, name, source_type, condition_json, debounce_sec, flow_id, enabled)
                VALUES ('atr_%s', '%s', 'trigger_%s', 'trigger-%s', 'ETL_ANOMALY', '{}', 0, 'afl_%s', true)
                """.formatted(tag, ws, tag, tag, tag));
        st.execute("""
                INSERT INTO au_flow_instance (id, workspace_id, flow_id, flow_code, flow_version, status, current_step_id, entity_key, run_mode, plan_json)
                VALUES ('afi_%s', '%s', 'afl_%s', 'flow_%s', 1, 'RUNNING', 's1', 'entity_%s', 'SHADOW', '{}')
                """.formatted(tag, ws, tag, tag, tag));
        st.execute("""
                INSERT INTO au_entity_lock_cooldown (id, workspace_id, entity_key)
                VALUES ('aulk_%s', '%s', 'entity_%s')
                """.formatted(tag, ws, tag));
        st.execute("""
                INSERT INTO au_decision_trace (id, workspace_id, trace_id, run_mode, verify_status)
                VALUES ('atrc_%s', '%s', 'trace_%s', 'SHADOW', 'PENDING')
                """.formatted(tag, ws, tag));
        st.execute("""
                INSERT INTO au_action_execution_counter
                    (id, workspace_id, action_code, counter_day, execution_count)
                VALUES ('aec_%s', '%s', 'GENERATE_REPORT', CURRENT_DATE, 1)
                """.formatted(tag, ws));

        st.execute("""
                INSERT INTO gv_mapping_proposal (id, workspace_id, source_table, source_field, confidence, transformation_type, evidence_json, routing_tier, suggestion_fingerprint, state, created_by)
                VALUES ('mpp_%s', '%s', 'cath_int', 'treat_dt', 0.5, 'IDENTITY', '{}', 'standard_hitl_review', 'fp_%s', 'PROPOSED', 'usr_%s')
                """.formatted(tag, ws, tag, tag));
    }

    @Test
    @DisplayName("C5 / V35：8 张核心表全部启用 RLS，未设租户默认返回 0 行，设租户后绝不跨空间泄露")
    void allPilotTablesEnforceRlsIsolation() throws Exception {
        try (Connection rlsConn = DriverManager.getConnection(jdbcUrl, "dg_rls_tester", "rls_pass")) {
            try (Statement st = rlsConn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT current_user")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("dg_rls_tester");
            }
            for (String table : PILOT_TABLES) {
                // 1. 未设置 app.workspace_id -> 默认拒绝（0 行）
                setConfig(rlsConn, "", "false");
                assertThat(countRows(rlsConn, table))
                        .as("未设 workspace 时表 %s 必须返回 0 行", table)
                        .isEqualTo(0);

                // 2. 设置 ws_alpha -> 只看到 ws_alpha 的行，看不到 ws_beta
                setConfig(rlsConn, "ws_alpha", "false");
                assertThat(countRows(rlsConn, table))
                        .as("ws_alpha 下表 %s 只能看到本空间数据", table)
                        .isGreaterThanOrEqualTo(1);
                assertThat(distinctWorkspaces(rlsConn, table)).containsExactly("ws_alpha");

                // 3. 设置 ws_beta -> 只看到 ws_beta 的行
                setConfig(rlsConn, "ws_beta", "false");
                assertThat(countRows(rlsConn, table))
                        .as("ws_beta 下表 %s 只能看到本空间数据", table)
                        .isGreaterThanOrEqualTo(1);
                assertThat(distinctWorkspaces(rlsConn, table)).containsExactly("ws_beta");
            }
        }
    }

    @Test
    @DisplayName("C5 / V35：在 ws_alpha 上下文下向 ws_beta 跨空间 INSERT 被 PostgreSQL RLS 拒绝（42501）")
    void crossWorkspaceInsertRejectedByRls() throws Exception {
        try (Connection rlsConn = DriverManager.getConnection(jdbcUrl, "dg_rls_tester", "rls_pass")) {
            setConfig(rlsConn, "ws_alpha", "false");
            assertThatThrownBy(() -> {
                try (Statement st = rlsConn.createStatement()) {
                    st.execute("""
                            INSERT INTO gv_governed_fact
                              (id, workspace_id, asset_ref, fact_type, title, content_json, source, feedback_source, evidence_ref, created_by)
                            VALUES
                              ('gvf_evil', 'ws_beta', 'table:x', 'case', 'evil', '{}', 'attribution', 'SQL_VERIFIED', 'ref:x', 'usr_a')
                            """);
                }
            })
                    .isInstanceOf(SQLException.class)
                    .satisfies(ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    @DisplayName("C5 / V38：GLOBAL 已发布权威本体对合法 Workspace 只读共享，但禁止未设上下文读取及租户篡改")
    void globalStandardOntologyReadableByWorkspacesButImmutableByTenants() throws Exception {
        DataSource ds = pg.getPostgresDatabase();
        try (Connection adminConn = ds.getConnection(); Statement st = adminConn.createStatement()) {
            st.execute("""
                    INSERT INTO md_semantic_object (id, workspace_id, scope, code, name, object_type, status)
                    VALUES ('sem_global_patient', '__GLOBAL__', 'GLOBAL', 'entity.patient', '标准患者实体', 'ENTITY', 'PUBLISHED')
                    ON CONFLICT (id) DO NOTHING
                    """);
        }
        try (Connection rlsConn = DriverManager.getConnection(jdbcUrl, "dg_rls_tester", "rls_pass")) {
            // 未设置 workspace_id 时，GLOBAL 也不泄露（0 行）
            setConfig(rlsConn, "", "false");
            assertThat(countRows(rlsConn, "md_semantic_object")).isEqualTo(0);

            // ws_alpha 可读到 GLOBAL 标准实体，但看不到 ws_beta
            setConfig(rlsConn, "ws_alpha", "false");
            assertThat(distinctWorkspaces(rlsConn, "md_semantic_object"))
                    .containsExactlyInAnyOrder("ws_alpha", "__GLOBAL__");

            // ws_alpha 试图篡改 GLOBAL 行被静默隔离（0 行受影响），无法修改
            try (Statement st = rlsConn.createStatement()) {
                int updated = st.executeUpdate("UPDATE md_semantic_object SET name = 'hacked' WHERE id = 'sem_global_patient'");
                assertThat(updated).isEqualTo(0);
            }
        }
        // 清理全局种子，避免影响其它测试方法顺序
        try (Connection adminConn = ds.getConnection(); Statement st = adminConn.createStatement()) {
            st.execute("DELETE FROM md_semantic_object WHERE id = 'sem_global_patient'");
        }
    }

    private static void setConfig(Connection conn, String workspaceId, String bypass) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT set_config('app.workspace_id', ?, false), set_config('app.rls_bypass', ?, false)")) {
            ps.setString(1, workspaceId);
            ps.setString(2, bypass);
            ps.execute();
        }
    }

    private static int countRows(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static List<String> distinctWorkspaces(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT workspace_id FROM " + table)) {
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        }
    }
}
