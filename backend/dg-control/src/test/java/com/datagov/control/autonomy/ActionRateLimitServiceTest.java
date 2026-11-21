package com.datagov.control.autonomy;

import com.datagov.control.mapper.AuActionCounterMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionRateLimitServiceTest {

    private final AuActionCounterMapper mapper = mock(AuActionCounterMapper.class);
    private final ActionRateLimitService service = new ActionRateLimitService(mapper);

    @Test
    @DisplayName("未配置上限时不产生数据库写入")
    void unlimitedDoesNotWrite() {
        assertThat(service.tryConsume("ws1", "PAUSE", 0)).isTrue();
        verify(mapper, never()).increment(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("额度内放行；超限回退本次自增并拒绝")
    void enforcesDailyCap() {
        when(mapper.currentCount(eq("ws1"), eq("PAUSE"), any(LocalDate.class))).thenReturn(1);
        assertThat(service.tryConsume("ws1", "PAUSE", 2)).isTrue();
        verify(mapper).increment(anyString(), eq("ws1"), eq("PAUSE"), any(LocalDate.class));

        when(mapper.currentCount(eq("ws1"), eq("PAUSE"), any(LocalDate.class))).thenReturn(3);
        assertThat(service.tryConsume("ws1", "PAUSE", 2)).isFalse();
        verify(mapper).decrement(eq("ws1"), eq("PAUSE"), any(LocalDate.class));
    }

    @Test
    @DisplayName("执行失败可回退额度")
    void releaseDecrements() {
        service.release("ws1", "PAUSE");
        verify(mapper).decrement(eq("ws1"), eq("PAUSE"), any(LocalDate.class));
    }
}
