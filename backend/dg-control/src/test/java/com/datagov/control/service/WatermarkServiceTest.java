package com.datagov.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 水位线比较规则。推进 / 重置的持久化路径由 mapper 层与端到端验收覆盖。 */
class WatermarkServiceTest {

    @Test
    @DisplayName("NUMBER 按数值比较,不是字典序")
    void numeric() {
        assertThat(WatermarkService.compare("NUMBER", "100", "99")).isPositive();
        assertThat(WatermarkService.compare("NUMBER", "9", "10")).isNegative();
        assertThat(WatermarkService.compare("NUMBER", "1.50", "1.5")).isZero();
    }

    @Test
    @DisplayName("TIMESTAMP 兼容 ISO 瞬时 / 带偏移 / 本地时间 / 纯日期")
    void timestamps() {
        assertThat(WatermarkService.compare("TIMESTAMP", "2026-01-02T00:00:00Z", "2026-01-01T23:59:59Z"))
                .isPositive();
        assertThat(WatermarkService.compare("TIMESTAMP", "2026-01-01T08:00:00+08:00", "2026-01-01T00:00:00Z"))
                .isZero();
        assertThat(WatermarkService.compare("TIMESTAMP", "2026-01-01 10:00:00", "2026-01-01T09:00:00"))
                .isPositive();
        assertThat(WatermarkService.compare("TIMESTAMP", "2026-02-01", "2026-01-31")).isPositive();
    }

    @Test
    @DisplayName("无法解析时退回字典序,不抛异常")
    void fallsBackToLexical() {
        assertThat(WatermarkService.compare("NUMBER", "abc", "abd")).isNegative();
        assertThat(WatermarkService.compare("TIMESTAMP", "not-a-date", "also-not")).isNotZero();
    }

    @Test
    @DisplayName("未知类型按 STRING 处理")
    void normalizeType() {
        assertThat(WatermarkService.normalizeType("weird")).isEqualTo("STRING");
        assertThat(WatermarkService.normalizeType(null)).isEqualTo("STRING");
        assertThat(WatermarkService.normalizeType("number")).isEqualTo("NUMBER");
    }
}
