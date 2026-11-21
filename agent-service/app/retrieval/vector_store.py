"""Vector-store interface and persistent ChromaDB implementation."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, Sequence

from app.retrieval.models import Chunk


@dataclass(frozen=True)
class VectorMatch:
    """A tenant-filtered vector search hit."""

    chunk: Chunk
    distance: float


class VectorStore(Protocol):
    """Operations needed by the retrieval service."""

    def replace_document(self, tenant_id: str, document_id: str, chunks: Sequence[Chunk], vectors: Sequence[Sequence[float]]) -> None: ...
    def search(self, tenant_id: str, query_vector: Sequence[float], limit: int) -> list[VectorMatch]: ...
    def delete_document(self, tenant_id: str, document_id: str) -> None: ...


class ChromaVectorStore:
    """Persistent Chroma collection with tenant metadata filters on every query."""

    def __init__(self, path: str, collection_name: str = "document_chunks") -> None:
        import chromadb

        self.client = chromadb.PersistentClient(path=path)
        self.collection = self.client.get_or_create_collection(
            name=collection_name,
            metadata={"hnsw:space": "cosine"},
            embedding_function=None,
        )

    def replace_document(self, tenant_id: str, document_id: str, chunks: Sequence[Chunk], vectors: Sequence[Sequence[float]]) -> None:
        self.delete_document(tenant_id, document_id)
        if not chunks:
            return
        self.collection.upsert(
            ids=[chunk.chunk_id for chunk in chunks],
            embeddings=[list(vector) for vector in vectors],
            documents=[chunk.text for chunk in chunks],
            metadatas=[{"tenant_id": tenant_id, "document_id": document_id, "filename": chunk.filename} for chunk in chunks],
        )

    def search(self, tenant_id: str, query_vector: Sequence[float], limit: int) -> list[VectorMatch]:
        result = self.collection.query(
            query_embeddings=[list(query_vector)],
            n_results=limit,
            where={"tenant_id": tenant_id},
            include=["documents", "metadatas", "distances"],
        )
        ids = result.get("ids", [[]])[0]
        docs = result.get("documents", [[]])[0]
        metadatas = result.get("metadatas", [[]])[0]
        distances = result.get("distances", [[]])[0]
        return [
            VectorMatch(
                Chunk(
                    chunk_id=chunk_id,
                    document_id=metadata["document_id"],
                    tenant_id=metadata["tenant_id"],
                    filename=metadata["filename"],
                    text=document,
                ),
                float(distance),
            )
            for chunk_id, document, metadata, distance in zip(ids, docs, metadatas, distances, strict=True)
        ]

    def delete_document(self, tenant_id: str, document_id: str) -> None:
        self.collection.delete(where={"tenant_id": tenant_id, "document_id": document_id})
