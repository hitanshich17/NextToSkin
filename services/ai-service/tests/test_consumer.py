"""Consumer retry and dead-letter rules, with the read model and Kafka replaced by fakes."""

import psycopg
import pytest

from app.events.consumer import MAX_ATTEMPTS, ProductEventsConsumer
from app.events.schemas import load_schemas
from app.config import get_settings
from tests.conftest import encode, make_event

SCHEMAS = load_schemas(get_settings().schemas_dir, "product-updated")


class FakeMessage:
    def __init__(self, value: bytes, key: bytes = b"gentle-cleanser"):
        self._value, self._key = value, key

    def value(self):
        return self._value

    def key(self):
        return self._key

    def topic(self):
        return "catalog.product-updated"

    def partition(self):
        return 0

    def offset(self):
        return 7


class FakeReadModel:
    def __init__(self, failures: list[Exception] | None = None):
        self.failures = list(failures or [])
        self.applied = []

    def apply(self, event):
        if self.failures:
            raise self.failures.pop(0)
        self.applied.append(event)
        return True


class OfflineConsumer(ProductEventsConsumer):
    """Real handling logic; Kafka is never contacted and dead letters are recorded instead of sent."""

    def __init__(self, read_model):
        self.sleeps = []
        super().__init__("localhost:1", read_model, SCHEMAS, sleep=self.sleeps.append)
        self.dead = []

    def _dead_letter(self, message, reason):
        self.dead.append(reason)
        self.stats["dead_lettered"] += 1


def test_a_valid_event_is_applied():
    model = FakeReadModel()
    consumer = OfflineConsumer(model)
    consumer.handle(FakeMessage(encode(make_event())))
    assert len(model.applied) == 1 and consumer.dead == []


@pytest.mark.parametrize("message, reason", [
    (FakeMessage(b"{oops"), "not JSON"),
    (FakeMessage(encode(make_event(status="GONE"))), "schema"),
    (FakeMessage(encode(make_event()), key=b"another-product"), "does not match"),
])
def test_events_that_can_never_succeed_are_dead_lettered_without_retrying(message, reason):
    consumer = OfflineConsumer(FakeReadModel())
    consumer.handle(message)
    assert len(consumer.dead) == 1 and reason in consumer.dead[0]
    assert consumer.sleeps == []


def test_other_errors_are_retried_then_dead_lettered():
    model = FakeReadModel([ValueError("bad data")] * MAX_ATTEMPTS)
    consumer = OfflineConsumer(model)
    consumer.handle(FakeMessage(encode(make_event())))
    assert consumer.dead and "failed 3 times" in consumer.dead[0]
    assert consumer.sleeps == [0.5, 1.0]


def test_a_transient_error_that_clears_is_not_dead_lettered():
    model = FakeReadModel([ValueError("blip")])
    consumer = OfflineConsumer(model)
    consumer.handle(FakeMessage(encode(make_event())))
    assert len(model.applied) == 1 and consumer.dead == []


def test_a_database_outage_is_waited_out_not_dead_lettered():
    model = FakeReadModel([psycopg.OperationalError("down")] * 10)
    consumer = OfflineConsumer(model)
    consumer.handle(FakeMessage(encode(make_event())))
    assert len(model.applied) == 1 and consumer.dead == []
    assert len(consumer.sleeps) == 10 and max(consumer.sleeps) == 30.0  # capped backoff
