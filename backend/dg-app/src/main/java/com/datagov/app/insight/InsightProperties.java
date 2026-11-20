package com.datagov.app.insight;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 智能问数网关配置（OpenSpec: semantic-ontology-kernel）。
 *
 * <p>平台已完全切换至原生语义问数引擎（{@code dg-metadata} 语义内核 + 确定性 SQL 编译器 + {@code DataAccessGateway}），
 * 不再依赖外部 SuperSonic 服务或共享服务账号。
 */
@ConfigurationProperties(prefix = "dg.insight")
public class InsightProperties {

    /** 问数引擎标识（native） */
    private String engine = "native";
    /** 默认目标查询方言：STARROCKS / MYSQL / POSTGRESQL */
    private String dialect = "STARROCKS";
    /** 默认助手 ID（兼容旧查询日志字段） */
    private int agentId = 1;
    /** 默认语义主题 ID */
    private long defaultDatasetId = 1;
    /** 平台单次最多返回行数 */
    private int maxRows = 200;
    /** 只读查询执行超时（秒） */
    private int timeoutSeconds = 120;

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public String getDialect() {
        return dialect;
    }

    public void setDialect(String dialect) {
        this.dialect = dialect;
    }

    public int getAgentId() {
        return agentId;
    }

    public void setAgentId(int agentId) {
        this.agentId = agentId;
    }

    public long getDefaultDatasetId() {
        return defaultDatasetId;
    }

    public void setDefaultDatasetId(long defaultDatasetId) {
        this.defaultDatasetId = defaultDatasetId;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
