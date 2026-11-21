# Technical Design: 受治理的语义探索、确定性剖析与智能体受控演进闭环（MVP）

- **Change ID**: `governed-agent-evolution-mvp`
- **Aligned Proposal**: [proposal.md](./proposal.md)
- **原则**：**以本仓库当前架构与技术栈为主**（Java 21 + Spring Boot 3.3 + PostgreSQL + StarRocks/MinIO + SuperSonic + Pi Agent sidecar + Vue 3 / Element Plus / ECharts），吸收通用工程机制，**不引入** Temporal、Rust-Wasmtime、NestJS、LangGraph、Cedar、Neo4j、Next.js/shadcn 等异构重型栈。

---

## 1. 技术栈与模块落点（对照 9+1 元语）

| 元语域 | 当前平台既有底座（主线） | 本方案吸收的通用增强 | 明确不引入 |
|---|---|---|---|
| **P2 数据与语义** | `PostgreSQL`（元数据库、`V35` RLS、双时间态 `Fact`）、`StarRocks` + `MinIO`（分析/湖层）、`SuperSonic`（可替换语义层，经 `Insight Query Gateway` 代理）、`dg-metadata`（Catalog / Glossary / Lineage / Sensitivity / Quality） | ① 统一语义与 `Standard` 作为人机唯一真理源；<br>② 远期文档/知识向量检索按需复用 PostgreSQL `pgvector`，强制 `workspace_id` + 权限 + 生效期**检索前过滤**；<br>③ 血缘/图谱查询落实**逐跳权限过滤（Hop-by-Hop ACL）** | Apache Ossie (OSI) 强绑定、dbt MetricFlow、Neo4j、LightRAG |
| **P3/P8 确定性算子与护栏** | `dg-control`（`RuleController` / `RuleSpec` 确定性模板编译）、`dg-data-spi`（只读 SQL 护栏）、`dg-runtime`（统一 `Execution` 与制品）、Maven 编译期物理依赖隔离、`scripts/verify-primitives.py` | 在既有质量引擎与 `DataAccessGateway` 上扩展标准化**确定性质量剖析算子（DQ Profile）**（空值/缺失率、单位或枚举异构占比、码表漂移、时间倒挂、阈值离群）；LLM 仅生成 `RuleSpec` 草案与结果解读，不心算指标 | Rust-Wasmtime 沙箱运行时 |
| **P4/P5 编排与协议** | `dg-app`（唯一装配层，Spring Boot 3.3）、`dg-control`（确定性任务/DAG/事件编排）、`agent-service/`（Node `:8090` Pi Agent sidecar + SSE）、`AgentToolController`、`McpServerController`、`AgentCardController` | ① `agent-service` SSE 事件流扩展受控白名单 `ui_block` 事件（对齐 AG-UI 增量渲染语义）；<br>② 由质量剖析完成事件（Spring Event / 后续 Kafka `dg.*`）异步触发 Reflector，严格与确定性主流程解耦 | NestJS、Temporal、LangGraph |
| **P6 前端交互工作台** | `Vue 3 + TypeScript + Element Plus + Pinia + ECharts`、`frontend/DESIGN.md` + `tokens.css`（`--dg-*`）、`AgentDock.vue`、`AgentConsentDialog.vue` | ① 新增宿主注册表白名单组件：`FilterFunnelCard`、`DistributionProfileCard`、`RuleDiffProposalCard`（严格走 `--dg-*` Token，禁止注入 HTML/JS/Vue）；<br>② 所有高危写动作统一复走 `AgentConsentDialog.vue` 与后端二次核验 | Next.js、Tailwind CSS v4、shadcn/ui |
| **P7/P9/A0 安全、演进与评测** | `dg-platform`（`UserContext` 实时鉴权、院方 OIDC/CAS/LDAP 对接）、`V32` 确认提案、`V33` Kill Switch/熔断、`V34` `GovernedFactService`、`V35` PG RLS、`knowledge/`、`agent-service/src/telemetry.ts`（OTel `gen_ai.*`）、`evals/`（`run-agent-evals.py` `pass^3`） | ① **按密级模型路由护栏 + PII 出站拦截器**：涉及 L3/L4 明细强制路由至 `SELF_HOSTED` 私有模型，阻断流向 `EXTERNAL` API；<br>② 在 `mvn test` 中补齐跨 `Workspace` PG RLS 自动化断言测试；<br>③ Curator 仅具 Staging 写权限（`knowledge/research/` + `Fact(PROPOSED)`），物理禁写 `articles/`；<br>④ 新增**驳回防重提**指纹检查（检索 `maintenance-log.md` / 驳回记录）；<br>⑤ `AnalysisReport` 与导出绑定 `reproducibilityFingerprint` + `traceId` | Cedar 独立引擎、Supabase/pgTAP、Graphiti、DeepEval |

---

## 2. Constitution Check（技术实现核验表 · C1–C10）

| 宪法公理 | 本方案具体实现机制（落到现有模块） | 核验 |
|---|---|---|
| **C1 Space 编译边界** | 质量剖析定义归 `dg-metadata`/`dg-control`，执行归 `dg-runtime`，演进提案/报告归 `dg-governance`，`dg-governance` 仅订阅剖析完成事件不依赖 `dg-control` 也不发 Command；Maven POM 校验由 `verify-primitives.py` 强制 | PASS |
| **C2 治理约束为编译输入** | 归一化与阈值离群剖析必须引用已登记 `Standard`，未登记时 `Rule` 编译闸门直接失败 | PASS |
| **C3 确定性计算优先** | 指标聚合走 SuperSonic / 语义层，质量剖析与单位归一化走确定性 SQL 模板/引擎算子；LLM 仅做工具参数装配、`RuleSpec` 草案起草与自然语言解读，严禁上下文心算与直出执行 DDL/SQL | PASS |
| **C4 零信任与最小代理** | `AgentToolController` 与 `McpServerController` 均按真实 `userId + workspaceId` 实时重算权限；写工具受 `SKILL.md allowed-tools` 门禁与 `V33` Kill Switch 约束，零共享超级账号 | PASS |
| **C5 纵深隔离与模型路由** | 沿用 `Workspace` 隔离与 `V35` PG RLS（补齐自动化测试）；网关新增 `model.endpointKind` 分级路由护栏与 L3/L4 出站正则兜底，禁止敏感明细流向 `EXTERNAL` 公网模型；检索与血缘遍历落实检索前 + 逐跳 ACL | PASS |
| **C6 医疗克制写回** | 严禁新增外部 HIS/EMR 写回通道；平台内部演进晋升/锁库作为受控写动作，强绑定幂等键、前置条件与 `gv_audit_record` | PASS |
| **C7 宿主画盘与独立确认** | 前端 `ui_block` 渲染器仅接受白名单组件名与校验后 Props，拒绝任何可执行脚本；高危写操作经 `AgentActionProposalService` 落 `PENDING` 并由 `AgentConsentDialog.vue` 弹窗 + 后端二次核验执行 | PASS |
| **C8 拒绝对猜与审计指纹** | 剖析截断或证据不足强制返回 `INCONCLUSIVE`；`AnalysisReportService`（`V36` 扩展）与导出结果写入不可变 `reproducibility_fingerprint`（数据源快照、`Standard` 版本、算子摘要、`knowledge/` SHA、人机委派链）并关联 `traceId` | PASS |
| **C9 受控自演进** | 演进提案仅由确定性剖析/人工纠偏触发；Curator 只能写入 `knowledge/research/` 与 `Fact(PROPOSED)`，晋升必须经治理员审校 + `verify-primitives.py` + `evals/regression`；支持双时间态一键回滚与驳回防重提 | PASS |
| **C10 编排解耦与轨迹评测** | 剖析执行与异步 Reflector 推理严格隔离；`telemetry.ts` 默认关闭原始 Prompt 采集；所有改动必过 `verify-primitives.py` 与 `run-agent-evals.py --suite regression --trials 3` | PASS |

---

## 3. 核心数据流架构（基于当前拓扑）

### 3.1 读与受控分析流

```text
Browser (Vue 3 · AgentDock.vue)
  → dg-app (AgentProxyController · Bearer JWT + X-Workspace-Id + V33 Kill Switch/配额)
      → agent-service (:8090 · Pi Agent · 按需激活 SKILL.md · 先查 knowledge/index.md)
            │  [模型路由护栏：含 L3/L4 敏感上下文强制路由 SELF_HOSTED，拦截 EXTERNAL 出站 PII]
            → dg-app (AgentToolController / McpServerController · userId + workspaceId 实时鉴权)
                  ├─ Insight Query Gateway → SuperSonic / StarRocks（语义指标只读查询 + 字段脱敏）
                  ├─ Quality Profile API → DataAccessGateway / dg-runtime（确定性质量剖析算子）
                  └─ Catalog / Lineage / Fact 检索（检索前 ACL + 血缘逐跳 ACL + V35 PG RLS 兜底）
            ← 确定性结果（含异常占比与可复现元数据）
      ← SSE 流（文本解读 + 白名单 ui_block：FilterFunnelCard / DistributionProfileCard）
  → 前端宿主安全渲染白名单组件
  → 落账：ig_query_log + gv_audit_record + OTel traceId
```

### 3.2 确定性异常驱动的受控演进流

```text
触发源：Quality Profile 检出未收录异常模式（聚合统计证据，零 L3 明细行） / 人工纠偏
  → 异步事件（Spring Event / 后续 Kafka dg.governance.*）
  → 负反馈抑制检查：计算异常模式指纹，查询 knowledge/maintenance-log.md 与已驳回 Fact
        ├─ 命中历史驳回指纹 → 静默跳过并记观测指标（防重复骚扰）
        └─ 未命中 → 触发 Reflector（独立上下文提取紧凑 Delta，禁重写全文）
              → Curator（最小权限：仅写 Staging）
                    ├─ 写入 knowledge/research/<topic>.md（附确定性统计快照引用）
                    ├─ 写入候选 Fact(state=PROPOSED) 与/或候选 RuleSpec 草案
                    └─ 生成 AgentActionProposal（或治理收件箱待办）
              → 机械门禁校验：verify-primitives.py + 离线/在线 evals/regression (pass^3 >= 0.90)
              → 人工审校（前端 RuleDiffProposalCard + AgentConsentDialog / Git Diff）
                    ├─ 批准 → 双时间态晋升至 articles/ & 正式 Fact/Rule（保留游标支持一键回滚）
                    └─ 驳回 → 驳回理由与模式指纹写入 maintenance-log.md 与 Fact(REJECTED)
```

---

## 4. 关键数据模型与接口增量设计（最小侵入）

1. **数据库迁移（`V40__reproducibility_fingerprint.sql` + `V44__governed_agent_evolution_mvp.sql`）**：
   - `gv_analysis_report_version` 已于 `V40` 补齐 `reproducibility_fingerprint TEXT`（向后兼容 `V13`）；
   - `V44` 新增 `md_dataset_profile_run`（记录数据集确定性剖析快照、五维异常摘要与 `pattern_fingerprint`，并启用 PostgreSQL RLS 策略）；
   - `V44` 复用 `V34` `gv_governed_fact`（补充 `pattern_fingerprint` 与 `rejection_reason` 字段及联合索引用于驳回防重提，不新造平行表）。
2. **模型路由护栏配置（`dg-app` + `agent-service/src/config.ts`）**：
   - 为模型端点显式标注 `endpointKind: SELF_HOSTED | EXTERNAL`；
   - 当请求关联数据集 `max_sensitivity ≥ L3` 或包含未脱敏明细时，服务端拒绝路由至 `EXTERNAL` 端点并返回结构化错误码，记入安全审计。
3. **前端白名单声明式块协议（`ui_block`）**：
   - SSE 新增 `event: ui_block`，载荷严格校验：`{ "component": "FilterFunnelCard" | "DistributionProfileCard" | "RuleDiffProposalCard", "version": "1", "props": { ... } }`；
   - 未在白名单内的 `component` 直接丢弃并降级为纯文本摘要，零可执行代码注入面。

---

## 5. 分期实施顺序（供拆解 `tasks.md`）

1. **Phase 1 — 合规底线补强（后端 + 测试）**：`V36` 迁移（`reproducibility_fingerprint` 与 `pattern_fingerprint`）、模型密级路由护栏、`AnalysisReportService` 指纹与 `endpointKind` 校验、`V35` PG RLS 自动化集成测试。
2. **Phase 2 — 确定性质量剖析（后端 + 工具）**：`POST /api/v1/metadata/quality/datasets/{id}/profile` 及剖析算子实现（缺失率、单位/枚举混用、码表漂移、时间倒挂、阈值离群），向 `agent-service` 暴露只读剖析工具。
3. **Phase 3 — 宿主白名单交互画布（Agent + 前端）**：`agent-service` 增加受控 `ui_block` 协议；Vue 3 实现 `FilterFunnelCard`、`DistributionProfileCard`、`RuleDiffProposalCard`（严格 `--dg-*` Token）并接入 `AgentDock.vue` / `AgentConsentDialog.vue`。
4. **Phase 4 — 受控演进闭环与回归门禁（后端 + 知识库 + 评测）**：剖析异常异步触发 Staging 提案 + 驳回防重提查询；在 `evals/regression/cases.json` 补齐 Scenario 1–3 对应回归用例，全量跑通 `verify-primitives.py`、`mvn test`、`pnpm build`、`tsc --noEmit` 与 `run-agent-evals.py --suite regression --trials 3`。
