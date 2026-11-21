package com.datagov.app.runner;

import com.datagov.control.service.BadRowBatchService;
import org.springframework.stereotype.Component;

/** 把 Control Space 的 {@link BadRowBatchService} 桥接到 Runner 侧 {@link EtlBadRowStore}。 */
@Component
public class ControlBadRowBridge implements EtlBadRowStore {

    private final BadRowBatchService delegate;

    public ControlBadRowBridge(BadRowBatchService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void record(String workspaceId, String jobId, String executionId, String nodeId,
                       long badRowCount, String storageRef, String sampleErrorJson) {
        delegate.record(workspaceId, jobId, executionId, nodeId, badRowCount, storageRef, sampleErrorJson);
    }
}
