from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


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

    @property
    def llm_configured(self) -> bool:
        return bool(self.llm_api_key)


@lru_cache
def get_settings() -> Settings:
    return Settings()
