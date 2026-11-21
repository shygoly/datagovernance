# CHANCE-HCC / Synthea 四类知识种子调研沉淀

> 状态：research（候选晋升 articles 前须治理员审校）  
> 对照数据：`synthea_hcc.*`（100 人队列）  
> 权威口径：国家卫健委《原发性肝癌诊疗指南（2024 年版）》CNLC；TACE 短期疗效常用 mRECIST

## 是否需要「调研类」Skill / MCP？

**不需要单独调研 MCP/Skill。**

| 能力 | 现有落点 | 说明 |
|---|---|---|
| 指南口径一次调研 | 本文 + `ai_context_json` | 人工/Agent 离线调研后写入本体，不在运行时爬网 |
| Agent 问数 grounding | `search_semantic_subgraph` + 可选 `chance-hcc-ask` Skill | 消费已发布 CONCEPT/PROCESS，禁止现场 invent 分期 |
| 映射建议 | 已有 `chance-ai-mapping` | 管字段→URN，不管医学文献检索 |
| 大文件/湖仓 | MinIO 仅分析湖 | 知识不进对象存储 |

运行时外挂「文献检索 MCP」会绕过 ACL、双时间态与 C9 审校，且易把未审校全文灌进上下文。

## 已补充的四类知识（种子脚本）

1. **PROCESS** `process.hcc.chance_care_pathway`：诊断 → 介入程次(T0/`session_seq`) → mRECIST 随访 → 生存/失访；`PRECEDES`/`PART_OF` 边。
2. **CONCEPT**：`cnlc_staging`、`mrecist`、`t0_actual_exec`、`locoregional_modality`（TACE≠HAIC）。
3. **METRIC**：`session_count`、`followup_visit_count`、`orr_rate`（访视级 CR+PR MVP）、`dead_count`。
4. **ENTITY 修正**：新增 `entity.treatment_session` / `followup_visit` / `imaging_report` / `hcc_diagnosis`；**撤销**将 `tace_session` 挂到 `entity.admission`。

## CNLC-2024 摘要（写入 attr 消歧卡）

- Ia：单灶 ≤5 cm；Ib：单灶 >5 cm 或 2–3 灶最大径 ≤3 cm  
- IIa：2–3 灶最大径 >3 cm；IIb：≥4 灶无栓无肝外  
- IIIa：有血管侵犯无肝外；IIIb：有肝外转移；IV：PS 3–4 或 Child-Pugh C  

## 非目标

- 不把指南 PDF/影像报告全文写入 `ai_context`  
- 不在本种子实现完整寿命表 KM（仍走专病看板/统计算子）  
- 不镜像 MIMIC 通用表凑专病指标  
