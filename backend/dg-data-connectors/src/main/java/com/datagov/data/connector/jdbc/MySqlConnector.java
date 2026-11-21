package com.datagov.data.connector.jdbc;

import com.datagov.data.connector.mapping.TypeMapper;
import com.datagov.data.connector.mapping.TypeMappers;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectorCapabilities;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.catalog.CatalogModel.TableKind;
import com.datagov.data.spi.catalog.CatalogPath;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.Set;

/**
 * MySQL 连接器(功能 1)。
 *
 * <p>MySQL 的 database 与 schema 是同一个东西,所以目录树只有两级(库→表)。
 * {@link com.datagov.data.spi.ConnectorCapabilities#hasSchemaLevel()} 声明为 false,
 * 前端据此少画一层 —— 而不是靠前端写 {@code if (type === 'MYSQL')}。
 */
@Component
public class MySqlConnector extends AbstractJdbcConnector {

    @Override
    public Set<DataSourceType> supportedTypes() {
        return Set.of(DataSourceType.MYSQL);
    }

    @Override
    public ConnectorCapabilities capabilities(DataSourceType type) {
        return ConnectorCapabilities.relationalWithoutSchema();
    }

    @Override
    protected String buildJdbcUrl(DataSourceType type, ConnectionConfig config) {
        String database = config.database() == null ? "" : config.database();
        return "jdbc:mysql://%s:%d/%s".formatted(
                config.host(), portOrDefault(type, config), database);
    }

    @Override
    protected TypeMapper typeMapper(DataSourceType type) {
        return TypeMappers.forType(type);
    }

    @Override
    protected void applyTimeouts(Properties props, ConnectionConfig config) {
        // MySQL 驱动的这两个参数单位都是毫秒
        props.setProperty("connectTimeout", String.valueOf(config.connectTimeoutMillis()));
        props.setProperty("socketTimeout", String.valueOf(config.readTimeoutMillis()));
        // 探测只读结构,不需要驱动去猜时区;显式关掉可避免 serverTimezone 相关的启动失败
        props.putIfAbsent("useSSL", "false");
        props.putIfAbsent("allowPublicKeyRetrieval", "true");
    }

    /**
     * 排除系统库。用户来这里是找自己的业务库的,把 mysql / sys 列出来只会碍事。
     */
    @Override
    protected String listDatabasesSql() {
        return """
               SELECT SCHEMA_NAME FROM information_schema.SCHEMATA
               WHERE SCHEMA_NAME NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys')
               ORDER BY SCHEMA_NAME
               """;
    }

    /** MySQL 用 catalog 表达库,schema 恒为 null。 */
    @Override
    protected String metadataSchema(ConnectionConfig config, CatalogPath path) {
        return null;
    }

    /** MySQL 的视图定义在 information_schema.VIEWS；库名即 catalog。物化视图走 SHOW CREATE。 */
    @Override
    protected String readViewDefinition(Connection connection, String catalog, String schema,
                                        String viewName, TableKind kind) throws SQLException {
        String sql = "SELECT VIEW_DEFINITION FROM information_schema.VIEWS "
                + "WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) AND TABLE_NAME = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, catalog);
            ps.setString(2, viewName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String definition = rs.getString(1);
                    if (definition != null && !definition.isBlank()) {
                        return definition;
                    }
                }
            }
        }
        if (kind == TableKind.MATERIALIZED_VIEW) {
            return showCreateMaterializedView(connection, catalog, viewName);
        }
        return null;
    }

    /**
     * MySQL 8.4+ / 兼容物化视图的方言：information_schema 无条目时尝试 SHOW CREATE MATERIALIZED VIEW。
     */
    private static String showCreateMaterializedView(Connection connection, String catalog,
                                                     String viewName) throws SQLException {
        String safeView = quoteMySqlIdentifier(viewName);
        String stmt = catalog == null || catalog.isBlank()
                ? "SHOW CREATE MATERIALIZED VIEW " + safeView
                : "SHOW CREATE MATERIALIZED VIEW " + safeView + " FROM " + quoteMySqlIdentifier(catalog);
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(stmt)) {
            if (!rs.next()) {
                return null;
            }
            int columns = rs.getMetaData().getColumnCount();
            for (int i = 1; i <= columns; i++) {
                String value = rs.getString(i);
                if (value != null && value.toUpperCase().contains("CREATE")) {
                    return value;
                }
            }
            return columns >= 2 ? rs.getString(2) : rs.getString(1);
        } catch (SQLException ex) {
            if (ex.getErrorCode() == 1146 || ex.getErrorCode() == 1064) {
                return null;
            }
            throw ex;
        }
    }

    private static String quoteMySqlIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
