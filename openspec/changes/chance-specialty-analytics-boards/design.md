# Design: `chance-specialty-analytics-boards`

## 1. 依赖边界

```text
chance-t0-alignment-and-qc-rules
        │  AlignmentHit + session_seq
        ▼
专病语义指标 / 受控查询
        │
        ├─ Board A 队列基线（Funnel + Table1）
        ├─ Board B 治疗路径（Sankey）
        └─ Board C 随访生存（依从性 + KM）
```

若 T₀ change 未完成，本 change **不得**用「按患者取 max(t0)」临时逻辑上线生产路径（开发期可用 feature flag + 显式 `UNALIGNED` 警告）。

## 2. API 草图

- `GET /api/v1/insight/chance/cohort-baseline?workspace…`
- `GET /api/v1/insight/chance/treatment-pathway?…`
- `GET /api/v1/insight/chance/survival?endpoint=OS|PFS&…`

统一响应：`data` + `reproducibilityFingerprint` + `warnings[]`（截断、未对齐人数）。

## 3. KM MVP 算法位置

- **首选**：服务端小型确定性生存模块（生命周期表法），输入仅 `(time, event, strata?)` 聚合允许的风险集；
- **备选**：若 `analysis-hardening` 统计算子已就绪，则看板只做适配器；
- **禁止**：浏览器端用全量明细自算后当作正式结果。

## 4. 前端组成

| 组件 | 用途 |
|---|---|
| 复用 `FilterFunnelCard` | 纳排 |
| 新 `TreatmentSankeyCard` | 治疗路径（可先看板内嵌，不必立刻进 Agent 白名单） |
| 新 `SurvivalKmCard` | OS/PFS |
| Table1 简易表 | Element Plus + tokens |

路由建议：`/insight/chance/overview` 三 Tab，或三独立路由。

## 5. 数据源

- 主验证仓：`synthea:` / `synthea_hcc.*`；
- 映射到语义后可切换真实多源；看板代码不写死 schema 名。
