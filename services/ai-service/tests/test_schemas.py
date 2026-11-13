import copy
import json

import pytest

from app.config import get_settings
from app.events.schemas import InvalidEvent, compatibility_problems, load_schemas, parse
from tests.conftest import encode, make_event

SCHEMAS = load_schemas(get_settings().schemas_dir, "product-updated")


def test_a_valid_event_parses():
    event = make_event()
    assert parse(encode(event), SCHEMAS) == event


@pytest.mark.parametrize("raw, reason", [
    (b"not json", "not JSON"),
    (b"[1, 2]", "not a JSON object"),
    (encode({**make_event(), "eventType": "product-deleted"}), "schema"),
    (encode(make_event(status="GONE")), "schema"),
    (encode({k: v for k, v in make_event().items() if k != "eventId"}), "schema"),
    (encode({**make_event(), "schemaVersion": 0}), "unknown schemaVersion"),
])
def test_invalid_events_are_rejected(raw, reason):
    with pytest.raises(InvalidEvent, match=reason):
        parse(raw, SCHEMAS)


def test_a_newer_compatible_version_is_accepted():
    event = {**make_event(), "schemaVersion": max(SCHEMAS) + 1, "newOptionalField": "ignored"}
    assert parse(encode(event), SCHEMAS)["schemaVersion"] == max(SCHEMAS) + 1


def test_every_published_version_is_backward_compatible_with_the_previous_one():
    """The CI check from docs/events.md: vN+1 may only add optional fields."""
    for event in ("product-updated",):
        schemas = load_schemas(get_settings().schemas_dir, event)
        for version in sorted(schemas)[1:]:
            assert compatibility_problems(schemas[version - 1], schemas[version]) == [], f"{event} v{version}"


V1 = SCHEMAS[1]


def changed(edit) -> dict:
    new = copy.deepcopy(V1)
    edit(new)
    return new


def test_adding_an_optional_field_is_compatible():
    new = changed(lambda s: s["properties"]["product"]["properties"].update({"texture": {"type": "string"}}))
    assert compatibility_problems(V1, new) == []


@pytest.mark.parametrize("edit, problem", [
    (lambda s: s["properties"]["product"]["properties"].pop("spf"), "/product/spf: removed"),
    (lambda s: s["properties"]["product"]["required"].append("spf"), "/product/spf: newly required"),
    (lambda s: s["properties"]["product"]["properties"]["brand"].update({"type": "string"}),
     "/product/brand: type narrowed"),
    (lambda s: s["properties"]["product"]["properties"]["status"].update({"enum": ["ACTIVE"]}),
     "/product/status: enum values removed"),
    (lambda s: s["properties"]["product"]["properties"]["offers"]["items"]["properties"].pop("url"),
     "/product/offers[]/url: removed"),
])
def test_breaking_changes_are_caught(edit, problem):
    problems = compatibility_problems(V1, changed(edit))
    assert any(p.startswith(problem) for p in problems), problems


def test_the_schema_file_is_valid_json_schema():
    from jsonschema import Draft202012Validator

    Draft202012Validator.check_schema(json.loads(json.dumps(V1)))
