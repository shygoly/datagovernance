package com.datagov.app.autonomy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.mapper.SemanticObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 装配层的只读语义查找：把 FlowSpec 里的 ref 还原成已发布的 Target/ActionType。
 * 不把解析结果缓存到运行态，避免发布新版本后旧缓存继续生效（C9）。
 */
@Component
public class AutonomyLookup {

    private final SemanticObjectMapper semanticObjectMapper;
    private final ObjectMapper json;

    public AutonomyLookup(SemanticObjectMapper semanticObjectMapper, ObjectMapper json) {
        this.semanticObjectMapper = semanticObjectMapper;
        this.json = json;
    }

    public SemanticObject requirePublished(String workspaceId, String ref, String type) {
        if (ref == null || ref.isBlank()) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID, type + " 引用为空");
        }
        SemanticObject object = semanticObjectMapper.selectOne(new LambdaQueryWrapper<SemanticObject>()
                .and(w -> w.eq(SemanticObject::getWorkspaceId, workspaceId)
                        .or(g -> g.eq(SemanticObject::getScope, "GLOBAL")
                                .eq(SemanticObject::getStatus, "PUBLISHED")))
                .eq(SemanticObject::getObjectType, type)
                .eq(SemanticObject::getStatus, "PUBLISHED")
                .and(w -> w.eq(SemanticObject::getId, ref)
                        .or().eq(SemanticObject::getCode, ref))
                .last("LIMIT 1"));
        if (object == null) {
            throw new BizException(ErrorCode.MTD_SEMANTIC_INVALID,
                    type + " 未发布或不存在: " + ref);
        }
        return object;
    }

    public Map<String, Object> spec(SemanticObject object) {
        return readMap(object.getSpecJson());
    }

    public String activityRef(Map<String, Object> actionSpec) {
        return str(actionSpec.get("activityRef"));
    }

    public String authLevel(Map<String, Object> actionSpec) {
        return str(actionSpec.get("authLevel")).toUpperCase();
    }

    public String actionCode(Map<String, Object> actionSpec) {
        return str(actionSpec.get("actionCode"));
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
