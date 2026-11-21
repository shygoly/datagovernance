#!/usr/bin/env python3
"""在 mac@localhost:5432 准备 synthea 库，并默认生成 100 人 CHANCE-HCC 专病队列。

实际造数委托 gen_synthea_hcc_cohort.py（不镜像 MIMIC 已有表）。

用法:
  python3 scripts/seed/provision_synthea_hcc_stubs.py
  SYNTH_N=50 python3 scripts/seed/provision_synthea_hcc_stubs.py
  SYNTH_LLM=1 python3 scripts/seed/provision_synthea_hcc_stubs.py   # 可选 LLM 报告
"""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
GEN = ROOT / "gen_synthea_hcc_cohort.py"


def main() -> int:
    if not GEN.is_file():
        print(f"[FAIL] missing generator: {GEN}", file=sys.stderr)
        return 2
    n = os.environ.get("SYNTH_N", "100")
    seed = os.environ.get("SYNTH_SEED", "20261119")
    cmd = [sys.executable, str(GEN), "--n", n, "--seed", seed]
    if os.environ.get("SYNTH_LLM", "").strip() in ("1", "true", "TRUE", "yes"):
        cmd.append("--llm")
        if os.environ.get("SYNTH_LLM_LIMIT"):
            cmd.extend(["--llm-limit", os.environ["SYNTH_LLM_LIMIT"]])
    env = dict(os.environ)
    env.setdefault("SYNTHEA_PG_USER", "mac")
    env.setdefault("SYNTHEA_PG_HOST", "localhost")
    env.setdefault("SYNTHEA_PG_PORT", "5432")
    print(f"[INFO] running: {' '.join(cmd)}")
    proc = subprocess.run(cmd, env=env)
    return proc.returncode


if __name__ == "__main__":
    raise SystemExit(main())
