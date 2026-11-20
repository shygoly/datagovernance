# 治理元语与受控知识沉淀总览

- 生效日期：2026-11-19
- 置信度：1.0（人工核验）
- 来源：`CONSTITUTION.md`、`SPACE-MODEL.md`、`GOVERNANCE-MODEL.md`、`AGENT-PRIMITIVES.md`

## 核心结论

1. **三轴融合拓扑**：平台由 `8 Space`（过程结构边界）、`7 Governance Primitives`（`Asset / Fact / Standard / Rule / Policy / Lineage / Event`）与横切共享的 `A0`（分层记忆、渐进式技能、受控自演进、轨迹评测）共同构成，不增设第 9 个业务 Space。
2. **防投毒与双时间态**：所有运行时沉淀的 `Fact` 先入 `PROPOSED` 暂存区，必须绑定外部反馈信号与证据引用；人工审校晋升 `APPROVED` 时，旧版本保留历史并写 `valid_to` 转 `SUPERSEDED`，支持 `asOf` 时间旅行回溯与 `ROLLED_BACK` 一键撤销。
3. **纵深隔离**：多租户隔离由应用层 `WorkspaceContext`、检索前权限过滤与 PostgreSQL RLS（`V35` 试点 8 张核心表）三层共同兜底。
