"""Typed retrieval domain objects."""

from dataclasses import dataclass


@dataclass(frozen=True)
class Chunk:
    """One indexed section of an uploaded document."""

    chunk_id: str
    document_id: str
    tenant_id: str
    filename: str
    text: str


@dataclass(frozen=True)
class RankedChunk:
    """A fused result and its per-retriever ranks."""

    chunk: Chunk
    score: float
    vector_rank: int | None
    bm25_rank: int | None
