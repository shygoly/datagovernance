# Proposal: 轻量级原生 ETL 管道（Lightweight ETL Pipeline · DG-ETL Lite）

- **Change ID**: `lightweight-etl-pipeline`
- **Status**: Draft
- **Depends On**: `dg-data-connectors`（现有 10+ JDBC / Parquet / DuckDB / 文件 / API 连接器）、`RuleService` / `RuleInterpreter`（功能 17 清洗与脱敏规则）；**协同对接** `column-lineage-e2e`（自动上报列级血缘）与 `data-driven-ops-profile`（异常自动触发自治闭环）
- **Constitution Alignment**: 严格遵循 `CONSTITUTION.md`（C1–C10）、`SPACE-MODEL.md`、`GOVERNANCE-MODEL.md`、`frontend/DESIGN.md`

---

## Why

平台目前已有离线单表同步（`OfflineSyncRunner`）、整库迁移（`DbMigrationRunner`）、单库 SQL 批处理（`BatchDevRunner`）与 9 种单列逐行清洗规则（`RuleInterpreter`），但仍缺少真正可用、轻量、可视化的 **ETL（Extract-Transform-Load）管道能力**：

1. **转换（Transform）能力严重不足**：无法进行跨源多表 Join、码表（`Standard(codeset)`）查表映射、分组聚合、按主键去重、表达式派生列及受控多步加工；
2. **缺少增量水位线与脏数据容错**：无内置水位线（Watermark）自动推进与回刷机制，脏数据无法分流至死信表/错误文件，也无法直接写出 Parquet / 对象存储；
3. **缺少可视化画布与单步调试能力**：现有同步只能填大表单，无法拖拽算子编排，也无法在发布前 1 秒预览任意算子节点的样本变换结果；
4. **与治理底座割裂**：同步/加工任务未自动上报字段级血缘，且缺少编译期敏感列未脱敏预警。

本提案基于项目**已内嵌的 `jdbc:duckdb:`** 与现有连接器，打造**零新增外部集群、单机秒级处理百万行、声明式 IR 可换引擎**的原生轻量 ETL 管道（`JobType.ETL_PIPELINE`）。

---

## Design Decisions（已对齐决策）

| # | 决策项 | 结论 |
|---|---|---|
| D1 | **执行内核与 IR 分层** | 定义版本化 **ETL Pipeline IR（`etl.ir/v1`）**，MVP 采用 **内嵌 DuckDB 向量化执行内核**（支持磁盘溢写防 JVM OOM，零新增外部进程）；预留 `EtlExecutionEngine` SPI，未来超大表/CDC 可平滑对接 SeaTunnel 等引擎。 |
| D2 | **算子体系与 Custom SQL 边界** | 提供首批 10 类结构化算子（Source / Filter / Select-Rename-Cast / Derive / Cleanse-Mask / Join-Lookup / Aggregate / Dedup / Sink），并**开放受控 `Custom SQL` 算子**：仅允许对上游 DuckDB 临时表执行只读 `SELECT`（禁用外部文件/网络/系统函数）。 |
| D3 | **增量抽取与水位线（Watermark）** | Phase 1 即内建 **`etl_watermark` 持久化表**（按 `workspace_id + job_id + source_node_id` 维护递增主键/时间戳位点），仅在目标写出事务成功后原子推进，支持手工重置回刷。 |
| D4 | **单步 Dry-Run 样本预览护栏（C5）** | 默认限制上游 `LIMIT 100` 行，强制 DuckDB 内存上限 `512MB` 与超时 `15s`；按当前 `UserContext` 实时对 L3/L4 敏感列脱敏返回，绝不落盘原始预览样本。 |
| D5 | **前端交互形态与菜单入口** | 新增独立菜单 **`ETL 管道`（`/integration/etl`）**，复用 `@vue-flow/core` 构建可视化算子拖拽画布 + 右侧属性配置抽屉 + 底部 100 行样本预览与编译诊断面板，与既有离线同步表单并存。 |

---

## What Changes

### Phase 1：ETL Pipeline IR + DuckDB 向量化内核 + 可视化画布 MVP
1. **数据模型与定义态**：新增 `JobType.ETL_PIPELINE` 与 Flyway 迁移（`etl_watermark` 水位线表、`etl_bad_row_batch` 脏数据批次记录），全部启用 `workspace_id` + PG RLS 隔离。
2. **编译期护栏（`EtlPipelineCompiler`）**：校验 DAG 无环、源/目标表列存在性、Schema 类型推演、`Custom SQL` 只读白名单（禁用 `read_csv`/`read_parquet`/`httpfs` 等越权函数）、以及 L3/L4 敏感列跨源未脱敏编译警告/拦截（C2/C5）。
3. **DuckDB 执行内核（`EtlPipelineRunner`）**：
   - **Extract**：复用 `dg-data-connectors` 抽取 JDBC（支持 Watermark 增量过滤）/ Parquet / CSV / JSON / REST API 至作业隔离的临时 DuckDB 实例（配置 `memory_limit` 与 `temp_directory` 溢写）；
   - **Transform**：将结构化算子与受控 `Custom SQL` 编译为确定性 CTE / 临时视图链执行，并桥接现有 `RuleInterpreter` 清洗/脱敏规则；
   - **Load**：支持写回 JDBC（`APPEND / OVERWRITE / UPSERT` 复用 `DialectUpsert`）与写出 Parquet / 对象存储；写入成功后原子推进 `etl_watermark`。
4. **单步 Dry-Run 样本预览 API**：`POST /api/v1/control/etl/preview`，支持指定任意算子节点 1 秒返回前 100 行输入/输出对比（L3/L4 实时脱敏）与 Schema 推演结果。
5. **原生血缘自动上报**：执行成功与发布时，直接从 Pipeline IR 列映射（含 `Custom SQL` 经 `lineage-parser-service` 解析）调用 `LineageService.recordJob` 写入 `PLANNED` / `ACTIVE` 字段血缘。
6. **前端可视化工作台（`/integration/etl`）**：基于 `@vue-flow/core` 提供算子面板、拖拽连线、右侧配置抽屉、底部样本预览/编译诊断与水位线管理。

### Phase 2：脏数据死信分流、码表 Lookup 与自治联动
1. **脏数据死信出口（Bad Row Sink）**：类型转换失败、主键为空或规则校验不通过的行按 `errorPolicy`（`FAIL_FAST / SKIP / ROUTE_TO_DEAD_LETTER`）路由至死信表或错误 Parquet 文件，并统计脏数据率。
2. **治理码表 Lookup 算子**：原生绑定平台 `Standard(type=codeset)` 权威码表，支持代码值标准化转换与未命中告警（落实 C2）。
3. **与 `data-driven-ops-profile` 联动**：ETL 失败、行数骤降或脏数据率超阈值时发出事件，驱动数据自治 `Trigger` 与 `DecisionTrace`。

### Phase 3：高阶引擎适配器（可选扩展）
1. 抽象 `EtlExecutionEngine` SPI，支持将同一份 `etl.ir/v1` 编译为 Apache SeaTunnel HOCON 或 Spark/Flink 作业，无缝承接百 GB+ 分布式或 CDC 实时同步。

---

## Non-goals（明确不做）

- **不引入重型独立 ETL 集群**（MVP 不部署 Kettle/Hop Server、SeaTunnel 集群、Spark 集群或 Airflow）。
- **不支持任意脚本代码执行**（不在 ETL 算子中开放裸 Python/Shell/Groovy 脚本，防止 ASI05 意外代码执行）。
- **不绕过既有权限与脱敏内核**：`Custom SQL` 严格运行在已抽取的隔离 DuckDB 内存临时表上，无法直连外部网络或本地文件系统。
- **不废弃现有离线同步与整库迁移页面**，保持既有任务 100% 向后兼容。

---

## Constitution Check（C1–C10）

| 公理 | 检查项与合规设计 |
|---|---|
| **C1 Space 边界** | Pipeline 定义归 `dg-metadata`；IR 编译与校验归 `dg-control`；执行引擎 SPI 与规则解释归 `dg-runtime`；连接器装配与 `EtlPipelineRunner` 归 `dg-app`；严守 Maven 无环依赖。 |
| **C2 治理为编译输入** | `EtlPipelineCompiler` 编译期校验表列存在性、码表 `Standard` 存在性与敏感列脱敏护栏。 |
| **C3 确定性计算优先** | 全部 ETL 转换与 `Custom SQL` 均为确定性 DuckDB SQL / 规则解释执行，绝无 LLM 心算；只读白名单与超时/内存上限硬编码强制。 |
| **C4 零信任身份** | 预览与执行均校验发起人 `UserContext` 与数据源访问权限，无共享超级账号。 |
| **C5 纵深隔离与脱敏** | 临时 DuckDB 实例按执行隔离、结束即销毁；样本预览 L3/L4 实时脱敏不落盘；`etl_watermark` 等表启用 `workspace_id` + PG RLS。 |
| **C6 受控写入** | 仅写入用户显式配置且经权限校验的目标数据源/对象存储，记录完整行数与审计；禁止对外部 HIS/EMR 源库写回。 |
| **C7 宿主掌握画盘** | 前端画布完全由宿主 `@vue-flow/core` 白名单算子组件渲染，不接受外部可执行前端代码。 |
| **C8 证据与可追溯** | 每次运行绑定 `execution_id`、水位线区间、读写/脏数据行数与自动上报的列级血缘证据。 |
| **C9 受控演进** | Pipeline 变更走版本化发布；水位线手工重置留痕审计。 |
| **C10 确定性与概率解耦** | ETL 执行主链路 100% 确定性编排；未来 Agent 仅可辅助生成 `etl.ir/v1` 草案并经编译器校验。 |
