"""Mapping of platform dialect names to SQLGlot dialects."""

from __future__ import annotations

# platform name -> sqlglot dialect name
_DIALECT_MAP: dict[str, str] = {
    "postgres": "postgres",
    "mysql": "mysql",
    "doris": "doris",
    "starrocks": "starrocks",
    "oracle": "oracle",
    "spark": "spark",
    "hive": "hive",
    "tsql": "tsql",
}

_ALIASES: dict[str, str] = {
    "postgresql": "postgres",
    "pg": "postgres",
    "sparksql": "spark",
    "mssql": "tsql",
    "sqlserver": "tsql",
}

SUPPORTED_DIALECTS: tuple[str, ...] = tuple(_DIALECT_MAP)


def normalize_dialect(name: str | None) -> str | None:
    """Return the canonical platform dialect name, or ``None`` when unsupported."""
    if not name:
        return None
    key = name.strip().lower()
    key = _ALIASES.get(key, key)
    return key if key in _DIALECT_MAP else None


def to_sqlglot_dialect(name: str) -> str:
    normalized = normalize_dialect(name)
    if normalized is None:
        raise ValueError(f"unsupported dialect: {name}")
    return _DIALECT_MAP[normalized]
