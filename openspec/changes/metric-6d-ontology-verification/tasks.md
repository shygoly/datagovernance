# Tasks: 度量六维正交本体、对象图五道闸门与四层证伪闭环

- **Change ID**: `metric-6d-ontology-verification`
- **Aligned Design**: [design.md](./design.md)

---

## Phase 0: 元语与契约对齐（已完成）
- [x] 更新 `ARCHITECTURE.md` §1 架构元语与 §5.3 受控语义建模与证伪流
- [x] 更新 `docs/space-contracts/metadata.yaml`（`SemanticVerificationRecord`、`STALE_REVIEW`、导入/导出/验证/选 ID 契约与核心不变量）
- [x] 更新 `contracts/mcp/datagov-mcp.json`（新增 `list_dimensions`、`list_metrics`、`resolve_ref`）
- [x] 创建 OpenSpec 提案与设计文档（`proposal.md` / `design.md` / `tasks.md`）

## Phase 1: 数据模型、六维契约与图上五道硬闸门（G1–G5 + 删除保护）
- [ ] 新增 Flyway 迁移 `V39__metric_6d_verification_gates.sql`（扩展 `md_semantic_object`、`md_semantic_link`，新增 `md_semantic_verification_record` 及 PG RLS 策略）
- [ ] 在 `dg-metadata` 定义六维正交 `Metric6DSpec`、`FieldProvenance`、`VerificationSummary` 领域模型与校验规则
- [ ] 实现 `SemanticYamlCodec`：短名解析 + 显式 `ref` 块（`UNRESOLVED_REF` / `AMBIGUOUS_REF` 模糊候选）、双模式导出（`short` / `full`）
- [ ] 实现 `SemanticGraphGateService`：G1 Schema、G2 引用完整性、G3 类型匹配、G4 DAG 无环检测、G5 `content_digest` 变更传播自动标 `STALE_REVIEW`、以及删除保护（含引用方列表）
- [ ] 单元测试覆盖 G1–G5 阻断、删除保护、环检测、`STALE_REVIEW` 传播与双模式 YAML 往返一致性

## Phase 2: LLM 权责治理、反模式检测（③d）与 MCP 接地选 ID
- [ ] 实现 Provenance 强制策略：`llm_inference` 自动置 `requires_human_review = true`；安全/租户/RLS 字段硬拦截 LLM 写入
- [ ] 实现 `SemanticAntiPatternLinter`（AP001–AP006 规则库）并接入导入与发布闸门
- [ ] 在 `dg-app` 暴露 YAML 导入/导出、删除影响查询接口与 MCP 工具 `list_dimensions` / `list_metrics` / `resolve_ref`（严格 `UserContext + workspaceId` 鉴权）
- [ ] 补齐 `agent-service` 侧只读工具透传与单测

## Phase 3: 统计证伪（③b）与 DuckDB 6 类探针引擎（③c + 常驻 CI）
- [ ] 实现 `MetricStatisticalFalsifier`：生成 PK 唯一性、1:N NDV 比、FK 匹配率只读 SQL，经 `DataAccessGateway` 执行；任一 FAIL 降级字段为 `LOW_CONFIDENCE`
- [ ] 扩展 `SemanticQueryCompiler` / `MultiWarehouseQueryCompiler` 支持半加性 `window_groupings`、LOD 修饰符与多事实 `IS NOT DISTINCT FROM` 缝合
- [ ] 实现 `MetricProbeRunner`：自动生成 6 类探针 SMQ（扇出 / 可加性 / 半加性 / NULL 维度 / 除零 / 量级）并在嵌入式 DuckDB 执行断言，结果写入 `md_semantic_verification_record` 与 `verified_json`
- [ ] 编写常驻 CI 脚本（校验 YAML 资产库 G1–G5 + ③d 反模式 + DuckDB 6 类探针套件），并接入回归验证

## Phase 4: 前端审批工作台与全链路验收
- [ ] 前端语义本体工作台新增：六维度量配置表单、YAML 双模式导入/导出弹窗、Provenance 来源徽标与 `STALE_REVIEW` / `LOW_CONFIDENCE` 复核队列、四层验证报告面板（严守 `--dg-*` Token 与 C7 独立确认）
- [ ] 运行全量防漂移与测试门禁：`python3 scripts/verify-primitives.py`、`mvn -pl backend/dg-app -am test`、`cd frontend && pnpm type-check && pnpm build`、`cd agent-service && npx tsc --noEmit`
