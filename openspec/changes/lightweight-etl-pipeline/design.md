# Design: 轻量级原生 ETL 管道（`lightweight-etl-pipeline`）

## 1. 总体架构与执行流

```text
┌────────────────────────────────────────────────────────────────────────────┐
│ Frontend: /integration/etl (@vue-flow/core，复用字段血缘同款图库)          │
│  ├─ 左侧算子栏（Source / Transform / Custom SQL / Sink）                   │
│  ├─ 中央 DAG 画布 + 右侧属性与 Schema 映射抽屉                             │
│  └─ 底部面板：单步 100 行样本预览（L3/L4 脱敏）| 编译诊断 | 水位线状态     │
└───────────────────────────────────┬────────────────────────────────────────┘
                                    │ /api/v1/control/etl/*
┌───────────────────────────────────▼────────────────────────────────────────┐
│ Control Space: EtlPipelineCompiler                                         │
│  ├─ ① DAG 拓扑与环路校验                                                   │
│  ├─ ② 源/目标表列存在性 & 逐节点 Schema 推演                               │
│  ├─ ③ Custom SQL 安全白名单校验（仅只读 SELECT，禁用文件/网络函数）        │
│  ├─ ④ 敏感字段护栏（L3/L4 流向外部目标未经过 Mask 算子时告警/拦截）        │
│  └─ ⑤ 编译产出 CompiledEtlPlan（DuckDB SQL 链 + 写出规格 + 血缘边清单）    │
└───────────────────────────────────┬────────────────────────────────────────┘
                                    │ DispatchExecution / Preview
┌───────────────────────────────────▼────────────────────────────────────────┐
│ dg-app / Runtime Space: EtlPipelineRunner (DuckDB 隔离实例)                │
│  ├─ 1. Extract: dg-data-connectors + Watermark 增量拉取 → 临时本地表       │
│  ├─ 2. Transform: DuckDB 向量化 CTE/View 链（支持 temp_directory 溢写）    │
│  │                + RuleInterpreter 清洗/脱敏桥接                          │
│  ├─ 3. Load: JDBC (APPEND/OVERWRITE/UPSERT) 或 Parquet/S3 COPY TO          │
│  │           + Bad Row 死信分流                                            │
│  └─ 4. Post-Commit: 原子推进 etl_watermark + 上报 LineageService.recordJob │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 声明式 `ETL Pipeline IR`（`etl.ir/v1`）

复用现有 `JobDefinition`（`jobType = ETL_PIPELINE`），`configJson` 严格遵循版本化 IR：

```json
{
  "schemaVersion": "etl.ir/v1",
  "errorPolicy": {
    "mode": "ROUTE_TO_DEAD_LETTER",
    "maxBadRows": 1000,
    "deadLetterTarget": { "kind": "TABLE", "dataSourceId": "ds_dw", "table": "etl_bad_rows" }
  },
  "nodes": [
    {
      "id": "src_orders",
      "kind": "SOURCE_JDBC",
      "config": {
        "dataSourceId": "ds_ods",
        "database": "his",
        "schema": "public",
        "table": "orders",
        "incremental": { "enabled": true, "column": "updated_at", "type": "TIMESTAMP", "initialValue": "2026-01-01T00:00:00Z" }
      }
    },
    {
      "id": "tf_filter",
      "kind": "TRANSFORM_FILTER",
      "config": { "expression": "status = 'PAID' AND amount > 0" }
    },
    {
      "id": "tf_cleanse",
      "kind": "TRANSFORM_CLEANSE_MASK",
      "config": { "fieldRules": { "patient_phone": ["rule_mask_phone"] } }
    },
    {
      "id": "tf_sql",
      "kind": "TRANSFORM_CUSTOM_SQL",
      "config": { "sql": "SELECT order_id, patient_phone, ROUND(amount, 2) AS net_amount FROM {{tf_cleanse}}" }
    },
    {
      "id": "sink_dwd",
      "kind": "SINK_JDBC",
      "config": {
        "dataSourceId": "ds_dw",
        "database": "dw",
        "schema": "dwd",
        "table": "dwd_orders",
        "writeMode": "UPSERT",
        "primaryKeys": ["order_id"],
        "batchSize": 2000
      }
    }
  ],
  "edges": [
    { "from": "src_orders", "to": "tf_filter" },
    { "from": "tf_filter", "to": "tf_cleanse" },
    { "from": "tf_cleanse", "to": "tf_sql" },
    { "from": "tf_sql", "to": "sink_dwd" }
  ]
}
```

---

## 3. 数据库增量设计（Flyway 迁移）

```sql
-- 1. 增量水位线状态表（目标写出事务成功后原子推进）
CREATE TABLE IF NOT EXISTS etl_watermark (
    id                VARCHAR(40)  PRIMARY KEY,
    workspace_id      VARCHAR(40)  NOT NULL,
    job_id            VARCHAR(40)  NOT NULL,
    source_node_id    VARCHAR(64)  NOT NULL,
    watermark_column  VARCHAR(128) NOT NULL,
    watermark_type    VARCHAR(24)  NOT NULL, -- TIMESTAMP / BIGINT / VARCHAR
    last_value        VARCHAR(256),
    last_execution_id VARCHAR(40),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by        VARCHAR(40),
    CONSTRAINT uk_etl_watermark UNIQUE (workspace_id, job_id, source_node_id)
);

-- 2. 脏数据死信批次记录（Phase 2）
CREATE TABLE IF NOT EXISTS etl_bad_row_batch (
    id                VARCHAR(40)  PRIMARY KEY,
    workspace_id      VARCHAR(40)  NOT NULL,
    job_id            VARCHAR(40)  NOT NULL,
    execution_id      VARCHAR(40)  NOT NULL,
    node_id           VARCHAR(64)  NOT NULL,
    bad_row_count     BIGINT       NOT NULL DEFAULT 0,
    storage_ref       VARCHAR(1024),         -- 死信表名或对象存储路径（不含原始 L3/L4 明文日志）
    sample_error_json TEXT,                  -- 仅存错误类型与统计摘要
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

---

## 4. 受控 `Custom SQL` 与单步预览安全沙箱（D2 / D4）

1. **DuckDB 实例安全加固（每次执行/预览独立创建，用完销毁）**：
   - `SET memory_limit = '512MB'`（预览模式）或可配上限（生产运行默认 `2GB` + `SET temp_directory = '<isolated_dir>'` 允许落盘溢写防 OOM）；
   - `SET enable_external_access = false`（**核心安全开关**：在完成 Source 阶段抽取并落入 DuckDB 本地临时表后，立即关闭外部访问，物理阻断 `Custom SQL` 读取本地文件、环境变量或发起 HTTP/S3 请求！）；
   - 预览强制超时 `15s`（`Statement.setQueryTimeout(15)` + 取消令牌）。
2. **`Custom SQL` 静态白名单校验**：
   - 使用 JSqlParser 校验仅包含单条 `SELECT`（允许 `WITH` CTE），禁止 `COPY`、`ATTACH`、`INSTALL`、`LOAD`、`PRAGMA`、`CREATE`/`DROP`/`INSERT`/`UPDATE`/`DELETE`；
   - 表引用必须通过 `{{upstream_node_id}}` 占位符绑定，编译期替换为隔离视图名。
3. **预览敏感列实时脱敏（C5）**：
   - 预览结果返回前端前，沿算子 Schema 推演追溯各输出列对应的源 `catalogColumnId`，查询 `SensitivityService` 中 `L3/L4` 确认标签并结合当前 `UserContext` 权限执行实时脱敏掩码（复用 `RuleInterpreter.mask`）。

---

## 5. 与字段血缘的原生融合

- 对于结构化算子（`Select/Rename/Cast`、`Derive`、`Cleanse/Mask`、`Join/Lookup`、`Aggregate`、`Dedup`）：`EtlPipelineCompiler` 在编译期即可 100% 确定性地推演出 `fromColumn -> toColumn`、`dependencyType`（`DIRECT`/`INDIRECT`）、`transformExpr` 与 `maskingApplied`（当经过 `MASK` 规则时置 `true`，自动阻断高密级传播）。
- 对于 `Custom SQL` 算子：编译期/执行后调用 `lineage-parser-service`（SQLGlot）解析列级映射并与上下游结构化映射拼接成端到端列级血缘，统一写入 `LineageService.recordJob`（发布写 `PLANNED`，执行成功晋升 `ACTIVE` 并绑定 `execution_id`）。

---

## 6. Phase 2 / 3 落地决定与偏差（实现后补记）

> 以下是实现时做的取舍,与上文设计草图不一致处以本节为准。

### 6.1 码表映射（P2-2）
- **码表的落地形态**:代码库里没有独立的 `Standard` 表。`Standard(type=codeset)` 在平台里的实际载体是**语义对象 + 值字典**(`md_semantic_object` / `md_semantic_value_dict`:原始值、同义词 → 标准标签,写入侧已受 C5 约束为 L1/L2)。`codesetRef` 引用语义对象的 id 或 code;本空间对象优先于 GLOBAL,同级优先已发布。
- **编译期(C2)**:码表必须存在且可见;`DEPRECATED/REJECTED` 报错,未发布只告警;值字典为空告警。条目**不进计划**,计划只带 `codesetId`,执行期按 id 装载当时发布的版本。其它空间的 GLOBAL 码集条目数在编译期不可见(RLS),按「未知」处理而不是误报为空。
- **执行期**:码表装进 DuckDB 临时表(与源表一样在 `enable_external_access=false` 之前),节点 SQL 是 `LEFT JOIN`。`onMiss`:`KEEP_ORIGINAL`(缺省)/`NULL`/`DEFAULT`/`BAD_ROW`;NULL 输入不算未命中。未命中数对所有 `onMiss` 一律统计。

### 6.2 errorPolicy 与脏数据（P2-1）
- 三种模式:`FAIL_FAST` / `SKIP_BAD_ROWS`(别名 `SKIP`)/ `ROUTE_TO_DEAD_LETTER`,可配 `maxBadRows`、`maxBadRatio`。绝对数阈值随时判,比例阈值处理完后判(此时已提交的行不回滚)。
- **坏行来源**:① 码表 `onMiss=BAD_ROW`(行被移出主流);② Sink 写入失败 —— 批次失败后回滚该批次并**逐行重放**定位坏行,代价是失败批次的写入变慢。
- **摘要 `etl_bad_row_batch` 不含 L3/L4 明文**:样例只有行号、错误类别、SQLSTATE 与清洗后的消息(引号内文本 / `(列)=(值)` / 数字串被抹掉);码表未命中代码样例仅当编译器确认该列无敏感标签时才带。
- **死信行本体不走 IR 里的 `deadLetterTarget` 表**(上文草图),而是 `EtlDeadLetterStore` 抽象:当前实现为本地 NDJSON(`dg.etl.dead-letter-dir`),摘要里的 `storage_ref` 是相对引用。行本体里 `maskColumns`(任一节点输出出现过 L3/L4 的列)被替换为 `***`;编译器无法静态推断全部列时 `captureValues=false`,死信只有行号与错误类别。对象存储版是后续替换点。
- **已知取舍**:水位线按抽取到的最大值推进,被跳过 / 路由的坏行不会在下一次增量里重来,修复后需手工重放或重置水位线。
- **执行详情页(P2-1b)**:`GET /api/v1/control/etl/executions/{executionId}/bad-rows`(`control:etl:read`,按空间隔离)返回 `etl_bad_row_batch` 摘要(节点、坏行数、`storageRef`、`sampleErrorJson`);`ExecutionListView` 详情抽屉在 `jobRefType=ETL_PIPELINE` 且有 `control:etl:read|manage` 时展示。接口只做长度截断,**不再二次清洗**:`sampleErrorJson` 的 L3/L4 保护由写入侧(`EtlBadRowLedger`)保证;死信行本体不经此接口返回。

### 6.3 事件（P2-3）
- `ControlEvents.EtlExecutionCompleted`(进程内 Spring 事件,已列入 `control.yaml` 的 `events_produced`):行数、脏数据率、各码表节点未命中数、`status = SUCCEEDED | FAILED_BAD_ROW_LIMIT`。**不含行内容**。仅在拿得到 ETL 统计时发布(成功,或坏行超阈值失败);其它失败由 Runtime 的 `ExecutionFinished` 覆盖。
- **自治 Trigger 侧的完整订阅尚未接入**(`data-driven-ops-profile` 的 TriggerSpec 事件源;该规约目前只有 openspec,没有 Trigger 运行时)。
- **P2-3b 最小桥接 `EtlOpsTriggerBridge`(dg-app)**:同步订阅 `EtlExecutionCompleted`,当 `status=FAILED_BAD_ROW_LIMIT`,或 `badRows>0 且 badRowRate ≥ dg.etl.ops.bad-row-rate-threshold`(缺省 0.05)时,写 WARN 日志并追加一条 `gv_audit_record`(`action=ANOMALY`、`resourceType=ETL_EXECUTION`、`errorCode=ETL_BAD_ROW_LIMIT|ETL_BAD_ROW_RATE_HIGH`,detail 只有计数与比例)。**没有**创建 `gv_alert`:现有 `AlertRule.TriggerType` 里没有「ETL 异常」,为一个桩去扩枚举会提前绑死 data-driven-ops-profile 的设计;且 Governance 不可见 Control,所以桥接放装配层。TriggerSpec 运行时落地后改由它订阅同一事件,本类即可删除。失败只记日志,不影响管道执行。
- **水位线重置审计**:`EtlWatermarkAuditListener`(dg-app)监听 `EtlWatermarkReset`(事务提交后)追加 `UPDATE / ETL_WATERMARK` 审计,detail 含原因与重置前后的值。

### 6.4 引擎 SPI（P3-1）
- `EtlExecutionEngine`(dg-runtime `com.datagov.runtime.etl`)+ `EtlEngineRegistry`;`EtlPipelineRunner` 变薄:按计划 `engine` 字段(缺省 `DUCKDB`)选引擎、发事件。`DefaultDuckDbEtlEngine`(dg-app)承载原 Runner 逻辑。找不到引擎时明确报错,不静默回落 DuckDB。
- **`SeaTunnelEngineAdapter` 是桩**:`translate(plan)` 把编译产物翻译为 HOCON 草稿(连接信息只有 `${DG_DS_<id>_*}` 占位,凭据不内联;DuckDB 方言、码表节点、多入边 JOIN、水位线、坏行策略一律以告警标出);`execute` 抛 `UnsupportedOperationException` 并指引改回 `DUCKDB`。编译器遇 `engine=SEATUNNEL` 给警告「只翻译不执行」。

**Non-goals(本版明确不做)**:向 SeaTunnel 集群提交作业与状态回收(P3-1b);HOCON 未经 SeaTunnel 运行时验证;死信的对象存储实现;`data-driven-ops-profile` 的 TriggerSpec 运行时;多 Sink;Parquet 目标的 `partitionBy` / `APPEND`;接口源的分页。

### 6.5 编译产物与引擎的计划形状对接
Phase 1 引擎的 `parse` 只认手写形状(`nodes[].type`),而编译器产出的是 `duckdbSteps[].step = LOAD|SQL|SINK` + `ir.edges`,两者此前没有测试连起来过。`EtlPipelineEngine#fromCompiledPlan` 把编译产物适配成引擎形状,编译器也开始把 `jobId` 写进计划(水位线与坏行摘要都需要它)。

### 6.6 文件源 / 接口源 / Parquet 目标（P1-4 补齐）
**安全顺序不变**:一切 I/O 都在 `SET enable_external_access=false` 之前由 Java 完成,用户 SQL 从头到尾碰不到文件系统与网络。

- **计划里没有服务器路径**:`SOURCE_FILE` / `SINK_PARQUET` 只用 `fileId`,或 `dataSourceId + 相对路径`(编译器与 `DefaultEtlObjectStore` 各校验一次:不含 `..`、不是绝对路径、无 scheme / 盘符 / 反斜杠)。引擎只通过 `EtlObjectStore` 缝隙取 / 存。
- **SOURCE_FILE**:`EtlObjectStore.fetch` 把对象落到引擎自建的临时目录 → `CREATE TEMP TABLE AS SELECT * FROM read_csv|read_json_auto|read_parquet(临时文件)`(外部访问仍开放时)→ 删临时文件 → 锁定。CSV `options` 只认白名单(`delimiter` 单字符 / `header` / `encoding`),取值校验后才拼进 SQL。预览按 `maxSourceRows` 封顶(`LIMIT`)。
- **SOURCE_API**:`EtlApiFetcher`(GET、不跟随重定向、响应体 ≤ 64MB、认证头由 `apiConfigId` 数据源凭据在执行期注入,不进计划)取 JSON 数组(`recordsPath`),列 = 各行键并集,类型从值推断(整数→BIGINT,含小数→DOUBLE,布尔,其余 VARCHAR,嵌套对象 / 数组存 JSON 文本),锁定前逐行写入临时表。0 条记录时明确报错(推不出列)。**不含分页**(IR 无此字段)。**SSRF**:计划里直接写的 `url` 拒绝回环 / 链路本地(含 169.254 元数据)/ 组播 / 通配地址,私网默认也拒绝(`dg.etl.api.allow-private-hosts=true` 放开);`apiConfigId` 引用的 REST_API 数据源由管理员配置,视为受信。不防 DNS rebinding。
- **SINK_PARQUET**:锁定后的会话不能 `COPY ... TO`,所以 Java 从锁定会话 `SELECT * FROM 上游视图` 流式读出,写进一个**只由 Java 驱动的独立临时 DuckDB 连接**(不跑任何用户 SQL),由它 `COPY ... TO 临时 parquet`(压缩 SNAPPY/ZSTD/GZIP/NONE),再 `EtlObjectStore.put` 落到目标。只支持**单文件 OVERWRITE**:`partitionBy` 与 `APPEND` 在解析阶段明确拒绝(OVERWRITE 幂等,不标记 unsafe-to-retry)。列类型沿用抽取映射(整数统一 BIGINT),不保证与上游物理类型逐一相同。一次执行要求恰好一个 Sink(JDBC 或 PARQUET)。
- **`DefaultEtlObjectStore` 支持矩阵**:LOCAL(读写;根目录 + 真实路径越界校验含符号链接;写入先落同目录暂存文件再原子 move)、FTP / SFTP(只读,`FileCatalogReader.openFile`;连接器无写入能力,作 Sink 被拒)、S3 / MinIO / OSS(用独立临时 DuckDB 连接 + httpfs 读写,凭据只注入该连接;**没有针对真实对象存储的自动化测试**)。`fileId`(平台文件资产)**目前仓库里没有对应服务**,明确拒绝并提示改用 `dataSourceId + path`。
