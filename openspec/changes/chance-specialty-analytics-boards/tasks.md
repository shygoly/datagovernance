# Tasks: `chance-specialty-analytics-boards`

## Phase 1 — 指标契约与查询 API

- [x] **T1.1** `BoardResponse` + fingerprint
- [x] **T1.2** `cohort-baseline` / `treatment-pathway`（按 `session_seq` Sankey 边）
- [x] **T1.3** `survival` OS MVP + ORR（标注 KM 粗粒度）

## Phase 2 — 前端三板

- [x] **T2.1** 路由 `/insight/chance` + `ChanceBoardsView.vue`
- [x] **T2.2** 漏斗 + Table1（CNLC 等）
- [x] **T2.3** Sankey 边表（session_seq 不串程）
- [x] **T2.4** KM 阶梯 + warnings

## Phase 3 — 门禁与文档

- [x] **T3.1** 依赖本机 `synthea` 100 人队列；不可达时 warnings
- [x] **T3.2** 权限 `insight:ask`
- [x] **T3.3** openspec tasks 勾选；`ARCHITECTURE.md` MappingProposal 术语已登记
