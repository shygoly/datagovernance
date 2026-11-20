# Technical Design: 统一本体语义元数据内核与业务知识图谱（Semantic Ontology Kernel）

- **Change ID**: `semantic-ontology-kernel`
- **Aligned Proposal**: [proposal.md](./proposal.md)
- **核心目标**：在 `dg-metadata` 内建立**人机共用的单一语义真相源**——用 MicroStrategy 式的强类型逻辑元数据管准“怎么算、怎么 Join”，再在同一张图上叠加面向 AI 的业务知识图谱管懂“业务含义、口径避坑与因果语境”。

---

## 1. 为什么是“双层同构”，而不是两套孤立系统

如果把“BI 逻辑元数据”和“AI 知识图谱”拆成两套库（例如指标在 SuperSonic、图谱在 Neo4j、目录在 PostgreSQL），必然出现三大问题：**口径漂移（人看一套、AI 看另一套）、权限割裂（图谱漏掉物理表 ACL）、运维爆炸**。

本设计采用**“一套对象表 + 一套关系表 + 一套映射表”的双层同构模型**：

```text
                ┌────────────────────────────────────────────────────┐
                │   Layer 2 · 业务知识图谱覆盖层（给 AI 理解业务）     │
                │   CONCEPT / PROCESS + SEMANTIC/CAUSAL 边           │
                │   + ai_context_json（别名 / 歧义辨析 / 口径坑）      │
                └───────────────────────┬────────────────────────────┘
                                        │ MAPS_TO_CONCEPT / CONSTRAINS / AFFECTS
                ┌───────────────────────▼────────────────────────────┐
                │   Layer 1 · 强类型逻辑元数据层（MicroStrategy 风格） │
                │   ENTITY / ATTRIBUTE / DIMENSION / FACT / METRIC   │
                │   + STRUCTURAL 边（HAS_ATTRIBUTE / HIERARCHY / JOIN）│
                └───────────────────────┬────────────────────────────┘
                                        │ md_semantic_mapping（多对多 + Form 角色）
                ┌───────────────────────▼────────────────────────────┐
                │   Layer 0 · 既有物理资产目录（dg-metadata 现状）     │
                │   CatalogDataset / CatalogColumn / Sensitivity     │
                └────────────────────────────────────────────────────┘
                                        │ 确定性语义 SQL 编译 / 受控子图检索
         ┌──────────────────────────────┼──────────────────────────────┐
         ▼                              ▼                              ▼
   Native Semantic Engine         MCP 工具 & Agent 子图          血缘与影响分析 / AnalysisReport
   (Compiler + DataAccessGateway) (NL2SemanticSpec)              (物理列 → 指标全链路)
   [过渡期支持双跑，最终下线 SuperSonic]
```

---

## 2. 数据模型设计（PostgreSQL · `V37__semantic_ontology_kernel.sql`）

全部表均强制包含 `workspace_id`，纳入 PostgreSQL RLS（延续 `V35` 策略），采用双时间态版本字段支持历史口径回溯与一键回滚。

### 2.1 `md_semantic_object`（统一逻辑对象与知识节点注册表）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `VARCHAR(64)` PK | 平台全局唯一 `Object ID`（跨版本逻辑标识可用 `logical_id` + `version_no` 或独立版本快照表） |
| `workspace_id` | `VARCHAR(64)` NOT NULL | 顶层租户隔离边界（RLS 强制过滤） |
| `code` | `VARCHAR(128)` NOT NULL | 空间内唯一机读编码（如 `metric.order.gmv_valid`、`attr.org.dept`、`concept.order.valid`） |
| `name` | `VARCHAR(200)` NOT NULL | 中文/业务标准名称 |
| `object_type` | `VARCHAR(32)` NOT NULL | **Layer 1（可计算）**：`ENTITY` / `ATTRIBUTE` / `DIMENSION` / `FACT` / `METRIC`<br>**Layer 2（知识图谱）**：`CONCEPT` / `PROCESS` |
| `domain_id` | `VARCHAR(64)` | 所属业务域（复用既有 `md_domain`） |
| `standard_ref` | `VARCHAR(128)` | 绑定的权威 `Standard` 引用（`METRIC` / 编码类 `ATTRIBUTE` 发布时做 C2 存在性校验） |
| `glossary_term_id` | `VARCHAR(64)` | 可选关联既有 `md_glossary_term.id`，保证平滑兼容 |
| `spec_json` | `JSONB` NOT NULL | **确定性计算定义**（随 `object_type` 分型，见 §2.5） |
| `ai_context_json` | `JSONB` NOT NULL | **面向 AI 的业务理解卡**（见 §3.1，严禁夹带 L3/L4 明细） |
| `max_sensitivity` | `VARCHAR(16)` | 由绑定物理列的最高敏感级别投影计算（`L1–L4`） |
| `status` | `VARCHAR(24)` NOT NULL | `DRAFT` / `PROPOSED`（AI 暂存） / `PUBLISHED` / `DEPRECATED` |
| `version_no` | `INT` NOT NULL | 每次 `PUBLISHED` 递增，旧版落入 `md_semantic_object_version` 不可变快照 |
| `valid_from` / `valid_to` | `TIMESTAMPTZ` | 双时间态业务生效区间（支持历史口径复现） |
| `owner_username` | `VARCHAR(100)` | 语义资产责任人 |
| `content_digest` | `VARCHAR(64)` | `spec_json + mappings + links` 规范化 SHA-256（供 C8 审计指纹引用） |

### 2.2 `md_semantic_mapping`（逻辑对象 ↔ 物理表/字段的多对多映射）

对标 MicroStrategy 的 **Attribute Form / Fact Expression → Physical Column** 机制：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `VARCHAR(64)` PK | 映射唯一 ID |
| `workspace_id` | `VARCHAR(64)` NOT NULL | 租户隔离 |
| `object_id` | `VARCHAR(64)` NOT NULL | 指向 `md_semantic_object.id` |
| `mapping_role` | `VARCHAR(32)` NOT NULL | `ID`（主键形态）、`DESC`（展示名形态）、`CODE`（标准码形态）、`MEASURE`（度量源列）、`TIME_KEY`（时间键）、`FILTER_KEY`（过滤列） |
| `catalog_dataset_id` | `VARCHAR(64)` NOT NULL | 指向既有 `md_catalog_dataset.id`（物理表） |
| `catalog_column_id` | `VARCHAR(64)` | 指向既有 `md_catalog_column.id`（物理列；复合表达式时可为空） |
| `expression_sql` | `TEXT` | 可选只读列级表达式（如 `COALESCE(col_a, 0)`；发布前经 `dg-data-spi` 只读 SQL 护栏校验） |
| `is_lookup_table` | `BOOLEAN` | 是否为主维表/Lookup 表（指导查询引擎优先选择维表关联） |
| `priority` | `INT` DEFAULT 100 | 多表存在同一逻辑属性时的优先级 |
| `status` | `VARCHAR(24)` | `DRAFT` / `PROPOSED` / `PUBLISHED` |

### 2.3 `md_semantic_link`（结构关系 + 知识图谱语义/因果边）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `VARCHAR(64)` PK | 边唯一 ID |
| `workspace_id` | `VARCHAR(64)` NOT NULL | 租户隔离 |
| `from_object_id` | `VARCHAR(64)` NOT NULL | 起点 `md_semantic_object.id` |
| `to_object_id` | `VARCHAR(64)` NOT NULL | 终点 `md_semantic_object.id` |
| `edge_category` | `VARCHAR(16)` NOT NULL | `STRUCTURAL`（SQL/Join 可计算关系） / `SEMANTIC`（概念/分类关系） / `CAUSAL`（约束/归因关系） |
| `link_type` | `VARCHAR(32)` NOT NULL | **STRUCTURAL**：`HAS_ATTRIBUTE`、`HAS_FACT`、`HIERARCHY_PARENT`、`RELATES_TO`、`DERIVED_FROM`<br>**SEMANTIC**：`IS_A`、`PART_OF`、`SYNONYM_OF`、`MAPS_TO_CONCEPT`、`PRECEDES`<br>**CAUSAL**：`CONSTRAINS`、`EXCLUDES`、`AFFECTS` |
| `cardinality` | `VARCHAR(16)` | `1:1` / `1:N` / `N:1` / `N:M`（防聚合扇出倍增，指导 SQL 与 AI） |
| `join_spec_json` | `JSONB` | 确定性 Join 条件（`STRUCTURAL` 边填写：`[{leftColRef, rightColRef, joinType}]`） |
| `rationale` | `TEXT` | 业务关系说明（给 AI 解释“为什么这两者相关/互斥”） |
| `source_kind` | `VARCHAR(24)` | `HUMAN` / `LINEAGE_DERIVED` / `AI_PROPOSED` |
| `status` | `VARCHAR(24)` | `DRAFT` / `PROPOSED` / `PUBLISHED` / `REJECTED` |
| `valid_from` / `valid_to` | `TIMESTAMPTZ` | 双时间态生效区间 |

### 2.4 `md_semantic_object_version`（不可变发布快照）
- 每次对 `md_semantic_object` 执行 `Publish` 时，冻结其 `spec_json`、`ai_context_json`、关联 `mappings` 与 `links` 的规范化 JSON 并计算 `content_digest`（SHA-256），写入不可变版本表。
- 任意历史 `AnalysisReport` 或导出记录引用 `(object_id, version_no, content_digest)` 即可 100% 还原当时的逻辑口径与物理映射。

### 2.5 `spec_json` 分型契约示例

```json
// 1) ATTRIBUTE（MicroStrategy 风格多 Form 属性）
{
  "entityId": "obj_entity_dept",
  "forms": [
    { "role": "ID", "dataType": "STRING", "isDisplay": false },
    { "role": "DESC", "dataType": "STRING", "isDisplay": true }
  ],
  "defaultSort": "ID ASC"
}

// 2) DIMENSION（含层级钻取路径）
{
  "hierarchy": ["obj_attr_hospital", "obj_attr_dept", "obj_attr_ward"],
  "isTimeDimension": false
}

// 3) METRIC（受控确定性指标，绝不靠 LLM 心算）
{
  "metricKind": "DERIVED",
  "aggregation": "RATIO",
  "numeratorObjectId": "obj_metric_readmit_30d_cnt",
  "denominatorObjectId": "obj_metric_discharge_cnt",
  "unit": "%",
  "defaultFilters": [
    { "objectId": "obj_attr_order_status", "op": "NOT_IN", "values": ["CANCELED", "TEST"] }
  ],
  "allowedDimensions": ["obj_dim_calendar", "obj_dim_org"],
  "grain": "Per Encounter / Per Day"
}
```

---

## 3. 知识图谱如何真正帮 AI 理解业务（不沦为花瓶）

很多团队做知识图谱失败，是因为只画了一堆空洞的三元组，却没跟真实查询与防幻觉绑定。本设计让知识图谱在 **4 个具体环节**直接服务 AI：

### 3.1 `ai_context_json`：每个语义对象的“防误解说明书”

```json
{
  "aliases": ["有效成交额", "净GMV", "剔除退单销售额"],
  "disambiguation": "统计实际经营收入时使用本指标；若用户问'含未支付下单金额'，应改用 metric.order.gross_gmv 而非本指标。",
  "grain": "订单行粒度聚合至订单粒度后求和；严禁直接与物流明细表 1:N 关联后直接 SUM，否则会产生扇出重复计数。",
  "caveats": ["2025-07-01 起口径新增剔除内部测试账号", "退单统计以退款完成时间为准，非申请时间"],
  "typicalQuestions": ["上月各区域有效GMV同比", "剔除退单后的净销售额趋势"],
  "safeEnumExamples": ["PAID", "COMPLETED"]
}
```
> **安全红线（C5）**：发布校验器强制扫描 `ai_context_json` 与 `rationale`，拒绝包含手机号/身份证等 L3+ 明文 PII。

### 3.2 四类高价值知识图谱边（直接解决 AI 问数与归因痛点）

| 痛点 | 图谱边类型 | AI 实际用法 |
|---|---|---|
| **口语/同义词找不到指标** | `SYNONYM_OF` / `MAPS_TO_CONCEPT` / `IS_A` | 用户问“急诊重症留观人数”，沿 `IS_A` 与 `MAPS_TO_CONCEPT` 精确定位到对应 `METRIC` 与过滤 `ATTRIBUTE` |
| **多表关联不会写或写错扇出** | `RELATES_TO`（带 `cardinality` + `join_spec_json`） | AI 从图谱读取确定性 Join 路径与 `1:N` 提示，避免幻觉猜外键或错误 `SUM` |
| **漏掉隐含过滤或口径冲突** | `CONSTRAINS` / `EXCLUDES` | 展开 `有效GMV` 子图时自动带出 `退单 EXCLUDES 有效GMV`、`测试单 EXCLUDES 有效GMV`，防止口径偏差 |
| **异常指标归因无从下手** | `AFFECTS` / `PRECEDES` / `DERIVED_FROM` | 当某指标突降时，沿 `DERIVED_FROM`（拆分子/分母）与 `AFFECTS`/`PRECEDES`（查上游业务流程环节）系统性排查，而非胡乱猜测 |

### 3.3 受控子图检索算法（PostgreSQL 递归 CTE + 逐跳 ACL · 严格满足 C5）

为避免把整库元数据塞进 Prompt 导致上下文膨胀和越权泄露，提供 `POST /api/v1/metadata/semantic/subgraph/search`：

1. **Step 1 · 检索前鉴权与种子召回（Pre-retrieval ACL）**：
   - 仅检索 `workspace_id = :ws AND status = 'PUBLISHED' AND (valid_to IS NULL OR valid_to > now())` 的节点；
   - 结合 `name` / `code` / `ai_context_json->aliases` 全文检索（未来可叠加 `pgvector` 语义向量召回）取 Top-K 种子节点（默认 $K \le 5$）；
   - **过滤掉**底层全部映射表对当前 `userId` 均无读权限的节点。
2. **Step 2 · 有界 $k$-Hop 逐跳权限扩展（Hop-by-Hop ACL，默认 $k = 2$，硬上限 $k = 3$）**：
   - 使用 PostgreSQL `WITH RECURSIVE` 沿 `md_semantic_link`（仅 `PUBLISHED` 边）展开关联 `ATTRIBUTE`、`DIMENSION`、`JOIN` 边、`CONSTRAINS/EXCLUDES/AFFECTS` 边；
   - **每一跳**检查目标节点的 `workspace_id`、敏感分级与当前用户资产读权限；遇到无权节点立即剪枝（不继续向下穿越），杜绝“经合法起点 2 跳穿透到无权表”。
3. **Step 3 · 物理映射裁剪与 Token 预算封装**：
   - 仅附带当前用户有权访问的 `md_semantic_mapping`；
   - 若请求将发送给 `EXTERNAL` 公网模型，自动剥离任何 `max_sensitivity ≥ L3` 字段的样例值（仅保留脱敏 Schema/逻辑名，守 C5）；
   - 返回紧凑 JSON 子图（对象卡 + 合法关系边 + 避坑提示 + 语义快照版本号列表），供 `agent-service` 直接注入本轮推理上下文。

---

## 4. 完全替换 SuperSonic：原生语义问数引擎与退场路线

代码实查表明，SuperSonic 在本仓库的唯一耦合边界仅为 `SuperSonicClient` 的 2 个能力（`datasets()` 与 `ask()`），其余鉴权、脱敏、临时授权、截断、导出、日志、审计与看板均已在 `InsightQueryService` 内实现。因此本方案采用**“双引擎平滑过渡 → 达标后完全下线 SuperSonic”**策略。

### 4.1 引擎抽象层（`InsightQueryEngine`）

在 `backend/dg-app/.../insight/` 抽取统一引擎接口，配置开关 `dg.insight.engine=native|supersonic`（过渡期支持双跑评测）：

```java
public interface InsightQueryEngine {
    String engineId(); // "native" | "supersonic"
    List<DatasetOption> listSemanticViews(Caller caller, String workspaceId);
    EngineAskResult executeAsk(Caller caller, String workspaceId, AskCommand cmd);
}
```

- `listSemanticViews`：原生引擎直接从 `dg-metadata` 读取当前 `Workspace` 且用户有权访问的已发布语义主题/域视图，不再依赖 SuperSonic 的 `/api/chat/conf/getDomainDataSetTree`。
- `executeAsk`：无缝复用 `InsightQueryService` 现有的 `InsightMasking`、临时授权（`AccessRequestService`）、行数上限、CSV 导出、`ig_query_log` 与 `AuditService`。

### 4.2 `NL2SemanticSpec`：自然语言 → 结构化查询意图（严守 C3，不裸写 SQL）

原生引擎拆分为 **概率意图解析（侧车/受控 LLM）** 与 **确定性 SQL 编译（Java 内核）** 两段：

1. **子图召回与消歧**：调 `search_semantic_subgraph` 召回候选 `Metric`、`Dimension`、`Attribute`、`ai_context_json` 与 `CONSTRAINS/EXCLUDES` 边。
2. **维度值对齐（Value Linking）**：
   - 在 `dg-metadata` 为 L1/L2 低基数枚举属性与 `Standard(codeset)` 维护安全值字典表 `md_semantic_value_dict`（`object_id`、`raw_value`、`display_label`、`synonyms_json`，**严禁采集 L3/L4 敏感列**）；
   - 解析前将用户口语值（如“华东区”“已退单”）确定性匹配为物理码值（`EAST` / `REFUNDED`）并注入提示上下文。
3. **确定性相对时间归一化**：按平台统一时区与基准日将“上月 / 近 30 天 / 同比 / Q3”规范为明确日期区间，禁止 LLM 自行心算日期。
4. **结构化 `SemanticQuerySpec` 输出（与多轮追问改写）**：
   ```json
   {
     "semanticViewId": "view_order_core",
     "metrics": ["metric.order.gmv_valid"],
     "dimensions": ["attr.org.region", "dim.time.month"],
     "filters": [{ "objectCode": "attr.order.status", "op": "IN", "values": ["PAID"] }],
     "timeRange": { "dimensionCode": "dim.time.biz_date", "start": "2026-10-01", "end": "2026-10-31" },
     "orderBy": [{ "objectCode": "metric.order.gmv_valid", "dir": "DESC" }],
     "limit": 100
   }
   ```
   - 多轮追问（如“只看华东”“改按周”）直接基于上一轮 `SemanticQuerySpec` 做结构化增量改写；
   - 无已认证指标、歧义无法消解或权限不足时，显式返回 `CLARIFY_NEEDED` / `INCONCLUSIVE`（守 C8）。

### 4.3 确定性语义 SQL 编译器（`SemanticQueryCompiler`）

纯 Java 确定性模块（无模型依赖，100% 单元测试覆盖）：
1. **存在性与权限闸门（C2/C4/C5）**：校验全部 `objectCode` 处于 `PUBLISHED`、属于当前 `Workspace`、满足 `allowedDimensions` 粒度约束且底层物理表对当前用户可读。
2. **选表与最短确定性 Join 规划**：根据 `md_semantic_mapping`（`is_lookup_table` + `priority`）选取物理表，沿 `md_semantic_link(STRUCTURAL)` 最短路径生成 `JOIN ... ON ...`。
3. **防扇出倍增（Fan-out / Chasm Trap 保护）**：当请求跨越两张及以上 `1:N` 事实表时，自动改写为“按共同维度在独立 CTE 内先聚合，再外层关联”，避免重复求和。
4. **公式展开、隐含条件注入与只读护栏**：
   - 展开基础聚合与衍生比率（强制 `NULLIF(denominator, 0)` 防除零）；
   - 自动注入 `METRIC.defaultFilters` 与 `EXCLUDES` 边隐含过滤；
   - 经 `dg-data-spi` 只读 SQL 护栏校验（禁写/禁多语句/强制 `LIMIT`）后，交由 `DataAccessGateway`（StarRocks MySQL-JDBC / PostgreSQL）执行；
   - 返回编译结果同时附带 `semanticSnapshotRefs[]`（对象 ID + 版本号 + `content_digest`），写入 `ig_query_log` 与 `AnalysisReport` 审计指纹（C8）。

### 4.4 SuperSonic 完全退场清理清单（双跑达标后执行）

当 50 题评测集 `native` 引擎准确率 ≥ SuperSonic 基线且全部回归门禁通过后，按清单彻底清除历史胶水：
- **后端删除**：`SuperSonicClient.java`、`InsightSupersonicMappingController.java`、`InsightSupersonicMappingService.java`、`InsightSupersonicMapping.java`、`InsightSupersonicMappingMapper.java`；精简 `InsightProperties.java` 与 `application.yml` 中的 SuperSonic 配置；
- **前端删除**：`frontend/src/views/insight/MappingView.vue` 及对应菜单、路由、`frontend/src/api/insight.ts` 与 `types/insight.ts` 中的映射定义；
- **脚本与部署清理**：替换 `scripts/seed-supersonic-semantic.py` 为 `scripts/seed-semantic-kernel.py`，将 `evaluate-supersonic-50.py` 升级为 `evaluate-insight-50.py`，移除部署编排中的 SuperSonic 容器与依赖库。
- **架构文档同步**：更新 `ARCHITECTURE.md` §1/§2/§5.1 与 `AGENTS.md`，将拓扑收口为 `Insight Query Gateway → Native Semantic Engine → DataAccessGateway → StarRocks`。

---

## 5. 与 A0.3 受控自演进的协同（AI 如何安全地丰富知识图谱）

AI 在问数、质量剖析或归因过程中若发现：
- 用户频繁使用某未登记的业务别名（如把“出院 30 天内非计划再入院”简称为“30 天返院”）；
- 两个物理表之间存在高频隐式关联，但缺少 `RELATES_TO` 边；
- 某指标存在新发现的异常排除条件（`EXCLUDES` 边）。

演进流程严格遵循 `C9`：
1. Reflector 仅生成 `status = PROPOSED`、`source_kind = AI_PROPOSED` 的候选 `md_semantic_object` / `md_semantic_link`（或 `ai_context_json` 增量提案）；
2. `PROPOSED` 记录**绝不进入**生产子图检索与 `SemanticQueryCompiler`；
3. 治理员在前端审批界面（复用 `RuleDiffProposalCard` + `AgentConsentDialog`）查看 Diff：
   - **批准** → 晋升为 `PUBLISHED`（写入双时间态版本与审计日志）；
   - **驳回** → 标为 `REJECTED` 并写入 `knowledge/maintenance-log.md`，抑制重复提案。

---

## 6. 前端交互与页面架构设计（遵循 `frontend/DESIGN.md`）

遵循本仓库 **Governed Calm** 设计元语（Vue 3 + TypeScript + Element Plus + ECharts + `.page-container` + `el-card shadow="never"` + `var(--dg-*)` Token，零硬编码色值），坚持**“结构化表格/抽屉主攻精确建模，图谱画布主攻关系浏览、影响分析与 AI 召回调试”**，采用 **1 个主工作台 + 3 处原地融入既有页面** 的信息架构：

### 6.1 主工作台：`/metadata/semantic`（`SemanticOntologyView.vue`）

顶部展示 4 枚精炼统计卡（已发布指标数、已映射物理表覆盖率、知识图谱关系边数、待审 AI 提案数），主卡片内设 4 个 Tab：

1. **Tab 1 · 语义对象目录（默认页，左树右表）**
   - 左侧 220px 树按「业务域」与「Layer 1 计算语义（`ENTITY/ATTRIBUTE/DIMENSION/FACT/METRIC`）/ Layer 2 知识图谱（`CONCEPT/PROCESS`）」筛选；
   - 右侧 `el-table size="small"` 展示对象名/`code`（`--dg-font-mono`）、类型标签、绑定物理表数、`Standard` 绑定状态（缺失标 `--dg-warning` 提示 C2 闸门）、投影敏感级（`L1–L4`）、状态与版本号、责任人、更新时间；
   - **对象建模抽屉（`SemanticObjectDrawer.vue`，宽 `68%`，5 个子 Tab）**：
     - *定义与公式*：随 `object_type` 动态切换（`ATTRIBUTE` 多 Form 表、`DIMENSION` 层级顺序列表、`METRIC` 聚合/衍生分子分母 + `standard_ref` + `defaultFilters` + `allowedDimensions`）；
     - *物理表列映射（`SemanticMappingEditor.vue`）*：复用 `DatasetSearchSelect.vue`，支持多表多列映射、`mapping_role`、`is_lookup_table`、`priority` 及带即时只读校验的 `expression_sql`；
     - *AI 业务理解卡 & 值字典（`SemanticAiContextEditor.vue`）*：结构化编辑 `aliases`、`disambiguation`（何时用/何时不用）、`grain`、`caveats`、`typicalQuestions`；下方挂载 `md_semantic_value_dict` 安全值字典表（绑定列 `≥ L3` 时自动禁用值采集并显示 C5 红线；保存前扫描拦截明文 PII）；
     - *关系与图谱边*：分组管理 `STRUCTURAL`（含基数与 `join_spec`）与 `SEMANTIC`/`CAUSAL`（含业务 `rationale`）；
     - *双时间态版本与回滚*：展示历史版本 `valid_from/valid_to`、`content_digest`、JSON Diff 对比与一键回滚。
2. **Tab 2 · 本体与知识图谱画布（`SemanticGraphPanel.vue`）**
   - 复用 `LineageGraph.vue` 风格；支持中心对象搜索、`1/2/3` 跳滑块及三类边开关（结构实线 / 语义虚线 / 约束与因果警告虚线）；单击节点/边在右侧轻量检视，支持高亮 AI 子图召回路径与 ACL 剪枝标记。
3. **Tab 3 · AI 召回与编译调试台（`SemanticPlaygroundPanel.vue`）**
   - 左右分栏，替代 SuperSonic 调试后台：左侧支持“自然语言子图召回（可切模拟角色验证逐跳 ACL）”与“确定性 `SemanticQuerySpec` 编译测试”；右侧透视命中对象、Value Linking 对齐结果、自动注入的 `EXCLUDES`、生成的只读 SQL（含防扇出 CTE 与审计指纹）及前 20 行脱敏试跑预览。
4. **Tab 4 · 待审演进提案（`PROPOSED` 审校队列）**
   - 展示 `AI_PROPOSED` 与成员提交的语义/图谱增量提案（附确定性统计快照证据），展开变更前后 Diff；**批准发布**复用 `AgentConsentDialog.vue` 二次核验，**驳回**强制填写理由并写入 `knowledge/maintenance-log.md` 抑制重复提案。

### 6.2 三处原地融入既有页面

- **`AssetDetailView.vue`**：新增「语义映射与影响」Tab，复用 `SemanticImpactDrawer.vue` 展示该物理表/列绑定的逻辑对象及字段变更影响树；
- **`AskView.vue` & `AgentDock.vue`**：回答顶部新增「语义依据条」（命中指标与版本、自动生效的排除约束、审计指纹），对 `CLARIFY_NEEDED` 渲染多义口径单选重跑卡，渲染宿主白名单 `ui_block`；Stage 5 下线 `MappingView.vue`；
- **`InboxView.vue`**：接入「待审语义/图谱提案」待办分组，与敏感审批、告警收敛在统一治理收件箱。

### 6.3 前端文件落点

```text
frontend/src/
├── api/semantic.ts
├── types/semantic.ts
├── views/metadata/SemanticOntologyView.vue
└── components/semantic/
    ├── SemanticObjectDrawer.vue
    ├── SemanticMappingEditor.vue
    ├── SemanticAiContextEditor.vue
    ├── SemanticGraphPanel.vue
    ├── SemanticPlaygroundPanel.vue
    └── SemanticImpactDrawer.vue
```

---

## 7. 建议实施分期（详见配套 `tasks.md`）

1. **Stage 1 — 语义元数据内核与逐跳子图检索**：`V37` 迁移（含 `md_semantic_value_dict` 与 PG RLS）、`dg-metadata` CRUD/发布/版本快照、逐跳鉴权子图检索、物理列影响分析与 20 表种子导入脚本。
2. **Stage 2 — 确定性 SQL 编译器与 `DataAccessGateway` 直连**：实现 `SemanticQueryCompiler`（单表/多表 Join/防扇出 CTE/比率除零/隐含过滤/只读护栏）并打通 StarRocks 只读执行与审计指纹。
3. **Stage 3 — `NL2SemanticSpec`、Value Linking、双引擎灰度与前端工作台**：抽取 `InsightQueryEngine`，落地维度值对齐、相对时间归一化、多轮 Spec 改写、`agent-service` 工具接入与前端语义本体工作台及三处原地融入。
4. **Stage 4 — 50 题双引擎对比评测与全量回归门禁**：双跑 `supersonic` vs `native` 达标，补齐 `evals/regression` 用例并全绿通过。
5. **Stage 5 — 彻底下线 SuperSonic**：切换默认引擎为 `native`，删除 `SuperSonicClient`、账号映射前后端与部署依赖，更新 `ARCHITECTURE.md` 与 `docs/space-contracts/`。
