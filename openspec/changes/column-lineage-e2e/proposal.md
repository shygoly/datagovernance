# Proposal: 字段级血缘全链路体系（Column Lineage End-to-End）

- **Change ID**: `column-lineage-e2e`
- **Status**: Draft
- **Depends On**: `lineage-workspace`（P10.4 基础表与工作台）、`lineage-table-overview`（表级聚合）、`semantic-ontology-kernel`（Phase 3 指标节点延伸）
- **Constitution Alignment**: 严格遵循 `CONSTITUTION.md`（C1–C10）、`SPACE-MODEL.md`、`GOVERNANCE-MODEL.md`（C.6 Lineage / C.2 Fact）、`frontend/DESIGN.md`

---

## Why

平台在 P10.4 已落地字段血缘的 PostgreSQL 存储表（`md_lineage_node` / `md_lineage_job` / `md_lineage_edge`）、查询 API（`LineageController` / `LineageService`）与基础工作台（`LineageView.vue`），但当前页面在实际环境中处于**空状态**，核心存在五处结构性缺口：

1. **血缘生产链路完全缺失**：全仓库除单元测试外，无任何业务模块调用 `LineageService.recordJob()` 或 `recordManualEdge()`。离线同步、整库迁移、批/流 SQL 作业与数据库视图均未自动上报血缘。
2. **工作台冷启动与人工补录能力缺失**：打开 `/metadata/lineage` 默认空白，缺乏“已有血缘的资产/作业概览”引导，也未开放前端手工补录或 SQL 解析导入入口。
3. **前端可视化表达力不足**：现有 `LineageGraph.vue` 使用 ECharts 散点连线，无法按“表容器 + 字段端口”呈现表-列层级归属，也无法点击边查看转换表达式（`transformExpr`）和作业来源（`LineageJob`）。
4. **合规与治理闭环未打通**：
   - `LineageService` 图遍历仅做 `workspace_id` 租户隔离，未落实 `CONSTITUTION.md` **C5 图遍历逐跳权限过滤（Hop-by-Hop ACL）**；
   - `md_column_sensitivity.detected_by = 'LINEAGE'` 尚未实现，敏感标签无法沿血缘自动向下游传播；
   - 物理字段血缘未延伸至语义层指标（`SemanticObject(METRIC)`）、分析报告（`AnalysisReport`）与质量规则（`Rule`）。
5. **多方言 SQL 列级解析空白**：缺少支持 CTE、子查询、`SELECT *` 展开与多方言（PostgreSQL / MySQL / Doris / StarRocks / Oracle / Spark / Flink SQL）的列级血缘解析能力。

---

## Design Decisions（已对齐决策）

本提案在立项前已通过设计评审敲定以下核心决策：

| # | 决策项 | 结论 |
|---|---|---|
| D1 | 立项拆分 | 单一总立项 `column-lineage-e2e`，内部按 Phase 1 / Phase 2 / Phase 3 划分里程碑与可独立验收任务 |
| D2 | SQL 解析引擎 | 引入 **独立轻量 Python HTTP Sidecar（`lineage-parser-service`，基于 SQLGlot）**，提供常驻多方言列级解析与超时隔离；解析失败或证据不足时显式降级返回 `INCONCLUSIVE` 与诊断原因（C8） |
| D3 | 作业血缘时机与状态 | **双态联动**：作业编译/发布时生成 `PLANNED` 预估血缘，执行成功（`ExecutionSucceeded`）后晋升为 `ACTIVE` 并绑定 `execution_id` 执行证据；历史失效边标 `SUPERSEDED` |
| D4 | 前端画布技术栈 | 引入 **`@vue-flow/core` + `@dagrejs/dagre`**，以 Vue 3 原生组件构建“表卡片 + 字段端口”双层列级血缘图，严守 `--dg-*` 设计令牌 |
| D5 | C5 逐跳 ACL 策略 | **逐跳截断 + 脱敏占位**：BFS 每一跳按 `UserContext` 实时校验数据集权限；遇到无权数据集立即停止向该分支更深处遍历，返回脱敏占位节点 `[受限资产]`（不泄露表名/列名/SQL，防侧信道） |
| D6 | 敏感度血缘传播 | 仅沿 **`ACTIVE` + `DIRECT`** 边自动生成 `SUGGESTED` 敏感建议供人工复核；遇到显式脱敏/哈希规则（如 `MASK`/`HASH`/`MD5`/`SHA`）时自动阻断高密级传播并记录阻断证据 |
| D7 | Phase 3 治理延伸 | 血缘终态节点覆盖 **`SemanticObject(METRIC)` + `AnalysisReport` + `QualityRule`**，并向 Agent 开放只读血缘溯源与影响分析工具 |

---

## What Changes

### Phase 1：采集主链路与工作台可用化（让页面“活起来”）
1. **数据模型增量迁移**：为 `md_lineage_edge` 增加 `execution_id`、`dependency_type`（`DIRECT`/`INDIRECT`）、`superseded_at` 及 `status` 扩展（支持 `PLANNED`/`ACTIVE`/`SUPERSEDED`）；为 `md_lineage_node` 扩展节点类型并补齐 Catalog 对齐能力。
2. **平台内建作业自动采集**：
   - 在 `dg-control` / `dg-app` 监听作业发布与 `ExecutionSucceeded` 事件；
   - 自动抽取 `OfflineSync`（`fieldMappings` + `fieldRules`）、`DbMigration`（含表/列命名规则）、`FileParse`、`ApiParse` 的列级血缘，实现 `PLANNED → ACTIVE` 双态流转。
3. **Python SQLGlot 解析 Sidecar（`lineage-parser-service`）与视图/SQL 作业采集**：
   - 搭建独立轻量 FastAPI + SQLGlot 服务，接收 `{ dialect, sql, catalogSchema }`，输出列级 `DIRECT`/`INDIRECT` 边与表达式；失败时返回 `INCONCLUSIVE` 诊断对象；
   - Catalog 刷新时同步采集数据库 `VIEW` / `MATERIALIZED_VIEW` 定义并触发解析；
   - `BatchDev` / `StreamingDev` 的 SQL 作业在发布与执行成功时调用解析并入库。
4. **前端概览态 + 补录/导入 + 边详情**：
   - `/metadata/lineage` 未选中资产时展示**血缘工作台概览**（覆盖率 KPI、最近活跃血缘数据集/作业快捷入口）；
   - 数据集与字段选择器展示血缘计数徽标；
   - 提供**手工补录血缘**弹窗与**粘贴 SQL 解析预览/导入**弹窗（受 `metadata:lineage:manage` 权限保护）；
   - 右侧详情面板同时支持**节点详情**与**连线（边）详情**（转换 SQL、所属作业、来源、状态、置信度）。

### Phase 2：专业列级画布、OpenLineage 与安全合规闭环
1. **表卡片 + 字段端口双层画布重构**：
   - 引入 `@vue-flow/core` + `@dagrejs/dagre` 替换/升级列级画布；
   - 节点为表容器卡片（含数据源类型、表名、敏感徽标、字段列表与展开/折叠），连线精确连接字段端口，区分实线（`DIRECT`）与虚线（`INDIRECT`），支持端到端路径高亮与逐跳展开。
2. **OpenLineage 标准协议接入**：
   - 新增 `POST /api/v1/metadata/lineage/openlineage` Webhook（基于 Workspace 授权令牌/OBO 身份，严守 C4 无共享超级账号），解析 `columnLineage` facet 并自动对齐 Catalog 资产。
3. **C5 逐跳 ACL（Hop-by-Hop ACL）**：
   - 在 `LineageService` 的列级/表级 BFS 与影响分析中注入逐跳数据集鉴权，无权节点截断分支并替换为 `[受限资产]` 占位节点。
4. **敏感标签沿血缘传播（`detected_by = 'LINEAGE'`）**：
   - 上游列敏感标签确认（`CONFIRMED`）或新 `ACTIVE + DIRECT` 边落盘时，自动向下游传播 `SUGGESTED` 敏感建议；识别脱敏/哈希算子时自动阻断高密级传播并记录阻断证据。

### Phase 3：语义治理延伸、历史 Diff 与 Agent 归因联动
1. **治理对象血缘延伸**：将 `SemanticObject(METRIC)`、`AnalysisReport`、`QualityRule` 纳入血缘下游节点视图，支持端到端影响分析（“改物理字段 → 影响哪些下游表、质检规则、业务指标与分析报告”）。
2. **血缘历史版本与 Diff**：支持按作业或时间点查看 `SUPERSEDED` 历史边并与当前 `ACTIVE` 血缘对比差异。
3. **Agent 只读血缘工具接入**：在 `agent-service` 与 Java 内部端点开放受 `userId + workspaceId` 实时鉴权与 Hop-by-Hop ACL 保护的血缘溯源/影响分析只读工具，赋能故障归因与变更影响评估。

---

## Non-goals（明确不做）

- **不引入独立图数据库或重型外部元数据栈**（不引入 Neo4j / JanusGraph / DataHub / Apache Atlas / Elasticsearch），继续以 PostgreSQL 邻接表 + 递归/BFS 查询作为唯一血缘存储。
- **不将原始业务数据或 L3/L4 敏感明细传给 SQL 解析服务或外部大模型**：`lineage-parser-service` 仅接收 SQL 文本与脱敏后的 Schema 元数据（表名、列名、类型）。
- **不开放对外部业务库（HIS/EMR/源业务库）的写回操作**（C6）。
- **不将解析失败的 SQL 臆造/猜测为高置信度血缘**：无法确定列映射时必须显式标记 `INCONCLUSIVE` 或退化为可证实的表级依赖（C8）。
- **不改变既有表级聚合不落冗余表的设计原则**（表级视图继续从列级血缘按 `catalogDatasetId` 聚合，辅以无列映射时的表级边补充）。

---

## Constitution Check（C1–C10）

| 公理 | 检查项与合规设计 |
|---|---|
| **C1 Space 边界** | `dg-runtime` 不依赖 `dg-metadata`；作业血缘由 `dg-control` / `dg-app` 消费执行事件并写入 `dg-metadata`；`lineage-parser-service` 为无状态纯计算 Sidecar，不直连业务库或元数据库；前端仅消费 `/api/v1`。 |
| **C2 治理为编译输入** | 同步/SQL 作业在编译阶段即产出 `PLANNED` 血缘与敏感传播预检，使影响评估前置到发布前。 |
| **C3 确定性计算优先** | SQL 血缘解析完全由确定性 AST 解析器（SQLGlot）+ Catalog Schema 完成，绝不依赖 LLM 猜测字段映射。 |
| **C4 零信任与最小代理** | OpenLineage Webhook 与 Agent 血缘工具均校验真实用户/OBO 身份与 Workspace 成员资格，禁止共享超级账号；手工补录/导入受 `metadata:lineage:manage` 保护。 |
| **C5 纵深隔离与逐跳 ACL** | 严守 `workspace_id` 隔离；BFS 遍历强制执行 Hop-by-Hop ACL，无权节点截断并脱敏占位；不向外部公网模型发送敏感明细。 |
| **C6 受控写入点** | 所有血缘写入统一收口至 `LineageService`（幂等 Upsert + 全程留痕），不新增旁路写入通道。 |
| **C7 宿主掌握画盘** | 前端血缘画布与补录/导入交互完全由宿主白名单组件渲染，Agent 仅调用只读查询工具，不注入可执行代码。 |
| **C8 证据与 INCONCLUSIVE** | `ACTIVE` 边绑定 `execution_id`/`sql_hash` 证据；SQL 解析失败或 `SELECT *` 缺 Schema 时显式返回 `INCONCLUSIVE` 与原因，绝不静默伪造边。 |
| **C9 受控演进** | 敏感度血缘传播仅产出 `SUGGESTED` 建议，需人工复核后方可晋升为 `CONFIRMED`，不自动覆写权威标签。 |
| **C10 确定性与概率解耦** | 血缘采集、解析、存储、传播全链路均为确定性编排；Agent 仅在只读消费侧调用工具辅助归因。 |
