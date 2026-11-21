# Design: `chance-t0-alignment-and-qc-rules`

## 1. 目标架构

```text
语义映射（物理列 → AnchorEvent / Observation）
        │
        ▼
┌───────────────────┐     RuleSpec 包（Standard 可插拔）
│ AlignToAnchor     │◄──── CNLC/单位/时序 规则编译
│ SplitTreatment    │
│ Cycles            │
└─────────┬─────────┘
          │ 异常 + 对齐结果（仅结构化）
          ▼
   Governance 质控异常 / 复检
          │
          ▼
   （下游）chance-specialty-analytics-boards
```

## 2. 核心类型（逻辑）

### AnchorEvent
| 字段 | 说明 |
|---|---|
| `anchorId` | 稳定 ID |
| `patientRef` | 带命名空间前缀（如 `synthea:SYN-P-001`） |
| `anchorType` | 枚举可扩展：`INTERVENTIONAL_TACE` / `RESECTION` / … |
| `anchorTs` | 实际执行完成时间 \(T_0\) |
| `sessionSeq` | 程次，从 1 递增 |
| `sourceRefs` | 物理表/行指纹 |

### AlignmentHit
| 字段 | 说明 |
|---|---|
| `anchorId` / `sessionSeq` | 归属锚点 |
| `role` | `BASELINE_IMAGING` / `BASELINE_LAB` / `FOLLOWUP` / … |
| `window` | 配置窗（如 `[-30d,0)` / `[28d,56d]`） |
| `candidateRef` / `eventTs` | 命中事件 |
| `status` | `MATCHED` / `AMBIGUOUS` / `MISSING` |

## 3. Space 归属

| 组件 | Space | 约束 |
|---|---|---|
| 锚点/窗口配置、规则包元数据 | Metadata | 经 Standard 引用 |
| RuleSpec 编译、批任务定义 | Control | 不执行物理 IO |
| Align / Split / QC 执行 | Runtime | 经 DataAccessGateway 只读 |
| 异常、复检、审计指纹 | Governance | 不可见 Control；订阅完成事件 |

## 4. 专病规则包落地方式

- 仓库内以 **种子 YAML/JSON + Standard 编码**（如 `STD_CHANCE_HCC_QC_V1`）交付；
- 运行时仅通过 `standardRef` 加载；更换行业包不改 Java 分支；
- CNLC 推导表维护在规则包内（版本化），冲突时输出 `expectedStage` 与证据路径。

## 5. 与 Synthea 夹具

- 读取 `synthea_hcc.tace_session.actual_exec_end_t0`、`followup_mrecist.visit_date`、`hcc_diagnosis.*`；
- Golden：固定 `--seed 20261119` 的 100 人队列中选取多程/冲突样例断言；
- **不**要求本 change 生成脏数据注入器（可手工夹具或后续 D3/D4 脚本）。

## 6. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 窗口配置过宽导致 AMBIGUOUS | 默认保守窗 + 显式歧义码，禁止自动择优 |
| 规则包写死表名 | 强制经语义映射 URN |
| 与 analysis-hardening 统计算子重叠 | 本 change 只做对齐/质控；KM/回归留给统计执行层或看板 change |
