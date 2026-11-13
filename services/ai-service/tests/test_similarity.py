"""pgvector similarity: vector weights, and scores that match the Java service's (testdata/routine-parity.json)."""

import json
import uuid
from pathlib import Path

import psycopg
import pytest

from app.db import Database, ensure_database
from app.feedback.store import Feedback, FeedbackStore
from app.similarity.vectors import sparsevec, weights
from tests.conftest import needs_docker

GOLDEN = json.loads((Path(__file__).resolve().parents[3] / "testdata" / "routine-parity.json").read_text())

# 10 products: "water" in all, "glycerin" in 5, "lanolin" in 1.
PRODUCTS_WITH = {"water": 10, "glycerin": 5, "lanolin": 1}


def test_an_ingredient_in_every_product_weighs_nothing():
    assert set(weights({"water": 1, "glycerin": 2}, PRODUCTS_WITH, 10)) == {"glycerin"}


def test_rarer_ingredients_weigh_more():
    w = weights({"glycerin": 4, "lanolin": 4}, PRODUCTS_WITH, 10)
    assert w["lanolin"] > w["glycerin"]


def test_earlier_on_the_label_weighs_more():
    early = weights({"lanolin": 1}, PRODUCTS_WITH, 10)["lanolin"]
    late = weights({"lanolin": 16}, PRODUCTS_WITH, 10)["lanolin"]
    assert late == pytest.approx(early / 4)  # 1 / sqrt(16)


def test_sparsevec_literal():
    assert sparsevec({17: 1.2, 3: 0.5}) == "{3:0.500000,17:1.200000}/1000000"
    with pytest.raises(ValueError):
        sparsevec({2_000_000: 1.0})


@pytest.fixture(scope="module")
def catalog_db(postgres_url):
    """A recommender database holding the real catalog (from the golden file's snapshots) with its vectors."""
    url = psycopg.conninfo.make_conninfo(postgres_url, dbname=f"sim_{uuid.uuid4().hex[:8]}")
    ensure_database(url)
    db = Database(url)
    with db.transaction() as conn:
        for s in GOLDEN["products"]:
            conn.execute("""INSERT INTO products (id, status, category, snapshot, occurred_at, event_id)
                            VALUES (%s, %s, %s, %s, now(), %s)""",
                         (s["id"], s["status"], s["category"], json.dumps(s), uuid.uuid4()))
    from app.similarity.vectors import Vectors

    vectors = Vectors(db)
    yield db, vectors, vectors.rebuild_all()
    db.close()


@needs_docker
def test_every_product_with_ingredients_gets_a_vector(catalog_db):
    _, _, written = catalog_db
    assert written == sum(1 for s in GOLDEN["products"] if s["ingredients"] or s["actives"])


@needs_docker
@pytest.mark.parametrize("index", range(len(GOLDEN["similarityCases"])))
def test_scores_match_the_java_service(catalog_db, index):
    db, vectors, _ = catalog_db
    case = GOLDEN["similarityCases"][index]
    client = uuid.uuid4()
    store = FeedbackStore(db)
    for f in case["feedback"]:
        store.save(client, Feedback(f["productId"], f["verdict"]), None)

    scores = vectors.scores(client)
    assert set(scores) == set(case["scores"])
    for product_id, expected in case["scores"].items():
        assert scores[product_id] == pytest.approx(expected, abs=2e-6), product_id


@needs_docker
def test_rebuilding_twice_keeps_dimensions_stable(catalog_db):
    db, vectors, _ = catalog_db
    with db.transaction() as conn:
        before = dict(conn.execute("SELECT name, dim FROM ingredient_dims").fetchall())
    vectors.rebuild_all()
    with db.transaction() as conn:
        after = dict(conn.execute("SELECT name, dim FROM ingredient_dims").fetchall())
    assert after == before
