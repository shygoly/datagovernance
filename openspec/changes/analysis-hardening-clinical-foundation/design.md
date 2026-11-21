# Technical Design: 分析正确性硬化、临床队列与确定性统计执行层

- **Change ID**: `analysis-hardening-clinical-foundation`
- **Aligned Proposal**: [proposal.md](./proposal.md)

---

## 1. 目标架构（一层图）

```text
                    ┌─────────────────────────────────────────┐
                    │  OperationalReport（W5）                 │
                    │  参数 · 复合块 · 快照 · PDF/Excel · 分发   │
                    └───────────────────┬─────────────────────┘
                                        │ 块类型分派
          ┌─────────────────────────────┼─────────────────────────────┐
          ▼                             ▼                             ▼
   SMQ 块（问数）                 Cohort 块                      Stats 块
          │                             │                             │
          ▼                             ▼                             ▼
┌─────────────────┐          ┌──────────────────┐          ┌────────────────────┐
│ SemanticGate    │◄─────────│ CohortCompiler   │─────────►│ ClinicalStatsRuntime│
│ （W1 统一门禁）  │          │ Criterion → 成员集│          │ 评分/生存/回归/PSM… │
└────────┬────────┘          └────────┬─────────┘          └──────────┬─────────┘
         │                            │ 物化成员/特征                  │
         ▼                            ▼                             ▼
   SemanticQueryCompiler        平台内表 / 快照制品              算子输出表/标量
   MultiWarehouse / Federated        │                             │
         │                            └─────────────┬───────────────┘
         └────────────────────────────► METRIC 可引用 ◄─────────────┘
                                        │
                                        ▼
                              reproducibility_fingerprint（W4）
```

**硬规则**：`ClinicalStatsRuntime` **不**调用、也**不被** `SemanticQueryCompiler` 内嵌；二者只通过「已登记的数据集/映射/指纹」耦合。

---

## 2. W1 — `SemanticGateContext`（统一门禁）

### 2.1 输入
- `workspaceId`、`UserContext`
- 待编译 SMQ（或报表块引用的 SMQ）
- 解析后的度量/维度对象与 mappings
- 执行模式：`SINGLE` | `COHORT_UNION` | `ENTITY_FEDERATED_JOIN`

### 2.2 强制检查（三条路径同一实现）
| 检查 | 失败行为 |
|---|---|
| 对象 PUBLISHED + Standard（C2） | 类型化拒绝 |
| `allowedDimensions` / grain | `DIMENSION_NOT_ALLOWED` / `GRAIN_MISMATCH` |
| 时间窗：仅接受已归一化的绝对区间 | 拒绝相对口语残留 |
| 注入 `defaultFilters` + `EXCLUDES` | 不可被 SMQ 关闭 |
| 数据集/仓 ACL | 不可见则等价未解析 |
| `maxRows` / 联邦 extract 上限 | 标记 `truncated` |
| 审计必填字段清单 | 缺则拒绝返回成功态 |

### 2.3 接入点
- `SemanticQueryCompiler.compile` 入口首行
- `MultiWarehouseQueryCompiler` 各 Mode 入口
- `NativeInsightEngine` 联邦执行前（二次确认 truncate 元数据写入指纹）

单测：同一 SMQ fixture 在三种 Mode 下门禁失败码一致（允许 SQL 形状不同）。

---

## 3. W2 — 与 metric-6d 的交付契约

```text
metric-6d Phase 1（必须先完成）
  Metric6DSpec 结构校验 + SemanticYamlCodec
  G1–G5 + 删除保护 + STALE_REVIEW
        ↓
本提案 W1/W3 门禁与 Golden 可依赖「仅图合法」的度量
        ↓
metric-6d Phase 3 六维运算接通编译器（半加性/LOD）
```

若六维运算抢跑：本提案 CI 门禁视为失败（文档 + tasks 交叉引用）。

---

## 4. W3 — 正确性语义

| 规则 | 要求 |
|---|---|
| NULL 聚合 | `SUM` 忽略 NULL 是 SQL 标准；**禁止** `COALESCE(x,0)` 除非 Spec `null_as_zero: true` 且人审 |
| NULL 维度缝合 | 多事实 `FULL OUTER` + `IS NOT DISTINCT FROM`（由 smq 提案实现；本处 Golden 锁行为） |
| 重叠队列 | Cohort 编译默认对成员主键去重；若允许重叠须显式 `allow_overlap` 并进入指纹 |
| 除零 | ratio → NULL，不抛、不变 0 |
| 粒度 | 非法 grain → 拒绝，不静默上卷 |
| 截断 | `truncated=true` 时问数/报表 UI 与指纹双写；联邦部分源截断同理 |

Golden 目录建议：`evals/analysis-correctness/golden/`（PG 夹具优先）。

---

## 5. W4 — `reproducibility_fingerprint` Schema

与 `docs/analysis-report.md` §10 对齐，最小 JSON：

```json
{
  "schemaVersion": 1,
  "datasourceSnapshots": [{ "warehouseId": "", "datasetId": "", "snapshotRef": "" }],
  "semanticObjectVersions": [{ "code": "", "versionNo": 1, "contentDigest": "" }],
  "standardVersions": [{ "standardId": "", "version": "" }],
  "operatorDigests": [{ "operatorId": "", "version": "", "digest": "" }],
  "sqlDigest": "sha256...",
  "gateDigest": "sha256...",
  "delegationChain": [{ "principalType": "USER", "id": "" }],
  "truncation": { "truncated": false, "maxRows": 200 },
  "compiledAt": "2026-11-20T00:00:00Z"
}
```

Insight / Stats / OperationalReportSnapshot 均存储完整对象或其 SHA-256 锚定值。

---

## 6. W5 — Operational Report MVP

### 6.1 与 AnalysisReport 边界
| | AnalysisReport | OperationalReport |
|---|---|---|
| 问题 | 「为什么 / 解释」 | 「定期业务报表是什么」 |
| 作者 | 外部分析 Agent 为主 | 人配置 + 调度 |
| 内容 | Markdown 结论 + evidence | 参数化块编排 + 表格/图 |
| 导出 | 非主路径 | PDF/Excel 一等 |

### 6.2 最小对象
- `OperationalReportDef`：参数 schema、块列表（`SMQ_BLOCK` | `COHORT_BLOCK` | `STATS_BLOCK` | `STATIC_MD`）
- `OperationalReportVersion`：不可变定义快照 + digest
- `OperationalReportRun`：触发（手动/定时）、状态、指纹、制品指针（PDF/XLSX）
- `OperationalReportSchedule`：cron、收件人、敏感导出确认策略

### 6.3 导出与分发
- 渲染在装配层或侧车；模板白名单（C7）
- 定时任务走 Control；Governance 记审计；L3+ 导出需宿主确认记录

---

## 7. W6 — Cohort / Criterion / ClinicalStatsRuntime

### 7.1 `CohortSpec`（示意）
```yaml
cohort:
  code: icu.ae_within_48h_after_admit
  anchor:
    event: icu.admission
    timeAttr: admit_time
  window:
    startOffset: "PT0S"
    endOffset: "PT48H"
  include:
    - type: EXISTS
      event: medication.exposure
      drugCodeSet: standard.antibiotic_xxx
      relativeTo: anchor
      window: ["PT0S", "PT72H"]   # 示例：可组合另一窗
  exclude:
    - type: EXISTS
      event: palliative.flag
  memberGrain: encounter_id
  allow_overlap: false
```

### 7.2 `CriterionNode` 算子（v0）
`AND` / `OR` / `NOT` / `EXISTS` / `NOT_EXISTS` / `SEQUENCE` / `FIRST` / `LAST` / `TIME_WITHIN`

- 编译为确定性 SQL/计划，针对**已映射**事件事实表；失败返回类型化错误（对齐 SMQ `CompileReject` 形状）。
- **禁止** Criterion 内嵌原始 SQL 字符串。

### 7.3 `ClinicalOperator` SPI
```text
ClinicalOperator {
  id, version, inputSchema, outputSchema
  execute(OperatorRequest) -> OperatorResult  // 含 operatorDigest + fingerprint 片段
}
```
- 内置样例（证明闭环，非域完备）：`sofa_total_v1`（输入已对齐特征列）、`kaplan_meier_v0`（小样本夹具）之一即可。
- 实现可放 `dg-runtime` 算子包或侧车；`dg-metadata` 只登记算子元数据与版本。

### 7.4 与度量的关系
- 发布队列物化表 → `SemanticMapping` → 可选 METRIC（如队列内不良事件率）
- SMQ 不得表达「入 ICU 后 48h」；必须 `cohortRef` 或预计算成员维

---

## 8. 风险

| 风险 | 缓解 |
|---|---|
| 范围过大导致无法落地 | tasks 按 W1→W2→W3→W4→W6 样例→W5 分期；W5 可拆仓仍受本门禁 |
| 统计引擎拖慢 Metadata | SPI 边界 + 禁止编译器内嵌 |
| 与 smq/metric-6d 冲突 | proposal 明确 Coordinates With；Phase 0 改三份 tasks 交叉引用 |
| 报表与 AnalysisReport 混淆 | 命名与文档双轨；API 路径分离 |
