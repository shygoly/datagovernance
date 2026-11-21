#!/usr/bin/env bash
# metric-6d 常驻门禁：G1–G5 / 反模式 / 探针生成 / 统计证伪 SQL（离线单测，不依赖外部 PG）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"

echo "== metric-6d GraphGate / YamlCodec / AntiPattern / Probe / Falsifier =="
mvn -f "$ROOT/pom.xml" -pl backend/dg-metadata -am test \
  "-Dtest=SemanticGraphGateServiceTest,SemanticYamlCodecTest,SemanticAntiPatternLinterTest,MetricProbeRunnerTest,MetricStatisticalFalsifierTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false"

echo "OK: metric-6d offline gates passed"

# 可选：本地 PG 夹具执行证伪 SQL（不可达则 SKIP）
bash "$ROOT/scripts/run-metric-falsifier-fixture.sh"
