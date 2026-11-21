package com.datagov.app.runner;

import java.util.List;
import java.util.Map;

/**
 * SOURCE_API 的取数缝隙:发一次 HTTP(S) GET,按 {@code recordsPath} 取出 JSON 数组,逐元素转成行。
 *
 * <p>在 DuckDB 锁定外部访问之前由 Java 侧调用 —— 用户 SQL 永远不碰网络。
 * 当前不含分页(IR 里没有分页字段):需要分页的接口请继续使用 API_PARSE 作业。
 */
public interface EtlApiFetcher {

    /**
     * @param url         完整 http(s) 地址;与 {@code apiConfigId} 二选一
     * @param apiConfigId REST_API 类型数据源 ID:地址与认证来自该数据源(凭据只在这里解密注入,不进计划)
     * @param recordsPath 以 {@code .} 分隔的 JSON 路径,为空表示顶层就是数组
     * @param maxRows     最多取多少行,0 = 不限
     * @return 每个元素一行;嵌套对象 / 数组序列化成 JSON 字符串
     */
    List<Map<String, Object>> fetch(String workspaceId, String url, String apiConfigId,
                                    String recordsPath, long maxRows) throws Exception;
}
