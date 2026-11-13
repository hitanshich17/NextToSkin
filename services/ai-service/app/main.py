"""NextToSkin AI service.

Responsible for the AI-heavy work: reading ingredient lists from label photos,
embeddings for product similarity, and plain-language explanations.
Safety decisions (culprit ranking, red flags, remedy filtering) stay in rule-based code.
"""

import logging
import threading
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException

from app.config import Settings, get_settings
from app.events.consumer import ProductEventsConsumer
from app.events.read_model import ReadModel, ensure_database
from app.events.schemas import load_schemas
from app.explain import ExplanationFacts, InvalidExplanation, explain
from app.llm import AnthropicLlm, Llm, LlmError

log = logging.getLogger(__name__)

consumer: ProductEventsConsumer | None = None


@asynccontextmanager
async def lifespan(_: FastAPI):
    """Starts the catalog-events consumer in a background thread when Kafka is configured."""
    global consumer
    settings = get_settings()
    thread = None
    if settings.kafka_bootstrap_servers:
        ensure_database(settings.read_model_url)
        consumer = ProductEventsConsumer(settings.kafka_bootstrap_servers, ReadModel(settings.read_model_url),
                                         load_schemas(settings.schemas_dir, "product-updated"))
        thread = threading.Thread(target=consumer.run, name="product-events", daemon=True)
        thread.start()
    yield
    if consumer is not None:
        consumer.stop()
        thread.join(timeout=10)


app = FastAPI(title="NextToSkin AI Service", version="0.1.0", lifespan=lifespan)


@app.get("/health")
def health() -> dict:
    settings = get_settings()
    return {
        "service": "ai-service",
        "status": "up",
        "env": settings.app_env,
        "llm_configured": settings.llm_configured,
        "catalog_events": {"consuming": consumer is not None, **(consumer.stats if consumer else {}),
                           "partitions": consumer.partitions if consumer else []},
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
