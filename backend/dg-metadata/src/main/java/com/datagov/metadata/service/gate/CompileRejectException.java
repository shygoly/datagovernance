package com.datagov.metadata.service.gate;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;

/**
 * 携带 {@link CompileReject} 的编译门禁失败；HTTP 层仍映射为 {@link ErrorCode#MTD_SEMANTIC_COMPILE_FAILED}。
 */
public class CompileRejectException extends BizException {

    private final CompileReject reject;

    public CompileRejectException(CompileReject reject) {
        super(ErrorCode.MTD_SEMANTIC_COMPILE_FAILED,
                reject == null ? ErrorCode.MTD_SEMANTIC_COMPILE_FAILED.defaultMessage() : reject.message(),
                reject == null ? null : reject.errorCode());
        this.reject = reject;
    }

    public CompileReject reject() {
        return reject;
    }
}
