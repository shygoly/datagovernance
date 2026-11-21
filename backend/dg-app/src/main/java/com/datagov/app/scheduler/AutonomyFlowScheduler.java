package com.datagov.app.scheduler;

import com.datagov.runtime.autonomy.DurableFlowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * T+1 回检 / 审批超时唤醒（P2-1）。定时开关归装配层，领域模块不标 @Scheduled。
 */
@Component
public class AutonomyFlowScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutonomyFlowScheduler.class);

    private final DurableFlowEngine flowEngine;
    private final int batchSize;

    public AutonomyFlowScheduler(DurableFlowEngine flowEngine,
                                 @Value("${dg.autonomy.flow-sweep-batch-size:50}") int batchSize) {
        this.flowEngine = flowEngine;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${dg.autonomy.flow-sweep-ms:30000}")
    public void sweep() {
        try {
            int advanced = flowEngine.sweepDueInstances(Instant.now(), batchSize);
            if (advanced > 0) {
                log.info("自治流程到期唤醒 {} 个实例", advanced);
            }
        } catch (RuntimeException ex) {
            log.error("自治流程到期巡检失败，本轮跳过", ex);
        }
    }
}
