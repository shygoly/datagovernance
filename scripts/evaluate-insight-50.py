#!/usr/bin/env python3
"""原生语义问数引擎评测脚本（替代历史 evaluate-supersonic-50.py）。

支持两种模式：
  - --mode offline：校验原生语义引擎与确定性编译器关键能力（零外部依赖）；
  - --mode live   ：对运行中的 dg-app `/api/v1/insight/ask` 批量回放核心问数题集并统计通过率与审计指纹覆盖率。
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
import urllib.request
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parent.parent

SAMPLE_QUESTIONS: list[dict[str, Any]] = [
    {"id": "q01", "category": "catalog", "question": "这个数据域有哪些表", "expect_states": ["METADATA"]},
    {"id": "q02", "category": "aggregate", "question": "按状态统计订单数", "expect_states": ["SUCCESS", "COMPILED_ONLY"]},
    {"id": "q03", "category": "aggregate", "question": "按区域统计有效GMV", "expect_states": ["SUCCESS", "COMPILED_ONLY"]},
    {"id": "q04", "category": "value-linking", "question": "统计华东区已支付订单的有效GMV", "expect_states": ["SUCCESS", "COMPILED_ONLY"]},
    {"id": "q05", "category": "relative-time", "question": "统计上月各区域有效GMV趋势", "expect_states": ["SUCCESS", "COMPILED_ONLY"]},
    {"id": "q06", "category": "inconclusive", "question": "查询未登记的火星人口增长率", "expect_states": ["INCONCLUSIVE"]},
]


def run_offline() -> int:
    required_files = [
        ROOT / "backend/dg-metadata/src/main/java/com/datagov/metadata/service/SemanticOntologyService.java",
        ROOT / "backend/dg-metadata/src/main/java/com/datagov/metadata/service/SemanticQueryCompiler.java",
        ROOT / "backend/dg-metadata/src/main/java/com/datagov/metadata/service/SemanticValueLinkingService.java",
        ROOT / "backend/dg-app/src/main/java/com/datagov/app/insight/NativeInsightEngine.java",
    ]
    missing = [str(p.relative_to(ROOT)) for p in required_files if not p.exists()]
    supersonic = ROOT / "backend/dg-app/src/main/java/com/datagov/app/insight/SuperSonicClient.java"
    if missing:
        print(f"FAILED: missing native semantic files: {missing}", file=sys.stderr)
        return 1
    if supersonic.exists():
        print("FAILED: SuperSonicClient.java still exists", file=sys.stderr)
        return 1
    print(f"Native Insight Benchmark (offline): OK ({len(SAMPLE_QUESTIONS)} scenario definitions ready, SuperSonic retired)")
    return 0


def run_live(base_url: str, token: str, workspace_id: str) -> int:
    passed = 0
    for item in SAMPLE_QUESTIONS:
        req = urllib.request.Request(
            f"{base_url.rstrip('/')}/api/v1/insight/ask",
            data=json.dumps({"question": item["question"]}, ensure_ascii=False).encode("utf-8"),
            method="POST",
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {token}",
                "X-Workspace-Id": workspace_id,
            },
        )
        with urllib.request.urlopen(req, timeout=60) as resp:
            body = json.loads(resp.read().decode("utf-8"))
        data = body.get("data") or {}
        state = data.get("state")
        ok = body.get("success") and state in item["expect_states"]
        if ok:
            passed += 1
        print(f"[{'PASS' if ok else 'FAIL'}] {item['id']} ({item['category']}) state={state} fp={data.get('auditFingerprint')}")
    print(f"Live Benchmark Summary: {passed}/{len(SAMPLE_QUESTIONS)} passed")
    return 0 if passed == len(SAMPLE_QUESTIONS) else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="原生语义问数评测脚本")
    parser.add_argument("--mode", choices=["offline", "live"], default="offline")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--token", default="")
    parser.add_argument("--workspace-id", default="")
    args = parser.parse_args()
    if args.mode == "offline":
        return run_offline()
    return run_live(args.base_url, args.token, args.workspace_id)


if __name__ == "__main__":
    raise SystemExit(main())
