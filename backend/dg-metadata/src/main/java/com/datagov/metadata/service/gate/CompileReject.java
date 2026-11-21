package com.datagov.metadata.service.gate;

import java.util.List;
import java.util.Map;

/**
 * 类型化编译拒绝（供 Agent 局部修复；HTTP/工具层可直接序列化）。
 */
public record CompileReject(
        String errorCode,
        String message,
        String location,
        List<String> allowed,
        String repairHint,
        Map<String, Object> details
) {
    public CompileReject {
        allowed = allowed == null ? List.of() : List.copyOf(allowed);
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static CompileReject of(SemanticCompileErrorCode code,
                                   String message,
                                   String location,
                                   List<String> allowed,
                                   String repairHint) {
        return new CompileReject(code.name(), message, location, allowed, repairHint, Map.of());
    }

    public static CompileReject of(SemanticCompileErrorCode code,
                                   String message,
                                   String location,
                                   List<String> allowed,
                                   String repairHint,
                                   Map<String, Object> details) {
        return new CompileReject(code.name(), message, location, allowed, repairHint, details);
    }
}
