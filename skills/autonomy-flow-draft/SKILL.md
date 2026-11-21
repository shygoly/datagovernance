---
name: autonomy-flow-draft
description: 数据自治 FlowSpec IR 草案与决策归因：当用户想“把某个异常自动处理”“配置触发器/自动暂停/自动质检”“解释系统为什么自动执行了这个动作”“查看决策轨迹/影响回检”时触发。Agent 只能生成受 JSON Schema 约束的草案并调用 FlowCompiler 静态校验，不得直接启动流程或修改授权等级。
allowed-tools:
  - autonomy_compile_flow
  - autonomy_trace_explain
  - list_datasets
  - get_semantic_object
  - navigate_workspace
---

# 数据自治 FlowSpec 草案与决策归因规程

> 所属元语：`A0.2`（只读编译/归因技能，不含任何启动或审批工具）。
> 安全红线：`CONSTITUTION.md` C3（确定性优先）、C6（外部写回默认关闭）、C7（宿主高危确认）、C9（灰度晋升不可跳过）。

## 1. 起草 FlowSpec IR（`flow.ir/v1`）

1. 先确认用户要守的 **Target** 与要执行的动作 **ActionType**（可用 `get_semantic_object` 查已发布对象）。
2. 按以下骨架生成草案，**只允许**这些 step kind：
   - `CAPTURE_CONTEXT`（至少一个 metric）
   - `CHECK_INVARIANT`（必须在动作之前）
   - `DRY_RUN_ACTION`
   - `ARBITER_DECIDE`
   - `WAIT_HUMAN_SIGNAL`
   - `INVOKE_ACTIVITY`
   - `DELAYED_VERIFY`
3. `runMode` 一律先写 `SHADOW`；`entityKeyExpr` 用 `trigger.datasetId` / `trigger.jobId` / `trigger.executionId` 等真实字段；`cooldownSec` 必须 > 0。
4. 动作步骤必须声明 `compensateWith`（配对补偿动作）；L2/L3 还必须带参数包络。
5. 调用 `autonomy_compile_flow` 校验草案，逐条解释诊断并修正。**禁止**声称“已部署/已生效”。

## 2. 解释决策轨迹

1. 用户给出 traceId 时调用 `autonomy_trace_explain`；没有 traceId 时引导用户到 `/autonomy/traces`。
2. 按 `触发快照 → Arbiter 裁决 → Dry-Run → 宿主审批 → 执行/补偿 → T+1 回检` 顺序叙述，引用具体字段作为证据。
3. `verifyStatus=INCONCLUSIVE` 时必须明确说“证据不足”，不得推断为改善或无影响；`DEGRADED` 要说明哪项指标恶化。
4. 归因只是建议：不得直接建议把 `SHADOW` 改成 `L1_AUTO`，只能提示用户走受控晋升与审批。

## 3. 明确不做

- 不创建/发布流程、不启停触发器、不确认审批、不调用任何写接口；
- 不生成任意脚本/代码，不绕过 `FlowCompiler`；
- 不把 L3/L4 原始数据写入草案或解释文本（C5）。
