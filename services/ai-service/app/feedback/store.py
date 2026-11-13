"""Anonymous "tried it?" feedback and stored quiz sessions (docs/feedback.md), in the recommender's database.

Moved from the Java catalog service: feedback is about recommendations, not the catalog. No accounts: the browser
sends a random client id; nothing stored identifies a person, and stored answers leave out pregnancy and city.
"""

from __future__ import annotations

import json
import uuid
from dataclasses import dataclass

from app.db import Database
from app.recommender.models import QuizAnswers, Reason


@dataclass(frozen=True)
class Feedback:
    product_id: str
    verdict: str          # LIKED or DISLIKED
    reason: Reason | None = None

    def __post_init__(self):
        if self.verdict not in ("LIKED", "DISLIKED"):
            raise ValueError("verdict is required")
        if self.verdict == "LIKED" and self.reason is not None:
            raise ValueError("only a dislike has a reason")

    @property
    def effective_reason(self) -> Reason | None:
        """A dislike without a reason is treated as a reaction: the safest choice."""
        return Reason.IRRITATED if self.verdict == "DISLIKED" and self.reason is None else self.reason


class FeedbackStore:
    def __init__(self, db: Database):
        self.db = db

    def save(self, client_id: uuid.UUID, feedback: Feedback, session_id: int | None) -> None:
        """Saves or replaces the client's verdict. The session is linked only if it belongs to this client."""
        with self.db.transaction() as conn:
            conn.execute(
                """
                INSERT INTO product_feedback (client_id, product_id, verdict, reason, session_id)
                VALUES (%s, %s, %s, %s, (SELECT s.id FROM quiz_sessions s WHERE s.id = %s AND s.client_id = %s))
                ON CONFLICT (client_id, product_id) DO UPDATE SET
                    verdict = EXCLUDED.verdict, reason = EXCLUDED.reason,
                    session_id = EXCLUDED.session_id, updated_at = now()
                """,
                (client_id, feedback.product_id, feedback.verdict,
                 feedback.reason.value if feedback.reason else None, session_id, client_id))

    def delete(self, client_id: uuid.UUID, product_id: str) -> None:
        """Undoes a verdict; deleting one that doesn't exist is fine."""
        with self.db.transaction() as conn:
            conn.execute("DELETE FROM product_feedback WHERE client_id = %s AND product_id = %s",
                         (client_id, product_id))

    def find_all(self, client_id: uuid.UUID) -> list[Feedback]:
        with self.db.transaction() as conn:
            rows = conn.execute("""SELECT product_id, verdict, reason FROM product_feedback
                                   WHERE client_id = %s ORDER BY updated_at, product_id""", (client_id,)).fetchall()
        return [Feedback(p, v, Reason(r) if r else None) for p, v, r in rows]

    def save_session(self, client_id: uuid.UUID, answers: QuizAnswers, climate_signals: list[str],
                     routine: dict[str, str]) -> int:
        """Stores a routine shown to a client (step -> product id) with its answers; returns the session id."""
        with self.db.transaction() as conn:
            return conn.execute(
                """INSERT INTO quiz_sessions (client_id, answers, climate_signals, routine)
                   VALUES (%s, %s, %s, %s) RETURNING id""",
                (client_id, json.dumps(stored_answers(answers)), sorted(climate_signals), json.dumps(routine)),
            ).fetchone()[0]


def stored_answers(a: QuizAnswers) -> dict:
    """The answers without pregnancy (sensitive health data) and city (kept only as climate signals)."""
    return {
        "skinType": a.skin_type.value,
        "concerns": [c.value for c in a.concerns],
        "reactivity": a.reactivity.value,
        "avoid": sorted(x.value for x in a.avoid),
        "activesExperience": a.actives_experience.value,
        "budgetCents": a.budget_cents,
        "skinTone": a.skin_tone,
    }
