"""GET/PUT/DELETE /api/v1/feedback: "tried it?" verdicts (same contract as the Java service had).

The browser identifies itself with a random UUID in the X-Client-Id header: no accounts, nothing personal.
"""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, Header, HTTPException, Request, Response
from pydantic import BaseModel

from app.events.read_model import ReadModel
from app.feedback.store import Feedback, FeedbackStore
from app.recommender.models import Reason

router = APIRouter(prefix="/api/v1/feedback")


def get_feedback_store(request: Request) -> FeedbackStore:
    return request.app.state.feedback_store


def get_read_model(request: Request) -> ReadModel:
    return request.app.state.read_model


def client_id(x_client_id: str | None = Header(default=None)) -> uuid.UUID:
    """The anonymous client id; 400 if missing or not a UUID."""
    try:
        return uuid.UUID(x_client_id or "")
    except ValueError:
        raise HTTPException(status_code=400, detail="X-Client-Id must be a UUID") from None


class FeedbackRequest(BaseModel):
    verdict: str | None = None
    reason: Reason | None = None
    sessionId: int | None = None


@router.get("")
def list_feedback(client: uuid.UUID = Depends(client_id), store: FeedbackStore = Depends(get_feedback_store)):
    return [{"productId": f.product_id, "verdict": f.verdict, "reason": f.reason.value if f.reason else None}
            for f in store.find_all(client)]


@router.put("/{product_id}", status_code=204)
def save_feedback(product_id: str, body: FeedbackRequest, client: uuid.UUID = Depends(client_id),
                  store: FeedbackStore = Depends(get_feedback_store),
                  read_model: ReadModel = Depends(get_read_model)) -> Response:
    try:
        feedback = Feedback(product_id, body.verdict, body.reason)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from None
    if not read_model.exists(product_id):
        raise HTTPException(status_code=404, detail="no such product")
    store.save(client, feedback, body.sessionId)
    return Response(status_code=204)


@router.delete("/{product_id}", status_code=204)
def undo_feedback(product_id: str, client: uuid.UUID = Depends(client_id),
                  store: FeedbackStore = Depends(get_feedback_store)) -> Response:
    store.delete(client, product_id)
    return Response(status_code=204)
