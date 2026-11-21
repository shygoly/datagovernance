# Tasks: 字段级血缘全链路体系（`column-lineage-e2e`）

> 实施原则：严格按 Phase 1 → Phase 2 → Phase 3 增量推进；每个 Phase 结束后须通过对应单测、构建与 `python3 scripts/verify-primitives.py` 校验。

---

## Phase 1：采集主链路与工作台可用化（MVP）

### 1.1 数据模型与后端基础增强
- [x] **P1-1** 新增 Flyway 迁移 `V47__column_lineage_e2e.sql`：扩展 `md_lineage_edge`（`execution_id`、`dependency_type`、`masking_applied`、`masking_evidence`、`superseded_at`）、`md_lineage_job`（`last_execution_id`、`parse_status`、`parse_message`）与 `md_lineage_node`（`external_ref_type`、`external_ref_id`）。
- [x] **P1-2** 更新 `LineageEntities`、`LineageService` 与 `LineageController`：
  - 支持 `PLANNED` / `ACTIVE` / `SUPERSEDED` 双态写入与晋升（`recordPlannedJob` / `promoteJobExecution`）；
  - 新增工作台概览接口 `GET /api/v1/metadata/lineage/overview`（统计 KPI、有血缘的数据集列表、最近作业及解析状态）；
  - 新增边详情与作业信息返回（`EdgeView` 补充 `jobId`、`jobName`、`jobType`、`executionId`、`dependencyType`、`maskingApplied`、`discoveredAt`）。
- [x] **P1-3** 实现 `LineageCatalogResolver`：根据数据源/库/表/列名自动解析并绑定 `catalogDatasetId` / `catalogColumnId`，补充历史节点回填入口。

### 1.2 平台内建作业自动采集（符合 C1 边界）
- [x] **P1-4** 在 `dg-control` / `dg-app` 实现 `StructuredJobLineageExtractor`：
  - 支持 `OfflineSync`（`fieldMappings` + `fieldRules` + 脱敏规则识别）；
  - 支持 `DbMigration`（表/列命名规则自动映射）；
  - 支持 `FileParse` / `ApiParse` 字段映射。
- [x] **P1-5** 接入事件监听：作业发布/编译成功后写入 `PLANNED` 血缘；监听 `ExecutionSucceeded` 事件后晋升为 `ACTIVE` 并绑定 `execution_id`；编写集成测试验证双态流转与 `SUPERSEDED` 标记。

### 1.3 Python SQLGlot Sidecar 与视图/SQL 作业采集
- [x] **P1-6** 新建 `lineage-parser-service/`（FastAPI + SQLGlot）：
  - 实现 `POST /v1/parse/column`（支持 PostgreSQL / MySQL / Doris / StarRocks / Oracle / Spark / Hive 方言、`catalogSchema` 展开 `SELECT *`、`DIRECT`/`INDIRECT` 区分、脱敏函数识别）；
  - 无法确定列映射时显式返回 `INCONCLUSIVE` 与诊断原因；
  - 补充 `pytest` 多方言测试用例与 `Dockerfile`。
- [x] **P1-7** 在 `dg-app` / `dg-metadata` 实现 `SqlLineageParserClient`（超时控制、熔断与 `INCONCLUSIVE` 降级）与 `POST /api/v1/metadata/lineage/sql/parse-preview`、`POST /api/v1/metadata/lineage/sql/import` 接口。
- [x] **P1-8** 接入数据库 `VIEW` / `MATERIALIZED_VIEW` 采集与 `BatchDev` / `StreamingDev` SQL 作业自动血缘解析。
  - 落地说明：`AbstractJdbcConnector#listTables` 在 `VIEW`/`MATERIALIZED_VIEW` 上调用 `safeViewDefinition`；**PostgreSQL**（`pg_views` + `pg_matviews`）、**MySQL**（`information_schema.VIEWS` + `SHOW CREATE MATERIALIZED VIEW` 兜底）、**Doris / StarRocks**（同 MySQL 线协议 + 物化视图 `SHOW CREATE MATERIALIZED VIEW`）已覆盖。`LineageEventListener` 已监听 Batch/Streaming SQL 作业编译与执行。
  - **Non-goals / 待补**：**HMS** / **Glue** Catalog 视图的定义 SQL 只读采集仍为 TODO（需各自 API/元数据表，工作量较大，不在本变更 JDBC 主路径内）。

### 1.4 前端工作台可用化（Phase 1）
- [x] **P1-9** 在 `LineageView.vue` 新增**血缘概览态**（未选中资产时展示 KPI、活跃血缘表/作业列表），并在数据集/字段下拉选择器上展示血缘计数徽标。
- [x] **P1-10** 新增 `ManualLineageDialog.vue`（手工补录血缘）与 `SqlLineageImportDialog.vue`（粘贴 SQL 解析预览/导入，含 `INCONCLUSIVE` 诊断展示），接入 `metadata:lineage:manage` 权限控制。
- [x] **P1-11** 增强右侧详情面板，支持点击连线查看 `transformExpr` SQL、作业来源、执行证据与状态，支持切换是否显示 `PLANNED` 预估边。

---

## Phase 2：表卡片画布、OpenLineage 与安全合规闭环

### 2.1 表卡片 + 字段端口双层画布重构
- [x] **P2-1** 前端引入 `@vue-flow/core`、`@vue-flow/background`、`@vue-flow/controls`、`@vue-flow/minimap`、`@dagrejs/dagre`，严守 `--dg-*` Token。
- [x] **P2-2** 实现 `LineageFlowCanvas.vue` 与 `TableLineageNode.vue`：
  - 表容器卡片（数据源/表名/敏感徽标/字段行端口/展开其余字段）；
  - 连线区分 `DIRECT`（实线）、`INDIRECT`（虚线）、`PLANNED`（点划线）；
  - 支持点击字段/边高亮端到端完整路径、按跳数懒加载展开，并保留表级聚合模式与大图收敛保护。
  - 落地说明：字段级默认走 Vue Flow 表卡片画布（可切回 ECharts 节点图），表级聚合仍为 ECharts；双击字段/点击展开出的其余字段即以其为中心按跳数重新加载。**敏感等级**：后端 `LineageService.NodeView.sensitivityLevel` 为各列/数据集上 **CONFIRMED** 标签的 L1–L4 最高级；前端表头与字段行用 `--dg-*` Token 徽章展示（`SUGGESTED` 不参与画布徽标）。

### 2.2 OpenLineage 标准协议接入
- [x] **P2-3** 实现 `POST /api/v1/metadata/lineage/openlineage` Webhook（遵循 C4 令牌鉴权与审计），解析 `RunEvent` 的 `columnLineage` facet 并自动对齐 Catalog 资产。

### 2.3 C5 逐跳 ACL 与敏感标签血缘传播
- [x] **P2-4** 实现 `LineageAclEvaluator` 并在 `LineageService`（列级上下游、影响分析、表级聚合）落实 **Hop-by-Hop ACL**：无权数据集逐跳截断并替换为 `[受限资产]` 脱敏占位节点，前端展示受限分支提示。
- [x] **P2-5** 实现 `LineageSensitivityPropagator`（`detected_by = 'LINEAGE'`）：
  - 上游 `CONFIRMED` 的 `L3/L4` 标签沿 `ACTIVE + DIRECT` 边自动生成下游 `SUGGESTED` 建议；
  - 实现脱敏/哈希函数与清洗规则阻断逻辑并记录阻断证据；
  - 编写传播与阻断单元测试。

---

## Phase 3：语义治理延伸、历史 Diff 与 Agent 归因联动

- [x] **P3-1** 实现 `SemanticLineageBridge`：将 `SemanticObject(METRIC)`、`AnalysisReport`、`QualityRule` 投影为血缘下游节点与影响清单项，前端画布支持治理节点渲染与跳转。
  - 落地说明：`dg-metadata` 内 `SemanticLineageBridge` 直接读指标（`SemanticMapping`→`SemanticObject(METRIC)`）与启用的 `QualityRule`；`AnalysisReport` 属 Governance Space，经 SPI `GovernanceConsumerProvider` 由 `dg-app` 的 `AnalysisReportConsumerProvider` 装配（保持 governance→metadata 单向依赖）。`includeGovernance=true` 接入 `downstream` / `impact` / `datasets/{id}/graph`，动态投影为 `gov:*` 虚拟节点（relation=`CONSUMED_BY`，不落 `md_lineage_*`）；锚点仅取已过逐跳 ACL 的可见节点，且按调用者权限（`metadata:semantic:read` / `metadata:quality:read` / `governance:analysis:read`）二次过滤。前端画布新增治理卡片与 `CONSUMED_BY` 连线样式，指标→语义本体、质检规则→资产质量可跳转；**分析报告当前没有独立前端页面，卡片不提供跳转**。表级报告仅匹配 `subjectType=DATASET` 的已发布报告（报告证据里对指标的引用暂未解析）。
- [x] **P3-2** 实现血缘历史版本 Diff API（`GET /api/v1/metadata/lineage/jobs/{jobId}/diff`）与前端历史变更对比抽屉（`ACTIVE` vs `SUPERSEDED`）。
  - 落地说明：新增 `V48__lineage_edge_revision.sql`（`introduced_at` / `previous_transform_expr` / `previous_dependency_type` / `previous_changed_at`）——同一作业同键边是原地更新的，不加列就无法回看「表达式被改写」。同一次上报内的所有变化共用一个批次时间，Diff 按最近一批返回 `added / removed / modifiedTransform`（relation 变化的下线+上线配成修改）；无任何历史改动时 `hasBaseline=false` 且列表为空。**V48 之前产生的历史行没有 `introduced_at`，只能给出 `removed`，不会伪造 added/modified。** 受限数据集的行按 C5 以 `[受限资产]` 占位。前端 `LineageJobDiffDrawer.vue`，入口：边详情「查看作业历史变更」、概览最近作业「历史变更」。
- [x] **P3-3** 在 `agent-service` 与 Java 内部端点新增只读血缘工具 `lineage_trace_column` / `lineage_impact_analysis`（透传 `userId + workspaceId` + Hop-by-Hop ACL），并补充离线轨迹评测用例（`evals/regression/`）。
  - 落地说明：Java 内部端点 `GET /api/v1/internal/agent/lineage/trace_column|impact_analysis`（`metadata:lineage:read`，按真实用户还原 `WorkspaceContext`，复用 `LineageService` 逐跳 ACL）；结果整形在 `LineageAgentToolService`，证据不足返回 `INCONCLUSIVE` + 原因码；`agent-service/src/tools.ts` 两个工具（只读，列入默认只读工具集）+ `skills/lineage-impact-attribution`；MCP 契约同步；离线评测 `reg-21`~`reg-25`。

---

## Verification & Acceptance Gates（每个 Phase 必跑）

- [x] 元语与防漂移校验：`python3 scripts/verify-primitives.py`
- [x] Space Contract 校验：`python3 docs/space-contracts/verify.py`
- [x] 后端编译与全量测试：`mvn -pl backend/dg-app -am test`（796 tests / 0 failures；含 Flyway V47/V48）
- [x] 前端类型检查与构建：`cd frontend && pnpm type-check && pnpm build`
- [x] Python SQLGlot Sidecar 测试：`cd lineage-parser-service && pytest`（42 passed）
- [x] Agent 侧检查：`cd agent-service && npx tsc --noEmit` 及 `python3 scripts/run-agent-evals.py --suite regression --trials 3 --mode offline`（含 `reg-21`~`reg-25` lineage 用例，pass^3=1.0）

---

## 诚实 Remaining / Non-goals（汇总）

1. **P1-8**：HMS / Glue 视图定义采集未做；对象存储/非 JDBC 源的 VIEW 仍依赖 SQL 导入或 OpenLineage（JDBC：PG/MySQL/Doris/StarRocks 已覆盖）。
2. **P2-2 徽标语义**：画布仅展示 **CONFIRMED** 最高级；`SUGGESTED` / 待复核标签在敏感标签工作台复核。
3. **P3-1**：分析报告无独立前端路由，治理卡片暂不提供报告跳转；报告证据内对指标的引用未解析。