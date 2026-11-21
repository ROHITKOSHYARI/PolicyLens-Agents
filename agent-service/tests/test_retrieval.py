"""Unit tests for chunking, tenant isolation, persistence, and hybrid ranking."""

from __future__ import annotations

import math
import os
import io
from pathlib import Path

import pytest

from app.retrieval.chunk_store import SQLiteChunkStore
from app.retrieval.chunking import chunk_text
from app.retrieval.embeddings import FakeEmbedder
from app.retrieval.embeddings import SentenceTransformerEmbedder
from app.retrieval.models import Chunk
from app.retrieval.parsers import DocumentParseError, ParseLimits, extract_text
from app.retrieval.service import RetrievalService, reciprocal_rank_fusion
from app.retrieval.vector_store import VectorMatch


class MemoryVectorStore:
    """Test double that applies tenant filters inside each vector search."""

    def __init__(self) -> None:
        self.items: dict[str, tuple[Chunk, list[float]]] = {}
        self.query_tenants: list[str] = []

    def replace_document(self, tenant_id, document_id, chunks, vectors):
        self.delete_document(tenant_id, document_id)
        self.items.update({chunk.chunk_id: (chunk, list(vector)) for chunk, vector in zip(chunks, vectors, strict=True)})

    def search(self, tenant_id, query_vector, limit):
        self.query_tenants.append(tenant_id)
        candidates = [
            (chunk, vector)
            for chunk, vector in self.items.values()
            if chunk.tenant_id == tenant_id
        ]
        scored = [
            (chunk, 1 - sum(a * b for a, b in zip(vector, query_vector, strict=True)))
            for chunk, vector in candidates
        ]
        scored.sort(key=lambda pair: (pair[1], pair[0].chunk_id))
        return [VectorMatch(chunk, distance) for chunk, distance in scored[:limit]]

    def delete_document(self, tenant_id, document_id):
        self.items = {
            chunk_id: pair for chunk_id, pair in self.items.items()
            if not (pair[0].tenant_id == tenant_id and pair[0].document_id == document_id)
        }


def make_service(path: str = ":memory:", store: MemoryVectorStore | None = None) -> RetrievalService:
    return RetrievalService(SQLiteChunkStore(path), store or MemoryVectorStore(), FakeEmbedder(), chunk_size=200, chunk_overlap=20)


def test_chunking_empty_short_overlap_and_invalid_overlap():
    assert chunk_text("  ", size=10, overlap=2) == []
    assert chunk_text(" short ", size=10, overlap=2) == ["short"]
    chunks = chunk_text("abcdefghijklmnop", size=10, overlap=4)
    assert chunks == ["abcdefghij", "ghijklmnop"]
    with pytest.raises(ValueError, match="overlap"):
        chunk_text("data", size=4, overlap=4)


def test_rrf_combines_rankings_and_exposes_each_source_rank():
    a = Chunk("a", "d", "t", "a.txt", "a")
    b = Chunk("b", "d", "t", "a.txt", "b")
    c = Chunk("c", "d", "t", "a.txt", "c")
    result = reciprocal_rank_fusion([a, b], [a, c, b], k=10)
    assert [item.chunk.chunk_id for item in result] == ["a", "b", "c"]
    assert result[0].vector_rank == 1 and result[0].bm25_rank == 1
    assert result[1].vector_rank == 2 and result[1].bm25_rank == 3
    assert math.isclose(result[0].score, 2 / 11)
    with pytest.raises(ValueError, match="positive"):
        reciprocal_rank_fusion([], [], k=0)


def test_tenant_a_cannot_retrieve_tenant_b_identical_text():
    vector_store = MemoryVectorStore()
    service = make_service(store=vector_store)
    shared = b"identical confidential policy text"
    service.ingest("tenant-a", "doc-a", "a.txt", shared)
    service.ingest("tenant-b", "doc-b", "b.txt", shared)

    results = service.retrieve("tenant-a", "confidential policy")
    assert results
    assert {result.chunk.tenant_id for result in results} == {"tenant-a"}
    assert {result.chunk.document_id for result in results} == {"doc-a"}
    assert vector_store.query_tenants[-1] == "tenant-a"


def test_reingestion_replaces_chunks_and_deletion_removes_them():
    service = make_service()
    service.ingest("tenant-a", "doc-1", "policy.txt", b"old text about refunds")
    count = service.ingest("tenant-a", "doc-1", "policy.txt", b"new text about retention")
    assert count == 1
    assert len(service.chunk_store.list_tenant("tenant-a")) == 1
    assert service.retrieve("tenant-a", "refunds") == []
    assert service.retrieve("tenant-a", "retention")
    service.delete("tenant-a", "doc-1")
    assert service.chunk_store.list_tenant("tenant-a") == []


def test_bm25_rebuilds_from_persistent_chunks_after_restart(tmp_path: Path):
    path = str(tmp_path / "chunks.sqlite3")
    service = make_service(path)
    service.ingest("tenant-a", "doc-1", "policy.txt", b"retention schedule lasts seven years")

    restarted = RetrievalService(SQLiteChunkStore(path), MemoryVectorStore(), FakeEmbedder(), chunk_size=200, chunk_overlap=20)
    results = restarted.retrieve("tenant-a", "retention schedule")
    assert results
    assert results[0].bm25_rank == 1
    assert results[0].chunk.text == "retention schedule lasts seven years"


def test_parser_enforces_character_and_csv_row_limits():
    assert extract_text("note.txt", b"text", ParseLimits(max_characters=4)) == "text"
    with pytest.raises(DocumentParseError, match="character"):
        extract_text("note.txt", b"longer", ParseLimits(max_characters=4))
    with pytest.raises(DocumentParseError, match="row"):
        extract_text("rows.csv", b"a\nb\nc", ParseLimits(max_rows=2))
    with pytest.raises(DocumentParseError, match="parse"):
        extract_text("note.txt", b"\xff", ParseLimits())


def test_docx_parsing_reads_paragraphs_and_caps_table_rows():
    from docx import Document as WordDocument

    document = WordDocument()
    document.add_paragraph("Refunds are available for thirty days.")
    table = document.add_table(rows=1, cols=1)
    table.cell(0, 0).text = "Exceptions require review"
    stream = io.BytesIO()
    document.save(stream)
    payload = stream.getvalue()
    assert "Refunds are available" in extract_text("policy.docx", payload, ParseLimits())
    with pytest.raises(DocumentParseError, match="row"):
        extract_text("policy.docx", payload, ParseLimits(max_rows=0))


def test_pdf_parser_enforces_page_limit():
    from pypdf import PdfWriter

    writer = PdfWriter()
    writer.add_blank_page(width=100, height=100)
    writer.add_blank_page(width=100, height=100)
    stream = io.BytesIO()
    writer.write(stream)
    with pytest.raises(DocumentParseError, match="page"):
        extract_text("two-pages.pdf", stream.getvalue(), ParseLimits(max_pages=1))


@pytest.mark.skipif(os.getenv("RUN_REAL_EMBEDDER_TEST") != "1", reason="opt-in; requires the model already cached locally")
def test_real_embedding_model_when_explicitly_enabled():
    """Optional offline check for a model that was provisioned before test execution."""
    model = os.environ.get("EMBEDDING_MODEL", "sentence-transformers/all-MiniLM-L6-v2")
    cache = os.environ.get("HF_HOME", str(Path.home() / ".cache" / "huggingface"))
    vectors = SentenceTransformerEmbedder(model, cache).embed(["policy text"])
    assert len(vectors) == 1 and vectors[0]
