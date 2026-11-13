"""Versioned event schemas (schemas/<event>/vN.json at the repo root, shared with the Java producer).

A new version may only add optional fields (docs/events.md), so an event from a newer producer is valid under the
newest schema this service knows, apart from its version number.
"""

import copy
import json
import re
from functools import lru_cache
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


class InvalidEvent(Exception):
    """The event can never be processed (bad JSON or schema violation): it goes to the dead-letter topic."""


@lru_cache
def load_schemas(schemas_dir: str, event: str) -> dict[int, dict]:
    """Version number -> schema, for every vN.json of the event."""
    found = {}
    for path in Path(schemas_dir, event).glob("v*.json"):
        if match := re.fullmatch(r"v(\d+)\.json", path.name):
            found[int(match.group(1))] = json.loads(path.read_text())
    if not found:
        raise FileNotFoundError(f"no schemas for {event} in {schemas_dir}")
    return found


def validator_for(schemas: dict[int, dict], version: object) -> Draft202012Validator:
    if isinstance(version, int) and version in schemas:
        schema = schemas[version]
    elif isinstance(version, int) and version > max(schemas):
        # A newer, backward-compatible version: check it against the newest schema we know.
        schema = copy.deepcopy(schemas[max(schemas)])
        schema["properties"]["schemaVersion"] = {"type": "integer", "minimum": 1}
    else:
        raise InvalidEvent(f"unknown schemaVersion {version!r}")
    return Draft202012Validator(schema, format_checker=FormatChecker())


def parse(raw: bytes | None, schemas: dict[int, dict]) -> dict:
    """Decodes and validates an event, or raises InvalidEvent."""
    try:
        event = json.loads(raw or b"")
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        raise InvalidEvent(f"not JSON: {e}") from e
    if not isinstance(event, dict):
        raise InvalidEvent("not a JSON object")
    errors = sorted(validator_for(schemas, event.get("schemaVersion")).iter_errors(event), key=lambda e: e.path)
    if errors:
        first = errors[0]
        raise InvalidEvent(f"schema: {'/'.join(map(str, first.path)) or '(root)'}: {first.message}")
    return event


def compatibility_problems(old: dict, new: dict, path: str = "") -> list[str]:
    """Why `new` is not backward compatible with `old`: removed or retyped properties, new required fields."""
    problems = []
    old_props, new_props = old.get("properties", {}), new.get("properties", {})
    for name in old_props:
        if name not in new_props:
            problems.append(f"{path}/{name}: removed")
    for name in set(new.get("required", [])) - set(old.get("required", [])):
        problems.append(f"{path}/{name}: newly required")
    for name, old_prop in old_props.items():
        new_prop = new_props.get(name)
        if new_prop is None:
            continue
        if name != "schemaVersion" and _types(old_prop) - _types(new_prop):
            problems.append(f"{path}/{name}: type narrowed from {sorted(_types(old_prop))} to {sorted(_types(new_prop))}")
        if "enum" in old_prop and "enum" in new_prop and set(map(str, old_prop["enum"])) - set(map(str, new_prop["enum"])):
            problems.append(f"{path}/{name}: enum values removed")
        if old_prop.get("type") == "object" or "properties" in old_prop:
            problems += compatibility_problems(old_prop, new_prop, f"{path}/{name}")
        if old_prop.get("type") == "array" and "items" in old_prop and "items" in new_prop:
            problems += compatibility_problems(old_prop["items"], new_prop["items"], f"{path}/{name}[]")
    return problems


def _types(prop: dict) -> set[str]:
    t = prop.get("type")
    return set(t) if isinstance(t, list) else {t} if t else set()
