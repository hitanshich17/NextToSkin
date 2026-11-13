"""Catalog events end to end on real Kafka and PostgreSQL (Testcontainers)."""

import threading
import time
import uuid

import psycopg
import pytest
from confluent_kafka import Consumer, Producer

from app.config import get_settings
from confluent_kafka.admin import AdminClient, NewTopic

from app.events.consumer import ProductEventsConsumer
from app.db import Database, ensure_database
from app.events.read_model import ReadModel
from app.events.schemas import load_schemas
from tests.conftest import encode, make_event, needs_docker

pytestmark = needs_docker
SCHEMAS = load_schemas(get_settings().schemas_dir, "product-updated")


def fresh_read_model(postgres_url: str) -> ReadModel:
    url = psycopg.conninfo.make_conninfo(postgres_url, dbname=f"rec_{uuid.uuid4().hex[:8]}")
    ensure_database(url)
    return ReadModel(Database(url))


def new_topic(bootstrap: str) -> str:
    """A fresh topic (3 partitions, like the real one) and its dead-letter topic, so tests don't see each other."""
    topic = f"catalog.product-updated.{uuid.uuid4().hex[:8]}"
    admin = AdminClient({"bootstrap.servers": bootstrap})  # must outlive the futures below
    futures = admin.create_topics(
        [NewTopic(t, num_partitions=3, replication_factor=1) for t in (topic, topic + ".dlt")])
    for f in futures.values():
        f.result(30)
    return topic


def send(bootstrap: str, topic: str, messages: list[tuple[bytes, bytes]]) -> None:
    producer = Producer({"bootstrap.servers": bootstrap})
    for key, value in messages:
        producer.produce(topic, key=key, value=value)
    assert producer.flush(30) == 0


def run_until(consumer: ProductEventsConsumer, done, timeout: float = 60) -> None:
    thread = threading.Thread(target=consumer.run, daemon=True)
    thread.start()
    deadline = time.time() + timeout
    while not done() and time.time() < deadline:
        time.sleep(0.2)
    consumer.stop()
    thread.join(15)
    assert done(), consumer.stats


def dead_letters(bootstrap: str, topic: str, expected: int) -> list:
    reader = Consumer({"bootstrap.servers": bootstrap, "group.id": f"dlt-{uuid.uuid4()}",
                       "auto.offset.reset": "earliest"})
    reader.subscribe([topic + ".dlt"])
    found, deadline = [], time.time() + 30
    while len(found) < expected and time.time() < deadline:
        message = reader.poll(0.5)
        if message is not None and not message.error():
            found.append(message)
    reader.close()
    return found


def test_events_build_the_read_model_and_bad_ones_go_to_the_dead_letter_topic(kafka_bootstrap, postgres_url):
    read_model = fresh_read_model(postgres_url)
    first = make_event()
    renamed = make_event(name="Gentle Cleanser (New Look)")
    removed = make_event(name="Gentle Cleanser (New Look)", status="REMOVED")
    other = make_event(id="rich-cream", category="moisturizer")
    topic = new_topic(kafka_bootstrap)
    send(kafka_bootstrap, topic, [
        (b"gentle-cleanser", encode(first)),
        (b"gentle-cleanser", encode(first)),          # redelivered: applied once
        (b"rich-cream", encode(other)),
        (b"gentle-cleanser", encode(renamed)),
        (b"bad", b"{not json"),                       # dead-lettered
        (b"wrong-key", encode(make_event())),        # key doesn't match the product: dead-lettered
        (b"gentle-cleanser", encode(removed)),
    ])
    group = f"recommender-{uuid.uuid4()}"
    consumer = ProductEventsConsumer(kafka_bootstrap, read_model, SCHEMAS, group=group, topic=topic)
    run_until(consumer, lambda: sum(consumer.stats[k] for k in ("applied", "duplicates", "dead_lettered")) >= 7)

    assert consumer.stats["applied"] == 4 and consumer.stats["duplicates"] == 1
    assert read_model.counts() == {"REMOVED": 1, "ACTIVE": 1}
    assert read_model.product("gentle-cleanser")["name"] == "Gentle Cleanser (New Look)"  # per-key order kept

    letters = dead_letters(kafka_bootstrap, topic, 2)
    assert sorted(m.key() for m in letters) == [b"bad", b"wrong-key"]
    headers = dict(letters[0].headers())
    assert headers["x-original-topic"] == topic.encode() and headers["x-error"]

    # Offsets were committed: the same group starting again has nothing left to do.
    again = ProductEventsConsumer(kafka_bootstrap, read_model, SCHEMAS, group=group, topic=topic)
    thread = threading.Thread(target=again.run, daemon=True)
    thread.start()
    time.sleep(8)
    again.stop()
    thread.join(15)
    assert sum(again.stats.values()) == 0


def test_a_consumer_group_shares_the_partitions(kafka_bootstrap, postgres_url):
    read_model = fresh_read_model(postgres_url)
    events = [make_event(id=f"product-{i}") for i in range(30)]
    topic = new_topic(kafka_bootstrap)
    group = f"recommender-{uuid.uuid4()}"
    consumers = [ProductEventsConsumer(kafka_bootstrap, read_model, SCHEMAS, group=group, topic=topic)
                 for _ in range(2)]
    threads = [threading.Thread(target=c.run, daemon=True) for c in consumers]
    for t in threads:
        t.start()
    # Wait until the group has shared out the 3 partitions between both members, then publish.
    deadline = time.time() + 60
    while not (all(c.partitions for c in consumers) and sum(len(c.partitions) for c in consumers) == 3):
        assert time.time() < deadline, [c.partitions for c in consumers]
        time.sleep(0.2)
    assert sorted(p for c in consumers for p in c.partitions) == [0, 1, 2]
    send(kafka_bootstrap, topic, [(e["product"]["id"].encode(), encode(e)) for e in events])

    def stored() -> int:
        return read_model.count("ACTIVE")

    deadline = time.time() + 60
    while stored() < 30 and time.time() < deadline:
        time.sleep(0.2)
    for c in consumers:
        c.stop()
    for t in threads:
        t.join(15)
    # Every product arrived exactly once in the read model (a redelivery during a rebalance counts as a
    # duplicate and is skipped), and both members of the group did part of the work.
    assert stored() == 30
    assert sum(c.stats["applied"] for c in consumers) == 30
    assert all(c.stats["applied"] > 0 for c in consumers), [c.stats for c in consumers]
