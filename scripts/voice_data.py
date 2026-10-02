"""Shared schema loading and validation for the voice assistant tooling.

Both `gen_training_data.py` and `eval_harness.py` depend on this module so that
the rules about what a *valid* tool call is are defined exactly once. If the
generator validated one way and the scorer another, a corrupt training set could
pass generation and then fail evaluation.

Everything here is stdlib-only and reads `tools.json` as the single source of
truth. No tool name, parameter name or enum value is hardcoded in the Python:
if the Kotlin schema changes, the data follows on the next run.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterator

REPO_ROOT = Path(__file__).resolve().parent.parent
TOOLS_PATH = REPO_ROOT / "tools.json"

# Slots whose value is free prose the assistant itself writes. Two records may
# legitimately differ here (a paraphrase of the same question is not a
# contradiction), whereas differing values in a structured slot are a real
# labelling conflict. Used to decide whether a repeated utterance is a genuine
# contradiction or harmless paraphrase variety.
PROSE_SLOTS = frozenset({"question", "reason"})

# `confirm` belongs to the executor, never to the model. tools.json says it is
# "stripped from the model prompt", so emitting it would train the model to
# invent an execution policy it has no business choosing.
FORBIDDEN_PARAM_KEYS = frozenset({"confirm"})

# Slots that must contain raw spoken strings. The resolver turns these into real
# entities. A model that emits "+919876543210" into contact_ref has learned the
# one thing this architecture exists to prevent.
RAW_STRING_SLOTS = frozenset(
    {"contact_ref", "query", "app_name", "alias", "body", "name", "from_ref"}
)

# Anything that looks like a phone number or a raw Android identifier landing in
# a spoken slot is a training-data bug, not just an unusual example.
_PHONE_LIKE = re.compile(r"(?<![\w.])\+?\d[\d\s().-]{6,}\d(?![\w])")
_PACKAGE_LIKE = re.compile(r"\bcom\.[a-z0-9_.]+", re.IGNORECASE)


class ValidationError(ValueError):
    """Raised when an example violates the schema or a safety invariant."""


@dataclass(frozen=True)
class ParamSpec:
    name: str
    type: str
    enum: tuple[Any, ...] | None
    minimum: int | None
    maximum: int | None
    default: Any
    required: bool
    # A required slot is usually non-empty ("ask rather than guess"), but
    # `play_music.query` documents "Empty string means resume/liked songs", so
    # empty is a real value there. Detected from the schema's own description so
    # the rule stays in tools.json instead of a hand-maintained list here.
    allows_empty: bool

    def check(self, value: Any) -> None:
        where = f"param {self.name!r}"

        if value is None:
            raise ValidationError(f"{where} must not be null")

        expected = self.type
        if expected == "string":
            if not isinstance(value, str):
                raise ValidationError(f"{where} must be a string, got {type(value).__name__}")
        elif expected == "integer":
            # bool is an int subclass in Python; a boolean here is always a bug.
            if isinstance(value, bool) or not isinstance(value, int):
                raise ValidationError(f"{where} must be an integer, got {type(value).__name__}")
        elif expected == "boolean":
            if not isinstance(value, bool):
                raise ValidationError(f"{where} must be a boolean, got {type(value).__name__}")
        elif expected == "number":
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise ValidationError(f"{where} must be a number, got {type(value).__name__}")

        if self.enum is not None and value not in self.enum:
            raise ValidationError(f"{where} value {value!r} is not one of {list(self.enum)}")

        if isinstance(value, int) and not isinstance(value, bool):
            if self.minimum is not None and value < self.minimum:
                raise ValidationError(f"{where} value {value} is below minimum {self.minimum}")
            if self.maximum is not None and value > self.maximum:
                raise ValidationError(f"{where} value {value} is above maximum {self.maximum}")

        if self.name in RAW_STRING_SLOTS and isinstance(value, str):
            if _PHONE_LIKE.search(value):
                raise ValidationError(
                    f"{where} looks like a phone number ({value!r}); slots must hold the "
                    "raw spoken string and let the resolver resolve it"
                )
            if _PACKAGE_LIKE.search(value):
                raise ValidationError(
                    f"{where} looks like a package name ({value!r}); the model must not "
                    "emit identifiers"
                )


@dataclass(frozen=True)
class Tool:
    name: str
    group: str
    confirm: str
    description: str
    params: dict[str, ParamSpec]
    required: tuple[str, ...]

    def validate_arguments(self, arguments: Any, *, require_complete: bool = True) -> None:
        if not isinstance(arguments, dict):
            raise ValidationError(f"{self.name}: arguments must be an object")

        for key in arguments:
            if key in FORBIDDEN_PARAM_KEYS:
                raise ValidationError(
                    f"{self.name}: model must not emit {key!r}; it is an executor concern"
                )
            if key not in self.params:
                raise ValidationError(f"{self.name}: unknown parameter {key!r}")

        for key, value in arguments.items():
            self.params[key].check(value)

        if require_complete:
            missing = [p for p in self.required if p not in arguments]
            if missing:
                raise ValidationError(
                    f"{self.name}: missing required parameter(s) {missing}; a call that "
                    "cannot be completed must be expressed as ask_clarification instead"
                )

        for key in self.required:
            spec = self.params[key]
            if key not in arguments:
                continue
            value = arguments[key]
            if value is None:
                raise ValidationError(
                    f"{self.name}: required parameter {key!r} is null; ask instead of guessing"
                )
            if value == "" and not spec.allows_empty:
                raise ValidationError(
                    f"{self.name}: required parameter {key!r} is empty; ask instead of guessing"
                )
            # A one-character free-text value is almost always a generation bug
            # (e.g. choosing an element out of a bare string instead of a list),
            # and schema validation cannot see it: "f" is a perfectly valid string.
            if isinstance(value, str) and not spec.enum and len(value.strip()) < 2 and not spec.allows_empty:
                raise ValidationError(
                    f"{self.name}: required parameter {key!r} is {value!r}, which looks like "
                    "a truncated value rather than a real slot; check the template bank"
                )


@dataclass
class ToolCatalog:
    tools: dict[str, Tool]
    version: int = 0
    notes: list[str] = field(default_factory=list)

    def __getitem__(self, name: str) -> Tool:
        try:
            return self.tools[name]
        except KeyError:
            raise ValidationError(f"unknown tool {name!r}") from None

    def __contains__(self, name: object) -> bool:
        return name in self.tools

    def in_group(self, group: str) -> list[Tool]:
        return [t for t in self.tools.values() if t.group == group]

    @property
    def names(self) -> list[str]:
        return sorted(self.tools)

    def validate_arguments(self, name: str, arguments: Any, **kw: Any) -> None:
        self[name].validate_arguments(arguments, **kw)


def load_catalog(path: Path | str = TOOLS_PATH) -> ToolCatalog:
    """Loads and structurally validates `tools.json`."""
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    tools: dict[str, Tool] = {}

    for raw in data["tools"]:
        params_schema = raw.get("parameters", {})
        properties = params_schema.get("properties", {})
        required = tuple(params_schema.get("required", []))

        params: dict[str, ParamSpec] = {}
        for pname, pspec in properties.items():
            description = (pspec.get("description") or "").lower()
            params[pname] = ParamSpec(
                name=pname,
                type=pspec.get("type", "string"),
                enum=tuple(pspec["enum"]) if "enum" in pspec else None,
                minimum=pspec.get("minimum"),
                maximum=pspec.get("maximum"),
                default=pspec.get("default"),
                required=pname in required,
                allows_empty="empty string" in description,
            )

        for pname in required:
            if pname not in params:
                raise ValidationError(f"{raw['name']}: required parameter {pname!r} is undefined")

        tools[raw["name"]] = Tool(
            name=raw["name"],
            group=raw["group"],
            confirm=raw["confirm"],
            description=raw["description"],
            params=params,
            required=required,
        )

    catalog = ToolCatalog(tools=tools, version=int(data.get("version", 0)), notes=list(data.get("notes", [])))
    _check_catalog_invariants(catalog)
    return catalog


def _check_catalog_invariants(catalog: ToolCatalog) -> None:
    """Guards the assumptions the generator relies on."""
    if "ask_clarification" not in catalog or "unsupported" not in catalog:
        raise ValidationError(
            "catalog must define both 'ask_clarification' and 'unsupported'; without them "
            "there is no way to train the model to admit it does not know something"
        )
    for tool in catalog.tools.values():
        if tool.confirm not in ("never", "ambiguous", "always"):
            raise ValidationError(f"{tool.name}: unknown confirm policy {tool.confirm!r}")


# --------------------------------------------------------------------------
# JSONL / example helpers
# --------------------------------------------------------------------------

# Tag vocabulary. `fast` marks utterances the deterministic path already owns;
# the model should never be trained to answer those, so the default generation
# filters them out. They are kept in the record because the eval harness needs
# them to prove routing is correct.
TAG_FASTPATH = "fast"
TAG_LLM = "llm"
TAG_AMBIGUOUS = "ambiguous"
TAG_OUT_OF_DOMAIN = "out_of_domain"
TAG_MISSING_SLOT = "missing_slot"
TAG_OUTSIDE_ENUM = "outside_enum"
TAG_NEGATION = "negation"


def make_example(
    catalog: ToolCatalog,
    utterance: str,
    tool_name: str,
    arguments: dict[str, Any],
    *,
    tags: list[str],
    split: str,
    expect_clarification: bool = False,
    notes: str | None = None,
) -> dict[str, Any]:
    """Builds one validated training/eval record.

    Raises `ValidationError` rather than emitting a bad example, so a broken
    template bank fails loudly at generation time instead of quietly poisoning a
    fine-tuning set.
    """
    if not utterance or not utterance.strip():
        raise ValidationError("utterance must not be empty")

    tool = catalog[tool_name]
    # `ask_clarification` exists precisely for incomplete calls, so its own
    # arguments are checked without the completeness rule.
    require_complete = not (expect_clarification and tool_name == "ask_clarification")
    tool.validate_arguments(arguments, require_complete=require_complete)

    if expect_clarification and tool_name != "ask_clarification":
        raise ValidationError(
            "expect_clarification is only meaningful for tool 'ask_clarification'"
        )

    record = {
        "id": None,  # assigned by the writer, which needs a stable counter
        "split": split,
        "utterance": utterance,
        "messages": [
            {"role": "user", "content": utterance},
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [
                    {"function": {"name": tool_name, "arguments": arguments}}
                ],
            },
        ],
        "tags": tags,
    }
    if notes:
        record["notes"] = notes
    return record


def assign_ids(records: list[dict[str, Any]], prefix: str = "va") -> list[dict[str, Any]]:
    for i, record in enumerate(records, start=1):
        record["id"] = f"{prefix}-{i:06d}"
    return records


def write_jsonl(path: Path | str, records: list[dict[str, Any]]) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        for record in records:
            fh.write(json.dumps(record, ensure_ascii=False, sort_keys=False) + "\n")


def read_jsonl(path: Path | str) -> Iterator[dict[str, Any]]:
    with Path(path).open(encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValidationError(f"{path}:{lineno}: invalid JSON: {exc}") from exc


def expected_call(record: dict[str, Any]) -> tuple[str | None, dict[str, Any] | None]:
    """Extracts the (tool, arguments) a record expects the model to produce."""
    for message in record.get("messages", []):
        if message.get("role") != "assistant":
            continue
        for call in message.get("tool_calls") or []:
            fn = call["function"]
            return fn["name"], fn.get("arguments") or {}
    return None, None