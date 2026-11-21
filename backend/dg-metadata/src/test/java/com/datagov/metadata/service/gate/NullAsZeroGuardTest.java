package com.datagov.metadata.service.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("W3 NULL 不隐式变 0")
class NullAsZeroGuardTest {

    private final NullAsZeroGuard guard = new NullAsZeroGuard();

    @Test
    @DisplayName("拒绝 COALESCE(expr, 0) / IFNULL / NVL 静默填零")
    void rejectsNullAsZeroPatterns() {
        assertThatThrownBy(() -> guard.assertAllowed("COALESCE(amount, 0)", false))
                .isInstanceOf(CompileRejectException.class)
                .satisfies(ex -> assertThat(((CompileRejectException) ex).reject().errorCode())
                        .isEqualTo(SemanticCompileErrorCode.NULL_AS_ZERO_FORBIDDEN.name()));

        assertThatThrownBy(() -> guard.assertAllowed("IFNULL(x,0)", false))
                .isInstanceOf(CompileRejectException.class);

        assertThatThrownBy(() -> guard.assertAllowed("nvl(col, 0)", false))
                .isInstanceOf(CompileRejectException.class);
    }

    @Test
    @DisplayName("null_as_zero=true 时允许显式声明")
    void allowsWhenFlagEnabled() {
        guard.assertAllowed("COALESCE(amount, 0)", true);
    }

    @Test
    @DisplayName("普通列表达式与 NULLIF 除零保护不受影响")
    void allowsNormalAndNullif() {
        guard.assertAllowed("t.amount", false);
        guard.assertAllowed("SUM(t.amount) / NULLIF(SUM(t.qty), 0)", false);
    }
}
