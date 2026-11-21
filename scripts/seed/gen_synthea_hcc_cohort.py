#!/usr/bin/env python3
"""生成 Synthea CHANCE-HCC 专病队列（默认 100 人）并写入 mac@localhost:5432/synthea。

只生成 MIMIC 没有的专病表：cohort_patient / hcc_diagnosis / tace_session /
followup_mrecist / imaging_report_zh。不镜像 admissions/icustays/lab/rx。

用法:
  python3 scripts/seed/gen_synthea_hcc_cohort.py --n 100 --seed 20261119
  python3 scripts/seed/gen_synthea_hcc_cohort.py --n 100 --llm   # 可选中文报告增强
"""
from __future__ import annotations

import argparse
import json
import math
import os
import random
import subprocess
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent
SCHEMA_SQL = ROOT / "synthea_hcc_schema.sql"

SYNTHEA_PG_HOST = os.environ.get("SYNTHEA_PG_HOST", "localhost")
SYNTHEA_PG_PORT = os.environ.get("SYNTHEA_PG_PORT", "5432")
SYNTHEA_PG_USER = os.environ.get("SYNTHEA_PG_USER", "mac")
TARGET_DB = os.environ.get("SYNTHEA_EFFECTIVE_DB", "synthea")

CENTERS = [f"CHANCE-SYNTH-CENTER-{i:02d}" for i in range(1, 6)]
ETIOLOGIES = [("HBV", 0.80), ("HCV", 0.08), ("MAFLD", 0.08), ("Alcohol", 0.04)]
MODALITIES = [("cTACE", 0.45), ("DEB-TACE", 0.35), ("HAIC", 0.20)]
REGIMENS = ["Atezo+Bev", "Camrelizumab+Apatinib", "Sintilimab+Bev", "Lenvatinib", "None"]
MRECIST = [("CR", 0.18), ("PR", 0.35), ("SD", 0.41), ("PD", 0.06)]
STYLES = ["规范详尽型", "门诊简写紧凑型", "带口语缩写临床型"]


def weighted_choice(rng: random.Random, pairs: list[tuple[Any, float]]) -> Any:
    r = rng.random()
    acc = 0.0
    for val, w in pairs:
        acc += w
        if r <= acc:
            return val
    return pairs[-1][0]


def age_group(age: int) -> str:
    if age < 40:
        return "18-39"
    if age < 65:
        return "40-64"
    if age < 80:
        return "65-79"
    return ">=80"


def albi(tb_umol: float, alb_g_l: float) -> tuple[float, int]:
    score = round(math.log10(max(tb_umol, 1.0)) * 0.66 + alb_g_l * -0.085, 2)
    if score <= -2.60:
        grade = 1
    elif score <= -1.39:
        grade = 2
    else:
        grade = 3
    return score, grade


def assign_stage(rng: random.Random) -> dict[str, Any]:
    """按指南自洽采样 CNLC 与影像/肝功能锚点。"""
    roll = rng.random()
    if roll < 0.10:
        stage, cp, pvtt, ehm, n_tum, dmax = "IV", "C", "None", False, rng.randint(1, 3), round(rng.uniform(2.0, 8.0), 1)
    elif roll < 0.28:
        stage, cp, pvtt, ehm, n_tum, dmax = "IIIb", rng.choice(["A", "B"]), "None", True, rng.randint(1, 5), round(rng.uniform(2.0, 10.0), 1)
    elif roll < 0.48:
        stage, cp, pvtt, ehm, n_tum, dmax = "IIIa", rng.choice(["A", "B"]), rng.choice(["Vp1", "Vp2", "Vp3", "Vp4"]), False, rng.randint(1, 4), round(rng.uniform(3.0, 12.0), 1)
    elif roll < 0.72:
        stage, cp, pvtt, ehm, n_tum, dmax = "IIb", rng.choice(["A", "B"]), "None", False, rng.randint(4, 8), round(rng.uniform(1.5, 6.0), 1)
    elif roll < 0.88:
        stage, cp, pvtt, ehm, n_tum, dmax = "IIa", rng.choice(["A", "B"]), "None", False, rng.randint(2, 3), round(rng.uniform(2.0, 5.0), 1)
    else:
        stage, cp, pvtt, ehm, n_tum, dmax = "Ib", "A", "None", False, 1, round(rng.uniform(2.1, 5.0), 1)
    return {
        "cnlc": stage,
        "child_pugh": cp,
        "pvtt": pvtt,
        "extrahepatic": ehm,
        "tumor_count": n_tum,
        "max_diameter_cm": dmax,
    }


@dataclass
class PatientBundle:
    patient: dict[str, Any]
    diagnosis: dict[str, Any]
    sessions: list[dict[str, Any]]
    followups: list[dict[str, Any]]
    reports: list[dict[str, Any]]


def build_patient(rng: random.Random, idx: int, seed: int) -> PatientBundle:
    pid = f"SYN-P-{idx:03d}"
    age = rng.randint(42, 74)
    gender = rng.choice(["M", "F"])
    center = rng.choice(CENTERS)
    st = assign_stage(rng)
    etiology = weighted_choice(rng, ETIOLOGIES)
    tb = round(rng.uniform(12.0, 65.0) if st["child_pugh"] != "C" else rng.uniform(50.0, 120.0), 1)
    alb = round(rng.uniform(28.0, 45.0) if st["child_pugh"] != "C" else rng.uniform(20.0, 28.0), 1)
    albi_s, albi_g = albi(tb, alb)
    dx_date = date(2022, 1, 1) + timedelta(days=rng.randint(0, 900))
    afp0 = round(rng.uniform(8.0, 2000.0), 1)
    pivka0 = round(rng.uniform(20.0, 800.0), 1)

    diagnosis = {
        "diagnosis_id": f"SYN-D-{idx:03d}",
        "patient_id": pid,
        "icd_code": "C22.0",
        "hcc_etiology": etiology,
        "hcc_cnlc_stage": st["cnlc"],
        "hcc_child_pugh_class": st["child_pugh"],
        "hcc_pvtt_grade": st["pvtt"],
        "hcc_extrahepatic_mets": st["extrahepatic"],
        "tumor_count": st["tumor_count"],
        "max_tumor_diameter_cm": st["max_diameter_cm"],
        "baseline_afp_ng_ml": afp0,
        "baseline_pivka_ii": pivka0,
        "albi_score": albi_s,
        "albi_grade": albi_g,
        "diagnosis_date": dx_date.isoformat(),
    }

    n_sessions = 1 if st["cnlc"] in ("Ib", "IIa") else rng.randint(1, 3)
    if st["cnlc"] in ("IIIa", "IIIb", "IV"):
        n_sessions = rng.randint(2, 4)
    modality = weighted_choice(rng, MODALITIES)
    regimen = "None" if st["cnlc"] == "Ib" and rng.random() < 0.5 else rng.choice(REGIMENS)
    sessions: list[dict[str, Any]] = []
    t0 = datetime(dx_date.year, dx_date.month, dx_date.day, 9, 0, tzinfo=timezone(timedelta(hours=8)))
    for s in range(1, n_sessions + 1):
        start = t0 + timedelta(days=(s - 1) * rng.randint(40, 90), hours=rng.randint(0, 3))
        end = start + timedelta(minutes=rng.randint(45, 120))
        sess_mod = modality if s == 1 else weighted_choice(rng, MODALITIES)
        embolic = {"cTACE": "超液化碘油", "DEB-TACE": "载药微球(100-300um)", "HAIC": "N/A"}[sess_mod]
        sessions.append({
            "session_id": f"SYN-T-{idx:03d}-{s}",
            "patient_id": pid,
            "diagnosis_id": diagnosis["diagnosis_id"],
            "session_seq": s,
            "modality": sess_mod,
            "actual_exec_start": start.isoformat(),
            "actual_exec_end_t0": end.isoformat(),
            "embolic_agent": embolic,
            "lipiodol_volume_ml": None if sess_mod == "HAIC" else round(rng.uniform(3.0, 15.0), 1),
            "systemic_regimen": regimen,
        })

    followups: list[dict[str, Any]] = []
    reports: list[dict[str, Any]] = []
    style = rng.choice(STYLES)
    # baseline report
    reports.append({
        "report_id": f"SYN-R-{idx:03d}-B",
        "patient_id": pid,
        "session_id": sessions[0]["session_id"],
        "followup_id": None,
        "report_kind": "baseline",
        "style_profile": style,
        "report_text": "",  # filled later
        "generated_by": "template",
    })

    survival = "Alive"
    last_known = dx_date
    afp = afp0
    pivka = pivka0
    viable = round(st["max_diameter_cm"] * max(1, st["tumor_count"] * 0.6), 1)

    for v, sess in enumerate(sessions, start=1):
        t0_end = datetime.fromisoformat(sess["actual_exec_end_t0"])
        visit = (t0_end + timedelta(days=rng.randint(28, 56))).date()
        resp = weighted_choice(rng, MRECIST)
        if resp in ("CR", "PR"):
            afp = max(2.0, round(afp * rng.uniform(0.4, 0.75), 1))
            pivka = max(10.0, round(pivka * rng.uniform(0.4, 0.8), 1))
            viable = max(0.0, round(viable * rng.uniform(0.2, 0.7), 1))
        elif resp == "PD":
            afp = round(afp * rng.uniform(1.2, 1.8), 1)
            pivka = round(pivka * rng.uniform(1.2, 1.8), 1)
            viable = round(viable * rng.uniform(1.15, 1.6) + 0.5, 1)

        lost = False
        if rng.random() < 0.08 and resp == "PD":
            lost = True
            survival = "Lost_to_FollowUp"
        fid = f"SYN-F-{idx:03d}-{v}"
        followups.append({
            "followup_id": fid,
            "patient_id": pid,
            "session_id": sess["session_id"],
            "visit_seq": v,
            "visit_date": visit.isoformat(),
            "days_from_t0": (visit - t0_end.date()).days,
            "mrecist_response": resp,
            "viable_target_sum_cm": viable,
            "afp_ng_ml": afp,
            "pivka_ii_mAU_ml": pivka,
            "lost_to_followup": lost,
        })
        last_known = visit
        reports.append({
            "report_id": f"SYN-R-{idx:03d}-P{v}",
            "patient_id": pid,
            "session_id": sess["session_id"],
            "followup_id": None,
            "report_kind": "procedure",
            "style_profile": style,
            "report_text": "",
            "generated_by": "template",
        })
        reports.append({
            "report_id": f"SYN-R-{idx:03d}-F{v}",
            "patient_id": pid,
            "session_id": sess["session_id"],
            "followup_id": fid,
            "report_kind": "followup",
            "style_profile": style,
            "report_text": "",
            "generated_by": "template",
        })
        if lost:
            break

    if survival == "Alive" and st["cnlc"] in ("IIIa", "IIIb", "IV") and rng.random() < 0.25:
        survival = "Dead"
        last_known = last_known + timedelta(days=rng.randint(30, 180))

    patient = {
        "patient_id": pid,
        "gender_std": gender,
        "age_years": age,
        "age_group_std": age_group(age),
        "race_std": "ASIAN",
        "synth_center_code": center,
        "survival_status": survival,
        "last_known_date": last_known.isoformat(),
        "created_seed": seed,
    }

    # template texts (LLM may overwrite)
    for rep in reports:
        rep["report_text"] = render_template_report(rep, patient, diagnosis, sessions, followups)

    return PatientBundle(patient, diagnosis, sessions, followups, reports)


def render_template_report(
    rep: dict[str, Any],
    patient: dict[str, Any],
    dx: dict[str, Any],
    sessions: list[dict[str, Any]],
    followups: list[dict[str, Any]],
) -> str:
    pid = patient["patient_id"]
    stage = dx["hcc_cnlc_stage"]
    pvtt = dx["hcc_pvtt_grade"]
    n = dx["tumor_count"]
    d = dx["max_tumor_diameter_cm"]
    kind = rep["report_kind"]
    if kind == "baseline":
        return (
            f"【检查所见】患者{pid}，肝内见{n}个病灶，最大径约{d}cm，呈快进快出强化；"
            f"门脉癌栓分级{pvtt}；CNLC {stage}期。Child-Pugh {dx['hcc_child_pugh_class']}。"
            f"【影像诊断】符合肝细胞癌影像学表现（合成报告，非真实病例）。"
        )
    sess = next(s for s in sessions if s["session_id"] == rep["session_id"])
    if kind == "procedure":
        return (
            f"【手术记录】第{sess['session_seq']}次{sess['modality']}，"
            f"完成时间T0={sess['actual_exec_end_t0']}，栓塞剂={sess['embolic_agent']}，"
            f"碘油约{sess['lipiodol_volume_ml']}ml，联合方案={sess['systemic_regimen']}。"
            f"患者耐受可（合成记录）。"
        )
    fup = next(f for f in followups if f["followup_id"] == rep["followup_id"])
    return (
        f"【复查所见】介入后第{fup['visit_seq']}次增强复查（D{fup['days_from_t0']}），"
        f"存活靶灶合计约{fup['viable_target_sum_cm']}cm，mRECIST={fup['mrecist_response']}；"
        f"AFP={fup['afp_ng_ml']}，PIVKA-II={fup['pivka_ii_mAU_ml']}（合成报告）。"
    )


def llm_enhance_reports(bundles: list[PatientBundle], limit: int) -> None:
    """可选：调用 OpenAI 兼容接口改写部分中文报告；失败则保留模板。"""
    api_key = os.environ.get("OPENAI_API_KEY") or os.environ.get("DG_LLM_API_KEY") or ""
    base = (os.environ.get("OPENAI_BASE_URL") or os.environ.get("DG_LLM_BASE_URL") or "https://api.openai.com/v1").rstrip("/")
    model = os.environ.get("DG_LLM_MODEL") or os.environ.get("OPENAI_MODEL") or "gpt-4o-mini"
    if not api_key:
        print("[WARN] --llm set but no OPENAI_API_KEY/DG_LLM_API_KEY; keep templates", file=sys.stderr)
        return

    enhanced = 0
    for b in bundles:
        if enhanced >= limit:
            break
        for rep in b.reports:
            if enhanced >= limit:
                break
            if rep["report_kind"] != "baseline":
                continue
            payload = {
                "model": model,
                "temperature": 0.3,
                "messages": [
                    {
                        "role": "system",
                        "content": (
                            "你是介入放射科主治医师。仅根据给定 JSON 事实改写中文影像报告，"
                            "不得改动分期、径线、癌栓与疗效结论；禁止真实姓名/身份证；只输出报告正文。"
                        ),
                    },
                    {
                        "role": "user",
                        "content": json.dumps(
                            {
                                "facts": {
                                    "patient_id": b.patient["patient_id"],
                                    "cnlc": b.diagnosis["hcc_cnlc_stage"],
                                    "pvtt": b.diagnosis["hcc_pvtt_grade"],
                                    "tumor_count": b.diagnosis["tumor_count"],
                                    "max_diameter_cm": b.diagnosis["max_tumor_diameter_cm"],
                                    "child_pugh": b.diagnosis["hcc_child_pugh_class"],
                                },
                                "style": rep["style_profile"],
                                "draft": rep["report_text"],
                            },
                            ensure_ascii=False,
                        ),
                    },
                ],
            }
            req = urllib.request.Request(
                f"{base}/chat/completions",
                data=json.dumps(payload).encode("utf-8"),
                headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
                method="POST",
            )
            try:
                with urllib.request.urlopen(req, timeout=60) as resp:
                    body = json.loads(resp.read().decode("utf-8"))
                text = body["choices"][0]["message"]["content"].strip()
                if text:
                    rep["report_text"] = text
                    rep["generated_by"] = "llm"
                    enhanced += 1
            except (urllib.error.URLError, urllib.error.HTTPError, KeyError, IndexError, TimeoutError) as ex:
                print(f"[WARN] LLM enhance skipped for {rep['report_id']}: {ex}", file=sys.stderr)
                return
    print(f"[OK] LLM enhanced {enhanced} baseline reports")


def psql(*extra: str, database: str = TARGET_DB) -> subprocess.CompletedProcess[str]:
    cmd = [
        "psql", "-h", "localhost" if SYNTHEA_PG_HOST in ("localhost", "127.0.0.1") else SYNTHEA_PG_HOST,
        "-p", str(SYNTHEA_PG_PORT), "-U", SYNTHEA_PG_USER, "-d", database,
        "-v", "ON_ERROR_STOP=1", *extra,
    ]
    return subprocess.run(cmd, capture_output=True, text=True)


def sql_literal(val: Any) -> str:
    if val is None:
        return "NULL"
    if isinstance(val, bool):
        return "TRUE" if val else "FALSE"
    if isinstance(val, (int, float)):
        return str(val)
    s = str(val).replace("'", "''")
    return f"'{s}'"


def upsert_sql(bundles: list[PatientBundle]) -> str:
    lines = ["BEGIN;"]
    # 全量重生成：清空专病表（保留 schema）
    lines.append("TRUNCATE synthea_hcc.imaging_report_zh, synthea_hcc.followup_mrecist, synthea_hcc.tace_session, synthea_hcc.hcc_diagnosis, synthea_hcc.cohort_patient CASCADE;")
    for b in bundles:
        p = b.patient
        lines.append(
            "INSERT INTO synthea_hcc.cohort_patient ("
            "patient_id, gender_std, age_years, age_group_std, race_std, synth_center_code, "
            "survival_status, last_known_date, created_seed) VALUES ("
            f"{sql_literal(p['patient_id'])}, {sql_literal(p['gender_std'])}, {p['age_years']}, "
            f"{sql_literal(p['age_group_std'])}, {sql_literal(p['race_std'])}, {sql_literal(p['synth_center_code'])}, "
            f"{sql_literal(p['survival_status'])}, {sql_literal(p['last_known_date'])}::date, {p['created_seed']});"
        )
        d = b.diagnosis
        lines.append(
            "INSERT INTO synthea_hcc.hcc_diagnosis ("
            "diagnosis_id, patient_id, icd_code, hcc_etiology, hcc_cnlc_stage, hcc_child_pugh_class, "
            "hcc_pvtt_grade, hcc_extrahepatic_mets, tumor_count, max_tumor_diameter_cm, "
            "baseline_afp_ng_ml, baseline_pivka_ii, albi_score, albi_grade, diagnosis_date) VALUES ("
            f"{sql_literal(d['diagnosis_id'])}, {sql_literal(d['patient_id'])}, {sql_literal(d['icd_code'])}, "
            f"{sql_literal(d['hcc_etiology'])}, {sql_literal(d['hcc_cnlc_stage'])}, {sql_literal(d['hcc_child_pugh_class'])}, "
            f"{sql_literal(d['hcc_pvtt_grade'])}, {sql_literal(d['hcc_extrahepatic_mets'])}, {d['tumor_count']}, "
            f"{d['max_tumor_diameter_cm']}, {d['baseline_afp_ng_ml']}, {d['baseline_pivka_ii']}, "
            f"{d['albi_score']}, {d['albi_grade']}, {sql_literal(d['diagnosis_date'])}::date);"
        )
        for s in b.sessions:
            lines.append(
                "INSERT INTO synthea_hcc.tace_session ("
                "session_id, patient_id, diagnosis_id, session_seq, modality, actual_exec_start, "
                "actual_exec_end_t0, embolic_agent, lipiodol_volume_ml, systemic_regimen) VALUES ("
                f"{sql_literal(s['session_id'])}, {sql_literal(s['patient_id'])}, {sql_literal(s['diagnosis_id'])}, "
                f"{s['session_seq']}, {sql_literal(s['modality'])}, {sql_literal(s['actual_exec_start'])}::timestamptz, "
                f"{sql_literal(s['actual_exec_end_t0'])}::timestamptz, {sql_literal(s['embolic_agent'])}, "
                f"{sql_literal(s['lipiodol_volume_ml'])}, {sql_literal(s['systemic_regimen'])});"
            )
        for f in b.followups:
            lines.append(
                "INSERT INTO synthea_hcc.followup_mrecist ("
                "followup_id, patient_id, session_id, visit_seq, visit_date, days_from_t0, "
                "mrecist_response, viable_target_sum_cm, afp_ng_ml, pivka_ii_mAU_ml, lost_to_followup) VALUES ("
                f"{sql_literal(f['followup_id'])}, {sql_literal(f['patient_id'])}, {sql_literal(f['session_id'])}, "
                f"{f['visit_seq']}, {sql_literal(f['visit_date'])}::date, {f['days_from_t0']}, "
                f"{sql_literal(f['mrecist_response'])}, {f['viable_target_sum_cm']}, {f['afp_ng_ml']}, "
                f"{f['pivka_ii_mAU_ml']}, {sql_literal(f['lost_to_followup'])});"
            )
        for r in b.reports:
            lines.append(
                "INSERT INTO synthea_hcc.imaging_report_zh ("
                "report_id, patient_id, session_id, followup_id, report_kind, style_profile, report_text, generated_by) VALUES ("
                f"{sql_literal(r['report_id'])}, {sql_literal(r['patient_id'])}, {sql_literal(r['session_id'])}, "
                f"{sql_literal(r['followup_id'])}, {sql_literal(r['report_kind'])}, {sql_literal(r['style_profile'])}, "
                f"{sql_literal(r['report_text'])}, {sql_literal(r['generated_by'])});"
            )
    lines.append("COMMIT;")
    return "\n".join(lines)


def ensure_schema() -> None:
    # DB must exist (provision script); apply DDL
    r = psql("-f", str(SCHEMA_SQL))
    if r.returncode != 0:
        raise RuntimeError(f"schema apply failed: {r.stderr}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate Synthea CHANCE-HCC cohort (no MIMIC-overlap tables)")
    parser.add_argument("--n", type=int, default=100, help="patient count")
    parser.add_argument("--seed", type=int, default=20261119)
    parser.add_argument("--llm", action="store_true", help="optionally enhance baseline reports via LLM")
    parser.add_argument("--llm-limit", type=int, default=20, help="max baseline reports to LLM-enhance")
    parser.add_argument("--dry-run", action="store_true", help="generate in memory only")
    args = parser.parse_args()

    rng = random.Random(args.seed)
    bundles = [build_patient(rng, i, args.seed) for i in range(1, args.n + 1)]
    if args.llm:
        llm_enhance_reports(bundles, args.llm_limit)

    print(f"[OK] built {len(bundles)} patients (seed={args.seed})")
    print(
        f"     sessions={sum(len(b.sessions) for b in bundles)} "
        f"followups={sum(len(b.followups) for b in bundles)} "
        f"reports={sum(len(b.reports) for b in bundles)}"
    )

    if args.dry_run:
        print(json.dumps(bundles[0].patient, ensure_ascii=False))
        print(json.dumps(bundles[0].diagnosis, ensure_ascii=False))
        return 0

    # ensure DB
    probe = psql("-tAc", "SELECT 1", database="postgres")
    if probe.returncode != 0:
        print(f"[FAIL] cannot connect {SYNTHEA_PG_USER}@{SYNTHEA_PG_HOST}:{SYNTHEA_PG_PORT}: {probe.stderr}", file=sys.stderr)
        return 1
    exists = psql("-tAc", f"SELECT 1 FROM pg_database WHERE datname='{TARGET_DB}'", database="postgres")
    if exists.stdout.strip() != "1":
        created = psql("-c", f"CREATE DATABASE {TARGET_DB} OWNER {SYNTHEA_PG_USER}", database="postgres")
        if created.returncode != 0:
            print(f"[FAIL] CREATE DATABASE: {created.stderr}", file=sys.stderr)
            return 1
        print(f"[OK] created database {TARGET_DB}")

    ensure_schema()
    sql = upsert_sql(bundles)
    tmp = ROOT / f".gen_synthea_hcc_{args.seed}.sql"
    tmp.write_text(sql, encoding="utf-8")
    try:
        applied = psql("-f", str(tmp))
        if applied.returncode != 0:
            print(f"[FAIL] load data: {applied.stderr}", file=sys.stderr)
            return 1
    finally:
        tmp.unlink(missing_ok=True)

    # grant datagov read
    app = os.environ.get("PG_USER", "datagov")
    psql("-c", f"GRANT CONNECT ON DATABASE {TARGET_DB} TO {app}", database="postgres")
    psql(
        "-c",
        f"GRANT USAGE ON SCHEMA synthea_hcc TO {app}; "
        f"GRANT SELECT ON ALL TABLES IN SCHEMA synthea_hcc TO {app};",
    )
    cnt = psql("-tAc", "SELECT count(*) FROM synthea_hcc.cohort_patient")
    print(f"[OK] loaded into {SYNTHEA_PG_USER}@localhost:{SYNTHEA_PG_PORT}/{TARGET_DB} cohort_patient={cnt.stdout.strip()}")
    print(f"SYNTHEA_EFFECTIVE_DB={TARGET_DB}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
