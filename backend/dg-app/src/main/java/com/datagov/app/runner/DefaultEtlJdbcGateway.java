package com.datagov.app.runner;

import com.datagov.data.connector.ddl.DialectUpsert;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.mapper.DataSourceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * {@link EtlJdbcGateway} 的生产实现:数据源定义来自 Metadata,连接装配与方言处理沿用 {@link TableCopier}。
 */
@Component
public class DefaultEtlJdbcGateway implements EtlJdbcGateway {

    private static final Logger log = LoggerFactory.getLogger(DefaultEtlJdbcGateway.class);

    private final DataSourceMapper dataSourceMapper;
    private final TableCopier copier;

    public DefaultEtlJdbcGateway(DataSourceMapper dataSourceMapper, TableCopier copier) {
        this.dataSourceMapper = dataSourceMapper;
        this.copier = copier;
    }

    @Override
    public Connection open(String workspaceId, String dataSourceId, boolean readOnly)
            throws SQLException {
        Connection connection = copier.open(require(workspaceId, dataSourceId));
        if (readOnly) {
            connection.setReadOnly(true);
            // PostgreSQL 的游标式流式读取需要关闭自动提交,否则驱动会把整张表拉进内存
            connection.setAutoCommit(false);
        }
        return connection;
    }

    @Override
    public String qualifiedTable(String workspaceId, String dataSourceId,
                                 String database, String schema, String table) {
        return TableCopier.qualified(require(workspaceId, dataSourceId), database, schema, table);
    }

    @Override
    public String quote(String workspaceId, String dataSourceId, String identifier) {
        return TableCopier.quote(require(workspaceId, dataSourceId), identifier);
    }

    @Override
    public String insertSql(String workspaceId, String dataSourceId, String qualifiedTable,
                            List<String> columns, String writeMode, List<String> primaryKeys) {
        DataSourceEntity ds = require(workspaceId, dataSourceId);
        if ("UPSERT".equals(writeMode)) {
            DialectUpsert.UpsertStatement statement = DialectUpsert.generate(
                    ds.getType(), qualifiedTable, columns, primaryKeys);
            statement.warnings().forEach(w -> log.warn("ETL UPSERT 写入 {}: {}", qualifiedTable, w));
            return statement.sql();
        }
        String columnList = columns.stream().map(c -> TableCopier.quote(ds, c))
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        String placeholders = String.join(", ", Collections.nCopies(columns.size(), "?"));
        return "INSERT INTO %s (%s) VALUES (%s)".formatted(qualifiedTable, columnList, placeholders);
    }

    private DataSourceEntity require(String workspaceId, String dataSourceId) {
        DataSourceEntity entity = dataSourceId == null ? null : dataSourceMapper.selectById(dataSourceId);
        // 工作空间不匹配与「不存在」同一个报错:不向调用者确认别的空间里有没有这个 ID
        if (entity == null || (workspaceId != null && !workspaceId.equals(entity.getWorkspaceId()))) {
            throw new IllegalArgumentException("数据源不存在: " + dataSourceId);
        }
        return entity;
    }
}
