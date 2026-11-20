# Proposal: 统一本体语义元数据内核与业务知识图谱（Semantic Ontology Kernel）

- **Change ID**: `semantic-ontology-kernel`
- **Status**: Draft（待评审后拆解 `specs/` 与 `tasks.md`）
- **Constitution Alignment**: 严格遵循 `CONSTITUTION.md`（C1–C10）、`SPACE-MODEL.md`、`GOVERNANCE-MODEL.md §J`

---

## Why

当前平台的语义能力割裂在三处，尚未真正实现**人机共用单一语义真理源**：
1. `Metadata Space` 目前只有物理目录（`CatalogDataset` / `CatalogColumn`）与扁平术语绑定（`GlossaryTerm` / `GlossaryBinding`），缺乏类似 MicroStrategy Metadata 的强类型逻辑对象（`Entity / Attribute / Dimension / Fact / Metric`）、逻辑到物理多对多映射与对象间 `Link` / Join 路径定义；
2. BI 指标与维度定义目前外挂在外部 `SuperSonic` 中，脱离平台 `Metadata` 注册中心、版本管理与血缘影响分析，存在被单一语义引擎反向锁定的风险；
3. 纯结构化 BI 元数据只能回答“怎么算、怎么 Join”，仍不足以帮助 AI 准确理解业务语境（别名/缩写、口径歧义辨析、业务流程先后、指标排除/因果约束），导致 Agent 在问数与归因中容易误选口径或产生幻觉。

本变更在既有 `Metadata Space` 内落地 **Semantic Ontology Kernel（本体语义元数据内核 + 轻量业务知识图谱层）**，将 [GOVERNANCE-MODEL.md §J](../../../GOVERNANCE-MODEL.md) 预留的本体演进位做实，使人类 BI/看板、质量规则、血缘影响分析与 AI Agent 真正消费同一套带版本号的语义定义。

---

## What Changes

### 1. 强类型逻辑语义对象与逻辑→物理映射（MicroStrategy 风格内核）
- 在 `dg-metadata` 新增统一逻辑语义注册模型（统一 `Object ID`、版本、状态机 `DRAFT / PROPOSED / PUBLISHED / DEPRECATED`）：
  - **可计算语义对象（5 类）**：`ENTITY`（业务实体）、`ATTRIBUTE`（属性，支持 `ID / DESC / CODE / BROWSE` 多 Form）、`DIMENSION`（分析维度与层级 Hierarchy）、`FACT`（底层数值事实）、`METRIC`（基础聚合 / 衍生复合指标，强绑定 `Standard` 口径）；
  - **物理映射（`md_semantic_mapping`）**：支持单个逻辑对象映射到多张物理表/字段（`catalog_dataset_id` / `catalog_column_id` / `form_role` / `expression_sql` / `priority`），解耦逻辑口径与物理表结构变化；
  - **结构关系与 Join 路径（`md_semantic_link` · `STRUCTURAL`）**：`HAS_ATTRIBUTE`、`HAS_FACT`、`HIERARCHY_PARENT`、`RELATES_TO`（含基数与确定性 `join_spec_json`）、`DERIVED_FROM`（指标依赖图）。

### 2. 面向 AI 业务理解的知识图谱增强（同库融合，零额外图库运维）
- 在同一图模型上叠加轻量**业务知识图谱层**（复用 PostgreSQL + 递归 CTE，无需立即引入 Neo4j/Nebula，保留 Graph Query SPI 供 >3 跳大规模扩展）：
  - **语义知识节点（2 类）**：`CONCEPT`（抽象业务概念/分类，承接 `SPACE-MODEL.md §C+D.8` 与 `GlossaryTerm` 融合）与 `PROCESS`（业务流程/生命周期阶段）；
  - **语义与因果关系边（`md_semantic_link` · `SEMANTIC / CAUSAL`）**：`IS_A`、`PART_OF`、`SYNONYM_OF`、`MAPS_TO_CONCEPT`、`PRECEDES`（流程时序）、`CONSTRAINS` / `EXCLUDES` / `AFFECTS`（口径约束、互斥排除与归因线索）；
  - **AI 语义富化卡（`ai_context_json`）**：为每个对象挂载结构化业务理解信息——别名、歧义辨析（何时用/何时不用）、数据粒度（防扇出聚合）、已知口径坑、典型问法与脱敏后的安全枚举样例（严禁 L3/L4 明细）；
  - **受控子图检索器（Subgraph Grounding API）**：根据用户问题召回种子语义节点，沿边做受控 $k$-Hop（默认 $k \le 2$）扩展，**每一跳强制执行 Workspace + 权限 + 敏感分级过滤（Hop-by-Hop ACL）**，输出紧凑可复现的语义子图供 Agent 推理。

### 3. 原生确定性语义查询引擎与完全替换 SuperSonic
- 在 `InsightQueryService` 下抽取 `InsightQueryEngine` 接口（配置开关 `dg.insight.engine: native | supersonic`），支持双引擎过渡与 50 题同题对比评测，最终**完全下线 SuperSonic** 及配套账号映射代码与外部部署依赖：
  - **NL2SemanticSpec**：结合受控子图检索、L1/L2 低基数维度值对齐（Value Linking）与确定性相对时间归一化，LLM 仅输出结构化 `SemanticQuerySpec` JSON（严禁直接写物理 SQL，守 C3）；
  - **确定性语义 SQL 编译器（`SemanticQueryCompiler`）**：依据 `PUBLISHED` 语义对象、映射与 `STRUCTURAL` Join 路径生成确定性只读 SQL（含防多事实表扇出 CTE、衍生比率防除零、`defaultFilters`/`EXCLUDES` 自动注入），经 `dg-data-spi` 护栏在 `DataAccessGateway`（StarRocks/PG）执行；
- 打通 `CatalogColumn → SemanticMapping → SemanticObject(Attribute/Metric) → AnalysisReport / Insight` 的影响分析查询；
- 所有语义对象与图谱边支持双时间态（`valid_from` / `valid_to` / `version_no`）与快照摘要，向 `AnalysisReport` 和导出提供 `C8` 可复现版本号。

---

## Non-goals（明确非目标）

- **不新建第 9 个 Space**：全部定义态模型归属 `Metadata Space`（`dg-metadata`），严格维持 8 Space 架构无环。
- **本期不引入独立图数据库（Neo4j / Nebula Graph）**：先以 PostgreSQL + 索引 + 递归 CTE 落地（深度 ≤ 3 跳完全满足），遵循 `docs/space-contracts/metadata.yaml` 的存储策略。
- **不做 Palantir 式外部业务系统 Action 写回**：严守 `CONSTITUTION.md` `C6`，本本体层用于语义理解、确定性计算与影响分析，不驱动 HIS/EMR 写回。
- **不直接废弃现有 `GlossaryTerm`**：现有术语 API 保持向后兼容，通过平滑关联/映射融入 `CONCEPT` 节点。
- **不允许 LLM 直接覆盖生产本体/知识图谱**：AI 发现的新别名、关系或口径建议只能生成 `PROPOSED` 暂存条目，经人工审校后发布（C9）。

---

## Impact（路由、API、权限、审计与模块影响）

| 维度 | 影响说明 |
|---|---|
| **是否需要后端改动** | **是**（`dg-metadata` 领域模型/服务、`dg-app` REST/MCP 装配、Flyway 迁移如 `V37__semantic_ontology_kernel.sql`；同步更新 `docs/space-contracts/metadata.yaml`）。 |
| **新增/重构 API** | ① `GET/POST/PUT/DELETE /api/v1/metadata/semantic/objects` 与 `.../publish` / `.../versions`（逻辑对象与版本管理）；<br>② `GET/POST/DELETE /api/v1/metadata/semantic/objects/{id}/mappings`（逻辑→物理表列映射）；<br>③ `GET/POST/DELETE /api/v1/metadata/semantic/links`（结构 Join 边与知识图谱语义/因果边）；<br>④ `POST /api/v1/metadata/semantic/subgraph/search`（面向 Agent 与 UI 的逐跳鉴权子图检索）；<br>⑤ `GET /api/v1/metadata/semantic/impact?columnId=...`（物理列变更影响分析）；<br>⑥ `POST /api/v1/insight/semantic/compile`（确定性编译预览）并重构 `/api/v1/insight/datasets`、`/api/v1/insight/ask` 经 `InsightQueryEngine` 切换至原生引擎；<br>⑦ **退场清理**：达标后下线 `/api/v1/insight/supersonic-mappings/**`、前端 `MappingView.vue` 与 `SuperSonicClient`。 |
| **前端与 Agent** | 前端在元数据域新增“语义本体工作台”（对象列表、映射配置、轻量关系图谱与影响分析视图，遵循 `--dg-*` Token）；`agent-service` 新增只读工具 `search_semantic_subgraph` 与 `get_semantic_object`，替代靠表名盲猜；MCP 清单自动从 `PUBLISHED` 指标生成。 |
| **权限与审计** | 新增细粒度权限：`metadata:semantic:read`、`metadata:semantic:manage`、`metadata:semantic:publish`；对象发布、映射变更、图谱边审批与引擎同步全部经 `AuditInterceptor` 写入 `gv_audit_record`；子图检索强制按 `userId + workspaceId` 实时逐跳过滤，核心新表纳入 PostgreSQL RLS。 |

---

## Constitution Check（对照 `CONSTITUTION.md` C1–C10）

| 公理 | 合规检查结论 |
|---|---|
| **C1 Space 编译边界** | ✅ 全部定义态归 `dg-metadata`，不引入 `dg-data-connectors` / `dg-control` / `dg-runtime` 依赖；向 SuperSonic 编译同步在 `dg-app` 装配层或独立适配器完成，同步依赖严格无环。 |
| **C2 治理约束为编译输入** | ✅ `METRIC` / `ATTRIBUTE` 发布与下游编译时校验 `standard_ref` 存在性与生效状态，缺失则编译闸门报错。 |
| **C3 确定性计算优先** | ✅ 指标聚合、衍生公式与跨表 Join 路径由元数据确定性定义并交由查询引擎执行，AI 只通过子图理解业务与装配参数，不心算、不自由写回。 |
| **C4 零信任与最小代理** | ✅ 子图检索与对象查询强制携带真实 `UserContext` 实时鉴权，AI 仅获得只读检索工具，提案写操作走受控暂存。 |
| **C5 纵深隔离与逐跳鉴权** | ✅ 全表 `workspace_id` 隔离 + PG RLS；子图 $k$-Hop 遍历执行**检索前 + 逐跳 ACL**，自动裁剪用户无权访问的物理映射；`ai_context_json` 严禁存 L3/L4 明细，避免敏感数据出境。 |
| **C6 克制写回** | ✅ 仅管理平台内部语义定义，不开放外部业务库写回。 |
| **C7 宿主独立确认** | ✅ AI 提议新增/修改语义对象或图谱关系时，仅生成 `PROPOSED` 提案并由宿主独立审批界面确认发布。 |
| **C8 拒绝对猜与审计指纹** | ✅ 子图检索未命中认证指标/路径时显式声明缺失或返回 `INCONCLUSIVE`；语义快照版本/摘要接入 `AnalysisReport` 可复现指纹。 |
| **C9 受控自演进** | ✅ AI 抽取的业务同义词/因果边只能落 `PROPOSED` 暂存区，经人工审校后双时间态晋升并可回滚。 |
| **C10 编排解耦与评测** | ✅ 语义编译与同步为纯确定性流水线；变更须通过 `verify-primitives.py`、`mvn test`、`pnpm build` 与 `evals/regression`（`pass^3`）。 |
