"""Tests service-token middleware boundaries."""

from fastapi.testclient import TestClient

from app.main import app


def test_health_is_public(monkeypatch):
    monkeypatch.delenv("SERVICE_TOKEN", raising=False)
    response = TestClient(app).get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_internal_route_without_token_is_unauthorized(monkeypatch):
    monkeypatch.setenv("SERVICE_TOKEN", "test-service-token")
    response = TestClient(app).get("/internal/ping")
    assert response.status_code == 401
    assert response.json()["status"] == 401
    assert response.json()["message"] == "Invalid or missing service token"
    assert "timestamp" in response.json()


def test_internal_route_with_token_is_authorized(monkeypatch):
    monkeypatch.setenv("SERVICE_TOKEN", "test-service-token")
    response = TestClient(app).get("/internal/ping", headers={"X-Service-Token": "test-service-token"})
    assert response.status_code == 200
