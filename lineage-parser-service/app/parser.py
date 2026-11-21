"""Deterministic column-level lineage extraction on top of SQLGlot.

Design rules (openspec ``column-lineage-e2e`` §3.2):

* Stateless and offline: only the SQL text, the dialect and the caller supplied ``catalogSchema`` are used.
* C8 - never invent columns: a ``SELECT *`` that cannot be expanded from ``catalogSchema``, an unresolved
  column, dynamic placeholders or unsupported statements yield ``INCONCLUSIVE`` and no edges for that statement.
* All identifiers are normalised to lower case in the output (``schema.table`` / column names), and
  ``catalogSchema`` keys are matched case-insensitively.
* ``DIRECT`` edges come from the projection list; ``INDIRECT`` edges (FILTER / JOIN / GROUP_BY) come from
  WHERE / HAVING / QUALIFY, JOIN ... ON and GROUP BY clauses of every query block that feeds the statement.
  Indirect edges are attached to every target column of the statement.
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass

import sqlglot
from sqlglot import exp
from sqlglot.errors import ParseError, SqlglotError, TokenError
from sqlglot.optimizer.qualify import qualify
from sqlglot.optimizer.scope import Scope, build_scope
from sqlglot.schema import MappingSchema

from .dialects import to_sqlglot_dialect
from .masking import column_is_masked
from .models import (
    ColumnEdge,
    Diagnostic,
    ParseColumnRequest,
    ParseColumnResponse,
    StatementLineage,
)

NO_SCHEMA = "__nodb__"  # internal placeholder schema for tables without an explicit / default schema
_WITH_KEY = "with_" if "with_" in exp.Select.arg_types else "with"
_MAX_DEPTH = 40

_DYNAMIC_SQL = re.compile(r"\$\{|\{\{|#\{|<%")
_UNSUPPORTED_DML = (exp.Merge, exp.Update)
_LINEAGE_COMMANDS = {"INSERT", "CREATE", "MERGE", "UPDATE", "REPLACE", "UPSERT"}


def _set_op_children(scope: Scope) -> list[Scope]:
    children = getattr(scope, "set_operation_scopes", None)
    if children is None:  # older SQLGlot releases
        children = getattr(scope, "union_scopes", [])
    return list(children)


def _is_set_op(scope: Scope) -> bool:
    """Scope of a UNION / INTERSECT / EXCEPT (scope attributes differ across SQLGlot releases)."""
    set_op = getattr(exp, "SetOperation", exp.Union)
    return isinstance(scope.expression, set_op) or bool(_set_op_children(scope))


class Inconclusive(Exception):
    """Raised when lineage cannot be determined deterministically (C8)."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


# --------------------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------------------


def sql_hash(sql: str) -> str:
    return hashlib.sha256(sql.encode("utf-8")).hexdigest()


def _lower_identifiers(tree: exp.Expression) -> None:
    """Lower-case every identifier and mark it quoted so SQLGlot's dialect normalisation leaves it alone."""
    for ident in tree.find_all(exp.Identifier):
        ident.set("this", str(ident.this).lower())
        ident.set("quoted", True)


def _display_sql(node: exp.Expression, dialect: str) -> str:
    clone = node.copy()
    for ident in clone.find_all(exp.Identifier):
        ident.set("quoted", False)
    return clone.sql(dialect=dialect)


def _table_key(table: exp.Table, default_db: str) -> tuple[str, str]:
    return (table.db or default_db, table.name)


def _display_name(db: str, name: str) -> str:
    return name if db == NO_SCHEMA or not db else f"{db}.{name}"


@dataclass(frozen=True)
class Leaf:
    """A resolved physical source column feeding some output."""

    table: str
    column: str
    masked: bool = False
    transformed: bool = False
    expr: str | None = None


class Catalog:
    """Lower-cased view of the request ``catalogSchema``."""

    def __init__(self, raw: dict[str, list[str]] | None, default_db: str) -> None:
        self.default_db = default_db
        self.columns: dict[tuple[str, str], list[str]] = {}
        for key, cols in (raw or {}).items():
            parts = [p for p in key.strip().lower().split(".") if p]
            if not parts or not cols:
                continue
            table = parts[-1]
            db = parts[-2] if len(parts) >= 2 else default_db
            ordered = [str(c).lower() for c in cols]
            self.columns[(db, table)] = list(dict.fromkeys(ordered))

    def lookup(self, key: tuple[str, str]) -> list[str] | None:
        return self.columns.get(key)

    def mapping_schema(self, dialect: str) -> MappingSchema:
        nested: dict[str, dict[str, dict[str, str]]] = {}
        for (db, table), cols in self.columns.items():
            nested.setdefault(db, {})[table] = {c: "unknown" for c in cols}
        return MappingSchema(nested, dialect=dialect, normalize=False)


# --------------------------------------------------------------------------------------
# per-statement analyser
# --------------------------------------------------------------------------------------


class StatementAnalyzer:
    def __init__(self, query: exp.Expression, dialect: str, catalog: Catalog, default_db: str) -> None:
        self.dialect = dialect
        self.catalog = catalog
        self.default_db = default_db
        self.query = query
        self.root: Scope | None = None
        self._scope_by_select: dict[int, Scope] = {}
        self._memo: dict[tuple[int, int], list[Leaf]] = {}

    # ---- preparation -------------------------------------------------------------

    def prepare(self) -> None:
        try:
            qualified = qualify(
                self.query,
                dialect=self.dialect,
                db=self.default_db,
                schema=self.catalog.mapping_schema(self.dialect),
                validate_qualify_columns=False,
                quote_identifiers=False,
                identify=False,
            )
        except SqlglotError as err:
            raise Inconclusive("QUALIFY_FAILED", f"column resolution failed: {err}") from err
        root = build_scope(qualified)
        if root is None:
            raise Inconclusive("NO_QUERY_SCOPE", "statement does not contain an analysable query")
        self.query = qualified
        self.root = root
        for scope in root.traverse():
            self._scope_by_select[id(scope.expression)] = scope
        self._check_stars()

    def branches(self, scope: Scope) -> list[Scope]:
        if _is_set_op(scope):
            out: list[Scope] = []
            for child in _set_op_children(scope):
                out.extend(self.branches(child))
            return out
        return [scope]

    def reachable(self) -> list[Scope]:
        assert self.root is not None
        seen: dict[int, Scope] = {}

        def visit(scope: Scope) -> None:
            if id(scope) in seen:
                return
            seen[id(scope)] = scope
            for src in scope.sources.values():
                if isinstance(src, Scope):
                    visit(src)
            for child in scope.subquery_scopes:
                visit(child)
            for child in _set_op_children(scope):
                visit(child)

        visit(self.root)
        return list(seen.values())

    @staticmethod
    def _is_star(proj: exp.Expression) -> bool:
        node = proj.this if isinstance(proj, exp.Alias) else proj
        return isinstance(node, exp.Star) or (isinstance(node, exp.Column) and isinstance(node.this, exp.Star))

    def _check_stars(self) -> None:
        for scope in self.reachable():
            if _is_set_op(scope):
                continue
            for proj in scope.expression.selects:
                if not self._is_star(proj):
                    continue
                qualifier = proj.table if isinstance(proj, exp.Column) else ""
                missing: list[str] = []
                for name, src in scope.sources.items():
                    if qualifier and name != qualifier:
                        continue
                    if isinstance(src, exp.Table) and self.catalog.lookup(_table_key(src, self.default_db)) is None:
                        missing.append(_display_name(*_table_key(src, self.default_db)))
                detail = (
                    f"catalogSchema has no columns for: {', '.join(sorted(set(missing)))}"
                    if missing
                    else "a source of the star could not be expanded"
                )
                raise Inconclusive(
                    "STAR_WITHOUT_SCHEMA",
                    f"SELECT * cannot be expanded ({detail}); refusing to invent columns",
                )

    # ---- column resolution -------------------------------------------------------

    def _owner(self, column: exp.Column, fallback: Scope) -> Scope:
        select = column.find_ancestor(exp.Select)
        return self._scope_by_select.get(id(select), fallback) if select is not None else fallback

    def _source_columns(self, src: Scope | exp.Expression) -> set[str] | None:
        if isinstance(src, Scope):
            names = [n for n in src.expression.named_selects]
            if any(not n for n in names):
                return None
            return set(names)
        if isinstance(src, exp.Table):
            cols = self.catalog.lookup(_table_key(src, self.default_db))
            return set(cols) if cols else None
        return None

    def _infer_table(self, column: exp.Column, scope: Scope) -> str:
        """Deterministic resolution of an unqualified column that SQLGlot could not qualify."""
        name = column.name
        sources = dict(scope.selected_sources) if scope.selected_sources else {}
        candidates = {alias: node_src[1] for alias, node_src in sources.items()}
        if not candidates:
            candidates = dict(scope.sources)
        if len(candidates) == 1:
            return next(iter(candidates))
        known_hits: list[str] = []
        unknown: list[str] = []
        for alias, src in candidates.items():
            cols = self._source_columns(src)
            if cols is None:
                unknown.append(alias)
            elif name in cols:
                known_hits.append(alias)
        if len(known_hits) == 1:
            return known_hits[0]
        if not known_hits and len(unknown) == 1:
            return unknown[0]
        raise Inconclusive(
            "UNRESOLVED_COLUMN",
            f"column '{name}' cannot be attributed to a single source table without schema information",
        )

    def resolve_column(self, column: exp.Column, scope: Scope, depth: int = 0) -> list[Leaf]:
        if depth > _MAX_DEPTH:
            raise Inconclusive("RECURSION_LIMIT", "query nesting too deep or recursive CTE is not supported")
        if isinstance(column.this, exp.Star):
            raise Inconclusive("STAR_WITHOUT_SCHEMA", "SELECT * cannot be expanded; refusing to invent columns")
        alias = column.table or self._infer_table(column, scope)
        cursor: Scope | None = scope
        src: Scope | exp.Expression | None = None
        while cursor is not None:
            if alias in cursor.sources:
                src = cursor.sources[alias]
                break
            cursor = cursor.parent
        if src is None:
            raise Inconclusive("UNRESOLVED_COLUMN", f"column '{column.sql()}' references an unknown source '{alias}'")
        if isinstance(src, exp.Table):
            db, name = _table_key(src, self.default_db)
            return [Leaf(table=_display_name(db, name), column=column.name)]
        if isinstance(src, Scope):
            return self._trace_scope_output(src, column.name, depth + 1)
        raise Inconclusive("UNSUPPORTED_SOURCE", f"unsupported source type for '{alias}'")

    def _trace_scope_output(self, scope: Scope, name: str, depth: int) -> list[Leaf]:
        names = list(scope.expression.named_selects)
        if name not in names:
            raise Inconclusive("UNRESOLVED_COLUMN", f"column '{name}' not found in derived source")
        idx = names.index(name)
        leaves: list[Leaf] = []
        for branch in self.branches(scope):
            projections = branch.expression.selects
            if idx >= len(projections):
                raise Inconclusive("UNION_ARITY_MISMATCH", "UNION branches expose a different number of columns")
            leaves.extend(self.trace_projection(branch, projections[idx], depth))
        return self._merge(leaves)

    @staticmethod
    def _merge(leaves: list[Leaf]) -> list[Leaf]:
        """Merge duplicates; a source is only 'masked' if every path to it is masked."""
        merged: dict[tuple, Leaf] = {}
        for leaf in leaves:
            key = (leaf.table, leaf.column, leaf.transformed, leaf.expr)
            prev = merged.get(key)
            if prev is None:
                merged[key] = leaf
            elif prev.masked and not leaf.masked:
                merged[key] = leaf
        return list(merged.values())

    def trace_projection(self, scope: Scope, projection: exp.Expression, depth: int = 0) -> list[Leaf]:
        memo_key = (id(scope), id(projection))
        if memo_key in self._memo:
            return self._memo[memo_key]
        node = projection.this if isinstance(projection, exp.Alias) else projection
        if self._is_star(projection):
            raise Inconclusive("STAR_WITHOUT_SCHEMA", "SELECT * cannot be expanded; refusing to invent columns")
        leaves: list[Leaf] = []
        if isinstance(node, exp.Column):
            leaves = self.resolve_column(node, self._owner(node, scope), depth)
        else:
            text = _display_sql(node, self.dialect)
            for column in node.find_all(exp.Column):
                masked_here = column_is_masked(column, node)
                for leaf in self.resolve_column(column, self._owner(column, scope), depth):
                    leaves.append(
                        Leaf(
                            table=leaf.table,
                            column=leaf.column,
                            masked=leaf.masked or masked_here,
                            transformed=True,
                            expr=text,
                        )
                    )
        result = self._merge(leaves)
        self._memo[memo_key] = result
        return result

    # ---- indirect dependencies ---------------------------------------------------

    def indirect_refs(self) -> list[tuple[Leaf, str, str]]:
        """(leaf, relation, clause text) for filter / join / group-by columns of all contributing blocks."""
        refs: list[tuple[Leaf, str, str]] = []
        for scope in self.reachable():
            if _is_set_op(scope):
                continue
            select = scope.expression
            clauses: list[tuple[str, str, exp.Expression]] = []
            where = select.args.get("where")
            if where is not None:
                clauses.append(("FILTER", _display_sql(where, self.dialect), where))
            for join in select.args.get("joins") or []:
                on = join.args.get("on")
                if on is not None:
                    clauses.append(("JOIN", "ON " + _display_sql(on, self.dialect), on))
            group = select.args.get("group")
            if group is not None:
                clauses.append(("GROUP_BY", _display_sql(group, self.dialect), group))
            having = select.args.get("having")
            if having is not None:
                clauses.append(("FILTER", _display_sql(having, self.dialect), having))
            qual = select.args.get("qualify")
            if qual is not None:
                clauses.append(("FILTER", _display_sql(qual, self.dialect), qual))
            for relation, text, clause in clauses:
                for column in clause.find_all(exp.Column):
                    for leaf in self.resolve_column(column, self._owner(column, scope)):
                        refs.append((leaf, relation, text))
        return refs

    # ---- outputs -----------------------------------------------------------------

    def output_names(self) -> list[str]:
        assert self.root is not None
        return [n for n in self.root.expression.named_selects]

    def projection_count(self) -> int:
        assert self.root is not None
        return len(self.root.expression.selects)

    def build_edges(self, target_table: str, target_columns: list[str]) -> tuple[list[ColumnEdge], list[str]]:
        assert self.root is not None
        branches = self.branches(self.root)
        if self.projection_count() != len(target_columns):
            raise Inconclusive(
                "COLUMN_COUNT_MISMATCH",
                f"target lists {len(target_columns)} column(s) but the query produces {self.projection_count()}",
            )
        direct: list[ColumnEdge] = []
        warnings: list[str] = []
        seen: set[tuple] = set()
        for idx, target_col in enumerate(target_columns):
            produced = False
            for branch in branches:
                projections = branch.expression.selects
                if idx >= len(projections):
                    raise Inconclusive("UNION_ARITY_MISMATCH", "UNION branches expose a different number of columns")
                for leaf in self.trace_projection(branch, projections[idx]):
                    produced = True
                    key = ("DIRECT", leaf.table, leaf.column, target_col, leaf.transformed, leaf.expr)
                    if key in seen:
                        continue
                    seen.add(key)
                    direct.append(
                        ColumnEdge(
                            sourceTable=leaf.table,
                            sourceColumn=leaf.column,
                            targetTable=target_table,
                            targetColumn=target_col,
                            dependencyType="DIRECT",
                            relation="TRANSFORM" if leaf.transformed else "COPY",
                            transformExpr=leaf.expr if leaf.transformed else None,
                            maskingApplied=leaf.masked,
                        )
                    )
            if not produced:
                warnings.append(f"target column '{target_col}' has no source column (constant or non-column expression)")

        indirect_unique = sorted(
            {(leaf.table, leaf.column, relation, text) for leaf, relation, text in self.indirect_refs()}
        )
        indirect: list[ColumnEdge] = []
        for target_col in target_columns:
            for table, column, relation, text in indirect_unique:
                indirect.append(
                    ColumnEdge(
                        sourceTable=table,
                        sourceColumn=column,
                        targetTable=target_table,
                        targetColumn=target_col,
                        dependencyType="INDIRECT",
                        relation=relation,
                        transformExpr=text,
                        maskingApplied=False,
                    )
                )
        return direct + indirect, warnings


# --------------------------------------------------------------------------------------
# statement dispatch
# --------------------------------------------------------------------------------------


def _unwrap_query(node: exp.Expression | None) -> exp.Expression | None:
    while isinstance(node, exp.Subquery) and not node.args.get("alias"):
        node = node.this
    if isinstance(node, (exp.Select, exp.SetOperation if hasattr(exp, "SetOperation") else exp.Union)):
        return node
    if isinstance(node, exp.Subquery):
        return node.this
    return None


def _attach_with(statement: exp.Expression, query: exp.Expression) -> exp.Expression:
    with_clause = statement.args.get(_WITH_KEY)
    if with_clause is not None and query.args.get(_WITH_KEY) is None:
        query.set(_WITH_KEY, with_clause.copy())
    return query


def _target_from(node: exp.Expression | None) -> tuple[exp.Table | None, list[str]]:
    if isinstance(node, exp.Schema):
        table = node.this if isinstance(node.this, exp.Table) else None
        cols = [str(e.name).lower() for e in node.expressions]
        return table, cols
    if isinstance(node, exp.Table):
        return node, []
    return None, []


def analyze_statement(
    statement: exp.Expression, dialect: str, catalog: Catalog, default_db: str
) -> tuple[StatementLineage | None, str | None]:
    """Return ``(lineage, info)``; ``lineage`` is None for statements without lineage."""
    if isinstance(statement, _UNSUPPORTED_DML):
        raise Inconclusive("UNSUPPORTED_STATEMENT", f"{statement.key.upper()} statements are not supported")
    if isinstance(statement, exp.Command):
        keyword = str(statement.this).upper()
        if keyword in _LINEAGE_COMMANDS:
            raise Inconclusive("UNSUPPORTED_SYNTAX", f"statement could not be parsed structurally ({keyword} ...)")
        return None, f"skipped {keyword} command (no column lineage)"

    target_table: exp.Table | None = None
    target_columns: list[str] = []
    query: exp.Expression | None = None
    explicit_columns = False

    if isinstance(statement, exp.Insert):
        query = _unwrap_query(statement.expression)
        target_table, target_columns = _target_from(statement.this)
        explicit_columns = bool(target_columns)
        if query is None:
            return None, "skipped INSERT without SELECT source (no column lineage)"
        query = _attach_with(statement, query)
    elif isinstance(statement, exp.Create) and statement.args.get("expression") is not None:
        query = _unwrap_query(statement.expression)
        kind = str(statement.args.get("kind") or "").upper()
        if query is None or kind not in {"TABLE", "VIEW"}:
            return None, f"skipped CREATE {kind} (no column lineage)"
        target_table, target_columns = _target_from(statement.this)
        explicit_columns = bool(target_columns)
        query = _attach_with(statement, query)
    else:
        kind = statement.key.upper()
        return None, f"skipped {kind} statement (no write target)"

    if target_table is None:
        raise Inconclusive("NO_TARGET", "write target table could not be determined")

    if not target_table.db:
        target_table.set("db", exp.to_identifier(default_db, quoted=True))
    target_key = _table_key(target_table, default_db)
    target_name = _display_name(*target_key)

    analyzer = StatementAnalyzer(query, dialect, catalog, default_db)
    analyzer.prepare()

    if not explicit_columns:
        if isinstance(statement, exp.Insert):
            known = catalog.lookup(target_key)
            if known is None:
                raise Inconclusive(
                    "TARGET_COLUMNS_UNKNOWN",
                    f"INSERT into '{target_name}' has no column list and catalogSchema lacks the target table; "
                    "positional mapping would be a guess",
                )
            target_columns = list(known)
        else:
            names = analyzer.output_names()
            if any((not n) or re.fullmatch(r"_col_\d+", n) for n in names):
                raise Inconclusive(
                    "UNNAMED_OUTPUT_COLUMN",
                    f"query for '{target_name}' has unnamed output expressions; add aliases or an explicit column list",
                )
            target_columns = names

    edges, warnings = analyzer.build_edges(target_name, target_columns)
    return StatementLineage(targetTable=target_name, edges=edges, warnings=warnings), None


# --------------------------------------------------------------------------------------
# public entrypoint
# --------------------------------------------------------------------------------------


def parse_column_lineage(request: ParseColumnRequest) -> ParseColumnResponse:
    digest = sql_hash(request.sql)
    dialect = to_sqlglot_dialect(request.dialect)
    default_db = (request.defaultSchema or "").strip().lower() or NO_SCHEMA
    diagnostics: list[Diagnostic] = []

    if not request.sql.strip():
        return ParseColumnResponse(
            status="ERROR",
            sqlHash=digest,
            diagnostics=[Diagnostic(code="EMPTY_SQL", severity="ERROR", message="sql is empty")],
        )

    if _DYNAMIC_SQL.search(request.sql):
        return ParseColumnResponse(
            status="INCONCLUSIVE",
            sqlHash=digest,
            diagnostics=[
                Diagnostic(
                    code="DYNAMIC_SQL",
                    severity="INCONCLUSIVE",
                    message="SQL contains unresolved template placeholders (${...}, {{...}}, #{...}); "
                    "lineage cannot be determined statically",
                )
            ],
        )

    try:
        statements = [s for s in sqlglot.parse(request.sql, dialect=dialect) if s is not None]
    except (ParseError, TokenError) as err:
        return ParseColumnResponse(
            status="ERROR",
            sqlHash=digest,
            diagnostics=[Diagnostic(code="PARSE_ERROR", severity="ERROR", message=str(err).splitlines()[0][:500])],
        )

    catalog = Catalog(request.catalogSchema, default_db)
    results: list[StatementLineage] = []
    inconclusive = False

    for index, statement in enumerate(statements):
        _lower_identifiers(statement)
        try:
            lineage, info = analyze_statement(statement, dialect, catalog, default_db)
        except Inconclusive as reason:
            inconclusive = True
            diagnostics.append(
                Diagnostic(code=reason.code, severity="INCONCLUSIVE", message=reason.message, statementIndex=index)
            )
            continue
        except SqlglotError as err:
            inconclusive = True
            diagnostics.append(
                Diagnostic(
                    code="ANALYSIS_FAILED",
                    severity="INCONCLUSIVE",
                    message=str(err).splitlines()[0][:500],
                    statementIndex=index,
                )
            )
            continue
        if lineage is None:
            diagnostics.append(Diagnostic(code="NO_LINEAGE", severity="INFO", message=info or "", statementIndex=index))
        else:
            results.append(lineage)

    return ParseColumnResponse(
        status="INCONCLUSIVE" if inconclusive else "OK",
        sqlHash=digest,
        statements=results,
        diagnostics=diagnostics,
    )
