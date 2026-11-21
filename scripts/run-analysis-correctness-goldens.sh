#!/usr/bin/env bash
# W3/SMQ Golden 门禁：加载 evals/analysis-correctness/golden（≥10）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
COUNT=$(find evals/analysis-correctness/golden -name 'g*.json' | wc -l | tr -d ' ')
if [[ "$COUNT" -lt 10 ]]; then
  echo "ERROR: expected ≥10 golden files, found $COUNT"
  exit 1
fi
export JAVA_HOME="${JAVA_HOME:-$(echo "$HOME"/.local/jdk/jdk-21*/Contents/Home)}"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -pl backend/dg-metadata -am test \
  -Dtest=AnalysisCorrectnessGoldenTest \
  -Dsurefire.failIfNoSpecifiedTests=false
echo "OK: AnalysisCorrectnessGoldenTest passed ($COUNT goldens)"
