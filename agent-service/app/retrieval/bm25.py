"""Small BM25 implementation with physically separate corpora per tenant."""

from __future__ import annotations

import math
import re
from collections import Counter, defaultdict

from app.retrieval.models import Chunk

TOKEN_PATTERN = re.compile(r"[\w]+", re.UNICODE)


def tokenize(text: str) -> list[str]:
    """Return normalized terms for BM25 scoring."""
    return TOKEN_PATTERN.findall(text.casefold())


class TenantBM25:
    """Maintain independent BM25 corpora and document frequencies per tenant."""

    def __init__(self, k1: float = 1.5, b: float = 0.75) -> None:
        self.k1 = k1
        self.b = b
        self._chunks: dict[str, list[Chunk]] = defaultdict(list)
        self._tokens: dict[str, list[list[str]]] = {}
        self._document_frequency: dict[str, Counter[str]] = {}

    def rebuild(self, chunks: list[Chunk]) -> None:
        """Rebuild indexes from stored chunks, partitioned before tokenization."""
        self._chunks.clear()
        for chunk in chunks:
            self._chunks[chunk.tenant_id].append(chunk)
        self._tokens.clear()
        self._document_frequency.clear()
        for tenant_id, tenant_chunks in self._chunks.items():
            documents = [tokenize(chunk.text) for chunk in tenant_chunks]
            frequencies: Counter[str] = Counter()
            for terms in documents:
                frequencies.update(set(terms))
            self._tokens[tenant_id] = documents
            self._document_frequency[tenant_id] = frequencies

    def rank(self, tenant_id: str, query: str) -> list[tuple[Chunk, float]]:
        """Rank a query only against the selected tenant's corpus."""
        chunks = self._chunks.get(tenant_id, [])
        documents = self._tokens.get(tenant_id, [])
        if not chunks:
            return []
        query_terms = tokenize(query)
        average_length = sum(map(len, documents)) / len(documents) or 1.0
        document_count = len(documents)
        dfs = self._document_frequency[tenant_id]
        scored: list[tuple[Chunk, float]] = []
        for chunk, terms in zip(chunks, documents, strict=True):
            term_counts = Counter(terms)
            score = 0.0
            for term in query_terms:
                frequency = term_counts[term]
                if not frequency:
                    continue
                idf = math.log(1 + (document_count - dfs[term] + 0.5) / (dfs[term] + 0.5))
                denominator = frequency + self.k1 * (1 - self.b + self.b * len(terms) / average_length)
                score += idf * frequency * (self.k1 + 1) / denominator
            scored.append((chunk, score))
        return sorted(scored, key=lambda item: (-item[1], item[0].chunk_id))
