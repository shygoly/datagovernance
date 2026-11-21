# ETL 产出的治理消费落地:目标表 → 语义映射 / 质检桩 / 报告

> 状态:research(未经审校,不是权威知识)
> 关联:`2026-11-20-clinical-multisource-etl-lineage-enrichment.md` §3.E、`scripts/seed-etl-governance-consumption.py`

## 1. 目标表与回落

物理 STD/DWS 表目前**不在目录里**(截至本记录,三个仓的目录只有 `v_*_std` 视图与原始表)。落地脚本按「物理表优先、视图回落」解析,回落时映射口径不变,物化后重跑即切到物理表。

| ETL 作业(研究清单 A/B 类) | 优先物理目标 | 目录里现有回落 |
|---|---|---|
| `etl_mimic_icu_stay_std` | `mimiciv_icu.icu_stay_std` | `mimiciv_icu.v_icu_stay_std` |
| `etl_mimic_lab_std` | `mimiciv_hosp.lab_std` | `mimiciv_hosp.v_lab_std` |
| `etl_mimic_diagnosis_std` | `mimiciv_hosp.diagnosis_std` | `mimiciv_hosp.v_diagnosis_std` |
| `etl_eicu_*_std` | `eicu_crd.{icu_stay,lab,diagnosis}_std` | `eicu_crd.v_{icu_stay,lab,diagnosis}_std` |
| `etl_synthea_followup_outcome` | `synthea_hcc.dws_hcc_orr_by_session` | `synthea_hcc.followup_mrecist` |

## 2. 语义映射(STD 列 → 已发布对象)

`attr.patient.gender ← gender_std`(正是 ETL 码表映射节点的产出)、`fact.icu.los_hours` / `metric.icu.avg_los_hours ← icu_los_hours`、`entity.lab_event` / `attr.lab.item_name` / `fact.lab.result_value ← lab_event_id / lab_name / lab_value_num`、`attr.diagnosis.icd_code ← icd_code`、`attr.followup.mrecist_response` / `metric.hcc.orr_rate ← mrecist_response`。

**观察**:`seed-clinical-multisource.py` 已经在这些 `v_*_std` 视图上建过同口径映射,所以在回落模式下新脚本的映射步骤全部是「已存在」;它真正新增的价值在物理表出现之后(映射随目标切换)以及质检桩、报告、码集检查。

## 3. 质检桩与报告

- NOT_NULL 桩:`lab_value_num`(maxNullRatio 0.4,MEDIUM,mimic / eicu)、`mrecist_response`(0.05,HIGH)。创建时不运行(`--run-rules` 才运行)。阈值是占位值,**不是**基于数据分布得出的结论。
- 分析报告 subject=`DATASET`,correlationId = `etl-gov-landing:<datasetId>`(幂等),证据为 CATALOG 引用;正文只登记「哪些治理对象挂在该数据集上」,不含指标取值或明细。`model` 字段如实填脚本自身(确定性脚本,不是 LLM)。

## 4. 码集

ETL `TRANSFORM_CODESET_LOOKUP` 引用语义对象(如 `attr.patient.gender`)的值字典;脚本检查它已发布且字典非空,并打印可粘贴的 IR 节点示例。

## 5. 血缘加厚（与本脚本分工）

`seed-etl-governance-consumption.py` **不会**增加 `activeColumnEdges`：它只挂语义映射 / 质检 / 报告。
若 UI 上「字段数 / 深度」不变，请先跑：

```bash
python3 scripts/seed-etl-lineage-enrichment.py
python3 scripts/seed-etl-governance-consumption.py
```

enrichment 脚本按 A/D/E 类写入 `ETL_PIPELINE` 血缘作业（源表 → `v_*_std` 多跳、脱敏、跨仓 lab 桥）。
2026-11-21 实测：overview `18 → 65` 边、`5 → 14` 作业；`lab_value_num` impact 出现 `QUALITY_RULE`。

## 6. Non-goals

- 不创建物理 STD/DWS 表,也不创建 ETL 作业定义（enrichment 只写血缘作业元数据，不跑 DuckDB）。
- 不运行质检规则(默认),不对阈值合理性下结论。
