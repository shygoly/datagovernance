package com.datagov.metadata.service.metric6d;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;

import java.util.List;

public class SemanticGraphGateException extends BizException {

    private final String gateCode;
    private final List<String> details;

    public SemanticGraphGateException(String gateCode, String message, List<String> details) {
        super(ErrorCode.MTD_SEMANTIC_INVALID, message, gateCode);
        this.gateCode = gateCode;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public String gateCode() {
        return gateCode;
    }

    public List<String> details() {
        return details;
    }
}
