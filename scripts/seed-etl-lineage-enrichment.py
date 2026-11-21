#!/usr/bin/env python3
"""把 clinical ETL STD / 脱敏 / 专病路径写进列级血缘，并核对治理消费节点是否进影响分析。

背景: seed-etl-governance-consumption.py 只在已有 v_*_std 上挂映射/规则/报告，
映射大多已存在，所以 overview 的 activeColumnEdges 不会变。本脚本按
knowledge/research/2026-11-20-clinical-multisource-etl-lineage-enrichment.md
A/D/E 类补齐「源表 → STD 视图」多跳边 + 脱敏边，使字段数与可达深度可观测增加。

用法:
  python3 scripts/seed-etl-lineage-enrichment.py
  # 可选再跑: python3 scripts/seed-etl-governance-consumption.py

环境: DG_BASE_URL / DG_USERNAME / DG_PASSWORD
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from typing import Any

BASE = os.environ.get("DG_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
USERNAME = os.environ.get("DG_USERNAME", "admin")
PASSWORD = os.environ.get("DG_PASSWORD", "admin123")
SOURCE_REF = "seed-etl-lineage-enrichment"

for k in ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY"):
    os.environ.pop(k, None)
os.environ["NO_PROXY"] = "*"
os.environ["no_proxy"] = "*"


def call(method: str, path: str, body: Any = None, token: str = "", ws: str = "") -> Any:
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if ws:
        headers["X-Workspace-Id"] = ws
    req = urllib.request.Request(f"{BASE}{path}", data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{method} {path} HTTP {e.code}: {e.read().decode()}") from e
    if not payload.get("success"):
        raise RuntimeError(f"{method} {path} failed: {payload}")
    return payload.get("data")


def index_datasets(token: str, ws: str) -> dict[str, dict[str, Any]]:
    by_q: dict[str, dict[str, Any]] = {}
    for src in call("GET", "/api/v1/metadata/catalog/sources", token=token, ws=ws):
        page = call(
            "GET",
            f"/api/v1/metadata/catalog/sources/{src['id']}/datasets?page=1&size=300",
            token=token,
            ws=ws,
        )
        for ds in page.get("records") or []:
            q = (ds.get("qualifiedName") or ds.get("name") or "").lower()
            cols = call(
                "GET",
                f"/api/v1/metadata/catalog/datasets/{ds['id']}/columns",
                token=token,
                ws=ws,
            )
            by_q[q] = {"dataset": ds, "cols": {c["name"].lower(): c for c in cols}}
    return by_q


def find(by_q: dict[str, dict[str, Any]], *suffixes: str) -> dict[str, Any]:
    for suf in suffixes:
        s = suf.lower()
        if s in by_q:
            return by_q[s]
        for k, v in by_q.items():
            if k.endswith("." + s) or k.endswith(s) or k == s:
                return v
    raise KeyError(f"dataset not found: {suffixes}")


def node(ds_entry: dict[str, Any], col: str) -> dict[str, Any]:
    c = ds_entry["cols"].get(col.lower())
    if c is None:
        raise KeyError(
            f"column {col!r} missing in {ds_entry['dataset'].get('qualifiedName')}; "
            f"have={sorted(ds_entry['cols'])[:20]}"
        )
    ds = ds_entry["dataset"]
    qname = f"{(ds.get('qualifiedName') or ds.get('name')).lower()}.{col.lower()}"
    return {
        "nodeType": "COLUMN",
        "qualifiedName": qname,
        "catalogDatasetId": ds["id"],
        "catalogColumnId": c["id"],
        "displayName": c["name"],
    }


def edge(
    frm: dict[str, Any],
    to: dict[str, Any],
    relation: str = "COPY",
    expr: str | None = None,
    dependency: str = "DIRECT",
    confidence: float = 1.0,
    masking: bool = False,
    evidence: str | None = None,
) -> dict[str, Any]:
    body: dict[str, Any] = {
        "from": frm,
        "to": to,
        "relation": relation,
        "transformExpr": expr,
        "confidence": confidence,
        "origin": "AUTO",
        "dependencyType": dependency,
        "maskingApplied": masking,
    }
    if evidence:
        body["maskingEvidence"] = evidence
    return body


def record_job(
    token: str, ws: str, name: str, job_type: str, edges: list[dict[str, Any]]
) -> dict[str, Any]:
    body = {
        "name": name,
        "jobType": job_type,
        "sourceRef": SOURCE_REF,
        "sqlHash": None,
        "edges": edges,
        "parseStatus": "OK",
        "parseMessage": "seeded from clinical ETL enrichment research",
    }
    res = call("POST", "/api/v1/metadata/lineage/jobs", body, token=token, ws=ws)
    print(f"[OK] job {name!r} type={job_type} -> {res['jobId']} edges={res['edges']}")
    return res


def try_impact(token: str, ws: str, column_id: str, label: str) -> None:
    try:
        data = call(
            "GET",
            f"/api/v1/metadata/lineage/columns/{column_id}/impact?depth=5&includeGovernance=true",
            token=token,
            ws=ws,
        )
    except RuntimeError as e:
        print(f"[WARN] impact {label}: {e}")
        return
    nodes = data.get("nodes") or data.get("items") or []
    # API shape may be {nodes,edges,summary} or list — tolerate both
    if isinstance(data, dict) and "summary" in data:
        print(f"[IMPACT] {label}: summary={data.get('summary')}")
    elif isinstance(nodes, list):
        types: dict[str, int] = {}
        for n in nodes:
            t = (n.get("refType") or n.get("nodeType") or n.get("type") or "?")
            types[t] = types.get(t, 0) + 1
        print(f"[IMPACT] {label}: nodes={len(nodes)} byType={types}")
    else:
        print(f"[IMPACT] {label}: keys={list(data.keys()) if isinstance(data, dict) else type(data)}")


def main() -> int:
    login = call("POST", "/api/v1/auth/login", {"username": USERNAME, "password": PASSWORD})
    token = login["token"]
    ws = login["currentWorkspaceId"]
    before = call("GET", "/api/v1/metadata/lineage/overview", token=token, ws=ws)
    print(
        "[BEFORE]",
        f"datasets={before['datasetsWithLineage']}",
        f"edges={before['activeColumnEdges']}",
        f"jobs={before['activeJobs']}",
    )

    by_q = index_datasets(token, ws)
    print(f"[OK] indexed {len(by_q)} catalog datasets")

    # MIMIC sources + STD
    m_pat = find(by_q, "mimic.mimiciv_hosp.patients", "mimiciv_hosp.patients")
    m_adm = find(by_q, "mimic.mimiciv_hosp.admissions", "mimiciv_hosp.admissions")
    m_icu = find(by_q, "mimic.mimiciv_icu.icustays", "mimiciv_icu.icustays")
    m_lab = find(by_q, "mimic.mimiciv_hosp.labevents", "mimiciv_hosp.labevents")
    m_dlab = find(by_q, "mimic.mimiciv_hosp.d_labitems", "mimiciv_hosp.d_labitems")
    m_dx = find(by_q, "mimic.mimiciv_hosp.diagnoses_icd", "mimiciv_hosp.diagnoses_icd")
    m_dicd = find(by_q, "mimic.mimiciv_hosp.d_icd_diagnoses", "mimiciv_hosp.d_icd_diagnoses")
    m_icu_std = find(by_q, "mimic.mimiciv_icu.v_icu_stay_std", "mimiciv_icu.v_icu_stay_std")
    m_lab_std = find(by_q, "mimic.mimiciv_hosp.v_lab_std", "mimiciv_hosp.v_lab_std")
    m_dx_std = find(by_q, "mimic.mimiciv_hosp.v_diagnosis_std", "mimiciv_hosp.v_diagnosis_std")

    # eICU
    e_pat = find(by_q, "eicu.eicu_crd.patient", "eicu_crd.patient")
    e_lab = find(by_q, "eicu.eicu_crd.lab", "eicu_crd.lab")
    e_dx = find(by_q, "eicu.eicu_crd.diagnosis", "eicu_crd.diagnosis")
    e_icu_std = find(by_q, "eicu.eicu_crd.v_icu_stay_std", "eicu_crd.v_icu_stay_std")
    e_lab_std = find(by_q, "eicu.eicu_crd.v_lab_std", "eicu_crd.v_lab_std")
    e_dx_std = find(by_q, "eicu.eicu_crd.v_diagnosis_std", "eicu_crd.v_diagnosis_std")

    # Synthea
    s_tace = find(by_q, "synthea.synthea_hcc.tace_session", "synthea_hcc.tace_session")
    s_fup = find(by_q, "synthea.synthea_hcc.followup_mrecist", "synthea_hcc.followup_mrecist")

    # ── A. MIMIC ODS→STD ───────────────────────────────────────────────
    record_job(
        token,
        ws,
        "etl_mimic_icu_stay_std",
        "ETL_PIPELINE",
        [
            edge(node(m_pat, "subject_id"), node(m_icu_std, "patient_id"), "COPY", "patients.subject_id"),
            edge(node(m_pat, "gender"), node(m_icu_std, "gender_std"), "TRANSFORM",
                 "CODESET_LOOKUP(attr.patient.gender)"),
            edge(node(m_adm, "hadm_id"), node(m_icu_std, "admission_id"), "COPY"),
            edge(node(m_adm, "hospital_expire_flag"), node(m_icu_std, "hospital_expired_flag"),
                 "TRANSFORM", "CASE hospital_expire_flag WHEN 1 THEN true ELSE false END"),
            edge(node(m_icu, "stay_id"), node(m_icu_std, "icu_stay_id"), "COPY"),
            edge(node(m_icu, "los"), node(m_icu_std, "icu_los_hours"), "TRANSFORM", "los * 24"),
            edge(node(m_icu, "first_careunit"), node(m_icu_std, "icu_unit_std"), "TRANSFORM",
                 "normalize_careunit(first_careunit)"),
            edge(node(m_adm, "subject_id"), node(m_icu_std, "patient_id"), "COPY",
                 dependency="INDIRECT", confidence=0.9),
        ],
    )
    record_job(
        token,
        ws,
        "etl_mimic_lab_std",
        "ETL_PIPELINE",
        [
            edge(node(m_lab, "labevent_id"), node(m_lab_std, "lab_event_id"), "COPY"),
            edge(node(m_lab, "subject_id"), node(m_lab_std, "patient_id"), "COPY"),
            edge(node(m_lab, "hadm_id"), node(m_lab_std, "admission_id"), "COPY"),
            edge(node(m_lab, "valuenum"), node(m_lab_std, "lab_value_num"), "COPY"),
            edge(node(m_lab, "valueuom"), node(m_lab_std, "lab_unit"), "COPY"),
            edge(node(m_lab, "flag"), node(m_lab_std, "abnormal_flag"), "TRANSFORM",
                 "normalize_flag(flag)"),
            edge(node(m_lab, "charttime"), node(m_lab_std, "lab_time"), "COPY"),
            edge(node(m_dlab, "label"), node(m_lab_std, "lab_name"), "TRANSFORM",
                 "d_labitems.label JOIN ON itemid", dependency="DIRECT"),
            edge(node(m_lab, "itemid"), node(m_lab_std, "lab_name"), "TRANSFORM",
                 "JOIN d_labitems", dependency="INDIRECT", confidence=0.85),
        ],
    )
    record_job(
        token,
        ws,
        "etl_mimic_diagnosis_std",
        "ETL_PIPELINE",
        [
            edge(node(m_dx, "subject_id"), node(m_dx_std, "patient_id"), "COPY"),
            edge(node(m_dx, "hadm_id"), node(m_dx_std, "admission_id"), "COPY"),
            edge(node(m_dx, "icd_code"), node(m_dx_std, "icd_code"), "COPY"),
            edge(node(m_dx, "icd_version"), node(m_dx_std, "icd_version"), "COPY"),
            edge(node(m_dx, "seq_num"), node(m_dx_std, "diagnosis_priority_rank"), "COPY"),
            edge(node(m_dicd, "long_title"), node(m_dx_std, "diagnosis_name"), "TRANSFORM",
                 "d_icd_diagnoses.long_title JOIN ON icd_code+version"),
        ],
    )

    # ── A. eICU ODS→STD ────────────────────────────────────────────────
    record_job(
        token,
        ws,
        "etl_eicu_icu_stay_std",
        "ETL_PIPELINE",
        [
            edge(node(e_pat, "uniquepid"), node(e_icu_std, "patient_id"), "COPY"),
            edge(node(e_pat, "patienthealthsystemstayid"), node(e_icu_std, "admission_id"), "COPY"),
            edge(node(e_pat, "patientunitstayid"), node(e_icu_std, "icu_stay_id"), "COPY"),
            edge(node(e_pat, "gender"), node(e_icu_std, "gender_std"), "TRANSFORM",
                 "CODESET_LOOKUP(attr.patient.gender)"),
            edge(node(e_pat, "age"), node(e_icu_std, "age_years"), "TRANSFORM",
                 "parse_age(age)"),
            edge(node(e_pat, "unittype"), node(e_icu_std, "icu_unit_std"), "TRANSFORM",
                 "normalize_unittype(unittype)"),
            edge(node(e_pat, "hospitaldischargestatus"), node(e_icu_std, "hospital_outcome_std"),
                 "TRANSFORM", "normalize_discharge(hospitaldischargestatus)"),
        ],
    )
    record_job(
        token,
        ws,
        "etl_eicu_lab_std",
        "ETL_PIPELINE",
        [
            edge(node(e_lab, "labid"), node(e_lab_std, "lab_event_id"), "COPY"),
            edge(node(e_lab, "patientunitstayid"), node(e_lab_std, "icu_stay_id"), "COPY"),
            edge(node(e_lab, "labname"), node(e_lab_std, "lab_name"), "COPY"),
            edge(node(e_lab, "labresult"), node(e_lab_std, "lab_value_num"), "TRANSFORM",
                 "CAST(labresult AS DOUBLE)"),
            edge(node(e_lab, "labmeasurenamesystem"), node(e_lab_std, "lab_unit"), "COPY"),
            edge(node(e_lab, "labresultoffset"), node(e_lab_std, "lab_offset_min"), "COPY"),
        ],
    )
    record_job(
        token,
        ws,
        "etl_eicu_diagnosis_std",
        "ETL_PIPELINE",
        [
            edge(node(e_dx, "diagnosisid"), node(e_dx_std, "diagnosis_id"), "COPY"),
            edge(node(e_dx, "patientunitstayid"), node(e_dx_std, "icu_stay_id"), "COPY"),
            edge(node(e_dx, "icd9code"), node(e_dx_std, "icd_code"), "TRANSFORM",
                 "normalize_icd(icd9code)"),
            edge(node(e_dx, "diagnosisstring"), node(e_dx_std, "diagnosis_name"), "TRANSFORM",
                 "trim(diagnosisstring)"),
        ],
    )

    # ── D. 脱敏演示 ────────────────────────────────────────────────────
    # 写入 STD 上的 patient_id 派生 token（同库列演示 masking_applied）
    record_job(
        token,
        ws,
        "etl_mimic_patient_token",
        "ETL_PIPELINE",
        [
            edge(
                node(m_pat, "subject_id"),
                node(m_icu_std, "global_patient_key"),
                "TRANSFORM",
                "sha256(subject_id || salt)",
                masking=True,
                evidence="HASH_SHA256(subject_id)",
            ),
        ],
    )

    # ── B. Synthea 程次→随访 ORR 路径加厚 ──────────────────────────────
    record_job(
        token,
        ws,
        "etl_synthea_followup_outcome",
        "ETL_PIPELINE",
        [
            edge(node(s_tace, "session_id"), node(s_fup, "session_id"), "COPY"),
            edge(node(s_tace, "patient_id"), node(s_fup, "patient_id"), "COPY"),
            edge(
                node(s_tace, "actual_exec_end_t0"),
                node(s_fup, "days_from_t0"),
                "TRANSFORM",
                "visit_date - actual_exec_end_t0",
            ),
            # ORR 指标消费锚在 followup.mrecist_response → 由语义映射 / impact 治理桥接，
            # 这里加一条从程次 modality 到应答的 INDIRECT 分层边，避免自环。
            edge(
                node(s_tace, "modality"),
                node(s_fup, "mrecist_response"),
                "TRANSFORM",
                "outcome stratified by modality (feeds metric.hcc.orr_rate)",
                dependency="INDIRECT",
                confidence=0.7,
            ),
        ],
    )

    # ── C. 跨仓 lab 对齐桥（INDIRECT） ─────────────────────────────────
    record_job(
        token,
        ws,
        "etl_unified_lab_fact",
        "ETL_PIPELINE",
        [
            edge(
                node(m_lab_std, "lab_name"),
                node(e_lab_std, "lab_name"),
                "DERIVE",
                "COHORT_UNION namespace mimic: ↔ eicu: lab_name",
                dependency="INDIRECT",
                confidence=0.5,
            ),
            edge(
                node(m_lab_std, "lab_value_num"),
                node(e_lab_std, "lab_value_num"),
                "DERIVE",
                "COHORT_UNION measure alignment (demo)",
                dependency="INDIRECT",
                confidence=0.45,
            ),
        ],
    )

    after = call("GET", "/api/v1/metadata/lineage/overview", token=token, ws=ws)
    print(
        "[AFTER]",
        f"datasets={after['datasetsWithLineage']}",
        f"edges={after['activeColumnEdges']}",
        f"jobs={after['activeJobs']}",
    )
    print(
        "[DELTA]",
        f"datasets={after['datasetsWithLineage'] - before['datasetsWithLineage']:+d}",
        f"edges={after['activeColumnEdges'] - before['activeColumnEdges']:+d}",
        f"jobs={after['activeJobs'] - before['activeJobs']:+d}",
    )

    # 表级图深度：以 MIMIC v_lab_std 为中心
    lab_ds = m_lab_std["dataset"]["id"]
    g = call(
        "GET",
        f"/api/v1/metadata/lineage/datasets/{lab_ds}/graph?direction=both&depth=3",
        token=token,
        ws=ws,
    )
    print(
        f"[TABLE-GRAPH] {lab_ds} v_lab_std depth=3: "
        f"nodes={len(g.get('nodes') or [])} edges={len(g.get('edges') or [])}"
    )

    # 字段影响分析（应带上治理消费者）
    lab_value_col = m_lab_std["cols"]["lab_value_num"]["id"]
    gender_col = m_icu_std["cols"]["gender_std"]["id"]
    try_impact(token, ws, lab_value_col, "mimic.v_lab_std.lab_value_num")
    try_impact(token, ws, gender_col, "mimic.v_icu_stay_std.gender_std")

    if after["activeColumnEdges"] <= before["activeColumnEdges"]:
        print("[FAIL] activeColumnEdges did not increase", file=sys.stderr)
        return 1
    print("DONE — refresh http://127.0.0.1:5173/metadata/lineage")
    print("Hint: also re-run scripts/seed-etl-governance-consumption.py for RULE/REPORT anchors")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        raise SystemExit(2)
