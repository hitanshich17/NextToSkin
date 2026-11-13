"""The Python recommender must give exactly the Java engine's results (testdata/routine-parity.json).

The golden file holds the real catalog (as product-updated snapshots) and 500 generated quiz profiles with climates
and feedback; core-api's RoutineParityTest regenerates it and fails if it's out of date.
"""

import json
from pathlib import Path

import pytest

from app.recommender import rules
from app.recommender.models import (
    ROUTINE_CATEGORIES, Climate, Product, QuizAnswers, Reason, Step, TriedProducts,
)

GOLDEN = json.loads((Path(__file__).resolve().parents[3] / "testdata" / "routine-parity.json").read_text())


def catalog() -> list[Product]:
    """What the recommender builds from the read model: active products in routine categories, by id."""
    return sorted((Product.from_snapshot(s) for s in GOLDEN["products"]
                   if s["status"] == "ACTIVE" and s["category"] in ROUTINE_CATEGORIES), key=lambda p: p.id)


CATALOG = catalog()


def test_the_catalog_matches_what_the_java_engine_loads():
    view = {v["id"]: v for v in GOLDEN["catalogView"]}
    assert [p.id for p in CATALOG] == [v["id"] for v in GOLDEN["catalogView"]]
    for p in CATALOG:
        assert dict(sorted(p.first_positions.items())) == view[p.id]["firstPositions"], p.id
        assert p.cheapest_price_cents == view[p.id]["price"], p.id
        assert p.texture.value == view[p.id]["texture"], p.id


def tried(data) -> TriedProducts:
    liked = frozenset(f["productId"] for f in data["feedback"] if f["verdict"] == "LIKED")
    disliked = {f["productId"]: Reason(f["reason"] or "IRRITATED") for f in data["feedback"]
                if f["verdict"] == "DISLIKED"}
    return TriedProducts(liked, disliked, data["similarity"])


def climate(name) -> Climate | None:
    c = GOLDEN["climates"].get(name)
    return None if c is None else Climate(c["city"], c["state"], c["uvIndexMax"], c["dewPointC"], c["humidity"],
                                          c["pm25"])


@pytest.mark.parametrize("index", range(len(GOLDEN["cases"])))
def test_same_plan_as_java(index):
    case = GOLDEN["cases"][index]
    answers = QuizAnswers.from_json(case["answers"])
    feedback, weather = tried(case["tried"]), climate(case["climate"])
    plan = rules.plan(answers, CATALOG, weather, feedback)
    expected = case["expected"]

    assert {s.value: [p.id for p in ps] for s, ps in plan.candidates.items()} == expected["candidates"]
    assert plan.treatment_active == expected["treatmentActive"]
    assert [n.value for n in plan.notes] == expected["notes"]
    assert list(plan.budget_fallbacks) == expected["budgetFallbackOrder"]
    assert {a: [p.id for p in ps] for a, ps in plan.budget_fallbacks.items()} == expected["budgetFallbacks"]
    assert sorted(p.id for p in plan.owned) == expected["owned"]
    assert plan.avoided == expected["avoided"]
    for step_name, reasons in expected["reasons"].items():
        step = Step(step_name)
        assert rules.reasons(answers, weather, feedback, step, plan.candidates[step][0]) == reasons, step_name
