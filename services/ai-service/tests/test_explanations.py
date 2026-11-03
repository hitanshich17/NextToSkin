from fastapi.testclient import TestClient

from app.llm import LlmError
from app.main import app, get_llm

client = TestClient(app)

FACTS = {
    "skinType": "OILY",
    "concerns": ["breakouts", "dark spots and marks"],
    "reactivity": "SOMETIMES",
    "avoid": [],
    "activesExperience": "A_LITTLE",
    "climate": ["dry air"],
    "treatmentActive": "a retinoid",
    "steps": [
        {"time": "AM", "step": "cleanser", "product": "Senka Perfect Whip", "keyIngredients": []},
        {"time": "PM", "step": "treatment", "product": "The INKEY List Starter Retinol Serum",
         "keyIngredients": ["a retinoid"]},
    ],
    "notes": ["Use your retinoid at night only. Start every other night."],
}

GOOD = ("Your night treatment is The INKEY List Starter Retinol Serum, because a retinoid is one of the "
        "best-studied ingredients for breakouts. The light gel cleanser suits oily skin in dry air.")


class FakeLlm:
    def __init__(self, answer=GOOD, error=None):
        self.answer, self.error, self.calls = answer, error, []

    def complete(self, system, user, max_tokens):
        self.calls.append((system, user))
        if self.error:
            raise self.error
        return self.answer


def use(llm):
    app.dependency_overrides[get_llm] = lambda: llm


def teardown_function():
    app.dependency_overrides.clear()


def test_returns_the_llm_explanation():
    llm = FakeLlm()
    use(llm)
    response = client.post("/explanations", json=FACTS)
    assert response.status_code == 200
    assert response.json() == {"text": GOOD}


def test_sends_only_the_facts():
    llm = FakeLlm()
    use(llm)
    client.post("/explanations", json=FACTS)
    system, user = llm.calls[0]
    assert "Every statement must come from the facts" in system
    assert "Senka Perfect Whip" in user
    assert "Reacts to new products: SOMETIMES" in user
    assert '"a retinoid": "one of the best-studied ingredients' in user  # roles come from us, not the LLM
    assert "pregnan" not in user.lower()
    assert "every other night" not in user.lower()  # notes are shown on the page, never sent


def test_no_llm_configured_is_503():
    use(None)
    assert client.post("/explanations", json=FACTS).status_code == 503


def test_llm_error_is_503():
    use(FakeLlm(error=LlmError("APITimeoutError")))
    assert client.post("/explanations", json=FACTS).status_code == 503


def test_medical_claims_are_rejected():
    use(FakeLlm(answer=GOOD + " This retinoid will cure your acne."))
    assert client.post("/explanations", json=FACTS).status_code == 503


def test_links_and_lists_are_rejected():
    use(FakeLlm(answer=GOOD + " See https://example.com for more."))
    assert client.post("/explanations", json=FACTS).status_code == 503
    use(FakeLlm(answer="- " + GOOD))
    assert client.post("/explanations", json=FACTS).status_code == 503


def test_too_short_is_rejected():
    use(FakeLlm(answer="Use it."))
    assert client.post("/explanations", json=FACTS).status_code == 503


def test_bad_facts_are_422():
    use(FakeLlm())
    assert client.post("/explanations", json={"concerns": []}).status_code == 422


def test_restating_a_safety_note_is_rejected():
    use(FakeLlm(answer=GOOD + " Limit sun exposure for about a week after you start."))
    assert client.post("/explanations", json=FACTS).status_code == 503
    use(FakeLlm(answer=GOOD + " A retinoid makes skin sun-sensitive."))
    assert client.post("/explanations", json=FACTS).status_code == 503


def test_calling_skin_sensitive_when_it_rarely_reacts_is_rejected():
    rarely = {**FACTS, "reactivity": "rarely"}
    use(FakeLlm(answer=GOOD + " It respects your sensitive skin."))
    assert client.post("/explanations", json=rarely).status_code == 503
    use(FakeLlm(answer=GOOD))
    assert client.post("/explanations", json=rarely).status_code == 200


def test_more_than_four_sentences_is_rejected():
    use(FakeLlm(answer=GOOD + " One. Two. Three."))
    assert client.post("/explanations", json=FACTS).status_code == 503
