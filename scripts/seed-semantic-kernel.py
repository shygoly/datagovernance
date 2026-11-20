#!/usr/bin/env python3
"""向平台单一语义本体内核（/api/v1/metadata/semantic/**）批量导入核心业务本体、指标、知识图谱边与 L1/L2 安全值字典。

完全替代历史 scripts/seed-supersonic-semantic.py。

用法:
  DG_BASE_URL=http://127.0.0.1:8080 DG_TOKEN=<jwt> DG_WORKSPACE_ID=<ws> \
    python3 scripts/seed-semantic-kernel.py
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from typing import Any

BASE_URL = os.environ.get("DG_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
TOKEN = os.environ.get("DG_TOKEN", "")
WORKSPACE_ID = os.environ.get("DG_WORKSPACE_ID", "")

SEED_OBJECTS: list[dict[str, Any]] = [
    {
        "code": "entity.order.core",
        "name": "核心订单实体",
        "objectType": "ENTITY",
        "spec": {"primaryKey": "order_id"},
        "aiContext": {"aliases": ["订单", "销售订单"], "grain": "每条记录对应一笔订单"},
    },
    {
        "code": "attr.org.region",
        "name": "销售区域",
        "objectType": "ATTRIBUTE",
        "spec": {"forms": [{"role": "ID", "dataType": "STRING"}, {"role": "DESC", "dataType": "STRING"}]},
        "aiContext": {"aliases": ["区域", "大区"], "safeEnumExamples": ["EAST", "SOUTH", "NORTH", "WEST"]},
    },
    {
        "code": "attr.order.status",
        "name": "订单状态",
        "objectType": "ATTRIBUTE",
        "spec": {"forms": [{"role": "CODE", "dataType": "STRING"}]},
        "aiContext": {"aliases": ["状态"], "safeEnumExamples": ["PAID", "COMPLETED", "CANCELED", "REFUNDED"]},
    },
    {
        "code": "dim.time.biz_date",
        "name": "业务日期",
        "objectType": "DIMENSION",
        "spec": {"isTimeDimension": True, "hierarchy": ["year", "month", "biz_date"]},
        "aiContext": {"aliases": ["日期", "下单日期", "时间"]},
    },
    {
        "code": "metric.order.gmv_valid",
        "name": "有效GMV",
        "objectType": "METRIC",
        "standardRef": "STD-ORDER-GMV-001",
        "spec": {
            "metricKind": "BASE",
            "aggregation": "SUM",
            "unit": "元",
            "defaultFilters": [{"objectCode": "attr.order.status", "op": "NOT_IN", "values": ["CANCELED", "TEST"]}],
            "allowedDimensions": ["attr.org.region", "attr.order.status", "dim.time.biz_date"],
        },
        "aiContext": {
            "aliases": ["净GMV", "有效成交额", "总金额"],
            "disambiguation": "统计实际经营收入时使用本指标；若需包含未支付订单请改用 metric.order.gmv_gross。",
            "grain": "订单粒度求和，禁止与 1:N 物流明细直接关联求和",
            "caveats": ["剔除已取消与内部测试订单"],
        },
    },
    {
        "code": "metric.order.count",
        "name": "订单数",
        "objectType": "METRIC",
        "standardRef": "STD-ORDER-CNT-001",
        "spec": {"metricKind": "BASE", "aggregation": "COUNT", "unit": "笔"},
        "aiContext": {"aliases": ["订单量", "单量"]},
    },
    {
        "code": "concept.order.refund",
        "name": "退单与测试单",
        "objectType": "CONCEPT",
        "spec": {},
        "aiContext": {"aliases": ["退货订单", "测试单"], "disambiguation": "不计入有效GMV的异常与内部订单"},
    },
]

SEED_VALUE_DICT: list[tuple[str, str, str, list[str]]] = [
    ("attr.org.region", "EAST", "华东", ["华东区", "华东大区"]),
    ("attr.org.region", "SOUTH", "华南", ["华南区", "华南大区"]),
    ("attr.org.region", "NORTH", "华北", ["华北区"]),
    ("attr.org.region", "WEST", "西部", ["西北", "西南"]),
    ("attr.order.status", "PAID", "已支付", ["已付款", "支付成功"]),
    ("attr.order.status", "COMPLETED", "已完成", ["已签收"]),
    ("attr.order.status", "CANCELED", "已取消", ["取消订单"]),
]


def api_call(method: str, path: str, body: dict[str, Any] | None = None) -> Any:
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        f"{BASE_URL}{path}",
        data=data,
        method=method,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {TOKEN}",
            "X-Workspace-Id": WORKSPACE_ID,
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
        if not payload.get("success"):
            raise RuntimeError(payload.get("message") or str(payload))
        return payload.get("data")


def main() -> int:
    if not TOKEN or not WORKSPACE_ID:
        print("请设置 DG_TOKEN 与 DG_WORKSPACE_ID 环境变量后再执行导入。", file=sys.stderr)
        return 1

    created_ids: dict[str, str] = {}
    for obj in SEED_OBJECTS:
        try:
            saved = api_call("POST", "/api/v1/metadata/semantic/objects", obj)
            oid = saved["id"]
            api_call("POST", f"/api/v1/metadata/semantic/objects/{oid}/publish")
            created_ids[obj["code"]] = oid
            print(f"[OK] created & published {obj['code']} -> {oid}")
        except urllib.error.HTTPError as exc:
            print(f"[SKIP] {obj['code']}: HTTP {exc.code}")

    if "concept.order.refund" in created_ids and "metric.order.gmv_valid" in created_ids:
        api_call("POST", "/api/v1/metadata/semantic/links", {
            "fromObjectId": created_ids["concept.order.refund"],
            "toObjectId": created_ids["metric.order.gmv_valid"],
            "edgeCategory": "CAUSAL",
            "linkType": "EXCLUDES",
            "rationale": "有效GMV 口径自动剔除退单与测试单",
            "joinSpec": [{"objectCode": "attr.order.status", "op": "NOT_IN", "values": ["REFUNDED", "TEST"]}],
        })
        print("[OK] created KG EXCLUDES link: refund -> gmv_valid")

    for code, raw, label, syns in SEED_VALUE_DICT:
        oid = created_ids.get(code)
        if not oid:
            continue
        api_call("POST", f"/api/v1/metadata/semantic/objects/{oid}/value-dict", {
            "rawValue": raw,
            "displayLabel": label,
            "synonyms": syns,
            "sourceKind": "SEED",
        })
    print("Semantic kernel seed finished.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
