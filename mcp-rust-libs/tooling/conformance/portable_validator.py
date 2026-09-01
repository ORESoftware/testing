from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class ValidationError:
    code: str
    path: str
    message: str


def validate(
    instance: Any,
    schema: dict[str, Any],
    root: dict[str, Any] | None = None,
    path: str = "$",
) -> list[ValidationError]:
    """Validate the portable ORE MCP JSON Schema profile.

    This validator is intentionally small and fail-closed. It is a scaffold
    oracle for the shared fixture corpus, not a replacement for each target's
    native validator. Error codes and JSON-Pointer-like paths are stable API.
    """

    root = schema if root is None else root
    if "$ref" in schema:
        target = _resolve_ref(root, schema["$ref"])
        return validate(instance, target, root, path)

    if "oneOf" in schema:
        branch_results = [validate(instance, branch, root, path) for branch in schema["oneOf"]]
        accepted_count = sum(not errors for errors in branch_results)
        if accepted_count == 1:
            return []
        if accepted_count > 1:
            return [
                ValidationError(
                    "one_of",
                    path,
                    f"expected exactly one matching branch, got {accepted_count}",
                )
            ]
        # When no branch matches, surface the closest branch's actionable
        # failures. This preserves the discriminator-level context while
        # avoiding an unhelpful top-level `one_of` for every invalid fixture.
        return _closest_branch_errors(branch_results)

    if "const" in schema and not _json_equal(instance, schema["const"]):
        return [ValidationError("const", path, "value does not equal const")]

    expected = schema.get("type")
    if expected is not None and not _is_type(instance, expected):
        return [ValidationError("type", path, f"expected {expected}")]

    errors: list[ValidationError] = []
    if isinstance(instance, dict):
        required = schema.get("required", [])
        for key in required:
            if key not in instance:
                errors.append(
                    ValidationError(
                        "required",
                        _join_path(path, key),
                        "required property is missing",
                    )
                )
        properties = schema.get("properties", {})
        if schema.get("additionalProperties") is False:
            for key in instance:
                if key not in properties:
                    errors.append(
                        ValidationError(
                            "additional_properties",
                            _join_path(path, key),
                            "unknown property",
                        )
                    )
        for key, child_schema in properties.items():
            if key in instance:
                errors.extend(validate(instance[key], child_schema, root, _join_path(path, key)))

    if isinstance(instance, list):
        minimum = schema.get("minItems")
        maximum = schema.get("maxItems")
        if minimum is not None and len(instance) < minimum:
            errors.append(ValidationError("min_items", path, "too few items"))
        if maximum is not None and len(instance) > maximum:
            errors.append(ValidationError("max_items", path, "too many items"))
        if schema.get("uniqueItems") and _contains_duplicate(instance):
            errors.append(ValidationError("unique_items", path, "items are not unique"))
        item_schema = schema.get("items")
        if isinstance(item_schema, dict):
            for index, item in enumerate(instance):
                errors.extend(validate(item, item_schema, root, _join_path(path, str(index))))

    if isinstance(instance, str):
        minimum = schema.get("minLength")
        maximum = schema.get("maxLength")
        # Python len(str) counts Unicode scalar values/code points, matching
        # JSON Schema's string-length definition for valid JSON text.
        if minimum is not None and len(instance) < minimum:
            errors.append(ValidationError("min_length", path, "string is too short"))
        if maximum is not None and len(instance) > maximum:
            errors.append(ValidationError("max_length", path, "string is too long"))
        pattern = schema.get("pattern")
        if pattern is not None and re.search(pattern, instance) is None:
            errors.append(ValidationError("pattern", path, "string does not match pattern"))

    if isinstance(instance, (int, float)) and not isinstance(instance, bool):
        minimum = schema.get("minimum")
        maximum = schema.get("maximum")
        if minimum is not None and instance < minimum:
            errors.append(ValidationError("minimum", path, "number is below minimum"))
        if maximum is not None and instance > maximum:
            errors.append(ValidationError("maximum", path, "number is above maximum"))

    if "enum" in schema and not any(_json_equal(instance, item) for item in schema["enum"]):
        errors.append(ValidationError("enum", path, "value is not in enum"))
    return errors


def _closest_branch_errors(branch_results: list[list[ValidationError]]) -> list[ValidationError]:
    if not branch_results:
        return [ValidationError("one_of", "$", "oneOf has no branches")]

    def score(errors: list[ValidationError]) -> tuple[int, int, tuple[tuple[str, str], ...]]:
        deepest = max((_path_depth(error.path) for error in errors), default=0)
        signature = tuple((error.path, error.code) for error in errors)
        # Fewer failures means a closer branch. For ties, prefer failures that
        # reached deeper into the instance, then use a deterministic signature.
        return (len(errors), -deepest, signature)

    return list(min(branch_results, key=score))


def _path_depth(path: str) -> int:
    return 0 if path == "$" else path.count("/")


def _join_path(path: str, token: str) -> str:
    escaped = token.replace("~", "~0").replace("/", "~1")
    return f"{path}/{escaped}"


def _contains_duplicate(items: list[Any]) -> bool:
    for index, item in enumerate(items):
        if any(_json_equal(item, previous) for previous in items[:index]):
            return True
    return False


def _json_equal(left: Any, right: Any) -> bool:
    # Python considers True == 1; JSON Schema does not treat booleans as
    # integers. Compare JSON value kinds before recursively comparing values.
    if isinstance(left, bool) or isinstance(right, bool):
        return isinstance(left, bool) and isinstance(right, bool) and left == right
    if left is None or right is None:
        return left is None and right is None
    if isinstance(left, (int, float)) and isinstance(right, (int, float)):
        return left == right
    if type(left) is not type(right):
        return False
    if isinstance(left, dict):
        return left.keys() == right.keys() and all(_json_equal(left[key], right[key]) for key in left)
    if isinstance(left, list):
        return len(left) == len(right) and all(_json_equal(a, b) for a, b in zip(left, right))
    return left == right


def _resolve_ref(root: dict[str, Any], ref: str) -> dict[str, Any]:
    if not ref.startswith("#/"):
        raise ValueError(f"remote or unsupported ref: {ref}")
    value: Any = root
    for raw in ref[2:].split("/"):
        token = raw.replace("~1", "/").replace("~0", "~")
        value = value[token]
    if not isinstance(value, dict):
        raise ValueError(f"ref does not resolve to an object: {ref}")
    return value


def _is_type(value: Any, expected: str | list[str]) -> bool:
    if isinstance(expected, list):
        return any(_is_type(value, item) for item in expected)
    return {
        "object": lambda: isinstance(value, dict),
        "array": lambda: isinstance(value, list),
        "string": lambda: isinstance(value, str),
        "integer": lambda: isinstance(value, int) and not isinstance(value, bool),
        "number": lambda: isinstance(value, (int, float)) and not isinstance(value, bool),
        "boolean": lambda: isinstance(value, bool),
        "null": lambda: value is None,
    }.get(expected, lambda: False)()
