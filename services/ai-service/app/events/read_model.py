"""The recommender's own copy of the catalog, built only from product-updated events (docs/events.md).

Stored in the recommender's database (app/db.py), separate from the catalog service's, so neither service reads the
other's tables.
"""

import json

from app.db import Database


class ReadModel:
    def __init__(self, db: Database):
        self.db = db

    def apply(self, event: dict) -> bool:
        """Applies a product-updated event; False if it was already applied. One transaction: never half applied."""
        product = event["product"]
        with self.db.transaction() as conn:
            inserted = conn.execute(
                "INSERT INTO processed_events (event_id) VALUES (%s) ON CONFLICT DO NOTHING", (event["eventId"],)
            ).rowcount
            if not inserted:
                return False
            # Per-key ordering already keeps events in order; the timestamp check also guards against a replay
            # of an older event after a newer one.
            conn.execute(
                """
                INSERT INTO products (id, status, category, snapshot, occurred_at, event_id)
                VALUES (%s, %s, %s, %s, %s, %s)
                ON CONFLICT (id) DO UPDATE SET
                    status = EXCLUDED.status, category = EXCLUDED.category, snapshot = EXCLUDED.snapshot,
                    occurred_at = EXCLUDED.occurred_at, event_id = EXCLUDED.event_id, updated_at = now()
                WHERE products.occurred_at <= EXCLUDED.occurred_at
                """,
                (product["id"], product["status"], product.get("category"), json.dumps(product),
                 event["occurredAt"], event["eventId"]),
            )
            return True

    def product(self, product_id: str) -> dict | None:
        with self.db.transaction() as conn:
            row = conn.execute("SELECT snapshot FROM products WHERE id = %s", (product_id,)).fetchone()
        return row[0] if row else None

    def exists(self, product_id: str) -> bool:
        with self.db.transaction() as conn:
            return conn.execute("SELECT 1 FROM products WHERE id = %s", (product_id,)).fetchone() is not None

    def snapshots(self) -> list[dict]:
        """Every product snapshot, removed ones included."""
        with self.db.transaction() as conn:
            return [row[0] for row in conn.execute("SELECT snapshot FROM products ORDER BY id")]

    def count(self, status: str = "ACTIVE") -> int:
        with self.db.transaction() as conn:
            return conn.execute("SELECT count(*) FROM products WHERE status = %s", (status,)).fetchone()[0]

    def counts(self) -> dict[str, int]:
        with self.db.transaction() as conn:
            rows = conn.execute("SELECT status, count(*) FROM products GROUP BY status").fetchall()
        return {status: n for status, n in rows}
