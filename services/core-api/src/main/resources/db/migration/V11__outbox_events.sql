-- Catalog events (docs/events.md): transactional outbox and the state needed to publish only real changes.

-- Events waiting for the relay to publish them to Kafka, in id order.
CREATE TABLE outbox_events (
    id           BIGSERIAL PRIMARY KEY,
    event_id     UUID NOT NULL UNIQUE,
    topic        TEXT NOT NULL,
    event_key    TEXT NOT NULL,
    payload      JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts     INTEGER NOT NULL DEFAULT 0,
    last_error   TEXT
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;

-- Hash of the last snapshot published for each product; a product only gets an event when it changes.
ALTER TABLE products ADD COLUMN event_hash TEXT;

-- A curated product dropped from catalog/products.csv is marked removed (not deleted: feedback refers to it).
ALTER TABLE products ADD COLUMN removed_at TIMESTAMPTZ;
