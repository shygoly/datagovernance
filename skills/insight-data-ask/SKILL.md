---
name: insight-data-ask
description: 协助用户检索平台单一语义本体与知识图谱子图、发现可用语义主题并发起受治理的确定性问数查询，解读聚合结论、口径排除约束、脱敏列与审计指纹。当用户询问有哪些表、语义主题、指标口径、统计、趋势、排名、金额或数量分析时触发。
allowed-tools:
  - list_datasets
  - search_semantic_subgraph
  - get_semantic_object
  - get_catalog_grammar
  - list_metrics
  - list_dimensions
  - compile_and_validate
  - execute_semantic_query
  - ask_data
  - navigate_workspace
---

# 受治理智能问数与语义本体规程

> 所属元语：`A0.2`（问数与语义检索只读技能包，不含任何写工具）。
> 安全红线：严守 `CONSTITUTION.md` C2（指标须绑定 Standard）、C3（确定性编译优先，严禁 LLM 上下文心算指标或裸写 SQL）、C4（按发起人实时鉴权）、C5（逐跳 ACL 与 L3 脱敏）、C8（证据不足返回 `INCONCLUSIVE`，附可复现审计指纹）。

## 标准执行步骤

1. **Catalog Grammar / 语义子图 Grounding（优先于盲猜）**
   - 组装 SMQ 前优先调用 `get_catalog_grammar`（或 `list_metrics`/`list_dimensions`）获取 ACL 裁剪后的可枚举 code、`allowedDimensions`/`allowedGrains`/`atModifiers`；越权对象不会出现在目录中（C5）；
   - 需要口径坑/排除关系时，再调用 `search_semantic_subgraph` 获取 `ai_context_json` 与 `EXCLUDES`/`CONSTRAINS` 边；
   - 若用户问“有哪些数据集/语义主题”，调用 `list_datasets`，禁止凭记忆编造。
2. **SMQ → validate → 局部修复 → execute**
   - 用 Catalog Grammar 内的 code 组装 `SemanticQuerySpec`（可含 `grain` / `atModifiers`）；
   - 先调用 `compile_and_validate` 干跑：成功再 `execute_semantic_query` 或 `ask_data`；失败时**只改** `error_code` 指向的字段（参考 `allowed` / `repair_hint`），最多局部修复 3 次，禁止整份 Spec 盲重生；
   - 若返回 `MULTI_FACT_AMBIGUOUS_PATH`，在 `at_modifiers` 加入 `{"op":"USING","rel":"<候选>"}` 后重试；
   - 若返回 `CLARIFY_NEEDED`（多义口径冲突），清晰列出各候选口径的 `disambiguation` 说明，请用户选择后再执行；
   - 若返回 `INCONCLUSIVE`（未登记指标或无证据），如实告知并引导至 `/metadata/semantic` 发布口径，严禁心算或猜测。
3. **中文结论呈现与可复现提示**
   - 先给核心结论，再列 2–3 条关键要点；
   - 明确展示自动生效的口径排除约束（`appliedExclusions`）、脱敏列（`maskedColumns`）、审计指纹（`auditFingerprint`）及可选 `reproducibilityFingerprint`，不输出原始明细行。
