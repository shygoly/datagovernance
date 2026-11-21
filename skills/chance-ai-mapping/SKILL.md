---
name: chance-ai-mapping
description: 协助用户基于脱敏字段画像生成字段到业务本体的映射建议，写入治理收件箱供人机审校，并在确认后沉淀或驳回防重提。当用户提到本体映射、字段对齐、URN 建议、T0 时间陷阱、映射收件箱或 CHANCE 字段映射时触发。
allowed-tools:
  - suggest_field_mappings
  - list_mapping_proposals
  - submit_mapping_decision
  - navigate_workspace
---

# AI 辅助本体映射建议规程

> 所属元语：`A0.2`（渐进式技能；默认不预加载全量 CHANCE 字典）。
> 安全红线：严守 `CONSTITUTION.md` C4/C5/C7/C9 —— 仅脱敏元数据/聚合画像；建议不自动生效；确认/驳回优先宿主 UI。

## 适用前置条件

1. 已有脱敏字段画像（表名、字段名、注释、类型、空值率、单位提示、k≥5 枚举），**严禁**请求或粘贴行级 PHI；
2. 目标业务 URN 列表已知或可缩小到少量候选（不要一次塞入 60+ 全量字典）。

## 标准执行步骤

1. **激活本技能**（若尚未激活）后，向用户确认画像字段列表与候选 URN 范围。
2. **生成建议**：调用 `suggest_field_mappings`；结果仅为 `PROPOSED` 工单。
3. **展示陷阱**：若 `evidence.t0_trap_warning` 非空或 `routing_tier=block_and_escalate`，必须用中文点名计费/医嘱/出室时间风险，禁止建议 fast_track。
4. **引导宿主审校**：调用 `navigate_workspace` 跳转 `/governance/mapping-proposals`；高危项要求用户在宿主页确认。
5. **可选决策工具**：仅在用户明确要求且已在宿主可见参数时，调用 `submit_mapping_decision`；驳回必须带理由；`block_and_escalate` 的 approve 必须带 note。
6. **复查**：`list_mapping_proposals` 核对状态；相同指纹被驳回后不得反复重提。

## 异常处理表

| 异常情形 | 处置动作 |
|---|---|
| 权限不足 | 告知缺少 `governance:knowledge:read/review`，停止 |
| 画像含疑似明文标识 | 拒绝调用，要求脱敏后重试 |
| 驳回指纹抑制 | 说明已命中 maintenance-log，勿强行重提 |
