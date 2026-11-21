package com.datagov.app.runner;

import com.datagov.control.service.WatermarkService;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 将 Control Space 的 {@link WatermarkService} 桥接到 Runner 侧 {@link EtlWatermarkService}。
 */
@Component
public class ControlWatermarkBridge implements EtlWatermarkService {

    private final WatermarkService delegate;

    public ControlWatermarkBridge(WatermarkService delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<String> lastValue(String workspaceId, String jobId, String sourceNodeId) {
        return delegate.get(workspaceId, jobId, sourceNodeId)
                .map(w -> w.getLastValue());
    }

    @Override
    public void advance(String workspaceId, String jobId, String sourceNodeId,
                        String column, String type, String newValue, String executionId) {
        delegate.advance(workspaceId, jobId, sourceNodeId, column, type, newValue, executionId, null);
    }
}
