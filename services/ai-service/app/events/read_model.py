"""The recommender's own copy of the catalog, built only from product-updated events (docs/events.md).

Stored in its own database, separate from the catalog service's, so neither service reads the other's tables.
"""

import json
from contextlib import contextmanager

import psycopg
from psycopg import sql
from psycopg_pool import ConnectionPool, PoolTimeout

SCHEMA = """
CREATE TABLE IF NOT EXISTS products (
    id          TEXT PRIMARY KEY,
    status      TEXT NOT NULL,
    category    TEXT,
    snapshot    JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    event_id    UUID NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Events already applied: delivery is at least once, so a redelivered event is skipped.
CREATE TABLE IF NOT EXISTS processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
"""


def ensure_database(url: str) -> None:
    """Creates the read-model database if it doesn't exist yet, then its tables."""
    info = psycopg.conninfo.conninfo_to_dict(url)
    name = info["dbname"]
    with psycopg.connect(psycopg.conninfo.make_conninfo(url, dbname="postgres"), autocommit=True) as admin:
        if not admin.execute("SELECT 1 FROM pg_database WHERE datname = %s", (name,)).fetchone():
            admin.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(name)))
    with psycopg.connect(url) as conn:
        conn.execute(SCHEMA)


class ReadModel:
    """Thread-safe: the consumer thread writes while request threads read, each on its own pooled connection."""

    def __init__(self, url: str, max_connections: int = 5):
        self._url = url
        # Opens lazily and replaces broken connections, so a database restart heals by itself.
        self._pool = ConnectionPool(url, min_size=1, max_size=max_connections, open=False, timeout=10,
                                    check=ConnectionPool.check_connection)
        self._pool.open(wait=False)

    def close(self) -> None:
        self._pool.close()

    @contextmanager
    def _transaction(self):
        try:
            with self._pool.connection() as conn, conn.transaction():
                yield conn
        except PoolTimeout as e:
            # No connection could be had: the database is unreachable, like an OperationalError.
            raise psycopg.OperationalError(str(e)) from e

    def apply(self, event: dict) -> bool:
        """Applies a product-updated event; False if it was already applied. One transaction: never half applied."""
        product = event["product"]
        with self._transaction() as conn:
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
        with self._transaction() as conn:
            row = conn.execute("SELECT snapshot FROM products WHERE id = %s", (product_id,)).fetchone()
        return row[0] if row else None

    def count(self, status: str = "ACTIVE") -> int:
        with self._transaction() as conn:
            return conn.execute("SELECT count(*) FROM products WHERE status = %s", (status,)).fetchone()[0]

    def counts(self) -> dict[str, int]:
        with self._transaction() as conn:
            rows = conn.execute("SELECT status, count(*) FROM products GROUP BY status").fetchall()
        return {status: n for status, n in rows}
