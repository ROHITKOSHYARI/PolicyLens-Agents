"""Embedding interface with deterministic test and local model implementations."""

from __future__ import annotations

import hashlib
import math
from typing import Protocol, Sequence


class Embedder(Protocol):
    """Produces vectors for chunks and retrieval queries."""

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        """Return one vector per input text."""


class FakeEmbedder:
    """Small deterministic embedder used in tests without downloading a model."""

    def __init__(self, dimensions: int = 96) -> None:
        self.dimensions = dimensions

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        vectors: list[list[float]] = []
        for text in texts:
            vector = [0.0] * self.dimensions
            for token in text.casefold().split():
                digest = hashlib.sha256(token.encode("utf-8")).digest()
                index = int.from_bytes(digest[:4], "big") % self.dimensions
                vector[index] += 1.0 if digest[4] & 1 else -1.0
            norm = math.sqrt(sum(value * value for value in vector)) or 1.0
            vectors.append([value / norm for value in vector])
        return vectors


class SentenceTransformerEmbedder:
    """Load a pre-cached sentence-transformers model without network access."""

    def __init__(self, model_name: str, cache_folder: str) -> None:
        from sentence_transformers import SentenceTransformer

        self.model = SentenceTransformer(model_name, cache_folder=cache_folder, local_files_only=True)

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        if not texts:
            return []
        vectors = self.model.encode(list(texts), normalize_embeddings=True, convert_to_numpy=True)
        return vectors.tolist()
