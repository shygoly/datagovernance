# OpenSpec Change: multisource-semantic-federation

## Why

当前语义本体内核（`semantic-ontology-kernel`，V36）已实现单一语义源、轻量知识图谱与确定性 SQL 编译，但在面向真实医疗与多中心分析场景（如 MIMIC-IV + eICU-CRD、院内 HIS + LIS）时存在 4 处结构性局限：

1. **缺乏多仓库挂载与 Warehouse Catalog**：每个 Workspace 尚无 MicroStrategy 风格的多仓库配置（Primary / Secondary Warehouse）与逻辑表/别名抽象（Logical Size 选表优先级、Attribute Role 别名）。
2. **缺乏跨 Workspace 权威标准本体共享**：标准临床实体与指标（如 `entity.patient`、`attr.patient.gender`、`metric.icu.los_hours`）在多个 Workspace 之间无法作为权威模板共享，容易出现口径漂移。
3. **同一本体跨仓异构映射与执行能力不足**：当前编译器假定单数据源单 SQL 输出，无法处理：
   - **互斥队列水平联合（`COHORT_UNION`）**：MIMIC 与 eICU 的患者主键属于不同命名空间，直接 Join 得 0 行，直接 Union 算 `COUNT(DISTINCT)` 会主键碰撞，直接把两库 `AVG` 求平均会产生统计学错误；
   - **共享实体键跨仓任意 Join（`ENTITY_FEDERATED_JOIN`）**：院内 HIS + LIS 共享 EMPI 时，需支持经 **StarRocks External Catalog** 或嵌入式 **DuckDB** 执行跨库任意 Join。

## What Changes

1. **不引入 `Project` 层级**，直接以既有 `Workspace` 承接业务/项目隔离边界，避免双重嵌套；
2. **`md_semantic_object` 增加 `scope = 'GLOBAL' | 'WORKSPACE'`**：平台级权威标准本体对全 Workspace 只读可见、直接复用，各 Workspace 仅配置各自的物理映射与私有扩展；
3. **新增 MicroStrategy 风格 Warehouse Catalog（Flyway `V38` + PG RLS）**：
   - `md_workspace_warehouse`：1 个 Workspace 挂载 1..N 个 `DataSourceDef`，声明 `PRIMARY/SECONDARY`、`cohort_code`、`id_namespace_prefix`、`combine_mode`、`federation_engine`（`AUTO`/`STARROCKS`/`DUCKDB`）、`starrocks_catalog_name`；
   - `md_logical_table`：逻辑表投影、`logical_size` 选表优先级与 `alias_of_table_id`（Attribute Role）；
   - `md_semantic_mapping` 增强：`warehouse_id`、`logical_table_id`、`value_normalization_json`、`source_unit`/`target_unit`。
4. **双引擎联邦查询 SPI（`dg-data-spi` + `dg-data-connectors`）**：
   - `FederatedQueryGateway`（纯接口，零驱动依赖，严守 C1）；
   - `DefaultFederatedQueryGateway`：提供 **StarRocks External Catalog 联邦下推** + **DuckDB 嵌入式内存安全联结**，强制 `ReadOnlySqlGuard` 与超时/行数护栏。
5. **确定性多仓编译器与编排（`dg-metadata` + `dg-app`）**：
   - `SemanticQueryCompiler.compileMultiWarehouse(...)` 统一支持 `SINGLE_ROUTE`、`COHORT_UNION`（命名空间主键前缀 + 两阶段充分统计量聚合）与 `ENTITY_FEDERATED_JOIN`（StarRocks 三段式 SQL / DuckDB 提取+联结 SQL）；
   - `NativeInsightEngine` 按仓执行并强制在合并前执行 C5 L3/L4 脱敏，附带跨仓联合 SHA-256 审计指纹（C8）。
6. **前端工作台升级**：
   - `/metadata/semantic` 新增「仓库目录（Warehouse Catalog）」与跨仓异构映射矩阵、多仓编译计划预览；
   - `/insight/ask` 展示多仓来源徽章、执行模式/联邦引擎标识与分仓 SQL 折叠视图。

## Non-goals

- 不新增 `Project` 实体或顶栏第二层切换器（`Workspace` 即租户与项目边界）。
- 不允许通过联邦查询向任何外部业务库写回数据（严格只读，C3/C6）。
- 不开放 DuckDB 直接访问未登记本地文件或绕过 `DataAccessGateway` 的无鉴权路径（C4/C5）。
- 不引入外部独立计算集群运维编排（StarRocks 为可选生产加速引擎；无 StarRocks 时自动降级至嵌入式 DuckDB）。

## Constitution Check

- **C1（Space 边界）**：`WorkspaceWarehouse` / `LogicalTable` / 多仓编译器归 `dg-metadata`；`FederatedQueryGateway` 接口归 `dg-data-spi`；DuckDB / StarRocks 执行器归 `dg-data-connectors`；装配编排归 `dg-app`。Maven 物理依赖严格无环。
- **C2（Standard 先行）**：`GLOBAL` 与 `WORKSPACE` 级 `METRIC` 均保留 `standardRef` 编译闸门；跨仓值归一化绑定统一标准码值。
- **C3（确定性计算优先）**：单位换算、`CASE WHEN` 值归一化、命名空间主键前缀、`AVG/比率` 两阶段充分统计量汇总与 `ReadOnlySqlGuard` 全部纯确定性执行，零 LLM 心算。
- **C4 / C5（零信任与纵深隔离）**：新增 `md_workspace_warehouse` 与 `md_logical_table` 全部启用 PostgreSQL RLS；`md_semantic_object` 的 RLS 仅对 `scope='GLOBAL' AND status='PUBLISHED'` 放行只读 `SELECT`，写入严格绑定 `workspace_id`；跨仓联合前先按 `md_column_sensitivity` 逐仓脱敏。
- **C6 / C7（克制写回与宿主画盘）**：仅只读分析，无外部写回；前端全部沿用 `--dg-*` Design Token、`confirmAction`/`confirmDanger` 与 C7 白名单图表。
- **C8（审计指纹与不猜不骗）**：多仓联合结果输出涵盖所有参与仓库、逻辑表与 SQL 摘要的 SHA-256 指纹；联邦源表被行数上限截断时显式告警并标明截断状态，绝不静默输出错误 Join 结论。
- **C9 / C10（受控演进与解耦）**：概率性 Agent 仅消费结构化 `SemanticQuerySpec` 工具，多仓路由/编译/联邦执行全部为纯确定性 Java 代码。
