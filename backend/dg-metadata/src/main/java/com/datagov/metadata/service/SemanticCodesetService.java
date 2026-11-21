package com.datagov.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.entity.SemanticEntities.SemanticValueDict;
import com.datagov.metadata.mapper.SemanticObjectMapper;
import com.datagov.metadata.mapper.SemanticValueDictMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 平台权威码表的只读解析(lightweight-etl-pipeline P2-2)。
 *
 * <p>治理模型里 {@code Standard(type=codeset)} 的落地形态是「语义对象 + 值字典」:
 * 对象是码集本身(有 code、状态、版本),值字典是它的取值域
 * (原始值 {@code raw_value} → 标准标签 {@code display_label},另有同义词)。
 * 值字典在写入侧已被 C5 约束为只能是 L1/L2(严禁 L3/L4 明文),所以条目可以安全地
 * 带进 ETL 执行会话。
 *
 * <p>本服务<b>只读</b>,被两个调用点共用,保证「编译期校验的那个码表」与
 * 「执行期查表用的那个码表」按同一规则解析:
 * <ul>
 *   <li>Control 的 {@code MetadataLookupAdapter}:编译期存在性 / 状态检查;</li>
 *   <li>装配层的 Runner 侧解析器:执行期取条目。</li>
 * </ul>
 */
@Service
public class SemanticCodesetService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCodesetService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 输出取值:CANONICAL_VALUE = 值字典的 raw_value(同义词归一到它);DISPLAY_LABEL = 标准标签。 */
    public static final String MAP_TO_CANONICAL = "CANONICAL_VALUE";
    public static final String MAP_TO_LABEL = "DISPLAY_LABEL";

    private final SemanticObjectMapper objectMapper;
    private final SemanticValueDictMapper valueDictMapper;

    public SemanticCodesetService(SemanticObjectMapper objectMapper,
                                  SemanticValueDictMapper valueDictMapper) {
        this.objectMapper = objectMapper;
        this.valueDictMapper = valueDictMapper;
    }

    /**
     * 按 id 或 code 解析码集对象。可见范围 = 本空间的对象 + GLOBAL 且已发布的对象;
     * code 同名时优先本空间(与语义对象的「空间覆写 GLOBAL」约定一致),再优先已发布。
     */
    public Optional<SemanticObject> resolve(String workspaceId, String ref) {
        if (workspaceId == null || ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        String key = ref.trim();
        SemanticObject byId = objectMapper.selectById(key);
        if (byId != null && visible(workspaceId, byId)) {
            return Optional.of(byId);
        }
        List<SemanticObject> byCode = objectMapper.selectList(new LambdaQueryWrapper<SemanticObject>()
                .apply("lower(code) = {0}", key.toLowerCase(Locale.ROOT))
                .and(w -> w.eq(SemanticObject::getWorkspaceId, workspaceId)
                        .or(g -> g.eq(SemanticObject::getScope, "GLOBAL")
                                .eq(SemanticObject::getStatus, "PUBLISHED"))));
        Instant now = Instant.now();
        return byCode.stream()
                .filter(o -> o.getValidTo() == null || o.getValidTo().isAfter(now))
                .min(Comparator
                        .comparing((SemanticObject o) -> !workspaceId.equals(o.getWorkspaceId()))
                        .thenComparing(o -> !"PUBLISHED".equalsIgnoreCase(o.getStatus())));
    }

    /**
     * 值字典条目数。不属于本空间的 GLOBAL 码集返回 -1(未知):它的条目行带的是归属空间的
     * workspace_id,在 RLS 之下本空间的会话看不见,返回 0 会让调用方误报「码表是空的」。
     */
    public long entryCount(String workspaceId, SemanticObject codeset) {
        if (!workspaceId.equals(codeset.getWorkspaceId())) {
            return -1;
        }
        Long count = valueDictMapper.selectCount(new LambdaQueryWrapper<SemanticValueDict>()
                .eq(SemanticValueDict::getObjectId, codeset.getId()));
        return count == null ? 0 : count;
    }

    /**
     * 执行期取查表映射:输入代码 → 输出值。同义词展开;键冲突时显式 raw_value 优先于同义词,
     * 同类冲突先到先得(按 raw_value 排序,结果确定)。
     *
     * @param ignoreCase true 时键统一转小写(调用方查表时也要转小写)
     */
    public Map<String, String> loadMapping(String codesetObjectId, String mapTo, boolean ignoreCase) {
        boolean toLabel = MAP_TO_LABEL.equalsIgnoreCase(mapTo);
        List<SemanticValueDict> rows = valueDictMapper.selectList(new LambdaQueryWrapper<SemanticValueDict>()
                .eq(SemanticValueDict::getObjectId, codesetObjectId)
                .orderByAsc(SemanticValueDict::getRawValue));
        Map<String, String> mapping = new LinkedHashMap<>();
        for (SemanticValueDict row : rows) {
            mapping.putIfAbsent(key(row.getRawValue(), ignoreCase), output(row, toLabel));
        }
        for (SemanticValueDict row : rows) {
            for (String synonym : synonyms(row)) {
                mapping.putIfAbsent(key(synonym, ignoreCase), output(row, toLabel));
            }
        }
        return mapping;
    }

    private static String output(SemanticValueDict row, boolean toLabel) {
        return toLabel ? row.getDisplayLabel() : row.getRawValue();
    }

    private static String key(String value, boolean ignoreCase) {
        return ignoreCase ? value.toLowerCase(Locale.ROOT) : value;
    }

    private static List<String> synonyms(SemanticValueDict row) {
        if (row.getSynonymsJson() == null || row.getSynonymsJson().isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = JSON.readValue(row.getSynonymsJson(), new TypeReference<List<String>>() {
            });
            return parsed == null ? List.of() : parsed.stream().filter(s -> s != null && !s.isBlank()).toList();
        } catch (Exception e) {
            log.warn("值字典同义词 JSON 无法解析,已忽略 entry={}", row.getId());
            return List.of();
        }
    }

    private static boolean visible(String workspaceId, SemanticObject obj) {
        return workspaceId.equals(obj.getWorkspaceId())
                || ("GLOBAL".equalsIgnoreCase(obj.getScope()) && "PUBLISHED".equalsIgnoreCase(obj.getStatus()));
    }
}
