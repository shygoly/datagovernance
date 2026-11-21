package com.datagov.metadata.service.lineage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.tenant.Caller;
import com.datagov.metadata.entity.QualityEntities.QualityRule;
import com.datagov.metadata.entity.SemanticEntities.SemanticMapping;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.mapper.QualityRuleMapper;
import com.datagov.metadata.mapper.SemanticMappingMapper;
import com.datagov.metadata.mapper.SemanticObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 语义血缘桥（column-lineage-e2e P3-1）：把血缘下游的治理消费方投影出来。
 *
 * <ul>
 *   <li>{@code METRIC}：经 {@code SemanticMapping} 绑定到目标表/列的已发布 {@code SemanticObject(METRIC)}；</li>
 *   <li>{@code QUALITY_RULE}：作用在目标表/列、且启用的质检规则；</li>
 *   <li>{@code REPORT}：已发布的分析报告，由 {@link GovernanceConsumerProvider} 在 dg-app 装配
 *       （报告属于 Governance Space，本模块不可见）。</li>
 * </ul>
 *
 * <p><b>边界与安全</b>
 * <ol>
 *   <li>只读、不落库：消费方不是血缘事实，不写 {@code md_lineage_*}，避免与治理对象状态漂移；</li>
 *   <li>锚点只来自上层已做过逐跳 ACL 的可见节点——受限数据集永远不会成为锚点，
 *       因此不会借「消费方」侧信道泄露受限资产；</li>
 *   <li>每类消费方再按调用者实时权限（semantic:read / quality:read / 提供者声明的权限）二次判定，无权即跳过；</li>
 *   <li>结果数量有上限，超限静默截断并保证确定性顺序。</li>
 * </ol>
 */
@Service
public class SemanticLineageBridge {

    public static final String METRIC = "METRIC";
    public static final String REPORT = "REPORT";
    public static final String QUALITY_RULE = "QUALITY_RULE";

    static final String PERM_SEMANTIC_READ = "metadata:semantic:read";
    static final String PERM_QUALITY_READ = "metadata:quality:read";
    static final int MAX_CONSUMERS = 200;

    private static final Logger log = LoggerFactory.getLogger(SemanticLineageBridge.class);

    /**
     * 投影锚点：图中一个可见节点。
     *
     * @param nodeId    图内节点 ID（列节点或数据集节点）
     * @param datasetId 所属 Catalog 数据集
     * @param columnId  Catalog 字段 ID；数据集级锚点为 null
     */
    public record ConsumerAnchor(String nodeId, String datasetId, String columnId) {
    }

    /** 某个锚点被某个治理消费方消费。 */
    public record ConsumerLink(String anchorNodeId, GovernanceConsumer consumer) {
    }

    private final SemanticMappingMapper mappingMapper;
    private final SemanticObjectMapper objectMapper;
    private final QualityRuleMapper qualityRuleMapper;
    private final List<GovernanceConsumerProvider> providers;

    @Autowired
    public SemanticLineageBridge(SemanticMappingMapper mappingMapper,
                                 SemanticObjectMapper objectMapper,
                                 QualityRuleMapper qualityRuleMapper,
                                 ObjectProvider<GovernanceConsumerProvider> providers) {
        this(mappingMapper, objectMapper, qualityRuleMapper, providers.orderedStream().toList());
    }

    public SemanticLineageBridge(SemanticMappingMapper mappingMapper,
                                 SemanticObjectMapper objectMapper,
                                 QualityRuleMapper qualityRuleMapper,
                                 List<GovernanceConsumerProvider> providers) {
        this.mappingMapper = mappingMapper;
        this.objectMapper = objectMapper;
        this.qualityRuleMapper = qualityRuleMapper;
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    /**
     * @param anchors 图内已通过逐跳 ACL 的可见节点
     * @return 锚点 → 治理消费方；顺序确定（按类型、名称、ID），最多 {@value #MAX_CONSUMERS} 条
     */
    public List<ConsumerLink> project(Caller caller, String workspaceId, Collection<ConsumerAnchor> anchors) {
        if (caller == null || workspaceId == null || workspaceId.isBlank()
                || anchors == null || anchors.isEmpty()) {
            return List.of();
        }
        List<ConsumerAnchor> usable = anchors.stream()
                .filter(a -> a != null && a.nodeId() != null && a.datasetId() != null && !a.datasetId().isBlank())
                .toList();
        if (usable.isEmpty()) {
            return List.of();
        }
        Set<String> datasetIds = new LinkedHashSet<>();
        usable.forEach(a -> datasetIds.add(a.datasetId()));

        List<GovernanceConsumer> consumers = new ArrayList<>();
        if (caller.hasPermission(PERM_SEMANTIC_READ)) {
            consumers.addAll(metrics(workspaceId, datasetIds));
        }
        if (caller.hasPermission(PERM_QUALITY_READ)) {
            consumers.addAll(qualityRules(workspaceId, datasetIds));
        }
        for (GovernanceConsumerProvider provider : providers) {
            if (!caller.hasPermission(provider.requiredPermission())) {
                continue;
            }
            try {
                consumers.addAll(provider.findConsumers(workspaceId, datasetIds));
            } catch (RuntimeException ex) {
                // 治理消费方是血缘之上的衍生视图：某一类读失败不能让血缘图本身不可用。
                log.warn("治理消费方提供者失败 type={} workspace={}", provider.refType(), workspaceId, ex);
            }
        }

        Map<String, ConsumerLink> links = new LinkedHashMap<>();
        for (GovernanceConsumer consumer : consumers) {
            for (ConsumerAnchor anchor : usable) {
                if (matches(anchor, consumer)) {
                    links.putIfAbsent(anchor.nodeId() + "|" + consumer.refType() + "|" + consumer.refId(),
                            new ConsumerLink(anchor.nodeId(), consumer));
                }
            }
        }
        return links.values().stream()
                .sorted((a, b) -> {
                    int c = a.consumer().refType().compareTo(b.consumer().refType());
                    if (c == 0) {
                        c = Objects.toString(a.consumer().displayName(), "")
                                .compareTo(Objects.toString(b.consumer().displayName(), ""));
                    }
                    if (c == 0) {
                        c = a.consumer().refId().compareTo(b.consumer().refId());
                    }
                    return c == 0 ? a.anchorNodeId().compareTo(b.anchorNodeId()) : c;
                })
                .limit(MAX_CONSUMERS)
                .toList();
    }

    /**
     * 列级锚点只接绑定在同一列、或整表绑定（columnId 为空）的消费方；
     * 数据集级锚点接该数据集上的所有消费方。
     */
    static boolean matches(ConsumerAnchor anchor, GovernanceConsumer consumer) {
        if (consumer.datasetId() == null || !consumer.datasetId().equals(anchor.datasetId())) {
            return false;
        }
        if (anchor.columnId() == null || anchor.columnId().isBlank()) {
            return true;
        }
        return consumer.columnId() == null || consumer.columnId().isBlank()
                || consumer.columnId().equals(anchor.columnId());
    }

    private List<GovernanceConsumer> metrics(String workspaceId, Set<String> datasetIds) {
        List<SemanticMapping> mappings = mappingMapper.selectList(new LambdaQueryWrapper<SemanticMapping>()
                .eq(SemanticMapping::getWorkspaceId, workspaceId)
                .eq(SemanticMapping::getStatus, "PUBLISHED")
                .in(SemanticMapping::getCatalogDatasetId, datasetIds));
        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }
        Set<String> objectIds = new LinkedHashSet<>();
        mappings.forEach(m -> objectIds.add(m.getObjectId()));
        Map<String, SemanticObject> objects = new LinkedHashMap<>();
        for (SemanticObject object : objectMapper.selectList(new LambdaQueryWrapper<SemanticObject>()
                .eq(SemanticObject::getWorkspaceId, workspaceId)
                .eq(SemanticObject::getObjectType, "METRIC")
                .eq(SemanticObject::getStatus, "PUBLISHED")
                .in(SemanticObject::getId, objectIds))) {
            objects.put(object.getId(), object);
        }
        List<GovernanceConsumer> result = new ArrayList<>();
        for (SemanticMapping mapping : mappings) {
            SemanticObject object = objects.get(mapping.getObjectId());
            if (object == null) {
                continue;
            }
            String code = object.getCode() == null ? object.getId() : object.getCode();
            result.add(new GovernanceConsumer(METRIC, object.getId(), "metric:" + code,
                    object.getName() == null ? code : object.getName(), object.getStatus(),
                    mapping.getCatalogDatasetId(), mapping.getCatalogColumnId(),
                    "语义指标 " + code + (mapping.getMappingRole() == null ? "" : "（" + mapping.getMappingRole() + "）")));
        }
        return result;
    }

    private List<GovernanceConsumer> qualityRules(String workspaceId, Set<String> datasetIds) {
        List<QualityRule> rules = qualityRuleMapper.selectList(new LambdaQueryWrapper<QualityRule>()
                .eq(QualityRule::getWorkspaceId, workspaceId)
                .eq(QualityRule::getEnabled, true)
                .in(QualityRule::getDatasetId, datasetIds));
        if (rules == null) {
            return List.of();
        }
        List<GovernanceConsumer> result = new ArrayList<>();
        for (QualityRule rule : rules) {
            String type = rule.getRuleType() == null ? "RULE" : rule.getRuleType();
            result.add(new GovernanceConsumer(QUALITY_RULE, rule.getId(), "quality-rule:" + rule.getId(),
                    type + (rule.getColumnId() == null ? "（整表）" : ""), "ENABLED",
                    rule.getDatasetId(), rule.getColumnId(),
                    "质检规则 " + type + (rule.getSeverity() == null ? "" : " · " + rule.getSeverity())));
        }
        return result;
    }
}
