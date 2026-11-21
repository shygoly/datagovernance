package com.datagov.runtime.autonomy;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class FlowActivityRegistry {

    private final Map<String, FlowActivity> activities;

    public FlowActivityRegistry(List<FlowActivity> activities) {
        Map<String, FlowActivity> map = new LinkedHashMap<>();
        for (FlowActivity activity : activities) {
            FlowActivity previous = map.putIfAbsent(activity.ref(), activity);
            if (previous != null) {
                throw new IllegalStateException("重复的 FlowActivity ref=" + activity.ref()
                        + ": " + previous.getClass().getName() + " / " + activity.getClass().getName());
            }
        }
        this.activities = Map.copyOf(map);
    }

    public FlowActivity require(String ref) {
        FlowActivity activity = activities.get(ref);
        if (activity == null) {
            throw new BizException(ErrorCode.RTM_FLOW_STATE, "没有注册自治 Activity: " + ref);
        }
        return activity;
    }

    public boolean supports(String ref) {
        return activities.containsKey(ref);
    }
}
