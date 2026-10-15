"""
Application configuration, loaded from environment variables / .env file.
"""
from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    APP_NAME: str = "MOSIP Face Liveness & PAD Service"
    APP_ENV: str = "development"
    LOG_LEVEL: str = "INFO"

    POSTGRES_USER: str = "mosip"
    POSTGRES_PASSWORD: str = "change_me"
    POSTGRES_DB: str = "pad_liveness"
    POSTGRES_HOST: str = "localhost"
    POSTGRES_PORT: int = 5432

    # ---- Default liveness/PAD policy (used to seed config table) ----
    DEFAULT_PASSIVE_THRESHOLD: float = 0.75
    DEFAULT_ACTIVE_LIVENESS_ENABLED: bool = True
    DEFAULT_MIN_CHALLENGE_COUNT: int = 1
    DEFAULT_CHALLENGE_TIMEOUT_MS: int = 8000
    DEFAULT_MAX_RETRY_COUNT: int = 3
    DEFAULT_CHALLENGE_TYPES: list[str] = ["blink", "smile", "turn_left", "turn_right"]

    # ---- Security ----
    # The key a privileged write must present as X-Admin-API-Key. Blank means
    # "no key configured", which refuses the route outright (403) rather than
    # leaving it open — see app.api.deps.require_admin_key. Read only by the
    # policy update today; the routes that need it are listed in the README's
    # Security section.
    #
    # Named with its MOSIP_ prefix, unlike the fields above, because it is the same
    # environment variable the Spring service documents for the same purpose and the
    # name is part of the operator-facing contract: the field name *is* the env var
    # pydantic-settings looks for, so calling it ADMIN_API_KEY would silently read a
    # different variable than the one every document tells you to set.
    MOSIP_ADMIN_API_KEY: str = ""

    # Origins allowed to call the API cross-origin (Registration Clients and
    # local dev servers). Override via CORS_ORIGINS (JSON list) in production;
    # the old wildcard + credentials combination is gone.
    CORS_ORIGINS: list[str] = [
        "http://localhost:8000",
        "http://127.0.0.1:8000",
        "http://localhost:5173",
        "http://127.0.0.1:5173",
    ]
    # Reject POST/PUT bodies larger than this before they are parsed.
    MAX_REQUEST_BODY_BYTES: int = 24 * 1024 * 1024

    @property
    def SQLALCHEMY_DATABASE_URI(self) -> str:
        return (
            f"postgresql+psycopg2://{self.POSTGRES_USER}:{self.POSTGRES_PASSWORD}"
            f"@{self.POSTGRES_HOST}:{self.POSTGRES_PORT}/{self.POSTGRES_DB}"
        )


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
