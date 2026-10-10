import json
import re
import uuid
from decimal import Decimal, InvalidOperation
from pathlib import Path


PROTOCOL_VERSION = 2
MAX_TOKEN_BYTES = 4096
MAX_EXPONENT_MAGNITUDE = 1000
DECIMAL_TOKEN = re.compile(r"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
CAPABILITIES = {
    "versions": [PROTOCOL_VERSION],
    "comparisonModes": ["LEGACY_TEXT", "DECIMAL_ABSOLUTE", "SPECIAL_JUDGE"],
    "features": ["relative-sources", "attempt-files", "explicit-limits", "retention"],
}
FIELDS = {
    "protocolVersion", "judgeId", "attemptId", "sourcePaths", "testCases", "comparison",
    "caseTimeLimitMillis", "maxTaskRuntimeMillis", "memoryLimitBytes", "maxOutputBytesPerCase",
    "stopOnFirstNonAc", "retention",
}


class ManifestError(Exception):
    pass


def validate_token(value: str) -> None:
    if len(value) > MAX_TOKEN_BYTES or len(value.encode("utf-8")) > MAX_TOKEN_BYTES:
        raise ValueError("comparison token exceeds 4096 UTF-8 bytes")
    if not DECIMAL_TOKEN.fullmatch(value):
        return
    marker = max(value.find("e"), value.find("E"))
    if marker < 0:
        return
    magnitude = 0
    for digit in value[marker + 1:].lstrip("+-"):
        magnitude = magnitude * 10 + ord(digit) - ord("0")
        if magnitude > MAX_EXPONENT_MAGNITUDE:
            raise ValueError("comparison exponent exceeds 1000")


def parse_decimal(value: str) -> Decimal:
    if not isinstance(value, str):
        raise ValueError("precision must be a finite decimal string")
    validate_token(value)
    if not DECIMAL_TOKEN.fullmatch(value):
        raise ValueError("precision must be a finite decimal string")
    return Decimal(value)


def require_fields(value, fields, name):
    if not isinstance(value, dict) or set(value) != fields:
        raise ManifestError(name + " has missing or unknown fields")


def unique_fields(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ManifestError("duplicate manifest field")
        result[key] = value
    return result


def positive_integer(value, name, maximum=2**63 - 1):
    if type(value) is not int or not 0 < value <= maximum:
        raise ManifestError(name + " must be a positive integer in protocol range")


def relative_path(value):
    if not isinstance(value, str) or not value or any(ord(character) < 32 for character in value):
        raise ManifestError("source path must be portable and relative")
    if any(character in value for character in '\\:<>"|?*'):
        raise ManifestError("source path must be portable and relative")
    for component in value.split("/"):
        if component in ("", ".", "..") or component.endswith((".", " ")) or re.fullmatch(
                r"(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?", component):
            raise ManifestError("source path contains an unsafe component")
    if value.split("/")[0].lower() in ("control", "workspace", "artifacts"):
        raise ManifestError("source path cannot name runner control or output files")
    return value


def no_links(root, relative):
    current = root
    for component in Path(relative).parts:
        current = current / component
        if current.is_symlink() or (current.exists() and current.resolve() != current):
            raise ManifestError("runner path must not traverse links")
    return current


def directory_or_absent(root, relative):
    candidate = no_links(root, relative)
    if candidate.exists() and not candidate.is_dir():
        raise ManifestError("runner parent must be a directory")
    return candidate


def validate_manifest(spec):
    require_fields(spec, FIELDS, "manifest")
    if type(spec["protocolVersion"]) is not int or spec["protocolVersion"] != PROTOCOL_VERSION:
        raise ManifestError("unsupported runner protocolVersion")
    if not isinstance(spec["judgeId"], str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", spec["judgeId"]):
        raise ManifestError("judgeId must be a task identifier")
    try:
        if str(uuid.UUID(spec["attemptId"])) != spec["attemptId"]:
            raise ValueError()
    except (ValueError, TypeError, AttributeError):
        raise ManifestError("attemptId must be a canonical UUID") from None
    positive_integer(spec["testCases"], "testCases", 2**31 - 1)
    for field in ("caseTimeLimitMillis", "maxTaskRuntimeMillis", "memoryLimitBytes", "maxOutputBytesPerCase"):
        positive_integer(spec[field], field)
    if type(spec["stopOnFirstNonAc"]) is not bool:
        raise ManifestError("stopOnFirstNonAc must be boolean")
    require_fields(spec["retention"], {"completedMillis", "failedMillis", "cancelledMillis"}, "retention")
    for value in spec["retention"].values():
        positive_integer(value, "retention")
    comparison = spec["comparison"]
    require_fields(comparison, {"mode", "precision"}, "comparison")
    precision = comparison["precision"]
    try:
        value = parse_decimal(precision)
        if not value.is_finite() or value < 0:
            raise InvalidOperation()
    except (InvalidOperation, ValueError, UnicodeError):
        raise ManifestError("precision must be finite and nonnegative") from None
    if comparison["mode"] not in CAPABILITIES["comparisonModes"] or (comparison["mode"] != "DECIMAL_ABSOLUTE" and value != 0):
        raise ManifestError("runner does not support the requested comparison or precision")
    sources = spec["sourcePaths"]
    expected_roles = {"GENERATOR", "USER", "SPECIAL_JUDGE" if comparison["mode"] == "SPECIAL_JUDGE" else "ORACLE"}
    require_fields(sources, expected_roles, "sourcePaths")
    for source in sources.values():
        relative_path(source)
    if len({source.lower() for source in sources.values()}) != len(sources):
        raise ManifestError("source roles must use distinct files")


def load_manifest(spec_path, work_root):
    root = work_root.absolute()
    if not root.is_dir() or root.is_symlink() or root.resolve() != root:
        raise ManifestError("execution root must be an existing non-linked directory")
    path = spec_path.absolute()
    if not path.is_relative_to(root):
        raise ManifestError("manifest must stay inside execution root")
    no_links(root, path.relative_to(root))
    with path.open("r", encoding="utf-8") as file:
        spec = json.load(file, object_pairs_hook=unique_fields)
    validate_manifest(spec)
    attempt_dir = root / "control" / "attempts" / spec["attemptId"]
    if path != attempt_dir / "task-spec.json":
        raise ManifestError("manifest location does not match attempt identity")
    for source in spec["sourcePaths"].values():
        if not no_links(root, source).is_file():
            raise ManifestError("source path must be an existing task file")
    directory_or_absent(root, "workspace")
    execution_dir = no_links(root, "workspace/" + spec["attemptId"])
    if execution_dir.exists():
        raise ManifestError("runner attempt workspace must be new")
    for filename in ("runner-events.jsonl", "runner-summary.json", "runner-summary.json.tmp"):
        candidate = no_links(root, (attempt_dir / filename).relative_to(root))
        if candidate.exists():
            raise ManifestError("runner attempt output must be new")
    no_links(root, (attempt_dir / "cancel.requested").relative_to(root))
    for child in root.iterdir():
        if re.fullmatch(r"[0-9]+\.(?:in|out|ans)", child.name):
            no_links(root, child.name)
            if not child.is_file():
                raise ManifestError("case artifact must be a regular file")
    return spec, root, attempt_dir, execution_dir
