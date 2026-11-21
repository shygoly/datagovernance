# Tasks: 度量六维正交本体、对象图五道闸门与四层证伪闭环

- **Change ID**: `metric-6d-ontology-verification`
- **Aligned Design**: [design.md](./design.md)

---

## Phase 0: 元语与契约对齐（已完成）
- [x] 更新 `ARCHITECTURE.md` §1 架构元语与 §5.3 受控语义建模与证伪流
- [x] 更新 `docs/space-contracts/metadata.yaml`（`SemanticVerificationRecord`、`STALE_REVIEW`、导入/导出/验证/选 ID 契约与核心不变量）
- [x] 更新 `contracts/mcp/datagov-mcp.json`（新增 `list_dimensions`、`list_metrics`、`resolve_ref`）
- [x] 创建 OpenSpec 提案与设计文档（`proposal.md` / `design.md` / `tasks.md`）

## Phase 1: 数据模型、六维契约与图上五道硬闸门（G1–G5 + 删除保护）【W2 硬前置：本 Phase 完成前禁止 Phase 3 接通六维运算】
- [x] 新增 Flyway 迁移 `V39__metric_6d_verification_gates.sql`（扩展 `md_semantic_object`、`md_semantic_link`，新增 `md_semantic_verification_record` 及 PG RLS 策略）
- [x] 在 `dg-metadata` 定义六维正交 `Metric6DSpec` 与 G1 校验规则；完整 `FieldProvenance` DTO + ProvenancePolicy 嵌套抽取
- [x] 实现 `SemanticYamlCodec`：短名解析 + 显式 `ref` 块（`UNRESOLVED_REF` / `AMBIGUOUS_REF` 模糊候选）、双模式导出（`short` / `full`）
- [x] 实现 `SemanticGraphGateService`：G1 Schema、G2 引用完整性、G3 类型匹配、G4 DAG 无环检测、G5 `content_digest` 变更传播自动标 `STALE_REVIEW`、以及删除保护（含引用方列表）
- [x] 单元测试覆盖 G1–G5 阻断、删除保护、环检测、`STALE_REVIEW` + YAML 双模式往返
- [x] 将 GraphGate 接入 REST：`yaml/import|export`、`objects/{id}/verify`、`propagate-stale`、`delete-impact`、`resolve-ref`（STALE_REVIEW 可持久化）

## Phase 2: LLM 权责治理、反模式检测（③d）与 MCP 接地选 ID
- [x] 实现 Provenance 强制策略：`llm_inference` 自动置 `requires_human_review = true`；安全/租户/RLS 字段硬拦截 LLM 写入（`ProvenancePolicy`）
- [x] 实现 `SemanticAntiPatternLinter`（AP001–AP006 规则库）并接入 YAML 导入预览与 `objects/{id}/verify`
- [x] 在 `dg-app` 暴露 YAML 导入/导出、删除影响、verify、resolve-ref；MCP 契约已列 `list_*`/`resolve_ref`/`compile_and_validate`（agent 工具透传可继续增强）
- [x] 补齐 `agent-service` 侧 `list_metrics`/`list_dimensions`/`resolve_ref`（经 catalog_grammar / resolve_ref）

## Phase 3: 统计证伪（③b）与探针引擎（③c + 常驻 CI）+ 六维运算接通【仅当 Phase 1 G1–G5 已验收】
- [x] 实现 `MetricStatisticalFalsifier` + `MetricVerificationExecutor`：经 DataAccessGateway 实跑，回写 `verified_json` / `LOW_CONFIDENCE`（REST `/falsify`、`/plan-probes`）
- [x] 多事实 `IS NOT DISTINCT FROM` 缝合已在 SMQ；LOD FIXED/INCLUDE/EXCLUDE grain 改写 + 多方言半加性末态取值
- [x] 实现 `MetricProbeRunner`：自动生成 6 类探针 SMQ；`planProbes`/`verifyObject` 回写 `verified_json`；离线门禁 `scripts/run-metric-6d-gates.sh`
- [x] 编写常驻 CI 脚本（校验 YAML 资产库 G1–G5 + ③d 反模式 + 探针套件）：`scripts/run-metric-6d-gates.sh`

## Phase 4: 前端审批工作台与全链路验收
- [x] 前端语义本体工作台新增：六维 additivity/LOD 表单、YAML 双模式导入/导出、resolve_ref、METRIC 验证、Provenance/STALE/verified 徽标
- [x] 运行全量防漂移与测试门禁：`python3 scripts/verify-primitives.py`、`mvn -pl backend/dg-app -am test`、`cd frontend && pnpm type-check && pnpm build`、`cd agent-service && npx tsc --noEmit`；`scripts/run-metric-6d-gates.sh` + 可选 PG falsifier 夹具
