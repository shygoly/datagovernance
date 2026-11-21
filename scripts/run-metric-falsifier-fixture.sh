#!/usr/bin/env bash
# ③b 统计证伪：对本地 PG 夹具执行 Falsifier SQL（无 PG 则跳过，不失败）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PGHOST="${DG_TEST_PG_HOST:-127.0.0.1}"
PGPORT="${DG_TEST_PG_PORT:-54329}"
PGUSER="${DG_TEST_PG_USER:-dg}"
PGPASSWORD="${DG_TEST_PG_PASSWORD:-dg}"
PGDATABASE="${DG_TEST_PG_DB:-dg_probe}"
export PGPASSWORD

psql_cmd() {
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -Atc "$1"
}

if ! psql_cmd "SELECT 1" >/dev/null 2>&1; then
  echo "SKIP: PG 不可达 ($PGHOST:$PGPORT/$PGDATABASE)；离线单测已覆盖 SQL 生成"
  exit 0
fi

SCHEMA="dg_metric6d_fx"
psql_cmd "CREATE SCHEMA IF NOT EXISTS ${SCHEMA};"
psql_cmd "DROP TABLE IF EXISTS ${SCHEMA}.child CASCADE; DROP TABLE IF EXISTS ${SCHEMA}.parent CASCADE;"
psql_cmd "CREATE TABLE ${SCHEMA}.parent (id BIGINT PRIMARY KEY, name TEXT);"
psql_cmd "CREATE TABLE ${SCHEMA}.child (id BIGINT PRIMARY KEY, parent_id BIGINT, amount NUMERIC);"
psql_cmd "INSERT INTO ${SCHEMA}.parent VALUES (1,'a'),(2,'b');"
psql_cmd "INSERT INTO ${SCHEMA}.child VALUES (10,1,1.5),(11,1,2.5),(12,2,3.0),(13,NULL,0.5);"

pk=$(psql_cmd "SELECT COUNT(*) = COUNT(DISTINCT id) FROM ${SCHEMA}.parent;")
ndv=$(psql_cmd "SELECT ROUND(CAST(COUNT(DISTINCT parent_id) AS NUMERIC) / NULLIF(COUNT(*),0), 4) FROM ${SCHEMA}.child;")
fk=$(psql_cmd "SELECT ROUND(AVG(CASE WHEN p.id IS NOT NULL THEN 1.0 ELSE 0.0 END)::numeric, 4)
  FROM ${SCHEMA}.child c LEFT JOIN ${SCHEMA}.parent p ON c.parent_id = p.id;")

echo "PK_UNIQUENESS=$pk NDV_RATIO=$ndv FK_MATCH_RATE=$fk"
test "$pk" = "t"
python3 - <<PY
ndv=float("$ndv")
fk=float("$fk")
assert 0 < ndv < 1.0, ndv
assert fk >= 0.75, fk
print("OK: metric falsifier fixture assertions passed")
PY

psql_cmd "DROP SCHEMA ${SCHEMA} CASCADE;"
