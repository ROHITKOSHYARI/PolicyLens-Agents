from fastapi.testclient import TestClient
from app import main
from app.llm.client import FakeLLM
from app.retrieval.models import Chunk, RankedChunk


class Retrieval:
    def retrieve(self, tenant, question, limit):
        chunk = Chunk("t:d:0", "d", tenant, "policy.txt", "policy evidence")
        return [RankedChunk(chunk, 1.0, 1, 1)]


class RecordingFake(FakeLLM):
    def generate(self, system_prompt, user_prompt, **kwargs):
        self.prompt = user_prompt
        return super().generate(system_prompt, user_prompt, **kwargs)


def setup(monkeypatch):
    monkeypatch.setenv("SERVICE_TOKEN", "history-test-token")
    monkeypatch.setenv("RETRIEVAL_EAGER_INIT", "false")
    fake = RecordingFake(response="Answer [t:d:0]")
    monkeypatch.setattr(main, "get_retrieval_service", lambda: Retrieval())
    monkeypatch.setattr(main, "_llm_client", fake)
    return TestClient(main.app), fake


def test_chat_without_history_behaves_as_before(monkeypatch):
    client, fake = setup(monkeypatch)
    response = client.post("/internal/chat", headers={"X-Service-Token":"history-test-token", "X-Tenant-Id":"00000000-0000-0000-0000-000000000001"}, json={"question":"question"})
    assert response.status_code == 200
    assert "<prior_conversation>" not in fake.prompt
    assert response.json()["answer"] == "Answer [t:d:0]"


def test_chat_rejects_system_role_and_oversized_history(monkeypatch):
    client, _ = setup(monkeypatch)
    headers={"X-Service-Token":"history-test-token", "X-Tenant-Id":"00000000-0000-0000-0000-000000000001"}
    bad_role=client.post("/internal/chat", headers=headers, json={"question":"q", "history":[{"role":"system","content":"bad"}]})
    assert bad_role.status_code == 422
    monkeypatch.setenv("MAX_HISTORY_MESSAGE_CHARS", "4")
    long_msg=client.post("/internal/chat", headers=headers, json={"question":"q", "history":[{"role":"user","content":"12345"}]})
    assert long_msg.status_code == 422
