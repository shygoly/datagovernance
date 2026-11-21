# Proposal: 度量六维正交本体、对象图五道闸门与四层证伪闭环（Metric 6D Ontology & Verification Loop）

- **Change ID**: `metric-6d-ontology-verification`
- **Status**: Draft（已同步核心元语至 `ARCHITECTURE.md`、`docs/space-contracts/metadata.yaml`、`contracts/mcp/datagov-mcp.json`）
- **Depends On**: `semantic-ontology-kernel`、`multisource-semantic-federation`
- **Coordinates With**: `analysis-hardening-clinical-foundation`（**W2**：交付顺序必须 **G1–G5 / 删除保护 / 变更传播先于六维运算接通编译器**；未 `verified` 对象不得进入生产问数目录）
- **Constitution Alignment**: 严格遵循 `CONSTITUTION.md`（C1–C10）、`SPACE-MODEL.md`、`GOVERNANCE-MODEL.md`

---

## Why

在 `semantic-ontology-kernel` 落地后，平台已具备统一的逻辑语义对象（`ENTITY / ATTRIBUTE / DIMENSION / FACT / METRIC`）与轻量知识图谱，但在面向 AI 辅助建模与生产级指标治理时仍存在四大结构性缺口：

1. **度量语义表达维度不足**：当前 `METRIC` 仅含基础聚合、比率与默认过滤，缺乏完整的**六维正交**建模能力（可加性含半加性 `non_additive_dim`/`window_groupings`、LOD 修饰符、过滤交互、时间日历、多态组合、图拓扑局部性），导致复杂口径只能退化为裸 SQL 或容易在跨粒度聚合时静默算错。
2. **LLM 建模权责边界未机械化**：若让 LLM 直接充当“定义作者”，容易臆造主键/基数、把比率写成行级 `a/b`、漏配 `window_groupings`，甚至触碰安全/租户/RLS 列；必须把 LLM 降级为**“候选起草 + 证据收集员”**，由人签署语义契约，由统计与探针负责确定性证伪。
3. **文本引用易漂移、缺乏图级硬不变量**：纯文本 YAML 无法强制外键级引用完整性、类型匹配、无环 DAG、删除保护与上游变更传播，LLM 自由拼写引用 ID 极易造成静默污染。
4. **“定义错了不报错”缺少运行时证伪**：静态语法正确不代表业务口径正确（如漏标 `1:N` 导致扇出翻倍、可加性错标、多事实缝合 NULL 组裂变、除零异常），必须在生效前通过**物理表统计证伪 + 探针查询执行断言**把荒谬定义拦在发布门外。

---

## What Changes

### 1. 度量六维正交契约 + Provenance 证据模型
- 在 `dg-metadata` 扩展 `METRIC` 的 `spec_json`，以**六维正交**结构化描述度量定义：
  1. **可加性（Additivity）**：`additive` / `semi_additive`（强制配 `non_additive_dim` + `window_groupings`）/ `non_additive`；
  2. **LOD（Level of Detail）**：`FIXED` / `INCLUDE` / `EXCLUDE` 及引用的规范维度集合；
  3. **过滤交互（Filter Interaction）**：默认过滤、忽略/锁定维度过滤作用域、聚合前后过滤阶段；
  4. **时间日历（Time Calendar）**：业务/财年日历引用、时间粒度与周期算子（禁止硬编码 `DATE_TRUNC`）；
  5. **多态组合（Polymorphic Composition）**：基础聚合、派生比率（强制 `NULLIF` 防除零）、复合子度量依赖；
  6. **图拓扑局部性（Graph Locality）**：实体键（`primary` / `unique` / `foreign`）与连接基数（`1:1` / `1:N` / `N:M`，仅接受 KG 边或 FK 约束，禁止从命名猜）。
- 建立字段级 **Provenance 溯源与 LLM 权责治理矩阵**：
  - 来源优先级：`db_constraint > knowledge_graph > historical_sql > llm_inference`；
  - `llm_inference` 来源字段**强制** `requires_human_review: true`（不信任 LLM 自评置信度）；
  - **安全/租户列与 RLS 策略严禁 LLM 参与**（仅策略系统/人工标注）；
  - `verified` 区块记录统计证伪与探针断言结果，作为生效前硬门槛。

### 2. 对象图为唯一真相、YAML 为双向投影 + 五道图闸门（G1–G5）与删除保护
- 明确 **Ontology Graph 是元模型资产库唯一真相**，YAML 仅为其导入/导出投影（支持**短名可读模式**与**全 ID 机器 diff 模式**，两种投影源自同一张图，绝不漂移）。
- **YAML 短名解析 + 显式 `ref` 块**：短名优先在当前模型命名空间解析；解析不到报 `UNRESOLVED_REF`（附模糊候选），多候选报 `AMBIGUOUS_REF`（要求显式 `ref` 块）；解析成功后在对象图建立类型化边（`dimension_ref` / `metric_ref` / `column_ref`），记录 `target_id + target_content_hash`。
- **图上五道硬闸门（G1–G5）与删除保护**：
  - **G1 Schema**：YAML 结构、六维正交轴合法性与 `ref` 语法校验 → 拒绝导入；
  - **G2 引用完整性**：所有 `ref` 必须解析到已存在节点 → 拒绝导入并列候选；
  - **G3 类型匹配**：表达式与 LOD 引用节点类型必须合法（如 `INCLUDE` 仅引 `DIMENSION`，`METRIC` 不可当 `COLUMN`）→ 拒绝导入；
  - **G4 图结构**：无循环引用、复合度量依赖 DAG 无环 → 拒绝导入；
  - **G5 变更传播**：被引用节点 `content_digest` 变化 → 引用方自动标记 `STALE_REVIEW`，合入/发布前强制复核；
  - **删除保护**：删除被引用的维度/度量时图查询直接拒绝，并列出所有引用方。

### 3. 四层确定性验证闭环（③a–③d）与常驻 CI
- **③a 静态校验**：即图上闸门 G1–G4 + 六维轴正交合法性 + 列敏感度/可见性校验。
- **③b 统计证伪**：对每个声明的结构性假设生成只读证伪 SQL，经 `DataAccessGateway` 在源表校验 PK 唯一性、1:N NDV 比、FK 匹配率；任一 FAIL 对应字段自动降级 `LOW_CONFIDENCE` 进入人工审校队列。
- **③c 探针查询（6 类探针 SMQ + DuckDB 执行）**：自动基于对象图构造 6 类探针 SMQ（扇出探针、可加性探针、半加性探针、NULL 维度探针、除零探针、量级探针），经 `SemanticQueryCompiler` / `MultiWarehouseQueryCompiler` 编译并在嵌入式 DuckDB 执行断言，作为元模型资产库常驻 CI。
- **③d 反模式检测**：纯确定性规则库拦截行级 `a/b` 代替 `SUM(a)/SUM(b)`、`semi_additive` 缺 `window_groupings`、硬编码 `DATE_TRUNC`、维度标为 `PRIVATE` 等典型坑。

### 4. MCP 接地选 ID 工具（赋能 LLM 建模低成本试错）
- 在 `contracts/mcp/datagov-mcp.json` 与 `dg-app` MCP 端点新增 3 个只读接地工具：`list_dimensions(model)`、`list_metrics(model)`、`resolve_ref(short_name)`，返回规范 ID + 类型 + 描述。
- LLM 生成度量草稿时仅从对象图目录选择规范 ID；若出现非法 ID，G2 导入期直接硬失败并返回 `UNRESOLVED_REF` + 候选列表，支持秒级自修。

---

## Non-goals（明确非目标）

- **不新建第 9 个 Space**：全部定义态、图闸门与编译逻辑归属 `dg-metadata`（Metadata Space），执行经既有 `dg-data-spi` / `DataAccessGateway` 与嵌入式 DuckDB 完成。
- **不以散落 YAML 文件替代对象图**：YAML 仅作为 Git 评审、CI diff 与人工编辑的投影格式，禁止绕过对象图与 G1–G5 闸门直接生效。
- **不允许 LLM 直接签署或发布度量**：LLM 仅可提交 `PROPOSED` 草稿；任何 `llm_inference` 字段、半加性配置、敏感可见性与全部安全/RLS 策略必须经宿主界面人工确认（C7/C9）。
- **不绕过既有 `Standard` 与脱敏体系**：保留 C2 `standard_ref` 编译闸门与 `md_column_sensitivity` 兜底脱敏。

---

## Impact（数据模型、API、MCP、CI 与前端影响）

| 维度 | 影响说明 |
|---|---|
| **后端与数据库** | `dg-metadata` 新增六维 `MetricSpec`、Provenance/Verified 结构、`SemanticYamlCodec`、`SemanticGraphGateService`（G1–G5 + 删除保护）、`SemanticAntiPatternLinter`、`MetricFalsificationService`（③b/③c 探针生成与断言）；Flyway 迁移新增 `md_semantic_verification_record` 表及 `md_semantic_object` / `md_semantic_link` 扩展列（`provenance_json`、`verified_json`、`target_content_hash`）。 |
| **REST 与 MCP API** | ① `POST /api/v1/metadata/semantic/yaml/import` & `GET /api/v1/metadata/semantic/yaml/export?mode=short|full`；<br>② `POST /api/v1/metadata/semantic/objects/{id}/verify`（触发 ③a–③d 四层验证并回写 `verified_json`）；<br>③ `GET /api/v1/metadata/semantic/objects/{id}/delete-impact`（删除保护前置检查）；<br>④ MCP 只读工具：`list_dimensions`、`list_metrics`、`resolve_ref`（均携带 `UserContext` + `workspaceId` 实时鉴权）。 |
| **前端与审批体验** | 语义本体工作台新增：六维度量编辑器、YAML 双模式导入导出预览、Provenance 来源徽标与 `LOW_CONFIDENCE` / `STALE_REVIEW` 人工复核队列、四层验证报告与探针结果面板（严守 `--dg-*` Token 与 C7 独立确认）。 |
| **CI 门禁** | 新增常驻探针回归脚本，每次 YAML / 本体变更自动运行 G1–G5 + ③d 反模式 + DuckDB 6 类探针断言。 |

---

## Constitution Check（对照 `CONSTITUTION.md` C1–C10）

| 公理 | 合规检查结论 |
|---|---|
| **C1 Space 编译边界** | ✅ 定义态、图闸门、反模式检测与探针 Spec 生成全在 `dg-metadata`；统计证伪与探针执行经 `dg-data-spi` / 装配层 DuckDB 适配，不引入 `dg-data-connectors`/`dg-control`/`dg-runtime` 依赖，同步依赖无环。 |
| **C2 治理约束为编译输入** | ✅ G1–G5 图闸门、六维轴合法性、Provenance 审核状态与 `verified` 门禁直接作为编译与发布前置输入，不合规拒绝生效。 |
| **C3 确定性计算优先** | ✅ 统计证伪 SQL、探针 SMQ 编译与反模式检测均为 100% 确定性逻辑；LLM 仅产结构化候选草稿，不直接输出执行 SQL，不心算指标。 |
| **C4 零信任与最小代理** | ✅ `list_dimensions` / `list_metrics` / `resolve_ref` 与所有验证接口必须携带真实 `UserContext` + `workspaceId` 实时鉴权，无共享服务账号。 |
| **C5 纵深隔离与分级路由** | ✅ 安全/租户列与 RLS 策略硬禁 LLM 参与；新增表纳入 `workspace_id` 隔离与 PG RLS；探针与证伪 SQL 严禁泄露 L3/L4 行级明文至外部模型。 |
| **C6 克制写回** | ✅ 仅操作平台内部元数据对象图，不向外部业务库写回；YAML 导入与验证执行均带幂等键与完整审计。 |
| **C7 宿主独立确认** | ✅ `requires_human_review: true` 字段、`LOW_CONFIDENCE` 降级项与 `STALE_REVIEW` 节点必须经前端宿主独立审校视图确认发布，严禁聊天流内口头生效。 |
| **C8 拒绝对猜与审计指纹** | ✅ 无法解析引用返回 `UNRESOLVED_REF`，多候选返回 `AMBIGUOUS_REF` 绝不静默猜测；`provenance`、`verified` 与 `target_content_hash` 共同纳入不可变 `content_digest` 审计指纹。 |
| **C9 受控自演进** | ✅ LLM 生成的度量定义仅能进入 `PROPOSED` 暂存态，经统计证伪 + 探针断言 + 人工 Diff 审校后才可晋升 `PUBLISHED` 并支持版本回滚。 |
| **C10 编排解耦与评测** | ✅ 验证闭环为纯确定性流水线且接入常驻 CI；变更须通过 `verify-primitives.py`、`mvn test`、`pnpm build` 与回归门禁。 |
