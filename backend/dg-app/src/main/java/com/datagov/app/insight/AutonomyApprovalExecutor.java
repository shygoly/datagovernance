package com.datagov.app.insight;

import java.util.Map;

/**
 * V32 提案执行器对自治流程审批的扩展点。
 *
 * <p>放在 insight 包是为了让通用提案服务不反向依赖 autonomy 包；
 * 真正实现位于装配层的 {@code AutonomyApprovalExecutorImpl}。
 */
public interface AutonomyApprovalExecutor {

    Map<String, Object> execute(Map<String, Object> payload);
}
