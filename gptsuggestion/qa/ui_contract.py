"""Small dependency-free helpers for the isolated UI preservation tests."""
from __future__ import annotations

import hashlib
import json
import re

# Keep literals intact while ignoring comments/formatting. Braces inside strings
# must not change the method boundaries used by the preservation fingerprint.
def matching(tokens: list[str], start: int, left: str, right: str) -> int:
    depth = 0
    for index in range(start, len(tokens)):
        if tokens[index] == left:
            depth += 1
        elif tokens[index] == right:
            depth -= 1
            if depth == 0:
                return index
    raise AssertionError(f"Unbalanced {left}{right}")


def method_span(tokens: list[str], name: str) -> tuple[int, int]:
    found = []
    for i, value in enumerate(tokens):
        if value != name or i + 1 >= len(tokens) or tokens[i + 1] != "(":
            continue
        end_params = matching(tokens, i + 1, "(", ")")
        if end_params + 1 < len(tokens) and tokens[end_params + 1] == "{":
            start = end_params + 1
            found.append((start, matching(tokens, start, "{", "}")))
    assert len(found) == 1, f"Expected exactly one declaration of {name}, got {len(found)}"
    return found[0]


def grouped_java_tokens(source: str) -> list[str]:
    # Literals/comments must be recognized before Java identifiers.
    token = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/|[A-Za-z_$][\w$]*|\s+|.', re.M)
    return [m[0] for m in token.finditer(source)
            if not (m[0].isspace() or m[0].startswith(("//", "/*")))]


def controller_fingerprint(source: str, policy: dict) -> str:
    for item in policy.get("allowed_imports", []):
        source = source.replace("import " + item + ";", "")
    # Only this version-label resource changes inside refreshGameState; all of
    # that method's state/launch logic stays in the protected fingerprint.
    for current, original in policy.get("presentation_aliases", {}).items():
        source = source.replace(current, original)
    tokens = grouped_java_tokens(source)
    spans = [method_span(tokens, name) for name in policy["ui_methods"]]
    for start, end in sorted(spans, reverse=True):
        tokens[start + 1:end] = ["UI_PRESENTATION_BODY"]
    return hashlib.sha256(json.dumps(tokens, separators=(",", ":")).encode()).hexdigest()
