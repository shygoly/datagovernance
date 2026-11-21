# Tasks: `chance-ai-mapping-inbox`

## Phase 1 — 契约与存储

- [x] **T1.1** Flyway `V46__chance_ai_mapping_inbox.sql` + `gv_mapping_proposal` RLS
- [x] **T1.2** `POST /api/v1/governance/mapping-proposals/suggest` + 列表 API
- [x] **T1.3** `approve` / `reject`（驳回指纹防重提 + maintenance-log）

## Phase 2 — Agent / Skill

- [x] **T2.1** 启发式/LLM JSON 收录服务 + Agent 内部端点
- [x] **T2.2** Skill `skills/chance-ai-mapping/SKILL.md` + 工具 `suggest_field_mappings` / `list_mapping_proposals` / `submit_mapping_decision`
- [x] **T2.3** evals `reg-20-chance-mapping-skill-gate` + MCP 契约登记

## Phase 3 — 收件箱 UI

- [x] **T3.1** 前端 `api/mappingProposal.ts`
- [x] **T3.2** `/governance/mapping-proposals` 复核页 + 治理收件箱入口
- [x] **T3.3** T₀ 陷阱高亮 `t0_trap_warning`

## Phase 4 — 门禁

- [x] **T4.1** `MappingProposalServiceTest`
- [x] **T4.2** RLS 纳入 `gv_mapping_proposal`
