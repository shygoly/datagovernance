package com.datagov.metadata.service.gate;

import com.datagov.metadata.service.SemanticQueryCompiler.CompiledSemanticQuery;

/**
 * compile_and_validate 干跑结果：成功返回 compiled；失败返回类型化 reject（HTTP 仍 200 + ok=false）。
 */
public record CompileAndValidateResult(
        boolean ok,
        CompileReject reject,
        CompiledSemanticQuery compiled
) {
    public static CompileAndValidateResult success(CompiledSemanticQuery compiled) {
        return new CompileAndValidateResult(true, null, compiled);
    }

    public static CompileAndValidateResult failure(CompileReject reject) {
        return new CompileAndValidateResult(false, reject, null);
    }
}
