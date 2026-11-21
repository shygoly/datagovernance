# Tasks: SMQ 约束编译内核与类型化修复闭环

- **Change ID**: `smq-constrained-compile`
- **Aligned Design**: [design.md](./design.md)

---

## Phase 0: 元语与契约对齐
- [x] 更新 `ARCHITECTURE.md` §1（SMQ / CompileReject / Catalog Grammar / SemanticGate 等）与 §5.1 问数主链路（由 `analysis-hardening-clinical-foundation` Phase 0 一并固化）
- [x] 更新 `docs/space-contracts/metadata.yaml`：补充 SMQ / CompileReject / CatalogGrammar / SemanticGate 契约要点与不变量
- [x] 更新 `contracts/mcp/datagov-mcp.json`：增强 `list_metrics` / `list_dimensions` 输出约束字段；新增 `compile_and_validate` / `get_catalog_grammar`
- [x] 与 `analysis-hardening-clinical-foundation` 对齐：**W1** 共享 `SemanticGate`、**W3/W4** Golden 与指纹字段；本 OpenSpec 提案包已创建

## Phase 1: SMQ IR v1 + 类型化 CompileReject
- [x] 扩展 `SemanticQuerySpec`（`grain`、`atModifiers`；兼容既有 7 参构造）与 Gate 透传 grain
- [x] 引入稳定 `SemanticCompileErrorCode` 枚举与 `CompileReject` 响应模型（含 `location` / `allowed` / `repair_hint`）
- [x] 关键失败路径经 `CompileRejectException`（门禁/ACL/NULL 填零）；其余仍可 BizException 兜底
- [x] 单元测试：Gate / Compiler / Golden（字节级一致夹具可后续加强）

## Phase 2: Catalog Grammar + compile_and_validate 工具面
- [x] 实现 `SemanticCatalogGrammarService`（PUBLISHED + ACL 裁剪 + allowedDimensions/grains；`productionOnly` 过滤未 verified）
- [x] `dg-app` internal：`POST .../semantic/compile_and_validate`（200 + `{ok}`）；`GET .../catalog_grammar`；`agent-service` 注册同名工具
- [x] 增强 MCP `list_metrics` / `list_dimensions` 约束字段；新增 `compile_and_validate` / `get_catalog_grammar`
- [x] 更新 `skills/insight-data-ask`：目录 → SMQ → validate → 局部修复（≤3）→ execute

## Phase 3: 编译红线升级（缝合 / 歧义路径 / 6D 对齐）
- [x] 多事实路径：预聚合 CTE + `FULL OUTER JOIN` + `IS NOT DISTINCT FROM`（MySQL `<=>`）
- [x] 多路径歧义 → `MULTI_FACT_AMBIGUOUS_PATH`；`USING` at_modifier 消歧
- [x] 半加性 / LOD：LOD FIXED/INCLUDE/EXCLUDE grain 改写；`window_groupings` 多方言末态取值（PG ARRAY_AGG / StarRocks max_by / MySQL GROUP_CONCAT）
- [x] 安全：`at_modifiers` 不可关闭 EXCLUDES/RLS（`SECURITY_FIELD_FORBIDDEN`）；仅允许 USING / SET_GRAIN

## Phase 4: DuckDB Golden SMQ 与全链路门禁
- [x] 落地 ≥12 条 Golden（g01–g12；含 `sql_row` 行级比对，H2 默认 / `DG_GOLDEN_JDBC_URL` 可指向 PG）
- [x] 与 metric-6d 定义态探针套件文档交叉引用（互不替代；`MetricProbeRunner` / Golden 分列）
- [x] 前端：`compile-and-validate` + Ask/Playground 展示 CompileReject `error_code` / `repair_hint`；agent 工具失败路径透传 `compileReject`
- [x] 运行门禁：`verify-primitives`、`mvn -pl backend/dg-app -am test`、`pnpm type-check && pnpm build`、`agent tsc`、`scripts/run-analysis-correctness-goldens.sh`
