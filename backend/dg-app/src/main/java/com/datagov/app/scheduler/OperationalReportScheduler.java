package com.datagov.app.scheduler;

import com.datagov.governance.service.OperationalReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 运营报表调度触发入口（装配层）。
 *
 * <p>与 {@link ControlRuntimeScheduler} 同理：定时开关归装配层，领域模块不标 {@code @Scheduled}。
 * 多实例时进程内防重入不够，生产需外部分布式锁。
 */
@Component
@ConfigurationProperties(prefix = "dg.ops-report.scheduler")
public class OperationalReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(OperationalReportScheduler.class);

    private final OperationalReportService reportService;
    private boolean enabled = true;

    public OperationalReportScheduler(OperationalReportService reportService) {
        this.reportService = reportService;
    }

    @Scheduled(fixedDelayString = "${dg.ops-report.scheduler.tick-interval-ms:15000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            reportService.fireDueSchedules(Instant.now());
        } catch (RuntimeException e) {
            log.error("运营报表调度轮询失败，本轮跳过", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
