"""Request / response contracts for ``POST /v1/parse/column`` (see openspec column-lineage-e2e design §3.2)."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, field_validator

from .dialects import SUPPORTED_DIALECTS, normalize_dialect


class ParseColumnRequest(BaseModel):
    dialect: str = Field(description=f"One of: {', '.join(SUPPORTED_DIALECTS)}")
    sql: str = Field(min_length=1, max_length=2_000_000)
    defaultSchema: str | None = None
    catalogSchema: dict[str, list[str]] | None = Field(
        default=None,
        description='Mapping of "schema.table" -> ordered column names, scoped to the caller Workspace.',
    )

    @field_validator("dialect")
    @classmethod
    def _check_dialect(cls, value: str) -> str:
        normalized = normalize_dialect(value)
        if normalized is None:
            raise ValueError(f"unsupported dialect '{value}', expected one of {list(SUPPORTED_DIALECTS)}")
        return normalized


class ColumnEdge(BaseModel):
    sourceTable: str
    sourceColumn: str
    targetTable: str
    targetColumn: str
    dependencyType: Literal["DIRECT", "INDIRECT"]
    # DIRECT: COPY | TRANSFORM ; INDIRECT: FILTER | JOIN | GROUP_BY
    relation: str
    transformExpr: str | None = None
    maskingApplied: bool = False


class StatementLineage(BaseModel):
    targetTable: str
    edges: list[ColumnEdge] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


class Diagnostic(BaseModel):
    code: str
    # INFO: no effect on status; INCONCLUSIVE: lineage cannot be determined (C8); ERROR: SQL could not be parsed.
    severity: Literal["INFO", "INCONCLUSIVE", "ERROR"]
    message: str
    statementIndex: int | None = None


class ParseColumnResponse(BaseModel):
    status: Literal["OK", "INCONCLUSIVE", "ERROR"]
    sqlHash: str
    statements: list[StatementLineage] = Field(default_factory=list)
    diagnostics: list[Diagnostic] = Field(default_factory=list)
