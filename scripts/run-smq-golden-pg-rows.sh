#!/usr/bin/env bash
# Golden 行级比对：默认走 H2（AnalysisCorrectnessGoldenTest）；若设置 DG_GOLDEN_JDBC_URL 则对真实 PG 重跑。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"

echo "== AnalysisCorrectnessGoldenTest (incl. sql_row g11/g12) =="
mvn -f "$ROOT/pom.xml" -pl backend/dg-metadata -am test \
  "-Dtest=AnalysisCorrectnessGoldenTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false"

if [[ -n "${DG_GOLDEN_JDBC_URL:-}" ]]; then
  echo "== Re-run with DG_GOLDEN_JDBC_URL=$DG_GOLDEN_JDBC_URL =="
  mvn -f "$ROOT/pom.xml" -pl backend/dg-metadata -am test \
    "-Dtest=AnalysisCorrectnessGoldenTest" \
    "-Dsurefire.failIfNoSpecifiedTests=false"
else
  echo "TIP: export DG_GOLDEN_JDBC_URL=jdbc:postgresql://127.0.0.1:54329/dg_probe to assert against live PG"
fi

echo "OK: golden row assertions passed"
