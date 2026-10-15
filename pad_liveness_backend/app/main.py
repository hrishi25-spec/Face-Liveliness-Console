"""
MOSIP Face Liveness Detection & Presentation Attack Detection (PAD) service.

Backend for the Desktop and Android Registration Clients: receives face
frame streams, runs passive liveness + PAD, escalates to active
challenge-response when required, and enforces liveness/PAD policy per
workflow (resident registration, operator authentication, supervisor
authentication).
"""
import logging

from fastapi import FastAPI, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.core.config import settings
from app.api.router import api_router
from app.services.image_utils import InvalidFrameError

logging.basicConfig(level=settings.LOG_LEVEL)
logger = logging.getLogger("pad_liveness")

app = FastAPI(
    title=settings.APP_NAME,
    version="1.0.0",
    description=(
        "Face liveness detection and Presentation Attack Detection (PAD) "
        "for MOSIP Registration Client workflows: resident registration, "
        "operator authentication, and supervisor authentication."
    ),
)

# Registration Clients (Desktop / Android) are the only expected callers.
# Origins are an explicit allow-list (settings.CORS_ORIGINS) — the previous
# wildcard + allow_credentials combination would let any site call the API.
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    allow_credentials=False,
    allow_methods=["GET", "POST", "PUT", "OPTIONS"],
    allow_headers=["Content-Type", "X-Admin-API-Key"],
)

# Strict CSP for our own pages; FastAPI's /docs and /redoc ship inline
# bootstrap scripts, so they are exempt from CSP only (all other headers still
# apply). API JSON responses don't execute anything either way.
_CSP = (
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
    "connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; "
    "frame-ancestors 'none'"
)


@app.middleware("http")
async def harden_requests(request: Request, call_next):
    """Security headers on every response + reject oversized bodies early."""
    if request.method in ("POST", "PUT"):
        try:
            content_length = int(request.headers.get("content-length", "0") or 0)
        except ValueError:
            content_length = -1
        if content_length > settings.MAX_REQUEST_BODY_BYTES:
            return JSONResponse(
                status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                content={"error": "PAYLOAD_TOO_LARGE",
                         "message": "Request body exceeds the configured limit.",
                         "status": 413},
            )
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Cross-Origin-Opener-Policy"] = "same-origin"
    response.headers["Permissions-Policy"] = "camera=(self), microphone=(), geolocation=()"
    if not request.url.path.startswith(("/docs", "/redoc", "/openapi.json")):
        response.headers["Content-Security-Policy"] = _CSP
    return response


@app.exception_handler(InvalidFrameError)
async def invalid_frame_handler(request: Request, exc: InvalidFrameError):
    return JSONResponse(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, content={"detail": str(exc)})


@app.get("/health", tags=["health"])
def health_check():
    return {"status": "ok", "service": settings.APP_NAME}


app.include_router(api_router)
