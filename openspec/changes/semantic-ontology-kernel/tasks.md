# Tasks: 统一本体语义元数据内核、知识图谱与完全替换 SuperSonic

- **Change ID**: `semantic-ontology-kernel`
- **参考文档**：[proposal.md](./proposal.md) · [design.md](./design.md)
- **状态**：代码、迁移、前后端工作台、Agent/MCP 工具、评测集与 SuperSonic 退场全部实施并通过离线/真库(EmbeddedPostgres)门禁验证。

---

## Stage 1：语义元数据内核、知识图谱存储与逐跳子图检索（`dg-metadata` + `dg-app`）

- [x] **1.1 数据库迁移与 PG RLS**
  - 新增 `V36__semantic_ontology_kernel.sql`：创建 `md_semantic_object`、`md_semantic_mapping`、`md_semantic_link`、`md_semantic_object_version`、`md_semantic_value_dict` 五张表、索引、PG RLS 策略及菜单/权限种子。
  - 在 `PgRlsIsolationTest` 中以真实 EmbeddedPostgres 验证全部 13 张表（含 5 张新表）严格执行跨租户 RLS 隔离。
- [x] **1.2 `dg-metadata` 领域模型与核心服务**
  - 新增 `SemanticEntities`、5 个 MyBatis-Plus Mapper 与 `SemanticOntologyService`（CRUD、C2 `standardRef` 闸门、C5 PII 拦截与 L3+ 禁建值字典、只读 SQL 表达式护栏、不可变快照与 SHA-256、双时间态回滚、PROPOSED 审校与驳回防重提）。
- [x] **1.3 逐跳鉴权子图检索与影响分析**
  - 实现有界 $k$-Hop（1–3 跳）检索前 + 逐跳 ACL 剪枝子图检索（含 `externalModel` L3+ 样例剥离）及 `impactByColumn` / `impactByDataset` 递归影响分析。
- [x] **1.4 REST API、Space Contract 与种子数据**
  - 新增 `SemanticOntologyController`（`/api/v1/metadata/semantic/**`），同步更新 `docs/space-contracts/metadata.yaml`，新增 `scripts/seed-semantic-kernel.py`。
- [x] **1.5 Stage 1 验证**
  - 新增 `SemanticOntologyServiceTest` 并通过全量 `dg-metadata` 单测与 `verify-primitives.py`。

---

## Stage 2：确定性语义 SQL 编译器与只读执行引擎

- [x] **2.1 `SemanticQuerySpec` 契约与编译闸门**
  - 实现存在性、发布状态、`standardRef`（C2）、`allowedDimensions` 粒度与物理数据集 ACL（C4/C5）校验。
- [x] **2.2 `SemanticQueryCompiler` 确定性 SQL 生成器**
  - 实现物理表选择、沿 `STRUCTURAL` 边确定性 JOIN、多事实表防扇出 CTE（Fan-out Protection）、衍生比率 `NULLIF(..., 0)` 除零保护、自动注入 `defaultFilters` 与知识图谱 `EXCLUDES` 约束、`ReadOnlySqlGuard` 只读校验与 SHA-256 审计指纹。
- [x] **2.3 直连 `DataAccessGateway` 执行与编译预览**
  - 新增 `POST /api/v1/insight/semantic/compile`，经 `DataQueryService`（`DataAccessGateway`）执行只读查询。
- [x] **2.4 Stage 2 确定性单测矩阵**
  - 新增 `SemanticQueryCompilerTest` 覆盖单表聚合、星型关联、多事实表防扇出 CTE、衍生比率除零保护、ACL 拦截、值对齐与相对时间归一化。

---

## Stage 3：`NL2SemanticSpec`、Value Linking、原生引擎切换与前后端接入

- [x] **3.1 抽取 `InsightQueryEngine` 并落地 `NativeInsightEngine`**
  - 重构 `InsightQueryService` 面向 `InsightQueryEngine`，新增 `NativeInsightEngine` + `NativeInsightEngineTest`（`INCONCLUSIVE` / `CLARIFY_NEEDED` / 确定性编译与审计指纹），无缝复用脱敏、临时授权、导出、查询日志与审计。
- [x] **3.2 维度值对齐（Value Linking）与相对时间归一化**
  - 实现 `SemanticValueLinkingService`（L1/L2 安全值匹配 + 确定性相对时间换算）。
- [x] **3.3 `agent-service` 与 MCP 工具升级**
  - `AgentToolController` 新增 `/semantic/subgraph`、`/semantic/objects/{id}`、`/semantic/compile`、`/semantic/query`；`McpServerController` 与 `contracts/mcp/datagov-mcp.json` 新增 `search_semantic_subgraph` 与 `dg://semantic/objects`；`agent-service/src/tools.ts` 与 `skills/insight-data-ask/SKILL.md` 完成只读语义工具升级。
- [x] **3.4 前端语义本体工作台与原地融入**
  - 新增 `types/semantic.ts`、`api/semantic.ts`、路由 `/metadata/semantic`（`SemanticOntologyView.vue` 四 Tab）及 6 个组件（`SemanticObjectDrawer`、`SemanticMappingEditor`、`SemanticAiContextEditor`、`SemanticGraphPanel`、`SemanticPlaygroundPanel`、`SemanticImpactDrawer`），零硬编码色值（严格 `var(--dg-*)`）。
  - 原地融入 `AssetDetailView.vue`（表/列语义影响分析）、`AskView.vue`（语义依据条 + `CLARIFY_NEEDED` 口径单选重跑卡）、`InboxView.vue`（待审语义提案提示条）。
- [x] **3.5 Stage 3 校验**
  - 通过 `mvn test`、`pnpm type-check && pnpm build`、`tsc --noEmit` 与 `verify-primitives.py --strict`。

---

## Stage 4：AI 受控图谱演进与回归评测门禁

- [x] **4.1 语义/图谱 `PROPOSED` 暂存审批与驳回防重提**
  - 完成 `proposeObject` / `approveProposal` / `rejectProposal` 闭环及负反馈抑制单测验证，前端待审 Tab 对接宿主二次确认与必填驳回理由。
- [x] **4.2 问数评测脚本升级**
  - 新增 `scripts/evaluate-insight-50.py`（支持 `--mode offline` 与 `--mode live` 实测回放，离线校验通过；在线实测需连接运行中的 StarRocks 与 dg-app 环境）。
- [x] **4.3 补齐智能体回归评测用例**
  - 在 `evals/regression/cases.json` 新增 `reg-13`–`reg-16`（多义消歧、2 跳逐跳 ACL 剪枝、未登记指标 `INCONCLUSIVE`、L3 外部模型剥离与 SuperSonic 退场校验），`run-agent-evals.py --suite regression --trials 3 --mode offline` 全量 `16/16` 通过（`pass^3 = 1.0`）。

---

## Stage 5：彻底下线 SuperSonic 与历史包袱清理

- [x] **5.1 切换默认引擎并删除后端 SuperSonic 胶水**
  - 默认配置切换为 `dg.insight.engine=native`；删除 `SuperSonicClient`、`InsightSupersonicMapping*` 全部 5 个类及 `application.yml` 遗留配置；新增 `V37__retire_supersonic_mapping.sql` 清理废弃映射菜单与权限。
- [x] **5.2 删除前端账号映射页面与废弃脚本**
  - 删除 `frontend/src/views/insight/MappingView.vue`、`/insight/mappings` 路由及 `api/insight.ts`、`types/insight.ts` 映射定义；删除 4 个历史 SuperSonic 脚本。
- [x] **5.3 权威文档同步与全量终验**
  - 同步更新 `ARCHITECTURE.md`、`AGENTS.md`、`docs/space-contracts/metadata.yaml` 与 `contracts/mcp/datagov-mcp.json`。
  - 全量门禁终验全部通过。
