#!/usr/bin/env python3
"""一键端到端配置 MIMIC-IV + eICU-CRD + Synthea(CHANCE-HCC stub) 临床多仓库、数据目录、逻辑表、
全套 GLOBAL 临床语义本体、跨仓异构物理映射、知识图谱关系边与标准化值字典。

完全幂等，可反复执行。Synthea 库不可达时跳过第三仓，不阻断 MIMIC/eICU 主流程。

用法:
  python3 scripts/seed-clinical-multisource.py
  # 可选覆盖环境变量: DG_BASE_URL / DG_USERNAME / DG_PASSWORD / PG_HOST / PG_PORT / PG_USER / PG_PASSWORD
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path
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
        "description": "临床数据库（MIMIC-IV / eICU-CRD / Synthea）只读访问凭据",
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


def try_provision_synthea_stubs() -> str | None:
    """灌 Synthea stub；成功返回有效 databaseName（通常 synthea），失败返回 None。"""
    script = Path(__file__).resolve().parent / "seed" / "provision_synthea_hcc_stubs.py"
    if not script.is_file():
        print(f"[WARN] synthea provision script missing: {script}")
        return None
    env = dict(os.environ, PG_HOST=PG_HOST, PG_PORT=str(PG_PORT), PG_USER=PG_USER, PG_PASSWORD=PG_PASSWORD)
    proc = subprocess.run([sys.executable, str(script)], env=env, capture_output=True, text=True)
    if proc.stdout.strip():
        print(proc.stdout.rstrip())
    if proc.returncode != 0:
        if proc.stderr.strip():
            print(proc.stderr.rstrip(), file=sys.stderr)
        print("[WARN] Synthea stub provision failed — skip synthea: warehouse wiring")
        return None
    effective = "synthea"
    for line in proc.stdout.splitlines():
        if line.startswith("SYNTHEA_EFFECTIVE_DB="):
            effective = line.split("=", 1)[1].strip() or effective
    return effective


def try_find_dataset(ds_map: dict[str, dict[str, Any]], suffix: str) -> dict[str, Any] | None:
    try:
        return find_dataset(ds_map, suffix)
    except KeyError:
        return None


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
            "grain": "每个实体代表一名唯一患者；跨 MIMIC-IV / eICU-CRD / Synthea 互斥队列自动注入 mimic:/eicu:/synthea: 命名空间前缀防碰撞",
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
            "grain": "去重患者数，跨 MIMIC/eICU/Synthea 互斥队列自动注入 mimic:/eicu:/synthea: 命名空间前缀防碰撞",
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
        "name": "多中心互斥队列约束（MIMIC ∩ eICU ∩ Synthea = ∅）",
        "objectType": "CONCEPT",
        "scope": "GLOBAL",
        "spec": {},
        "aiContext": {
            "aliases": ["多中心队列约束", "互斥队列"],
            "disambiguation": "MIMIC-IV、eICU-CRD 与 Synthea(CHANCE-HCC) 患者群体互斥；联合统计严禁无前缀 INNER JOIN，须走 COHORT_UNION + mimic:/eicu:/synthea: 命名空间前缀",
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
    # ── CHANCE-HCC / Synthea 专病 Layer1+2（对齐 CNLC-2024 / mRECIST；非 MIMIC 克隆）──
    {
        "code": "entity.treatment_session",
        "name": "介入治疗程次实体",
        "objectType": "ENTITY",
        "scope": "GLOBAL",
        "spec": {"primaryKey": "session_id"},
        "aiContext": {
            "aliases": ["TACE程次", "介入session", "treatment session", "HAIC程次"],
            "grain": "同一患者按 session_seq 区分的一次局部治疗完成单元；主键 session_id；禁止与住院 admission/hadm 混用",
            "caveats": ["synthea_hcc.tace_session；T0=actual_exec_end_t0（实际执行完成），非医嘱/计费日"],
        },
    },
    {
        "code": "entity.followup_visit",
        "name": "专病随访评估实体",
        "objectType": "ENTITY",
        "scope": "GLOBAL",
        "spec": {"primaryKey": "followup_id"},
        "aiContext": {
            "aliases": ["随访访视", "mRECIST随访", "follow-up visit"],
            "grain": "相对某治疗程次 T0 的一次影像/肿瘤学随访评估（visit_seq）",
        },
    },
    {
        "code": "entity.imaging_report",
        "name": "影像报告实体（合成夹具）",
        "objectType": "ENTITY",
        "scope": "GLOBAL",
        "spec": {"primaryKey": "report_id"},
        "aiContext": {
            "aliases": ["影像报告", "CT/MRI报告"],
            "grain": "合成中文影像报告文本夹具；禁止把 report_text 写入 ai_context 或当作 Standard",
            "caveats": ["仅治理/质控样例；问数默认不返回全文"],
        },
    },
    {
        "code": "entity.hcc_diagnosis",
        "name": "肝癌专病诊断实体",
        "objectType": "ENTITY",
        "scope": "GLOBAL",
        "spec": {"primaryKey": "diagnosis_id"},
        "aiContext": {
            "aliases": ["HCC诊断", "肝癌分期诊断"],
            "grain": "队列内一次肝癌基线诊断（含 CNLC/Child-Pugh/PVTT/肿瘤负荷）；可与通用 entity.diagnosis 并存但勿混 grain",
        },
    },
    {
        "code": "attr.diagnosis.cnlc_stage",
        "name": "CNLC 分期",
        "objectType": "ATTRIBUTE",
        "scope": "GLOBAL",
        "standardRef": "STD-CNLC-2024-STAGE",
        "spec": {"forms": [{"role": "CODE", "dataType": "STRING"}]},
        "aiContext": {
            "aliases": ["中国肝癌分期", "CNLC", "China Liver Cancer Staging"],
            "safeEnumExamples": ["Ia", "Ib", "IIa", "IIb", "IIIa", "IIIb", "IV"],
            "disambiguation": "CNLC-2024：Ia 单灶≤5cm；Ib 单灶>5cm或2–3灶≤3cm；IIa 2–3灶最大径>3cm；IIb≥4灶无栓无肝外；IIIa有血管侵犯无肝外；IIIb有肝外转移；IV为PS3–4或Child-Pugh C。勿与 BCLC 字母分期混用。",
        },
    },
    {
        "code": "attr.diagnosis.child_pugh_class",
        "name": "Child-Pugh 分级",
        "objectType": "ATTRIBUTE",
        "scope": "GLOBAL",
        "standardRef": "STD-CHILD-PUGH-CLASS",
        "spec": {"forms": [{"role": "CODE", "dataType": "STRING"}]},
        "aiContext": {
            "aliases": ["Child-Pugh", "肝功能分级", "CTP"],
            "safeEnumExamples": ["A", "B", "C"],
            "disambiguation": "A/B/C 三级；CNLC IV 可因 Child-Pugh C 判定，与肿瘤负荷无关",
        },
    },
    {
        "code": "attr.diagnosis.pvtt_grade",
        "name": "门脉癌栓分级",
        "objectType": "ATTRIBUTE",
        "scope": "GLOBAL",
        "standardRef": "STD-PVTT-GRADE-LOCAL",
        "spec": {"forms": [{"role": "CODE", "dataType": "STRING"}]},
        "aiContext": {
            "aliases": ["PVTT", "门静脉癌栓", "血管侵犯分级"],
            "disambiguation": "合成字段 hcc_pvtt_grade；有影像可见血管癌栓时常对应 CNLC IIIa 语境（无肝外转移时）",
        },
    },
    {
        "code": "attr.treatment.modality",
        "name": "局部治疗方式",
        "objectType": "ATTRIBUTE",
        "scope": "GLOBAL",
        "standardRef": "STD-HCC-LOCOREGIONAL-MODALITY",
        "spec": {"forms": [{"role": "CODE", "dataType": "STRING"}]},
        "aiContext": {
            "aliases": ["介入方式", "TACE类型", "modality"],
            "safeEnumExamples": ["cTACE", "DEB-TACE", "HAIC"],
            "disambiguation": "cTACE/DEB-TACE 为栓塞类；HAIC 为肝动脉灌注化疗，适应证与 TACE 不完全等同（多用于 TACE 抵抗/门脉癌栓等场景），禁止把 HAIC 统计进「TACE 次数」而不加模态过滤",
        },
    },
    {
        "code": "attr.treatment.session_seq",
        "name": "治疗程次序号",
        "objectType": "ATTRIBUTE",
        "scope": "GLOBAL",
        "standardRef": "STD-TREATMENT-SESSION-SEQ",
        "spec": {"forms": [{"role": "CODE", "dataType": "INTEGER"}]},
        "aiContext": {
            "aliases": ["session_seq", "第几程", "程次"],
            "disambiguation": "由多 T0 切分得到的稳定序号；跨程归因必须带 session_seq，禁止「取最近一次治疗」",
        },
    },
    {
        "code": "attr.treatment.t0_exec_end",
        "name": "实际治疗完成时间 T0",
        "objectType": "ATTRIBUTE",
        "scope": "GLOBAL",
        "standardRef": "STD-TREATMENT-T0-ACTUAL-EXEC",
        "spec": {"forms": [{"role": "TIME_KEY", "dataType": "TIMESTAMP"}]},
        "aiContext": {
            "aliases": ["T0", "actual_exec_end_t0", "治疗锚点时间"],
            "disambiguation": "临床实际执行完成时间；严禁映射到计费日/医嘱开立日/出导管室近似字段而不加 t0_trap_warning",
            "caveats": ["计费 treat_date、医嘱时间不是 T0"],
        },
    },
    {
        "code": "attr.followup.mrecist_response",
        "name": "mRECIST 疗效",
        "objectType": "ATTRIBUTE",
        "scope": "GLOBAL",
        "standardRef": "STD-MRECIST-RESPONSE",
        "spec": {"forms": [{"role": "CODE", "dataType": "STRING"}]},
        "aiContext": {
            "aliases": ["mRECIST", "肿瘤疗效", "影像缓解"],
            "safeEnumExamples": ["CR", "PR", "SD", "PD"],
            "disambiguation": "改良 RECIST：按存活靶灶评估；ORR 通常计 CR+PR。TACE 短期疗效常用 mRECIST/EASL，勿与经典 RECIST 混用。",
        },
    },
    {
        "code": "fact.hcc.days_from_t0",
        "name": "相对 T0 随访天数事实",
        "objectType": "FACT",
        "scope": "GLOBAL",
        "spec": {"unit": "天", "dataType": "INTEGER"},
        "aiContext": {"aliases": ["days_from_t0", "距治疗天数"]},
    },
    {
        "code": "fact.hcc.baseline_afp",
        "name": "基线 AFP 事实",
        "objectType": "FACT",
        "scope": "GLOBAL",
        "spec": {"unit": "ng/mL", "dataType": "DOUBLE"},
        "aiContext": {"aliases": ["AFP", "甲胎蛋白"]},
    },
    {
        "code": "metric.hcc.session_count",
        "name": "介入治疗程次数",
        "objectType": "METRIC",
        "scope": "GLOBAL",
        "standardRef": "STD-HCC-SESSION-CNT-001",
        "spec": {
            "metricKind": "BASE",
            "aggregation": "COUNT_DISTINCT",
            "unit": "程",
            "allowedDimensions": [
                "attr.system.cohort", "attr.treatment.modality", "attr.treatment.session_seq",
                "attr.diagnosis.cnlc_stage", "attr.patient.gender", "attr.patient.age_group",
            ],
        },
        "aiContext": {
            "aliases": ["TACE次数", "介入程次数", "session count"],
            "grain": "按 session_id 去重；按 modality 过滤时 HAIC 与 TACE 分开统计",
        },
    },
    {
        "code": "metric.hcc.followup_visit_count",
        "name": "专病随访访视次数",
        "objectType": "METRIC",
        "scope": "GLOBAL",
        "standardRef": "STD-HCC-FOLLOWUP-CNT-001",
        "spec": {
            "metricKind": "BASE",
            "aggregation": "COUNT_DISTINCT",
            "unit": "次",
            "allowedDimensions": [
                "attr.system.cohort", "attr.followup.mrecist_response",
                "attr.treatment.modality", "attr.diagnosis.cnlc_stage",
            ],
        },
        "aiContext": {"aliases": ["随访次数", "mRECIST访视数"]},
    },
    {
        "code": "metric.hcc.orr_rate",
        "name": "客观缓解率 ORR（mRECIST）",
        "objectType": "METRIC",
        "scope": "GLOBAL",
        "standardRef": "STD-HCC-ORR-MRECIST-001",
        "spec": {
            "metricKind": "BASE",
            "aggregation": "AVG",
            "unit": "%",
            "allowedDimensions": [
                "attr.system.cohort", "attr.treatment.modality", "attr.diagnosis.cnlc_stage",
                "attr.patient.gender", "attr.patient.age_group",
            ],
        },
        "aiContext": {
            "aliases": ["ORR", "客观缓解率", "CR+PR率"],
            "grain": "访视级：mRECIST∈{CR,PR}→100，否则 0，再求均值；跨仓须两阶段加权，禁止 LLM 心算",
            "caveats": ["MVP 按访视而非最佳总体疗效；完整 best-ORR 需专病看板/统计算子"],
        },
    },
    {
        "code": "metric.hcc.dead_count",
        "name": "专病队列死亡人数",
        "objectType": "METRIC",
        "scope": "GLOBAL",
        "standardRef": "STD-HCC-DEAD-CNT-001",
        "spec": {
            "metricKind": "BASE",
            "aggregation": "SUM",
            "unit": "人",
            "allowedDimensions": [
                "attr.system.cohort", "attr.diagnosis.cnlc_stage", "attr.diagnosis.child_pugh_class",
                "attr.patient.gender", "attr.patient.age_group",
            ],
        },
        "aiContext": {
            "aliases": ["HCC死亡数", "队列死亡"],
            "grain": "survival_status=Dead 计 1；Lost_to_FollowUp 不计死亡",
        },
    },
    {
        "code": "concept.hcc.cnlc_staging",
        "name": "中国肝癌分期体系（CNLC-2024）",
        "objectType": "CONCEPT",
        "scope": "GLOBAL",
        "spec": {},
        "aiContext": {
            "aliases": ["CNLC分期体系", "中国肝癌分期"],
            "disambiguation": "权威口径：国家卫健委《原发性肝癌诊疗指南（2024年版）》CNLC Ia–IV；与 BCLC 不同体系，问数消歧时必须点名 CNLC",
        },
    },
    {
        "code": "concept.hcc.mrecist",
        "name": "mRECIST 疗效评估概念",
        "objectType": "CONCEPT",
        "scope": "GLOBAL",
        "spec": {},
        "aiContext": {
            "aliases": ["改良RECIST", "存活靶灶评估"],
            "disambiguation": "局部治疗短期疗效常用；ORR=CR+PR；勿与经典 RECIST 径线总和规则混用",
        },
    },
    {
        "code": "concept.hcc.t0_actual_exec",
        "name": "实际治疗完成锚点 T0",
        "objectType": "CONCEPT",
        "scope": "GLOBAL",
        "spec": {},
        "aiContext": {
            "aliases": ["治疗T0", "执行完成锚点"],
            "disambiguation": "仅 actual_exec_end_t0；计费/医嘱/出室近似时间属陷阱字段",
        },
    },
    {
        "code": "concept.hcc.locoregional_modality",
        "name": "肝癌局部治疗模态（TACE/HAIC）",
        "objectType": "CONCEPT",
        "scope": "GLOBAL",
        "spec": {},
        "aiContext": {
            "aliases": ["介入治疗模态", "TACE与HAIC"],
            "disambiguation": "TACE（含 cTACE/DEB-TACE）与 HAIC 适应证与统计口径分离；指南中 HAIC 多作补充/特定人群方案",
        },
    },
    {
        "code": "process.hcc.chance_care_pathway",
        "name": "CHANCE 肝癌诊疗全路径",
        "objectType": "PROCESS",
        "scope": "GLOBAL",
        "spec": {},
        "aiContext": {
            "aliases": ["肝癌专病路径", "CHANCE路径", "介入-随访路径"],
            "disambiguation": "基线诊断(CNLC等) → 介入程次(session_seq,T0) → mRECIST随访 → 生存/失访；影像报告为旁路证据夹具",
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
    {"from": "concept.clinical.disjoint_multicenter", "to": "metric.patient.count", "cat": "CAUSAL", "type": "CONSTRAINS", "rationale": "多中心互斥队列约束跨仓去重患者数必须带 mimic:/eicu:/synthea: 命名空间前缀"},
    {"from": "concept.clinical.elderly_cohort", "to": "metric.hospital.mortality_rate", "cat": "CAUSAL", "type": "AFFECTS", "rationale": "高龄重症群体显著影响院内病死率分布"},
    # CHANCE-HCC pathway & knowledge
    {"from": "entity.patient", "to": "entity.hcc_diagnosis", "cat": "STRUCTURAL", "type": "RELATES_TO", "card": "1:N", "rationale": "专病队列患者可有肝癌基线诊断"},
    {"from": "entity.hcc_diagnosis", "to": "attr.diagnosis.cnlc_stage", "cat": "STRUCTURAL", "type": "HAS_ATTRIBUTE", "card": "1:1", "rationale": "诊断绑定 CNLC 分期"},
    {"from": "entity.hcc_diagnosis", "to": "attr.diagnosis.child_pugh_class", "cat": "STRUCTURAL", "type": "HAS_ATTRIBUTE", "card": "1:1", "rationale": "诊断绑定 Child-Pugh"},
    {"from": "entity.hcc_diagnosis", "to": "attr.diagnosis.pvtt_grade", "cat": "STRUCTURAL", "type": "HAS_ATTRIBUTE", "card": "1:1", "rationale": "诊断绑定门脉癌栓分级"},
    {"from": "entity.hcc_diagnosis", "to": "fact.hcc.baseline_afp", "cat": "STRUCTURAL", "type": "HAS_FACT", "card": "1:1", "rationale": "基线 AFP"},
    {"from": "entity.hcc_diagnosis", "to": "entity.treatment_session", "cat": "STRUCTURAL", "type": "RELATES_TO", "card": "1:N", "rationale": "一次诊断可对应多程介入"},
    {"from": "entity.treatment_session", "to": "attr.treatment.modality", "cat": "STRUCTURAL", "type": "HAS_ATTRIBUTE", "card": "N:1", "rationale": "程次绑定局部治疗模态"},
    {"from": "entity.treatment_session", "to": "attr.treatment.session_seq", "cat": "STRUCTURAL", "type": "HAS_ATTRIBUTE", "card": "1:1", "rationale": "程次稳定序号"},
    {"from": "entity.treatment_session", "to": "attr.treatment.t0_exec_end", "cat": "STRUCTURAL", "type": "HAS_ATTRIBUTE", "card": "1:1", "rationale": "程次绑定实际执行完成 T0"},
    {"from": "entity.treatment_session", "to": "entity.followup_visit", "cat": "STRUCTURAL", "type": "RELATES_TO", "card": "1:N", "rationale": "一程治疗后可有多次随访"},
    {"from": "entity.followup_visit", "to": "attr.followup.mrecist_response", "cat": "STRUCTURAL", "type": "HAS_ATTRIBUTE", "card": "1:1", "rationale": "随访绑定 mRECIST"},
    {"from": "entity.followup_visit", "to": "fact.hcc.days_from_t0", "cat": "STRUCTURAL", "type": "HAS_FACT", "card": "1:1", "rationale": "随访相对 T0 天数"},
    {"from": "entity.patient", "to": "entity.imaging_report", "cat": "STRUCTURAL", "type": "RELATES_TO", "card": "1:N", "rationale": "患者可有多份影像报告夹具"},
    {"from": "metric.hcc.session_count", "to": "entity.treatment_session", "cat": "STRUCTURAL", "type": "DERIVED_FROM", "card": "N:1", "rationale": "程次数统计治疗程次实体"},
    {"from": "metric.hcc.followup_visit_count", "to": "entity.followup_visit", "cat": "STRUCTURAL", "type": "DERIVED_FROM", "card": "N:1", "rationale": "随访次数统计随访实体"},
    {"from": "metric.hcc.orr_rate", "to": "attr.followup.mrecist_response", "cat": "STRUCTURAL", "type": "DERIVED_FROM", "card": "N:1", "rationale": "ORR 由 mRECIST CR/PR 派生"},
    {"from": "attr.diagnosis.cnlc_stage", "to": "concept.hcc.cnlc_staging", "cat": "SEMANTIC", "type": "MAPS_TO_CONCEPT", "rationale": "分期码映射 CNLC-2024 体系概念"},
    {"from": "attr.followup.mrecist_response", "to": "concept.hcc.mrecist", "cat": "SEMANTIC", "type": "MAPS_TO_CONCEPT", "rationale": "疗效码映射 mRECIST 概念"},
    {"from": "attr.treatment.t0_exec_end", "to": "concept.hcc.t0_actual_exec", "cat": "SEMANTIC", "type": "MAPS_TO_CONCEPT", "rationale": "T0 字段映射实际执行完成概念"},
    {"from": "attr.treatment.modality", "to": "concept.hcc.locoregional_modality", "cat": "SEMANTIC", "type": "MAPS_TO_CONCEPT", "rationale": "模态码映射局部治疗概念"},
    {"from": "entity.hcc_diagnosis", "to": "entity.treatment_session", "cat": "SEMANTIC", "type": "PRECEDES", "rationale": "基线诊断先于介入程次"},
    {"from": "entity.treatment_session", "to": "entity.followup_visit", "cat": "SEMANTIC", "type": "PRECEDES", "rationale": "治疗完成 T0 先于随访评估"},
    {"from": "entity.hcc_diagnosis", "to": "process.hcc.chance_care_pathway", "cat": "SEMANTIC", "type": "PART_OF", "rationale": "专病诊断属于 CHANCE 路径"},
    {"from": "entity.treatment_session", "to": "process.hcc.chance_care_pathway", "cat": "SEMANTIC", "type": "PART_OF", "rationale": "介入程次属于 CHANCE 路径"},
    {"from": "entity.followup_visit", "to": "process.hcc.chance_care_pathway", "cat": "SEMANTIC", "type": "PART_OF", "rationale": "随访属于 CHANCE 路径"},
    {"from": "concept.hcc.t0_actual_exec", "to": "metric.hcc.orr_rate", "cat": "CAUSAL", "type": "CONSTRAINS", "rationale": "ORR/随访归因必须以实际 T0 为锚，禁止计费日"},
    {"from": "concept.hcc.locoregional_modality", "to": "metric.hcc.session_count", "cat": "CAUSAL", "type": "CONSTRAINS", "rationale": "统计介入次数时须按 modality 区分 TACE 与 HAIC"},
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
    "attr.diagnosis.cnlc_stage": [
        ("Ia", "CNLC Ⅰa", ["Ia", "I a", "1a"]),
        ("Ib", "CNLC Ⅰb", ["Ib", "I b", "1b"]),
        ("IIa", "CNLC Ⅱa", ["IIa", "II a", "2a"]),
        ("IIb", "CNLC Ⅱb", ["IIb", "II b", "2b"]),
        ("IIIa", "CNLC Ⅲa", ["IIIa", "III a", "3a"]),
        ("IIIb", "CNLC Ⅲb", ["IIIb", "III b", "3b"]),
        ("IV", "CNLC Ⅳ", ["IV", "4期"]),
    ],
    "attr.diagnosis.child_pugh_class": [
        ("A", "Child-Pugh A", ["A级"]),
        ("B", "Child-Pugh B", ["B级"]),
        ("C", "Child-Pugh C", ["C级"]),
    ],
    "attr.treatment.modality": [
        ("cTACE", "常规 TACE", ["conventional TACE", "c-TACE"]),
        ("DEB-TACE", "载药微球 TACE", ["DEB TACE", "D-TACE"]),
        ("HAIC", "肝动脉灌注化疗", ["hepatic arterial infusion chemotherapy"]),
    ],
    "attr.followup.mrecist_response": [
        ("CR", "完全缓解", ["Complete Response", "完全缓解"]),
        ("PR", "部分缓解", ["Partial Response", "部分缓解"]),
        ("SD", "疾病稳定", ["Stable Disease"]),
        ("PD", "疾病进展", ["Progressive Disease"]),
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

    synthea_db = try_provision_synthea_stubs()
    synthea_ready = bool(synthea_db)
    synthea_ds_id: str | None = None
    synthea_datasets: dict[str, dict[str, Any]] = {}
    wh_synthea_id: str | None = None
    if synthea_db:
        try:
            synthea_ds_id = ensure_datasource(
                cli, cred_id, "Synthea CHANCE-HCC 合成仓", synthea_db,
                "Synthea CHANCE-HCC 合成队列 stub（schema synthea_hcc；id 前缀 synthea:）",
            )
        except Exception as ex:  # noqa: BLE001 — 第三仓失败不阻断 MIMIC/eICU
            print(f"[WARN] Synthea datasource unavailable: {ex}")
            synthea_ready = False

    # 2. Catalog Sources & Metadata Refresh
    mimic_datasets = ensure_catalog_and_refresh(cli, mimic_ds_id, "MIMIC-IV 数据目录")
    eicu_datasets = ensure_catalog_and_refresh(cli, eicu_ds_id, "eICU-CRD 数据目录")
    if synthea_ready and synthea_ds_id:
        try:
            synthea_datasets = ensure_catalog_and_refresh(cli, synthea_ds_id, "Synthea CHANCE-HCC 数据目录")
        except Exception as ex:  # noqa: BLE001
            print(f"[WARN] Synthea catalog refresh failed: {ex}")
            synthea_ready = False

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
    if synthea_ready and synthea_ds_id:
        wh_synthea_id = ensure_warehouse(cli, {
            "datasourceId": synthea_ds_id,
            "code": "synthea_hcc",
            "name": "Synthea CHANCE-HCC 合成仓",
            "warehouseRole": "SECONDARY",
            "cohortCode": "SYNTHEA_HCC",
            "dialect": "POSTGRESQL",
            "idNamespacePrefix": "synthea:",
            "combineMode": "COHORT_UNION",
            "federationEngine": "DUCKDB",
            "priority": 30,
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

    # 专病表：cohort / diagnosis / tace_session / followup / imaging（不造 MIMIC 同构表）
    ds_s_pat = try_find_dataset(synthea_datasets, "synthea_hcc.cohort_patient") if synthea_ready else None
    ds_s_tace = try_find_dataset(synthea_datasets, "synthea_hcc.tace_session") if synthea_ready else None
    ds_s_dx = try_find_dataset(synthea_datasets, "synthea_hcc.hcc_diagnosis") if synthea_ready else None
    ds_s_fup = try_find_dataset(synthea_datasets, "synthea_hcc.followup_mrecist") if synthea_ready else None
    ds_s_img = try_find_dataset(synthea_datasets, "synthea_hcc.imaging_report_zh") if synthea_ready else None
    lt_s_pat = lt_s_tace = lt_s_dx = lt_s_fup = lt_s_img = None
    cols_s_pat: dict[str, str] = {}
    cols_s_tace: dict[str, str] = {}
    cols_s_dx: dict[str, str] = {}
    cols_s_fup: dict[str, str] = {}
    cols_s_img: dict[str, str] = {}
    if wh_synthea_id and ds_s_pat and ds_s_tace and ds_s_dx:
        lt_s_pat = ensure_logical_table(cli, wh_synthea_id, ds_s_pat["id"], "lt_synthea_cohort_patient", "LOOKUP", 300)
        lt_s_tace = ensure_logical_table(cli, wh_synthea_id, ds_s_tace["id"], "lt_synthea_tace_session", "FACT", 400)
        lt_s_dx = ensure_logical_table(cli, wh_synthea_id, ds_s_dx["id"], "lt_synthea_hcc_diagnosis", "FACT", 80)
        cols_s_pat = get_columns_map(cli, ds_s_pat["id"])
        cols_s_tace = get_columns_map(cli, ds_s_tace["id"])
        cols_s_dx = get_columns_map(cli, ds_s_dx["id"])
        if ds_s_fup:
            lt_s_fup = ensure_logical_table(cli, wh_synthea_id, ds_s_fup["id"], "lt_synthea_followup_mrecist", "FACT", 450)
            cols_s_fup = get_columns_map(cli, ds_s_fup["id"])
        if ds_s_img:
            lt_s_img = ensure_logical_table(cli, wh_synthea_id, ds_s_img["id"], "lt_synthea_imaging_report", "FACT", 500)
            cols_s_img = get_columns_map(cli, ds_s_img["id"])
        print("[OK] logical tables provisioned for Synthea CHANCE-HCC (synthea:)")
    elif synthea_ready:
        print("[WARN] Synthea catalog datasets incomplete — skip synthea logical tables/mappings")
        wh_synthea_id = None

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

    if wh_synthea_id and lt_s_pat and lt_s_tace and lt_s_dx and ds_s_pat and ds_s_tace and ds_s_dx:
        mapping_specs.extend([
            ("entity.patient", {"mappingRole": "ID", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_pat, "catalogDatasetId": ds_s_pat["id"], "catalogColumnId": cols_s_pat.get("patient_id"), "isLookupTable": True, "priority": 30}),
            ("entity.hcc_diagnosis", {"mappingRole": "ID", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_dx, "catalogDatasetId": ds_s_dx["id"], "catalogColumnId": cols_s_dx.get("diagnosis_id"), "priority": 30}),
            ("entity.treatment_session", {"mappingRole": "ID", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_tace, "catalogDatasetId": ds_s_tace["id"], "catalogColumnId": cols_s_tace.get("session_id"), "priority": 30}),
            ("attr.patient.gender", {"mappingRole": "CODE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_pat, "catalogDatasetId": ds_s_pat["id"], "catalogColumnId": cols_s_pat.get("gender_std"), "priority": 30}),
            ("attr.patient.age_group", {"mappingRole": "CODE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_pat, "catalogDatasetId": ds_s_pat["id"], "catalogColumnId": cols_s_pat.get("age_group_std"), "priority": 30}),
            ("attr.patient.race", {"mappingRole": "CODE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_pat, "catalogDatasetId": ds_s_pat["id"], "catalogColumnId": cols_s_pat.get("race_std"), "priority": 30}),
            ("attr.outcome.hospital_status", {"mappingRole": "CODE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_pat, "catalogDatasetId": ds_s_pat["id"], "catalogColumnId": cols_s_pat.get("survival_status"), "valueNormalization": {"Alive": "ALIVE", "Dead": "EXPIRED", "Lost_to_FollowUp": "UNKNOWN"}, "priority": 30}),
            ("attr.diagnosis.icd_code", {"mappingRole": "CODE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_dx, "catalogDatasetId": ds_s_dx["id"], "catalogColumnId": cols_s_dx.get("icd_code"), "priority": 30}),
            ("attr.diagnosis.cnlc_stage", {"mappingRole": "CODE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_dx, "catalogDatasetId": ds_s_dx["id"], "catalogColumnId": cols_s_dx.get("hcc_cnlc_stage"), "priority": 30}),
            ("attr.diagnosis.child_pugh_class", {"mappingRole": "CODE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_dx, "catalogDatasetId": ds_s_dx["id"], "catalogColumnId": cols_s_dx.get("hcc_child_pugh_class"), "priority": 30}),
            ("attr.diagnosis.pvtt_grade", {"mappingRole": "CODE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_dx, "catalogDatasetId": ds_s_dx["id"], "catalogColumnId": cols_s_dx.get("hcc_pvtt_grade"), "priority": 30}),
            ("attr.treatment.modality", {"mappingRole": "CODE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_tace, "catalogDatasetId": ds_s_tace["id"], "catalogColumnId": cols_s_tace.get("modality"), "priority": 30}),
            ("attr.treatment.session_seq", {"mappingRole": "CODE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_tace, "catalogDatasetId": ds_s_tace["id"], "catalogColumnId": cols_s_tace.get("session_seq"), "priority": 30}),
            ("attr.treatment.t0_exec_end", {"mappingRole": "TIME_KEY", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_tace, "catalogDatasetId": ds_s_tace["id"], "catalogColumnId": cols_s_tace.get("actual_exec_end_t0"), "priority": 30}),
            ("fact.hcc.baseline_afp", {"mappingRole": "MEASURE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_dx, "catalogDatasetId": ds_s_dx["id"], "catalogColumnId": cols_s_dx.get("baseline_afp_ng_ml"), "sourceUnit": "ng/mL", "targetUnit": "ng/mL", "priority": 30}),
            ("metric.patient.count", {"mappingRole": "MEASURE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_pat, "catalogDatasetId": ds_s_pat["id"], "catalogColumnId": cols_s_pat.get("patient_id"), "priority": 30}),
            ("metric.patient.avg_age", {"mappingRole": "MEASURE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_pat, "catalogDatasetId": ds_s_pat["id"], "catalogColumnId": cols_s_pat.get("age_years"), "sourceUnit": "岁", "targetUnit": "岁", "priority": 30}),
            ("metric.hcc.session_count", {"mappingRole": "MEASURE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_tace, "catalogDatasetId": ds_s_tace["id"], "catalogColumnId": cols_s_tace.get("session_id"), "priority": 30}),
            ("metric.hcc.dead_count", {"mappingRole": "MEASURE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_pat, "catalogDatasetId": ds_s_pat["id"], "catalogColumnId": cols_s_pat.get("survival_status"), "expressionSql": "CASE WHEN survival_status = 'Dead' THEN 1 ELSE 0 END", "priority": 30}),
        ])
        if lt_s_fup and ds_s_fup:
            mapping_specs.extend([
                ("entity.followup_visit", {"mappingRole": "ID", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_fup, "catalogDatasetId": ds_s_fup["id"], "catalogColumnId": cols_s_fup.get("followup_id"), "priority": 30}),
                ("attr.followup.mrecist_response", {"mappingRole": "CODE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_fup, "catalogDatasetId": ds_s_fup["id"], "catalogColumnId": cols_s_fup.get("mrecist_response"), "priority": 30}),
                ("fact.hcc.days_from_t0", {"mappingRole": "MEASURE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_fup, "catalogDatasetId": ds_s_fup["id"], "catalogColumnId": cols_s_fup.get("days_from_t0"), "sourceUnit": "天", "targetUnit": "天", "priority": 30}),
                ("metric.hcc.followup_visit_count", {"mappingRole": "MEASURE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_fup, "catalogDatasetId": ds_s_fup["id"], "catalogColumnId": cols_s_fup.get("followup_id"), "priority": 30}),
                ("metric.hcc.orr_rate", {"mappingRole": "MEASURE", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_fup, "catalogDatasetId": ds_s_fup["id"], "catalogColumnId": cols_s_fup.get("mrecist_response"), "expressionSql": "CASE WHEN mrecist_response IN ('CR','PR') THEN 100.0 ELSE 0.0 END", "sourceUnit": "%", "targetUnit": "%", "priority": 30}),
            ])
        if lt_s_img and ds_s_img:
            mapping_specs.append(
                ("entity.imaging_report", {"mappingRole": "ID", "warehouseId": wh_synthea_id, "logicalTableId": lt_s_img, "catalogDatasetId": ds_s_img["id"], "catalogColumnId": cols_s_img.get("report_id"), "priority": 30}),
            )

    cached_mappings: dict[str, list[dict[str, Any]]] = {}
    for code, m_payload in mapping_specs:
        oid = obj_by_code[code]["id"]
        if oid not in cached_mappings:
            cached_mappings[oid] = cli.call("GET", f"/api/v1/metadata/semantic/objects/{oid}/mappings")
        ensure_mapping(cli, oid, cached_mappings[oid], m_payload)
    print(f"[OK] provisioned {len(mapping_specs)} cross-warehouse heterogeneous mappings (MIMIC-IV + eICU-CRD"
          f"{' + Synthea' if wh_synthea_id else ''})")

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

    suffix = " + Synthea(CHANCE-HCC / synthea:)" if wh_synthea_id else " (Synthea skipped)"
    print(f"\nALL MIMIC-IV + eICU-CRD{suffix} CONFIGURATION & ONTOLOGY STANDARDIZATION COMPLETE!")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
