"""Persistent SQLite catalog used to rebuild per-tenant lexical indexes."""

from __future__ import annotations

import sqlite3
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Iterable, Iterator

from app.retrieval.models import Chunk


class SQLiteChunkStore:
    """Persist chunk text and metadata independently of the in-memory BM25 index."""

    def __init__(self, path: str) -> None:
        self._use_uri = path == ":memory:"
        self.path = f"file:policylens-{uuid.uuid4().hex}?mode=memory&cache=shared" if self._use_uri else path
        self._anchor = sqlite3.connect(self.path, uri=True, check_same_thread=False) if self._use_uri else None
        if not self._use_uri:
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as connection:
            connection.execute("""
                CREATE TABLE IF NOT EXISTS chunks (
                    chunk_id TEXT PRIMARY KEY,
                    tenant_id TEXT NOT NULL,
                    document_id TEXT NOT NULL,
                    filename TEXT NOT NULL,
                    text TEXT NOT NULL
                )
            """)
            connection.execute("CREATE INDEX IF NOT EXISTS idx_chunks_tenant ON chunks(tenant_id)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_chunks_document ON chunks(tenant_id, document_id)")

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self.path, uri=self._use_uri, check_same_thread=False)
        try:
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def replace_document(self, tenant_id: str, document_id: str, chunks: Iterable[Chunk]) -> None:
        """Replace all chunks for one tenant/document pair atomically."""
        with self._connect() as connection:
            connection.execute("DELETE FROM chunks WHERE tenant_id = ? AND document_id = ?", (tenant_id, document_id))
            connection.executemany(
                "INSERT INTO chunks(chunk_id, tenant_id, document_id, filename, text) VALUES (?, ?, ?, ?, ?)",
                [(c.chunk_id, c.tenant_id, c.document_id, c.filename, c.text) for c in chunks],
            )

    def delete_document(self, tenant_id: str, document_id: str) -> None:
        """Remove chunks scoped to a tenant and document ID."""
        with self._connect() as connection:
            connection.execute("DELETE FROM chunks WHERE tenant_id = ? AND document_id = ?", (tenant_id, document_id))

    def list_tenant(self, tenant_id: str) -> list[Chunk]:
        """Load persisted chunks for exactly one tenant."""
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT chunk_id, document_id, tenant_id, filename, text FROM chunks WHERE tenant_id = ? ORDER BY chunk_id",
                (tenant_id,),
            ).fetchall()
        return [Chunk(*row) for row in rows]

    def list_all(self) -> list[Chunk]:
        """Load all chunks for startup index rebuilding, preserving tenant labels."""
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT chunk_id, document_id, tenant_id, filename, text FROM chunks ORDER BY tenant_id, chunk_id"
            ).fetchall()
        return [Chunk(*row) for row in rows]
