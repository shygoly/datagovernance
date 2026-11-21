# Proposal: T₀ 事件对齐、多程治疗切分与肝癌确定性质控规则包

- **Change ID**: `chance-t0-alignment-and-qc-rules`
- **Status**: Implemented（核心算子/API/MCP/RLS/单测已落地；Control 独立 DAG 批为后续增强）
- **Depends On**: `governed-agent-evolution-mvp`（DQ Profile / 演进闭环）、`semantic-ontology-kernel`、`multisource-semantic-federation`
- **Coordinates With**: `chance-ai-mapping-inbox`（并行）、`chance-specialty-analytics-boards`（本 change 为看板前置）、`analysis-hardening-clinical-foundation`（Cohort/统计算子边界）
- **Constitution Alignment**: 严格遵循 `CONSTITUTION.md`（尤其 **C2/C3/C8/C9**）

---

## Why

CHANCE 肝癌专病跨 HIS/EMR/PACS/LIS/随访多源后，治理与分析仍缺两类**确定性**能力：

1. **缺少以「实际治疗完成时间」为锚点的事件对齐**：基线影像/肝功能与术后随访常被挂错程次；多程 TACE/HAIC 之间的不良反应与复查易错误归因到首次治疗。
2. **缺少可编译的肝癌专病质控规则包**：CNLC/BCLC（及可选 TNM）与肿瘤负荷、门脉癌栓、肝外转移、Child-Pugh 之间的推导冲突，以及随访/死亡时序倒挂、肿瘤径线 mm/cm 混用等，尚不能作为 `RuleSpec` → Control 编译 → Runtime 隔离执行的标准流水线，只能靠 ad-hoc SQL 或 LLM 心算（违反 C3）。

合成仓 `synthea:`（`tace_session.actual_exec_end_t0` 等）已能提供可复现夹具，但平台内核尚无通用「锚点事件 + 窗口匹配 + 多程切分」原语与可插拔专病规则包。

---

## What Changes

### 1. T₀ 锚点事件模型与窗口对齐算子
- 引入领域无关的 **AnchorEvent** 契约（`anchor_type`、`patient_ref`、`anchor_ts`、`session_seq`、可选 `modality`），由语义映射绑定到物理列（如 `actual_exec_end_t0`）；**禁止**把 CHANCE 表名硬编码进 `dg-*`。
- 确定性算子 **AlignToAnchor**：按配置窗口匹配基线（默认 \(T_0-14/-30\) 天）与随访（默认 \(T_0+1/3/6\) 月）候选事件，输出结构化对齐结果 + 未匹配/歧义原因码。
- 确定性算子 **SplitTreatmentCycles**：按同一患者多锚点时间序切分程次，强制下游归因带 `session_seq`；跨程错挂标记为质控异常（非静默修正）。

### 2. 肝癌专病 RuleSpec 包（可插拔 Standard）
- 以 `Standard` / 规则包形式交付（种子或 `skills/` 挂载），至少覆盖：
  - **分期自洽**：CNLC（及可选 BCLC）与肿瘤数目/最大径/PVTT/肝外转移/Child-Pugh 的推导冲突；
  - **时序倒挂**：随访日 &lt; \(T_0\)、死亡日 &lt; 末次出院/末次已知存活日；
  - **单位混用**：肿瘤径线 cm/mm、胆红素 µmol/L vs mg/dL 等（复用并扩展 DQ Profile 维度，结果可回写质控工单）。
- 规则经 Control 编译为确定性模板，Runtime 隔离执行；LLM **仅**可起草 `PROPOSED` RuleSpec，不得直出可执行 SQL（C2/C3/C9）。

### 3. 异常标记 → 人工修正 → 自动复检
- 对齐/规则命中写入可审计异常记录（含 `rule_id`、`anchor_ref`、`session_seq`、证据摘要、指纹）。
- 宿主确认修正后触发**同一规则集**复检；通过则关闭，失败则保持开放（C7/C8）。

---

## Non-goals（明确非目标）

- **不把 CHANCE / MIMIC Schema 写死进平台内核**：专病口径仅以 `Standard`、语义映射、Skill、种子数据承载。
- **不实现 Kaplan-Meier / Sankey / 专病 BI 看板**：归属 `chance-specialty-analytics-boards`。
- **不做 AI 字段→本体映射收件箱**：归属 `chance-ai-mapping-inbox`。
- **不引入 Temporal / 独立规则引擎进程**：复用既有 Control/Runtime 与质量剖析扩展。
- **不开放外部业务库写回**（C6）。

---

## Acceptance Scenarios

### Scenario 1：多程 TACE 归因切分
- **GIVEN** 患者存在 2 次 `actual_exec_end_t0` 相隔 ≥40 天，且各有一次 \(T_0+4\)–\(8\) 周复查；
- **WHEN** 运行 `SplitTreatmentCycles` + `AlignToAnchor`；
- **THEN** 各复查分别归属对应 `session_seq`，不得串程；审计含算子摘要指纹。

### Scenario 2：CNLC 与 PVTT 冲突拦截
- **GIVEN** 结构化 `pvtt=Vp3` 且无肝外转移，但 `cnlc_stage=IIb`；
- **WHEN** 执行专病 RuleSpec 包；
- **THEN** 产生 Blocker 级异常，指出应为 IIIa 的推导证据；人工改分期后复检通过。

### Scenario 3：单位混用与时序倒挂
- **GIVEN** 合成脏数据夹具含 mm 误作 cm、随访日早于 \(T_0\)；
- **WHEN** 跑质控批；
- **THEN** 两类缺陷均被标记且进入复检闭环；样本不足时返回 `INCONCLUSIVE`（C8）。

---

## Impact

| 维度 | 影响说明 |
|---|---|
| **后端** | Metadata：锚点映射与结果实体；Control：RuleSpec 编译；Runtime：对齐/切分/质控执行；Governance：异常与复检审计；Flyway 新表（RLS） |
| **API** | `POST .../anchors/align`、`POST .../cycles/split`、`POST .../qc/rulesets/{id}/run`、异常列表与复检 |
| **Agent/MCP** | 只读/受控触发工具（实时 `userId+workspaceId`）；禁止聊天流内直接关闭 Blocker |
| **夹具** | 扩展 `synthea_hcc` Golden / 单元测试；可选对接 D3/D4 脏数据配额 |
| **前端** | 质控异常列表与复检确认（复用 Consent/收件箱模式）；无新 BI 大屏 |

---

## Constitution Check

| 公理 | 结论 |
|---|---|
| **C1** | ✅ 定义/编译/执行/审计分属 Metadata/Control/Runtime/Governance；Governance 不发 Command |
| **C2** | ✅ 专病规则以 Standard/RuleSpec 为编译输入 |
| **C3** | ✅ 对齐、切分、分期校验均为确定性算子 |
| **C4** | ✅ 工具实时鉴权，最小 allowed-tools |
| **C5** | ✅ 仅聚合/结构化证据；L3 明细不外送 |
| **C6** | ✅ 无外部写回；修正写平台内质控状态 |
| **C7** | ✅ 关闭 Blocker / 改分期须宿主确认 |
| **C8** | ✅ 证据不足 → INCONCLUSIVE；结果绑指纹 |
| **C9** | ✅ 新规则先 PROPOSED，审校后晋升 |
| **C10** | ✅ 算子与 LLM 起草解耦；Golden + `verify-primitives` |
