"""Ingredient vectors and "similar to what you liked" scores with pgvector (docs/feedback.md, Similarity).

Ported from the Java catalog service (ProductVectors, SimilarityRepository); the parity test checks the scores match.
Vectors are built from the read model, so they follow catalog events: every vector depends on how rare each
ingredient is across the whole catalog, so all are rebuilt together shortly after catalog changes arrive.
"""

from __future__ import annotations

import logging
import math
import uuid

from app.db import Database

log = logging.getLogger(__name__)

DIMENSIONS = 1_000_000  # matches the sparsevec(1000000) column


def label_positions(snapshot: dict) -> dict[str, int]:
    """Ingredient name -> first label position; declared OTC actives count as position 1."""
    positions: dict[str, int] = {}
    for i in snapshot["ingredients"]:
        positions[i["name"]] = min(positions.get(i["name"], i["position"]), i["position"])
    for a in snapshot["actives"]:
        positions[a["name"]] = 1
    return positions


def weights(positions: dict[str, int], products_with: dict[str, int], products: int) -> dict[str, float]:
    """TF-IDF style: position weight 1/sqrt(position) x rarity ln(N / products containing it).

    Water and glycerin, in nearly everything, weigh ~0, so products look alike only when they share distinctive
    ingredients. Ingredients in every product get weight 0 and are left out.
    """
    result = {}
    for name, position in positions.items():
        weight = math.log(products / products_with[name]) / math.sqrt(max(position, 1))
        if weight > 0:
            result[name] = weight
    return result


def sparsevec(by_dim: dict[int, float]) -> str:
    """pgvector's text form, e.g. {3:0.500000,17:1.200000}/1000000; indices ascending and starting at 1."""
    for dim in by_dim:
        if not 1 <= dim <= DIMENSIONS:
            raise ValueError(f"dimension {dim} doesn't fit the vector size {DIMENSIONS}")
    return "{" + ",".join(f"{d}:{w:.6f}" for d, w in sorted(by_dim.items())) + "}/" + str(DIMENSIONS)


class Vectors:
    def __init__(self, db: Database):
        self.db = db

    def rebuild_all(self) -> int:
        """Rebuilds every product's vector from the read model; returns how many were written."""
        with self.db.transaction() as conn:
            conn.execute("SELECT pg_advisory_xact_lock(hashtext('recommender-vectors'))")
            snapshots = [row[0] for row in conn.execute("SELECT snapshot FROM products ORDER BY id")]
            labels = {s["id"]: label_positions(s) for s in snapshots}
            labels = {pid: pos for pid, pos in labels.items() if pos}
            products_with: dict[str, int] = {}
            for positions in labels.values():
                for name in positions:
                    products_with[name] = products_with.get(name, 0) + 1
            names = sorted(products_with)
            conn.execute("INSERT INTO ingredient_dims (name) SELECT unnest(%s::text[]) ORDER BY 1 ON CONFLICT DO NOTHING",
                         (names,))
            dims = dict(conn.execute("SELECT name, dim FROM ingredient_dims WHERE name = ANY(%s)", (names,)).fetchall())
            conn.execute("DELETE FROM product_vectors")
            written = 0
            for pid, positions in labels.items():
                w = weights(positions, products_with, len(labels))
                if w:  # a vector of zeros has no direction to compare
                    conn.execute("INSERT INTO product_vectors (product_id, embedding) VALUES (%s, %s::sparsevec)",
                                 (pid, sparsevec({dims[n]: v for n, v in w.items()})))
                    written += 1
        log.info("Rebuilt %d product vectors", written)
        return written

    def scores(self, client_id: uuid.UUID) -> dict[str, float]:
        """Product id -> cosine similarity to the closest liked product minus to the closest disliked one (each 0
        if there are none), so roughly -1 to 1. Empty without feedback."""
        with self.db.transaction() as conn:
            rows = conn.execute("""
                WITH mine AS (
                    SELECT f.verdict, v.embedding
                    FROM product_feedback f JOIN product_vectors v ON v.product_id = f.product_id
                    WHERE f.client_id = %s
                )
                SELECT v.product_id,
                       coalesce(max(1 - (v.embedding <=> m.embedding)) FILTER (WHERE m.verdict = 'LIKED'), 0)
                     - coalesce(max(1 - (v.embedding <=> m.embedding)) FILTER (WHERE m.verdict = 'DISLIKED'), 0)
                FROM product_vectors v CROSS JOIN mine m
                GROUP BY v.product_id
                """, (client_id,)).fetchall()
        return {pid: float(score) for pid, score in rows}
