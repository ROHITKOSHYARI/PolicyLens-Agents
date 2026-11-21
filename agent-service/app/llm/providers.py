"""Gemini and Ollama provider adapters."""
from __future__ import annotations

import json
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from app.llm.client import LLMError, LLMResult


class GeminiClient:
    """Adapter for the official google-genai SDK; credentials are never included in errors."""
    provider = "gemini"
    def __init__(self, api_key: str, model: str):
        self.model = model
        try:
            from google import genai
            self._client = genai.Client(api_key=api_key)
        except Exception as exc:
            raise LLMError("Gemini client could not be initialized") from None

    def generate(self, system_prompt: str, user_prompt: str, *, temperature: float = 0.0, bypass_cache: bool = False) -> LLMResult:
        from google.genai import types
        started = time.monotonic()
        try:
            response = self._client.models.generate_content(
                model=self.model, contents=user_prompt,
                config=types.GenerateContentConfig(system_instruction=system_prompt, temperature=temperature),
            )
        except Exception as exc:
            code = getattr(exc, "code", None) or getattr(exc, "status_code", None)
            status = int(code) if str(code).isdigit() else None
            raise LLMError("Gemini request failed", status) from None
        usage = getattr(response, "usage_metadata", None)
        return LLMResult(response.text or "", int(getattr(usage, "prompt_token_count", 0) or 0),
                         int(getattr(usage, "candidates_token_count", 0) or 0), self.model, self.provider,
                         int((time.monotonic() - started) * 1000), False)


class OllamaClient:
    """Adapter for Ollama's POST /api/chat endpoint."""
    provider = "ollama"
    def __init__(self, base_url: str, model: str, timeout: float = 60.0):
        self.base_url, self.model, self.timeout = base_url.rstrip("/"), model, timeout

    def generate(self, system_prompt: str, user_prompt: str, *, temperature: float = 0.0, bypass_cache: bool = False) -> LLMResult:
        body = json.dumps({"model": self.model, "stream": False, "messages": [
            {"role": "system", "content": system_prompt}, {"role": "user", "content": user_prompt}],
            "options": {"temperature": temperature}}).encode()
        req = Request(self.base_url + "/api/chat", data=body, headers={"Content-Type": "application/json"}, method="POST")
        started = time.monotonic()
        try:
            with urlopen(req, timeout=self.timeout) as response:
                data = json.loads(response.read())
        except HTTPError as exc:
            raise LLMError("Ollama request failed", exc.code) from None
        except (URLError, TimeoutError, OSError):
            raise LLMError("Ollama server is unreachable; check OLLAMA_BASE_URL and that Ollama is running") from None
        message = data.get("message") or {}
        return LLMResult(str(message.get("content", "")), int(data.get("prompt_eval_count", 0)),
                         int(data.get("eval_count", 0)), str(data.get("model", self.model)), self.provider,
                         int((time.monotonic() - started) * 1000), False)
