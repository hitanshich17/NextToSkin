"""Shared fixtures. Docker-backed fixtures (real Kafka and PostgreSQL) skip their tests when Docker is unavailable."""

import json
import socket
import uuid
from datetime import datetime, timezone

import pytest

SAMPLE_PRODUCT = {
    "id": "gentle-cleanser",
    "status": "ACTIVE",
    "brand": "Brand",
    "name": "Gentle Cleanser",
    "category": "cleanser",
    "spf": None,
    "imported": False,
    "actives": [],
    "ingredients": [{"name": "Water", "position": 1, "tags": []},
                    {"name": "Ceramide NP", "position": 2, "tags": ["CERAMIDE"]}],
    "offers": [{"retailer": "brand", "priceCents": 1599, "sizeAmount": 236, "sizeUnit": "ml",
                "url": "https://example.com/gentle-cleanser"}],
}


def make_event(**product_changes) -> dict:
    return {
        "schemaVersion": 1,
        "eventId": str(uuid.uuid4()),
        "eventType": "product-updated",
        "occurredAt": datetime.now(timezone.utc).isoformat(),
        "product": {**SAMPLE_PRODUCT, **product_changes},
    }


def encode(event: dict) -> bytes:
    return json.dumps(event).encode()


def _docker_available() -> bool:
    try:
        import docker

        docker.from_env().ping()
        return True
    except Exception:  # noqa: BLE001
        return False


needs_docker = pytest.mark.skipif(not _docker_available(), reason="Docker not available")


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="session")
def kafka_bootstrap():
    """A real Kafka broker, the same image as docker-compose.yml, with the catalog topics (3 partitions)."""
    from confluent_kafka.admin import AdminClient, NewTopic
    from testcontainers.core.container import DockerContainer
    from testcontainers.core.wait_strategies import LogMessageWaitStrategy

    port = _free_port()  # same port inside and outside, so the advertised address works from the host
    container = (
        DockerContainer("apache/kafka:4.4.0")
        .with_bind_ports(port, port)
        .with_env("KAFKA_NODE_ID", "1")
        .with_env("KAFKA_PROCESS_ROLES", "broker,controller")
        .with_env("KAFKA_LISTENERS", f"PLAINTEXT://:{port},CONTROLLER://:9093")
        .with_env("KAFKA_ADVERTISED_LISTENERS", f"PLAINTEXT://localhost:{port}")
        .with_env("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT")
        .with_env("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
        .with_env("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
        .with_env("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
        .with_env("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
        .with_env("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
        .with_env("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
        .waiting_for(LogMessageWaitStrategy("Kafka Server started").with_startup_timeout(90))
    )
    with container:
        bootstrap = f"localhost:{port}"
        admin = AdminClient({"bootstrap.servers": bootstrap})
        futures = admin.create_topics([NewTopic(t, num_partitions=3, replication_factor=1)
                                       for t in ("catalog.product-updated", "catalog.product-updated.dlt")])
        for f in futures.values():
            f.result(30)
        yield bootstrap


@pytest.fixture(scope="session")
def postgres_url():
    """A real PostgreSQL server (same image as docker-compose.yml); tests create their own databases on it."""
    from testcontainers.community.postgres import PostgresContainer

    with PostgresContainer("pgvector/pgvector:pg16", driver=None) as pg:
        yield pg.get_connection_url()
