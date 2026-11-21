# Tasks: 轻量级原生 ETL 管道（`lightweight-etl-pipeline`）

> 实施原则：严格遵守 `CONSTITUTION.md`（C1–C10）与 8 Space 边界；先交付可跑通、可预览、自带血缘的 Phase 1 MVP。

---

## Phase 1：ETL Pipeline IR + DuckDB 执行内核 + 可视化画布 MVP

### 1.1 数据模型与 IR 契约
- [x] **P1-1** 新增 Flyway 迁移脚本（承接最新版本号），创建 `etl_watermark` 与 `etl_bad_row_batch` 表，启用 `workspace_id` + PostgreSQL RLS 策略（C5）。
- [x] **P1-2** 在 `dg-control` 扩展 `JobType.ETL_PIPELINE` 与 `JobRefType.ETL_PIPELINE`，定义 `etl.ir/v1` 模型及 `WatermarkService`（查询、事务成功后推进、手工重置并记审计）。

### 1.2 编译器与安全沙箱
- [x] **P1-3** 实现 `EtlPipelineCompiler`：
  - DAG 拓扑排序与环路检测；
  - 源/目标表列存在性校验与逐节点 Schema 类型推演；
  - `Custom SQL` 仅允许只读 `SELECT` 的白名单校验与 `{{nodeId}}` 绑定；
  - 敏感列跨源未脱敏编译警告（C2/C5）；
  - 产出 `CompiledEtlPlan`（DuckDB 转换 SQL 链 + 写出规格 + 血缘边清单）。

### 1.3 DuckDB 执行内核与单步预览
- [x] **P1-4** 在 `dg-app` 实现 `EtlPipelineRunner`（封装隔离 DuckDB 会话）：`SOURCE_JDBC`(含水位线增量) / `SOURCE_FILE`(CSV·JSON·NDJSON·Parquet) / `SOURCE_API` → DuckDB 转换 → `SINK_JDBC`(`APPEND/OVERWRITE/UPSERT`) / `SINK_PARQUET`，抽取完成后 `SET enable_external_access = false`，写入成功后推进 `etl_watermark`。**已知范围限制**(见 design.md §6.6):Parquet 目标只支持单文件 OVERWRITE(`partitionBy` / `APPEND` 解析期拒绝);接口源无分页;`fileId` 引用因平台尚无文件资产服务而拒绝,只支持 `dataSourceId + path`;对象存储写入仅 LOCAL / S3 系(FTP/SFTP 只读),**S3 路径无真实对象存储的自动化测试**。
  - **Extract**：支持 `SOURCE_JDBC`（带 `etl_watermark` 增量过滤）、`SOURCE_FILE`（Parquet/CSV/JSON）、`SOURCE_API`；抽取完成后强制 `SET enable_external_access = false`；
  - **Transform**：支持 `Filter`、`Select/Rename/Cast`、`Derive`、`Cleanse/Mask`（桥接 `RuleInterpreter`）、`Join`、`Aggregate`、`Dedup` 与受控 `Custom SQL`；
  - **Load**：支持 `SINK_JDBC`（`APPEND/OVERWRITE/UPSERT`）与 `SINK_PARQUET`；写入成功后原子推进 `etl_watermark`。
- [x] **P1-5**(证据:`EtlPipelineController#preview` 已落地,`PREVIEW_LIMIT=100` / `512MB` / `15s` 经 `Limits.preview` 传入引擎,`EtlPipelineRunnerTest#previewDoesNotWrite` 覆盖「预览不写入」;**范围限制**:L3/L4 脱敏已接入 `AccessRequestService.activeGrants`，按当前 `UserContext` 的类别/级别逐列放行或脱敏；仍不能追踪 `AS` 重命名/表达式派生的敏感列，未在真实数据源上做超时/内存上限压测)实现单步 Dry-Run 样本预览接口 `POST /api/v1/control/etl/preview`：强制 `LIMIT 100`、`memory_limit=512MB`、`15s` 超时，并按当前 `UserContext` 的有效 AccessGrant 对 L3/L4 敏感列逐列脱敏/放行；无 grant 或无法判定时保持脱敏。
- [x] **P1-6**(证据:`LineageEventListener` 编译→`PLANNED`、执行成功→`ACTIVE` 并绑定 `execution_id`,`LineageEventListenerTest` 12 例;`StructuredJobLineageExtractor.extractEtlPipeline` 现对 `TRANSFORM_CUSTOM_SQL`/`CUSTOM_SQL` 节点(顶层 `nodes` 或 `ir.nodes`)把 `{{上游}}` 重写为物理表后经 `SqlLineageParserClient.parseColumn` 补齐「物理源列→Sink 列」边,编译器 `lineageEdges` 优先、重复 from→to 不覆盖、INCONCLUSIVE 只告警不造边,`StructuredJobLineageExtractorTest` 新增 5 例;**范围限制**:仅支持上游为(仅经过滤的)`SOURCE_JDBC`、输出(仅经过滤)直连 JDBC Sink 的 Custom SQL;方言用 `postgres`(sidecar 当前无 `duckdb` 方言);未对真实 lineage-parser-service 与真实 PostgreSQL 做端到端联调)打通字段血缘上报：Pipeline 发布写入 `PLANNED` 血缘，执行成功晋升为 `ACTIVE` 并绑定 `execution_id`（`Custom SQL` 节点调用 `SqlLineageParserClient` 补齐列映射）。

### 1.4 前端可视化画布（`/integration/etl`）
- [x] **P1-7**(证据:`frontend/src/router/index.ts` 的 `integration/etl`、`integration/etl/:id` 路由带 `menu:integration:etl` 权限(菜单与权限码由 V49 种入),`EtlPipelineEditorView.vue` + `components/etl/EtlFlowNode.vue` 基于 `@vue-flow/core`;**未做浏览器手测**)新增 `/integration/etl` 路由与权限配置，基于 `@vue-flow/core` 实现 ETL 算子拖拽画布（Source / Transform / Custom SQL / Sink 节点与连线）。
- [x] **P1-8**(证据:`EtlNodeDrawer.vue` 接收 `upstreamColumns/upstreamIds` 推演上游 Schema 并支持自动字段映射;`CompileDiagnosticsDialog.vue` 为编译诊断;水位线页签已接 `GET/POST /control/etl/{jobId}/watermarks[...]`:展示当前水位 / 最近推进执行,`control:etl:manage` 可带原因重置并写审计;预览走 P1-5 接口;**未做浏览器手测,`pnpm type-check` 本轮未重跑**)实现右侧节点属性配置抽屉（自动加载上游推演 Schema、字段映射与规则选择）与底部三页签面板（**单步 100 行样本预览** / **编译诊断** / **增量水位线管理与重置**）。

---

## Phase 2：脏数据死信分流、码表 Lookup 与自治联动

- [x] **P2-1** 实现 `errorPolicy`（`FAIL_FAST / SKIP / ROUTE_TO_DEAD_LETTER`）与脏数据死信落盘（`etl_bad_row_batch`）：后端完成（坏行来源：码表 `BAD_ROW`、Sink 逐行重放；摘要无 L3/L4 明文；死信本体经 `EtlDeadLetterStore`，当前为本地 NDJSON）。编辑器仅同步了模式取值，未做执行详情页。
- [x] **P2-1b** 前端执行详情支持查看脏数据统计与错误原因摘要：`GET /api/v1/control/etl/executions/{executionId}/bad-rows` + `ExecutionListView` 详情抽屉(`ETL_PIPELINE` 执行;摘要里 `sampleErrorJson` 的 L3/L4 保护由写入侧 `EtlBadRowLedger` 保证,接口只截断)。
- [x] **P2-2** 实现 `TRANSFORM_CODESET_LOOKUP` 算子：原生绑定平台 `Standard(type=codeset)` 权威码表，编译期校验码表存在性（C2），执行期完成代码值标准化与未命中计数。
- [x] **P2-3** 发出 `EtlExecutionCompleted`（`ControlEvents`，含行数、脏数据率、码表未命中数）事件，已列入 `control.yaml`。
- [x] **P2-3b** 最小运维联动桩 `EtlOpsTriggerBridge`(dg-app):订阅 `EtlExecutionCompleted`,`FAILED_BAD_ROW_LIMIT` 或脏数据率 ≥ `dg.etl.ops.bad-row-rate-threshold`(缺省 0.05)时写 WARN 日志 + `gv_audit_record`(`ANOMALY / ETL_EXECUTION`,仅计数与比例)。**不是** TriggerSpec 运行时,也不创建 `gv_alert`(见 design.md §6.3)。
- [x] **P2-3c** `data-driven-ops-profile` 的 TriggerSpec 运行时订阅该事件并替换 P2-3b 桩(依赖该变更落地,本变更不做)。
  - 落地说明：`AutonomyEtlAnomalyListener` 订阅 `EtlExecutionCompleted`，把 status/badRowRate/坏行计数映射为 `ETL_ANOMALY` 触发快照，交给 `AutonomyFlowService` 启动 Flow；原 `EtlOpsTriggerBridge` 保留为审计兜底（不再决定自治动作）。V50 的自治表与 `AutonomyFlowService` 已落地，故本项关闭。

---

## Phase 3：多引擎扩展（可选）

- [x] **P3-1** 抽象 `EtlExecutionEngine` SPI（`DefaultDuckDbEtlEngine` + `EtlEngineRegistry`），并提供 `SeaTunnelEngineAdapter` **桩**：只把编译产物翻译为 HOCON 草稿，`execute` 抛 `UnsupportedOperationException`。
- [ ] **P3-1b**（非目标，本版不做）SeaTunnel 作业提交 / 状态回收 / CDC；HOCON 未经 SeaTunnel 运行时验证。

---

## Verification & Acceptance Gates

- [x] 元语与防漂移校验：`python3 scripts/verify-primitives.py`（2026-11-20 PASSED）
- [x] Space Contract 校验：`python3 docs/space-contracts/verify.py`（2026-11-20 PASSED）
- [x] 后端编译与全量测试（2026-11-21 `mvn -o -pl backend/dg-app -am test`，JDK 21，944 tests / 0 failures；含 DuckDB `enable_external_access=false` 沙箱拦截测试、水位线事务推进/回滚测试、预览 L3/L4 脱敏测试、血缘自动上报测试、`PgRlsIsolationTest` 对 V50 自治表的 RLS 验证）：`mvn -pl backend/dg-app -am test`
- [x] 前端类型检查与生产构建（2026-11-20 通过）：`cd frontend && pnpm type-check && pnpm build`
- [x] 补齐文件 / 接口 / Parquet / 水位线 REST / 脏数据 FE / ops 桩 / Custom SQL 血缘后的终态复验（2026-11-21）：自仓库根目录 `python3 scripts/verify-primitives.py` + `python3 docs/space-contracts/verify.py` + `mvn -o -pl backend/dg-app -am test` + `cd frontend && pnpm type-check && pnpm build` 全部 exit 0。未覆盖：真实 S3/MinIO、真实 PostgreSQL 上 V49/水位线 REST 端到端、浏览器 UI 手测、真实 lineage-parser-service。
