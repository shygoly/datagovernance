# Tasks: 分析正确性硬化、临床队列与确定性统计执行层

- **Change ID**: `analysis-hardening-clinical-foundation`
- **Aligned Design**: [design.md](./design.md)

---

## Phase 0: 元语对齐与兄弟 Change 重排（文档门禁）
- [x] 更新 `ARCHITECTURE.md` §1：`SMQ`、`Catalog Grammar`、`SemanticGate`、`CompileReject`、`reproducibility_fingerprint`、`CohortSpec` / `CriterionNode`、`ClinicalOperator` / `ClinicalStatsRuntime`、`OperationalReport`
- [x] 更新 `ARCHITECTURE.md` §5.1 / §5.3 / §5.4：问数门禁统一流 + 建模证伪 + 临床/报表与 SMQ 解耦
- [x] 在 `metric-6d-ontology-verification/tasks.md` 标注：**G1–G5 先于六维运算接通编译器**（W2）
- [x] 在 `smq-constrained-compile/proposal.md`（或 tasks）标注：依赖本提案 **W1/W3/W4**；Golden 与门禁共用
- [x] 扩展 `docs/analysis-report.md` §10 与 space-contracts（metadata/governance/runtime）：指纹为跨路径必选契约
- [x] `python3 scripts/verify-primitives.py`（PASSED：Space Contracts 8/8 + 其余门禁）

## Phase A — W1 + W3 起步：统一门禁与正确性基线
- [x] 实现 `SemanticGateService` + `SemanticGateRequest`/`SemanticGatePass`/`CompileReject`；单仓 `SemanticQueryCompiler` 强制调用
- [x] `MultiWarehouseQueryCompiler` 三 Mode 入口复用同一门禁；单测三路径 error_code 一致（`SemanticGateServiceTest`）
- [x] NULL 不隐式变 0：`NullAsZeroGuard` + 编译器拦截 `COALESCE/IFNULL/NVL(...,0)`；比率用 `NULLIF` 返回 NULL
- [x] Golden v0：`evals/analysis-correctness/golden/` ≥6 条 + `AnalysisCorrectnessGoldenTest`
- [x] `mvn -pl backend/dg-metadata -am test`（SemanticGate + NullAsZero + Golden + Compiler / MultiWarehouse）

## Phase B — W2 协同验收 + W4 指纹落地
- [x] 验收 metric-6d：G1–G5 + YamlCodec/REST/反模式 + 删除影响 + STALE_REVIEW 持久化；Catalog Grammar `productionOnly` 过滤未 verified
- [x] Insight / 编译成功响应写入完整 `reproducibility_fingerprint`（`CompiledSemanticQuery` / `EngineAskResult` / `Answer` + `MultiWarehousePlan`）
- [x] 截断进入指纹；`ReproducibilityFingerprint.requireComplete` 契约测试；AnalysisReport 可选写入并校验缺字段失败（V40）
- [x] 联邦路径指纹含多仓 `datasourceSnapshots`（warehouseId/code）

## Phase C — W6：Cohort/Criterion + ClinicalStatsRuntime 最小闭环
- [x] Cohort/Criterion 领域模型 + 校验（禁止裸 SQL）+ Flyway `V41__cohort_spec.sql`
- [x] `CohortCompiler`：锚点 + 相对窗 + EXISTS/NOT_EXISTS（FIRST/SEQUENCE 降级）→ 确定性成员 SQL（只读护栏）
- [x] `ClinicalOperator` SPI + `sofa_total_v1` 样例算子 + `ClinicalStatsRuntime`
- [x] 统计输出带 `operatorDigest` + `reproducibility_fingerprint`（METRIC/SMQ 消费成员集留后续映射）
- [x] Non-goal：SOFA 不在 `SemanticQueryCompiler`；位于 `dg-runtime` ClinicalStatsRuntime

## Phase D — W5：Operational Report MVP
- [x] `OperationalReportDef` / `Version` / `Run` / `Schedule` 表（V42）+ 服务/API（publish/run/export）
- [x] 复合块编排（SMQ_BLOCK / COHORT_BLOCK / STATS_BLOCK / STATIC_MD）；禁止裸 SQL；参数绑定进快照
- [x] MD + CSV 导出（PDF 以 Markdown 快照落地；xlsx 以 CSV 兼容）；运行快照 + fingerprint
- [x] 定时分发 API + C7 `requireExportConfirm` / `exportConfirmed`（Schedule 落库；`OperationalReportScheduler` + `fireDueSchedules` 装配层消费）
- [x] 前端最小工作台（`/ops/operational-reports`，`--dg-*` Token）

## Phase E — 全量门禁
- [x] `python3 scripts/verify-primitives.py`（PASSED）
- [x] `mvn -pl backend/dg-app -am test`（68 tests, BUILD SUCCESS）
- [x] `cd frontend && pnpm type-check && pnpm build`
- [x] `cd agent-service && npx tsc --noEmit`
- [x] Golden + 门禁一致性套件：`scripts/run-analysis-correctness-goldens.sh`
