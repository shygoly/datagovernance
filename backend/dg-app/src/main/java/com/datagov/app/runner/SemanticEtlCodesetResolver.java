package com.datagov.app.runner;

import com.datagov.metadata.entity.SemanticEntities.SemanticObject;
import com.datagov.metadata.service.SemanticCodesetService;
import org.springframework.stereotype.Component;

import java.util.Map;

/** {@link EtlCodesetResolver} 的生产实现:语义对象 + 值字典(见 {@link SemanticCodesetService})。 */
@Component
public class SemanticEtlCodesetResolver implements EtlCodesetResolver {

    private final SemanticCodesetService codesets;

    public SemanticEtlCodesetResolver(SemanticCodesetService codesets) {
        this.codesets = codesets;
    }

    @Override
    public Map<String, String> resolve(String workspaceId, String codesetId, String mapTo,
                                       boolean ignoreCase) {
        SemanticObject codeset = codesets.resolve(workspaceId, codesetId).orElseThrow(
                () -> new IllegalArgumentException("码表不存在或不可见: " + codesetId));
        String status = codeset.getStatus();
        if ("DEPRECATED".equalsIgnoreCase(status) || "REJECTED".equalsIgnoreCase(status)) {
            throw new IllegalArgumentException("码表 " + codeset.getCode() + " 已是 " + status + " 状态,不能用于执行");
        }
        return codesets.loadMapping(codeset.getId(), mapTo, ignoreCase);
    }
}
