# Tasks: `governed-agent-evolution-mvp`

> 对齐 [proposal.md](./proposal.md) 与 [design.md](./design.md)，按四阶段落地并全部通过构建、单测、PG RLS 集成测试与离线 `pass^3` 回归门禁。

## Phase 1 — 合规底线补强（后端 + 测试）

- [x] **T1.1** 新增 Flyway 迁移 `V44__governed_agent_evolution_mvp.sql`：创建 `md_dataset_profile_run`（启用 PostgreSQL RLS）并扩展 `gv_governed_fact`（`pattern_fingerprint`、`rejection_reason` 与索引）；复用 `V40` 已落地的 `gv_analysis_report_version.reproducibility_fingerprint`。
- [x] **T1.2** 补齐 C5 按密级模型路由护栏：
  - `ErrorCode.GOV_MODEL_ROUTING_BLOCKED`（403）；
  - `agent-service/src/config.ts` 增加 `modelEndpointKind: SELF_HOSTED | EXTERNAL` 并通过 `X-Agent-Model-Endpoint-Kind` 透传；
  - `AgentToolController` 新增 `/api/v1/internal/agent/guardrail/model-routing-check` 校验 L3/L4 数据集禁止流向 `EXTERNAL` 端点并落安全审计。
- [x] **T1.3** `PgRlsIsolationTest` 纳入 `md_dataset_profile_run`，在真实 EmbeddedPostgres 上验证未设租户 0 行与跨租户隔离。

## Phase 2 — 确定性质量剖析（后端 + 工具）

- [x] **T2.1** 在 `dg-metadata` 实现 `DatasetQualityProfileService`、`DatasetProfileRun`、`DatasetProfileRunMapper` 与 `DatasetProfileCompletedEvent`：
  - 覆盖五维确定性剖析：空值率、单位/枚举异构占比、码表版本漂移、时间戳倒挂、基于 `Standard` 阈值的离群极值；
  - 强制 C2 校验（未绑定 `standardRef` 显式拒绝）与 C8 校验（样本不足或截断强制返回 `INCONCLUSIVE`）；
  - 计算稳定 `patternFingerprint` 与 `reproducibilityFingerprint` 并发布仅含聚合统计的解耦事件。
- [x] **T2.2** 暴露 API 与 Agent/MCP 工具：
  - `QualityController`：`POST /api/v1/metadata/quality/datasets/{id}/profile`、`GET /api/v1/metadata/quality/datasets/{id}/profiles`；
  - `AgentToolController`：`POST /api/v1/internal/agent/quality/datasets/{id}/profile`、`GET /api/v1/internal/agent/evolution/proposals`；
  - `agent-service/src/tools.ts` 与 `contracts/mcp/datagov-mcp.json` 注册 `profile_dataset_quality`、`list_evolution_proposals`、`render_filter_funnel`。

## Phase 3 — 宿主白名单声明式交互画布（Agent + 前端）

- [x] **T3.1** `agent-service/src/uiBlocks.ts` 与 `agent.ts` 扩展 SSE `event: ui_block`：严格白名单校验（仅允许 `FilterFunnelCard`、`DistributionProfileCard`、`RuleDiffProposalCard`），拒绝可执行 HTML/JS/Vue 载荷并附带单元测试 `uiBlocks.test.ts`。
- [x] **T3.2** 前端实现三张白名单组件与注册表渲染器（严守 `var(--dg-*)`，零硬编码色值，零 `ElMessageBox.confirm`）：
  - `FilterFunnelCard.vue`
  - `DistributionProfileCard.vue`（ECharts 读取 CSS 变量）
  - `RuleDiffProposalCard.vue`（接入演进提案审校/驳回防重提）
  - `AgentUiBlockRenderer.vue`
- [x] **T3.3** `AskView.vue`、`AgentDock.vue`、`types/agent.ts` 与 `api/evolution.ts` 接入 `ui_block` SSE 事件与组件渲染。

## Phase 4 — 受控演进闭环与回归门禁（后端 + 评测）

- [x] **T4.1** 在 `dg-governance` 实现 `EvolutionProposalService` 并扩展 `GovernedFact` / `GovernedFactService`：
  - 监听 `DatasetProfileCompletedEvent`（零 `dg-control` 依赖，符合 C1）；
  - 提提案前检索 DB `REJECTED` 与 `knowledge/maintenance-log.md` 驳回账本，命中指纹即静默抑制防重提；
  - 仅写入 `PROPOSED` 暂存态与 `research/` 草稿，物理禁止写入 `articles/`；
  - 暴露 `EvolutionProposalController`（`/api/v1/governance/evolution/proposals` 列表/创建/晋升/驳回/回滚）。
- [x] **T4.2** 补齐单元测试：`DatasetQualityProfileServiceTest`、`EvolutionProposalServiceTest`、`AgentToolControllerTest`（C5 模型路由护栏）与 `agent-service/src/uiBlocks.test.ts`。
- [x] **T4.3** 更新 `evals/regression/cases.json`、`evals/graders/deterministic.py` 与 `scripts/run-agent-evals.py`（补齐 `reg-17`、`reg-18`、`reg-19` 并修复 `reg-15` 门禁检查），全量跑通校验门禁。
