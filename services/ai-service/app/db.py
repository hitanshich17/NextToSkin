"""The recommender's own PostgreSQL database: connection pool and versioned schema migrations.

Migrations are applied in order at startup and recorded in schema_migrations (like Flyway on the Java side).
Never edit a released migration; add a new one.
"""

from __future__ import annotations

from contextlib import contextmanager

import psycopg
from psycopg import sql
from psycopg_pool import ConnectionPool, PoolTimeout

MIGRATIONS: list[tuple[int, str, str]] = [
    (1, "read model", """
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
    """),
    (2, "feedback (docs/feedback.md)", """
        -- Each routine built for a client, with the answers it was built from (without pregnancy and city)
        -- and what was shown. Training data for the ranking model.
        CREATE TABLE quiz_sessions (
            id              BIGSERIAL PRIMARY KEY,
            client_id       UUID NOT NULL,
            answers         JSONB NOT NULL,
            climate_signals TEXT[] NOT NULL DEFAULT '{}',
            routine         JSONB NOT NULL,
            created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
        );
        CREATE INDEX idx_quiz_sessions_client ON quiz_sessions (client_id);

        -- One verdict per client and product; a new verdict replaces the old one. No foreign key to products:
        -- the read model is rebuilt from events and must never lose feedback.
        CREATE TABLE product_feedback (
            client_id  UUID NOT NULL,
            product_id TEXT NOT NULL,
            verdict    TEXT NOT NULL CHECK (verdict IN ('LIKED', 'DISLIKED')),
            reason     TEXT CHECK (reason IN ('IRRITATED', 'DIDNT_WORK', 'TEXTURE', 'TOO_PRICEY')),
            session_id BIGINT REFERENCES quiz_sessions (id) ON DELETE SET NULL,
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            PRIMARY KEY (client_id, product_id),
            CHECK (verdict = 'DISLIKED' OR reason IS NULL)
        );
    """),
]


def ensure_database(url: str) -> None:
    """Creates the database if it doesn't exist yet, then applies any new migrations."""
    name = psycopg.conninfo.conninfo_to_dict(url)["dbname"]
    with psycopg.connect(psycopg.conninfo.make_conninfo(url, dbname="postgres"), autocommit=True) as admin:
        if not admin.execute("SELECT 1 FROM pg_database WHERE datname = %s", (name,)).fetchone():
            admin.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(name)))
    with psycopg.connect(url) as conn, conn.transaction():
        conn.execute("SELECT pg_advisory_xact_lock(hashtext('recommender-migrations'))")  # one instance at a time
        conn.execute("""CREATE TABLE IF NOT EXISTS schema_migrations (
                            version INTEGER PRIMARY KEY, description TEXT NOT NULL,
                            applied_at TIMESTAMPTZ NOT NULL DEFAULT now())""")
        done = {row[0] for row in conn.execute("SELECT version FROM schema_migrations")}
        for version, description, statements in MIGRATIONS:
            if version not in done:
                conn.execute(statements)
                conn.execute("INSERT INTO schema_migrations (version, description) VALUES (%s, %s)",
                             (version, description))


class Database:
    """A connection pool. Thread-safe: the event consumer writes while request threads read."""

    def __init__(self, url: str, max_connections: int = 5):
        self.url = url
        # Opens lazily and replaces broken connections, so a database restart heals by itself.
        self._pool = ConnectionPool(url, min_size=1, max_size=max_connections, open=False, timeout=10,
                                    check=ConnectionPool.check_connection)
        self._pool.open(wait=False)

    def close(self) -> None:
        self._pool.close()

    @contextmanager
    def transaction(self):
        try:
            with self._pool.connection() as conn, conn.transaction():
                yield conn
        except PoolTimeout as e:
            # No connection could be had: the database is unreachable, like an OperationalError.
            raise psycopg.OperationalError(str(e)) from e
