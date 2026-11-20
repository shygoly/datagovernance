package com.datagov.data.spi;

import com.datagov.data.spi.query.SqlQuery;

import java.util.List;
import java.util.Map;

/**
 * 跨仓库联邦查询网关契约（OpenSpec: multisource-semantic-federation · Data Space SPI）。
 *
 * <p>零驱动依赖，严守 CONSTITUTION C1（Metadata / Governance 不可见 JDBC 驱动）与 C3/C5：
 * <ul>
 *   <li><b>StarRocks External Catalog</b>：适用于生产大规模跨库联邦，由 StarRocks 分布式执行三段式 Catalog SQL；</li>
 *   <li><b>DuckDB 嵌入式联邦引擎</b>：零运维跨源任意 Join，接收经 {@link DataAccessGateway} 逐仓预过滤并完成
 *       L3/L4 脱敏后的物化结果集，在隔离内存 DuckDB 会话内执行只读 SQL。</li>
 * </ul>
 */
public interface FederatedQueryGateway {

    enum FederationEngine {
        AUTO,
        STARROCKS,
        DUCKDB
    }

    /**
     * 装载入嵌入式 DuckDB 联邦会话的单张物化源表（已逐仓完成前置鉴权与 C5 L3/L4 脱敏）。
     */
    record FederatedTableInput(
            String alias,
            List<SqlQuery.Column> columns,
            List<Map<String, Object>> rows
    ) {
    }

    record DuckDbFederatedRequest(
            List<FederatedTableInput> tables,
            String federatedSql,
            int maxRows,
            int timeoutSeconds
    ) {
        public DuckDbFederatedRequest {
            if (maxRows <= 0) {
                maxRows = SqlQuery.Request.DEFAULT_MAX_ROWS;
            }
            maxRows = Math.min(maxRows, SqlQuery.Request.MAX_ALLOWED_ROWS);
            if (timeoutSeconds <= 0) {
                timeoutSeconds = SqlQuery.Request.DEFAULT_TIMEOUT_SECONDS;
            }
            timeoutSeconds = Math.min(timeoutSeconds, SqlQuery.Request.MAX_ALLOWED_TIMEOUT_SECONDS);
        }
    }

    record StarRocksFederatedRequest(
            ConnectionConfig starRocksConfig,
            String federatedSql,
            int maxRows,
            int timeoutSeconds
    ) {
        public StarRocksFederatedRequest {
            if (maxRows <= 0) {
                maxRows = SqlQuery.Request.DEFAULT_MAX_ROWS;
            }
            maxRows = Math.min(maxRows, SqlQuery.Request.MAX_ALLOWED_ROWS);
            if (timeoutSeconds <= 0) {
                timeoutSeconds = SqlQuery.Request.DEFAULT_TIMEOUT_SECONDS;
            }
            timeoutSeconds = Math.min(timeoutSeconds, SqlQuery.Request.MAX_ALLOWED_TIMEOUT_SECONDS);
        }
    }

    record FederatedResult(
            String engineUsed,
            String executedSql,
            List<SqlQuery.Column> columns,
            List<Map<String, Object>> rows,
            int rowCount,
            boolean truncated,
            long elapsedMillis
    ) {
    }

    /** 返回当前运行环境可用的联邦引擎列表。 */
    List<FederationEngine> availableEngines();

    /** 使用隔离的嵌入式 DuckDB 内存会话执行跨源任意只读 JOIN / 聚合。 */
    FederatedResult executeDuckDbJoin(DuckDbFederatedRequest request);

    /** 通过 StarRocks External Catalog 下推跨源三段式只读 SQL。 */
    FederatedResult executeStarRocksJoin(StarRocksFederatedRequest request);
}
