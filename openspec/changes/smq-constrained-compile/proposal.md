# Proposal: SMQ 约束编译内核与类型化修复闭环（SMQ Constrained Compile）

- **Change ID**: `smq-constrained-compile`
- **Status**: Draft
- **Depends On**: `semantic-ontology-kernel`、`multisource-semantic-federation`；**契约输入**依赖 `metric-6d-ontology-verification`（六维目录 / 允许 grain / 组合合法性，定义态不在本 change 交付）
- **Coordinates With**: `analysis-hardening-clinical-foundation`（**W1** 统一单仓/多仓/联邦语义门禁、**W3** 正确性 Golden、**W4** 完整指纹；本 change **不**实现临床队列/评分/生存等统计能力）
- **Constitution Alignment**: 严格遵循 `CONSTITUTION.md`（C1–C10）、`SPACE-MODEL.md`、`ARCHITECTURE.md` §5.1

---

## Why

平台已具备 `SemanticQuerySpec` → `SemanticQueryCompiler` → `DataAccessGateway` 的确定性问数主链路，但相对「LLM 只在度量目录内选择组装、编译器做语法/语义/安全三重裁判」仍有四处结构性缺口：

1. **动作空间仍偏文本、缺少可前缀检查的 IR 语法**：Agent 工具虽要求结构化 Spec，但目录侧未暴露「度量→允许维度/grain/AT 组合」的完整枚举约束；生成阶段无法做枚举级 mask，只能事后编译失败。
2. **编译拒绝未类型化，修复闭环成本高**：现状多为单一 `MTD_SEMANTIC_COMPILE_FAILED` + 自由文本；缺少 `{error_code, location, allowed_*, repair_hint}`，LLM 倾向整份重写 Spec，而非局部修正。
3. **编译内核红线未完全固化到与探针同构**：多事实缝合仍以 `LEFT JOIN`/`CROSS JOIN` 为主，尚未一等公民化 `FULL OUTER JOIN` + `IS NOT DISTINCT FROM`；半加性 `window_groupings`、LOD、`at_modifiers` 路径消歧尚未进入 Spec→SQL 契约（与 metric-6d 探针消费面脱节）。
4. **安全约束仍偏事后拦截**：角色不可见度量主要靠编译/执行闸门；经验证的 AST/GRID 范式表明，**策略合规应下沉到生成 mask（不可达）+ 编译强制注入双点**，且该收益不随模型升级衰减。

面向 AST 编程的工程结论可直接平移：**把 LLM 输出面收窄为可前缀检查的 SMQ IR，把约束下沉到解码/工具 schema，把编译器变成带类型化错误码的确定性裁判**——LLM 永不写、永不改 SQL AST。

---

## What Changes

### 1. SMQ IR v1（扩展既有 `SemanticQuerySpec`，仍禁止含 SQL）
- 正式将问数结构化查询命名为 **SMQ**（对外文档/MCP；代码类型可保留 `SemanticQuerySpec` 别名）。
- v1 字段：`metrics`、`dimensions`、`filters`、`time`（含 `range`/`grain` 归一化）、`orderBy`、`limit`，新增可选 **`at_modifiers`**（如 `USING` 路径消歧、`SET grain`）与 **`grain`** 一等字段。
- **硬约束**：SMQ 不得包含表名、列名、JOIN、GROUP BY、裸聚合函数或任意 SQL 片段；物理映射只存在于对象图 / mapping。

### 2. 可前缀检查的目录语法 + 接地工具面
- 从 PUBLISHED 对象图 +（metric-6d 就绪后）六维契约投影 **Catalog Grammar**：
  - 合法 `metric` / `dimension` ID 集合（按 `userId + workspaceId` 实时 ACL 裁剪）；
  - 每度量 `allowed_dimensions` / `allowed_grains` / 禁止组合；
  - 合法 `at_modifiers` 算子与参数枚举。
- Agent / MCP 工具对齐：
  - 增强 `list_metrics` / `list_dimensions`（带回约束元数据，而不只是 id/name）；
  - 新增 **`compile_and_validate`**（干跑：SMQ → 规范化 SQL 或类型化拒绝，**不执行**）；
  - 既有 `execute_semantic_query` 仅接受已通过校验的 SMQ（或内部先走同一编译入口）。
- MVP 约束解码形态：**JSON Schema / function-calling 枚举 + 工具返回目录**（不必上完整 LALR）；完整 token-mask 运行时列为后续可选。

### 3. 类型化 `CompileReject` + generate→validate→repair 闭环
- 编译失败统一返回结构化对象，至少含：`error_code`、`message`、`location`（JSON path）、`allowed`（合法替代集）、`repair_hint`。
- 初始错误码目录（可扩展，禁止静默猜测）：
  - `UNRESOLVED_METRIC` / `UNRESOLVED_DIMENSION`
  - `GRAIN_MISMATCH` / `DIMENSION_NOT_ALLOWED`
  - `MULTI_FACT_AMBIGUOUS_PATH`（要求 `at_modifiers: USING`）
  - `ADDITIVITY_VIOLATION` / `LOD_INVALID`
  - `FILTER_OP_FORBIDDEN` / `SECURITY_FIELD_FORBIDDEN`
  - `STANDARD_MISSING`（C2）
- Agent 交互约定：收到拒绝后**只改涉及字段**再调 `compile_and_validate`，禁止整份 Spec 盲重生；问数侧终态仍保留 `CLARIFY_NEEDED` / `INCONCLUSIVE`（C8）。

### 4. 编译内核确定性升级（零 LLM）
在既有 `SemanticQueryCompiler` / `MultiWarehouseQueryCompiler` 上增量：
- **③a 图路径**：歧义路径拒绝并给出 `MULTI_FACT_AMBIGUOUS_PATH` + 候选 `USING`；
- **③b 逻辑计划**：显式多事实预聚合 CTE → **`FULL OUTER JOIN` + `IS NOT DISTINCT FROM`** 缝合；半加性两阶段；LOD 子查询（与 metric-6d Spec 对齐）；
- **③c 轻量优化**：谓词下推、无关维表裁剪（不做完整代价优化器）；
- **③d 物化路由**：v0 **不做**（Non-goal）；
- **③e 方言发射**：延续当前方言分支（StarRocks/MySQL/PostgreSQL）；`IS NOT DISTINCT FROM` / `<=>` 映射；**不引入 sqlglot/DataFusion 作为 MVP 必需依赖**（可选后续）。
- 同一 SMQ + 同一语义快照 → **规范化 SQL 逐字节稳定**（确定性排序别名/CTE 名）。

### 5. 安全双注入点（旁路 C）
- **生成侧**：Catalog Grammar / 工具 schema 中角色不可见度量与维度**不可枚举**（Pre-retrieval ACL，禁 retrieve-then-filter）。
- **编译侧**：RLS/租户/敏感策略与 `EXCLUDES` **强制注入**；SMQ 的 `at_modifiers` **不可覆写**安全谓词；试图引用安全字段 → `SECURITY_FIELD_FORBIDDEN`。

### 6. 问数侧 Golden SMQ 回归（旁路 B · 查询态）
- 与 metric-6d **定义态探针**及 `analysis-hardening-clinical-foundation` **W3** 互补：本套件验证「合法 SMQ → SQL → **PG 优先夹具**结果」的 golden 行级比对（无 LLM judge；联邦场景可辅以现有嵌入式 DuckDB）。
- v0 覆盖：单事实、多事实 NULL 缝合、除零、grain 拒绝、歧义路径拒绝、越权度量不可达、截断告警；并与统一 `SemanticGate` 行为对齐。

---

## Non-goals（明确非目标）

- **不重复 metric-6d**：不实现六维 Spec 编辑器、G1–G5 导入闸门、统计证伪、定义态探针生成；本 change **只消费**已 PUBLISHED 且（在 metric-6d 落地后）`verified` 通过的度量目录。
- **不做临床队列 / 评分 / 生存 / 回归 / PSM / 审方**：归 `analysis-hardening-clinical-foundation`（W6）；SMQ 不得表达锚点相对窗或统计推断。
- **LLM 永不读写 SQL AST / Dataflow IR**：禁止 NL2SQL 文本捷径；禁止 Agent「先看 SQL 再改」。
- **不在 MVP 引入完整约束解码引擎**：不上 GRID/LALR token mask、不上 TypeScript 级类型推断约束解码；枚举级 JSON Schema + 类型化拒绝即可。
- **不做物化视图路由 / 时间脊柱完整实现 / 半加性以外的高级代数优化**（可后续叠加）。
- **不以 Cube / AQL 为运行时必需依赖**；不新建第 9 个 Space；编译与目录投影留在 `dg-metadata`，执行经既有 `dg-data-spi`。
- **不把建模 MCP（`resolve_ref` 等）与问数编译混成同一工具语义**：建模选 ID ≠ 问数 SMQ 组装。

---

## Impact（API、Agent、CI、文档）

| 维度 | 影响说明 |
|---|---|
| **后端** | 扩展 `SemanticQuerySpec`；新增 `CompileReject` / 错误码枚举；升级 `SemanticQueryCompiler` 多事实缝合与（对齐 6D 后的）半加性/LOD；`compile_and_validate` REST + internal Agent 端点；Catalog Grammar 投影服务（按 ACL）。 |
| **Agent / MCP** | `compile_and_validate` 工具；增强 `list_metrics`/`list_dimensions` 约束字段；`execute_semantic_query` 与干跑共用编译入口；skills/问数 skill 更新为「目录 → SMQ → validate → 局部修复 → execute」。 |
| **契约** | 更新 `contracts/mcp/datagov-mcp.json`、必要时 OpenAPI；`ARCHITECTURE.md` §5.1 补充 SMQ 主链路与双注入点。 |
| **CI** | 新增问数 Golden SMQ + DuckDB 回归脚本；与 metric-6d 定义态探针套件并存、互不替代。 |
| **前端** | Playground / Ask 展示类型化拒绝与 `repair_hint`；不新增建模 YAML 能力（属 metric-6d）。 |

---

## Constitution Check（对照 `CONSTITUTION.md` C1–C10）

| 公理 | 合规检查结论 |
|---|---|
| **C1 Space 编译边界** | ✅ 目录投影与编译内核在 `dg-metadata`；执行经 `dg-data-spi` / 装配层；不引入对 connectors/control/runtime 的非法依赖。 |
| **C2 治理约束为编译输入** | ✅ `STANDARD_MISSING`、allowed grain/维度、安全策略均为编译期硬闸门；不合规拒绝发射 SQL。 |
| **C3 确定性计算优先** | ✅ LLM 只产 SMQ；SQL 仅由确定性编译器生成；禁止心算指标与直写 SQL。 |
| **C4 零信任与最小代理** | ✅ 目录与 compile/execute 均携带真实 `UserContext` + `workspaceId` 实时鉴权，无共享服务账号。 |
| **C5 纵深隔离与检索前鉴权** | ✅ Catalog Grammar 按角色预裁剪（不可枚举越权对象）；L3/L4 明细不因 debug 回传外部模型；编译强制注入安全谓词。 |
| **C6 克制写回** | ✅ 仅只读查询与平台内审计日志；无外部业务库写回。 |
| **C7 宿主独立确认** | ✅ 本 change 不引入高危写动作；澄清类交互用宿主白名单组件（既有 `CLARIFY_NEEDED`）。 |
| **C8 拒绝对猜与审计指纹** | ✅ 歧义/未命中 → 类型化拒绝或 `INCONCLUSIVE`/`CLARIFY_NEEDED`；成功路径保留 `semanticSnapshotRefs` + SHA-256 指纹。 |
| **C9 受控自演进** | ✅ 不因问数失败自动改写生产度量定义；定义演进仍走 metric-6d / PROPOSED 审校。 |
| **C10 编排解耦与评测** | ✅ 编译路径零 LLM；Golden SMQ 确定性回归接入 CI；概率性仅存在于 SMQ 生成侧车。 |

---

## 与相邻 Change 的边界（防范围膨胀）

```text
metric-6d-ontology-verification     smq-constrained-compile（本提案）
─────────────────────────────────   ────────────────────────────────
定义态：六维 Spec / G1–G5 / 证伪     查询态：SMQ IR / 目录 Grammar
建模 LLM：草稿 + Provenance         问数 LLM：选择组装 SMQ
MCP：list_* / resolve_ref           MCP/Agent：list_*+约束 / compile_and_validate / execute
DuckDB：定义探针 6 类               DuckDB：问数 Golden SMQ
对象图 = 名词表（唯一真相）  ──输入──►  编译器 = 语法+语义+安全裁判
```
