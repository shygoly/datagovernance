#!/usr/bin/env python3
"""灌入演示用字段级血缘（幂等）：MIMIC / eICU / Synthea 跨表边，驱动概览与表级聚合图。

用法:
  python3 scripts/seed-lineage-demo.py
"""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from typing import Any

BASE = os.environ.get("DG_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
USERNAME = os.environ.get("DG_USERNAME", "admin")
PASSWORD = os.environ.get("DG_PASSWORD", "admin123")

for k in ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY"):
    os.environ.pop(k, None)
os.environ["NO_PROXY"] = "*"


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
            if not payload.get("success"):
                raise RuntimeError(f"{method} {path}: {payload}")
            return payload.get("data")
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{method} {path} HTTP {e.code}: {e.read().decode()}") from e


def index_datasets(token: str, ws: str) -> dict[str, dict[str, Any]]:
    by_q: dict[str, dict[str, Any]] = {}
    for src in call("GET", "/api/v1/metadata/catalog/sources", token=token, ws=ws):
        page = call(
            "GET",
            f"/api/v1/metadata/catalog/sources/{src['id']}/datasets?page=1&size=200",
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
            colmap = {c["name"].lower(): c for c in cols}
            by_q[q] = {"dataset": ds, "cols": colmap}
    return by_q


def find(by_q: dict[str, dict[str, Any]], *suffixes: str) -> dict[str, Any]:
    for suf in suffixes:
        s = suf.lower()
        if s in by_q:
            return by_q[s]
        for k, v in by_q.items():
            if k.endswith("." + s) or k.endswith(s):
                return v
    raise KeyError(f"dataset not found: {suffixes}")


def node(ds_entry: dict[str, Any], col: str) -> dict[str, Any]:
    c = ds_entry["cols"].get(col.lower())
    if c is None:
        raise KeyError(f"column {col} missing in {ds_entry['dataset'].get('qualifiedName')}")
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
) -> dict[str, Any]:
    return {
        "from": frm,
        "to": to,
        "relation": relation,
        "transformExpr": expr,
        "confidence": confidence,
        "origin": "AUTO",
        "dependencyType": dependency,
        "maskingApplied": False,
    }


def record_job(token: str, ws: str, name: str, job_type: str, edges: list[dict[str, Any]]) -> None:
    body = {
        "name": name,
        "jobType": job_type,
        "sourceRef": "seed-lineage-demo",
        "sqlHash": None,
        "edges": edges,
        "parseStatus": "OK",
    }
    res = call("POST", "/api/v1/metadata/lineage/jobs", body, token=token, ws=ws)
    print(f"[OK] job {name!r} -> {res['jobId']} edges={res['edges']}")


def main() -> int:
    login = call("POST", "/api/v1/auth/login", {"username": USERNAME, "password": PASSWORD})
    token = login["token"]
    ws = login["currentWorkspaceId"]
    print(f"[LOGIN] workspace={ws}")

    by_q = index_datasets(token, ws)
    print(f"[OK] indexed {len(by_q)} catalog datasets")

    m_pat = find(by_q, "mimic.mimiciv_hosp.patients", "mimiciv_hosp.patients")
    m_adm = find(by_q, "mimic.mimiciv_hosp.admissions", "mimiciv_hosp.admissions")
    m_icu = find(by_q, "mimic.mimiciv_icu.icustays", "mimiciv_icu.icustays")
    m_dx = find(by_q, "mimic.mimiciv_hosp.diagnoses_icd", "mimiciv_hosp.diagnoses_icd")
    e_pat = find(by_q, "eicu.eicu_crd.patient", "eicu_crd.patient")
    e_dx = find(by_q, "eicu.eicu_crd.diagnosis", "eicu_crd.diagnosis")
    s_pat = find(by_q, "synthea.synthea_hcc.cohort_patient", "synthea_hcc.cohort_patient")
    s_dx = find(by_q, "synthea.synthea_hcc.hcc_diagnosis", "synthea_hcc.hcc_diagnosis")
    s_tace = find(by_q, "synthea.synthea_hcc.tace_session", "synthea_hcc.tace_session")
    s_fup = find(by_q, "synthea.synthea_hcc.followup_mrecist", "synthea_hcc.followup_mrecist")

    # ── MIMIC：patients → admissions → icustays / diagnoses ─────────────
    record_job(
        token,
        ws,
        "mimic_patient_to_admission",
        "SPARK",
        [
            edge(node(m_pat, "subject_id"), node(m_adm, "subject_id"), "COPY", "patients.subject_id"),
            edge(
                node(m_pat, "gender"),
                node(m_adm, "marital_status"),
                "TRANSFORM",
                "demographic enrichment (demo)",
                "INDIRECT",
                0.6,
            ),
        ],
    )
    record_job(
        token,
        ws,
        "mimic_admission_to_icu_dx",
        "SPARK",
        [
            edge(node(m_adm, "subject_id"), node(m_icu, "subject_id"), "COPY"),
            edge(node(m_adm, "hadm_id"), node(m_icu, "hadm_id"), "COPY"),
            edge(node(m_adm, "subject_id"), node(m_dx, "subject_id"), "COPY"),
            edge(node(m_adm, "hadm_id"), node(m_dx, "hadm_id"), "COPY"),
            edge(
                node(m_adm, "admittime"),
                node(m_icu, "intime"),
                "TRANSFORM",
                "GREATEST(admittime, unit_in)",
            ),
        ],
    )

    # ── eICU：patient → diagnosis ───────────────────────────────────────
    record_job(
        token,
        ws,
        "eicu_patient_to_diagnosis",
        "SPARK",
        [
            edge(
                node(e_pat, "patientunitstayid"),
                node(e_dx, "patientunitstayid"),
                "COPY",
            ),
            edge(
                node(e_pat, "apacheadmissiondx"),
                node(e_dx, "diagnosisstring"),
                "TRANSFORM",
                "COALESCE(diagnosisstring, apacheadmissiondx)",
            ),
        ],
    )

    # ── Synthea CHANCE：cohort → diagnosis → tace → followup ────────────
    record_job(
        token,
        ws,
        "synthea_hcc_care_pathway",
        "BATCH",
        [
            edge(node(s_pat, "patient_id"), node(s_dx, "patient_id"), "COPY"),
            edge(node(s_pat, "patient_id"), node(s_tace, "patient_id"), "COPY"),
            edge(node(s_dx, "diagnosis_id"), node(s_tace, "diagnosis_id"), "COPY"),
            edge(node(s_tace, "session_id"), node(s_fup, "session_id"), "COPY"),
            edge(node(s_tace, "patient_id"), node(s_fup, "patient_id"), "COPY"),
            edge(
                node(s_tace, "actual_exec_end_t0"),
                node(s_fup, "days_from_t0"),
                "TRANSFORM",
                "visit_date - actual_exec_end_t0",
            ),
            edge(
                node(s_dx, "hcc_cnlc_stage"),
                node(s_fup, "mrecist_response"),
                "TRANSFORM",
                "outcome stratified by CNLC (demo INDIRECT)",
                "INDIRECT",
                0.55,
            ),
        ],
    )

    # ── 跨仓示意：MIMIC subject ↔ Synthea patient（联邦对齐预估，PLANNED 用 ACTIVE 演示）──
    record_job(
        token,
        ws,
        "demo_cross_cohort_patient_bridge",
        "MANUAL",
        [
            edge(
                node(m_pat, "subject_id"),
                node(s_pat, "patient_id"),
                "DERIVE",
                "namespace bridge mimic: ↔ synthea: (demo)",
                "INDIRECT",
                0.4,
            ),
            edge(
                node(e_pat, "patienthealthsystemstayid"),
                node(s_pat, "patient_id"),
                "DERIVE",
                "namespace bridge eicu: ↔ synthea: (demo)",
                "INDIRECT",
                0.4,
            ),
        ],
    )

    ov = call("GET", "/api/v1/metadata/lineage/overview", token=token, ws=ws)
    print(
        "[OVERVIEW]",
        f"datasets={ov['datasetsWithLineage']}",
        f"edges={ov['activeColumnEdges']}",
        f"jobs={ov['activeJobs']}",
    )
    if ov["recentDatasets"]:
        sample = ov["recentDatasets"][0]
        g = call(
            "GET",
            f"/api/v1/metadata/lineage/datasets/{sample['datasetId']}/graph?direction=both&depth=3",
            token=token,
            ws=ws,
        )
        print(
            f"[TABLE-GRAPH] {sample['qualifiedName']}: "
            f"nodes={len(g.get('nodes') or [])} edges={len(g.get('edges') or [])}"
        )
    print("DONE — refresh http://127.0.0.1:5173/metadata/lineage")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
