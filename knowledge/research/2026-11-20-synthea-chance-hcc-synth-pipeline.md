# Synthea（GMF + 导出扩展）× LLM 提示词 × 可控脏数据 — 研究草稿

> **状态**：Research Staging（未晋升 `articles/`）  
> **日期**：2026-11-20  
> **范围（本轮已落地）**：设计沉淀 + 与 MIMIC/eICU 并列的第三仓种子（`idNamespacePrefix=synthea:`）  
> **非目标**：不把 CHANCE / MIMIC / Synthea Schema 写死进平台内核；不本轮跑通真实 GMF Gradle / LLM / 脏数据注入执行器

---

## 1. 为何需要 Synthea 融合层

MITRE Synthea（Apache 2.0，JDK 17+，`-s` 固定种子，`synthea.properties` 切换 FHIR R4 / Bulk ndjson / CSV / C-CDA）擅长纵向病程时序，但单独支撑 CHANCE 肝癌专病治理原型有四项边界：

| 维度 | Synthea 强项 | CHANCE 缺口 | 对策 |
|---|---|---|---|
| 流行病学 | MA 人口镜像 | 中国高 HBV / 高肿瘤负荷 | 自定义 CHANCE GMF 改写转移概率 |
| 字段粒度 | 标准 FHIR/CSV | CNLC、TACE 栓塞参数、mRECIST、PIVKA-II、CTCAE 等 | GMF `SetAttribute`/`Counter` + 导出扩展 + LLM 结构化增强 |
| 中文非结构化 | 仅英文结构化 | 中文影像/手术/随访文本与 PHI 脱敏压测 | 先采样结构化真值，再受限生成文本 |
| 脏度 | 过于干净 | 需演示识别→标记→复检与同义异名陷阱 | 独立可控脏数据注入器 + Ground Truth |

## 2. 四阶段混合造数流水线

1. **GMF 骨干模拟**：固定种子驱动肝癌主/子模块，产出 HBV/肝硬化、CNLC、多程 TACE/靶免、随访生存骨架。  
2. **FHIR/CSV 导出扩展与拆表**：清洗 US Core 专有扩展与真实 NPI，替换为 `CHANCE-SYNTH-CENTER-01..05`；按 HIS / EMR / CATH / PACS / LIS / CRM 拆 6 域源表（60+ 模块骨架靶点）。  
3. **大模型分层条件增强**：锚定阶段 1–2 真值 JSON，补细粒度字段与中文报告；确定性生理学/公式脚本复检（ALBI、CNLC 自洽等）。  
4. **可控脏数据注入**：Clean Baseline → D1–D7 配额污染；双路输出：带噪源库（治理步骤 1）与 `ground_truth_manifest.jsonl`（A0.4）。

## 3. GMF 核心自定义属性（摘要）

`hcc_etiology`、`hcc_child_pugh_class` / `hcc_albi_score`、`hcc_tumor_count` / `hcc_max_diameter_cm`、`hcc_pvtt_grade` / `hcc_extrahepatic_mets`、`hcc_cnlc_stage`、`hcc_tace_cycle_count`、`hcc_tace_modality`、`hcc_systemic_regimen`、`hcc_last_mrecist` / `hcc_consecutive_pd_count`、`hcc_tace_refractory` / `hcc_lost_to_followup`。

主模块骨架文件名建议：`chance_hcc_interventional_followup.json`；子模块：`chance_hcc/baseline_staging_and_labs`、`chance_hcc/mrecist_and_ctcae_eval`。落地前对目标 Synthea 发行版执行 `./gradlew check` / `graphviz`。

CLI 示例：`./run_synthea -s 20261119 -p 300 -a 40-80 -d ./custom_modules -c ./chance_synthea.properties`。

## 4. 缺陷谱系 D1–D7（配额见提案原文）

| 码 | 类 | 治理检验点 |
|---|---|---|
| D1 | 同义异名 / 同名异义（含 treat_dt 计费陷阱） | AI 映射 + 人工复核 |
| D2 | 单位混用（cm/mm、µmol/L vs mg/dL、AFP 截断串） | 单位归一化算子 |
| D3 | 时间轴倒挂 | SHACL/时序约束 |
| D4 | CNLC / Child-Pugh / ALBI 逻辑冲突 | 分期校验算子 |
| D5 | ICD 缺失与口语药名（双艾 / T+A） | 术语对齐技能 |
| D6 | MNAR 缺失与双主索引 | 完整性 / EMPI |
| D7 | 文本隐匿 PHI（仅虚构号段 + `[SYNTH_PHI]`） | 脱敏与发布阻断 |

真值行字段：`defect_id`、`defect_class`、`source_system`、`record_pk`、`field_name`、`clean_value`、`injected_dirty_value`、`expected_shacl_rule`、`expected_remediation`（及 PHI span）。

## 5. 五套 Prompt 工程（仅契约摘要）

均遵循：锚定 Synthea 真值 → 强结构化 JSON → 临床公式硬约束 → 禁止真实人物信息。

1. **60+ 模块细粒度扩展器**（CNLC/ALBI/mRECIST 约束）  
2. **中文影像报告 + 导管室记录生成器**（`STYLE_PROFILE`）  
3. **多中心异构元数据 + mapping_ground_truth**  
4. **脏数据注入 + ground_truth_manifest**  
5. **AI 辅助映射/质控建议**（只读脱敏画像；读 `maintenance-log`；路由 fast_track / HITL / escalate）

完整模板正文保留在提案源文档；晋升 articles 前需再审校。

## 6. 与平台联邦的落地约定（本轮实现）

对齐已有 MIMIC / eICU 种子模式（`scripts/seed-clinical-multisource.py`）：

| 项 | 值 |
|---|---|
| PostgreSQL 实例 | **`mac@localhost:5432`**（`SYNTHEA_PG_USER`/`SYNTHEA_PG_HOST`/`SYNTHEA_PG_PORT`） |
| PostgreSQL database | `synthea`（OWNER=`mac`）；对平台凭据 `datagov` `GRANT CONNECT` + schema 只读 |
| Schema | `synthea_hcc` |
| DataSource 名 | `Synthea CHANCE-HCC 合成仓` |
| Warehouse `code` | `synthea_hcc` |
| `cohortCode` | `SYNTHEA_HCC` |
| `idNamespacePrefix` | **`synthea:`** |
| `combineMode` | `COHORT_UNION` |
| `federationEngine` | `DUCKDB` |

互斥队列：`mimic:` ∩ `eicu:` ∩ `synthea:` = ∅；跨仓 `COUNT(DISTINCT patient_id)` **必须**带命名空间前缀。Synthea 为合成队列，禁止与 MIMIC/eICU 做无前缀主键对齐。

专病表（**不造** MIMIC 已有的 admissions/icustays/lab/rx 等）：

- `cohort_patient` → 队列锚点（`synthea:`）  
- `hcc_diagnosis` / `tace_session` / `followup_mrecist` / `imaging_report_zh`  

生成器：

```bash
python3 scripts/seed/gen_synthea_hcc_cohort.py --n 100 --seed 20261119
python3 scripts/seed/gen_synthea_hcc_cohort.py --n 100 --llm   # 可选；需 OPENAI_API_KEY 或 DG_LLM_API_KEY
# 或
python3 scripts/seed/provision_synthea_hcc_stubs.py            # 默认 n=100
```

`seed-clinical-multisource.py` 在 MIMIC/eICU 之后挂载第三仓；失败则跳过。

## 7. 关联 openspec changes（Draft）

| Change | 路径 | 说明 |
|---|---|---|
| T₀ 对齐 + 专病 QC | `openspec/changes/chance-t0-alignment-and-qc-rules/` | ①+② |
| AI 映射 → 收件箱 | `openspec/changes/chance-ai-mapping-inbox/` | ③（可并行） |
| 三张专病看板 | `openspec/changes/chance-specialty-analytics-boards/` | ④（依赖 T₀） |

## 8. Constitution / Non-goals 检查

- **C9**：本文仅 `research/`；GMF/Prompt 全文未写入 `articles/`。  
- **平台内核**：行业 Schema 经种子与 Standard/Skill 插件承载，不进入 `dg-*` 硬编码。  
- **后续**：真实 Synthea 跑数、D1–D7 注入器、上述三 change 实现与 A0.4 用例。
