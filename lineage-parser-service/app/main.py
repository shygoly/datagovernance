"""FastAPI entrypoint for the lineage parser sidecar (stateless, no database connections)."""

from __future__ import annotations

from fastapi import FastAPI

from .models import ParseColumnRequest, ParseColumnResponse
from .parser import parse_column_lineage

app = FastAPI(
    title="lineage-parser-service",
    version="0.1.0",
    description="Column-level SQL lineage parser (SQLGlot). Stateless; never connects to any database.",
)


@app.get("/health")
def health() -> dict[str, bool]:
    return {"ok": True}


@app.post("/v1/parse/column", response_model=ParseColumnResponse)
def parse_column(request: ParseColumnRequest) -> ParseColumnResponse:
    return parse_column_lineage(request)
