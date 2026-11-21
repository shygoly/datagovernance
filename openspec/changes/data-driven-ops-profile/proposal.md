# Proposal: 数据驱动自治领域包与持久化控制环（Data-Driven Ops Profile & Flow IR）

- **Change ID**: `data-driven-ops-profile`
- **Status**: Draft
- **Depends On**: `semantic-ontology-kernel`（`V36` 语义本体内核）、`smq-constrained-compile`（SMQ 确定性问数）、`V32__agent_action_proposal.sql`（宿主确认 UI）、`V33__agent_control_plane.sql`（Kill Switch）、`V40__reproducibility_fingerprint.sql`（审计指纹）、`V42__operational_report.sql`（运营报表）
- **Constitution Alignment**: 严格遵循 `CONSTITUTION.md`（C1–C10）、`SPACE-MODEL.md`、`GOVERNANCE-MODEL.md`、`AGENT-PRIMITIVES.md`（保持 P1–P9 + A0 顶层元原语彻底稳定，不新增 P10/P11）

---

## Why

平台已具备元数据目录、语义本体内核（`ENTITY / ATTRIBUTE / DIMENSION / FACT / METRIC / CONCEPT / PROCESS`）、确定性 SMQ 问数、运营报表（`OperationalReport`）、宿主独立确认提案（`ig_agent_action_proposal`）与智能体控制面（`Kill Switch`）。但要从“被动查数与离线治理平台”升级为**“带观测延迟的数据驱动自治系统（Data-Driven Ops）”**，仍存在五处结构性缺口：

1. **缺少业务目标与动作契约层（Target & ActionType）**：语义本体仅描述“数据是什么”，尚未声明“业务要守什么硬不变量（Invariant）、优化什么软目标（Objective）”，也未对动作建立统一的 L0–L3 分级授权、参数包络（Parameter Envelope）、Dry-Run 规则与配对补偿契约。
2. **缺少事件驱动的声明式控制环（Trigger & Flow IR）**：现有调度以 Cron 为主，缺乏基于语义指标阈值/异常/质检结果的声明式触发器，以及可静态校验、可跨引擎移植的持久化工作流中间表示（`Flow IR`）。
3. **缺少长事务与防震荡护栏**：数据闭环存在 L2 人工审批休眠、实体级并发冲突、控制环震荡与数仓 T+1 回流延迟，亟需实体在途锁（In-flight Lock）、冷却期（Cooldown）、多策略冲突裁决（Arbiter）与 Saga 补偿能力。
4. **缺少零副作用验证机制（Shadow Mode & Dry-Run）**：新策略缺乏强制影子模式与 T+1 延迟效果回检门禁，直接放开自动执行爆炸半径不可控。
5. **缺少端到端决策因果链（DecisionTrace）**：触发快照、冲突裁决、Dry-Run 预演、审批记录、执行结果与 T+1 归因回检分散在不同表中，无法复盘“为什么系统在当时做了这个动作，以及效果如何”。

本提案**不新增 P10/P11 元原语**，而是将上述能力定义为挂载在既有 **P1–P9 + A0** 骨架下的 **Data-Driven Ops Profile（数据自治领域包）**。

---

## Design Decisions（已通过 `/grill-me` 对齐的 6 项核心决策）

| # | 决策项 | 结论 |
|---|---|---|
| D1 | **本体归属与菜单信息架构（动静分层）** | 语义本体（`/metadata/semantic`）仅新增 **Layer 3 声明契约（`TARGET` + `ACTION_TYPE`）**；高频演进的动态控制环（`Trigger`、`Flow IR`、`Shadow/Cooldown`、`DecisionTrace`）独立成**“数据自治（Autonomy / Ops）”**菜单域，避免污染静态语义图谱。 |
| D2 | **Action 作用域与 C6 红线（分域渐进）** | 默认仅开放**平台内部自治动作**（调度暂停/重跑、发起质检/敏感扫描、生成/推送运营预警报表、推送治理收件箱，支持 L0–L2）；**外部业务系统写回**系统级默认关闭（严守 C6），未来仅按租户 + 白名单 Activity 显式豁免开放（强制 L2/L3 + 补偿）。 |
| D3 | **Flow IR 与执行引擎（IR + 双引擎 SPI）** | 保留轻量声明式 **`FlowSpec IR`**，抽象可插拔 **`DurableFlowEngine` SPI**：MVP 默认采用 **PostgreSQL 持久化状态机**（零新增运维中间件，满足 P4 私有化约束，原生支持审批唤醒、实体在途锁、冷却定时器、Saga 补偿与 T+1 回检），预留 Temporal / Restate 适配器。 |
| D4 | **Target 与冲突裁决层（两阶段确定性 Arbiter）** | ① `Target.Invariant` 硬不变量一票否决；② 同实体在途锁与冷却期互斥；③ 多策略冲突按声明式优先级规则表确定性裁决；超出规则覆盖的复杂权衡自动升级为 **L2 人工裁决**（附 A0 Agent 归因建议），绝不让 LLM 直接拍板高危冲突。 |
| D5 | **Shadow Mode 与 T+1 回检（强制灰度晋升）** | 所有新建/变更策略强制经历 **`SHADOW → L2_ASSISTED → L1_AUTO`** 三阶段灰度：默认以 `SHADOW` 启动（完整跑 Trigger / Arbiter / Dry-Run / DecisionTrace 但不落副作用），经 T+1 延迟回检与人工确认后方可晋升；与 `V33` Kill Switch 打通，一键全局降级回 `SHADOW`。 |
| D6 | **MVP 垂直切片** | 首期以**“数据治理自治闭环”**跑通端到端验证：语义指标/质检异常 `Trigger` → `Target + Arbiter` 判定 → `Shadow / Dry-Run` 预演 → L2 宿主审批暂停异常同步任务 / 发起定向质检 / 生成预警 `OperationalReport` → `DecisionTrace` + T+1 延迟效果回检。 |

---

## P1–P9 + A0 领域映射（Domain Projection）

| 领域包组件 | 挂载原语槽位 | 所在 Space |
|---|---|---|
| `Target`（硬不变量 + 软目标） | P2 Context + P8 Verify | `dg-metadata`（Layer 3 语义对象） |
| `ActionType`（L0–L3 分级、参数包络、Dry-Run、补偿契约） | P3 Logic + P7 Security | `dg-metadata`（定义态）+ `dg-governance`（鉴权） |
| `FilterPreset`（可复用命名过滤器） | P2 Context | `dg-metadata` |
| `TriggerSpec` + `FlowSpec IR` + `Arbiter` | P4 Flow + P8 Verify | `dg-control`（编译校验、规则裁决、调度触发） |
| `DurableFlowEngine`（PG 状态机 / Temporal SPI）+ 原子 Activity | P4 Flow + P5 Protocol | `dg-runtime`（持久化状态机与无状态执行器） |
| `Shadow Mode` / `Cooldown` / `In-flight Lock` / `Kill Switch` 联动 | P9 Operate + P7 Security | `dg-control` + `dg-governance` |
| `DecisionTrace`（上下文快照 + 因果链 + T+1 回检） | P9 Observe / State | `dg-governance` |
| 归因建议、冲突解释、T+1 效果复盘与 IR 草案生成 | A0 Agent 基座 | `agent-service`（受控于 P1–P9 确定性护栏） |

---

## Non-goals（明确不做）

- **不新增 P10/P11 顶层元原语**，不破坏现有 8 Space 依赖无环拓扑与 7 治理原语正交性。
- **不在 MVP 阶段强制引入 Temporal / Restate 外部集群**，保持医院/信创私有化单 PostgreSQL 零新增中间件交付能力。
- **不开放外部医疗业务系统（HIS/EMR）的通用写回**（坚守 `CONSTITUTION.md` C6）。
- **不允许 Agent 直接裸调底层业务 API 或编写可执行工作流代码**：Agent 仅可生成受 JSON Schema 约束的 `FlowSpec IR` 草案或提供归因建议，所有执行必须经过 `FlowCompiler` 静态校验、参数包络、Arbiter 与 `DurableFlowEngine`。
- **不将 `Trigger`、`Flow IR` 或运行态日志塞进 `md_semantic_object` 静态本体表**。

---

## Constitution Check（C1–C10）

| 公理 | 检查项与合规设计 |
|---|---|
| **C1 Space 边界** | 定义态（`Target`/`ActionType`/`FilterPreset`）归 `dg-metadata`；编译/裁决/触发归 `dg-control`；持久化状态机与原子 Activity 归 `dg-runtime`；`DecisionTrace`/审计/Kill Switch 归 `dg-governance`；严守 Maven 无环依赖。 |
| **C2 治理为编译输入** | `FlowCompiler` 在编译 `FlowSpec IR` 时强制校验绑定 `Target`/`ActionType` 存在性、L2/L3 补偿契约完整性、冷却期与参数包络合法性。 |
| **C3 确定性计算优先** | 指标触发求值复用确定性 SMQ 编译器；硬不变量校验、参数包络与 Arbiter 裁决全部确定性执行，LLM 仅做归因辅助。 |
| **C4 零信任与最小代理** | 触发执行、L2 审批唤醒与原子 Activity 调用均绑定真实 `UserContext` / 策略责任人委派身份，严禁共享超级账号。 |
| **C5 纵深隔离与分级路由** | 所有自治表强制 `workspace_id` + PG RLS 兜底；`DecisionTrace` 上下文快照仅存聚合指标与脱敏引用，严禁落盘 L3/L4 原始明细。 |
| **C6 克制写回** | 平台内自治动作先行，外部写回系统级默认关闭并需白名单显式豁免；所有写动作收口至 `DurableFlowEngine` 唯一受控通道。 |
| **C7 宿主掌握画盘与高危确认** | L2/L3 审批复用宿主独立确认视图（`AgentConsentDialog` / 治理收件箱），执行瞬间二次核验参数摘要与权限（Recheck at Execution）。 |
| **C8 证据与 INCONCLUSIVE** | 触发样本不足、Dry-Run 依赖缺失或 T+1 回流未就绪时，回检与归因显式落入 `INCONCLUSIVE`，并绑定 `reproducibility_fingerprint`。 |
| **C9 受控演进** | 策略必须从 `SHADOW` 经 T+1 实证与人工审校晋升，不自动提升授权等级；历史 `FlowSpec` 版本只追加不覆写。 |
| **C10 确定性与概率解耦** | 概率性 Agent 节点严格隔离在 `Flow IR` 的受控推理 Activity 边界内，绝不嵌入确定性编排主循环。 |
