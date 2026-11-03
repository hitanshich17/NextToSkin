"""The LLM behind a small interface, so the provider can change in one place."""

from typing import Protocol

import anthropic


class LlmError(Exception):
    """The LLM couldn't answer (not configured, timeout, API error)."""


class Llm(Protocol):
    def complete(self, system: str, user: str, max_tokens: int) -> str: ...


class AnthropicLlm:
    def __init__(self, api_key: str, model: str, timeout_seconds: float):
        # No retries: the caller has a template fallback, and a retry could outlast core-api's timeout.
        self._client = anthropic.Anthropic(api_key=api_key, timeout=timeout_seconds, max_retries=0)
        self._model = model

    def complete(self, system: str, user: str, max_tokens: int) -> str:
        try:
            response = self._client.messages.create(
                model=self._model,
                max_tokens=max_tokens,
                system=system,
                messages=[{"role": "user", "content": user}],
            )
        except anthropic.APIError as e:
            raise LlmError(type(e).__name__) from e
        # A reply cut off at max_tokens, or a refusal, is not an answer we can use.
        if response.stop_reason != "end_turn":
            raise LlmError(f"stop_reason {response.stop_reason}")
        return "".join(block.text for block in response.content if block.type == "text")
