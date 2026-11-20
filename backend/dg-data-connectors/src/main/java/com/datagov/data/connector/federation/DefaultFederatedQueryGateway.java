package com.datagov.data.connector.federation;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.data.spi.DataAccessGateway;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.FederatedQueryGateway;
import com.datagov.data.spi.catalog.CanonicalType;
import com.datagov.data.spi.query.ReadOnlySqlGuard;
import com.datagov.data.spi.query.SqlQuery;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 双引擎联邦查询实现（OpenSpec: multisource-semantic-federation）：
 * <ul>
 *   <li><b>DuckDB</b>：基于 {@code duckdb_jdbc} 的隔离内存联邦会话，支持任意跨源 JOIN / 聚合 / 窗口函数；</li>
 *   <li><b>StarRocks</b>：委托 {@link DataAccessGateway} 向 StarRocks 下推跨 External Catalog 只读 SQL。</li>
 * </ul>
 *
 * <p>两条路径均强制经 {@link ReadOnlySqlGuard#requireReadOnly(String)} 拦截写意图（CONSTITUTION C3）。
 */
@Component
public class DefaultFederatedQueryGateway implements FederatedQueryGateway {

    private static final Pattern SAFE_IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final DataAccessGateway dataAccessGateway;

    public DefaultFederatedQueryGateway(DataAccessGateway dataAccessGateway) {
        this.dataAccessGateway = dataAccessGateway;
    }

    @Override
    public List<FederationEngine> availableEngines() {
        List<FederationEngine> engines = new ArrayList<>();
        engines.add(FederationEngine.DUCKDB);
        if (dataAccessGateway != null
                && dataAccessGateway.availableTypes().contains(DataSourceType.STARROCKS)) {
            engines.add(FederationEngine.STARROCKS);
        }
        return engines;
    }

    @Override
    public FederatedResult executeDuckDbJoin(DuckDbFederatedRequest request) {
        if (request == null || request.tables() == null || request.tables().isEmpty()) {
            throw new BizException(ErrorCode.DAT_QUERY_FAILED, "DuckDB 联邦查询至少需要一张源表");
        }
        String safeSql = ReadOnlySqlGuard.requireReadOnly(request.federatedSql());
        long started = System.currentTimeMillis();

        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement cfg = conn.createStatement()) {
                cfg.execute("SET threads TO 2");
                cfg.execute("SET memory_limit = '512MB'");
            }

            // 将逐仓预过滤并脱敏后的结果集安全物化为隔离内存临时表
            for (FederatedTableInput table : request.tables()) {
                materializeTable(conn, table);
            }

            // 包装外层 LIMIT (maxRows + 1) 以精确检测截断
            String wrappedSql = "SELECT * FROM (" + safeSql + ") AS __dg_fed LIMIT " + (request.maxRows() + 1);
            List<SqlQuery.Column> outCols = new ArrayList<>();
            List<Map<String, Object>> outRows = new ArrayList<>();
            boolean truncated = false;

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(wrappedSql)) {
                ResultSetMetaData md = rs.getMetaData();
                int colCount = md.getColumnCount();
                for (int i = 1; i <= colCount; i++) {
                    String label = md.getColumnLabel(i);
                    String rawType = md.getColumnTypeName(i);
                    outCols.add(new SqlQuery.Column(label, rawType, mapCanonicalType(md.getColumnType(i), rawType)));
                }
                while (rs.next()) {
                    if (outRows.size() >= request.maxRows()) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        Object val = rs.getObject(i);
                        row.put(outCols.get(i - 1).name(), normalizeOutValue(val));
                    }
                    outRows.add(row);
                }
            }

            long elapsed = Math.max(1L, System.currentTimeMillis() - started);
            return new FederatedResult("DUCKDB", safeSql, outCols, outRows, outRows.size(), truncated, elapsed);
        } catch (BizException ex) {
            throw ex;
        } catch (SQLException ex) {
            throw new BizException(ErrorCode.DAT_QUERY_FAILED,
                    "DuckDB 联邦查询执行失败: " + ex.getMessage());
        }
    }

    @Override
    public FederatedResult executeStarRocksJoin(StarRocksFederatedRequest request) {
        if (request == null || request.starRocksConfig() == null) {
            throw new BizException(ErrorCode.DAT_QUERY_FAILED, "StarRocks 联邦查询缺少连接配置");
        }
        if (dataAccessGateway == null) {
            throw new BizException(ErrorCode.DAT_QUERY_FAILED, "DataAccessGateway 未装配，无法执行 StarRocks 联邦查询");
        }
        String safeSql = ReadOnlySqlGuard.requireReadOnly(request.federatedSql());
        SqlQuery.Result result = dataAccessGateway.executeQuery(
                DataSourceType.STARROCKS,
                request.starRocksConfig(),
                new SqlQuery.Request(safeSql, request.maxRows(), request.timeoutSeconds())
        );

        List<Map<String, Object>> rowMaps = new ArrayList<>();
        if (result.rows() != null && result.columns() != null) {
            for (List<String> values : result.rows()) {
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < result.columns().size(); i++) {
                    String name = result.columns().get(i).name();
                    String val = i < values.size() ? values.get(i) : null;
                    map.put(name, parseTypedValue(val, result.columns().get(i).canonicalType()));
                }
                rowMaps.add(map);
            }
        }
        return new FederatedResult(
                "STARROCKS",
                safeSql,
                result.columns() == null ? List.of() : result.columns(),
                rowMaps,
                rowMaps.size(),
                result.truncated(),
                result.elapsedMillis()
        );
    }

    // ── DuckDB Materialization Helpers ────────────────────────────────────

    private void materializeTable(Connection conn, FederatedTableInput table) throws SQLException {
        String alias = requireSafeIdent(table.alias(), "联邦表别名");
        List<SqlQuery.Column> cols = table.columns();
        if (cols == null || cols.isEmpty()) {
            throw new BizException(ErrorCode.DAT_QUERY_FAILED, "联邦源表 " + alias + " 缺少列定义");
        }
        List<String> colDefs = new ArrayList<>();
        List<String> colNames = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        List<String> duckTypes = new ArrayList<>();

        for (SqlQuery.Column col : cols) {
            String safeCol = requireSafeIdent(col.name(), "联邦列名");
            String duckType = toDuckDbType(col.canonicalType(), col.rawType());
            colNames.add("\"" + safeCol + "\"");
            colDefs.add("\"" + safeCol + "\" " + duckType);
            placeholders.add("?");
            duckTypes.add(duckType);
        }

        String ddl = "CREATE TEMP TABLE \"" + alias + "\" (" + String.join(", ", colDefs) + ")";
        try (Statement st = conn.createStatement()) {
            st.execute(ddl);
        }

        List<Map<String, Object>> rows = table.rows();
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String insertSql = "INSERT INTO \"" + alias + "\" (" + String.join(", ", colNames)
                + ") VALUES (" + String.join(", ", placeholders) + ")";
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (Map<String, Object> row : rows) {
                for (int i = 0; i < cols.size(); i++) {
                    String key = cols.get(i).name();
                    Object val = row == null ? null : row.get(key);
                    bindTypedParam(ps, i + 1, val, duckTypes.get(i));
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void bindTypedParam(PreparedStatement ps, int idx, Object val, String duckType) throws SQLException {
        if (val == null) {
            ps.setObject(idx, null);
            return;
        }
        String str = String.valueOf(val).trim();
        if ("DOUBLE".equals(duckType)) {
            if (val instanceof Number n) {
                ps.setDouble(idx, n.doubleValue());
            } else if (!str.isEmpty()) {
                try {
                    ps.setDouble(idx, Double.parseDouble(str));
                } catch (NumberFormatException ignored) {
                    ps.setObject(idx, null);
                }
            } else {
                ps.setObject(idx, null);
            }
        } else if ("BIGINT".equals(duckType)) {
            if (val instanceof Number n) {
                ps.setLong(idx, n.longValue());
            } else if (!str.isEmpty()) {
                try {
                    ps.setLong(idx, Long.parseLong(str));
                } catch (NumberFormatException ignored) {
                    ps.setObject(idx, null);
                }
            } else {
                ps.setObject(idx, null);
            }
        } else if ("BOOLEAN".equals(duckType)) {
            if (val instanceof Boolean b) {
                ps.setBoolean(idx, b);
            } else {
                ps.setBoolean(idx, Boolean.parseBoolean(str));
            }
        } else {
            ps.setString(idx, String.valueOf(val));
        }
    }

    private String toDuckDbType(CanonicalType canonicalType, String rawType) {
        if (canonicalType != null) {
            return switch (canonicalType) {
                case TINYINT, SMALLINT, INT, BIGINT -> "BIGINT";
                case DECIMAL, FLOAT, DOUBLE -> "DOUBLE";
                case BOOLEAN -> "BOOLEAN";
                default -> "VARCHAR";
            };
        }
        String upper = rawType == null ? "" : rawType.toUpperCase(Locale.ROOT);
        if (upper.contains("INT")) {
            return "BIGINT";
        }
        if (upper.contains("DEC") || upper.contains("NUM") || upper.contains("DOUBLE") || upper.contains("FLOAT")) {
            return "DOUBLE";
        }
        if (upper.contains("BOOL")) {
            return "BOOLEAN";
        }
        return "VARCHAR";
    }

    private CanonicalType mapCanonicalType(int jdbcType, String rawType) {
        return switch (jdbcType) {
            case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT -> CanonicalType.BIGINT;
            case Types.DECIMAL, Types.NUMERIC -> CanonicalType.DECIMAL;
            case Types.DOUBLE, Types.FLOAT, Types.REAL -> CanonicalType.DOUBLE;
            case Types.BOOLEAN, Types.BIT -> CanonicalType.BOOLEAN;
            case Types.DATE -> CanonicalType.DATE;
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> CanonicalType.TIMESTAMP;
            default -> {
                String upper = rawType == null ? "" : rawType.toUpperCase(Locale.ROOT);
                if (upper.contains("INT")) yield CanonicalType.BIGINT;
                if (upper.contains("DOUBLE") || upper.contains("FLOAT")) yield CanonicalType.DOUBLE;
                if (upper.contains("DEC") || upper.contains("HUGEINT")) yield CanonicalType.DECIMAL;
                yield CanonicalType.VARCHAR;
            }
        };
    }

    private Object normalizeOutValue(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof BigDecimal bd) {
            return bd.doubleValue();
        }
        if (val instanceof Number || val instanceof Boolean || val instanceof String) {
            return val;
        }
        return String.valueOf(val);
    }

    private Object parseTypedValue(String val, CanonicalType type) {
        if (val == null) {
            return null;
        }
        if (type == CanonicalType.BIGINT || type == CanonicalType.INT
                || type == CanonicalType.SMALLINT || type == CanonicalType.TINYINT) {
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException ignored) {
                return val;
            }
        }
        if (type == CanonicalType.DECIMAL || type == CanonicalType.FLOAT || type == CanonicalType.DOUBLE) {
            try {
                return Double.parseDouble(val);
            } catch (NumberFormatException ignored) {
                return val;
            }
        }
        return val;
    }

    private String requireSafeIdent(String raw, String label) {
        if (raw == null || !SAFE_IDENT.matcher(raw.trim()).matches()) {
            throw new BizException(ErrorCode.DAT_SQL_NOT_ALLOWED,
                    label + " 包含非法标识符: " + raw);
        }
        return raw.trim();
    }
}
