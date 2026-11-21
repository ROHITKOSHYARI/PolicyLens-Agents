"""Internal document ingestion and tenant-isolated retrieval API."""

import hmac
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Annotated
from uuid import UUID
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel, Field
from fastapi.responses import JSONResponse

from app.retrieval.chunk_store import SQLiteChunkStore
from app.retrieval.embeddings import SentenceTransformerEmbedder
from app.retrieval.parsers import DocumentParseError
from app.retrieval.service import RetrievalService
from app.retrieval.vector_store import ChromaVectorStore

_retrieval_service: RetrievalService | None = None


@asynccontextmanager
async def lifespan(_app: FastAPI):
    """Warm persistent indexes at container startup when enabled by deployment config."""
    if os.environ.get("RETRIEVAL_EAGER_INIT", "true").casefold() == "true":
        get_retrieval_service()
    yield


app = FastAPI(title="PolicyLens Agent Service", version="0.1.0", lifespan=lifespan)


def get_retrieval_service() -> RetrievalService:
    """Create the persistent retrieval stack on the first internal request."""
    global _retrieval_service
    if _retrieval_service is None:
        data_path = Path(os.environ.get("AGENT_DATA_PATH", "/data"))
        model_name = os.environ.get("EMBEDDING_MODEL", "sentence-transformers/all-MiniLM-L6-v2")
        cache_path = os.environ.get("HF_HOME", "/opt/model-cache")
        _retrieval_service = RetrievalService(
            chunk_store=SQLiteChunkStore(os.environ.get("CHUNK_DB_PATH", str(data_path / "chunks.sqlite3"))),
            vector_store=ChromaVectorStore(os.environ.get("CHROMA_PATH", str(data_path / "chroma"))),
            embedder=SentenceTransformerEmbedder(model_name, cache_path),
            chunk_size=int(os.environ.get("CHUNK_SIZE", "1000")),
            chunk_overlap=int(os.environ.get("CHUNK_OVERLAP", "150")),
            rrf_k=int(os.environ.get("RRF_K", "60")),
        )
    return _retrieval_service


class RetrievalRequest(BaseModel):
    """User query and result limit; tenant scope is carried in a trusted header."""

    query: str = Field(min_length=1, max_length=4000)
    limit: int = Field(default=5, ge=1, le=100)


@app.exception_handler(DocumentParseError)
async def document_parse_error(_request: Request, exception: DocumentParseError):
    """Return parser failures as concise client errors rather than stack traces."""
    return JSONResponse(status_code=422, content=error_body(422, str(exception)))


@app.exception_handler(RequestValidationError)
async def request_validation_error(_request: Request, _exception: RequestValidationError):
    """Hide request internals while returning a stable validation response."""
    return JSONResponse(status_code=422, content=error_body(422, "Invalid request"))


def error_body(status: int, message: str) -> dict[str, object]:
    """Build the common internal-service error shape."""
    return {"status": status, "message": message, "timestamp": datetime.now(timezone.utc).isoformat()}


@app.middleware("http")
async def require_service_token(request: Request, call_next):
    """Keep health checks public and require a constant-time token check elsewhere."""
    if request.url.path == "/health":
        return await call_next(request)

    expected = os.environ.get("SERVICE_TOKEN", "").encode("utf-8")
    supplied = request.headers.get("X-Service-Token", "").encode("utf-8")
    if not expected or not hmac.compare_digest(expected, supplied):
        return JSONResponse(
            status_code=401,
            content={
                "status": 401,
                "message": "Invalid or missing service token",
                "timestamp": datetime.now(timezone.utc).isoformat(),
            },
        )
    return await call_next(request)


@app.get("/health")
async def health() -> dict[str, str]:
    """Return service liveness without requiring the internal token."""
    return {"status": "ok"}


@app.get("/internal/ping")
async def internal_ping() -> dict[str, str]:
    """Provide a protected placeholder route for checking service auth."""
    return {"status": "ok"}


@app.post("/internal/ingest")
async def ingest_document(
    tenant_id: Annotated[str, Header(alias="X-Tenant-Id", min_length=1)],
    document_id: Annotated[str, Form(min_length=1)],
    filename: Annotated[str, Form(min_length=1)],
    file: Annotated[UploadFile, File()],
) -> dict[str, object]:
    """Parse and index one uploaded file within the gateway-provided tenant."""
    try:
        UUID(tenant_id)
        UUID(document_id)
    except ValueError as error:
        raise HTTPException(status_code=422, detail="Invalid tenant or document identifier") from error
    maximum = int(os.environ.get("MAX_UPLOAD_SIZE_BYTES", str(10 * 1024 * 1024)))
    content = await file.read(maximum + 1)
    await file.close()
    if not content:
        raise HTTPException(status_code=422, detail="Uploaded file is empty")
    if len(content) > maximum:
        raise HTTPException(status_code=413, detail="Uploaded file exceeds the configured size limit")
    chunks = get_retrieval_service().ingest(tenant_id, document_id, Path(filename).name, content)
    return {"documentId": document_id, "chunkCount": chunks, "status": "READY"}


@app.post("/internal/retrieve")
async def retrieve_documents(
    payload: RetrievalRequest,
    tenant_id: Annotated[str, Header(alias="X-Tenant-Id", min_length=1)],
) -> dict[str, object]:
    """Return tenant-filtered hybrid retrieval results and source ranks."""
    try:
        UUID(tenant_id)
    except ValueError as error:
        raise HTTPException(status_code=422, detail="Invalid tenant identifier") from error
    results = get_retrieval_service().retrieve(tenant_id, payload.query, payload.limit)
    return {
        "results": [
            {
                "chunkId": item.chunk.chunk_id,
                "documentId": item.chunk.document_id,
                "filename": item.chunk.filename,
                "text": item.chunk.text,
                "score": item.score,
                "vectorRank": item.vector_rank,
                "bm25Rank": item.bm25_rank,
            }
            for item in results
        ]
    }


@app.delete("/internal/documents/{document_id}")
async def delete_document(
    document_id: str,
    tenant_id: Annotated[str, Header(alias="X-Tenant-Id", min_length=1)],
) -> dict[str, str]:
    """Remove vectors and lexical chunks only for the specified tenant."""
    try:
        UUID(tenant_id)
        UUID(document_id)
    except ValueError as error:
        raise HTTPException(status_code=422, detail="Invalid tenant or document identifier") from error
    get_retrieval_service().delete(tenant_id, document_id)
    return {"status": "deleted"}
