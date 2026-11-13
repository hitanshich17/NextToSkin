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

## Consuming (next step)

The recommender consumes in a consumer group with retries and a dead-letter topic
(`catalog.product-updated.dlt`) for events that still fail; details come with that step.
