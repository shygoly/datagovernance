# Analysis Correctness Golden（W3）

PG 优先夹具目录。每条用例为 JSON：

| 字段 | 说明 |
|---|---|
| `id` | 用例 ID |
| `kind` | `gate` / `compile_sql` / `compile_reject` |
| `assert` | 断言载荷（见各文件） |

当前由 `AnalysisCorrectnessGoldenTest` 加载并执行：

| kind | 说明 |
|---|---|
| `null_as_zero` / `gate` / `gate_pass` | 门禁与 NULL 填零断言 |
| `sql_row` | **行级比对**：默认 H2（PostgreSQL 兼容模式）；可用 `DG_GOLDEN_JDBC_URL` 指向真实 PG |

`g11`/`g12` 覆盖 ③b 证伪 SQL 的行级结果断言。
