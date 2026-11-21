"""Document ingestion, tenant-isolated hybrid retrieval, and deletion."""

from __future__ import annotations

import uuid
from typing import Sequence

from app.retrieval.bm25 import TenantBM25
from app.retrieval.chunk_store import SQLiteChunkStore
from app.retrieval.chunking import chunk_text
from app.retrieval.embeddings import Embedder
from app.retrieval.models import Chunk, RankedChunk
from app.retrieval.parsers import ParseLimits, extract_text
from app.retrieval.parsers import DocumentParseError
from app.retrieval.vector_store import VectorStore


def reciprocal_rank_fusion(
    vector_ranked: Sequence[Chunk], bm25_ranked: Sequence[Chunk], k: int = 60
) -> list[RankedChunk]:
    """Fuse vector and BM25 rankings using reciprocal rank fusion."""
    if k <= 0:
        raise ValueError("RRF constant must be positive")
    vector_ranks = {chunk.chunk_id: rank for rank, chunk in enumerate(vector_ranked, start=1)}
    bm25_ranks = {chunk.chunk_id: rank for rank, chunk in enumerate(bm25_ranked, start=1)}
    chunks = {chunk.chunk_id: chunk for chunk in (*vector_ranked, *bm25_ranked)}
    fused = []
    for chunk_id, chunk in chunks.items():
        vector_rank = vector_ranks.get(chunk_id)
        bm25_rank = bm25_ranks.get(chunk_id)
        score = (1 / (k + vector_rank) if vector_rank else 0) + (1 / (k + bm25_rank) if bm25_rank else 0)
        fused.append(RankedChunk(chunk, score, vector_rank, bm25_rank))
    return sorted(fused, key=lambda item: (-item.score, item.chunk.chunk_id))


class RetrievalService:
    """Coordinate bounded parsing, persistence, and tenant-scoped retrieval."""

    def __init__(
        self,
        chunk_store: SQLiteChunkStore,
        vector_store: VectorStore,
        embedder: Embedder,
        chunk_size: int = 1000,
        chunk_overlap: int = 150,
        rrf_k: int = 60,
        limits: ParseLimits | None = None,
    ) -> None:
        self.chunk_store = chunk_store
        self.vector_store = vector_store
        self.embedder = embedder
        self.chunk_size = chunk_size
        self.chunk_overlap = chunk_overlap
        self.rrf_k = rrf_k
        self.limits = limits or ParseLimits()
        self.bm25 = TenantBM25()
        self.rebuild_lexical_indexes()

    def rebuild_lexical_indexes(self) -> None:
        """Rebuild independent BM25 corpora from durable chunk records."""
        self.bm25.rebuild(self.chunk_store.list_all())

    def ingest(self, tenant_id: str, document_id: str, filename: str, data: bytes) -> int:
        """Parse and idempotently replace a tenant's document chunks."""
        text = extract_text(filename, data, self.limits)
        contents = chunk_text(text, self.chunk_size, self.chunk_overlap)
        if not contents:
            raise DocumentParseError("Document contains no extractable text")
        chunks = [
            Chunk(
                chunk_id=f"{tenant_id}:{document_id}:{index}",
                document_id=document_id,
                tenant_id=tenant_id,
                filename=filename,
                text=content,
            )
            for index, content in enumerate(contents)
        ]
        vectors = self.embedder.embed([chunk.text for chunk in chunks])
        try:
            self.vector_store.replace_document(tenant_id, document_id, chunks, vectors)
            self.chunk_store.replace_document(tenant_id, document_id, chunks)
            self.rebuild_lexical_indexes()
        except Exception:
            self.vector_store.delete_document(tenant_id, document_id)
            self.chunk_store.delete_document(tenant_id, document_id)
            self.rebuild_lexical_indexes()
            raise
        return len(chunks)

    def delete(self, tenant_id: str, document_id: str) -> None:
        """Delete a document's vectors and persisted lexical records in tenant scope."""
        self.vector_store.delete_document(tenant_id, document_id)
        self.chunk_store.delete_document(tenant_id, document_id)
        self.rebuild_lexical_indexes()

    def retrieve(self, tenant_id: str, query: str, limit: int = 5) -> list[RankedChunk]:
        """Search only one tenant's vector and separately-built BM25 indexes."""
        if not tenant_id:
            raise ValueError("tenant_id is required")
        if limit < 1 or limit > 100:
            raise ValueError("limit must be between 1 and 100")
        query_vector = self.embedder.embed([query])[0]
        vector_matches = self.vector_store.search(tenant_id, query_vector, limit)
        # BM25 assigns zero to non-matching documents. Exclude those entries so
        # they cannot appear in RRF merely because vector search always returns
        # its nearest neighbors, even when they are unrelated to the query.
        bm25_pairs = [(chunk, score) for chunk, score in self.bm25.rank(tenant_id, query) if score > 0][:limit]
        lexical_chunk_ids = {chunk.chunk_id for chunk, _score in bm25_pairs}
        vector_matches = [
            match
            for match in vector_matches
            if match.chunk.chunk_id in lexical_chunk_ids
        ]
        return reciprocal_rank_fusion(
            [match.chunk for match in vector_matches], [chunk for chunk, _score in bm25_pairs], self.rrf_k
        )[:limit]


def make_document_id() -> str:
    """Generate a document identifier suitable for stable chunk IDs."""
    return str(uuid.uuid4())
