---
name: lineage-impact-attribution
description: 基于字段级血缘做数据溯源与变更影响评估：追溯某字段的上游来源/下游去向，评估修改字段或数据集会影响哪些下游表、指标、分析报告与质检规则。当用户询问“这个字段从哪来”“改了它会影响谁”“哪些指标/报告依赖这张表”“血缘”“影响面”时触发。
allowed-tools:
  - lineage_trace_column
  - lineage_impact_analysis
  - list_datasets
  - navigate_workspace
---

# 字段级血缘溯源与影响评估规程

> 所属元语：`A0.2`（只读技能包，不含任何写工具）。
> 安全红线：`CONSTITUTION.md` C4（按发起人 `userId + workspaceId` 实时鉴权）、C5（Hop-by-Hop 逐跳 ACL）、C8（证据不足返回 `INCONCLUSIVE`）。

## 标准执行步骤

1. **定位目标**
   - 用户给出的是字段/数据集名称而非 ID 时，先用 `list_datasets` 确认数据集，再向用户确认字段；禁止凭记忆编造 ID。
2. **溯源（“从哪来 / 流向哪”）**
   - 调用 `lineage_trace_column`，`direction` 取 `upstream` / `downstream` / `both`；默认只看执行确认的 `ACTIVE` 边，用户明确要看“预估/未运行”血缘时才设 `includePlanned=true`，并在回答里标注“预估”。
   - 按 `level`（跳数）分层叙述，引用边上的 `transformExpr`、`jobName`、`executionId` 作为证据；`maskingApplied=true` 的边要说明敏感标签传播已被脱敏阻断。
3. **影响评估（“改了会影响谁”）**
   - 调用 `lineage_impact_analysis`：字段级传 `columnId`，表级传 `datasetId`；默认 `includeGovernance=true`，结果里的 `summary` 给出下游字段/表数量及指标、报告、质检规则数量，`items` 逐项列出（含 `level`）。
   - 先给核心结论（影响了多少下游、是否触及指标/报告/质检规则），再列出最关键的几项；不要把整份 `items` 原样倾倒。
4. **证据不足与受限（必须遵守）**
   - 返回 `status=INCONCLUSIVE` 时，如实转述 `reason` 与 `message`：
     - `NO_DOWNSTREAM_EVIDENCE` / `NO_LINEAGE_EVIDENCE`：未采集到血缘，**不等于没有依赖/影响**，严禁回答“没有影响”；
     - `ONLY_PLANNED_EVIDENCE`：只有编译期预估，尚无执行确认；
     - `NODE_NOT_FOUND`：该字段尚未建立血缘节点；
     - `ROOT_RESTRICTED`：当前用户无权查看该资产，不要尝试换入口绕过。
   - 引导用户前往 `/metadata/lineage` 补录血缘或导入 SQL，而不是猜测来源。
   - `truncatedByAcl=true` 时必须告知“有 N 个受限分支已按最小权限截断，影响面可能不完整”；`[受限资产]` 占位不得被推测为任何具体表名。
5. **只读边界**
   - 本技能不修改血缘、不触发采集；需要变更时引导用户在宿主界面操作。
