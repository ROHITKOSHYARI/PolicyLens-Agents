"""Core types, disk cache, rate limiting, retry policy, and provider factory."""
from __future__ import annotations

import hashlib
import json
import os
import random
import threading
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable, Protocol


@dataclass(frozen=True)
class LLMResult:
    """Structured model response and usage metadata."""
    text: str
    tokens_in: int
    tokens_out: int
    model: str
    provider: str
    latency_ms: int
    cached: bool = False


class LLMClient(Protocol):
    """Generate text from system and user prompts."""
    provider: str
    model: str

    def generate(self, system_prompt: str, user_prompt: str, *, temperature: float = 0.0,
                 bypass_cache: bool = False) -> LLMResult: ...


class LLMError(RuntimeError):
    """Safe provider failure with an HTTP-like status for retry decisions."""
    def __init__(self, message: str, status: int | None = None):
        super().__init__(message)
        self.status = status


class DiskResponseCache:
    """JSON response cache; entries contain no credentials or prompt secrets beyond prompts."""
    def __init__(self, directory: str | Path):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)

    @staticmethod
    def key(provider: str, model: str, temperature: float, system_prompt: str, user_prompt: str) -> str:
        payload = json.dumps([provider, model, temperature, system_prompt, user_prompt], ensure_ascii=False, separators=(",", ":"))
        return hashlib.sha256(payload.encode("utf-8")).hexdigest()

    def get(self, key: str) -> LLMResult | None:
        path = self.directory / f"{key}.json"
        try:
            values = json.loads(path.read_text(encoding="utf-8"))
            values["cached"] = True
            return LLMResult(**values)
        except FileNotFoundError:
            return None
        except (OSError, ValueError, TypeError):
            return None

    def put(self, key: str, result: LLMResult) -> None:
        path = self.directory / f"{key}.json"
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(asdict(result), ensure_ascii=False), encoding="utf-8")
        os.replace(temporary, path)


class RateLimiter:
    """Thread-safe fixed-window limiter with injectable monotonic clock and sleeper."""
    def __init__(self, requests_per_minute: int, clock: Callable[[], float] = time.monotonic,
                 sleeper: Callable[[float], None] = time.sleep):
        if requests_per_minute < 1:
            raise ValueError("requests_per_minute must be positive")
        self.interval = 60.0 / requests_per_minute
        self.clock, self.sleeper = clock, sleeper
        self._next = 0.0
        self._lock = threading.Lock()

    def acquire(self) -> None:
        with self._lock:
            now = self.clock()
            delay = max(0.0, self._next - now)
            self._next = max(now, self._next) + self.interval
        if delay:
            self.sleeper(delay)


def with_retries(operation: Callable[[], LLMResult], *, max_attempts: int = 3,
                 base_delay: float = 0.25, sleeper: Callable[[float], None] = time.sleep,
                 jitter: Callable[[], float] = random.random) -> LLMResult:
    """Retry only transient throttling/server/timeout failures with capped backoff."""
    if max_attempts < 1:
        raise ValueError("max_attempts must be positive")
    for attempt in range(max_attempts):
        try:
            return operation()
        except (TimeoutError, LLMError) as error:
            retryable = isinstance(error, TimeoutError) or error.status == 429 or (error.status is not None and 500 <= error.status <= 599)
            if not retryable or attempt + 1 == max_attempts:
                raise
            sleeper(base_delay * (2 ** attempt) * (1 + jitter()))
    raise AssertionError("unreachable")


class FakeLLM:
    """Deterministic test/local provider."""
    provider = "fake"
    def __init__(self, model: str = "fake-v1", response: str = "[chunk-1] Synthetic answer"):
        self.model, self.response, self.calls = model, response, 0

    def generate(self, system_prompt: str, user_prompt: str, *, temperature: float = 0.0,
                 bypass_cache: bool = False) -> LLMResult:
        self.calls += 1
        text = self.response
        return LLMResult(text, len((system_prompt + user_prompt).split()), len(text.split()), self.model,
                         self.provider, 0, False)


def build_llm_client(environ: dict[str, str] | None = None) -> LLMClient:
    """Construct the configured provider; Gemini credentials are validated at startup."""
    env = os.environ if environ is None else environ
    provider = env.get("LLM_PROVIDER", "fake").strip().lower()
    model = env.get("LLM_MODEL", "gemini-2.0-flash")
    if provider == "fake":
        return FakeLLM(env.get("LLM_MODEL", "fake-v1"))
    if provider == "gemini":
        if not env.get("GEMINI_API_KEY", "").strip():
            raise RuntimeError("LLM_PROVIDER=gemini requires GEMINI_API_KEY")
        from app.llm.providers import GeminiLLM
        return GeminiLLM(env["GEMINI_API_KEY"], model)
    if provider == "ollama":
        from app.llm.providers import OllamaLLM
        return OllamaLLM(env.get("OLLAMA_BASE_URL", "http://localhost:11434"), env.get("OLLAMA_MODEL", "llama3.1"))
    raise RuntimeError(f"Unsupported LLM_PROVIDER: {provider}")
