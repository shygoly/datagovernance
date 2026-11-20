# Technical Design: 度量六维正交本体、对象图五道闸门与四层证伪闭环

- **Change ID**: `metric-6d-ontology-verification`
- **Aligned Proposal**: [proposal.md](./proposal.md)
- **核心设计原则**：
  1. **对象图是唯一真相，YAML 只是投影**（短名可读 / 全 ID 机读 diff 两种投影不漂移）；
  2. **LLM 负责命名、描述、表达式初稿、候选建议；人负责语义契约最终签署；统计与探针负责确定性证伪**；
  3. **引用完整性与口径正确性由系统不变量（G1–G5 + ③b/③c/③d）强制保障，不靠人肉 Review**。

---

## 1. 度量定义六维正交模型（Metric 6D Spec）

在 `md_semantic_object.spec_json`（`object_type = 'METRIC'`）中，将度量契约分解为六个彼此正交的维度：

| 正交维度 | 核心字段 | 合法性约束（G1 / ③a） |
|---|---|---|
| **1. 可加性（`additivity`）** | `kind`: `additive` \| `semi_additive` \| `non_additive`<br>`non_additive_dim`: `[dim_ref...]`<br>`window_groupings`: `[dim_ref...]`<br>`aggregation_op`: `last` \| `first` \| `avg` | • `non_additive_dim` 与 `window_groupings` 仅允许出现在 `semi_additive`<br>• `semi_additive` 缺少 `window_groupings` 触发 ③d 反模式阻断<br>• `ratio` / `distinct_count` 默认禁止标为 `additive` |
| **2. LOD（`lod`）** | `type`: `NONE` \| `FIXED` \| `INCLUDE` \| `EXCLUDE`<br>`dimensions`: `[dim_ref...]` | • `dimensions` 只能引用 `DIMENSION` / `ATTRIBUTE` 节点（G3）<br>• `lod.type != NONE` 时须通过探针查询验证 |
| **3. 过滤交互（`filter_interaction`）** | `default_filters`: `[{dimension_ref, op, values}]`<br>`ignore_dimensions`: `[dim_ref...]`<br>`filter_stage`: `PRE_AGG` \| `POST_AGG` | • `default_filters` 与 `ignore_dimensions` 引用的维度必须存在（G2/G3）<br>• 安全/租户过滤与 RLS **不属于本块**，由独立策略层强制注入，禁 LLM 写入 |
| **4. 时间日历（`time_calendar`）** | `calendar_ref`: `dim_ref`<br>`time_grain`: `DAY` \| `WEEK` \| `MONTH` \| `FISCAL_QUARTER` \| `YEAR`<br>`period_transform`: `NONE` \| `YTD` \| `MTD` \| `MOM` \| `YOY` | • 禁止在 `expression` 中硬编码 `DATE_TRUNC`（③d 拦截）<br>• 须绑定已注册的时间维度节点 |
| **5. 多态组合（`composition`）** | `metric_type`: `atomic` \| `ratio` \| `derived` \| `cumulative`<br>`expression`: 规范引用表达式（如 `${refund_amount} / NULLIF(${gross_revenue}, 0)`）<br>`components`: `{alias: {metric_ref \| column_ref, expression}}` | • `ratio` 必须显式防除零（`NULLIF(..., 0)`）<br>• `components` 构成依赖 DAG，严格无环（G4） |
| **6. 图拓扑局部性（`graph_locality`）** | `entity_keys`: `[{table_ref, column_ref, key_type: primary\|unique\|foreign}]`<br>`join_cardinalities`: `[{link_ref, cardinality: 1:1\|1:N\|N:M}]`<br>`max_hop`: `int` | • 连接基数仅接受 KG 边或 DB FK 约束，**禁止从命名猜测**<br>• 须经 ③b 统计证伪（PK/NDV/FK）验证 |

---

## 2. LLM 权责治理矩阵与 Provenance 契约

### 2.1 字段级权责矩阵

| YAML / Spec 字段 | LLM 角色 | 允许依据 | 生效前硬门槛 |
|---|---|---|---|
| 度量名、同义词、业务描述、示例问题 | 主导生成 | 历史 SQL + 文档 + 列名 | 格式与 PII 扫描通过即可（无需人工阻断） |
| 物理表达式（`SUM(amount)`） | 候选生成 | 列名 + 历史 SQL 模式 | ③d 反模式检测通过 + 只读 SQL 护栏 |
| 聚合算子（`sum`/`count`/`avg`） | 候选生成 | 列类型 + 历史用法 | G3 类型校验通过 |
| 可加性（`additive`/`semi`/`non`） | 仅给建议 + 证据 | KG 实体类型 | **必须人工确认** |
| 半加性 `non_additive_dim` / `window_groupings` | 仅给建议 | KG 实体键 + 统计 | **必须人工确认** |
| 实体键（`primary`/`unique`/`foreign`） | 候选 + 证据 | PK 约束 + NDV 唯一性 + KG | ③b 统计证伪通过则自动，否则人审 |
| 连接基数（`1:1`/`1:N`/`N:M`） | **不从命名猜** | 仅接受 KG 边或 FK 约束 | ③b 统计 NDV 对照 + 人审 |
| LOD 修饰符 | 仅给建议 | 历史 SQL 窗口/子查询模式 | ③c 探针查询验证 |
| 安全/租户列、RLS 策略 | **禁止 LLM 参与** | 策略系统 / 人工标注 | **强制人工（API/Schema 硬拦截 LLM 写入）** |
| 可见性（`PUBLIC`/`PRIVATE`） | 候选 | 列敏感度统计（`md_column_sensitivity`） | 敏感列强制人审；维度标 `PRIVATE` 触发 ③d 拦截 |

### 2.2 带 Provenance 与 Verified 区块的 YAML 投影形态

```yaml
metric:
  name: return_rate
  code: sales.metric.return_rate
  composition:
    metric_type: ratio
    expression: "${refund_amount} / NULLIF(${gross_revenue}, 0)"
    components:
      refund_amount:
        expression: "SUM(returns.refund_amount)"
        ref:
          fact: sales.fact.returns
          column: sales.column.returns.refund_amount
        provenance: { source: "historical_sql", ref: "bi_report_2024_q3.sql#L42", confidence: "HIGH" }
      gross_revenue:
        expression: "SUM(orders.amount)"
        ref:
          fact: sales.fact.orders
          column: sales.column.orders.amount
        provenance: { source: "db_constraint", ref: "orders.amount", confidence: "HIGH" }
  additivity:
    kind: non_additive
    provenance:
      source: "llm_inference"
      evidence: "ratio of two additive measures"
      confidence: "MEDIUM"
    requires_human_review: true
  lod:
    type: NONE
    dimensions: []
  graph_locality:
    entity_keys:
      - table: orders
        column: order_id
        key_type: primary
        provenance: { source: "db_constraint", ref: "orders_pkey" }
        verified: { pk_unique: true, checked_at: "2026-11-20T03:00:00Z" }
  ai_context:
    synonyms: ["退货比例", "refund ratio"]
    sample_questions: ["上季度各大区退货率"]
    provenance: { source: "llm_inference", confidence: "HIGH" }
  verified:
    gate_g1_g4: PASS
    anti_pattern_lint: PASS
    stat_falsification: PASS
    probe_suite: PASS
```

三条不可妥协规则：
1. **来源优先级**：`db_constraint > knowledge_graph > historical_sql > llm_inference`，优先级越低越需人审；
2. **`llm_inference` 硬规则**：除纯描述类 `ai_context` 外，所有 `llm_inference` 来源字段在导入期由后端强制置 `requires_human_review = true`，忽略 LLM 自填的 `confidence`；
3. **`verified` 硬门槛**：任一验证层为 `FAIL` 时，对象禁止流转至 `PUBLISHED`，失败字段自动降级 `LOW_CONFIDENCE` 进入人工复核队列。

---

## 3. 对象图真相源、YAML 引用解析与五道硬闸门（G1–G5）

### 3.1 短名解析 + 显式 `ref` 块
- **导入解析规则**：
  1. 短名（如 `dimensions: [customer_id]`）优先在当前模型/域命名空间内解析；
  2. 0 命中 → 返回 `UNRESOLVED_REF` + 基于编辑距离/同义词的 Top-K 规范候选列表，**拒绝导入**；
  3. >1 命中 → 返回 `AMBIGUOUS_REF` + 冲突节点列表，要求建模者补充显式 `ref:` 块（如 `sales.dimension.customer_id`），**拒绝导入**；
  4. 唯一命中 → 在 `md_semantic_link` 写入类型化引用边（`link_type ∈ {DIMENSION_REF, METRIC_REF, COLUMN_REF, CALENDAR_REF}`），并持久化 `target_id` 与 `target_content_hash`。
- **双模式导出**：`GET /api/v1/metadata/semantic/yaml/export?mode=short|full`，短名模式便于人编辑，全 ID 模式便于 Git diff 与 CI 审计，二者实时从同一对象图投影生成。

### 3.2 图上五道闸门（G1–G5）与删除保护

| 闸门 | 图查询检查内容 | 失败行为 |
|---|---|---|
| **G1 Schema** | YAML/JSON Schema、六维正交合法性、禁止 LLM 写入安全/RLS 字段 | 硬拒绝导入 |
| **G2 引用完整性** | 所有 `ref` 均解析到当前 Workspace 可见的已存在节点 | 硬拒绝导入 + 返回 `UNRESOLVED_REF` / `AMBIGUOUS_REF` 候选 |
| **G3 类型匹配** | `lod.dimensions` 仅引 `DIMENSION`/`ATTRIBUTE`；`components` 引 `METRIC`/`COLUMN` 不可混用；聚合算子与物理列数据类型兼容 | 硬拒绝导入 |
| **G4 图结构** | 沿 `METRIC_REF` / `DERIVED_FROM` 边执行环检测（DFS/Kahn 拓扑排序），确保复合度量依赖 DAG 严格无环 | 硬拒绝导入并输出环路径 |
| **G5 变更传播** | 当节点 `X` 重新发布且 `content_digest` 变化时，沿入边检索所有引用 `X` 且 `target_content_hash != X.content_digest` 的上游节点，自动标记 `status = STALE_REVIEW` | 阻塞引用方再次发布或合并，须人工复核后更新 `target_content_hash` |
| **删除保护** | 删除任意 `DIMENSION` / `ATTRIBUTE` / `METRIC` / `FACT` 前执行入边图查询；若存在任一活跃引用边，直接拒绝删除并列出全部引用方 | 硬拒绝删除（HTTP 409 + 引用方清单） |

---

## 4. 四层确定性验证闭环（③a–③d）

### 4.1 ③a 静态校验 & ③d 反模式规则库（`SemanticAntiPatternLinter`）
纯确定性规则，无需 LLM，支持独立扩展：
- `AP001_ROW_LEVEL_RATIO`：`ratio` 度量表达式出现行级 `a / b` 而非 `SUM(a) / NULLIF(SUM(b), 0)`；
- `AP002_RATIO_MISSING_NULLIF`：除法分母未包裹 `NULLIF(..., 0)` 或安全除零保护；
- `AP003_SEMI_ADDITIVE_NO_WINDOW`：`additivity.kind == semi_additive` 但缺少 `window_groupings` 或 `non_additive_dim`；
- `AP004_HARDCODED_DATE_TRUNC`：表达式中硬编码 `DATE_TRUNC(...)` 绕过 `time_calendar`；
- `AP005_PRIVATE_DIMENSION`：将 `DIMENSION` 节点设为 `PRIVATE`（破坏切片与下钻一致性）；
- `AP006_ADDITIVE_ON_NON_ADDITIVE_OP`：`COUNT(DISTINCT ...)` 或 `AVG`/`RATIO` 误标为 `additive`。

### 4.2 ③b 统计证伪（`MetricStatisticalFalsifier`）
在 `dg-metadata` 生成只读证伪 SQL，通过 `DataAccessGateway`（携带真实 `UserContext`）在物理表执行：
```sql
-- 1) PK 唯一性证伪
SELECT COUNT(*) = COUNT(DISTINCT {pk_col}) AS pk_is_unique FROM {table};
-- 2) 1:N 基数 NDV 比证伪（child 表外键 NDV 比应 < 1.0 且 > 0）
SELECT CAST(COUNT(DISTINCT {fk_col}) AS DOUBLE) / NULLIF(COUNT(*), 0) AS ndv_ratio FROM {child_table};
-- 3) 外键匹配率证伪（阈值默认 >= 0.95，可按业务域配置）
SELECT AVG(CASE WHEN p.{pk_col} IS NOT NULL THEN 1.0 ELSE 0.0 END) AS fk_match_rate
FROM {child_table} c LEFT JOIN {parent_table} p ON c.{fk_col} = p.{pk_col};
```
任一断言 `FAIL` → 对应 `entity_key` / `join_cardinality` 的 `verified` 标为 `false`，置信度降级为 `LOW_CONFIDENCE`，自动进入人工复核队列。

### 4.3 ③c 探针查询引擎（`MetricProbeRunner` · 6 类探针 SMQ + DuckDB CI）
基于对象图自动为目标度量生成 6 类探针 `SemanticQuerySpec` → 交 `SemanticQueryCompiler` / `MultiWarehouseQueryCompiler` 编译 → 在嵌入式 DuckDB（CI 夹具表或抽样脱敏视图）执行断言：

| 探针类型 | 构造方式与断言 | 捕获的静默错误 |
|---|---|---|
| **1. 扇出探针（`FANOUT_PROBE`）** | 仅查订单金额 vs 联合 `1:N` 明细表维度后再按订单汇总，总金额相对误差应 `< 1e-6` | 漏标 `1:N` 或缺少防扇出 CTE |
| **2. 可加性探针（`ADDITIVITY_PROBE`）** | 对标为 `additive` 的度量：按日汇总再求和 vs 按周汇总求和，结果应一致 | `non_additive` / `semi_additive` 误标为 `additive` |
| **3. 半加性探针（`SEMI_ADDITIVE_PROBE`）** | 对 `semi_additive` 度量：跨实体（如不同账户最新日期不同）汇总时，验证按 `window_groupings` 各取最新而非仅取全局最新日 | 缺少或错配 `window_groupings` |
| **4. NULL 维度探针（`NULL_DIM_PROBE`）** | 构造含 `NULL` 维度值的多事实缝合测试集，断言 `NULL` 组不裂变为多行 | 多事实缝合缺少 `IS NOT DISTINCT FROM` |
| **5. 除零探针（`DIV_ZERO_PROBE`）** | 注入分母为 `0` 的过滤/测试行，断言返回 `NULL` 而非抛出运行异常 | 缺少 `NULLIF(..., 0)` |
| **6. 量级探针（`MAGNITUDE_PROBE`）** | 对比历史分位数区间 `[p05, p95]`（或声明值域，如比率 `[0, 1]`），断言不越界 | 分子分母写反或聚合层级错 |

---

## 5. 数据模型迁移与 MCP 接地接口

### 5.1 Flyway 迁移（`V39__metric_6d_verification_gates.sql`）
- `md_semantic_object` 新增列：`provenance_json JSONB`、`verified_json JSONB`、`requires_human_review BOOLEAN DEFAULT FALSE`（状态支持 `STALE_REVIEW`）；
- `md_semantic_link` 新增列：`target_content_hash VARCHAR(64)`（支撑 G5 变更传播）；
- 新增表 `md_semantic_verification_record`（含 `workspace_id` + PG RLS）：记录每次验证的 `object_id`、`version_no`、`layer`（`G1_G4` / `ANTI_PATTERN` / `STAT_FALSIFY` / `PROBE_DUCKDB`）、`status`（`PASS`/`FAIL`/`WARN`）、`details_json`、`executed_by`、`created_at`。

### 5.2 MCP 接地选 ID 工具（已同步 `contracts/mcp/datagov-mcp.json`）
- `list_dimensions(model)` → `[{id, code, name, objectType, description}]`
- `list_metrics(model)` → `[{id, code, name, additivity, metricType, description}]`
- `resolve_ref(short_name, model)` → `{status: "RESOLVED" | "UNRESOLVED_REF" | "AMBIGUOUS_REF", canonicalId?, candidates: [...]}`
- 全程透传 `Bearer JWT + X-Workspace-Id`，由后端按 `userId + workspaceId` 实时鉴权与逐跳过滤（C4/C5）。
