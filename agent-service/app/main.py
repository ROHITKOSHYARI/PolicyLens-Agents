"""Internal FastAPI app foundation with service-token protection."""

import hmac
import os
from datetime import datetime, timezone

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

app = FastAPI(title="PolicyLens Agent Service", version="0.1.0")


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
