"""HTTP tests for protected ingestion and tenant-scoped retrieval endpoints."""

from fastapi.testclient import TestClient

from app import main
from app.retrieval.chunk_store import SQLiteChunkStore
from app.retrieval.embeddings import FakeEmbedder
from app.retrieval.service import RetrievalService


def make_test_service(tmp_path):
    class VectorStore:
        def __init__(self):
            self.items = {}

        def delete_document(self, tenant_id, document_id):
            self.items = {key: pair for key, pair in self.items.items()
                          if not (pair[0].tenant_id == tenant_id and pair[0].document_id == document_id)}

        def replace_document(self, tenant_id, document_id, chunks, vectors):
            self.delete_document(tenant_id, document_id)
            self.items.update({chunk.chunk_id: (chunk, vector) for chunk, vector in zip(chunks, vectors, strict=True)})

        def search(self, tenant_id, query_vector, limit):
            from app.retrieval.vector_store import VectorMatch
            matches = [pair[0] for pair in self.items.values() if pair[0].tenant_id == tenant_id]
            return [VectorMatch(chunk, 0.1) for chunk in matches[:limit]]

    return RetrievalService(SQLiteChunkStore(str(tmp_path / "api.sqlite3")), VectorStore(), FakeEmbedder())


def test_internal_retrieve_returns_explanatory_fields_and_header_tenant(tmp_path, monkeypatch):
    monkeypatch.setenv("SERVICE_TOKEN", "api-test-token")
    monkeypatch.setenv("RETRIEVAL_EAGER_INIT", "false")
    tenant_id = "00000000-0000-0000-0000-000000000001"
    service = make_test_service(tmp_path)
    service.ingest(tenant_id, "00000000-0000-0000-0000-000000000002", "policy.txt", b"refund policy is thirty days")
    monkeypatch.setattr(main, "get_retrieval_service", lambda: service)

    response = TestClient(main.app).post(
        "/internal/retrieve",
        headers={"X-Service-Token": "api-test-token", "X-Tenant-Id": tenant_id},
        json={"query": "refund policy", "limit": 5},
    )
    assert response.status_code == 200
    result = response.json()["results"][0]
    assert result["documentId"] == "00000000-0000-0000-0000-000000000002"
    assert result["filename"] == "policy.txt"
    assert result["text"] == "refund policy is thirty days"
    assert result["score"] > 0
    assert result["vectorRank"] == 1
    assert result["bm25Rank"] == 1


def test_ingestion_parser_failure_returns_clean_422(tmp_path, monkeypatch):
    monkeypatch.setenv("SERVICE_TOKEN", "api-test-token")
    monkeypatch.setenv("RETRIEVAL_EAGER_INIT", "false")
    monkeypatch.setattr(main, "get_retrieval_service", lambda: make_test_service(tmp_path))
    response = TestClient(main.app).post(
        "/internal/ingest",
        headers={
            "X-Service-Token": "api-test-token",
            "X-Tenant-Id": "00000000-0000-0000-0000-000000000001",
        },
        data={"document_id": "00000000-0000-0000-0000-000000000002", "filename": "bad.txt"},
        files={"file": ("bad.txt", b"\xff", "text/plain")},
    )
    assert response.status_code == 422
    assert response.json()["status"] == 422
    assert response.json()["message"] == "Could not parse document content"
    assert "Traceback" not in response.text
