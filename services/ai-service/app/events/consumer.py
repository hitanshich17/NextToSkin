"""Consumes catalog.product-updated into the read model (docs/events.md).

- Consumer group "recommender": partitions are shared out between instances, and each event is handled by one.
- Offsets are committed only after the event is applied (or dead-lettered), so nothing is lost: at least once,
  made safe by the read model skipping events it already applied.
- Events that can never succeed (not JSON, schema violation, key not matching the product) go straight to the
  dead-letter topic. Other errors are retried with backoff; database outages are retried until the database is
  back, so an outage never floods the dead-letter topic.
"""

import logging
import threading
import time
from datetime import datetime, timezone

import psycopg
from confluent_kafka import Consumer, KafkaError, Message, Producer

from app.events.read_model import ReadModel
from app.events.schemas import InvalidEvent, parse

log = logging.getLogger(__name__)

TOPIC = "catalog.product-updated"
DEAD_LETTER_TOPIC = TOPIC + ".dlt"
GROUP = "recommender"
MAX_ATTEMPTS = 3
BACKOFF_SECONDS = (0.5, 1.0, 2.0)
MAX_OUTAGE_BACKOFF_SECONDS = 30.0


class ProductEventsConsumer:
    def __init__(self, bootstrap_servers: str, read_model: ReadModel, schemas: dict[int, dict],
                 group: str = GROUP, sleep=time.sleep, topic: str = TOPIC, on_catalog_changed=None):
        self._consumer = Consumer({
            "bootstrap.servers": bootstrap_servers,
            "group.id": group,
            "enable.auto.commit": False,      # commit only what was applied
            "auto.offset.reset": "earliest",  # a new group rebuilds the read model from the whole topic
        })
        self._producer = Producer({"bootstrap.servers": bootstrap_servers, "enable.idempotence": True})
        self._read_model = read_model
        self._schemas = schemas
        self._sleep = sleep
        self._stop = threading.Event()
        self._topic = topic
        # Called once the topic goes quiet after products changed (e.g. to rebuild similarity vectors): a catalog
        # import arrives as a burst of events, and the work is done once for the whole burst.
        self._on_catalog_changed = on_catalog_changed
        self._changed = False
        self._dead_letter_topic = topic + ".dlt"
        self.stats = {"applied": 0, "duplicates": 0, "dead_lettered": 0, "retries": 0}
        self.partitions: list[int] = []  # partitions this member of the group currently owns

    def run(self) -> None:
        self._consumer.subscribe([self._topic], on_assign=self._assigned, on_revoke=self._revoked)
        try:
            while not self._stop.is_set():
                self.poll_once()
        finally:
            self._consumer.close()
            self._producer.flush(5)

    def _assigned(self, _consumer, partitions) -> None:
        self.partitions = sorted({*self.partitions, *(p.partition for p in partitions)})
        log.info("Assigned partitions %s", self.partitions)

    def _revoked(self, _consumer, partitions) -> None:
        self.partitions = sorted(set(self.partitions) - {p.partition for p in partitions})

    def stop(self) -> None:
        self._stop.set()

    def poll_once(self, timeout: float = 1.0) -> None:
        message = self._consumer.poll(timeout)
        if message is None:
            if self._changed and self._on_catalog_changed is not None:
                self._changed = False
                try:
                    self._on_catalog_changed()
                except Exception as e:  # noqa: BLE001 - retried after the next change
                    self._changed = True
                    log.warning("After-change work failed: %s", e)
            return
        if message.error():
            if message.error().code() != KafkaError._PARTITION_EOF:
                log.warning("Kafka error: %s", message.error())
            return
        self.handle(message)
        self._consumer.commit(message=message, asynchronous=False)

    def handle(self, message: Message) -> None:
        """Applies one message, retrying as described above; returns when it is applied or dead-lettered."""
        try:
            event = parse(message.value(), self._schemas)
            key = (message.key() or b"").decode()
            if key != event["product"]["id"]:
                raise InvalidEvent(f"key {key!r} does not match product id {event['product']['id']!r}")
        except InvalidEvent as e:
            self._dead_letter(message, str(e))
            return

        attempt, outage = 0, 0
        while True:
            try:
                applied = self._read_model.apply(event)
                self.stats["applied" if applied else "duplicates"] += 1
                self._changed = self._changed or applied
                return
            except psycopg.OperationalError as e:
                # The database is unreachable: not this event's fault. Wait and retry, without giving up.
                outage += 1
                delay = min(MAX_OUTAGE_BACKOFF_SECONDS, 0.5 * 2 ** min(outage, 6))
                log.warning("Read model unavailable (%s); retrying in %.1fs", e, delay)
                self.stats["retries"] += 1
                if self._stop.is_set():
                    raise
                self._sleep(delay)
            except Exception as e:  # noqa: BLE001 - anything else may be this event's fault
                attempt += 1
                if attempt >= MAX_ATTEMPTS:
                    self._dead_letter(message, f"failed {attempt} times: {e!r}")
                    return
                self.stats["retries"] += 1
                self._sleep(BACKOFF_SECONDS[attempt - 1])

    def _dead_letter(self, message: Message, reason: str) -> None:
        log.error("Dead-lettering %s[%d]@%d: %s", message.topic(), message.partition(), message.offset(), reason)
        headers = [
            ("x-error", reason[:1000].encode()),
            ("x-original-topic", message.topic().encode()),
            ("x-original-partition", str(message.partition()).encode()),
            ("x-original-offset", str(message.offset()).encode()),
            ("x-failed-at", datetime.now(timezone.utc).isoformat().encode()),
        ]
        self._producer.produce(self._dead_letter_topic, key=message.key(), value=message.value(), headers=headers)
        if self._producer.flush(10) != 0:
            raise RuntimeError("dead-letter topic did not accept the event")  # not committed: retried later
        self.stats["dead_lettered"] += 1

