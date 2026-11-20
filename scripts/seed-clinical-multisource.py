#!/usr/bin/env python3
"""一键端到端配置 MIMIC-IV + eICU-CRD 临床多仓库、数据目录、逻辑表、全套 GLOBAL 临床语义本体、
跨仓异构物理映射、知识图谱关系边与标准化值字典。

完全幂等，可反复执行。

用法:
  python3 scripts/seed-clinical-multisource.py
  # 可选覆盖环境变量: DG_BASE_URL / DG_USERNAME / DG_PASSWORD / PG_HOST / PG_PORT / PG_USER / PG_PASSWORD
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from typing import Any

BASE_URL = os.environ.get("DG_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
USERNAME = os.environ.get("DG_USERNAME", "admin")
PASSWORD = os.environ.get("DG_PASSWORD", "admin123")
PG_HOST = os.environ.get("PG_HOST", "127.0.0.1")
PG_PORT = int(os.environ.get("PG_PORT", "5432"))
PG_USER = os.environ.get("PG_USER", "datagov")
PG_PASSWORD = os.environ.get("PG_PASSWORD", "datagov")

# 禁用代理直连本机
for k in ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY"):
    os.environ.pop(k, None)
os.environ["NO_PROXY"] = "*"
os.environ["no_proxy"] = "*"


class Client:
    def __init__(self, base_url: str, token: str = "", workspace_id: str = "") -> None:
        self.base_url = base_url
        self.token = token
        self.workspace_id = workspace_id

    def call(self, method: str, path: str, body: Any = None) -> Any:
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if self.workspace_id:
            headers["X-Workspace-Id"] = self.workspace_id
        req = urllib.request.Request(f"{self.base_url}{path}", data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
                if not payload.get("success"):
                    raise RuntimeError(f"{method} {path} failed: {payload}")
                return payload.get("data")
        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"{method} {path} HTTP {e.code}: {err_body}") from e


def ensure_credential(cli: Client) -> str:
    creds = cli.call("GET", "/api/v1/credentials")
    for c in creds:
        if c["name"] == "pg-clinical-readonly":
            print(f"[SKIP] credential exists: {c['id']}")
            return c["id"]
    created = cli.call("POST", "/api/v1/credentials", {
        "name": "pg-clinical-readonly",
        "authType": "PASSWORD",
        "username": PG_USER,
        "secret": PG_PASSWORD,
        "description": "临床数据库（MIMIC-IV / eICU-CRD）只读访问凭据",
    })
    print(f"[OK] created credential pg-clinical-readonly -> {created['id']}")
    return created["id"]


def ensure_datasource(cli: Client, cred_id: str, name: str, db_name: str, desc: str) -> str:
    page = cli.call("GET", "/api/v1/datasources?page=1&size=100")
    existing = next((d for d in page.get("records", []) if d["name"] == name), None)
    if existing:
        ds_id = existing["id"]
        print(f"[SKIP] datasource exists: {name} -> {ds_id} ({existing['status']})")
    else:
        created = cli.call("POST", "/api/v1/datasources", {
            "name": name,
            "type": "POSTGRESQL",
            "description": desc,
            "host": PG_HOST,
            "port": PG_PORT,
            "databaseName": db_name,
            "username": PG_USER,
            "credentialId": cred_id,
        })
        ds_id = created["id"]
        print(f"[OK] created datasource {name} -> {ds_id}")

    test_res = cli.call("POST", f"/api/v1/datasources/{ds_id}/test")
    if not (test_res.get("success") or test_res.get("reachable")):
        raise RuntimeError(f"datasource {name} not reachable: {test_res}")
    print(f"[OK] tested datasource {name}: connected ({test_res.get('latencyMillis') or test_res.get('latencyMs')}ms, {test_res.get('serverVersion')})")
    return ds_id


def ensure_catalog_and_refresh(cli: Client, ds_id: str, source_name: str) -> dict[str, dict[str, Any]]:
    sources = cli.call("GET", "/api/v1/metadata/catalog/sources")
    existing = next((s for s in sources if s["name"] == source_name), None)
    if existing:
        src_id = existing["id"]
        print(f"[SKIP] catalog source exists: {source_name} -> {src_id}")
    else:
        created = cli.call("POST", "/api/v1/metadata/catalog/sources", {
            "name": source_name,
            "sourceType": "JDBC",
            "dataSourceId": ds_id,
        })
        src_id = created["id"]
        print(f"[OK] created catalog source {source_name} -> {src_id}")

    ref = cli.call("POST", f"/api/v1/metadata/catalog/sources/{src_id}/refresh")
    print(f"[OK] refreshed catalog {source_name}: {ref}")

    page = cli.call("GET", f"/api/v1/metadata/catalog/sources/{src_id}/datasets?page=1&size=200")
    by_qname: dict[str, dict[str, Any]] = {}
    for ds in page.get("records", []):
        qname = (ds.get("qualifiedName") or ds.get("name") or "").lower()
        by_qname[qname] = ds
    print(f"[OK] catalog {source_name} indexed {len(by_qname)} datasets")
    return by_qname


def find_dataset(ds_map: dict[str, dict[str, Any]], suffix: str) -> dict[str, Any]:
    s = suffix.lower()
    if s in ds_map:
        return ds_map[s]
    for k, v in ds_map.items():
        if k.endswith("." + s) or k == s or k.endswith(s):
            return v
    raise KeyError(f"dataset matching '{suffix}' not found in {sorted(ds_map.keys())[:15]}...")


def get_columns_map(cli: Client, dataset_id: str) -> dict[str, str]:
    cols = cli.call("GET", f"/api/v1/metadata/catalog/datasets/{dataset_id}/columns")
    return {c["name"].lower(): c["id"] for c in cols}


def ensure_warehouse(cli: Client, payload: dict[str, Any]) -> str:
    whs = cli.call("GET", "/api/v1/metadata/semantic/warehouses")
    existing = next((w for w in whs if w["code"].lower() == payload["code"].lower()), None)
    if existing:
        payload = dict(payload, id=existing["id"])
    saved = cli.call("POST", "/api/v1/metadata/semantic/warehouses", payload)
    print(f"[OK] warehouse {saved['code']} -> {saved['id']} ({saved['combineMode']} / {saved['federationEngine']})")
    return saved["id"]


def ensure_logical_table(cli: Client, wh_id: str, ds_id: str, logical_name: str,
                         table_role: str, logical_size: int) -> str:
    lts = cli.call("GET", f"/api/v1/metadata/semantic/logical-tables?warehouseId={wh_id}")
    existing = next((t for t in lts if t["logicalName"].lower() == logical_name.lower()), None)
    payload: dict[str, Any] = {
        "warehouseId": wh_id,
        "catalogDatasetId": ds_id,
        "logicalName": logical_name,
        "tableRole": table_role,
        "logicalSize": logical_size,
    }
    if existing:
        payload["id"] = existing["id"]
    saved = cli.call("POST", "/api/v1/metadata/semantic/logical-tables", payload)
    return saved["id"]


CLINICAL_OBJECTS: list[dict[str, Any]] = [
    # ── Entities (6) ─────────────────────────────────────────────────────
    {
        "code": "entity.patient",
        "name": "标准患者实体",
        "objectType": "ENTITY",
        "scope": "GLOBAL",
        "spec": {"primaryKey": "patient_id"},
        "aiContext": {
            "aliases": ["患者", "病人", "受试者", "Subject", "Patient"],
            "grain": "每个实体代表一名唯一患者；跨 MIMIC-IV 与 eICU-CRD 互斥队列自动注入命名空间前缀防碰撞",
        },
    },
    {
        "code": "entity.admission",
        "name": "住院就诊实体",
        "objectType": "ENTITY",
        "scope": "GLOBAL",
        "spec": {"primaryKey": "admission_id"},
        "aiContext": {
            "aliases": ["住院", "就诊", "入院记录", "Hospital Admission"],
            "grain": "每次完整住院就诊（MIMIC hadm_id / eICU patienthealthsystemstayid）",
        },
    },
    {
        "code": "entity.icu_stay",
        "name": "ICU 入科单元实体",
        "objectType": "ENTITY",
        "scope": "GLOBAL",
        "spec": {"primaryKey": "icu_stay_id"},
        "aiContext": {
            "aliases": ["ICU住院", "入科记录", "重症监护单元", "ICU Stay"],
            "grain": "单次 ICU 监护入科单元（MIMIC stay_id / eICU patientunitstayid）",
        },
    },
    {
        "code": "entity.diagnosis",
        "name": "临床诊断实体",
        "objectType": "ENTITY",
        "scope": "GLOBAL",
        "spec": {"primaryKey": "icd_code"},
        "aiContext": {
            "aliases": ["诊断", "疾病诊断", "ICD诊断", "Diagnosis"],
            "grain": "每次就诊或 ICU 入科记录下的标准 ICD 诊断条目",
        },
    },
    {
        "code": "entity.lab_event",
        "name": "实验室检验事件实体",
        "objectType": "ENTITY",
        "scope": "GLOBAL",
        "spec": {"primaryKey": "lab_event_id"},
        "aiContext": {
            "aliases": ["检验", "化验", "实验室检查", "Lab Event"],
            "grain": "单次实验室化验结果记录",
        },
    },
    {
        "code": "entity.medication",
        "name": "临床用药医嘱实体",
        "objectType": "ENTITY",
        "scope": "GLOBAL",
        "spec": {"primaryKey": "drug_name"},
        "aiContext": {
            "aliases": ["用药", "处方", "药物医嘱", "Medication"],
            "grain": "住院/ICU 期间开具的单条药物医嘱",
        },
    },
    # ── Attributes (8) ───────────────────────────────────────────────────
    {
        "code": "attr.patient.gender",
        "name": "患者性别",
        "objectType": "ATTRIBUTE",
        "scope": "GLOBAL",
        "standardRef": "STD-CLINICAL-GENDER-ISO5218",
        "spec": {"forms": [{"role": "CODE", "dataType": "STRING"}]},
        "aiContext": {
            "aliases": ["性别", "Sex", "Gender"],
            "safeEnumExamples": ["M", "F", "U"],
            "disambiguation": "标准化码值 M(男) / F(女) / U(未知)；eICU 原始 Male/Female 已标准化",
        },
    },
    {
        "code": "attr.patient.age_group",
        "name": "患者年龄段",
        "objectType": "ATTRIBUTE",
        "scope": "GLOBAL",
        "standardRef": "STD-CLINICAL-AGE-BAND-001",
        "spec": {"forms": [{"role": "CODE", "dataType": "STRING"}]},
        "aiContext": {
            "aliases": ["年龄段", "年龄分组", "Age Group"],
            "safeEnumExamples": ["<40", "40-64", "65-79", ">=80"],
            "disambiguation": "eICU 中 '> 89' 统一归入 >=80 岁高龄老年组（按 90 岁近似）",
        },
    },
    {
        "code": "attr.patient.race",
        "name": "患者族裔",
        "objectType": "ATTRIBUTE",
        "scope": "GLOBAL",
        "standardRef": "STD-CLINICAL-RACE-OMB",
        "spec": {"forms": [{"role": "CODE", "dataType": "STRING"}]},
        "aiContext": {
            "aliases": ["种族", "族裔", "民族", "Race", "Ethnicity"],
            "safeEnumExamples": ["WHITE", "BLACK", "HISPANIC", "ASIAN", "OTHER"],
        },
    },
    {
        "code": "attr.icu.unit_type",
        "name": "ICU 科室类型",
        "objectType": "ATTRIBUTE",
        "scope": "GLOBAL",
        "standardRef": "STD-CLINICAL-ICU-UNIT-001",
        "spec": {"forms": [{"role": "CODE", "dataType": "STRING"}]},
        "aiContext": {
            "aliases": ["ICU类型", "重症科室", "监护室类型", "Care Unit", "Unit Type"],
            "safeEnumExamples": ["MICU", "SICU", "MICU_SICU", "CARDIAC_ICU", "NEURO_ICU", "TRAUMA_ICU"],
            "disambiguation": "跨 MIMIC first_careunit 与 eICU unittype 统一映射至 6 大标准 ICU 类别",
        },
    },
    {
        "code": "attr.outcome.hospital_status",
        "name": "住院转归结局",
        "objectType": "ATTRIBUTE",
        "scope": "GLOBAL",
        "standardRef": "STD-CLINICAL-OUTCOME-001",
        "spec": {"forms": [{"role": "CODE", "dataType": "STRING"}]},
        "aiContext": {
            "aliases": ["住院结局", "出院状态", "存活状态", "死亡结局", "Hospital Outcome"],
            "safeEnumExamples": ["ALIVE", "EXPIRED", "UNKNOWN"],
        },
    },
    {
        "code": "attr.diagnosis.icd_code",
        "name": "ICD 诊断编码",
        "objectType": "ATTRIBUTE",
        "scope": "GLOBAL",
        "standardRef": "STD-WHO-ICD-9-10",
        "spec": {"forms": [{"role": "CODE", "dataType": "STRING"}]},
        "aiContext": {"aliases": ["ICD编码", "诊断代码", "ICD"]},
    },
    {
        "code": "attr.lab.item_name",
        "name": "检验项目名称",
        "objectType": "ATTRIBUTE",
        "scope": "GLOBAL",
        "standardRef": "STD-CLINICAL-LOINC-LOCAL",
        "spec": {"forms": [{"role": "DESC", "dataType": "STRING"}]},
        "aiContext": {"aliases": ["化验项目", "检验名", "指标项", "Lab Name"]},
    },
    {
        "code": "attr.medication.drug_name",
        "name": "药品名称",
        "objectType": "ATTRIBUTE",
        "scope": "GLOBAL",
        "standardRef": "STD-CLINICAL-RXNORM-LOCAL",
        "spec": {"forms": [{"role": "DESC", "dataType": "STRING"}]},
        "aiContext": {"aliases": ["药名", "药物名称", "处方药", "Drug"]},
    },
    # ── Dimension with Hierarchy (1) ─────────────────────────────────────
    {
        "code": "dim.clinical.icu_unit",
        "name": "临床科室分析维度",
        "objectType": "DIMENSION",
        "scope": "GLOBAL",
        "spec": {
            "isTimeDimension": False,
            "hierarchy": ["attr.icu.unit_type", "attr.patient.gender", "attr.patient.age_group"],
        },
        "aiContext": {
            "aliases": ["重症科室维度", "ICU层级"],
            "disambiguation": "支持图表 L2 零 LLM 钻取路径：ICU 科室类型 → 患者性别 → 患者年龄段",
        },
    },
    # ── Facts (3) ────────────────────────────────────────────────────────
    {
        "code": "fact.icu.los_hours",
        "name": "ICU 住院时长事实",
        "objectType": "FACT",
        "scope": "GLOBAL",
        "spec": {"unit": "小时", "dataType": "DOUBLE"},
        "aiContext": {
            "aliases": ["ICU停留小时数", "ICU Length of Stay"],
            "grain": "单次 ICU 入科的标准化停留小时数（MIMIC 天×24 / eICU 分÷60）",
        },
    },
    {
        "code": "fact.hospital.los_hours",
        "name": "总住院时长事实",
        "objectType": "FACT",
        "scope": "GLOBAL",
        "spec": {"unit": "小时", "dataType": "DOUBLE"},
        "aiContext": {"aliases": ["住院小时数", "Hospital LOS"]},
    },
    {
        "code": "fact.lab.result_value",
        "name": "实验室检验数值事实",
        "objectType": "FACT",
        "scope": "GLOBAL",
        "spec": {"dataType": "DOUBLE"},
        "aiContext": {"aliases": ["检验数值", "化验值", "Lab Value"]},
    },
    # ── Metrics (7, all C2 Standard bound) ───────────────────────────────
    {
        "code": "metric.patient.count",
        "name": "去重患者数",
        "objectType": "METRIC",
        "scope": "GLOBAL",
        "standardRef": "STD-CLINICAL-PAT-CNT-001",
        "spec": {
            "metricKind": "BASE",
            "aggregation": "COUNT_DISTINCT",
            "unit": "人",
            "allowedDimensions": [
                "attr.system.cohort", "attr.patient.gender", "attr.patient.age_group",
                "attr.patient.race", "attr.icu.unit_type", "attr.outcome.hospital_status",
            ],
        },
        "aiContext": {
            "aliases": ["患者数", "病人数", "队列人数", "总患者数"],
            "grain": "去重患者数，跨 MIMIC/eICU 互斥队列自动注入 mimic:/eicu: 命名空间前缀防碰撞",
        },
    },
    {
        "code": "metric.icu.stay_count",
        "name": "ICU 入科人次",
        "objectType": "METRIC",
        "scope": "GLOBAL",
        "standardRef": "STD-CLINICAL-ICU-STAY-001",
        "spec": {
            "metricKind": "BASE",
            "aggregation": "COUNT_DISTINCT",
            "unit": "人次",
            "allowedDimensions": [
                "attr.system.cohort", "attr.patient.gender", "attr.patient.age_group",
                "attr.patient.race", "attr.icu.unit_type", "attr.outcome.hospital_status",
            ],
        },
        "aiContext": {
            "aliases": ["入科次数", "ICU人次数", "重症收治人次", "ICU Stays"],
            "grain": "按唯一 ICU 入科事件计数（一名患者可能多次入住 ICU）",
        },
    },
    {
        "code": "metric.icu.avg_los_hours",
        "name": "平均 ICU 住院时长",
        "objectType": "METRIC",
        "scope": "GLOBAL",
        "standardRef": "STD-CLINICAL-ICU-LOS-001",
        "spec": {
            "metricKind": "BASE",
            "aggregation": "AVG",
            "unit": "小时",
            "allowedDimensions": [
                "attr.system.cohort", "attr.patient.gender", "attr.patient.age_group",
                "attr.patient.race", "attr.icu.unit_type", "attr.outcome.hospital_status",
            ],
        },
        "aiContext": {
            "aliases": ["ICU时长", "平均住ICU时间", "ICU平均停留时长", "Average ICU LOS"],
            "grain": "单位统一为小时；跨仓联合时采用 SUM/COUNT 两阶段精确加权平均",
            "caveats": ["MIMIC 原始单位为天（los*24），eICU 原始单位为分钟（unitdischargeoffset/60）"],
        },
    },
    {
        "code": "metric.icu.total_los_hours",
        "name": "累计 ICU 监护总时长",
        "objectType": "METRIC",
        "scope": "GLOBAL",
        "standardRef": "STD-CLINICAL-ICU-TOTLOS-001",
        "spec": {
            "metricKind": "BASE",
            "aggregation": "SUM",
            "unit": "小时",
            "allowedDimensions": ["attr.system.cohort", "attr.icu.unit_type", "attr.patient.gender"],
        },
        "aiContext": {"aliases": ["ICU总时长", "累计监护小时"]},
    },
    {
        "code": "metric.hospital.expired_count",
        "name": "院内死亡人次",
        "objectType": "METRIC",
        "scope": "GLOBAL",
        "standardRef": "STD-CLINICAL-MORT-CNT-001",
        "spec": {
            "metricKind": "BASE",
            "aggregation": "SUM",
            "unit": "人次",
            "allowedDimensions": [
                "attr.system.cohort", "attr.patient.gender", "attr.patient.age_group",
                "attr.icu.unit_type", "attr.patient.race",
            ],
        },
        "aiContext": {"aliases": ["死亡数", "院内死亡人数", "死亡例数", "Expired Count"]},
    },
    {
        "code": "metric.hospital.mortality_rate",
        "name": "院内病死率",
        "objectType": "METRIC",
        "scope": "GLOBAL",
        "standardRef": "STD-CLINICAL-MORT-RATE-001",
        "spec": {
            "metricKind": "BASE",
            "aggregation": "AVG",
            "unit": "%",
            "allowedDimensions": [
                "attr.system.cohort", "attr.patient.gender", "attr.patient.age_group",
                "attr.icu.unit_type", "attr.patient.race",
            ],
        },
        "aiContext": {
            "aliases": ["病死率", "院内死亡率", "死亡率", "Hospital Mortality Rate"],
            "grain": "院内死亡标识（0/1）×100 求均值，跨队列两阶段 SUM/COUNT 精确加权汇总",
        },
    },
    {
        "code": "metric.patient.avg_age",
        "name": "平均患者年龄",
        "objectType": "METRIC",
        "scope": "GLOBAL",
        "standardRef": "STD-CLINICAL-AVG-AGE-001",
        "spec": {
            "metricKind": "BASE",
            "aggregation": "AVG",
            "unit": "岁",
            "allowedDimensions": [
                "attr.system.cohort", "attr.patient.gender", "attr.icu.unit_type", "attr.outcome.hospital_status",
            ],
        },
        "aiContext": {"aliases": ["平均年龄", "患者均龄", "Mean Age"]},
    },
    # ── Knowledge Graph Concepts & Processes (4) ─────────────────────────
    {
        "code": "concept.clinical.icu_mortality",
        "name": "重症院内死亡转归",
        "objectType": "CONCEPT",
        "scope": "GLOBAL",
        "spec": {},
        "aiContext": {
            "aliases": ["死亡结局", "不良预后"],
            "disambiguation": "评估重症救治质量的核心结局概念，口径以 hospital_expired_flag=1 为准",
        },
    },
    {
        "code": "concept.clinical.elderly_cohort",
        "name": "老年高危重症人群（≥65岁）",
        "objectType": "CONCEPT",
        "scope": "GLOBAL",
        "spec": {},
        "aiContext": {
            "aliases": ["老年重症", "高龄患者"],
            "disambiguation": "包含 65-79 岁与 >=80 岁两个标准年龄段",
        },
    },
    {
        "code": "concept.clinical.disjoint_multicenter",
        "name": "多中心互斥队列约束（MIMIC ∩ eICU = ∅）",
        "objectType": "CONCEPT",
        "scope": "GLOBAL",
        "spec": {},
        "aiContext": {
            "aliases": ["多中心队列约束", "互斥队列"],
            "disambiguation": "MIMIC-IV 与 eICU-CRD 患者群体完全互斥，联合统计严禁 INNER JOIN，须走 COHORT_UNION + 命名空间前缀",
        },
    },
    {
        "code": "process.clinical.icu_care_pathway",
        "name": "重症临床诊疗全路径",
        "objectType": "PROCESS",
        "scope": "GLOBAL",
        "spec": {},
        "aiContext": {
            "aliases": ["ICU诊疗路径", "重症临床流程"],
            "disambiguation": "住院入院 → ICU 监护 → 检验/诊断/用药干预 → 出院/死亡转归",
        },
    },
]

SEMANTIC_LINKS: list[dict[str, Any]] = [
    # STRUCTURAL: Entity -> Attributes / Entities / Facts / Metrics
    {"from": "entity.patient", "to": "attr.patient.gender", "cat": "STRUCTURAL", "type": "HAS_ATTRIBUTE", "card": "1:1", "rationale": "患者基础人口学性别属性"},
    {"from": "entity.patient", "to": "attr.patient.age_group", "cat": "STRUCTURAL", "type": "HAS_ATTRIBUTE", "card": "1:1", "rationale": "患者标准化年龄分组属性"},
    {"from": "entity.patient", "to": "attr.patient.race", "cat": "STRUCTURAL", "type": "HAS_ATTRIBUTE", "card": "1:1", "rationale": "患者标准化族裔属性"},
    {"from": "entity.patient", "to": "entity.admission", "cat": "STRUCTURAL", "type": "RELATES_TO", "card": "1:N", "rationale": "一名患者可有多次住院就诊（MIMIC subject_id / eICU uniquepid）"},
    {"from": "entity.admission", "to": "entity.icu_stay", "cat": "STRUCTURAL", "type": "RELATES_TO", "card": "1:N", "rationale": "一次住院可包含一次或多次 ICU 入科单元"},
    {"from": "entity.icu_stay", "to": "attr.icu.unit_type", "cat": "STRUCTURAL", "type": "HAS_ATTRIBUTE", "card": "N:1", "rationale": "每次 ICU 入科归属标准重症科室类别"},
    {"from": "entity.icu_stay", "to": "attr.outcome.hospital_status", "cat": "STRUCTURAL", "type": "HAS_ATTRIBUTE", "card": "N:1", "rationale": "ICU 入科对应最终住院结局状态"},
    {"from": "entity.icu_stay", "to": "fact.icu.los_hours", "cat": "STRUCTURAL", "type": "HAS_FACT", "card": "1:1", "rationale": "每次 ICU 入科产出标准化的停留小时数事实"},
    {"from": "entity.admission", "to": "fact.hospital.los_hours", "cat": "STRUCTURAL", "type": "HAS_FACT", "card": "1:1", "rationale": "每次住院产出总住院时长事实"},
    {"from": "entity.admission", "to": "entity.diagnosis", "cat": "STRUCTURAL", "type": "RELATES_TO", "card": "1:N", "rationale": "一次住院/入科可登记多条 ICD 诊断"},
    {"from": "entity.diagnosis", "to": "attr.diagnosis.icd_code", "cat": "STRUCTURAL", "type": "HAS_ATTRIBUTE", "card": "1:1", "rationale": "诊断关联标准 ICD 编码"},
    {"from": "entity.icu_stay", "to": "entity.lab_event", "cat": "STRUCTURAL", "type": "RELATES_TO", "card": "1:N", "rationale": "ICU 监护期间产生多条实验室检验事件"},
    {"from": "entity.lab_event", "to": "attr.lab.item_name", "cat": "STRUCTURAL", "type": "HAS_ATTRIBUTE", "card": "N:1", "rationale": "检验事件对应具体化验项目"},
    {"from": "entity.lab_event", "to": "fact.lab.result_value", "cat": "STRUCTURAL", "type": "HAS_FACT", "card": "1:1", "rationale": "检验事件产出数值结果事实"},
    {"from": "entity.icu_stay", "to": "entity.medication", "cat": "STRUCTURAL", "type": "RELATES_TO", "card": "1:N", "rationale": "ICU 监护期间开具多条药物医嘱"},
    {"from": "entity.medication", "to": "attr.medication.drug_name", "cat": "STRUCTURAL", "type": "HAS_ATTRIBUTE", "card": "N:1", "rationale": "药物医嘱对应药品名称"},
    {"from": "metric.icu.avg_los_hours", "to": "fact.icu.los_hours", "cat": "STRUCTURAL", "type": "DERIVED_FROM", "card": "N:1", "rationale": "平均 ICU 住院时长基于标准化小时数事实两阶段加权聚合"},
    {"from": "metric.icu.total_los_hours", "to": "fact.icu.los_hours", "cat": "STRUCTURAL", "type": "DERIVED_FROM", "card": "N:1", "rationale": "累计 ICU 时长由时长事实求和得出"},
    {"from": "metric.patient.count", "to": "entity.patient", "cat": "STRUCTURAL", "type": "DERIVED_FROM", "card": "N:1", "rationale": "去重患者数统计患者实体唯一键"},
    {"from": "metric.icu.stay_count", "to": "entity.icu_stay", "cat": "STRUCTURAL", "type": "DERIVED_FROM", "card": "N:1", "rationale": "ICU 入科人次统计入科实体唯一键"},
    # SEMANTIC: Knowledge Graph meaning & clinical pathway
    {"from": "metric.hospital.mortality_rate", "to": "concept.clinical.icu_mortality", "cat": "SEMANTIC", "type": "MAPS_TO_CONCEPT", "rationale": "院内病死率是重症死亡转归的核心量化指标"},
    {"from": "metric.hospital.expired_count", "to": "concept.clinical.icu_mortality", "cat": "SEMANTIC", "type": "MAPS_TO_CONCEPT", "rationale": "院内死亡人次映射重症死亡转归概念"},
    {"from": "attr.patient.age_group", "to": "concept.clinical.elderly_cohort", "cat": "SEMANTIC", "type": "MAPS_TO_CONCEPT", "rationale": "65-79 与 >=80 年龄段构成老年高危重症群体"},
    {"from": "entity.admission", "to": "entity.icu_stay", "cat": "SEMANTIC", "type": "PRECEDES", "rationale": "住院登记先于或同步触发 ICU 入科监护"},
    {"from": "entity.icu_stay", "to": "process.clinical.icu_care_pathway", "cat": "SEMANTIC", "type": "PART_OF", "rationale": "ICU 监护属于重症临床诊疗全路径核心环节"},
    # CAUSAL: Governance & clinical causality
    {"from": "concept.clinical.disjoint_multicenter", "to": "metric.patient.count", "cat": "CAUSAL", "type": "CONSTRAINS", "rationale": "多中心互斥队列约束跨仓去重患者数必须带 mimic:/eicu: 命名空间前缀"},
    {"from": "concept.clinical.elderly_cohort", "to": "metric.hospital.mortality_rate", "cat": "CAUSAL", "type": "AFFECTS", "rationale": "高龄重症群体显著影响院内病死率分布"},
]

VALUE_DICT_SEEDS: dict[str, list[tuple[str, str, list[str]]]] = {
    "attr.patient.gender": [
        ("M", "男性", ["男", "Male", "male"]),
        ("F", "女性", ["女", "Female", "female"]),
        ("U", "未知", ["Unknown", "其他"]),
    ],
    "attr.patient.age_group": [
        ("<40", "青年（<40岁）", ["青年", "40岁以下"]),
        ("40-64", "中年（40-64岁）", ["中年"]),
        ("65-79", "老年（65-79岁）", ["老年"]),
        (">=80", "高龄老年（≥80岁）", ["高龄", "80岁以上", ">89"]),
    ],
    "attr.patient.race": [
        ("WHITE", "白人", ["Caucasian", "白种人"]),
        ("BLACK", "非裔", ["African American", "黑人"]),
        ("HISPANIC", "拉丁裔", ["Hispanic", "Latino"]),
        ("ASIAN", "亚裔", ["Asian", "亚洲人"]),
        ("OTHER", "其他/未知", ["Other", "Unknown"]),
    ],
    "attr.icu.unit_type": [
        ("MICU", "内科重症监护（MICU）", ["内科ICU", "Medical ICU"]),
        ("SICU", "外科重症监护（SICU）", ["外科ICU", "Surgical ICU"]),
        ("MICU_SICU", "综合内外科 ICU", ["Med-Surg ICU", "综合ICU"]),
        ("CARDIAC_ICU", "心脏/冠心病重症监护", ["CCU", "CVICU", "CSICU", "CTICU", "心内ICU"]),
        ("NEURO_ICU", "神经重症监护（Neuro ICU）", ["神经ICU", "NICU"]),
        ("TRAUMA_ICU", "创伤重症监护（TSICU）", ["创伤ICU"]),
    ],
    "attr.outcome.hospital_status": [
        ("ALIVE", "存活出院", ["存活", "Alive", "生还"]),
        ("EXPIRED", "院内死亡", ["死亡", "Expired", "去世"]),
        ("UNKNOWN", "结局未知", ["未知"]),
    ],
}


def ensure_mapping(cli: Client, obj_id: str, existing_mappings: list[dict[str, Any]], payload: dict[str, Any]) -> None:
    for m in existing_mappings:
        if (m.get("warehouseId") == payload.get("warehouseId")
                and m.get("catalogDatasetId") == payload.get("catalogDatasetId")
                and m.get("mappingRole") == payload.get("mappingRole")
                and (m.get("catalogColumnId") or "") == (payload.get("catalogColumnId") or "")
                and (m.get("expressionSql") or "") == (payload.get("expressionSql") or "")):
            return
    cli.call("POST", f"/api/v1/metadata/semantic/objects/{obj_id}/mappings", payload)


def main() -> int:
    cli = Client(BASE_URL)
    login = cli.call("POST", "/api/v1/auth/login", {"username": USERNAME, "password": PASSWORD})
    cli.token = login["token"]
    cli.workspace_id = login["currentWorkspaceId"]
    print(f"[LOGIN OK] workspace={cli.workspace_id}")

    # 1. Credential & DataSources
    cred_id = ensure_credential(cli)
    mimic_ds_id = ensure_datasource(
        cli, cred_id, "MIMIC-IV 临床重症仓", "mimic",
        "MIT MIMIC-IV 临床重症监护数据库（mimiciv_hosp + mimiciv_icu + 标准化视图）",
    )
    eicu_ds_id = ensure_datasource(
        cli, cred_id, "eICU-CRD 多中心重症仓", "eicu",
        "Philips eICU Collaborative Research Database 多中心重症库（eicu_crd + 标准化视图）",
    )

    # 2. Catalog Sources & Metadata Refresh
    mimic_datasets = ensure_catalog_and_refresh(cli, mimic_ds_id, "MIMIC-IV 数据目录")
    eicu_datasets = ensure_catalog_and_refresh(cli, eicu_ds_id, "eICU-CRD 数据目录")

    # 3. Multi-Warehouse Mounting (COHORT_UNION + DUCKDB federation ready)
    wh_mimic_id = ensure_warehouse(cli, {
        "datasourceId": mimic_ds_id,
        "code": "mimic_iv",
        "name": "MIMIC-IV 临床重症仓",
        "warehouseRole": "PRIMARY",
        "cohortCode": "MIMIC_IV",
        "dialect": "POSTGRESQL",
        "idNamespacePrefix": "mimic:",
        "combineMode": "COHORT_UNION",
        "federationEngine": "DUCKDB",
        "priority": 10,
    })
    wh_eicu_id = ensure_warehouse(cli, {
        "datasourceId": eicu_ds_id,
        "code": "eicu_crd",
        "name": "eICU-CRD 多中心重症仓",
        "warehouseRole": "SECONDARY",
        "cohortCode": "EICU_CRD",
        "dialect": "POSTGRESQL",
        "idNamespacePrefix": "eicu:",
        "combineMode": "COHORT_UNION",
        "federationEngine": "DUCKDB",
        "priority": 20,
    })

    # 4. Logical Tables (Standardized Views logicalSize=50 prioritized over Raw Tables logicalSize=500)
    ds_m_std = find_dataset(mimic_datasets, "mimiciv_icu.v_icu_stay_std")
    ds_m_dx_std = find_dataset(mimic_datasets, "mimiciv_hosp.v_diagnosis_std")
    ds_m_lab_std = find_dataset(mimic_datasets, "mimiciv_hosp.v_lab_std")
    ds_m_pat = find_dataset(mimic_datasets, "mimiciv_hosp.patients")
    ds_m_adm = find_dataset(mimic_datasets, "mimiciv_hosp.admissions")
    ds_m_icu = find_dataset(mimic_datasets, "mimiciv_icu.icustays")
    ds_m_rx = find_dataset(mimic_datasets, "mimiciv_hosp.prescriptions")

    ds_e_std = find_dataset(eicu_datasets, "eicu_crd.v_icu_stay_std")
    ds_e_dx_std = find_dataset(eicu_datasets, "eicu_crd.v_diagnosis_std")
    ds_e_lab_std = find_dataset(eicu_datasets, "eicu_crd.v_lab_std")
    ds_e_pat = find_dataset(eicu_datasets, "eicu_crd.patient")
    ds_e_med = find_dataset(eicu_datasets, "eicu_crd.medication")
    ds_e_vital = find_dataset(eicu_datasets, "eicu_crd.vitalperiodic")

    lt_m_std = ensure_logical_table(cli, wh_mimic_id, ds_m_std["id"], "lt_mimic_icu_stay_std", "FACT", 50)
    lt_m_dx_std = ensure_logical_table(cli, wh_mimic_id, ds_m_dx_std["id"], "lt_mimic_diagnosis_std", "FACT", 80)
    lt_m_lab_std = ensure_logical_table(cli, wh_mimic_id, ds_m_lab_std["id"], "lt_mimic_lab_std", "FACT", 80)
    lt_m_pat = ensure_logical_table(cli, wh_mimic_id, ds_m_pat["id"], "lt_mimic_patients_raw", "LOOKUP", 300)
    lt_m_adm = ensure_logical_table(cli, wh_mimic_id, ds_m_adm["id"], "lt_mimic_admissions_raw", "FACT", 400)
    lt_m_icu = ensure_logical_table(cli, wh_mimic_id, ds_m_icu["id"], "lt_mimic_icustays_raw", "FACT", 500)
    lt_m_rx = ensure_logical_table(cli, wh_mimic_id, ds_m_rx["id"], "lt_mimic_prescriptions", "FACT", 500)

    lt_e_std = ensure_logical_table(cli, wh_eicu_id, ds_e_std["id"], "lt_eicu_icu_stay_std", "FACT", 50)
    lt_e_dx_std = ensure_logical_table(cli, wh_eicu_id, ds_e_dx_std["id"], "lt_eicu_diagnosis_std", "FACT", 80)
    lt_e_lab_std = ensure_logical_table(cli, wh_eicu_id, ds_e_lab_std["id"], "lt_eicu_lab_std", "FACT", 80)
    lt_e_pat = ensure_logical_table(cli, wh_eicu_id, ds_e_pat["id"], "lt_eicu_patient_raw", "FACT", 500)
    lt_e_med = ensure_logical_table(cli, wh_eicu_id, ds_e_med["id"], "lt_eicu_medication", "FACT", 500)
    ensure_logical_table(cli, wh_eicu_id, ds_e_vital["id"], "lt_eicu_vitalperiodic", "FACT", 600)
    print("[OK] logical tables provisioned for MIMIC-IV & eICU-CRD")

    # 5. Upsert & Publish All GLOBAL Clinical Ontology Objects
    existing_page = cli.call("GET", "/api/v1/metadata/semantic/objects?page=1&size=200")
    obj_by_code = {o["code"].lower(): o for o in existing_page.get("records", [])}

    for spec in CLINICAL_OBJECTS:
        code = spec["code"].lower()
        if code in obj_by_code:
            obj_id = obj_by_code[code]["id"]
            cli.call("PUT", f"/api/v1/metadata/semantic/objects/{obj_id}", spec)
            if obj_by_code[code]["status"] != "PUBLISHED":
                obj_by_code[code] = cli.call("POST", f"/api/v1/metadata/semantic/objects/{obj_id}/publish")
            else:
                obj_by_code[code] = cli.call("GET", f"/api/v1/metadata/semantic/objects/{obj_id}")
        else:
            created = cli.call("POST", "/api/v1/metadata/semantic/objects", spec)
            pub = cli.call("POST", f"/api/v1/metadata/semantic/objects/{created['id']}/publish")
            obj_by_code[code] = pub
            print(f"[OK] created & published GLOBAL {code} -> {pub['id']}")
    print(f"[OK] total {len(CLINICAL_OBJECTS)} GLOBAL clinical ontology objects ready")

    # Fetch column maps for standardized views + raw tables
    cols_m_std = get_columns_map(cli, ds_m_std["id"])
    cols_m_dx_std = get_columns_map(cli, ds_m_dx_std["id"])
    cols_m_lab_std = get_columns_map(cli, ds_m_lab_std["id"])
    cols_m_pat = get_columns_map(cli, ds_m_pat["id"])
    cols_m_adm = get_columns_map(cli, ds_m_adm["id"])
    cols_m_icu = get_columns_map(cli, ds_m_icu["id"])
    cols_m_rx = get_columns_map(cli, ds_m_rx["id"])

    cols_e_std = get_columns_map(cli, ds_e_std["id"])
    cols_e_dx_std = get_columns_map(cli, ds_e_dx_std["id"])
    cols_e_lab_std = get_columns_map(cli, ds_e_lab_std["id"])
    cols_e_pat = get_columns_map(cli, ds_e_pat["id"])
    cols_e_med = get_columns_map(cli, ds_e_med["id"])

    # 6. Cross-Warehouse Heterogeneous Mappings (Both Standardized logicalSize=50 & Raw logicalSize=500!)
    mapping_specs: list[tuple[str, dict[str, Any]]] = [
        # entity.patient -> MIMIC (std + raw patients) & eICU (std + raw patient)
        ("entity.patient", {"mappingRole": "ID", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_std, "catalogDatasetId": ds_m_std["id"], "catalogColumnId": cols_m_std.get("patient_id"), "isLookupTable": True, "priority": 10}),
        ("entity.patient", {"mappingRole": "ID", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_pat, "catalogDatasetId": ds_m_pat["id"], "catalogColumnId": cols_m_pat.get("subject_id"), "isLookupTable": True, "priority": 100}),
        ("entity.patient", {"mappingRole": "ID", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_std, "catalogDatasetId": ds_e_std["id"], "catalogColumnId": cols_e_std.get("patient_id"), "isLookupTable": True, "priority": 10}),
        ("entity.patient", {"mappingRole": "ID", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_pat, "catalogDatasetId": ds_e_pat["id"], "catalogColumnId": cols_e_pat.get("uniquepid"), "isLookupTable": True, "priority": 100}),
        # entity.admission
        ("entity.admission", {"mappingRole": "ID", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_std, "catalogDatasetId": ds_m_std["id"], "catalogColumnId": cols_m_std.get("admission_id"), "priority": 10}),
        ("entity.admission", {"mappingRole": "ID", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_adm, "catalogDatasetId": ds_m_adm["id"], "catalogColumnId": cols_m_adm.get("hadm_id"), "priority": 100}),
        ("entity.admission", {"mappingRole": "ID", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_std, "catalogDatasetId": ds_e_std["id"], "catalogColumnId": cols_e_std.get("admission_id"), "priority": 10}),
        ("entity.admission", {"mappingRole": "ID", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_pat, "catalogDatasetId": ds_e_pat["id"], "catalogColumnId": cols_e_pat.get("patienthealthsystemstayid"), "priority": 100}),
        # entity.icu_stay
        ("entity.icu_stay", {"mappingRole": "ID", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_std, "catalogDatasetId": ds_m_std["id"], "catalogColumnId": cols_m_std.get("icu_stay_id"), "priority": 10}),
        ("entity.icu_stay", {"mappingRole": "ID", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_icu, "catalogDatasetId": ds_m_icu["id"], "catalogColumnId": cols_m_icu.get("stay_id"), "priority": 100}),
        ("entity.icu_stay", {"mappingRole": "ID", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_std, "catalogDatasetId": ds_e_std["id"], "catalogColumnId": cols_e_std.get("icu_stay_id"), "priority": 10}),
        ("entity.icu_stay", {"mappingRole": "ID", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_pat, "catalogDatasetId": ds_e_pat["id"], "catalogColumnId": cols_e_pat.get("patientunitstayid"), "priority": 100}),
        # entity.diagnosis / lab / medication
        ("entity.diagnosis", {"mappingRole": "ID", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_dx_std, "catalogDatasetId": ds_m_dx_std["id"], "catalogColumnId": cols_m_dx_std.get("icd_code"), "priority": 10}),
        ("entity.diagnosis", {"mappingRole": "ID", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_dx_std, "catalogDatasetId": ds_e_dx_std["id"], "catalogColumnId": cols_e_dx_std.get("diagnosis_id"), "priority": 10}),
        ("entity.lab_event", {"mappingRole": "ID", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_lab_std, "catalogDatasetId": ds_m_lab_std["id"], "catalogColumnId": cols_m_lab_std.get("lab_event_id"), "priority": 10}),
        ("entity.lab_event", {"mappingRole": "ID", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_lab_std, "catalogDatasetId": ds_e_lab_std["id"], "catalogColumnId": cols_e_lab_std.get("lab_event_id"), "priority": 10}),
        ("entity.medication", {"mappingRole": "ID", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_rx, "catalogDatasetId": ds_m_rx["id"], "catalogColumnId": cols_m_rx.get("drug"), "priority": 10}),
        ("entity.medication", {"mappingRole": "ID", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_med, "catalogDatasetId": ds_e_med["id"], "catalogColumnId": cols_e_med.get("medicationid"), "priority": 10}),
        # attr.patient.gender (standardized view + raw with valueNormalization JSON)
        ("attr.patient.gender", {"mappingRole": "CODE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_std, "catalogDatasetId": ds_m_std["id"], "catalogColumnId": cols_m_std.get("gender_std"), "priority": 10}),
        ("attr.patient.gender", {"mappingRole": "CODE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_pat, "catalogDatasetId": ds_m_pat["id"], "catalogColumnId": cols_m_pat.get("gender"), "priority": 100}),
        ("attr.patient.gender", {"mappingRole": "CODE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_std, "catalogDatasetId": ds_e_std["id"], "catalogColumnId": cols_e_std.get("gender_std"), "priority": 10}),
        ("attr.patient.gender", {"mappingRole": "CODE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_pat, "catalogDatasetId": ds_e_pat["id"], "catalogColumnId": cols_e_pat.get("gender"), "valueNormalization": {"Male": "M", "Female": "F", "Unknown": "U"}, "priority": 100}),
        # attr.patient.age_group
        ("attr.patient.age_group", {"mappingRole": "CODE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_std, "catalogDatasetId": ds_m_std["id"], "catalogColumnId": cols_m_std.get("age_group_std"), "priority": 10}),
        ("attr.patient.age_group", {"mappingRole": "CODE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_std, "catalogDatasetId": ds_e_std["id"], "catalogColumnId": cols_e_std.get("age_group_std"), "priority": 10}),
        # attr.patient.race
        ("attr.patient.race", {"mappingRole": "CODE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_std, "catalogDatasetId": ds_m_std["id"], "catalogColumnId": cols_m_std.get("race_std"), "priority": 10}),
        ("attr.patient.race", {"mappingRole": "CODE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_std, "catalogDatasetId": ds_e_std["id"], "catalogColumnId": cols_e_std.get("race_std"), "priority": 10}),
        ("attr.patient.race", {"mappingRole": "CODE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_pat, "catalogDatasetId": ds_e_pat["id"], "catalogColumnId": cols_e_pat.get("ethnicity"), "valueNormalization": {"Caucasian": "WHITE", "African American": "BLACK", "Hispanic": "HISPANIC", "Asian": "ASIAN"}, "priority": 100}),
        # attr.icu.unit_type & dim.clinical.icu_unit
        ("attr.icu.unit_type", {"mappingRole": "CODE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_std, "catalogDatasetId": ds_m_std["id"], "catalogColumnId": cols_m_std.get("icu_unit_std"), "priority": 10}),
        ("attr.icu.unit_type", {"mappingRole": "CODE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_std, "catalogDatasetId": ds_e_std["id"], "catalogColumnId": cols_e_std.get("icu_unit_std"), "priority": 10}),
        ("dim.clinical.icu_unit", {"mappingRole": "CODE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_std, "catalogDatasetId": ds_m_std["id"], "catalogColumnId": cols_m_std.get("icu_unit_std"), "priority": 10}),
        ("dim.clinical.icu_unit", {"mappingRole": "CODE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_std, "catalogDatasetId": ds_e_std["id"], "catalogColumnId": cols_e_std.get("icu_unit_std"), "priority": 10}),
        # attr.outcome.hospital_status
        ("attr.outcome.hospital_status", {"mappingRole": "CODE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_std, "catalogDatasetId": ds_m_std["id"], "catalogColumnId": cols_m_std.get("hospital_outcome_std"), "priority": 10}),
        ("attr.outcome.hospital_status", {"mappingRole": "CODE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_std, "catalogDatasetId": ds_e_std["id"], "catalogColumnId": cols_e_std.get("hospital_outcome_std"), "priority": 10}),
        ("attr.outcome.hospital_status", {"mappingRole": "CODE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_pat, "catalogDatasetId": ds_e_pat["id"], "catalogColumnId": cols_e_pat.get("hospitaldischargestatus"), "valueNormalization": {"Alive": "ALIVE", "Expired": "EXPIRED"}, "priority": 100}),
        # attr.diagnosis / lab / medication
        ("attr.diagnosis.icd_code", {"mappingRole": "CODE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_dx_std, "catalogDatasetId": ds_m_dx_std["id"], "catalogColumnId": cols_m_dx_std.get("icd_code"), "priority": 10}),
        ("attr.diagnosis.icd_code", {"mappingRole": "CODE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_dx_std, "catalogDatasetId": ds_e_dx_std["id"], "catalogColumnId": cols_e_dx_std.get("icd_code"), "priority": 10}),
        ("attr.lab.item_name", {"mappingRole": "DESC", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_lab_std, "catalogDatasetId": ds_m_lab_std["id"], "catalogColumnId": cols_m_lab_std.get("lab_name"), "priority": 10}),
        ("attr.lab.item_name", {"mappingRole": "DESC", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_lab_std, "catalogDatasetId": ds_e_lab_std["id"], "catalogColumnId": cols_e_lab_std.get("lab_name"), "priority": 10}),
        ("attr.medication.drug_name", {"mappingRole": "DESC", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_rx, "catalogDatasetId": ds_m_rx["id"], "catalogColumnId": cols_m_rx.get("drug"), "priority": 10}),
        ("attr.medication.drug_name", {"mappingRole": "DESC", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_med, "catalogDatasetId": ds_e_med["id"], "catalogColumnId": cols_e_med.get("drugname"), "priority": 10}),
        # Facts (with unit conversion documented on raw tables!)
        ("fact.icu.los_hours", {"mappingRole": "MEASURE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_std, "catalogDatasetId": ds_m_std["id"], "catalogColumnId": cols_m_std.get("icu_los_hours"), "sourceUnit": "小时", "targetUnit": "小时", "priority": 10}),
        ("fact.icu.los_hours", {"mappingRole": "MEASURE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_icu, "catalogDatasetId": ds_m_icu["id"], "catalogColumnId": cols_m_icu.get("los"), "expressionSql": "los * 24.0", "sourceUnit": "天", "targetUnit": "小时", "priority": 100}),
        ("fact.icu.los_hours", {"mappingRole": "MEASURE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_std, "catalogDatasetId": ds_e_std["id"], "catalogColumnId": cols_e_std.get("icu_los_hours"), "sourceUnit": "小时", "targetUnit": "小时", "priority": 10}),
        ("fact.icu.los_hours", {"mappingRole": "MEASURE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_pat, "catalogDatasetId": ds_e_pat["id"], "catalogColumnId": cols_e_pat.get("unitdischargeoffset"), "expressionSql": "unitdischargeoffset / 60.0", "sourceUnit": "分钟", "targetUnit": "小时", "priority": 100}),
        ("fact.hospital.los_hours", {"mappingRole": "MEASURE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_std, "catalogDatasetId": ds_m_std["id"], "catalogColumnId": cols_m_std.get("hosp_los_hours"), "sourceUnit": "小时", "targetUnit": "小时", "priority": 10}),
        ("fact.hospital.los_hours", {"mappingRole": "MEASURE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_std, "catalogDatasetId": ds_e_std["id"], "catalogColumnId": cols_e_std.get("hosp_los_hours"), "sourceUnit": "小时", "targetUnit": "小时", "priority": 10}),
        ("fact.lab.result_value", {"mappingRole": "MEASURE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_lab_std, "catalogDatasetId": ds_m_lab_std["id"], "catalogColumnId": cols_m_lab_std.get("lab_value_num"), "priority": 10}),
        ("fact.lab.result_value", {"mappingRole": "MEASURE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_lab_std, "catalogDatasetId": ds_e_lab_std["id"], "catalogColumnId": cols_e_lab_std.get("lab_value_num"), "priority": 10}),
        # Metrics (bound to standardized views logicalSize=50 + raw tables logicalSize=500)
        ("metric.patient.count", {"mappingRole": "MEASURE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_std, "catalogDatasetId": ds_m_std["id"], "catalogColumnId": cols_m_std.get("patient_id"), "priority": 10}),
        ("metric.patient.count", {"mappingRole": "MEASURE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_std, "catalogDatasetId": ds_e_std["id"], "catalogColumnId": cols_e_std.get("patient_id"), "priority": 10}),
        ("metric.icu.stay_count", {"mappingRole": "MEASURE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_std, "catalogDatasetId": ds_m_std["id"], "catalogColumnId": cols_m_std.get("icu_stay_id"), "priority": 10}),
        ("metric.icu.stay_count", {"mappingRole": "MEASURE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_std, "catalogDatasetId": ds_e_std["id"], "catalogColumnId": cols_e_std.get("icu_stay_id"), "priority": 10}),
        ("metric.icu.avg_los_hours", {"mappingRole": "MEASURE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_std, "catalogDatasetId": ds_m_std["id"], "catalogColumnId": cols_m_std.get("icu_los_hours"), "sourceUnit": "小时", "targetUnit": "小时", "priority": 10}),
        ("metric.icu.avg_los_hours", {"mappingRole": "MEASURE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_icu, "catalogDatasetId": ds_m_icu["id"], "catalogColumnId": cols_m_icu.get("los"), "expressionSql": "los * 24.0", "sourceUnit": "天", "targetUnit": "小时", "priority": 100}),
        ("metric.icu.avg_los_hours", {"mappingRole": "MEASURE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_std, "catalogDatasetId": ds_e_std["id"], "catalogColumnId": cols_e_std.get("icu_los_hours"), "sourceUnit": "小时", "targetUnit": "小时", "priority": 10}),
        ("metric.icu.avg_los_hours", {"mappingRole": "MEASURE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_pat, "catalogDatasetId": ds_e_pat["id"], "catalogColumnId": cols_e_pat.get("unitdischargeoffset"), "expressionSql": "unitdischargeoffset / 60.0", "sourceUnit": "分钟", "targetUnit": "小时", "priority": 100}),
        ("metric.icu.total_los_hours", {"mappingRole": "MEASURE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_std, "catalogDatasetId": ds_m_std["id"], "catalogColumnId": cols_m_std.get("icu_los_hours"), "sourceUnit": "小时", "targetUnit": "小时", "priority": 10}),
        ("metric.icu.total_los_hours", {"mappingRole": "MEASURE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_std, "catalogDatasetId": ds_e_std["id"], "catalogColumnId": cols_e_std.get("icu_los_hours"), "sourceUnit": "小时", "targetUnit": "小时", "priority": 10}),
        ("metric.hospital.expired_count", {"mappingRole": "MEASURE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_std, "catalogDatasetId": ds_m_std["id"], "catalogColumnId": cols_m_std.get("hospital_expired_flag"), "priority": 10}),
        ("metric.hospital.expired_count", {"mappingRole": "MEASURE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_std, "catalogDatasetId": ds_e_std["id"], "catalogColumnId": cols_e_std.get("hospital_expired_flag"), "priority": 10}),
        ("metric.hospital.mortality_rate", {"mappingRole": "MEASURE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_std, "catalogDatasetId": ds_m_std["id"], "catalogColumnId": cols_m_std.get("hospital_expired_flag"), "expressionSql": "hospital_expired_flag * 100.0", "sourceUnit": "标志位(0/1)", "targetUnit": "%", "priority": 10}),
        ("metric.hospital.mortality_rate", {"mappingRole": "MEASURE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_std, "catalogDatasetId": ds_e_std["id"], "catalogColumnId": cols_e_std.get("hospital_expired_flag"), "expressionSql": "hospital_expired_flag * 100.0", "sourceUnit": "标志位(0/1)", "targetUnit": "%", "priority": 10}),
        ("metric.patient.avg_age", {"mappingRole": "MEASURE", "warehouseId": wh_mimic_id, "logicalTableId": lt_m_std, "catalogDatasetId": ds_m_std["id"], "catalogColumnId": cols_m_std.get("age_years"), "sourceUnit": "岁", "targetUnit": "岁", "priority": 10}),
        ("metric.patient.avg_age", {"mappingRole": "MEASURE", "warehouseId": wh_eicu_id, "logicalTableId": lt_e_std, "catalogDatasetId": ds_e_std["id"], "catalogColumnId": cols_e_std.get("age_years"), "sourceUnit": "岁", "targetUnit": "岁", "priority": 10}),
    ]

    cached_mappings: dict[str, list[dict[str, Any]]] = {}
    for code, m_payload in mapping_specs:
        oid = obj_by_code[code]["id"]
        if oid not in cached_mappings:
            cached_mappings[oid] = cli.call("GET", f"/api/v1/metadata/semantic/objects/{oid}/mappings")
        ensure_mapping(cli, oid, cached_mappings[oid], m_payload)
    print(f"[OK] provisioned {len(mapping_specs)} cross-warehouse heterogeneous mappings (MIMIC-IV + eICU-CRD)")

    # 7. Knowledge Graph & Structural Links
    existing_links = cli.call("GET", "/api/v1/metadata/semantic/links")
    link_keys = {(l["fromObjectId"], l["toObjectId"], l["linkType"]) for l in existing_links}
    links_added = 0
    for lnk in SEMANTIC_LINKS:
        fid = obj_by_code[lnk["from"]]["id"]
        tid = obj_by_code[lnk["to"]]["id"]
        if (fid, tid, lnk["type"]) in link_keys:
            continue
        cli.call("POST", "/api/v1/metadata/semantic/links", {
            "fromObjectId": fid,
            "toObjectId": tid,
            "edgeCategory": lnk["cat"],
            "linkType": lnk["type"],
            "cardinality": lnk.get("card"),
            "rationale": lnk["rationale"],
            "sourceKind": "SEED",
        })
        link_keys.add((fid, tid, lnk["type"]))
        links_added += 1
    print(f"[OK] semantic & KG links ready (added {links_added}, total {len(link_keys)})")

    # 8. Standardized L1/L2 Value Dictionary
    vd_count = 0
    for code, entries in VALUE_DICT_SEEDS.items():
        oid = obj_by_code[code]["id"]
        for raw_val, label, syns in entries:
            cli.call("POST", f"/api/v1/metadata/semantic/objects/{oid}/value-dict", {
                "rawValue": raw_val,
                "displayLabel": label,
                "synonyms": syns,
                "sourceKind": "SEED",
            })
            vd_count += 1
    print(f"[OK] upserted {vd_count} standardized L1/L2 value dictionary entries")

    print("\nALL MIMIC-IV + eICU-CRD CONFIGURATION & ONTOLOGY STANDARDIZATION COMPLETE!")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
