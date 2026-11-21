package com.datagov.app.runner;

import java.util.Map;

/**
 * 执行期取码表条目的缝隙(lightweight-etl-pipeline P2-2)。
 *
 * <p>引擎不直接依赖 Metadata 的 Mapper:这样引擎测试里一个内存实现就够了,
 * 生产实现({@link SemanticEtlCodesetResolver})则与编译期校验共用同一套解析规则。
 */
public interface EtlCodesetResolver {

    /**
     * @param codesetId  计划里编译期解析出的码表(语义对象)id
     * @param mapTo      CANONICAL_VALUE / DISPLAY_LABEL
     * @param ignoreCase true 时返回的键已全部转小写
     * @return 输入代码(含同义词)→ 输出值
     * @throws IllegalArgumentException 码表已不存在 / 不可见 / 已废弃 —— 编译之后它可能变了,
     *                                  执行必须失败而不是拿一个空映射把全部值判为未命中
     */
    Map<String, String> resolve(String workspaceId, String codesetId, String mapTo, boolean ignoreCase);
}
