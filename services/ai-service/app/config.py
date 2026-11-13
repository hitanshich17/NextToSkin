from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


def _find_schemas_dir() -> str:
    """The nearest schemas/ folder above this file: the repo root in development, /app/schemas in the image."""
    for parent in Path(__file__).resolve().parents:
        if (parent / "schemas").is_dir():
            return str(parent / "schemas")
    return "schemas"


class Settings(BaseSettings):
    """Settings come from environment variables (see docker-compose.yml / .env)."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_env: str = "local"
    llm_api_key: str = ""
    # Claude Haiku 5.5 with thinking off: in a 10-profile comparison it invented nothing, answered in 1-3 s and costs a
    # tenth of Haiku 4.5, which kept adding small unsupported phrases ("removes daily buildup").
    llm_model: str = "claude-haiku-5-5"
    # Thinking effort ("low", "medium", ...) for models that take it (Haiku 5.5 and newer); empty for Haiku 4.5,
    # which rejects the parameter.
    llm_effort: str = "low"
    # "disabled" turns thinking off on models that think by default (Haiku 5.5 accepts it at effort low to high):
    # explaining given facts needs no reasoning, and thinking made answers slower and less predictable.
    llm_thinking: str = "disabled"
    # Kept below core-api's read timeout, so core-api gets an answer (or an error) before it gives up.
    llm_timeout_seconds: float = 4.0

    # Catalog events (docs/events.md). Empty bootstrap servers: no consumer (e.g. unit tests).
    kafka_bootstrap_servers: str = ""
    # The recommender's own database, created on startup if missing.
    read_model_url: str = "postgresql://skinvidhi:skinvidhi_dev_password@localhost:5432/recommender"
    # Versioned event schemas shared with the catalog service (repo root schemas/, copied into the image).
    schemas_dir: str = Field(default_factory=lambda: _find_schemas_dir())

    @property
    def llm_configured(self) -> bool:
        return bool(self.llm_api_key)


@lru_cache
def get_settings() -> Settings:
    return Settings()
