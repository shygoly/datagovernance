# Proposal: AI 辅助本体映射建议入治理收件箱（人机协同复核）

- **Change ID**: `chance-ai-mapping-inbox`
- **Status**: Implemented
- **Depends On**: `semantic-ontology-kernel`、`governed-agent-evolution-mvp`（Consent / 演进审校模式）、既有 `workbench-governance-inbox` / 敏感复核模式
- **Coordinates With**: `chance-t0-alignment-and-qc-rules`（**并行**）、`chance-specialty-analytics-boards`（映射质量间接影响看板可信度）
- **Constitution Alignment**: 严格遵循 `CONSTITUTION.md`（尤其 **C4/C5/C7/C9**）与 `AGENT-PRIMITIVES.md`（A0.2 最小工具、A0.3 受控演进）

---

## Why

多源异构（HIS/EMR/PACS/LIS/随访）盘点后，物理字段到业务本体的映射仍大量依赖人工；Agent 虽能读目录，但缺少：

1. **结构化「映射建议」产物**：Top-K 候选 URN、转换类型、T₀ 陷阱警告、置信度与四维证据；
2. **强制进入治理收件箱的人机闭环**：一键确认/修正后沉淀可复用映射规则，而非聊天里口头答应；
3. **与驳回账本联动**：历史驳回指纹抑制重复烂建议（对齐 C9 / maintenance-log 模式）。

---

## What Changes

### 1. 映射建议 API 与 Schema
- 输入：脱敏后的源表画像（表名、字段名、注释、类型、空值率、单位分布、k≥5 聚合枚举）+ 目标业务本体定义 + 可选 `maintenance-log` 摘要。
- 输出：严格 JSON 数组（对齐研究稿 Prompt5 / P2.3 契约）：`recommended_business_urn`、`alternative_candidates`、`confidence`、`transformation_type`、`transform_expression`、`join_condition_proposal`、`evidence`、`routing_tier`（`fast_track_review` / `standard_hitl_review` / `block_and_escalate`）。
- **禁止**请求或返回个体明文 PHI；注释文本一律不可信（防间接提示词注入）。

### 2. 收件箱工单与宿主确认
- 建议落库为 `PROPOSED` 映射工单，进入治理收件箱队列；
- 治理员/临床在宿主 UI 确认、改映射或驳回；确认后写入正式语义映射（或 Logical mapping），驳回写入原因 + 指纹防重提；
- 高危（核心终点 / L4–L5 / conf&lt;0.75）强制 `AgentConsentDialog` 二次确认（C7）。

### 3. Agent / Skill 接线
- 新增最小权限工具：`suggest_field_mappings`（只产建议）、`list_mapping_proposals`、`submit_mapping_decision`（经提案 API，不直写生产映射）；
- Skill 包渐进加载；默认不预加载全量 CHANCE 60+ 字典。

---

## Non-goals

- **不自动晋升生产映射**：无人工确认不得生效（C9）。
- **不在本 change 实现 T₀ 对齐算子或专病 BI**。
- **不扫描明文行级数据做映射**；仅元数据 + 聚合画像。
- **不把 60+ CHANCE 模块字典写进 Java 内核**；字典以 Standard/Skill/种子承载。

---

## Acceptance Scenarios

### Scenario 1：同名异义陷阱降级
- **GIVEN** 源字段 `treat_date` 注释含「治疗」但值域实为计费日，另有 `cath_out_dt` 为真 T₀；
- **WHEN** 生成映射建议；
- **THEN** `treat_date` 不得 fast_track 到临床 T₀ URN；`evidence.t0_trap_warning` 非空；路由 ≥ `standard_hitl_review`。

### Scenario 2：确认沉淀与驳回防重提
- **GIVEN** 治理员驳回某错误映射建议；
- **WHEN** 相同指纹再次建议；
- **THEN** 被抑制或强制 escalate，并可见驳回理由引用。

### Scenario 3：安全边界
- **GIVEN** 工具调用缺少合法 `userId+workspaceId` 或试图拉取行级 PHI；
- **THEN** 拒绝并审计；数据库无映射变更。

---

## Impact

| 维度 | 影响 |
|---|---|
| **后端** | Metadata/Governance：映射提案实体、收件箱查询、确认写映射；Flyway+RLS |
| **Agent** | 工具 + Skill；evals 回归用例 |
| **前端** | 治理收件箱映射复核页；复用 Consent |
| **契约** | MCP/OpenAPI 增量 |

---

## Constitution Check

| 公理 | 结论 |
|---|---|
| **C1** | ✅ 建议与审校在 Metadata/Governance；不向 Control 发执行命令 |
| **C3** | ✅ LLM 只产建议 JSON；生效映射为人审结果 |
| **C4** | ✅ 实时鉴权 + 最小工具集 |
| **C5** | ✅ 仅脱敏元数据/聚合；Pre-retrieval ACL |
| **C7** | ✅ 确认/驳回宿主独立 UI |
| **C9** | ✅ PROPOSED→审校；驳回防重提 |
| **C10** | ✅ 建议生成与落库审校解耦；pass^3 回归 |
