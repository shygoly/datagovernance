from __future__ import annotations

import hashlib

import pytest
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def parse(dialect: str, sql: str, **extra):
    body = {"dialect": dialect, "sql": sql, **extra}
    resp = client.post("/v1/parse/column", json=body)
    assert resp.status_code == 200, resp.text
    return resp.json()


def edges(data, dep: str | None = None, stmt: int = 0):
    out = data["statements"][stmt]["edges"]
    return [e for e in out if dep is None or e["dependencyType"] == dep]


def pairs(data, dep: str = "DIRECT"):
    return {(e["sourceTable"], e["sourceColumn"], e["targetColumn"]) for e in edges(data, dep)}


def test_health():
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"ok": True}


def test_simple_insert_select_column_map_with_hash():
    sql = "INSERT INTO dws.t (a, b) SELECT x, y FROM ods.s"
    data = parse("postgres", sql)
    assert data["status"] == "OK"
    assert data["diagnostics"] == []
    assert data["sqlHash"] == hashlib.sha256(sql.encode()).hexdigest()
    stmt = data["statements"][0]
    assert stmt["targetTable"] == "dws.t"
    assert pairs(data) == {("ods.s", "x", "a"), ("ods.s", "y", "b")}
    for e in stmt["edges"]:
        assert e["relation"] == "COPY"
        assert e["transformExpr"] is None
        assert e["maskingApplied"] is False
        assert e["targetTable"] == "dws.t"


def test_design_example_direct_and_indirect():
    sql = """
    INSERT INTO dws.order_summary (user_id, total_amt)
    SELECT o.user_id, SUM(COALESCE(o.amount, 0))
    FROM ods.orders o
    WHERE o.status = 'PAID'
    GROUP BY o.user_id
    """
    data = parse("postgres", sql)
    assert data["status"] == "OK"
    direct = {(e["sourceColumn"], e["targetColumn"]): e for e in edges(data, "DIRECT")}
    assert direct[("user_id", "user_id")]["relation"] == "COPY"
    total = direct[("amount", "total_amt")]
    assert total["relation"] == "TRANSFORM"
    assert total["transformExpr"] == "SUM(COALESCE(o.amount, 0))"

    indirect = [e for e in edges(data, "INDIRECT") if e["targetColumn"] == "total_amt"]
    by_rel = {(e["relation"], e["sourceColumn"]): e for e in indirect}
    assert by_rel[("FILTER", "status")]["transformExpr"] == "WHERE o.status = 'PAID'"
    assert ("GROUP_BY", "user_id") in by_rel
    # indirect edges attach to every target column
    assert {e["targetColumn"] for e in edges(data, "INDIRECT")} == {"user_id", "total_amt"}


def test_join_condition_is_indirect_and_projection_is_direct():
    sql = """
    INSERT INTO dws.t (name, amt)
    SELECT u.name, o.amount FROM ods.orders o JOIN ods.users u ON o.user_id = u.id
    """
    data = parse("postgres", sql)
    assert data["status"] == "OK"
    assert pairs(data) == {("ods.users", "name", "name"), ("ods.orders", "amount", "amt")}
    joins = {(e["sourceTable"], e["sourceColumn"]) for e in edges(data, "INDIRECT") if e["relation"] == "JOIN"}
    assert joins == {("ods.orders", "user_id"), ("ods.users", "id")}


def test_select_star_expanded_with_catalog_schema():
    data = parse(
        "postgres",
        "INSERT INTO dws.t (a, b, c) SELECT * FROM ods.orders",
        catalogSchema={"ods.orders": ["id", "user_id", "amount"]},
    )
    assert data["status"] == "OK"
    assert pairs(data) == {
        ("ods.orders", "id", "a"),
        ("ods.orders", "user_id", "b"),
        ("ods.orders", "amount", "c"),
    }


def test_select_star_without_schema_is_inconclusive():
    data = parse("postgres", "INSERT INTO dws.t SELECT * FROM ods.orders")
    assert data["status"] == "INCONCLUSIVE"
    assert data["statements"] == []
    diag = data["diagnostics"][0]
    assert diag["code"] == "STAR_WITHOUT_SCHEMA"
    assert diag["severity"] == "INCONCLUSIVE"
    assert "ods.orders" in diag["message"]


def test_select_star_with_other_table_schema_only_is_inconclusive():
    data = parse(
        "postgres",
        "CREATE TABLE dws.t AS SELECT * FROM ods.orders",
        catalogSchema={"ods.users": ["id"]},
    )
    assert data["status"] == "INCONCLUSIVE"
    assert data["diagnostics"][0]["code"] == "STAR_WITHOUT_SCHEMA"


def test_never_invents_target_columns_for_positional_insert():
    data = parse("postgres", "INSERT INTO dws.t SELECT x FROM ods.s")
    assert data["status"] == "INCONCLUSIVE"
    assert data["diagnostics"][0]["code"] == "TARGET_COLUMNS_UNKNOWN"


def test_positional_insert_uses_catalog_target_columns():
    data = parse(
        "postgres",
        "INSERT INTO dws.t SELECT x, y FROM ods.s",
        catalogSchema={"dws.t": ["a", "b"]},
    )
    assert data["status"] == "OK"
    assert pairs(data) == {("ods.s", "x", "a"), ("ods.s", "y", "b")}


@pytest.mark.parametrize(
    "func,expected",
    [
        ("MD5(u.phone)", True),
        ("SHA256(u.phone)", True),
        ("SHA1(u.phone)", True),
        ("HASH(u.phone)", True),
        ("MASK(u.phone)", True),
        ("MASK_PHONE(u.phone)", True),
        ("ENCRYPT(u.phone, 'k')", True),
        ("DESENSITIZE(u.phone)", True),
        ("UPPER(u.phone)", False),
    ],
)
def test_masking_functions(func, expected):
    data = parse("postgres", f"INSERT INTO dws.t (p) SELECT {func} FROM ods.users u")
    assert data["status"] == "OK"
    (edge,) = edges(data, "DIRECT")
    assert edge["maskingApplied"] is expected
    assert edge["relation"] == "TRANSFORM"
    assert edge["sourceColumn"] == "phone"


def test_masking_applies_only_to_wrapped_column():
    data = parse("postgres", "INSERT INTO dws.t (c) SELECT CONCAT(MD5(u.phone), u.name) FROM ods.users u")
    by_col = {e["sourceColumn"]: e["maskingApplied"] for e in edges(data, "DIRECT")}
    assert by_col == {"phone": True, "name": False}


def test_masking_propagates_through_subquery():
    sql = """
    INSERT INTO dws.t (p)
    SELECT s.hp FROM (SELECT MD5(phone) AS hp FROM ods.users) s
    """
    data = parse("postgres", sql)
    (edge,) = edges(data, "DIRECT")
    assert edge["sourceTable"] == "ods.users"
    assert edge["sourceColumn"] == "phone"
    assert edge["maskingApplied"] is True


def test_cte_lineage_and_default_schema():
    sql = """
    WITH c AS (SELECT id, amount * 2 AS dbl FROM orders)
    INSERT INTO t (a, b) SELECT id, dbl FROM c
    """
    data = parse("postgres", sql, defaultSchema="Public")
    assert data["status"] == "OK"
    assert data["statements"][0]["targetTable"] == "public.t"
    got = {(e["sourceTable"], e["sourceColumn"], e["targetColumn"], e["relation"]) for e in edges(data, "DIRECT")}
    assert got == {("public.orders", "id", "a", "COPY"), ("public.orders", "amount", "b", "TRANSFORM")}


def test_union_all_maps_positionally():
    sql = "INSERT INTO dws.t (k) SELECT a FROM ods.x UNION ALL SELECT b FROM ods.y"
    data = parse("postgres", sql)
    assert data["status"] == "OK"
    assert pairs(data) == {("ods.x", "a", "k"), ("ods.y", "b", "k")}


def test_ctas_uses_output_names():
    data = parse("postgres", "CREATE TABLE dws.t AS SELECT x AS a, y FROM ods.s")
    assert data["status"] == "OK"
    assert pairs(data) == {("ods.s", "x", "a"), ("ods.s", "y", "y")}


def test_create_view():
    data = parse("mysql", "CREATE VIEW v_orders AS SELECT id, amount FROM orders", defaultSchema="shop")
    assert data["status"] == "OK"
    assert data["statements"][0]["targetTable"] == "shop.v_orders"
    assert pairs(data) == {("shop.orders", "id", "id"), ("shop.orders", "amount", "amount")}


# ---- multi-dialect ---------------------------------------------------------------------


def test_mysql_backtick_identifiers():
    data = parse("mysql", "INSERT INTO `dws`.`t` (`a`) SELECT `x` FROM `ods`.`s`")
    assert data["status"] == "OK"
    assert pairs(data) == {("ods.s", "x", "a")}


def test_oracle_normalises_case_and_catalog_lookup():
    data = parse(
        "oracle",
        "INSERT INTO DWS.T (A, B) SELECT * FROM ODS.Orders",
        catalogSchema={"ods.orders": ["ID", "AMT"]},
    )
    assert data["status"] == "OK"
    assert data["statements"][0]["targetTable"] == "dws.t"
    assert pairs(data) == {("ods.orders", "id", "a"), ("ods.orders", "amt", "b")}


def test_hive_insert_overwrite_with_masking():
    data = parse(
        "hive",
        "INSERT OVERWRITE TABLE dws.t SELECT MD5(phone) AS p, city FROM ods.users",
        catalogSchema={"dws.t": ["p", "city"]},
    )
    assert data["status"] == "OK"
    by_col = {e["targetColumn"]: e for e in edges(data, "DIRECT")}
    assert by_col["p"]["maskingApplied"] is True
    assert by_col["city"]["maskingApplied"] is False


@pytest.mark.parametrize("dialect", ["doris", "starrocks", "spark", "hive", "oracle", "postgres", "mysql"])
def test_all_dialects_basic_insert_select(dialect):
    data = parse(dialect, "INSERT INTO dws.t (a, b) SELECT x, y + 1 FROM ods.s WHERE z > 0")
    assert data["status"] == "OK", data
    got = {(e["sourceColumn"], e["targetColumn"], e["relation"]) for e in edges(data, "DIRECT")}
    assert got == {("x", "a", "COPY"), ("y", "b", "TRANSFORM")}
    assert {e["sourceColumn"] for e in edges(data, "INDIRECT")} == {"z"}


# ---- C8 / error handling -----------------------------------------------------------------


def test_unqualified_ambiguous_column_is_inconclusive():
    data = parse("postgres", "INSERT INTO dws.t (a) SELECT x FROM ods.s JOIN ods.u ON s.id = u.id")
    assert data["status"] == "INCONCLUSIVE"
    assert data["diagnostics"][0]["code"] == "UNRESOLVED_COLUMN"


def test_dynamic_placeholders_are_inconclusive():
    data = parse("hive", "INSERT OVERWRITE TABLE dws.t SELECT a FROM ods.s WHERE dt = '${bizdate}'")
    assert data["status"] == "INCONCLUSIVE"
    assert data["diagnostics"][0]["code"] == "DYNAMIC_SQL"


def test_merge_is_unsupported_inconclusive():
    data = parse(
        "postgres",
        "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET a = s.a",
    )
    assert data["status"] == "INCONCLUSIVE"


def test_syntax_error_is_error():
    data = parse("postgres", "INSERT INTO SELECT FROM WHERE (")
    assert data["status"] == "ERROR"
    assert data["diagnostics"][0]["code"] == "PARSE_ERROR"


def test_plain_select_has_no_lineage_but_is_ok():
    data = parse("postgres", "SELECT 1")
    assert data["status"] == "OK"
    assert data["statements"] == []
    assert data["diagnostics"][0]["severity"] == "INFO"


def test_multi_statement_script_partial_inconclusive():
    sql = "INSERT INTO dws.a (x) SELECT p FROM ods.s; INSERT INTO dws.b SELECT * FROM ods.s"
    data = parse("postgres", sql)
    assert data["status"] == "INCONCLUSIVE"
    assert [s["targetTable"] for s in data["statements"]] == ["dws.a"]
    assert data["diagnostics"][0]["statementIndex"] == 1


def test_output_is_deterministic():
    sql = "INSERT INTO dws.t (a, b) SELECT o.x, SUM(o.y) FROM ods.o o JOIN ods.u u ON o.k = u.k WHERE u.z = 1 GROUP BY o.x"
    first = parse("postgres", sql)
    for _ in range(3):
        assert parse("postgres", sql) == first


def test_unsupported_dialect_rejected():
    resp = client.post("/v1/parse/column", json={"dialect": "db2", "sql": "select 1"})
    assert resp.status_code == 422
