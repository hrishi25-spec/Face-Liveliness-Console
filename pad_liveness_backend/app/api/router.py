from fastapi import APIRouter

from app.api.routes import sessions, frames, challenges, config, audit, metrics

api_router = APIRouter(prefix="/api/v1")
api_router.include_router(sessions.router)
api_router.include_router(frames.router)
api_router.include_router(challenges.router)
api_router.include_router(config.router)
api_router.include_router(audit.router)
api_router.include_router(metrics.router)
