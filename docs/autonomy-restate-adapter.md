# 数据自治 Restate 适配器契约（可选后端）

> 对应 `data-driven-ops-profile` P3-1。默认仍是 PostgreSQL 单库 `PgDurableFlowEngine`；Restate 只在显式配置 `dg.autonomy.engine=restate` 时启用。

## 1. 配置

```yaml
dg:
  autonomy:
    engine: restate
    restate:
      ingress-url: http://127.0.0.1:8080
      service: DataGovernanceFlow
      admin-service: DataGovernanceFlowAdmin
      auth-token: ${RESTATE_AUTH_TOKEN:}
      timeout-ms: 10000
```

## 2. 部署侧需要提供的 Workflow handlers

平台只依赖以下四个 ingress 调用，不引入 Temporal/Restate SDK 到 Java 主进程：

| 调用 | 路径 | 请求体 | 返回 |
|---|---|---|---|
| 启动 | `POST /restate/send/{service}/{instanceId}/run` | `{plan, trigger, initiator, instanceId}` | 无需同步返回；workflow 完成后 output 返回 `FlowInstanceView` |
| 信号 | `POST /restate/send/{service}/{instanceId}/signal` | `{signal, payload, actor}` | 202 |
| 取消 | `POST /restate/send/{service}/{instanceId}/cancel` | `{reason, actor}` | 202 |
| 查询 | `POST /restate/output` | `{target:"workflow",workflowName,workflowKey}` | `FlowInstanceView` 或 `{"message":"not ready"}` |
| 批量取消 | `POST /restate/call/{adminService}/cancelActive` | `{workspaceId, reason, actor}` | `{"cancelled": N}` |

`plan` 的结构就是平台 `CompiledFlowPlan`（含 `flow.ir/v1` 编译后的 steps、digest、refs）；`FlowInstanceView` 结构见 `backend/dg-runtime/.../FlowInstanceView.java`。Restate workflow 内部的 durable timer 负责审批超时/T+1 唤醒，因此平台侧 `sweepDueInstances` 返回 0。

## 3. 验证边界

- `RestateFlowEngineAdapterTest` 使用本地 `HttpServer` 模拟 ingress，证明路径、请求体、幂等键、output-not-ready 映射与 cancelActive 计数正确。
- 未在真实 Restate 集群 + 真实 workflow service 上跑端到端；生产启用前必须补这一步。
- 未接入 Temporal SDK；`TemporalFlowEngineAdapter` 仍是 fail-fast 占位，选择 `dg.autonomy.engine=temporal` 会明确报错而不是静默退回 PG。
