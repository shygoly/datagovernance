package com.datagov.runtime.autonomy;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 kind 索引的步骤处理器注册表。重复注册直接启动失败：
 * 两个实现同时处理 INVOKE_ACTIVITY 会让同一动作执行两次，这是不可接受的静默错误。
 */
@Component
public class FlowStepHandlerRegistry {

    private final Map<String, FlowStepHandler> handlers;

    public FlowStepHandlerRegistry(List<FlowStepHandler> handlers) {
        Map<String, FlowStepHandler> map = new LinkedHashMap<>();
        for (FlowStepHandler handler : handlers) {
            FlowStepHandler previous = map.putIfAbsent(handler.kind(), handler);
            if (previous != null) {
                throw new IllegalStateException("重复的 FlowStepHandler kind=" + handler.kind()
                        + ": " + previous.getClass().getName() + " / " + handler.getClass().getName());
            }
        }
        this.handlers = Map.copyOf(map);
    }

    public FlowStepHandler require(String kind) {
        FlowStepHandler handler = handlers.get(kind);
        if (handler == null) {
            throw new BizException(ErrorCode.RTM_FLOW_STATE, "没有注册 Flow 步骤处理器: " + kind);
        }
        return handler;
    }

    public boolean supports(String kind) {
        return handlers.containsKey(kind);
    }
}
