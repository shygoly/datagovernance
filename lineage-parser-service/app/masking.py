"""Detection of deterministic masking / irreversible hash functions (design §5, D6)."""

from __future__ import annotations

import re

from sqlglot import exp

# Tokens of a function name (split on "_") that mark masking / hashing / encryption.
MASKING_TOKENS: frozenset[str] = frozenset(
    {
        "MASK",
        "MD5",
        "SHA",
        "SHA1",
        "SHA2",
        "SHA256",
        "HASH",
        "ENCRYPT",
        "DESENSITIZE",
    }
)


_CAMEL_TOKENS = re.compile(r"[A-Z]+[0-9]*(?![a-z])|[A-Z]?[a-z]+[0-9]*|[0-9]+")


def function_tokens(node: exp.Expression) -> set[str]:
    """Upper-cased name tokens of a function node (``MASK_PHONE`` -> MASK, PHONE; ``SHA2Digest`` -> SHA2, DIGEST)."""
    if isinstance(node, exp.Anonymous):
        return {t for t in str(node.name).upper().split("_") if t}
    if isinstance(node, exp.Func):
        tokens = {t.upper() for t in _CAMEL_TOKENS.findall(type(node).__name__)}
        tokens.update(t for t in node.sql_name().upper().split("_") if t)
        return tokens
    return set()


def is_masking_function(node: exp.Expression) -> bool:
    return bool(function_tokens(node) & MASKING_TOKENS)


def column_is_masked(column: exp.Column, root: exp.Expression) -> bool:
    """True when ``column`` sits (at any depth) below a masking function inside ``root``."""
    node: exp.Expression | None = column.parent
    while node is not None:
        if is_masking_function(node):
            return True
        if node is root:
            break
        node = node.parent
    return False
