# Tasks: multisource-semantic-federation

## Stage 1: Database Migration & PG RLS
- [x] 新增 `V38__multisource_semantic_federation.sql`（`scope`、`md_workspace_warehouse`、`md_logical_table`、`md_semantic_mapping` 增强列及 PG RLS）
- [x] 扩展 `PgRlsIsolationTest` 覆盖新表租户隔离与 `GLOBAL` 只读共享/写保护

## Stage 2: Data Space Federation SPI & Dual Engine
- [x] `dg-data-spi` 新增 `FederatedQueryGateway` 纯接口与值对象
- [x] `dg-data-connectors` 实现 `DefaultFederatedQueryGateway`（DuckDB 内存安全联结 + StarRocks External Catalog 下推 + `ReadOnlySqlGuard`）
- [x] 新增 `DefaultFederatedQueryGatewayTest` 真 DuckDB 跨仓 Join 与只读护栏单测

## Stage 3: Metadata Space Multi-Warehouse Service & Compiler
- [x] 扩展 `SemanticEntities`、`WorkspaceWarehouseMapper`、`LogicalTableMapper`
- [x] 扩展 `SemanticOntologyService` & 新增 `SemanticWarehouseService`（GLOBAL+WORKSPACE 视图、Warehouse/LogicalTable CRUD、跨仓映射矩阵）
- [x] 新增 `MultiWarehouseQueryCompiler`（Mode A/B/C、值归一化、两阶段聚合、Logical Size 选表）
- [x] 新增 `MultiWarehouseCompilerTest` 全覆盖单元测试

## Stage 4: Assembly Layer Execution & REST API
- [x] 扩展 `SemanticOntologyController`（Warehouses / LogicalTables / Alias / Mapping Matrix / Compile Plan）
- [x] 扩展 `InsightQueryEngine`、`NativeInsightEngine`、`InsightQueryService`（COHORT_UNION 两阶段合并 + StarRocks/DuckDB 联邦执行）
- [x] 新增 `NativeInsightMultiWarehouseTest` 端到端单测

## Stage 5: Frontend Workbench & Q&A UI
- [x] 扩展 `frontend/src/types/semantic.ts`、`api/semantic.ts`、`types/insight.ts`
- [x] 新增 `WarehouseCatalogPanel.vue`（仓库挂载、逻辑表导入、别名创建）
- [x] 增强 `SemanticMappingEditor.vue` & `SemanticObjectDrawer.vue`（`GLOBAL`/`WORKSPACE` 作用域 + 跨仓异构映射矩阵）
- [x] 增强 `SemanticPlaygroundPanel.vue`（多仓编译计划预览）与 `SemanticOntologyView.vue`
- [x] 增强 `AskView.vue`（多仓徽章、联邦引擎标识、分仓 SQL 折叠视图）

## Stage 6: Architecture Sync, Seed & Full Verification
- [x] 同步 `ARCHITECTURE.md`、`docs/space-contracts/metadata.yaml`、`docs/space-contracts/data.yaml`
- [x] 新增 `scripts/seed-clinical-multisource.py`（MIMIC-IV + eICU-CRD 临床多仓示范种子）
- [x] 跑通全量 Maven 测试、前端构建、Agent 类型检查、严苛元语护栏校验与离线回归评测，并重启本地演示服务
