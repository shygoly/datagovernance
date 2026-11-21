# Tasks: `chance-t0-alignment-and-qc-rules`

> 对齐 [proposal.md](./proposal.md) 与 [design.md](./design.md)。

## Phase 1 — 数据模型与锚点契约

- [x] **T1.1** Flyway 迁移：锚点定义/对齐结果/质控异常表（PG RLS + workspace 隔离）。→ `V45__chance_t0_alignment_and_qc.sql`
- [x] **T1.2** Metadata：`AnchorEventSpec` 与物理列映射 API（经逻辑 URN binding_json）。→ `ClinicalAnchorController` `/anchors/specs`
- [x] **T1.3** 注册 Standard 占位 `STD_CHANCE_HCC_QC_V1`。→ `standards/STD_CHANCE_HCC_QC_V1.json` + `ensure-standard-stub`

## Phase 2 — 确定性算子

- [x] **T2.1** Runtime：`AlignToAnchorV1Operator`
- [x] **T2.2** Runtime：`SplitTreatmentCyclesV1Operator`
- [x] **T2.3** 装配层编排 + 落库命中；Governance 表 `gv_clinical_qc_finding`（本 MVP 同步写入，未引入独立 Control DAG 批）

## Phase 3 — 专病 RuleSpec 包

- [x] **T3.1** 规则包：CNLC 自洽、时序倒挂、径线单位混用 → `HccClinicalQcV1Operator`
- [x] **T3.2** `POST .../qc/rulesets/STD_CHANCE_HCC_QC_V1/run|recheck` + resolve（Blocker 需备注 C7）
- [x] **T3.3** 与 DQ Profile 并存（单位/时序可复用；专病规则独立算子）

## Phase 4 — 夹具、测试与门禁

- [x] **T4.1** `ChanceT0OperatorsTest`（多程对齐 + CNLC/时序/单位）
- [x] **T4.2** Agent/MCP：`align_treatment_anchors` / `run_hcc_clinical_qc` / `list_clinical_qc_findings`
- [x] **T4.3** `PgRlsIsolationTest` 含 V45 表；`ARCHITECTURE.md` §1 术语；单测通过
