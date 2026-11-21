from app.llm.client import DiskResponseCache, FakeLLM, LLMError, LLMResult, RateLimiter, build_llm_client, with_retries


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
