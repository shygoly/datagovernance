# Design: 字段级血缘全链路体系（`column-lineage-e2e`）

## 1. 总体架构与 Space 边界（C1）

```text
┌────────────────────────────────────────────────────────────────────────────┐
│ Frontend (Vue 3 + @vue-flow/core + @dagrejs/dagre)                         │
│  ├─ 血缘工作台概览（KPI / 活跃血缘资产 / 作业）                            │
│  ├─ 表卡片 + 字段端口双层画布（DIRECT 实线 / INDIRECT 虚线 / 路径高亮）    │
│  ├─ 右侧面板：节点详情 + 边详情（SQL/作业/证据）+ 历史 Diff                │
│  └─ 补录与导入：手工补录弹窗 / SQL 解析预览导入弹窗                        │
└───────────────────────────────────┬────────────────────────────────────────┘
                                    │ /api/v1/metadata/lineage/*
┌───────────────────────────────────▼────────────────────────────────────────┐
│ dg-app (Assembly & REST / Webhook / Event Listeners)                       │
│  ├─ LineageController（查询、概览、手工补录、SQL 预览导入、OpenLineage）   │
│  ├─ LineageEventListener（监听作业发布 & ExecutionSucceeded）              │
│  └─ SqlLineageParserClient（HTTP 调用 lineage-parser-service，带超时降级） │
└───────────────┬───────────────────────────────────────────┬────────────────┘
                │                                           │ HTTP (仅传 SQL + Schema)
┌───────────────▼──────────────────────────┐   ┌───────────▼────────────────┐
│ dg-metadata (Metadata Space)             │   │ lineage-parser-service     │
│  ├─ LineageService（双态写入、BFS+ACL、  │   │ (Python FastAPI + SQLGlot) │
│  │   表级聚合、影响分析、历史 Diff）     │   │  ├─ /v1/parse/column       │
│  ├─ LineageCatalogResolver（资产对齐）   │   │  ├─ /health                │
│  ├─ LineageSensitivityPropagator         │   │  └─ 多方言 AST + Schema    │
│  │   （敏感传播 + 脱敏阻断）             │   │     无状态，不连任何 DB    │
│  └─ SemanticLineageBridge                │   └────────────────────────────┘
│      （Metric / Report / Rule 投影）     │
└──────────────────────────────────────────┘
                ▲
                │ 仅读取 JobDef / 编译计划（无环）
┌───────────────┴──────────────────────────┐
│ dg-control (Control Space)               │
│  └─ OfflineSync / DbMigration / BatchDev │
│     / StreamingDev / FileParse / ApiParse│
└──────────────────────────────────────────┘
```

**边界不变量**：
- `dg-runtime` 不依赖 `dg-metadata`；执行完成后通过 `ExecutionEvents`（`ExecutionSucceeded`）通知 `dg-app` 监听器触发血缘晋升。
- `lineage-parser-service` 完全无状态、无数据库连接，仅接收 SQL 文本、方言与当前 Workspace 的脱敏 Schema 定义，返回确定性列级映射 JSON 或 `INCONCLUSIVE` 诊断。

---

## 2. 数据库增量设计（Flyway 迁移）

在现有 [V16__lineage_sensitivity.sql](file:///Users/mac/projects/datagovernance/backend/dg-app/src/main/resources/db/migration/V16__lineage_sensitivity.sql) 基础上新增迁移脚本（遵循当前最新编号 `V47__column_lineage_e2e.sql`）：

```sql
-- 1. 扩展血缘边：执行证据、直接/间接依赖、失效时间、脱敏阻断标记
ALTER TABLE md_lineage_edge
  ADD COLUMN IF NOT EXISTS execution_id    VARCHAR(40),
  ADD COLUMN IF NOT EXISTS dependency_type VARCHAR(16) NOT NULL DEFAULT 'DIRECT', -- DIRECT / INDIRECT
  ADD COLUMN IF NOT EXISTS masking_applied BOOLEAN     NOT NULL DEFAULT FALSE,    -- 是否经脱敏/哈希阻断敏感传播
  ADD COLUMN IF NOT EXISTS masking_evidence VARCHAR(512),
  ADD COLUMN IF NOT EXISTS superseded_at   TIMESTAMPTZ;

-- 放宽并规范 status：PLANNED（编译/发布预估） / ACTIVE（执行确认或权威视图/人工） / SUPERSEDED（历史失效）
CREATE INDEX IF NOT EXISTS idx_md_lineage_edge_status
  ON md_lineage_edge (workspace_id, status);
CREATE INDEX IF NOT EXISTS idx_md_lineage_edge_job_status
  ON md_lineage_edge (workspace_id, job_id, status);

-- 2. 扩展血缘作业：最近执行 ID、解析状态（OK / INCONCLUSIVE / FAILED）与诊断信息
ALTER TABLE md_lineage_job
  ADD COLUMN IF NOT EXISTS last_execution_id VARCHAR(40),
  ADD COLUMN IF NOT EXISTS parse_status      VARCHAR(16) NOT NULL DEFAULT 'OK',
  ADD COLUMN IF NOT EXISTS parse_message     VARCHAR(2048);

-- 3. 扩展血缘节点：支持外部治理对象引用（METRIC / REPORT / QUALITY_RULE）
ALTER TABLE md_lineage_node
  ADD COLUMN IF NOT EXISTS external_ref_type VARCHAR(32),
  ADD COLUMN IF NOT EXISTS external_ref_id   VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_md_lineage_node_ext
  ON md_lineage_node (workspace_id, external_ref_type, external_ref_id)
  WHERE external_ref_id IS NOT NULL;
```

---

## 3. 血缘双态状态机与采集通道设计

### 3.1 边生命周期状态机（D3）

```text
  [作业发布/编译] ──▶ PLANNED ──(ExecutionSucceeded + execution_id)──▶ ACTIVE
                         │                                                │
                         └──(作业变更/下线)──▶ SUPERSEDED ◀──(新版本覆盖)──┘

  [数据库 VIEW / 人工补录 / OpenLineage COMPLETE] ──▶ ACTIVE ──(变更/删除)──▶ SUPERSEDED
```

- 默认图谱查询返回 `ACTIVE` 边；前端提供“包含未运行预估血缘（PLANNED）”开关（虚线浅色标注“预估”）与“查看历史版本（SUPERSEDED）”Diff 面板。
- 同一作业的 `PLANNED` 边在 `ExecutionSucceeded` 后原地晋升为 `ACTIVE` 并回填 `execution_id`、`discovered_at`，旧版本多出的边标 `SUPERSEDED` 并写 `superseded_at`。

### 3.2 四条采集通道细节

1. **结构化作业抽取器（`StructuredJobLineageExtractor`）**：
   - **`OfflineSync`**：读取 `sourceDataSourceId/Database/Schema/Table`、`target*`、`fieldMappings` 与 `fieldRules`。无规则为 `relation=COPY, dependencyType=DIRECT`；有清洗/转换规则为 `relation=TRANSFORM, transformExpr=<规则摘要>`；若 `fieldRules` 包含脱敏/哈希类规则，置 `maskingApplied=true` 并写 `maskingEvidence`。
   - **`DbMigration`**：按源表字段清单 + `tablePrefix`/`tableSuffix`/`lowercaseNames` 规则映射目标列，产出 `COPY` 边。
   - **`FileParse` / `ApiParse`**：将文件/API Schema 节点（`node_type=COLUMN`, `qualified_name=file://{source}/{field}`）连向目标表列。

2. **SQLGlot Sidecar 契约（`lineage-parser-service`）**：
   - 目录：`lineage-parser-service/`（独立轻量 Python 3.11+ FastAPI 项目，含 `pyproject.toml`、`Dockerfile`、单测）。
   - 请求 `POST /v1/parse/column`：
     ```json
     {
       "dialect": "postgres | mysql | doris | starrocks | oracle | spark | tsql | hive",
       "sql": "INSERT INTO dws.order_summary (user_id, total_amt) SELECT ...",
       "defaultSchema": "public",
       "catalogSchema": {
         "ods.orders": ["id", "user_id", "amount", "status"]
       }
     }
     ```
   - 响应：
     ```json
     {
       "status": "OK | INCONCLUSIVE | ERROR",
       "sqlHash": "sha256...",
       "statements": [
         {
           "targetTable": "dws.order_summary",
           "edges": [
             {
               "sourceTable": "ods.orders",
               "sourceColumn": "amount",
               "targetTable": "dws.order_summary",
               "targetColumn": "total_amt",
               "dependencyType": "DIRECT",
               "relation": "TRANSFORM",
               "transformExpr": "SUM(COALESCE(o.amount, 0))",
               "maskingApplied": false
             },
             {
               "sourceTable": "ods.orders",
               "sourceColumn": "status",
               "targetTable": "dws.order_summary",
               "targetColumn": "total_amt",
               "dependencyType": "INDIRECT",
               "relation": "FILTER",
               "transformExpr": "WHERE o.status = 'PAID'",
               "maskingApplied": false
             }
           ],
           "warnings": []
         }
       ],
       "diagnostics": []
     }
     ```
   - **C8 降级规则**：当 SQL 含动态拼接、不支持语法、`SELECT *` 且 `catalogSchema` 缺失源表定义，或 Sidecar 超时/不可达时，返回 `status = INCONCLUSIVE` 与明确原因；写入作业 `parse_status = INCONCLUSIVE`、`parse_message`，**绝不猜列**。

3. **数据库视图自动采集**：
   - 扩展 `CatalogAssetService`（元数据刷新链路），当对象 `kind` 为 `VIEW` / `MATERIALIZED_VIEW` 且连接器提供 `viewDefinition` 时，自动组装 `catalogSchema` 调用 `SqlLineageParserClient` 写入 `jobType=VIEW` 的 `ACTIVE` 血缘。

4. **OpenLineage Webhook**：
   - `POST /api/v1/metadata/lineage/openlineage`，支持 OpenLineage 1.x `RunEvent`（`COMPLETE` 状态写入 `ACTIVE`），解析 `outputs[].facets.columnLineage.fields` 的 `inputFields` 与 `transformationType`/`transformationDescription`。
   - 认证遵循 C4：校验 Workspace 绑定令牌（携带真实授权主体与 `metadata:lineage:manage` 权限），写入审计日志。

---

## 4. C5 逐跳权限过滤（Hop-by-Hop ACL）设计

在 `LineageService` 中引入 `LineageAclEvaluator` 接口（由 `dg-app` 装配，复用平台数据权限/数据集可见性判定）：

```text
BFS(frontier):
  for each edge in queryActiveEdges(workspaceId, frontier):
    nextNode = upstream ? edge.fromNode : edge.toNode
    if !acl.canViewDataset(userContext, nextNode.catalogDatasetId):
      // 1. 不将该节点加入 nextFrontier（立即截断更深遍历）
      // 2. 生成稳定哈希占位 ID: "restricted:" + sha256(workspaceId + ":" + nextNode.id)[0..12]
      // 3. 占位节点仅含 { nodeType: "RESTRICTED", displayName: "[受限资产]", qualifiedName: "[受限资产]" }
      // 4. 边隐藏 transformExpr，仅保留脱敏后的连通提示，并递增 restrictedCount
      continue
    else:
      加入结果集与 nextFrontier
```

- `GraphView` 响应新增 `restrictedCount: int` 与 `truncatedByAcl: boolean`，前端展示明确提示（“当前图谱包含 N 个受权限限制的分支，已按最小权限原则截断”）。
- 影响分析（`impact`）与表级图（`datasetGraph`）复用同一逐跳 ACL 拦截逻辑，无绕过路径。

---

## 5. 敏感标签沿血缘传播与脱敏阻断（D6）

新增 `LineageSensitivityPropagator`（位于 `dg-metadata`）：
- **触发时机**：
  1. 敏感标签在 `SensitivityService` 被人工复核为 `CONFIRMED`（且级别 ∈ `L3, L4`）时，沿下游 `ACTIVE` + `DIRECT` 边 BFS 传播（上限 5 层）；
  2. `LineageService.recordJob` 产生新的 `ACTIVE` + `DIRECT` 边时，检查上游列是否已有 `CONFIRMED` 敏感标签并触发传播。
- **脱敏阻断规则**：
  - 若边的 `masking_applied = true`，或 `transform_expr` 命中确定性脱敏/不可逆哈希函数白名单（`MASK`、`MD5`、`SHA1`、`SHA256`、`HASH`、`ENCRYPT`、`DESENSITIZE`），则**停止向下游传播该高密级标签**，并在血缘边/敏感证据中记录 `blockedByMasking` 理由。
- **落盘规则（C9）**：
  - 仅在目标列尚未存在同 `category` 的 `CONFIRMED` 标签时，插入/更新 `md_column_sensitivity`，设 `status = 'SUGGESTED'`、`detected_by = 'LINEAGE'`、`confidence = 0.85 * edge.confidence`，`evidence_json` 记录上游源列、经过的边 ID 与作业名，进入人工复核工作台。

---

## 6. 前端表卡片工作台设计（D4）

### 6.1 依赖与设计令牌
- 前端新增 `@vue-flow/core`、`@vue-flow/background`、`@vue-flow/controls`、`@vue-flow/minimap`、`@dagrejs/dagre`。
- 所有颜色、阴影、边框、状态标签严格使用 `frontend/src/styles/tokens.css` 中的 `var(--dg-*)`，禁止硬编码十六进制色值。

### 6.2 视图模式与组件划分
- **概览态（`LineageOverviewPanel.vue`）**：当未选择具体数据集/字段时展示：
  - 统计卡片：已建血缘数据集数、活跃列级边数、活跃血缘作业数、敏感传播待复核数；
  - 快捷列表：最近更新的血缘数据集（带上下游边数徽标，点击直达）+ 最近血缘作业列表（含 `OK`/`INCONCLUSIVE` 状态与错误详情）；
  - 顶部操作栏：**手工补录血缘**、**SQL 解析导入**。
- **表卡片画布（`LineageFlowCanvas.vue`）**：
  - **自定义节点 `TableLineageNode.vue`**：
    - 卡片头部：表名、数据源/库限定名、资产敏感最高等级徽标（`L1–L4`）；
    - 卡片主体：参与当前血缘子图的字段行（每行左右两侧为 Vue Flow `Handle` 端口，展示字段名、数据类型、敏感小标、中心高亮标）；底部提供“展开表内其余 N 个字段”按钮；
    - 特殊节点：`RESTRICTED` 脱敏占位卡（灰色虚线框 + 锁图标 `[受限资产]`）、`METRIC` / `REPORT` / `QUALITY_RULE` 治理卡片。
  - **连线样式**：
    - `ACTIVE + DIRECT`：主色实线带箭头；
    - `ACTIVE + INDIRECT`：次色虚线（`stroke-dasharray`）；
    - `PLANNED`：浅色点划线 + “预估”标签；
    - 路径高亮：点击任意字段或连线时，高亮从该节点出发的完整上游/下游可达子图，其余节点与边透明度降至 `0.25`。
- **右侧检查器（`LineageInspectorDrawer.vue`）**：
  - Tab/上下文自动切换：**节点详情**（字段元数据、敏感标签、关联指标/质检规则、跳转资产详情、设为中心）与**边详情**（高亮 SQL `transformExpr`、作业名称与类型、`execution_id`、来源/置信度、是否脱敏阻断、人工废弃/修正按钮）。
- **弹窗组件**：
  - `ManualLineageDialog.vue`：选择源表/源字段 → 目标表/目标字段、关系类型、`DIRECT/INDIRECT`、转换表达式，调用 `POST /api/v1/metadata/lineage/edges`。
  - `SqlLineageImportDialog.vue`：选择方言、作业名称、粘贴 SQL → 调用预览接口展示解析结果（或 `INCONCLUSIVE` 告警）→ 确认后入库。

---

## 7. Phase 3 治理延伸与 Agent 工具契约

1. **治理对象投影（`SemanticLineageBridge`）**：
   - 在下游影响分析与图谱展开时（勾选“包含治理消费方”），动态投影：
     - `SemanticMapping` 绑定到目标表/列的 `SemanticObject(METRIC)`；
     - 引用该指标/数据集的已发布 `AnalysisReport`；
     - 作用在目标表/列的 `Rule`（质检/合规规则）。
2. **血缘历史 Diff API**：
   - `GET /api/v1/metadata/lineage/jobs/{jobId}/diff`：对比同一作业当前 `ACTIVE` 边与最近一批 `SUPERSEDED` 边，返回 `added / removed / modifiedTransform` 列表。
3. **Agent 只读工具**：
   - 新增 `lineage_trace_column`（上下游溯源）与 `lineage_impact_analysis`（影响评估，含下游表、指标、报告、质检规则），走 Java 内部受控端点，严格透传 `userId + workspaceId` 并执行 Hop-by-Hop ACL，归因证据不足时返回 `INCONCLUSIVE`。
