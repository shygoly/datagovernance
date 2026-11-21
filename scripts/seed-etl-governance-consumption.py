#!/usr/bin/env python3
"""ETL 产出 → 治理消费面落地(lightweight-etl-pipeline · governance consumption landing)。

前提:ETL 目标表已在 knowledge/research/2026-11-20-clinical-multisource-etl-lineage-enrichment.md
(及 ...-etl-governance-consumption-landing.md)里文档化,且已跑过 scripts/seed-clinical-multisource.py
(它创建仓库 / 逻辑表 / GLOBAL 临床语义对象)。本脚本只做「消费」这一层,全部幂等:

  1. 解析每个 ETL 目标数据集。**优先物理目标表**(如 lab_std、dws_hcc_orr_by_session);
     目录里还没有物理表时,**回落到已存在的 v_*_std 视图数据集**(它们本来就是 STD 层的目录形态),
     并明确打印 [FALLBACK],而不是静默当作物理表已就绪。
  2. 语义映射:STD/DWD 列 → 已发布的 ENTITY / ATTRIBUTE / FACT / METRIC(mimic / eicu / synthea 哪个有就做哪个)。
     与 seed-clinical-multisource.py 的去重口径一致,重复执行不产生重复映射。
  3. 质检规则桩:在检验值 / 随访应答列上建 NOT_NULL 规则(不运行,除非 --run-rules)。
  4. 分析报告:以 DATASET 为 subject 发布一份「落地登记」报告(correlationId 幂等)。
  5. 码表:确认 ETL 码表映射节点要引用的语义对象(attr.patient.gender 等)已发布且值字典非空,
     并打印可直接粘进 etl.ir/v1 的 TRANSFORM_CODESET_LOOKUP 配置片段。

用法:
  # 若要让血缘「字段数/深度」变厚,先跑 enrichment(写源→v_*_std 列级边),再跑本脚本:
  python3 scripts/seed-etl-lineage-enrichment.py
  python3 scripts/seed-etl-governance-consumption.py [--dry-run] [--skip-quality] [--skip-report] [--run-rules]
  # 环境变量: DG_BASE_URL / DG_USERNAME / DG_PASSWORD

注意:本脚本只挂映射/规则/报告,不会增加 activeColumnEdges;映射在 v_*_std 上回落时大多已存在。

退出码:0 = 完成(含部分跳过,见汇总);1 = 有步骤失败;2 = 无法登录 / 服务不可达。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any

BASE_URL = os.environ.get("DG_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
USERNAME = os.environ.get("DG_USERNAME", "admin")
PASSWORD = os.environ.get("DG_PASSWORD", "admin123")

# 本机直连:不走代理
for _k in ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY"):
    os.environ.pop(_k, None)
os.environ["NO_PROXY"] = "*"
os.environ["no_proxy"] = "*"


# ── HTTP ────────────────────────────────────────────────────────────────

class Client:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url
        self.token = ""
        self.workspace_id = ""

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
        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"{method} {path} HTTP {e.code}: {err_body}") from e
        if not payload.get("success"):
            raise RuntimeError(f"{method} {path} failed: {payload}")
        return payload.get("data")


# ── 声明:ETL 目标 → 治理消费 ───────────────────────────────────────────

@dataclass(frozen=True)
class Target:
    """一个 ETL 目标数据集。preferred 是物理目标表(按序尝试),fallback 是已存在的 v_*_std 视图。"""
    key: str
    etl_job: str
    catalog: str                 # 目录源名(seed-clinical-multisource.py 创建)
    warehouse: str               # 语义仓库 code
    preferred: tuple[str, ...]
    fallback: str
    logical: str                 # 逻辑表名;与 seed-clinical 对齐,回落时复用其已有逻辑表
    table_role: str = "FACT"
    logical_size: int = 80


TARGETS: tuple[Target, ...] = (
    Target("mimic_icu_stay", "etl_mimic_icu_stay_std", "MIMIC-IV 数据目录", "mimic_iv",
           ("mimiciv_icu.icu_stay_std",), "mimiciv_icu.v_icu_stay_std", "lt_mimic_icu_stay_std", logical_size=50),
    Target("mimic_lab", "etl_mimic_lab_std", "MIMIC-IV 数据目录", "mimic_iv",
           ("mimiciv_hosp.lab_std",), "mimiciv_hosp.v_lab_std", "lt_mimic_lab_std"),
    Target("mimic_dx", "etl_mimic_diagnosis_std", "MIMIC-IV 数据目录", "mimic_iv",
           ("mimiciv_hosp.diagnosis_std",), "mimiciv_hosp.v_diagnosis_std", "lt_mimic_diagnosis_std"),
    Target("eicu_icu_stay", "etl_eicu_icu_stay_std", "eICU-CRD 数据目录", "eicu_crd",
           ("eicu_crd.icu_stay_std",), "eicu_crd.v_icu_stay_std", "lt_eicu_icu_stay_std", logical_size=50),
    Target("eicu_lab", "etl_eicu_lab_std", "eICU-CRD 数据目录", "eicu_crd",
           ("eicu_crd.lab_std",), "eicu_crd.v_lab_std", "lt_eicu_lab_std"),
    Target("eicu_dx", "etl_eicu_diagnosis_std", "eICU-CRD 数据目录", "eicu_crd",
           ("eicu_crd.diagnosis_std",), "eicu_crd.v_diagnosis_std", "lt_eicu_diagnosis_std"),
    # Synthea 的 ORR 物化表是指标物化的目标;还没建时回落到随访表(metric.hcc.orr_rate 本来就映射在它上面)
    Target("synthea_orr", "etl_synthea_followup_outcome", "Synthea CHANCE-HCC 数据目录", "synthea_hcc",
           ("synthea_hcc.dws_hcc_orr_by_session",), "synthea_hcc.followup_mrecist",
           "lt_synthea_followup_mrecist", logical_size=450),
)

ORR_EXPR = "CASE WHEN mrecist_response IN ('CR','PR') THEN 100.0 ELSE 0.0 END"


@dataclass(frozen=True)
class MappingSpec:
    """目标列 → 语义对象。columns 是候选列名(按序取第一个存在的),便于物理表与视图列名略有差异。"""
    target: str
    object_code: str
    role: str
    columns: tuple[str, ...]
    extra: dict[str, Any] = field(default_factory=dict)


HOURS = {"sourceUnit": "小时", "targetUnit": "小时"}
MAPPINGS: tuple[MappingSpec, ...] = (
    # ── ICU 停留(性别列正是 ETL 码表映射节点的产出)──
    *(MappingSpec(t, "attr.patient.gender", "CODE", ("gender_std",)) for t in ("mimic_icu_stay", "eicu_icu_stay")),
    *(MappingSpec(t, "fact.icu.los_hours", "MEASURE", ("icu_los_hours", "los_hours"), dict(HOURS))
      for t in ("mimic_icu_stay", "eicu_icu_stay")),
    *(MappingSpec(t, "metric.icu.avg_los_hours", "MEASURE", ("icu_los_hours", "los_hours"), dict(HOURS))
      for t in ("mimic_icu_stay", "eicu_icu_stay")),
    # ── 检验 ──
    *(MappingSpec(t, "entity.lab_event", "ID", ("lab_event_id",)) for t in ("mimic_lab", "eicu_lab")),
    *(MappingSpec(t, "attr.lab.item_name", "DESC", ("lab_name",)) for t in ("mimic_lab", "eicu_lab")),
    *(MappingSpec(t, "fact.lab.result_value", "MEASURE", ("lab_value_num",)) for t in ("mimic_lab", "eicu_lab")),
    # ── 诊断 ──
    MappingSpec("mimic_dx", "attr.diagnosis.icd_code", "CODE", ("icd_code",)),
    MappingSpec("eicu_dx", "attr.diagnosis.icd_code", "CODE", ("icd_code",)),
    # ── 专病随访 / ORR ──
    MappingSpec("synthea_orr", "attr.followup.mrecist_response", "CODE", ("mrecist_response",)),
    MappingSpec("synthea_orr", "metric.hcc.orr_rate", "MEASURE", ("mrecist_response",),
                {"expressionSql": ORR_EXPR, "sourceUnit": "%", "targetUnit": "%"}),
)


@dataclass(frozen=True)
class QualitySpec:
    target: str
    column: str
    rule_type: str
    config: dict[str, Any]
    severity: str


QUALITY_STUBS: tuple[QualitySpec, ...] = (
    QualitySpec("mimic_lab", "lab_value_num", "NOT_NULL", {"maxNullRatio": 0.4}, "MEDIUM"),
    QualitySpec("eicu_lab", "lab_value_num", "NOT_NULL", {"maxNullRatio": 0.4}, "MEDIUM"),
    QualitySpec("synthea_orr", "mrecist_response", "NOT_NULL", {"maxNullRatio": 0.05}, "HIGH"),
)

# ETL 码表映射节点会引用的权威码集(语义对象 code → 对应的 etl.ir/v1 配置示例列)
CODESETS: tuple[tuple[str, str], ...] = (
    ("attr.patient.gender", "gender"),
)


# ── 汇总 ────────────────────────────────────────────────────────────────

class Summary:
    def __init__(self) -> None:
        self.lines: list[tuple[str, str]] = []

    def add(self, tag: str, text: str) -> None:
        self.lines.append((tag, text))
        print(f"[{tag}] {text}")

    def count(self, tag: str) -> int:
        return sum(1 for t, _ in self.lines if t == tag)


# ── 步骤 ────────────────────────────────────────────────────────────────

def login(cli: Client) -> None:
    res = cli.call("POST", "/api/v1/auth/login", {"username": USERNAME, "password": PASSWORD})
    cli.token = res["token"]
    cli.workspace_id = res["currentWorkspaceId"]
    print(f"[LOGIN OK] workspace={cli.workspace_id}")


def catalog_datasets(cli: Client, source_name: str, cache: dict[str, dict[str, dict[str, Any]]]) -> dict[str, dict[str, Any]]:
    if source_name in cache:
        return cache[source_name]
    by_qname: dict[str, dict[str, Any]] = {}
    sources = cli.call("GET", "/api/v1/metadata/catalog/sources") or []
    src = next((s for s in sources if s["name"] == source_name), None)
    if src:
        page = cli.call("GET", f"/api/v1/metadata/catalog/sources/{src['id']}/datasets?page=1&size=500")
        for ds in page.get("records", []):
            by_qname[(ds.get("qualifiedName") or ds.get("name") or "").lower()] = ds
    cache[source_name] = by_qname
    return by_qname


def lookup(ds_map: dict[str, dict[str, Any]], suffix: str) -> dict[str, Any] | None:
    s = suffix.lower()
    if s in ds_map:
        return ds_map[s]
    for k, v in ds_map.items():
        if k.endswith("." + s) or k.endswith(s):
            return v
    return None


def resolve_targets(cli: Client, summary: Summary) -> dict[str, dict[str, Any]]:
    """target.key → {dataset, physical: bool}。物理表优先,其次视图回落,都没有则跳过。"""
    cache: dict[str, dict[str, dict[str, Any]]] = {}
    resolved: dict[str, dict[str, Any]] = {}
    for t in TARGETS:
        ds_map = catalog_datasets(cli, t.catalog, cache)
        if not ds_map:
            summary.add("SKIP", f"{t.key}: 目录源「{t.catalog}」不存在或为空(先跑 seed-clinical-multisource.py)")
            continue
        physical = next((d for d in (lookup(ds_map, p) for p in t.preferred) if d), None)
        if physical:
            resolved[t.key] = {"dataset": physical, "physical": True}
            summary.add("OK", f"{t.key}: ETL 物理目标表 {physical.get('qualifiedName')} ← {t.etl_job}")
            continue
        view = lookup(ds_map, t.fallback)
        if view:
            resolved[t.key] = {"dataset": view, "physical": False}
            summary.add("FALLBACK", f"{t.key}: 物理表 {', '.join(t.preferred)} 尚未建成,"
                                    f"回落到已有目录数据集 {view.get('qualifiedName')}(映射口径不变,物化后重跑本脚本即切到物理表)")
        else:
            summary.add("SKIP", f"{t.key}: 物理表与回落数据集 {t.fallback} 均不在目录里")
    return resolved


def columns_of(cli: Client, dataset_id: str, cache: dict[str, dict[str, str]]) -> dict[str, str]:
    if dataset_id not in cache:
        cols = cli.call("GET", f"/api/v1/metadata/catalog/datasets/{dataset_id}/columns") or []
        cache[dataset_id] = {c["name"].lower(): c["id"] for c in cols}
    return cache[dataset_id]


def ensure_warehouse_id(cli: Client, code: str, cache: dict[str, str | None]) -> str | None:
    if not cache:
        for w in cli.call("GET", "/api/v1/metadata/semantic/warehouses") or []:
            cache[w["code"].lower()] = w["id"]
    return cache.get(code.lower())


def ensure_logical_table(cli: Client, wh_id: str, dataset_id: str, t: Target, dry: bool) -> str | None:
    lts = cli.call("GET", f"/api/v1/metadata/semantic/logical-tables?warehouseId={wh_id}") or []
    existing = next((x for x in lts if x["logicalName"].lower() == t.logical.lower()), None)
    if existing and existing.get("catalogDatasetId") == dataset_id:
        return existing["id"]
    if dry:
        return existing["id"] if existing else None
    payload: dict[str, Any] = {
        "warehouseId": wh_id, "catalogDatasetId": dataset_id, "logicalName": t.logical,
        "tableRole": t.table_role, "logicalSize": t.logical_size,
    }
    if existing:
        payload["id"] = existing["id"]
    return cli.call("POST", "/api/v1/metadata/semantic/logical-tables", payload)["id"]


def object_by_code(cli: Client, code: str, cache: dict[str, dict[str, Any] | None]) -> dict[str, Any] | None:
    if code not in cache:
        page = cli.call("GET", f"/api/v1/metadata/semantic/objects?page=1&size=50&keyword={code}")
        cache[code] = next((o for o in page.get("records", []) if o["code"].lower() == code.lower()), None)
    return cache[code]


def same_mapping(m: dict[str, Any], p: dict[str, Any]) -> bool:
    return (m.get("warehouseId") == p.get("warehouseId")
            and m.get("catalogDatasetId") == p.get("catalogDatasetId")
            and m.get("mappingRole") == p.get("mappingRole")
            and (m.get("catalogColumnId") or "") == (p.get("catalogColumnId") or "")
            and (m.get("expressionSql") or "") == (p.get("expressionSql") or ""))


def land_mappings(cli: Client, resolved: dict[str, dict[str, Any]], summary: Summary, dry: bool) -> dict[str, int]:
    """返回 target.key → 该目标上已就位(新建 + 已存在)的映射数,供报告引用。"""
    wh_cache: dict[str, str | None] = {}
    col_cache: dict[str, dict[str, str]] = {}
    obj_cache: dict[str, dict[str, Any] | None] = {}
    mapping_cache: dict[str, list[dict[str, Any]]] = {}
    lt_ids: dict[str, str | None] = {}
    targets = {t.key: t for t in TARGETS}
    landed: dict[str, int] = {}

    for spec in MAPPINGS:
        if spec.target not in resolved:
            continue
        t = targets[spec.target]
        dataset = resolved[spec.target]["dataset"]
        wh_id = ensure_warehouse_id(cli, t.warehouse, wh_cache)
        if not wh_id:
            summary.add("SKIP", f"映射 {spec.object_code} ← {spec.target}: 语义仓库 {t.warehouse} 不存在")
            continue
        obj = object_by_code(cli, spec.object_code, obj_cache)
        if not obj:
            summary.add("SKIP", f"映射 {spec.object_code} ← {spec.target}: 语义对象不存在(先跑 seed-clinical-multisource.py)")
            continue
        if obj.get("status") != "PUBLISHED":
            summary.add("SKIP", f"映射 {spec.object_code} ← {spec.target}: 对象状态 {obj.get('status')},未发布")
            continue
        cols = columns_of(cli, dataset["id"], col_cache)
        column = next((c for c in spec.columns if c.lower() in cols), None)
        if not column:
            summary.add("SKIP", f"映射 {spec.object_code} ← {spec.target}: 数据集 {dataset.get('qualifiedName')} "
                                f"没有列 {'/'.join(spec.columns)}(可用: {', '.join(sorted(cols)[:8])}...)")
            continue
        if spec.target not in lt_ids:
            lt_ids[spec.target] = ensure_logical_table(cli, wh_id, dataset["id"], t, dry)
        lt_id = lt_ids[spec.target]
        if not lt_id:
            summary.add("DRY", f"映射 {spec.object_code} ← {dataset.get('qualifiedName')}.{column}: 将先建逻辑表 {t.logical}")
            continue

        payload: dict[str, Any] = {
            "mappingRole": spec.role, "warehouseId": wh_id, "logicalTableId": lt_id,
            "catalogDatasetId": dataset["id"], "catalogColumnId": cols[column.lower()], "priority": 10,
        }
        payload.update(spec.extra)
        if obj["id"] not in mapping_cache:
            mapping_cache[obj["id"]] = cli.call("GET", f"/api/v1/metadata/semantic/objects/{obj['id']}/mappings") or []
        label = f"{spec.object_code} ← {dataset.get('qualifiedName')}.{column}"
        if any(same_mapping(m, payload) for m in mapping_cache[obj["id"]]):
            summary.add("EXISTS", f"映射 {label}")
        elif dry:
            summary.add("DRY", f"映射 {label}: 将新建")
            continue
        else:
            created = cli.call("POST", f"/api/v1/metadata/semantic/objects/{obj['id']}/mappings", payload)
            mapping_cache[obj["id"]].append(created if isinstance(created, dict) else payload)
            summary.add("OK", f"映射 {label}: 已新建")
        landed[spec.target] = landed.get(spec.target, 0) + 1
    return landed


def land_quality_stubs(cli: Client, resolved: dict[str, dict[str, Any]], summary: Summary,
                       dry: bool, run_rules: bool) -> int:
    col_cache: dict[str, dict[str, str]] = {}
    made = 0
    for q in QUALITY_STUBS:
        if q.target not in resolved:
            continue
        dataset = resolved[q.target]["dataset"]
        cols = columns_of(cli, dataset["id"], col_cache)
        column_id = cols.get(q.column.lower())
        label = f"{dataset.get('qualifiedName')}.{q.column} {q.rule_type}"
        if not column_id:
            summary.add("SKIP", f"质检规则 {label}: 没有该列")
            continue
        page = cli.call("GET", f"/api/v1/metadata/quality/rules?page=1&size=100&datasetId={dataset['id']}")
        existing = next((r for r in page.get("records", [])
                         if r.get("columnId") == column_id and r.get("ruleType") == q.rule_type), None)
        if existing:
            summary.add("EXISTS", f"质检规则桩 {label}")
            rule_id = existing["id"]
        elif dry:
            summary.add("DRY", f"质检规则桩 {label}: 将新建({q.config}, {q.severity})")
            continue
        else:
            created = cli.call("POST", "/api/v1/metadata/quality/rules", {
                "datasetId": dataset["id"], "columnId": column_id, "ruleType": q.rule_type,
                "config": q.config, "severity": q.severity, "enabled": True,
            })
            rule_id = created["id"]
            made += 1
            summary.add("OK", f"质检规则桩 {label}: 已新建 {rule_id}")
        if run_rules and not dry:
            run = cli.call("POST", f"/api/v1/metadata/quality/rules/{rule_id}/run")
            summary.add("OK", f"质检规则 {label} 运行: status={run.get('status')} passed={run.get('passed')}")
    return made


def land_report(cli: Client, resolved: dict[str, dict[str, Any]], landed: dict[str, int],
                summary: Summary, dry: bool) -> None:
    """以 ORR 目标(没有则取第一个已解析的目标)为 subject 发布「落地登记」报告。"""
    key = "synthea_orr" if "synthea_orr" in resolved else next(iter(resolved), None)
    if key is None:
        summary.add("SKIP", "分析报告: 没有任何已解析的 ETL 目标数据集")
        return
    target = next(t for t in TARGETS if t.key == key)
    dataset = resolved[key]["dataset"]
    physical = resolved[key]["physical"]
    correlation = f"etl-gov-landing:{dataset['id']}"[:80]
    existing = cli.call("GET", f"/api/v1/governance/analysis-reports?page=1&size=5&subjectType=DATASET&subjectId={dataset['id']}")
    if any(r.get("correlationId") == correlation for r in existing.get("records", [])):
        summary.add("EXISTS", f"分析报告 subject=DATASET:{dataset['id']}")
        return
    if dry:
        summary.add("DRY", f"分析报告: 将以 DATASET:{dataset['id']} 为 subject 发布落地登记")
        return
    body = "\n".join([
        f"# ETL 治理消费落地登记 · {target.etl_job}",
        "",
        f"- 目标数据集: `{dataset.get('qualifiedName')}`({'物理 ETL 目标表' if physical else '回落:目录里的 v_*_std / 随访视图,物理表尚未建成'})",
        f"- 本次运行确认就位的语义映射: {landed.get(key, 0)} 条(新建 + 已存在)",
        "- 质检规则桩: 见数据质量页的 NOT_NULL 规则(创建时不运行)",
        "",
        "本报告只登记「哪些治理对象已挂到该数据集上」,不含任何指标取值或患者明细。",
    ])
    created = cli.call("POST", "/api/v1/governance/analysis-reports", {
        "subject": {"type": "DATASET", "id": dataset["id"]},
        "title": f"ETL 治理消费落地:{target.etl_job}",
        "summary": f"{dataset.get('qualifiedName')} 已纳入语义映射与质检桩;{'物理表' if physical else '回落视图'}。",
        "bodyMd": body,
        "conclusion": "CONFIRMED",
        "evidence": [{"kind": "CATALOG", "ref": dataset["id"]}],
        "correlationId": correlation,
        "maxSensitivity": "L1",
        # 报告必须声明产出者。这里是确定性脚本而不是 LLM,如实写成脚本自身,不冒充某个模型
        "model": {"provider": "datagov", "name": "seed-etl-governance-consumption.py",
                  "version": "1", "endpointKind": "SELF_HOSTED"},
    })
    summary.add("OK", f"分析报告已发布 id={created.get('id')} subject=DATASET:{dataset['id']}")


def check_codesets(cli: Client, summary: Summary) -> None:
    cache: dict[str, dict[str, Any] | None] = {}
    for code, column in CODESETS:
        obj = object_by_code(cli, code, cache)
        if not obj:
            summary.add("SKIP", f"码集 {code}: 语义对象不存在")
            continue
        entries = cli.call("GET", f"/api/v1/metadata/semantic/objects/{obj['id']}/value-dict") or []
        status = obj.get("status")
        if status != "PUBLISHED" or not entries:
            summary.add("WARN", f"码集 {code}: 状态={status} 值字典条目={len(entries)} —— ETL 码表映射节点编译会失败/告警")
            continue
        summary.add("OK", f"码集 {code}(id={obj['id']}, {len(entries)} 条值字典)可被 TRANSFORM_CODESET_LOOKUP 引用")
        print("      etl.ir/v1 节点示例:")
        print("      " + json.dumps({
            "id": f"lookup_{column}", "kind": "TRANSFORM_CODESET_LOOKUP",
            "config": {"codesetRef": code, "column": column, "mapTo": "CANONICAL_VALUE",
                       "onMiss": "BAD_ROW", "ignoreCase": True},
        }, ensure_ascii=False))


# ── 入口 ────────────────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dry-run", action="store_true", help="只解析与报告将做什么,不写任何数据")
    ap.add_argument("--skip-quality", action="store_true", help="不创建质检规则桩")
    ap.add_argument("--skip-report", action="store_true", help="不发布分析报告")
    ap.add_argument("--run-rules", action="store_true", help="创建后立即运行质检规则(会查询目标数据集)")
    args = ap.parse_args()

    cli = Client(BASE_URL)
    try:
        login(cli)
    except (urllib.error.URLError, RuntimeError, KeyError) as e:
        print(f"[FATAL] 无法登录 {BASE_URL}: {e}", file=sys.stderr)
        return 2

    summary = Summary()

    def step(name: str, fn: Any) -> Any:
        """单步失败不拖垮其它步骤:记 FAIL、继续,最后以非零退出码提示。"""
        try:
            return fn()
        except (urllib.error.URLError, RuntimeError, KeyError) as e:
            summary.add("FAIL", f"{name}: {e}")
            return None

    resolved = step("解析 ETL 目标", lambda: resolve_targets(cli, summary)) or {}
    landed = step("语义映射", lambda: land_mappings(cli, resolved, summary, args.dry_run)) or {}
    if not args.skip_quality:
        step("质检规则桩", lambda: land_quality_stubs(cli, resolved, summary, args.dry_run, args.run_rules))
    if not args.skip_report:
        step("分析报告", lambda: land_report(cli, resolved, landed, summary, args.dry_run))
    step("码集检查", lambda: check_codesets(cli, summary))

    print("\n── 汇总 ──")
    for tag in ("OK", "EXISTS", "FALLBACK", "DRY", "SKIP", "WARN", "FAIL"):
        print(f"  {tag:<8} {summary.count(tag)}")
    if not resolved:
        print("[WARN] 没有解析到任何 ETL 目标数据集:请先运行 scripts/seed-clinical-multisource.py")
    return 1 if summary.count("FAIL") else 0


if __name__ == "__main__":
    raise SystemExit(main())
