import pytest

from app.llm.client import (DiskResponseCache, FakeLLM, LLMError, LLMResult, RateLimiter,
                            ResilientLLMClient, build_llm_client, with_retries)


def test_result_and_fake_provider_are_deterministic():
    client = FakeLLM(response="fixed")
    one = client.generate("system", "question")
    two = client.generate("system", "question")
    assert one.text == two.text == "fixed"
    assert one.provider == "fake" and one.cached is False
    assert client.calls == 2


def test_cache_key_covers_all_prompt_settings_and_roundtrips(tmp_path):
    cache = DiskResponseCache(tmp_path)
    key = cache.key("fake", "v1", 0, "sys", "prompt")
    assert key != cache.key("fake", "v1", 0.2, "sys", "prompt")
    assert cache.get(key) is None
    cache.put(key, LLMResult("answer", 2, 1, "v1", "fake", 4))
    assert cache.get(key).cached is True


def test_rate_limiter_uses_injected_clock():
    now = [10.0]
    slept = []
    def sleep(delay):
        slept.append(delay)
        now[0] += delay
    limiter = RateLimiter(30, clock=lambda: now[0], sleeper=sleep)
    limiter.acquire(); limiter.acquire()
    assert slept == [2.0]


def test_retry_only_transient_statuses_and_backoff():
    delays, calls = [], [0]
    def flaky():
        calls[0] += 1
        if calls[0] == 1:
            raise LLMError("throttled", 429)
        return LLMResult("ok", 0, 0, "m", "fake", 0)
    result = with_retries(flaky, sleeper=delays.append, jitter=lambda: 0)
    assert result.text == "ok" and delays == [0.25]
    calls[0] = 0
    def forbidden():
        calls[0] += 1
        raise LLMError("bad request", 400)
    try:
        with_retries(forbidden, sleeper=delays.append)
    except LLMError:
        pass
    assert calls[0] == 1


def test_factory_default_fake_and_missing_gemini_key():
    assert build_llm_client({"LLM_PROVIDER": "fake"}).provider == "fake"
    try:
        build_llm_client({"LLM_PROVIDER": "gemini"})
    except RuntimeError as error:
        assert "GEMINI_API_KEY" in str(error)
    else:
        raise AssertionError("missing Gemini key must fail at startup")


def test_wrapper_cache_hit_skips_provider_and_limiter(tmp_path):
    provider = FakeLLM(response="answer")
    clock = [0.0]
    limiter = RateLimiter(1000, clock=lambda: clock[0], sleeper=lambda delay: clock.__setitem__(0, clock[0] + delay))
    client = ResilientLLMClient(provider, DiskResponseCache(tmp_path), limiter)
    first = client.generate("sys", "prompt")
    second = client.generate("sys", "prompt")
    assert first.cached is False and second.cached is True
    assert provider.calls == 1
    assert limiter._next == pytest.approx(0.06)
    assert clock[0] == 0.0  # only the first acquire ran; no wait occurred


def test_wrapper_retries_429_then_caches_success(tmp_path):
    class Flaky(FakeLLM):
        def generate(self, *args, **kwargs):
            self.calls += 1
            if self.calls == 1:
                raise LLMError("rate limited", 429)
            return LLMResult("ok", 1, 1, self.model, self.provider, 0)
    provider = Flaky()
    delays = []
    client = ResilientLLMClient(provider, DiskResponseCache(tmp_path), RateLimiter(1000, sleeper=lambda _: None),
                                sleeper=delays.append, jitter=lambda: 0)
    assert client.generate("s", "u").text == "ok"
    assert provider.calls == 2 and delays == [0.25]
    assert client.generate("s", "u").cached is True


@pytest.mark.parametrize("status", [400, 401, 403])
def test_wrapper_does_not_retry_non_retryable_or_cache_errors(tmp_path, status):
    class Failure:
        provider, model, calls = "fake", "fake-v1", 0
        def generate(self, *args, **kwargs):
            self.calls += 1
            raise LLMError("request failed", status)
    provider = Failure()
    cache = DiskResponseCache(tmp_path)
    client = ResilientLLMClient(provider, cache, RateLimiter(1000, sleeper=lambda _: None), sleeper=lambda _: None)
    with pytest.raises(LLMError):
        client.generate("system", "user")
    key = cache.key("fake", "fake-v1", 0.0, "system", "user")
    assert provider.calls == 1 and cache.get(key) is None


def test_bypass_cache_calls_provider_again(tmp_path):
    provider = FakeLLM(response="fresh")
    client = ResilientLLMClient(provider, DiskResponseCache(tmp_path), RateLimiter(1000, sleeper=lambda _: None))
    client.generate("s", "u")
    forced = client.generate("s", "u", bypass_cache=True)
    assert provider.calls == 2 and not forced.cached


def test_gemini_key_is_not_in_errors_logs_or_cache(tmp_path, caplog):
    from app.llm.providers import GeminiClient
    key = "test-secret-key-never-persist"
    client = GeminiClient.__new__(GeminiClient)
    client.model = "fake-model"
    class Models:
        def generate_content(self, **kwargs):
            raise RuntimeError(f"provider echoed {key}")
    client._client = type("Client", (), {"models": Models()})()
    with pytest.raises(LLMError) as caught:
        client.generate("s", "u")
    cache = DiskResponseCache(tmp_path)
    cache.put("some-cache-key", LLMResult("safe", 0, 0, "m", "gemini", 0))
    persisted = "".join(p.read_text(encoding="utf-8") for p in tmp_path.iterdir())
    assert key not in str(caught.value) + caplog.text + persisted
