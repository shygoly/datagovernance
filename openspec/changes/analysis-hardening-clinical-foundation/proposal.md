# Proposal: 分析正确性硬化、临床队列原语与确定性统计执行层

- **Change ID**: `analysis-hardening-clinical-foundation`
- **Status**: Draft
- **Depends On**: `semantic-ontology-kernel`、`multisource-semantic-federation`
- **Coordinates With**: `metric-6d-ontology-verification`、`smq-constrained-compile`（本提案重排其交付优先级并补齐跨路径门禁）
- **Constitution Alignment**: 严格遵循 `CONSTITUTION.md`（尤其 **C3** 确定性计算、**C8** 可复现指纹、**C1** Space 边界）

---

## Why

当前语义问数链路（`SemanticQuerySpec` → `SemanticQueryCompiler` / 多仓 / 联邦）与度量六维提案，解决的是「目录内聚合度量如何确定性编译」。但面向真实医疗分析与可治理报表时，仍存在六类结构性缺口：

1. **编译器语义门禁未统一**：单仓、多仓（`COHORT_UNION`）、联邦（`ENTITY_FEDERATED_JOIN`）路径对允许维度、时间窗、默认过滤、`EXCLUDES`、数据 ACL、截断与审计的执行不一致，存在「一路径守住、另一路径漏过」风险。
2. **度量图契约未先于六维运算落地**：六维运算若先于 Schema/引用/类型/DAG/删除保护/变更传播，会在错误图上做复杂计算。
3. **统计正确性红线未固化**：NULL 可能静默变 0；缺少 NULL 维度、重叠队列、除零、粒度与截断等 Golden。
4. **可复现指纹不完整**：`docs/analysis-report.md` §10 已预留结构，但问数/算子/报表路径尚未统一捕获数据快照、指标/Standard 版本、算子摘要与委派链。
5. **缺少真正的运营报表 MVP**：既有 `AnalysisReport` 是 Governance「解释结论」对象，不是带参数、复合块、PDF/Excel、版本回滚、快照与定时分发的业务报表产品。
6. **临床高阶分析被误塞进 SQL 字符串编译器**：队列时序、OR/EXISTS/序列事件、评分、生存/回归/PSM、审方规则等**不能**靠拉长 SMQ/度量表达式解决；须独立 **Cohort/Criterion + 确定性统计执行层**（呼应此前结论）。

本提案把上述工作收束为一条**可分期、边界清晰**的硬化与临床基础建设程序，并明确：**医疗统计能力单独建设，禁止继续塞进 SQL 字符串编译器。**

---

## What Changes

### 程序总览（六条工作流 W1–W6）

| ID | 工作流 | 主归属 | 与相邻 Change 关系 |
|---|---|---|---|
| **W1** | 统一编译器语义门禁（单仓/多仓/联邦共用） | 本提案 Phase A + 回写 `smq-constrained-compile` | 问数编译内核必须先共用 `SemanticGateContext` |
| **W2** | 指标 Schema 与图校验优先于六维运算 | **重排** `metric-6d-ontology-verification` | G1–G5 + 删除保护 + 变更传播 **先于** 半加性/LOD 运算接通 |
| **W3** | 统计正确性修复 + Golden | 本提案 Phase A/B + `smq-constrained-compile` Golden | NULL≠0、NULL 维、重叠队列、除零、粒度、截断 |
| **W4** | 完整可复现指纹 | 本提案横切契约；Insight / Stats / Report 共用 | 落地 `reproducibility_fingerprint`（对齐 `docs/analysis-report.md` §10） |
| **W5** | 真正的报表 MVP | 本提案 Phase D（或拆分子 change 仍由本程序门禁） | 参数、复合块、PDF/Excel、版本回滚、快照、定时分发 |
| **W6** | 医疗确定性统计执行层 + Cohort/Criterion | **本提案核心交付** | 与 SMQ/METRIC 解耦；METRIC 仅消费算子/队列输出 |

### W1 — 统一编译器语义门禁
- 引入共享 **`SemanticGateContext` / `SemanticGateService`**：允许维度、时间窗归一化结果、度量 `defaultFilters`、图谱 `EXCLUDES`、数据集 ACL、`maxRows` 截断策略、审计字段强制清单。
- `SemanticQueryCompiler.compile`、`MultiWarehouseQueryCompiler`（含 `SINGLE_ROUTE` / `COHORT_UNION` / `ENTITY_FEDERATED_JOIN`）与联邦执行入口**必须调用同一套闸门**；禁止路径私有「宽松模式」。
- 截断时：结果态显式 `truncated=true` + 警告进入指纹；**禁止**静默当全集。

### W2 — 指标 Schema 与图校验优先
- 对 `metric-6d` 交付顺序硬约束：**先** Schema、引用完整性、类型匹配、DAG 无环、删除保护、`STALE_REVIEW` 变更传播；**再**接通六维运算到编译器。
- 未通过 G1–G5 / `verified` 门禁的对象不得进入生产 Catalog Grammar / SMQ 编译。

### W3 — 统计正确性
- 编译与聚合语义：**NULL 不得隐式 COALESCE 为 0**（除非度量 Spec 显式声明并经人审）。
- Golden（PG 优先夹具；联邦场景可辅以现有 DuckDB）：NULL 维度缝合、重叠队列双计风险、除零、非法粒度拒绝、截断告警断言。

### W4 — 完整可复现指纹
- 统一结构（可 JSON）：
  - `datasourceSnapshots[]`
  - `metricOrObjectVersions[]` / `standardVersions[]`
  - `operatorDigests[]`（SQL 模板或统计算子摘要）
  - `gateDigest`（门禁配置摘要）
  - `delegationChain[]`（userId → agent/session）
  - `truncated` / `rowLimit` 元数据
- Insight 成功/拒绝、Stats 算子输出、运营报表快照均须绑定同一指纹 schema（C8）。

### W5 — 运营报表 MVP（Governed Operational Report）
- **不是**替代 `AnalysisReport`（解释结论）；新产品对象建议名 **`OperationalReport`**（或等价），归属需在 design 中裁定（偏 Control 调度 + Governance 审计，或 Metadata 定义 + Runtime 渲染）。
- 必备能力：**参数化**、**复合块**（多 SMQ / 多 Stats 块编排）、**PDF/Excel 导出**、**版本回滚**、**结果快照**、**定时分发**（邮件/站内；高危导出走 C7 宿主确认）。
- 每块执行走 W1 门禁 + W4 指纹；禁止报表定义内嵌裸 SQL。

### W6 — 临床 Cohort/Criterion + 确定性统计执行层（核心）
- 新增原语（术语写入 `ARCHITECTURE.md` §1）：
  - **`CohortSpec`**：目标人群定义（锚点事件、相对时间窗、纳入/排除）。
  - **`CriterionNode`**：可审校条件树（AND/OR/NOT、EXISTS、序列、FIRST/LAST 事件），**不是**自由 SQL。
  - **`ClinicalOperator`**：版本化确定性算子（SOFA/APACHE、生存、回归、PSM、CI 等）接口；输入/输出 schema + `operatorDigest`。
- **`ClinicalStatsRuntime`**（工作名）：沙箱执行统计/评分；**禁止**由 `SemanticQueryCompiler` 字符串拼接实现；可经 Control 编排、Runtime 执行，结果以表/Arrow/制品回写平台内仓并登记 Catalog。
- METRIC/SMQ **只引用**已物化的队列成员集或算子输出列；审方/相互作用规则走既有/扩展 **RuleSpec**，不进 SMQ。

---

## Non-goals（明确非目标）

- **不把**生存分析、回归、PSM、显著性、CI、SOFA/APACHE **实现为** SMQ 字段或 SQL 编译器分支。
- **不**在本提案一次性补齐全部院感/AE/合理用药/再入院域指标内容包（只建原语、算子接口与 1–2 个可运行样例证明闭环）。
- **不**开放 Agent 对外部 HIS/EMR 写回（C6）；统计落表仅限平台托管仓/schema。
- **不**用 LLM judge 做 Golden；不引入 SQLGlot/DataFusion 为编译热路径必需依赖。
- **不**用运营报表替代 `AnalysisReport` 的「解释结论」语义。
- **不**新建第 9 个 Space；Cohort 定义态偏 Metadata，统计执行偏 Runtime/装配，门禁与指纹契约跨模块共享而不环依赖。

---

## Impact

| 维度 | 影响 |
|---|---|
| **元语 / 契约** | `ARCHITECTURE.md` §1 增补 Cohort/Criterion/ClinicalOperator/OperationalReport/SemanticGate；space-contracts 与 MCP/OpenAPI 按 Phase 追加 |
| **编译路径** | 抽出共享门禁；多仓/联邦对齐；Golden 扩展 |
| **metric-6d** | 任务顺序改为「图闸门 → 再六维运算」 |
| **smq-constrained-compile** | 依赖本提案 W1/W3/W4 契约；Catalog Grammar 只暴露已门禁对象 |
| **新模块倾向** | `ClinicalStatsRuntime` SPI + 至少一种进程内/侧车实现；Cohort 编译器（确定性） |
| **报表** | 新 API/表结构：定义、版本、快照、分发任务；导出与定时 |
| **CI** | 门禁一致性单测 + 正确性 Golden + 指纹字段完备断言 |

---

## Constitution Check（C1–C10 摘要）

| 公理 | 结论 |
|---|---|
| **C1** | ✅ Cohort 定义在 Metadata；执行经 SPI/Runtime/装配；Maven 无环；不把统计引擎塞进 `dg-metadata` 的 JDBC 热路径 |
| **C2** | ✅ 门禁含 Standard；未绑定拒绝编译/入报表 |
| **C3** | ✅ 评分与统计仅确定性算子；LLM 不产 SQL、不心算评分 |
| **C4** | ✅ 门禁与执行均 `UserContext + workspaceId` |
| **C5** | ✅ ACL 在门禁层统一；L3/L4 不进外部模型；导出脱敏 |
| **C6** | ✅ 仅平台内落表/快照；无外部业务库写回 |
| **C7** | ✅ 定时分发与敏感导出宿主确认 |
| **C8** | ✅ 完整 `reproducibility_fingerprint`；证据不足 `INCONCLUSIVE` |
| **C9** | ✅ 临床定义与算子版本经审校晋升，禁止对话内覆写 |
| **C10** | ✅ 统计执行与编译编排零裸调 LLM；评测用 Golden/算子夹具 |

---

## 建议阅读顺序

1. 本文件 → [design.md](./design.md)（分层与接口）→ [tasks.md](./tasks.md)（Phase 与勾选）
2. 并行阅读并接受重排：`metric-6d-ontology-verification`、`smq-constrained-compile`
