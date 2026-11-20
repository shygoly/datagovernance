# 智能体共性基座与“9+1”融合规约 · AGENT-PRIMITIVES.md

> 地位：本仓库在既有 **8 Space 过程元语（`SPACE-MODEL.md`）** 与 **7 治理元语（`GOVERNANCE-MODEL.md`）** 基础上，吸收“9+1”系统元语架构优势后形成的 **横切智能体共性认知与演进基座（A0）** 及 **全生命周期护栏工具链（T1–T5）** 规约。
> 设计原则：**向后兼容、不另起第 9 个 Space、坚守医疗私有化克制写入底线**。

---

## 1. 为什么引入 A0，而不把智能体平铺成新 Space

当前平台已存在四类智能体触点：
1. **内置问数与引导式运维 Agent**：`agent-service/`（Pi Agent sidecar，已实现 `/insight/ask` 与本地 Parquet 接入引导）；
2. **外部治理质检/归因 Agent**：`dataQA`（经 `INTEGRATION-ROADMAP.md` 与 `contracts/` 对接）；
3. **外部分析结论 Agent**：`DeepAnalyze`（经 `docs/analysis-report.md` 写入不可变 `AnalysisReport`）；
4. **远期医学智能域**：`Intelligence Space`（序号 34，独立立项，Contract-only）。

沿用“9+1”架构的三条正交判定准则（确定性边界、资产所有权、合规与审计责任），全部智能体能力被严格分流为两类：

- **原地融合至既有 Space / Governance 元语（不建平行烟囱）**：
  - 语义检索 / 指标语义 / 知识图谱 → 挂载于 `Metadata Space` + `Governance Fact` 统一检索面；
  - 受控写动作与流程编排 → 挂载于 `Metadata`（定义注册）+ `Control`（确定性编译/调度）+ `Runtime`（受控执行）；
  - 智能体身份、鉴权、脱敏与审计 → 挂载于 `Platform Space` + `Governance Space`（人机共用同一权限与审计轨道）；
  - 生成式 UI、页面导览与高危确认视图 → 挂载于 `UI Space`（`frontend/DESIGN.md` 宿主掌控组件调色盘）。
- **抽离为横切共享的智能体共性基座 `A0`（A0.1–A0.4）**：
  - 凡具备概率推理、上下文渐进装配、跨会话经验蒸馏、记忆防投毒与轨迹评测共性的能力，统一由 `A0` 规范，避免每个 Agent 重复发明私有提示词拼接、文件记忆与评测脚本。

---

## 2. “9+1”元语架构 ↔ 本项目元语体系全景映射

| 9+1 元语 | 本项目既有对应 | 吸收的增量增强 | 刻意裁剪 / 保留差异 |
|---|---|---|---|
| **P1 边界与宪法** | `SPACE-MODEL.md §0/§A/§G`、`ARCHITECTURE.md` | 新增根目录 `/CONSTITUTION.md`（10 条公理）+ `≤100行 /AGENTS.md` 导航地图 | 保留 `Space` vs `Workspace` 严格术语纪律 |
| **P2 数据与语义** | `Metadata Space` + `GOVERNANCE-MODEL`（`Asset/Fact/Standard/Lineage`）+ SuperSonic | 明确统一 `Fact` 检索面的**检索前鉴权**与自适应混合路由（指标走语义层、条款走带生效期预过滤混合检索、多跳才引入图谱） | 保持 SuperSonic 可替换，不强绑定单一商业语义引擎 |
| **P3 动能与逻辑** | `GOVERNANCE-MODEL`（`Rule` 三步编译）+ 受控写入口（`create_quality_task`/`AnalysisReport`/onboarding） | 对**既有受控写动作**统一补齐 `Governed Action` 契约（入参 Schema、前置校验、幂等键、副作用声明、审计） | **刻意不做 Palantir 式临床/业务库通用写回**（坚守 `GOVERNANCE-MODEL §K` 与 `C6`） |
| **P4 流程与编排** | `Control Space`（Workflow Compiler / DAG / Cron / 批流分离）+ `Runtime Space` | 固化“确定性编排与概率性推理节点严格解耦”铁律；区分瞬时重试与语义重规划（Raise-and-Replan） | **不引入 Temporal**（遵循 `SPACE-MODEL §L` 医疗私有化低运维约束） |
| **P5 接口与协议** | REST `/api/v1` + `contracts/openapi` + Kafka `dg.*` + `/api/v1/internal/agent/*` | 定义协议演进路径：内部工具逐步对齐 **MCP** 契约；跨系统 Agent（`dataQA`/`DeepAnalyze`）对齐 **A2A** 任务卡片语义；前端 SSE 对齐 **AG-UI** 事件模型 | 现阶段保持 `agent-service` 与 Java 内部端点向后兼容，渐进演进 |
| **P6 设计与交互** | `UI Space` + `frontend/DESIGN.md` + `tokens.css`（Governed Calm） | 增加前端 Token 字面量漂移机械检查；确立**宿主掌控调色盘**与**高危操作宿主独立确认 UI**（参考 A2UI 声明式白名单原则） | **坚持 Vue 3 + Element Plus**，不迁移 Tailwind/shadcn |
| **P7 身份与安全** | `Platform Space` + `Governance EvaluateAccess` + 无服务账号内核 | 补齐 **OWASP ASI01–10** 对照基线、NHI 登记与 Kill Switch、修复 `AgentToolController` 问数端点用户实时校验缺口、规划 PostgreSQL RLS 纵深兜底 | 保持对接院方统一认证（OIDC/CAS/LDAP）前提 |
| **P8 运行态护栏** | Maven 物理依赖隔离 + `docs/space-contracts/verify.py` + `openspec/` | 升级为 `scripts/verify-primitives.py` 综合防漂移门禁（Linter 输出即 Agent 修复指令），确立季度 `Build-to-Delete` 护栏精简机制 | 保留既有 Maven 编译期强制依赖边界（项目核心优势） |
| **P9 可观测审计** | `Governance Space`（统一 `Execution`、监控、告警、`AuditRecord`、`AnalysisReport`） | 规划接入 OpenTelemetry `gen_ai.*` 语义规约（版本锁定、生产默认关闭原始 Prompt 采集）与预算/调用深度控制面熔断 | 保留 `INCONCLUSIVE` 一等终态与不可变 `AnalysisReport` |
| **A0 智能体基座** | 分散于 `agent-service/`、dataQA `case` fact、`evaluate-supersonic-50.py` | **全面吸收 A0.1–A0.4**，统一纳管记忆/编译知识、渐进式技能、受控自演进闭环与 `pass^k` 轨迹评测 | 与 `GOVERNANCE-MODEL` 的 `Fact` 和 `Learn` 闭环直接合流 |

---

## 3. 智能体共性基座 A0 详细规约（A0.1–A0.4）

### A0.1 分层记忆与编译型知识（CoALA Memory + llmWiki / Bi-Temporal Fact）
将平台零散的会话文件（`agent-service/data/agent/`）与 `GOVERNANCE-MODEL` 的 `Fact` 统一升级为分层记忆模型：

| 记忆层 | 承载对象 | 平台落点与约束 |
|---|---|---|
| **Working（工作记忆）** | 当前多轮对话上下文与工具中间结果 | `agent-service` 会话态（多副本前迁 PostgreSQL/Redis，保留稳定静态系统前缀以保提示词缓存） |
| **Episodic（情景记忆）** | 历史失败归因案例、成功排障轨迹 | `Fact(fact_type=case)` + `AnalysisReport`（带 `evidence` 与 `INCONCLUSIVE` 终态） |
| **Semantic（语义记忆）** | 数据资产结构、业务术语、标准口径、分级与编译型领域 Wiki | `Metadata Catalog` + `Business Glossary` + `Standard` + 可选编译型知识库（`raw/ → research/ → articles/`，附 `index.md` / `log.md` / `maintenance-log.md`） |
| **Procedural（过程记忆）** | 多步骤 SOP 与工作流指导规程 | `A0.2` 技能包（`skills/<name>/SKILL.md`），不再硬编码于 `agent.ts` 的 `systemPrompt()` |

**双时间态演进规则**：所有 `Fact` 与知识条目遵循只追加演进（`valid_from` / `valid_to` / `supersedes`），旧口径、旧分级、旧标准永不物理覆写，确保医疗与审计场景可还原“任意历史时间点生效的口径”。

### A0.2 渐进式技能封装（`SKILL.md` / AgentSkills.io 标准）
解决当前 `agent-service/src/agent.ts` 将特定业务流程（如 9 步 Parquet 接入引导）硬编码在全局 `systemPrompt()` 中导致的上下文膨胀与维护困难：

1. **Level 1 元数据常驻**（约 30–100 Tokens/技能）：仅注入 `name`（kebab-case，≤64 字符，与目录名一致）和 `description`（≤1,024 字符，明确触发条件）。
2. **Level 2 规程按需加载**（建议 ≤200 行，硬上限 500 行）：仅当用户意图命中时载入步骤指令、异常处理与 `allowed-tools` 最小作用域白名单（严禁声明裸 Shell / 通配写工具）。
3. **Level 3 附属资源按需读取**：脚本（`scripts/`）、参考规范（`references/`）与模板（`assets/`）留在磁盘按需触发。
4. **首批迁移示范**：仓库已将 Parquet 接入流程抽取为首个规范化技能包 [`skills/parquet-local-onboarding/SKILL.md`](skills/parquet-local-onboarding/SKILL.md)，并纳入 `scripts/verify-primitives.py` 自动校验。

### A0.3 受控自我演进与知识沉淀闭环（升级 `GOVERNANCE-MODEL` 的 `Learn`）
为防止 `GOVERNANCE-MODEL.md §E` 的 `Learn` 环节退化为“直接让模型总结并覆写知识库/提示词”（导致上下文崩溃与 ASI06 记忆投毒），强制实施六阶段增量闭环：

```text
[1] 执行与观测（Execution / InsightQuery / Attribution）
       ↓  前置硬条件：必须存在可靠外部反馈（SQL 核对 / 测试通过 / 人工纠偏；纯闲聊禁止触发）
[2] 反思提取（Reflector）—— 提取紧凑结构化增量洞见（Delta），不重写全文
       ↓
[3] 暂存隔离（Staging）—— 落盘至 research/ 临时区或候选 Fact(state=PROPOSED)
       ↓
[4] 机械校验（Verifier）—— 检查引用 Asset/Standard 存在性、敏感级别（严禁夹带 L3+ 原始行）、断链与冲突
       ↓
[5] 人工 Diff 审校（Human Gate）—— 以 Git Diff / 治理收件箱裁决采纳、拒绝或延缓，并记入 maintenance-log
       ↓
[6] 版本晋升与回滚（Canonical + Rollback）—— 晋升至 articles/ 或已发布 Fact/Skill，保留游标快照随时撤销
```

### A0.4 认知护栏与轨迹评测（升级 `evaluate-supersonic-50.py`）
将当前一次性的 50 题问数评测升级为平台内建评测元语（`/evals/`）：

- **区分四要素**：`Task`（任务）→ `Trial`（独立试验）→ `Trajectory`（完整推理与工具轨迹）→ `Outcome`（真实后端/数据库终态，不采信模型口头声称“已完成”）。
- **三层裁判**：
  1. **代码确定性裁判优先**：校验数据库落库状态、脱敏列掩码、SQL 白名单、越权 403/404 与权限隔离；
  2. **独立上下文模型裁判**：仅用于开放式总结质量，严禁复用被测 Agent 会话上下文；
  3. **领域专家抽检**：用于校准高风险医疗/治理归因场景与 `INCONCLUSIVE` 边界。
- **核心门禁指标**：
  - 开发探索看 `pass@k`；
  - 生产上线与 CI 回归必须以 **`pass^k`**（连续 $k$ 次独立试验全部通过）与 **Token/延迟预算**为红线。

---

## 4. OWASP Agentic Top 10 (2026) 在本项目的防御矩阵与现存缺口

基于代码实查（`AgentToolController.java`、`agent-service/src/tools.ts`、Flyway `V1–V31`），逐项锁定防御坐标与待补齐项：

| OWASP 2026 | 风险名称 | 本项目防御坐标 | 现状与优先补齐动作 |
|---|---|---|---|
| **ASI01** | 目标劫持 | `CONSTITUTION C3/C6` + 工具白名单 | 外部查询结果与表注释均视为不可信数据，严禁拼入系统指令；后续隔离数据载荷与指令边界 |
| **ASI02** | 工具滥用 | `agent-service/src/tools.ts` + `SKILL.md allowed-tools` | ✅ 已落地：TypeBox 参数校验 + 运行时 `isToolPermittedBySkills` 写工具门禁 + `dg.insight.local-root-allowlist` 规范化目录白名单与防 `..` 穿越 |
| **ASI03** | 身份与权限滥用 | `Platform Space` + `AgentToolController` + `V35` PG RLS | ✅ 已落地：`/datasets`、`/ask` 与全部 onboarding/proposal 端点统一按 `userId + workspaceId` 实时校验成员资格与权限，已彻底删除合成 `Caller` 重载；`V35` 对 8 张核心表启用 PostgreSQL RLS 纵深兜底 |
| **ASI04** | 智能体供应链 | `pom.xml` + `skills/` + `verify-primitives.py` | ✅ 已落地：`skills/*/SKILL.md` Frontmatter/行数/`allowed-tools` 白名单自动校验；`contracts/mcp` & `contracts/a2a` 契约校验纳入 CI |
| **ASI05** | 意外代码执行 | `CONSTITUTION C3` + `dg-data-spi` | ✅ 已落地：LLM 不直出可执行代码；未来若启用沙箱执行（如 Python 节点/DeepAnalyze），强制零生产凭证 + 默认禁外网 |
| **ASI06** | 记忆与上下文投毒 | `A0.1` + `A0.3` + `GovernedFactService` + `knowledge/` | ✅ 已落地：强制外部反馈信号 + L3 敏感信息拦截 + `PROPOSED` 暂存审校 + 双时间态 `SUPERSEDED` + `rollback` 一键恢复前驱版本 |
| **ASI07** | 不安全智能体通信 | `contracts/` + `INTEGRATION-ROADMAP` | ✅ 已落地：`agent-service` 限绑定 `127.0.0.1`；MCP 端点复用平台 JWT 与 `WorkspaceContext`；A2A 契约卡片落盘 `contracts/a2a/` |
| **ASI08** | 级联故障 | `AgentControlPlaneService` + `agent-service/src/agent.ts` | ✅ 已落地：单轮最大工具深度上限、连续相同错误熔断（Raise-and-Replan）与租户每日轮次配额 429 熔断 |
| **ASI09** | 人机信任利用 | `AgentActionProposalService` + `AgentConsentDialog.vue` | ✅ 已落地：全部 Agent 写工具仅生成 `PENDING` 提案并推送 `consent_required`，由宿主独立弹窗展示不可篡改参数与 SHA-256 摘要，经后端实时二次核验后执行 |
| **ASI10** | 失控智能体 | `AgentControlPlaneService` + `/control/abort-all` | ✅ 已落地：全局与空间级 `Kill Switch`（`V33`），开启后立即拒绝新消息并回调中止在途会话，受 `governance:agent:manage` 权限与审计保护 |

---

## 5. 五阶段元语工具链（T1–T5）与三阶段吸收路线

### 5.1 工具链映射（贴合本项目技术栈）

| 阶段 | 目标 | 本项目当前落点 | 增量补齐抓手 |
|---|---|---|---|
| **T1 存量发现** | 逆向补齐元语与目录映射 | `SPACE-MODEL.md`、`frontend/DESIGN.md`、`docs/function-space-matrix.md` | 保持 `AGENTS.md` 随模块目录同步；前端视觉回归沿用 `scripts/verify-ui.mjs` / `capture-ui-flow.mjs` |
| **T2 规约建模** | 宪法 + 轻量增量规约 + 技能校验 | `openspec/`（已落地！）+ 新增 `CONSTITUTION.md` | 在 `openspec/config.yaml` 绑定 Constitution Check；`skills/*/SKILL.md` 纳入统一校验 |
| **T3 防漂移门禁** | 编译期 + CI 机械拦截漂移 | Maven 依赖防环 + `docs/space-contracts/verify.py` + 新增 `scripts/verify-primitives.py` | 接入 GitHub Actions / GitLab CI 自动阻断；增配 OpenAPI Spectral Lint 与前端 Token 严查 |
| **T4 知识与检索** | 语义指标 + 统一 `Fact` + 编译知识 | `Catalog` + `Glossary` + `Lineage` + SuperSonic | 统一 `Fact` 检索前 ACL 过滤；落地 `knowledge/raw → research → articles` 编译管线 |
| **T5 观测与控制面** | 追踪 + 轨迹评测 + 预算熔断 | `AuditService` + `ig_query_log` + `evaluate-supersonic-50.py` | 引入 OTel `gen_ai.*` 埋点归一化、`/evals/` `pass^k` 回归套件与租户级调用/预算熔断器 |

### 5.2 三阶段渐进吸收路线

> 完整可执行计划、任务拆分、数据库迁移规划、依赖图与可证伪验收口径见：[`docs/plans/2026-11-19-primitives-9plus1-absorption.md`](docs/plans/2026-11-19-primitives-9plus1-absorption.md)。

1. **Phase A（✅ 已完成）：宪法、导航与机械门禁闭环**
   - ✅ 落地 `CONSTITUTION.md`、`AGENTS.md`、`AGENT-PRIMITIVES.md`、`skills/parquet-local-onboarding/SKILL.md`、`scripts/verify-primitives.py`、`openspec/config.yaml`；
   - ✅ 补齐 `AgentToolController` `/datasets` 与 `/ask` 的 `userId` 实时鉴权一致性并删除合成 `Caller` 重载；
   - ✅ 清零前端 4 处硬编码色值，上线 `.github/workflows/primitives-guard.yml` 严格 CI 门禁。
2. **Phase B（✅ 已完成）：Agent 安全与技能运行时化**
   - ✅ `agent-service` 实现 `skills.ts` Level 1/2 渐进披露与 `allowed-tools` 运行时门禁，淘汰 `agent.ts` 硬编码流程提示词并补齐 `skills/insight-data-ask/SKILL.md`；
   - ✅ 落地 `V32__agent_action_proposal.sql`、`AgentActionProposalService`、`AgentProposalController` 与前端 `AgentConsentDialog.vue` 宿主独立确认视图；
   - ✅ 落地 `V33__agent_control_plane.sql`、`AgentControlPlaneService`、`AgentControlController`、单轮深度/连续错误熔断、路径白名单与 Kill Switch（含 `/control/abort-all`）；
   - ✅ 落地 `/evals/`（`regression` + `capability` + `graders/`）与 `scripts/run-agent-evals.py` `pass^3` 轨迹回归门禁。
3. **Phase C（✅ 已完成）：协议标准化与受控自演进**
   - ✅ 落地 `McpServerController`（`/api/v1/mcp`）、`AgentCardController`（`/.well-known/agent-card.json`）、`contracts/mcp/`、`contracts/a2a/` 与版本锁定 `1.42.0` 的双端 OTel `gen_ai.*` 遥测（默认关闭原始 Prompt 采集）；
   - ✅ 落地 `V34__bitemporal_fact_and_staging.sql`、`GovernedFactService`、`GovernedFactController` 与 `knowledge/{raw,research,articles}` 编译型知识库账本及校验；
   - ✅ 落地 `V35__pg_rls_pilot.sql`、`PgRlsMybatisInterceptor` 与 `PgRlsIsolationTest`（真实 EmbeddedPostgres 8 表 RLS 纵深隔离证明）。
