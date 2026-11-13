# Catalog events (Kafka)

The catalog service (`core-api`, Spring Boot) owns the product catalog. The recommender (`ai-service`, FastAPI)
keeps its own copy of the products it needs, built only from events. Neither service reads the other's database.

Decisions by the author: outbox pattern, recommender read model built from events, JSON Schema files checked in CI.

## The event

- **Topic:** `catalog.product-updated`, 3 partitions (one per recommender instance later).
- **Key:** the catalog product id (e.g. `cerave-hydrating-facial-cleanser`). Kafka keeps the order of messages with
  the same key, so updates to one product always arrive in order.
- **Value:** JSON, a full snapshot of the product as the recommender needs it ("event-carried state transfer"), so
  the consumer never has to call back. Schema: [`schemas/product-updated/v1.json`](../schemas/product-updated/v1.json).
- **Removed products** are an update with `status: "REMOVED"`, so the same ordering guarantee covers removals.

## Publishing: transactional outbox

```
catalog import ──▶ products, ingredients, offers, tags (PostgreSQL)
                         │
   event sync ───────────┤  one transaction: compare each product's snapshot hash with the last one
                         │  published; for every changed product insert an outbox row + store the new hash
                         ▼
                 outbox_events ──▶ relay (every 0.5 s) ──▶ Kafka ──▶ recommender
```

- **Why an outbox:** writing the event row in the same database transaction as the stored hash means an event is
  never lost or sent twice for the same change, even if the service crashes; the relay retries until Kafka accepts.
- **Snapshot diff:** events are derived from the stored catalog by comparing a hash of each product's snapshot with
  the hash last published. Products whose data didn't change produce no event (deduplication). The sync also runs
  at startup, so a crash between the import and the sync is caught up automatically.
- **Relay:** publishes unpublished rows in id order with an idempotent producer (`acks=all`), marks them published,
  and stops at the first failure so order is kept; a Postgres advisory lock makes sure only one instance relays
  at a time. Delivery is **at least once**: consumers deduplicate by `eventId`.

## Versioning

- Each event carries `schemaVersion`. A new version must be **backward compatible**: only optional fields may be
  added; nothing is removed, renamed or retyped. Consumers ignore fields they don't know.
- Schemas live in `schemas/<event>/vN.json`. Producer tests validate every produced event against the schema;
  consumer tests validate what they accept. A CI check compares each version with the previous one.

## Consuming: the recommender's read model

The FastAPI service (`app/events/`) consumes in a background thread and keeps its own `recommender` database.

- **Consumer group `recommender`:** Kafka shares the 3 partitions among running instances; each event is handled
  by one of them. A new group starts from the beginning of the topic, so a fresh instance rebuilds the read model.
- **At least once, applied exactly once:** offsets are committed only after the event is stored. Each event's
  `eventId` is recorded in the same transaction as the product, so a redelivered event is skipped.
- **Dead-letter topic `catalog.product-updated.dlt`:** events that can never succeed (not JSON, schema violation,
  message key not matching the product id) go there immediately, with headers saying why and where they came from
  (`x-error`, `x-original-topic`, `x-original-partition`, `x-original-offset`, `x-failed-at`). Other errors are
  retried 3 times with backoff (0.5 s, 1 s), then dead-lettered.
- **Database outages are waited out**, with backoff up to 30 s, instead of dead-lettering: the event isn't at
  fault, and the consumer simply doesn't advance until the database is back.
- **Schemas:** each event is validated against `schemas/product-updated/v<schemaVersion>.json`; an event from a newer,
  backward-compatible version is checked against the newest schema the consumer knows.
- **Thread safety:** the read model uses a connection pool, because request threads read while the consumer writes.
- Health: `GET /health` on the AI service shows `catalog_events` (applied, duplicates, dead-lettered, retries,
  owned partitions).

## Tests

- Java (Testcontainers, real Kafka + PostgreSQL): produced events match the schema; dedup, rename, removal,
  per-key order; relay ordering on failure.
- Python (Testcontainers, real Kafka + PostgreSQL): read model built from events, duplicates skipped, bad events
  dead-lettered with headers, offsets committed, 3 partitions shared by a 2-member group; unit tests for the retry
  rules; the schema compatibility check.
