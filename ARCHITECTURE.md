# 数据治理平台 · ARCHITECTURE.md

> 本文件是架构的**元语（meta-language）索引**：先统一“我们说的词是什么意思”，
> 再指向真正的详细契约。它不是 `SPACE-MODEL.md` 的替代品，也不重复 35 项功能的推导。
>
> - 最高架构宪法：`CONSTITUTION.md`（10 条不可妥协公理与 Constitution Check）
> - 仓库导航地图：`AGENTS.md`（≤100 行，面向编码智能体与协作者）
> - 模块边界与功能归属：`SPACE-MODEL.md`
> - 治理对象与规则：`GOVERNANCE-MODEL.md`
> - 智能体共性基座与 9+1 融合规约：`AGENT-PRIMITIVES.md`
> - 集成路线：`INTEGRATION-ROADMAP.md`
> - 前端设计元语：`frontend/DESIGN.md`

## 1. 架构元语

| 元语 | 含义 | 不指什么 |
|---|---|---|
| Space | 架构边界：Platform / Data / Metadata / Control / Runtime / Governance / UI | 不是 UI 上的“空间”菜单 |
| Workspace | 租户；代码与 API 中的 `workspaceId` | 不是架构 Space |
| Metadata | 定义态：数据源、目录、血缘、敏感标签、注册中心 | 不执行查询/任务 |
| Data | 连接器与只读查询契约：`DataAccessGateway` | 不解释业务语义 |
| Control | 任务定义、编译、DAG、Cron、下发 | 不执行物理任务 |
| Runtime | 统一 Execution 事实、执行器、制品、规则解释器 | 不解释业务语义 |
| Governance | 监控、告警、审计、分析报告 | 不向 Control 发 Command |
| Platform | 租户、用户、角色、菜单权限、凭据 | 不管理业务数据 |
| UI | Vue 3 前端；只消费 `/api/v1` | 不直连数据库/SuperSonic |
| Catalog | 规范化数据资产：来源、命名空间、数据集、字段、分区 | 不是 Snapshot 缓存 |
| Lineage | 字段级血缘：节点、作业、边 | 不是执行日志 |
| Sensitivity | 字段敏感标签与规则命中的复核状态 | 不是内容脱敏执行器 |
| AnalysisReport | 对执行/告警/任务的结论，版本不可变 | 不是制品 Artifact |
| Insight | 受治理的 AI 问数入口 | 不是语义层本身 |
| Insight Agent | 独立 Node sidecar，嵌 Pi agent，负责多轮对话/工具/SSE | 不直连数据源，工具全走 Java 内部端点 |
| Semantic Ontology | `dg-metadata` 内建的单一语义真理源（`ENTITY/ATTRIBUTE/DIMENSION/FACT/METRIC` + `CONCEPT/PROCESS` 知识图谱 + `GLOBAL/WORKSPACE` 权威共享 + 多仓库目录 `WorkspaceWarehouse` / `LogicalTable` + 异构物理映射 + 安全值字典）；**对象图是唯一真相，YAML 仅为短名/全 ID 双向投影** | 不是外部 BI 工具私有模型或散落文本 YAML |
| Metric 6D Spec | 度量定义的六维正交契约：可加性（`additive`/`semi_additive`/`non_additive` + `window_groupings`）、LOD、过滤交互、时间日历、多态组合、图拓扑局部性（实体键与基数） | 不是扁平 SQL 字符串表达式 |
| Metric Provenance | 语义字段级证据与责任契约：`db_constraint > knowledge_graph > historical_sql > llm_inference`；LLM 仅做候选起草与证据收集，`llm_inference` 强制 `requires_human_review`，安全/租户/RLS 字段禁 LLM 参与 | 不是信任 LLM 自评置信度 |
| Ontology Graph Gate | 基于对象图的五道硬闸门（G1 Schema / G2 引用完整性 / G3 类型匹配 / G4 DAG 无环 / G5 `content_digest` 变更传播标 `STALE_REVIEW`）与删除保护 | 不是文本正则或弱警告 |
| Falsification Probe | 度量生效前四层确定性裁判：静态图闸门（③a）+ 物理表统计证伪（③b PK/NDV/FK）+ 探针 SMQ（③c，**PG 优先夹具**；联邦场景可辅以嵌入式 DuckDB）+ 反模式检测（③d），常驻 CI | 不是依赖语法不报错即放行；不是生产问数执行引擎 |
| SMQ (`SemanticQuerySpec`) | LLM/API 与确定性编译器之间的**唯一**结构化问数 IR（metrics/dimensions/filters/time/grain/`at_modifiers`…）；代码类型可保留 `SemanticQuerySpec` 别名 | 不含表名/列名/JOIN/GROUP BY/裸 SQL；不是临床队列定义 |
| Catalog Grammar | 从 PUBLISHED 对象图按 `UserContext` 实时 ACL 裁剪后的可枚举目录约束投影（允许维度/grain/修饰符等） | 不是全量元数据 dump；不是 retrieve-then-filter |
| SemanticGate | 单仓 / 多仓 / 联邦三条路径**共用**的语义门禁：允许维度与时间窗、`defaultFilters`、`EXCLUDES`、数据 ACL、截断策略与审计必填字段 | 不是某一编译 Mode 的私有宽松分支 |
| CompileReject | 类型化编译拒绝：`error_code` + `location` + `allowed` + `repair_hint`（供 Agent 局部修复） | 不是单一自由文本 `MTD_SEMANTIC_COMPILE_FAILED` |
| reproducibility_fingerprint | C8 可复现审计指纹结构：数据快照、语义对象/Standard 版本、算子或 SQL 摘要、gateDigest、委派链、截断元数据（见 `docs/analysis-report.md` §10） | 不是仅 SQL 字符串 hash；Insight / Stats / 报表快照共用 schema |
| CohortSpec / CriterionNode | 临床/分析队列：锚点事件、相对时间窗、可审校条件树（AND/OR/EXISTS/序列/FIRST/LAST…） | 不是 SMQ 过滤字段；禁止内嵌裸 SQL |
| ClinicalOperator / ClinicalStatsRuntime | 版本化确定性评分与统计算子及其沙箱执行面（SOFA/生存/回归/PSM 等） | 不是 `SemanticQueryCompiler` 字符串分支；禁止 LLM 心算（C3） |
| AnchorEvent / AlignToAnchor | 以实际治疗完成时间 \(T_0\) 为锚的事件模型与窗口对齐算子（基线/随访 MATCHED·AMBIGUOUS·MISSING） | 不是自由文本时间叙述；不硬编码行业物理表名 |
| TreatmentCycleSplit | 同患者多 \(T_0\) 按时间切分程次并输出稳定 `session_seq` | 不是「取最近一次治疗」启发式 |
| ClinicalQcFinding | 专病 RuleSpec 命中的可审计质控发现（Blocker/Warning）与宿主确认复检 | 不是聊天里口头关闭异常 |
| MappingProposal | 字段→业务本体映射建议工单（PROPOSED→APPROVED/REJECTED）与驳回指纹防重提 | 不是聊天口头映射；不自动晋升生产语义映射 |
| OperationalReport | 参数化运营报表：复合块、快照、PDF/Excel、版本回滚、定时分发 | 不是 `AnalysisReport`（解释结论对象） |
| Query Gateway | 平台 `/api/v1/insight/*`，由原生 `NativeInsightEngine` + `SemanticGate` + `MultiWarehouseQueryCompiler` 驱动，支持 `SINGLE_ROUTE`、`COHORT_UNION`（两阶段统计合并）与 `ENTITY_FEDERATED_JOIN`（StarRocks External Catalog / 嵌入式 DuckDB） | 不是直接 SQL 客户端或 SuperSonic 代理 |
| Design Token | 前端颜色/间距/字体等稳定变量，`var(--dg-*)` | 不是 Element Plus 内部变量 |
| Constitution | 仓库根级不可妥协公理与安全红线（`CONSTITUTION.md`） | 不是普通设计建议 |
| Agent Substrate (A0) | 跨 Agent 复用的认知与演进基座：分层记忆、Skill、受控自演进、轨迹评测 | 不是第 9 个业务 Space |
| Skill (`SKILL.md`) | 渐进式披露的过程性技能包（`skills/<name>/SKILL.md`），含最小 `allowed-tools` | 不是硬编码全局 System Prompt |
| Governed Action | 平台允许的受控写动作契约：前置校验、幂等键、副作用声明、审计与宿主确认 | 不是无约束业务库写回 |
| Host Consent UI | 宿主前端渲染的不可被 Agent 篡改的高危操作独立确认视图（防 ASI09） | 不是聊天流内自然语言“同意” |
| Trajectory Eval (`pass^k`) | 独立裁判上下文下校验终态 Outcome 与连续 $k$ 次全胜率的智能体回归门禁 | 不是单次冒烟或模型口头自述 |

**术语纪律**：新词先进入本表；同一个概念只保留一个英文名。不要把 Workspace 写成 Space，
也不要把 “看板” 写成 “Dashboard” 之外的新词。

## 2. 运行时拓扑

```text
Browser
  → dg-app (Spring Boot, /api/v1, 静态 SPA)
      ├─ PostgreSQL (平台元数据库 / Flyway V1–V38 / PG RLS)
      ├─ dg-metadata (目录 / 血缘 / 敏感 / 语义本体与多仓库目录 / MultiWarehouseQueryCompiler)
      │     → DataAccessGateway / FederatedQueryGateway → 业务数据源 / StarRocks / DuckDB
      ├─ Insight Query Gateway
      │     → NativeInsightEngine (子图检索 + Value Linking + 确定性 SQL 编译)
      │           → DataAccessGateway → StarRocks / PostgreSQL
      │                 → MinIO / S3 / HDFS (Parquet)
      └─ Insight Agent Gateway
            → agent-service (:8090, Pi agent / SSE)
                  → 内部工具端点 → 语义子图 / Insight Query Gateway / 目录
```

- `dg-app` 是唯一同时看见各 Space 的装配层；
- 前端与 `dg-app` 同源部署时，`/` 与 SPA 路由由 `SpaForwardController` 回退到 `index.html`；
- 浏览器不直连 StarRocks/ClickHouse，外部 SuperSonic 已完全退场，人机统一消费 `dg-metadata` 单一语义真理源；
- ClickHouse 仅保留为历史 POC 链路，当前平台默认走 StarRocks。

## 3. 模块边界

| 模块 | 职责 | 关键约束 |
|---|---|---|
| `dg-common` | 响应、错误码、WorkspaceContext、加密、ID | 不依赖 Spring Web |
| `dg-platform` | 租户、用户、角色、菜单、凭据 | 凭据唯一解密入口 |
| `dg-data-spi` | 连接器契约、只读 SQL 护栏 | 零驱动依赖 |
| `dg-data-connectors` | JDBC/文件/对象存储实现 | 不解释业务语义 |
| `dg-metadata` | 数据源、目录、血缘、敏感、注册中心 | 不执行任务 |
| `dg-runtime` | Execution、执行器、制品 | 不解释业务语义 |
| `dg-control` | 任务、编译、调度 | 不直接执行 |
| `dg-governance` | 监控、告警、审计、报告 | 不向 Control 发命令 |
| `dg-app` | REST、拦截器、Flyway、SPA | 唯一装配层 |
| `agent-service` | Node sidecar：Pi agent、会话、SSE | 不直连数据库/数据源；工具只走 Java 内部端点 |
| `frontend` | Vue 3 + Element Plus + Pinia + Router | 只消费 API |

边界由 Maven 依赖缺失强制，不靠评审自觉。新增跨 Space 调用前先问：
“这是契约，还是我想省一层？”

## 4. 前端架构元语

- **入口**：`frontend/src/main.ts` → Pinia → Router → Element Plus；
- **路由与壳层**：静态声明 + `meta.permission` / `meta.parentPath`；菜单由后端 `pf_permission` 下发；共享壳层 `layouts/MainLayout.vue` 承载侧边栏菜单、多级面包屑、Workspace 切换、退出确认与 `AgentDock`；
- **会话**：`stores/auth.ts`，只持久化 token/workspaceId，权限每次 `/auth/me` 重新拉；切换 Workspace 换票并重置 `KeepAlive` 与 `agentSession`；
- **请求**：`api/request.ts`，统一 `ApiResponse` 拆包、Bearer、`X-Workspace-Id`、401 清会话并回跳 `/login`；
- **浮层与确认**：5 级浮层规约见 `frontend/DESIGN.md` §6.8（二次确认统一走 `utils/confirm.ts`，禁止裸调 `ElMessageBox.confirm`；AI 高危审批走 C7 独立组件 `AgentConsentDialog.vue`）；
- **页面与行为基线**：`views/` 按业务域；壳层交互规范见 `openspec/specs/frontend-shell-interaction/spec.md`；
- **设计元语**：`styles/tokens.css` + `frontend/DESIGN.md`（机械门禁 `scripts/verify-primitives.py` 覆盖 `layouts/`、`views/`、`components/`）；
- **AI 问数与图表**：`views/insight/*` 经 `/api/v1/insight/*`；`InsightAnswerChart.vue` 按需引入 ECharts，遵循 C7 宿主 10 类白名单图表、自动双 Y 轴、L1 联动过滤与 L2 零 LLM 层级下钻。
- **P0 计划**：`docs/plans/2026-10-30-frontend-p0.md`；实施拆分在 `openspec/changes/`。

### 4.1 API 契约

- 统一信封：`{ success, code, message, data, traceId, timestamp }`；
- 空间：`X-Workspace-Id` 可覆盖 token 内空间，但服务端每次重新校验成员资格；
- 权限：`@RequirePermission("code")`，前端 `meta.permission`/`v-permission` 同步；
- 审计：写操作由 `AuditInterceptor`；问数/导出由服务层写 `ig_query_log` + `gv_audit_record`。

## 5. 关键数据流

### 5.1 问数（SMQ + 统一 SemanticGate）

```text
用户问题 / Agent 工具
  → Catalog Grammar（ACL 裁剪目录）→ 组装 SMQ（SemanticQuerySpec）
  → compile_and_validate / execute
  → SemanticGate（单仓·多仓·联邦共用：允许维/时间窗/defaultFilters/EXCLUDES/ACL/截断）
        → 失败：CompileReject（error_code + allowed + repair_hint）
        → 成功：SemanticQueryCompiler / MultiWarehouseQueryCompiler
  → DataAccessGateway / FederatedQueryGateway → PostgreSQL / StarRocks（联邦兜底 DuckDB）
  → 脱敏 + ig_query_log / gv_audit_record + reproducibility_fingerprint
```

### 5.2 目录/血缘/敏感

```text
数据源 → CatalogProvider → md_catalog_* → 目录页
SQL/作业 → LineageService → md_lineage_* → 影响分析
字段名/注释 → SensitivityService → md_column_sensitivity → 问数脱敏/复核
```

### 5.3 受控语义建模与证伪闭环

```text
MCP 接地选 ID（list_dimensions / list_metrics / resolve_ref）
  → LLM 起草带 Provenance 的六维 YAML 投影（禁止触碰安全/租户/RLS 字段）
  → Ontology Graph 导入（短名/显式 ref + G1–G5 + 删除保护 + ③d 反模式）
  → ③b 物理表统计证伪（PK/NDV/FK；FAIL → LOW_CONFIDENCE）
  → ③c 探针 SMQ（PG 优先夹具；扇出/可加性/半加性/NULL/除零/量级）
  → 宿主审校（PROPOSED / STALE_REVIEW → PUBLISHED + content_digest）
  ※ 图闸门必须先于六维运算接通编译器（openspec: metric-6d + analysis-hardening W2）
```

### 5.4 临床队列、确定性统计与运营报表（与 SMQ 解耦）

```text
CohortSpec / CriterionNode → CohortCompiler → 平台内成员集物化
ClinicalOperator → ClinicalStatsRuntime（沙箱；禁止嵌入 SemanticQueryCompiler）
        → 输出表/标量 + operatorDigest → 可被 METRIC/SMQ 引用
OperationalReport（参数·复合块·快照·PDF/Excel·定时分发）
        → 块分派：SMQ | Cohort | Stats | 静态 Markdown
        → 每块经 SemanticGate；整单绑定 reproducibility_fingerprint
AnalysisReport 仍为「解释结论」对象，与 OperationalReport 双轨并存
```

## 6. Data-Driven Ops Profile（数据自治领域包）

`data-driven-ops-profile` 不新增 P10/P11 元原语，而是把自治能力挂载到既有 P1–P9 + A0：

| 组件 | 槽位 | Space | 稳定契约 |
|---|---|---|---|
| `Target` / `ActionType`（Layer 3） | P2 Context + P7 Security | metadata | `md_semantic_object`（object_type=TARGET/ACTION_TYPE），发布后不可变快照 |
| `FilterPreset` | P2 Context | metadata | `au_filter_preset`，只存声明式条件 |
| `TriggerSpec` / `FlowSpec IR` / `FlowCompiler` / `Arbiter` | P4 Flow + P8 Verify | control | `flow.ir/v1` + `au_flow_def/version` + `au_trigger_def` |
| `DurableFlowEngine` / 原子 Activity / 实体锁冷却 | P4 Flow + P5 Protocol | runtime | `au_flow_instance` + `au_entity_lock_cooldown`；SPI 可替换 Temporal/Restate |
| `DecisionTrace` / `ShadowEvaluator` / Kill Switch | P9 Observe + P7 Security | governance | `au_decision_trace` 不可变证据链 + T+1 回检 |
| 归因建议 / IR 草案 | A0 Agent 基座 | agent-service | 只读归因 + 受 JSON Schema 约束的草案，必须经 FlowCompiler |

硬纪律：

1. 新建或变更 FlowSpec 一律以 `SHADOW` 启动，`SHADOW → L2_ASSISTED → L1_AUTO` 不可跳级；
2. 硬不变量、参数包络、`domainScope`（C6）、配对补偿与冷却期全部在 `FlowCompiler` 编译期拦截；
3. L2/L3 审批复用 V32 `ig_agent_action_proposal` + 宿主确认，不造第二套审批入口；
4. 回检缺少观测数据必须落 `INCONCLUSIVE`，不得把缺失当 0（C8）；
5. 所有自治表启用 `workspace_id` + PostgreSQL RLS；Trace 只存聚合指标，不落行内容（C5）。

## 7. 变更协议

1. 新术语先加到 §1；
2. 新模块/依赖先更新 §3，并确保 Maven 依赖不产生环；
3. 新前端 token 先改 `tokens.css`，再改 `frontend/DESIGN.md`；
4. 新 API 必须写清信封、权限码、workspace 作用域、审计落点；
5. 外部服务（SuperSonic/StarRocks/LLM）变更必须写进 `INTEGRATION-ROADMAP.md` 或对应 plan；
6. 文档中的“已完成”必须有代码、迁移或验证记录支撑。
