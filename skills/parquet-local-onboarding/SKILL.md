---
name: parquet-local-onboarding
description: 引导用户将后端服务器本机或 NFS 上的 Parquet 目录接入平台数据源并完成元数据目录采集。当用户提出接入本地 Parquet、NFS 目录、扫描本地数据湖文件或批量注册 Parquet 表结构时触发。
allowed-tools:
  - create_local_datasource
  - update_local_datasource
  - test_datasource
  - list_catalog_sources
  - create_parquet_catalog_source
  - refresh_catalog_source
  - navigate_workspace
---

# 本地 / NFS Parquet 数据源引导接入规程

> 所属元语：`A0.2`（由 `agent-service/src/agent.ts` 硬编码系统提示词抽离的渐进式技能包）。
> 安全红线：严守 `CONSTITUTION.md` C4（按真实发起人重算权限）、C6（仅采集元数据，不复制业务数据）、C7（写操作前须确认参数与目录边界）。

## 适用前置条件

在调用任何写工具前，必须向用户明确确认以下两项必填信息，**严禁猜测路径或编造名称**：

1. **数据源名称**（当前 Workspace 内唯一）；
2. **后端进程所在机器上的绝对目录路径**（`rootPath`，例如 `/data/lakehouse`），并明确提醒用户该路径是后端服务器视角路径，而非浏览器本机路径。

## 标准执行步骤

1. **确认参数与风险边界**
   - 缺少数据源名称或 `rootPath` 时先追问用户；
   - 路径必须为绝对路径且非根目录 `/`；若处于宿主确认模式，先呈交结构化参数供宿主 UI 二次确认后再执行写动作。
2. **创建 LOCAL 数据源**
   - 调用 `create_local_datasource` 创建 `LOCAL` 类型数据源。
3. **连通性校验与纠错**
   - 调用 `test_datasource` 验证目录存在且可读；
   - 测试失败时向用户解释真实失败原因，必要时在用户提供修正路径后调用 `update_local_datasource` 重试；
   - **硬门禁**：连通性测试未通过前，严禁进入下一步建目录来源。
4. **跳转展示数据源**
   - 测试通过后调用 `navigate_workspace`，跳转至 `/metadata/datasources`，并通过 `query.highlight` 传入数据源 ID。
5. **创建 Parquet 目录来源**
   - 调用 `create_parquet_catalog_source` 创建 `PARQUET_DIRECTORY` 目录来源；
   - 默认命名为 `<数据源名>-catalog`，`rootPath` 留空以复用数据源根目录；
   - 若返回重名错误，先调用 `list_catalog_sources` 检查现有来源，再协商改名或复用。
6. **执行元数据采集**
   - 调用 `refresh_catalog_source` 读取 Parquet footer / schema / 分区元数据写入平台目录。
7. **跳转展示采集结果并汇总**
   - 调用 `navigate_workspace`，跳转至 `/metadata/catalog`，通过 `query.sourceId` 自动选中新建目录来源；
   - 用中文汇总采集到的数据集数量、字段数量与告警信息；
   - 明确告知边界：**当前仅采集元数据、不复制原始文件；如需对这批 Parquet 执行 SQL 查询或问数，需先接入 StarRocks / Doris / Trino / Spark 等查询引擎并注册对应数据集**。

## 异常处理表

| 异常情形 | 处置动作 |
|---|---|
| 权限不足（403） | 明确告知缺少对应权限码（`metadata:datasource:create` / `metadata:asset:refresh`），不得尝试绕过 |
| 路径无效或不可读 | 停止后续步骤，反馈后端报错并请用户确认服务器挂载目录 |
| 目录来源重名 | 先调用 `list_catalog_sources` 核对，禁止静默覆盖 |
| 采集存在部分告警 | 如实透出 `warnings`，不夸大完成度 |
