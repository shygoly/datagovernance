package com.datagov.control.autonomy;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class FlowSpecParser {

    private final ObjectMapper json;

    public FlowSpecParser(ObjectMapper json) {
        this.json = json;
    }

    public FlowSpec parse(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new BizException(ErrorCode.CTL_FLOW_COMPILE_FAILED, "FlowSpec IR 不能为空");
        }
        try {
            return json.reader()
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .forType(FlowSpec.class)
                    .readValue(json.writeValueAsString(raw));
        } catch (Exception ex) {
            throw new BizException(ErrorCode.CTL_FLOW_COMPILE_FAILED,
                    "FlowSpec IR 不是合法 JSON: " + ex.getMessage());
        }
    }
}
