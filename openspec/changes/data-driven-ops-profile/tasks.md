# Tasks: Data-Driven Ops Profile & Flow IR（`data-driven-ops-profile`）

> 实施原则：严格遵循 `CONSTITUTION.md`（C1–C10）与 P1–P9 + A0 稳定性约束；按 **Phase 1（数据治理自治 MVP 垂直切片） → Phase 2（影子回测、补偿与扩展动作） → Phase 3（高级引擎 SPI 与 A0 协同）** 分阶段交付。

---

## Phase 1：语义契约 Layer 3 + Flow IR + PG 持久化状态机（数据治理自治 MVP）

### 1.1 数据库与语义本体 Layer 3 扩展
- [x] **P1-1** 新增 Flyway 迁移脚本（承接最新版本号），创建 `au_filter_preset`、`au_trigger_def`、`au_flow_def`、`au_flow_version`、`au_flow_instance`、`au_entity_lock_cooldown`、`au_decision_trace`，并全部启用 `workspace_id` + PostgreSQL RLS 策略（C5）。
- [x] **P1-2** 扩展 `dg-metadata` 的 `SemanticOntologyService`：支持 `TARGET`（硬不变量 + 软目标）与 `ACTION_TYPE`（L0–L3 分级、参数包络、Dry-Run、补偿契约、`domainScope`）的 CRUD、发布校验与语义图谱关联；新增 `FilterPresetService`。
- [x] **P1-3** 更新 `docs/space-contracts/`（`metadata.yaml`、`control.yaml`、`runtime.yaml`、`governance.yaml`）登记新增核心对象，确保 `python3 docs/space-contracts/verify.py` 通过。

### 1.2 FlowSpec IR 编译器、Arbiter 与 Trigger
- [x] **P1-4** 在 `dg-control` 定义 `FlowSpec IR`（`flow.ir/v1`）模型与 `FlowCompiler`：实现存在性闸门、C6 `domainScope` 拦截、参数包络/补偿完整性校验、冷却期必配校验与默认 `SHADOW` 启动门禁。
  - `paramEnvelope.maxExecutionsPerDay` 已落地：`V51__autonomy_action_rate_limit.sql` + `ActionRateLimitService` 原子日计数；`InvokeActivityHandler` 在真实副作用前消费额度，超限失败并回退本次自增，执行失败时释放额度。
- [x] **P1-5** 实现两阶段确定性 `ArbiterService`：硬不变量（Invariant）一票否决 → 实体在途锁与冷却期互斥 → 声明式优先级规则表裁决 → 超规则冲突自动升 L2 人工裁决。
- [x] **P1-6** 实现 `TriggerEvaluationService`：支持基于 SMQ 语义指标阈值与质检失败事件的声明式触发（含去抖窗口）。

### 1.3 `DurableFlowEngine` SPI 与 PG 状态机实现
- [x] **P1-7** 在 `dg-runtime` 定义 `DurableFlowEngine` SPI，并实现默认后端 `PgDurableFlowEngine`（Checkpoint 持久化、实体在途锁、审批信号挂起/唤醒、冷却定时器、超时处理）。
- [x] **P1-8** 实现首批 3 个平台内无状态原子 Activity（带 `Dry-Run` 与配对补偿）：
  - `control.job.pause` / `control.job.resume`（暂停/恢复异常同步任务）；
  - `metadata.quality.run`（发起定向质检扫描）；
  - `governance.report.generate`（生成并推送预警 `OperationalReport`）。
- [x] **P1-9** 复用 `V32` `AgentActionProposalService` 与治理收件箱完成 L2 宿主独立确认唤醒闭环，并在执行瞬间做二次鉴权与参数摘要校验（C7）。

### 1.4 DecisionTrace 与 MVP 前端页面
- [x] **P1-10** 在 `dg-governance` 实现 `DecisionTraceService`（串联触发快照、Arbiter 裁决、Dry-Run、审批、执行结果与 `reproducibility_fingerprint`），并与 `V33` Kill Switch 打通（急停一键将 `L1_AUTO` 降级回 `SHADOW`）。
- [x] **P1-11** 前端在 `/metadata/semantic` 新增 Layer 3（`Target` / `ActionType`）管理视图；新建 `/autonomy/triggers`、`/autonomy/flows`、`/autonomy/traces` 页面（严守 `--dg-*` Token 与宿主白名单组件）。

---

## Phase 2：Shadow Mode T+1 延迟回检、Saga 补偿与灰度晋升

- [x] **P2-1** 实现 `DelayedVerifyScheduler`（T+1 数据回流后自动唤醒 `WAITING_VERIFY` 实例，复查 `Target` 指标变化，产出 `IMPROVED / NO_EFFECT / DEGRADED / INCONCLUSIVE` 结论）。
  - 自动观测源：`FlowVerifyObservationProvider` SPI + `EtlQualityVerifyObservationProvider` 订阅 `EtlExecutionCompleted` / `QualityRunCompleted`；引擎 sweep 时把 after-metrics 注入 `VERIFY_DUE`，`AutonomyHttpE2ETest` 不调用手工 signal 即验证 IMPROVED 与 DEGRADED。
- [x] **P2-2** 实现 `ShadowEvaluator` 与灰度晋升审批流（`SHADOW → L2_ASSISTED → L1_AUTO`），附带影子回测对比面板。
- [x] **P2-3** 实现 `PgDurableFlowEngine` 的 Saga 逆序补偿执行与补偿失败告警升级（推送治理收件箱）。

---

## Phase 3：外部引擎适配器与 A0 Agent 深度协同

- [x] **P3-1** 提供可选的 `TemporalFlowEngineAdapter`（或 Restate Adapter）插件模块，通过同一 `FlowSpec IR` 与 `DurableFlowEngine` SPI 零改动切换高阶分布式工作流后端。
  - 当前状态：**Restate HTTP ingress 适配器** `RestateFlowEngineAdapter` 已落地（`dg.autonomy.engine=restate`），通过 `/restate/send|output|call` 调用约定的 workflow service；`RestateFlowEngineAdapterTest` 用本地 HttpServer 证明启动/信号/取消/查询/批量取消契约。`TemporalFlowEngineAdapter` 仍是 fail-fast 占位。真实 Restate 集群 + 真实 workflow service 未跑，契约见 `docs/autonomy-restate-adapter.md`。
- [x] **P3-2** 接入 A0 Agent 受控能力：复杂多目标冲突时生成只读归因建议（不直接拍板）、支持基于自然语言生成 `FlowSpec IR` 草案（必须经 `FlowCompiler` 校验且默认落 `SHADOW`）、补充 `/evals/` 离线 `pass^3` 轨迹回归用例。
- [x] **P3-3** 在 `AGENT-PRIMITIVES.md` / `ARCHITECTURE.md` 补充 `Data-Driven Ops Profile` 领域包索引章节，保持 P1–P9 + A0 顶层文档一致。

---

## Verification & Acceptance Gates

- [x] 元语与防漂移校验：`python3 scripts/verify-primitives.py`（2026-11-21 PASSED，8/8 契约、6 个技能包）
- [x] Space Contract 校验：`python3 docs/space-contracts/verify.py`（2026-11-21 PASSED，35/35 功能项、72 种事件）
- [x] 后端编译与全量测试：`mvn -o -pl backend/dg-app -am test`（2026-11-21，JDK 21，955 tests / 0 failures；含 `PgRlsIsolationTest` 对 V50/V51 八张 au_* 表的默认拒绝/跨租户写拒绝、`FlowCompilerTest`、`ArbiterServiceTest`、`TriggerEvaluationServiceTest`、`PgDurableFlowEngineTest`、`ShadowEvaluatorTest`、`DecisionTraceServiceTest`、`AutonomyFlowServiceTest`、`AutonomyHttpE2ETest`（真实 EmbeddedPostgres + HTTP：登录→Layer 3 发布→Flow 编译→Trigger→Arbiter→SHADOW→ETL 完成事件自动提供 after-metrics→IMPROVED/DEGRADED→晋升 L2→审批提案；不发送手工 VERIFY_DUE）、`EtlQualityVerifyObservationProviderTest`、`InvokeActivityHandlerTest`、`ActionRateLimitServiceTest`、`EtlPreviewMaskingTest`（ETL 预览按 UserContext grant 放行/脱敏））
- [x] 前端类型检查与生产构建：`cd frontend && pnpm type-check && pnpm build`（2026-11-21 通过，含 3 个自治页面与 Semantic Layer 3 页签）
- [x] Agent 评测回归（Phase 3）：`cd agent-service && npx tsc --noEmit` 及 `python3 scripts/run-agent-evals.py --suite regression --trials 3 --mode offline`（2026-11-21，28/28 PASSED，pass^3=1.0）


---

## 实施证据与诚实剩余（2026-11-21）

### 已落地

- **V50**：`au_filter_preset` / `au_trigger_def` / `au_flow_def` / `au_flow_version` / `au_flow_instance` / `au_entity_lock_cooldown` / `au_decision_trace` 全部建表 + RLS；`PgRlsIsolationTest` 已把 7 张表纳入真实 EmbeddedPostgres 的默认拒绝/跨租户写拒绝验证。
- **Layer 3**：`SemanticOntologyService` 支持 `TARGET` / `ACTION_TYPE`，发布时校验 `targetEntityRef`、metric 引用、L0–L3 授权、参数包络、Dry-Run 与配对补偿；`FilterPresetService` + `/api/v1/metadata/filter-presets`。
- **Control**：`FlowSpec IR (flow.ir/v1)`、`FlowSpecParser`、`FlowCompiler`（存在性/C6/包络/补偿/防震荡/动作前置不变量）、`FlowDefinitionService`（版本化 + SHADOW 强制 + 受控晋升）、`ArbiterService`（硬不变量 → 锁/冷却 → priority 确定性规则 → 冲突升级 L2）、`TriggerDefinitionService` / `TriggerEvaluationService`（指标、质检、ETL、到达延迟 + 去抖）。执行期 `ActionParameterValidator` 对 `paramEnvelope` 的 required/类型/数值界/枚举/pattern 再校验一次。
- **Runtime**：`DurableFlowEngine` SPI + `PgDurableFlowEngine`（checkpoint、current_step、wake_at、审批/回检唤醒、Saga 逆序补偿、实体锁冷却）、`FlowActivity` SPI + 4 个原子 Activity（暂停/恢复同步、定向质检、运营报表）。
- **Governance**：`DecisionTraceService`（触发快照/裁决/Dry-Run/审批/执行/回检/指纹）、`ShadowEvaluator`（按 Target.objectives 比较，证据不足 INCONCLUSIVE）；失败/补偿失败实例与待审批提案已投影到治理收件箱。
- **装配闭环**：`AutonomyFlowService` 串起 Trigger → Arbiter → DurableFlow → Trace；ETL `EtlExecutionCompleted` 与 `QualityRunCompleted` 已接入 Trigger 运行时；L2 审批复用 V32 `ig_agent_action_proposal` + 宿主确认；Kill Switch 联动 V33 并取消在途实例。
- **前端**：`/autonomy/triggers`、`/autonomy/flows`、`/autonomy/traces`，语义本体新增 Layer 3 页签；`pnpm type-check` 与 `pnpm build` 通过。
- **A0 Agent**：`autonomy_compile_flow` / `autonomy_trace_explain` 只读工具 + `skills/autonomy-flow-draft/SKILL.md` + `reg-26~28` 轨迹用例；离线 `pass^3=1.0`（28/28）。

### 诚实剩余

1. **P3-1**：`RestateFlowEngineAdapter` 已实现并有 mock ingress 契约测试，但未在真实 Restate 集群 + 真实 workflow service 上联调；`TemporalFlowEngineAdapter` 仍为 fail-fast 占位。PG 默认实现仍是唯一经过完整真库/HTTP 端到端测试的运行后端。
2. **真实 PostgreSQL + HTTP 端到端**：`AutonomyHttpE2ETest` 已覆盖 Trigger → Arbiter → SHADOW 实例 → T+1 回检 → 晋升 L2 → L2 审批提案；仍缺真实外部 Restate/Temporal 集群、真实审批确认后执行 Activity 的成功路径（当前测试到审批待办为止，避免依赖外部报表产物）。
3. **浏览器手测**：三个自治页面只做了 `vue-tsc` 与生产构建，未做浏览器交互手测；`/workbench/inbox` 已投影 `AUTONOMY`（失败/补偿失败实例）与 `AUTONOMY_APPROVAL`（V32 待审批提案）两类来源，但未做真实浏览器点击验收。
4. **T+1 数据回流**：ETL / 质检完成事件已通过 `FlowVerifyObservationProvider` 自动提供 after-metrics；运营报表与其他数仓指标源尚未实现 Provider，仍需通过 `POST /api/v1/autonomy/instances/{id}/signal` 手工提交。
5. **SMQ 内嵌**：`METRIC_THRESHOLD` 由上游 SMQ/指标快照提供 `metrics`，Trigger 层未内嵌 SMQ 编译器（避免把问数编译链拉进定时触发路径）；`maxExecutionsPerDay` 的跨实例日计数已由 V51 + `ActionRateLimitService` 落地。
