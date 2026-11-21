# Design: `chance-ai-mapping-inbox`

## 1. 流程

```text
源画像（脱敏） + 业务本体 YAML
        │
        ▼
Agent/LLM → MappingSuggestion[]（严格 Schema）
        │
        ▼
Governance 映射工单 PROPOSED ──► 收件箱
        │
   confirm / amend / reject
        │
        ├─ confirm → 写入语义映射（Metadata）
        └─ reject  → 指纹 + 理由 → maintenance-log / REJECTED
```

## 2. routing_tier 策略

| 条件 | tier |
|---|---|
| conf≥0.90 且非核心终点且非 L4/L5 | `fast_track_review`（仍需人点确认，可批量） |
| 0.75≤conf&lt;0.90 或核心终点或 L4/L5 | `standard_hitl_review` |
| conf&lt;0.75 或类型/值域冲突或 T₀ 陷阱 | `block_and_escalate` |

## 3. 证据四维（最低集）

- `naming_signal`：命名/同义词
- `value_domain_check`：Compatible / UnitMismatch / TypeConflict / Insufficient
- `t0_trap_warning`：计费/医嘱/出室时间陷阱说明（可空）
- `maintenance_log_hit`：历史驳回命中（可空）

## 4. Space 与安全

- 建议生成可在 Agent sidecar；**落库与生效**必须经 Java API + 权限 `governance:*` / `metadata:semantic:*`。
- 画像接口禁止返回行样例中的直接标识符；枚举值 k-匿名。
- 与 `EvolutionProposalService` 驳回防重提模式对齐（可复用指纹工具，不必复用实体）。

## 5. 前端

- 扩展治理收件箱：映射建议卡片（候选 URN、证据、一键采纳/改写/驳回）。
- 严守 `--dg-*`；确认不用 `ElMessageBox.confirm` 替代 `AgentConsentDialog`（高危路径）。
