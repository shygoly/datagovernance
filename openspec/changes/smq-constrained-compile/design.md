# Technical Design: SMQ 约束编译内核与类型化修复闭环

- **Change ID**: `smq-constrained-compile`
- **Aligned Proposal**: [proposal.md](./proposal.md)
- **核心设计原则**：
  1. **SMQ 是 LLM 与编译器之间唯一接口**——不含 SQL；SQL AST / Dataflow 仅编译器内部可见；
  2. **约束优先级**：安全 mask（不可绕过）> 语义枚举约束 > 纯语法（现代 LLM 已够用，MVP 不加重）；
  3. **拒绝必须可局部修复**：每个 `CompileReject` 携带 error_code + allowed + repair_hint；
  4. **与 metric-6d 解耦交付**：本设计消费 PUBLISHED 目录；六维字段未就绪时降级到现有 `allowedDimensions` / ratio 表达式能力。

---

## 1. 分层与三种产物

```text
① LLM / Agent（唯一概率层）
   读 Catalog Grammar → 采样/组装 SMQ → 收到 CompileReject → 局部改字段
        │ SMQ JSON
        ▼
② 工具面（无业务推断）
   list_metrics(+constraints) · list_dimensions(+constraints)
   compile_and_validate（干跑） · execute_semantic_query
        │ SMQ
        ▼
③ 编译内核（确定性，零 LLM）
   ③a 路径解析 → ③b 逻辑计划（CTE/LOD/半加性/缝合）
   → ③c 轻量优化 → ③e 方言 SQL（③d 物化路由 v0 不做）
        │
        ▼
④ DataAccessGateway / 数仓执行
```

旁路：
- **A** 对象图元模型（metric-6d / semantic-ontology-kernel）
- **B** DuckDB Golden SMQ（本 change）
- **C** 安全双注入（目录 mask + 编译强制谓词）

| 产物 | 生产者 | 消费者 | 关键性质 |
|---|---|---|---|
| 元模型 / Catalog Grammar | 人 + 图闸门 | LLM、编译器 | 静态、版本化、ACL 裁剪后投影 |
| SMQ | LLM / 显式 API | 编译内核 | 可校验、无 SQL |
| SQL 文本（规范化） | 编译内核 | 执行层 / 审计 | 同输入同快照逐字节稳定 |

---

## 2. SMQ IR v1 Schema

### 2.1 JSON 形态（示意）

```json
{
  "metrics": ["sales.metric.return_rate"],
  "dimensions": ["sales.dimension.region"],
  "filters": [
    { "objectCode": "sales.attr.order_type", "op": "!=", "values": ["test"] }
  ],
  "time": {
    "dimensionCode": "sales.dimension.biz_date",
    "range": { "start": "2026-07-01", "end": "2026-09-30" },
    "grain": "quarter"
  },
  "grain": "quarter",
  "at_modifiers": [
    { "op": "USING", "rel": "sales.entity.customers" }
  ],
  "orderBy": [{ "objectCode": "sales.metric.return_rate", "dir": "DESC" }],
  "limit": 200
}
```

### 2.2 与现有 `SemanticQuerySpec` 兼容策略
- Java record 扩展可选字段：`grain`、`atModifiers`；`timeRange` 保留，同时接受归一化后的 `time` DTO（装配层适配）。
- 相对时间（「上个季度」）**禁止**由 LLM 心算：继续由 `SemanticValueLinkingService` / 确定性时间归一化写入 `start`/`end` 后再编译。
- 非法字段（表名、rawSql、joinHints 文本）→ Schema 层直接拒绝，不进入编译器。

### 2.3 `at_modifiers` v0 算子

| op | 语义 | 典型触发 |
|---|---|---|
| `USING` | 指定多事实共享路径上的桥接实体/维 | `MULTI_FACT_AMBIGUOUS_PATH` |
| `SET_GRAIN` | 显式覆盖请求 grain（仍须 ∈ allowed_grains） | 修复 `GRAIN_MISMATCH` 时的局部声明 |

v0 **不**实现自由表达式 AT；禁止用 modifier 关闭 RLS/EXCLUDES。

---

## 3. Catalog Grammar 投影

`SemanticCatalogGrammarService`（`dg-metadata`）：
1. 输入：`workspaceId`、`UserContext`、可选 `model`/`semanticViewId`；
2. 输出：机器可读约束文档（JSON），供工具 schema 与 Agent 缓存：

```json
{
  "metrics": [
    {
      "code": "sales.metric.return_rate",
      "metricType": "ratio",
      "allowedDimensions": ["sales.dimension.region", "..."],
      "allowedGrains": ["day", "month", "quarter"],
      "requiresStandard": true
    }
  ],
  "dimensions": [{ "code": "sales.dimension.region", "objectType": "DIMENSION" }],
  "filterOps": ["=", "!=", "IN", "NOT_IN", ">", ">=", "<", "<=", "LIKE"],
  "atModifiers": ["USING", "SET_GRAIN"]
}
```

3. **ACL**：不可见对象不出现在数组中（C5 Pre-retrieval）；禁止返回「存在但无权」的暗提示列表（防枚举探测可另做审计级速率限制，不在 Grammar 正文泄漏）。

---

## 4. 类型化 `CompileReject`

```json
{
  "ok": false,
  "error_code": "GRAIN_MISMATCH",
  "message": "return_rate 不支持 grain=week",
  "location": "$.grain",
  "metric": "sales.metric.return_rate",
  "requested_grain": "week",
  "allowed": ["day", "month", "quarter"],
  "repair_hint": "将 grain 改为 month，或去掉 grain 使用度量默认时间粒度",
  "semanticSnapshotRefs": []
}
```

成功干跑：

```json
{
  "ok": true,
  "sql": "WITH ...",
  "dialect": "STARROCKS",
  "auditFingerprint": "...",
  "semanticSnapshotRefs": ["..."],
  "warnings": [],
  "fanoutCteUsed": true,
  "normalizedSmq": { }
}
```

实现要点：
- 将今日散落的 `BizException(MTD_SEMANTIC_COMPILE_FAILED, "自由文本")` 逐步替换为 `CompileRejectException` + 稳定 `error_code`；
- HTTP：干跑接口返回 200 + `{ok:false,...}`（便于 Agent 消费），或 422 + 同一 JSON body（二选一，**契约固定一种**；推荐 **200 + ok 判别**，避免工具链把业务拒绝当传输错误）。

---

## 5. 编译内核增量（对准红线）

### 5.1 多事实缝合（替换 LEFT/CROSS 默认路径）
对跨事实度量：
1. 每事实按请求维度 + grain 预聚合 CTE；
2. 外层以维度键 **`FULL OUTER JOIN`**；
3. 连接谓词使用 **`IS NOT DISTINCT FROM`**（MySQL 方言映射 `<=>`）；
4. ratio 外层 `CAST AS DOUBLE` + `NULLIF(den, 0)`。

### 5.2 路径歧义
- 度量→维度存在多条 STRUCTURAL 可达路径且未指定 `USING` → **拒绝**，不猜最短路静默胜出（C8）；
- 提供候选 `rel` code 列表于 `allowed`。

### 5.3 半加性 / LOD
- 仅当度量 Spec 声明 `semi_additive` + `window_groupings`（metric-6d）时启用两阶段窗口；否则保持现有聚合；
- LOD：`FIXED`/`INCLUDE`/`EXCLUDE` 编译为相关子查询 / 分组改写；非法引用 → `LOD_INVALID`。

### 5.4 确定性
- CTE 名、列别名、JOIN 顺序按稳定排序键生成；
- 单测：同一 SMQ fixture 两次 `compile` 字符串相等。

---

## 6. Agent / MCP 交互协议

### 6.1 推荐轨迹
1. `list_metrics` / `list_dimensions`（或一次拉取 Grammar）；
2. 组装 SMQ；
3. `compile_and_validate`；
4. 若 `ok=false` → 按 `repair_hint` **补丁式**修改 SMQ → 回到 3（建议上限 3 次）；
5. `execute_semantic_query`（服务端可选择强制要求近期 validate 指纹，v0 可仅内部复用同一 `compile`）。

### 6.2 约束解码 MVP
- Agent 工具参数用 TypeBox / JSON Schema 限制 `op` 枚举；
- `metrics[]`/`dimensions[]` **不**在静态 schema 写死全集（随租户变化），而以「先 list 再填」+ 编译拒绝兜底；
- 若宿主支持 constrained decoding：将 Grammar 中的 code 列表注入动态 schema（可选增强，非阻断）。

### 6.3 安全
- Grammar 已裁剪；
- 编译器再次校验 PUBLISHED + ACL + Standard；
- 任何试图通过 filter/objectCode 引用未授权对象 → `UNRESOLVED_*` 或 `SECURITY_FIELD_FORBIDDEN`（不区分「不存在」与「无权限」的对外文案可统一为未解析，对内审计记真实原因）。

---

## 7. Golden SMQ（DuckDB）套件

目录建议：`evals/semantic-compile/golden/`（或 `backend/dg-metadata/src/test/resources/smq-golden/`）

每条用例：`smq.json` + `expected_rows.json` + `expected_error_code?`

v0 最少 10 条：
1. 单事实 SUM
2. 单事实 ratio + NULLIF
3. 多事实缝合（双方都有维值）
4. 多事实 NULL 维不裂变
5. grain 非法 → `GRAIN_MISMATCH`
6. 维度不在 allowed → `DIMENSION_NOT_ALLOWED`
7. 歧义路径 → `MULTI_FACT_AMBIGUOUS_PATH`
8. `USING` 消歧成功
9. 除零行 → SQL 结果 NULL 非异常
10. 越权度量不在 Grammar 且 compile 拒绝

CI：`python3 scripts/run-smq-golden.py`（或 JUnit 嵌入 DuckDB）纳入 `verify` 相关门禁说明。

---

## 8. 与 metric-6d / semantic-ontology-kernel 的接口

| 能力 | 来源 change | 本内核用法 |
|---|---|---|
| PUBLISHED 对象 / mapping / STRUCTURAL 边 | semantic-ontology-kernel | 路径与选表 |
| 六维 additivity / lod / time_calendar / composition | metric-6d | 编译分支与 Grammar 的 allowed_grains |
| 定义态 6 探针 | metric-6d | **不**替代 Golden SMQ；探针失败的度量不应出现在生产 Grammar |
| `UNRESOLVED_REF` 形状 | metric-6d 建模 | 问数侧对齐字段名风格（error_code / candidates / repair_hint） |

---

## 9. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 强模型上枚举 mask 准确率增益有限 | 仍优先做安全裁剪与类型化拒绝（合规收益不衰减） |
| FULL OUTER 改写破坏既有问数结果 | Golden + 现有 NativeInsight 回归双跑；特性开关 `smq.full-outer-stitch=true` |
| metric-6d 未完成导致半加性/LOD 空转 | 编译器 feature-detect Spec 字段；缺省走旧路径并在 warnings 声明 |
| Agent 无限修复循环 | validate 重试上限 + 超限升级 `CLARIFY_NEEDED` |
