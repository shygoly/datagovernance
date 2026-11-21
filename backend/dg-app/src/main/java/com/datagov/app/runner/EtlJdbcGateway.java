package com.datagov.app.runner;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * ETL 管道访问外部 JDBC 数据源的唯一缝隙(lightweight-etl-pipeline Phase 1)。
 *
 * <p>把「按数据源 ID 取连接 / 取方言引用符 / 生成写入语句」收口成一个接口,
 * 有两个目的:
 * <ul>
 *   <li>生产实现 {@link DefaultEtlJdbcGateway} 复用 {@link TableCopier} 的连接装配与方言处理,
 *       不再复制一份 JDBC URL 拼接逻辑;</li>
 *   <li>引擎({@link EtlPipelineEngine})因此可以不依赖 Metadata 的 Mapper 与凭据解密就被测试 ——
 *       测试里用两个 DuckDB 内存库充当源端与目标端。</li>
 * </ul>
 */
public interface EtlJdbcGateway {

    /**
     * 打开到数据源的连接。
     *
     * @param readOnly true 时调用方只读(源端抽取):实现应设只读并关闭自动提交以便流式读取
     *                 (纵深防御:即使计划被篡改,抽取也写不坏源库)
     */
    Connection open(String workspaceId, String dataSourceId, boolean readOnly) throws SQLException;

    /** 方言限定表名(已带引用符)。 */
    String qualifiedTable(String workspaceId, String dataSourceId,
                          String database, String schema, String table);

    /** 方言标识符引用。 */
    String quote(String workspaceId, String dataSourceId, String identifier);

    /**
     * 写入语句,参数顺序恒等于 {@code columns}。
     *
     * @param writeMode APPEND / OVERWRITE / UPSERT;UPSERT 时 {@code primaryKeys} 为目标端列名
     */
    String insertSql(String workspaceId, String dataSourceId, String qualifiedTable,
                     List<String> columns, String writeMode, List<String> primaryKeys);
}
