# MIMIC / eICU / Synthea 三库可落地 ETL 与血缘加厚调研

> 状态：research  
> 对照目录：MIMIC-IV（34 表）、eICU-CRD（14 表）、Synthea CHANCE-HCC（5 表）  
> 目的：在现有 `v_*_std` 与演示血缘之上，规划能**显著加厚字段级/表级血缘图**的 ETL 清单

## 1. 现状与缺口

| 仓 | 已有利于血缘的资产 | 缺口 |
|---|---|---|
| MIMIC | `v_icu_stay_std` / `v_diagnosis_std` / `v_lab_std`；hosp+icu 宽表 | `v_*` 多半缺自动 VIEW 血缘边；lab/药/转科链路未贯通 |
| eICU | 同构 `v_*_std`；lab/vital/用药 | 偏移时间轴未落到「绝对时刻」中间表；与 MIMIC 的 std 对齐作业未落库 |
| Synthea | 专病星型（患者→诊断→程次→随访→影像） | 缺「宽表/主题表」；ORR/程次指标物化表缺失；影像文本未衍生结构化字段 |

当前演示血缘约 18 条边、5 作业，多为主键 COPY。要「丰富」，需要：**多跳 TRANSFORM、字典维表 JOIN、脱敏、跨仓 COHORT 对齐、以及落到语义 METRIC 的消费边**。

## 2. ETL 优先原则（对血缘友好）

1. **每条作业尽量产出可解析 SQL 或显式 `fieldMappings`** → 自动 PLANNED/ACTIVE 边 + SQLGlot 表达式。  
2. **优先做「标准层 / 主题层」表**（新物理表或 VIEW），不要只改内存查询。  
3. **刻意留下脱敏/哈希步骤**（如 `subject_id → patient_token`）以演示 `masking_applied` 与敏感传播阻断。  
4. **跨仓用命名空间前缀**（`mimic:` / `eicu:` / `synthea:`），血缘用 INDIRECT/DERIVE 标「对齐桥」而非假装同一主键。

## 3. 推荐 ETL 清单（按血缘收益排序）

### A. 仓内 ODS→STD 标准化（高收益 / 低风险）

这些与已有 `v_*_std` 同构，建议**物化为表**或确保 VIEW 定义被 Catalog 采集，血缘从源列 → std 列自动生成。

| 作业名 | 源 → 目标 | 关键列级映射 | 血缘看点 |
|---|---|---|---|
| `etl_mimic_icu_stay_std` | `patients`⋈`admissions`⋈`icustays` → `mimiciv_icu.icu_stay_std` | `subject_id→patient_id`, `hadm_id→admission_id`, `stay_id→icu_stay_id`, `gender→gender_std`, `los*24→los_hours` | 多表 JOIN + 单位换算 TRANSFORM |
| `etl_mimic_diagnosis_std` | `diagnoses_icd`⋈`d_icd_diagnoses` → `diagnosis_std` | `icd_code`, `long_title→diagnosis_name`, `seq_num→priority_rank` | **维表 JOIN**（字典→事实） |
| `etl_mimic_lab_std` | `labevents`⋈`d_labitems` → `lab_std` | `itemid→lab_name`, `valuenum→lab_value_num`, `flag→abnormal_flag` | 宽事实 + 字典；INDIRECT：`hadm_id` 过滤 |
| `etl_eicu_icu_stay_std` | `patient` → `icu_stay_std` | `uniquepid/patienthealthsystemstayid/patientunitstayid` 三角映射；`age` 解析；出院状态 | eICU 特有偏移字段需文档化 |
| `etl_eicu_lab_std` | `lab` → `lab_std` | `labname/labresult`；`labresultoffset` 保留 | 与 MIMIC lab_std 同构，支撑跨仓 COHORT_UNION |
| `etl_eicu_diagnosis_std` | `diagnosis` → `diagnosis_std` | `icd9code→icd_code`, `diagnosisstring→diagnosis_name` | 字符串清洗 TRANSFORM |

### B. 主题宽表 / 专病路径（中高收益）

| 作业名 | 源 → 目标 | 说明 | 血缘看点 |
|---|---|---|---|
| `etl_mimic_admission_spine` | patients+admissions+transfers → `dwd_admission_spine` | 住院主链：入出院、转科、死亡 | 表级星型中心；下游多事实挂接 |
| `etl_mimic_med_admin` | `emar`⋈`emar_detail`⋈`pharmacy` → `dwd_med_admin` | 用药执行事实 | 长 TRANSFORM 链；可挂 `entity.medication` |
| `etl_eicu_vitals_1h` | `vitalperiodic` → `dws_vital_1h` | 按 stay+小时聚合 HR/SpO2/RR | 聚合：源多列 → 目标多度量；INDIRECT 时间窗 |
| `etl_synthea_session_wide` | cohort⋈diagnosis⋈tace → `dwd_hcc_session_wide` | 程次宽表：CNLC、Child-Pugh、modality、T0 | 专病路径主干 |
| `etl_synthea_followup_outcome` | tace⋈followup → `dws_hcc_orr_by_session` | 程次级 ORR（CR+PR） | **指标物化**：喂给 `metric.hcc.orr_rate` |
| `etl_synthea_imaging_nlp_stub` | `imaging_report_zh` → `dwd_imaging_struct` | 从报告抽「病灶数/最大径」占位列 | 文本→结构；低置信度 + INCONCLUSIVE 可演示 |

### C. 跨仓联邦对齐（血缘「故事性」最强）

| 作业名 | 内容 | 血缘看点 |
|---|---|---|
| `etl_cohort_patient_bridge` | 三仓患者键 → `bridge_global_patient`（仅命名空间前缀，禁止无前缀 JOIN） | INDIRECT DERIVE；C5 跨仓影响分析入口 |
| `etl_unified_lab_fact` | MIMIC `lab_std` ∪ eICU `lab_std` → `dws_lab_union`（COHORT_UNION） | 水平联合；`idNamespacePrefix`；AVG→sum/cnt 充分统计 |
| `etl_unified_icu_los` | 两仓 `icu_stay_std` → `dws_icu_los_union` | 支撑 `metric.icu.avg_los_hours` 跨仓 |
| `etl_hcc_vs_icu_overlap_demo` | Synthea 专病属性 **不与** MIMIC ICU 混 grain；仅做「研究队列标签」旁表 | 用血缘表达 **禁止错误 JOIN** 的边界（文档边 / 低置信度） |

### D. 脱敏与合规演示（专门加厚敏感血缘）

| 作业名 | 映射 | 血缘看点 |
|---|---|---|
| `etl_mimic_patient_token` | `subject_id → sha256(subject_id\|\|salt)` 写入 `ods_patient_masked` | `masking_applied=true`；阻断 L3/L4 向下游传播 |
| `etl_export_deid_admission` | admissions 去标识导出列子集 | MANUAL/导出作业；敏感 SUGGESTED 复核闭环 |

### E. 语义/治理消费（Phase 3 画布变「活」）

物化或 VIEW 稳定后：

1. 把 STD/DWS 列 **映射到已发布** `entity.*` / `metric.*`（已有种子）。  
2. 质检 Rule 挂在 `lab_std.lab_value_num`、`followup.mrecist_response` 等。  
3. 分析报告 subject=DATASET 指向 `dws_hcc_orr_by_session`。  

→ 影响分析会出现 METRIC / RULE / REPORT 治理节点，血缘不再停在物理表。

## 4. 建议落地顺序（2～3 周可感知变厚）

1. **Week 1**：A 类 —— 把已有 `v_*_std` 的 VIEW 血缘采全 + 补 MIMIC lab/diagnosis 字典 JOIN 作业（SQL 导入或 BatchDev）。  
2. **Week 1**：D 类一条脱敏作业 —— 立刻能演示敏感传播阻断。  
3. **Week 2**：B 类 Synthea `session_wide` + `orr_by_session` —— 专病路径多跳 + 指标物化。  
4. **Week 2**：C 类 `lab_union` / `icu_los_union` —— 表级图出现跨仓边。  
5. **Week 3**：E 类映射与质检/报告挂载 —— 治理节点进入影响分析。

## 5. 与平台能力的对应

| ETL 形态 | 平台采集通道 |
|---|---|
| OfflineSync / 字段映射 | `StructuredJobLineageExtractor` → PLANNED/ACTIVE |
| BatchDev/StreamingDev SQL | `SqlLineageParserClient` + SQLGlot |
| DB VIEW/MV | Catalog 刷新 VIEW 定义 |
| 脱敏规则 | `fieldRules` / transform 中 MASK/SHA → `masking_applied` |
| 跨仓 | Warehouse `COHORT_UNION` + bridge 作业边 |

## 6. Non-goals

- 不在 ETL 中无前缀硬拼三仓患者主键。  
- 不把指南 PDF/影像全文落入血缘节点。  
- 不要求一次上齐全部 MIMIC 表；优先 std/主题层与专病路径。
