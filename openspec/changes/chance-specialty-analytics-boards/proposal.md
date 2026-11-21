# Proposal: CHANCE 专病三张分析看板（队列·治疗·生存）

- **Change ID**: `chance-specialty-analytics-boards`
- **Status**: Implemented
- **Depends On**: `chance-t0-alignment-and-qc-rules`（**硬依赖**：T₀ / `session_seq` 对齐结果为治疗与随访归因前提）、`semantic-ontology-kernel`、既有 Insight Dashboard / Ask / `FilterFunnelCard`
- **Coordinates With**: `chance-ai-mapping-inbox`（弱依赖：映射质量↑则字段填充率可信）、`analysis-hardening-clinical-foundation`（若 KM 升级为统计算子层则对接，本 MVP 可先确定性预聚合）
- **Constitution Alignment**: 严格遵循 `CONSTITUTION.md`（尤其 **C3/C5/C8**）

---

## Why

本体与质控具备后，临床仍缺**固定专病 BI 面**，仅靠自由问数无法稳定交付：

1. **队列与基线画像**：纳排漏斗 + Table 1（CNLC/Child-Pugh/病因分布）；
2. **治疗路径**：单纯介入 vs 靶免联合的流转（Sankey）与方案占比；
3. **随访与生存**：依从性、ORR 粗算、OS/PFS 曲线（科研常用）。

平台已有 Insight 看板、Ask、`FilterFunnelCard` 与 Synthea 专病表，但无上述三张**产品化看板**与确定性指标契约。

---

## What Changes

### 1. 专病指标与数据集契约（语义层）
- 以语义对象 / 受控视图定义看板指标（纳排步、分期分布、方案标签、OS/PFS 风险集等）；
- 治疗归因**必须**消费 T₀ 对齐输出的 `session_seq`（禁止看板 SQL 私自按「最近一次」瞎猜）。

### 2. 三张看板 MVP
| 看板 | 核心视图 | 数据要点 |
|---|---|---|
| **队列与基线** | 纳排漏斗 + Table 1 分布 | CNLC、Child-Pugh、HBV/病因；复用/扩展 Funnel |
| **治疗路径** | Sankey + 方案占比 | TACE/HAIC vs 靶免联合；按程次 |
| **随访与生存** | 依从性趋势 + KM（OS/PFS） | \(T_0\)、随访、死亡/失访；截断与删失明示 |

### 3. 确定性与合规
- 聚合与生存估计在服务端确定性执行（C3）；LLM 不得心算 p 值或生存率；
- 行级明细默认不进看板；敏感列 MASK；结果带 `reproducibilityFingerprint`（C8）；
- 前端 ECharts + `--dg-*`；可嵌入 Insight Dashboard 或独立 `/insight/chance/*` 路由。

---

## Non-goals

- **不做** 并发症/irAE 热力、治理填充率大盘（可后续 change）。
- **不在本 change 实现** T₀ 算子或 AI 映射收件箱。
- **不把** 完整生物统计套件（竞风险、PSM、多变量回归）塞进看板；复杂统计走 analysis-hardening 统计执行层。
- **不镜像** MIMIC 已有通用表来「凑」指标。

---

## Acceptance Scenarios

### Scenario 1：漏斗与 Table 1
- **GIVEN** `synthea:` 100 人队列已对齐；
- **WHEN** 打开队列看板；
- **THEN** 漏斗步可复现；CNLC 分布与库内 `hcc_diagnosis` 聚合一致（允许展示舍入说明）。

### Scenario 2：Sankey 不串程
- **GIVEN** 多程 TACE 患者；
- **WHEN** 渲染治疗路径；
- **THEN** 边与节点按 `session_seq` 归因；与未切分「拍脑袋最近一次」结果可对比差异测试。

### Scenario 3：KM 与指纹
- **GIVEN** 含死亡/删失样本；
- **WHEN** 请求 OS/PFS；
- **THEN** 返回阶梯数据 + 风险集人数；绑定指纹；明细 L3 不外泄。

---

## Impact

| 维度 | 影响 |
|---|---|
| **后端** | Insight/Metadata：专病看板查询 API 或受控语义指标；可选预聚合表 |
| **前端** | 三页/三 Tab + Sankey/KM 图组件（白名单若经 Agent 推送则走 ui_block） |
| **评测** | 离线聚合金标；可选 agent 回归「只读看板摘要」 |

---

## Constitution Check

| 公理 | 结论 |
|---|---|
| **C3** | ✅ 指标与 KM 服务端确定性计算 |
| **C5** | ✅ 聚合优先；敏感脱敏；模型路由不变 |
| **C7** | ✅ 看板只读；无高危写 |
| **C8** | ✅ 截断/删失明示 + 指纹 |
| **C1** | ✅ 查询经语义/网关；Governance 不发 Command |
