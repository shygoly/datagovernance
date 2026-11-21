package com.datagov.app.lineage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagov.governance.domain.ReportState;
import com.datagov.governance.domain.ReportSubjectType;
import com.datagov.governance.entity.GovernanceEntities.AnalysisReport;
import com.datagov.governance.mapper.AnalysisReportMapper;
import com.datagov.metadata.service.lineage.GovernanceConsumer;
import com.datagov.metadata.service.lineage.GovernanceConsumerProvider;
import com.datagov.metadata.service.lineage.SemanticLineageBridge;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 把「以数据集为主体的已发布分析报告」作为血缘下游治理消费方提供给 {@link SemanticLineageBridge}
 * （column-lineage-e2e P3-1）。
 *
 * <p>分析报告属于 Governance Space，Metadata Space 不可见它，所以由唯一装配层 dg-app 桥接，
 * 依赖方向保持 governance → metadata 不变。
 *
 * <p>只读；只返回 {@code PUBLISHED}（已撤回的报告不再是消费方）；按 workspace 过滤；
 * 调用者需持有 {@code governance:analysis:read}——与报告列表接口同一权限，不因血缘入口而放宽。
 */
@Component
public class AnalysisReportConsumerProvider implements GovernanceConsumerProvider {

    static final String PERMISSION = "governance:analysis:read";

    private final AnalysisReportMapper reportMapper;

    public AnalysisReportConsumerProvider(AnalysisReportMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    @Override
    public String refType() {
        return SemanticLineageBridge.REPORT;
    }

    @Override
    public String requiredPermission() {
        return PERMISSION;
    }

    @Override
    public List<GovernanceConsumer> findConsumers(String workspaceId, Collection<String> datasetIds) {
        if (workspaceId == null || datasetIds == null || datasetIds.isEmpty()) {
            return List.of();
        }
        List<GovernanceConsumer> result = new ArrayList<>();
        for (AnalysisReport report : reportMapper.selectList(new LambdaQueryWrapper<AnalysisReport>()
                .eq(AnalysisReport::getWorkspaceId, workspaceId)
                .eq(AnalysisReport::getSubjectType, ReportSubjectType.DATASET)
                .eq(AnalysisReport::getState, ReportState.PUBLISHED)
                .in(AnalysisReport::getSubjectId, datasetIds)
                .orderByDesc(AnalysisReport::getCreatedAt)
                .last("LIMIT 200"))) {
            result.add(new GovernanceConsumer(SemanticLineageBridge.REPORT, report.getId(),
                    "report:" + report.getId(),
                    report.getTitle() == null || report.getTitle().isBlank() ? report.getId() : report.getTitle(),
                    report.getState().name(), report.getSubjectId(), null,
                    "分析报告 v" + report.getLatestVersion()));
        }
        return result;
    }
}
