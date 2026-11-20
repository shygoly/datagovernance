package com.datagov.app.config;

import com.datagov.common.tenant.Caller;
import com.datagov.common.tenant.WorkspaceContext;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * PostgreSQL RLS 会话变量注入拦截器（Phase C / c3）。
 *
 * <p>在每次 MyBatis SQL 准备前，将当前线程 {@link WorkspaceContext} 的 {@code workspaceId}
 * 通过 {@code set_config('app.workspace_id', ?, false)} 绑定到 PostgreSQL 会话，配合
 * {@code V35__pg_rls_pilot.sql} 的行级安全策略形成数据库内核级多租户兜底防线。
 * 非 PostgreSQL 数据库（如信创 DM8/MySQL 测试）自动跳过，不影响兼容性。
 */
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare",
                args = {Connection.class, Integer.class})
})
public class PgRlsMybatisInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(PgRlsMybatisInterceptor.class);

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        if (args.length > 0 && args[0] instanceof Connection connection) {
            bindRlsContext(connection);
        }
        return invocation.proceed();
    }

    private void bindRlsContext(Connection connection) {
        try {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase().contains("postgresql")) {
                return;
            }
            Caller caller = WorkspaceContext.get();
            String workspaceId = (caller != null && caller.workspaceId() != null)
                    ? caller.workspaceId() : "";
            boolean bypass = caller == null;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT set_config('app.workspace_id', ?, false), set_config('app.rls_bypass', ?, false)")) {
                ps.setString(1, workspaceId);
                ps.setString(2, bypass ? "true" : "false");
                ps.execute();
            }
        } catch (SQLException ex) {
            log.debug("绑定 PostgreSQL RLS 会话上下文跳过: {}", ex.getMessage());
        }
    }
}
