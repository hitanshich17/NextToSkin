"""SkinVidhi AI service.

Responsible for the AI-heavy work: reading ingredient lists from label photos,
embeddings for product similarity, and plain-language explanations.
Safety decisions (culprit ranking, red flags, remedy filtering) stay in rule-based code.
"""

import logging

from fastapi import Depends, FastAPI, HTTPException

from app.config import Settings, get_settings
from app.explain import ExplanationFacts, InvalidExplanation, explain
from app.llm import AnthropicLlm, Llm, LlmError

log = logging.getLogger(__name__)

app = FastAPI(title="SkinVidhi AI Service", version="0.1.0")


@app.get("/health")
def health() -> dict:
    settings = get_settings()
    return {
        "service": "ai-service",
        "status": "up",
        "env": settings.app_env,
        "llm_configured": settings.llm_configured,
    }


def get_llm(settings: Settings = Depends(get_settings)) -> Llm | None:
    if not settings.llm_configured:
        return None
    return AnthropicLlm(settings.llm_api_key, settings.llm_model, settings.llm_timeout_seconds, settings.llm_effort,
                        settings.llm_thinking)


@app.post("/explanations")
def create_explanation(facts: ExplanationFacts, llm: Llm | None = Depends(get_llm)) -> dict:
    """503 whenever there is no usable explanation; core-api then shows its template explanation."""
    if llm is None:
        raise HTTPException(status_code=503, detail="LLM not configured")
    try:
        return {"text": explain(facts, llm)}
    except LlmError as e:
        log.warning("LLM failed: %s", e)
        raise HTTPException(status_code=503, detail="LLM unavailable") from e
    except InvalidExplanation as e:
        log.warning("Explanation rejected: %s | %s", e, e.text)
        raise HTTPException(status_code=503, detail="explanation rejected") from e
