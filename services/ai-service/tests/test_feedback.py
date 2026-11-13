"""Feedback API (contract kept from the Java service) and storage on real PostgreSQL."""

import json
import uuid

import psycopg
import pytest
from fastapi.testclient import TestClient

from app.db import Database, ensure_database
from app.feedback.api import get_feedback_store, get_read_model
from app.feedback.store import Feedback, FeedbackStore, stored_answers
from app.main import app
from app.recommender.models import QuizAnswers, Reason
from tests.conftest import needs_docker

CLIENT = uuid.UUID("7d4f0c2e-5b7a-4a51-9c1e-0a2b3c4d5e6f")


class FakeStore:
    def __init__(self):
        self.saved, self.deleted = [], []

    def save(self, client, feedback, session_id):
        self.saved.append((client, feedback, session_id))

    def delete(self, client, product_id):
        self.deleted.append((client, product_id))

    def find_all(self, client):
        return [Feedback("rich-cream", "LIKED")]


class FakeReadModel:
    def exists(self, product_id):
        return product_id != "no-such-product"


@pytest.fixture
def api():
    store = FakeStore()
    app.dependency_overrides[get_feedback_store] = lambda: store
    app.dependency_overrides[get_read_model] = lambda: FakeReadModel()
    yield TestClient(app), store
    app.dependency_overrides.clear()


HEADERS = {"X-Client-Id": str(CLIENT)}


def test_saves_a_dislike_with_its_reason(api):
    client, store = api
    response = client.put("/api/v1/feedback/rich-cream", headers=HEADERS,
                          json={"verdict": "DISLIKED", "reason": "TEXTURE", "sessionId": 7})
    assert response.status_code == 204
    assert store.saved == [(CLIENT, Feedback("rich-cream", "DISLIKED", Reason.TEXTURE), 7)]


def test_unknown_product_is_404(api):
    client, _ = api
    assert client.put("/api/v1/feedback/no-such-product", headers=HEADERS, json={"verdict": "LIKED"}).status_code == 404


def test_a_like_cannot_have_a_reason(api):
    client, store = api
    response = client.put("/api/v1/feedback/rich-cream", headers=HEADERS, json={"verdict": "LIKED", "reason": "TEXTURE"})
    assert response.status_code == 400 and response.json()["detail"] == "only a dislike has a reason"
    assert store.saved == []


@pytest.mark.parametrize("headers", [{}, {"X-Client-Id": "not-a-uuid"}])
def test_missing_or_invalid_client_id_is_400(api, headers):
    client, _ = api
    assert client.get("/api/v1/feedback", headers=headers).status_code == 400


def test_lists_and_undoes(api):
    client, store = api
    assert client.get("/api/v1/feedback", headers=HEADERS).json() == [
        {"productId": "rich-cream", "verdict": "LIKED", "reason": None}]
    assert client.delete("/api/v1/feedback/rich-cream", headers=HEADERS).status_code == 204
    assert store.deleted == [(CLIENT, "rich-cream")]


def test_a_dislike_without_a_reason_counts_as_irritated():
    assert Feedback("x", "DISLIKED").effective_reason == Reason.IRRITATED
    assert Feedback("x", "DISLIKED", Reason.TOO_PRICEY).effective_reason == Reason.TOO_PRICEY
    assert Feedback("x", "LIKED").effective_reason is None


def answers(pregnancy="YES", city="Austin, TX") -> QuizAnswers:
    return QuizAnswers.from_json({"skinType": "OILY", "concerns": ["BREAKOUTS"], "reactivity": "SOMETIMES",
                                  "avoid": ["SOY", "FRAGRANCE"], "activesExperience": "NEVER", "pregnancy": pregnancy,
                                  "budgetCents": 6000, "city": city, "skinTone": 4})


def test_stored_answers_leave_out_pregnancy_and_city():
    stored = stored_answers(answers())
    assert "pregnancy" not in stored and "city" not in stored
    assert stored["avoid"] == ["FRAGRANCE", "SOY"] and stored["budgetCents"] == 6000 and stored["skinTone"] == 4


# ---- On real PostgreSQL ----


@pytest.fixture
def store(postgres_url):
    url = psycopg.conninfo.make_conninfo(postgres_url, dbname=f"fb_{uuid.uuid4().hex[:8]}")
    ensure_database(url)
    db = Database(url)
    yield FeedbackStore(db)
    db.close()


@needs_docker
def test_a_new_verdict_replaces_the_old_one(store):
    store.save(CLIENT, Feedback("cream", "LIKED"), None)
    store.save(CLIENT, Feedback("cream", "DISLIKED", Reason.TEXTURE), None)
    assert store.find_all(CLIENT) == [Feedback("cream", "DISLIKED", Reason.TEXTURE)]
    assert store.find_all(uuid.uuid4()) == []


@needs_docker
def test_a_session_is_linked_only_when_it_belongs_to_the_client(store):
    mine = store.save_session(CLIENT, answers(), ["HUMID"], {"AM_CLEANSER": "cleanser"})
    theirs = store.save_session(uuid.uuid4(), answers(), [], {})
    store.save(CLIENT, Feedback("cream", "LIKED"), mine)
    store.save(CLIENT, Feedback("cleanser", "LIKED"), theirs)
    with store.db.transaction() as conn:
        rows = conn.execute("SELECT product_id, session_id FROM product_feedback ORDER BY product_id").fetchall()
        session = conn.execute("SELECT answers, climate_signals, routine FROM quiz_sessions WHERE id = %s",
                               (mine,)).fetchone()
    assert rows == [("cleanser", None), ("cream", mine)]
    assert "pregnancy" not in json.dumps(session[0]) and "Austin" not in json.dumps(session[0])
    assert session[1] == ["HUMID"] and session[2] == {"AM_CLEANSER": "cleanser"}


@needs_docker
def test_undo_removes_the_verdict(store):
    store.save(CLIENT, Feedback("cream", "LIKED"), None)
    store.delete(CLIENT, "cream")
    store.delete(CLIENT, "cream")  # twice is fine
    assert store.find_all(CLIENT) == []


@needs_docker
def test_the_database_rejects_a_like_with_a_reason(store):
    with pytest.raises(psycopg.errors.CheckViolation), store.db.transaction() as conn:
        conn.execute("INSERT INTO product_feedback (client_id, product_id, verdict, reason) "
                     "VALUES (%s, 'x', 'LIKED', 'TEXTURE')", (CLIENT,))


@needs_docker
def test_migrations_run_once_and_are_recorded(store):
    ensure_database(store.db.url)  # again: nothing to do
    with store.db.transaction() as conn:
        versions = [r[0] for r in conn.execute("SELECT version FROM schema_migrations ORDER BY version")]
    assert versions == [1, 2, 3]
