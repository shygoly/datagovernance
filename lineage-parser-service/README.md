# lineage-parser-service

Stateless column-level SQL lineage parser sidecar (FastAPI + SQLGlot) for openspec `column-lineage-e2e`.
It never connects to a database: it only receives SQL text, a dialect and the caller's `catalogSchema`.

## Run

```bash
pip install -e ".[dev]"
uvicorn app.main:app --port 8091
pytest
```

Docker: `docker build -t lineage-parser-service . && docker run -p 8091:8091 lineage-parser-service`

## API

* `GET /health` -> `{"ok": true}`
* `POST /v1/parse/column`

```json
{
  "dialect": "postgres | mysql | doris | starrocks | oracle | spark | hive | tsql",
  "sql": "INSERT INTO dws.t (a, b) SELECT x, SUM(y) FROM ods.s GROUP BY x",
  "defaultSchema": "public",
  "catalogSchema": { "ods.s": ["x", "y"] }
}
```

Response: `{status: OK|INCONCLUSIVE|ERROR, sqlHash, statements: [{targetTable, edges[], warnings[]}], diagnostics[]}`.

Each edge: `sourceTable, sourceColumn, targetTable, targetColumn, dependencyType (DIRECT|INDIRECT),
relation (COPY|TRANSFORM | FILTER|JOIN|GROUP_BY), transformExpr, maskingApplied`.

## Behaviour contract

* **C8 - never invent columns.** `SELECT *` without schema for the source tables, unresolvable columns,
  `${...}`/`{{...}}` placeholders, `MERGE`/`UPDATE`, and `INSERT` without a column list whose target is
  not in `catalogSchema` all return `INCONCLUSIVE` with a diagnostic and no edges for the affected statement.
  Unparseable SQL returns `ERROR`.
* `SELECT *` is expanded from `catalogSchema` (keys are `schema.table`, matched case-insensitively).
* Tables without a schema use `defaultSchema` (if given); output names are lower-cased `schema.table`.
* `DIRECT` edges come from projections (`COPY` for bare columns, `TRANSFORM` otherwise).
  `INDIRECT` edges come from WHERE/HAVING/QUALIFY (`FILTER`), JOIN ON (`JOIN`) and GROUP BY (`GROUP_BY`) and are
  attached to every target column of the statement.
* `maskingApplied=true` when the source column is wrapped by a masking/hash function
  (`MASK*`, `MD5`, `SHA`/`SHA1`/`SHA2`/`SHA256`, `HASH`, `ENCRYPT`, `DESENSITIZE`; matched per `_`-separated token).
* Supported statements: `INSERT ... SELECT`, `CREATE TABLE/VIEW ... AS SELECT` (CTEs, subqueries, UNION supported).
