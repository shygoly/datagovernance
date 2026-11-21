---
name: chance-hcc-ask
description: 在 Synthea/CHANCE 肝癌专病语境下做受治理问数：先检索 CNLC/mRECIST/T0/程次等已发布语义对象，再组装指标查询。当用户询问肝癌分期分布、TACE/HAIC 程次、mRECIST/ORR、专病随访或 synthea 队列时触发。
allowed-tools:
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

# CHANCE 肝癌专病问数规程

> A0.2 渐进技能。权威分期口径以已发布 CONCEPT/`attr.diagnosis.cnlc_stage` 为准（CNLC-2024），禁止凭记忆编造分期定义。

## 标准步骤

1. `activate_skill` 后先 `search_semantic_subgraph`（关键词如 CNLC、mRECIST、T0、session），确认 `process.hcc.chance_care_pathway` 与相关 METRIC。
2. 组装 SMQ 时优先使用：`metric.hcc.session_count` / `followup_visit_count` / `orr_rate` / `dead_count`，维度用 `attr.diagnosis.cnlc_stage`、`attr.treatment.modality`、`attr.followup.mrecist_response`。
3. **硬约束**：治疗归因必须带 `session_seq`；T0 仅 `actual_exec_end_t0`；HAIC 不得无过滤地并入「TACE 次数」。
4. ORR 为访视级 MVP（CR+PR）；若用户要最佳总体疗效或 KM，引导 `/insight/chance` 专病看板，禁止心算。
5. 证据不足返回 INCONCLUSIVE；可 `navigate_workspace` 到 `/metadata/semantic` 或 `/insight/chance`。
