#!/usr/bin/env python3
"""
normalise-bob-output.py
=======================
AUTOMATE-CVE pipeline — Bob output normalisation wrapper.

Reads raw Bob `--output-format json` output from stdin.
Extracts the inner CVE triage JSON from the `response` string field.
Validates required schema fields are present on each record.
Outputs a clean, validated JSON array to stdout.

Usage:
    cat scan.json | bob "$(cat prompts/triage.txt)" \\
        --approval-mode yolo --output-format json --hide-intermediary-output \\
        | python3 scripts/normalise-bob-output.py

Exit codes:
    0 = success, validated JSON array written to stdout
    1 = parse error or schema validation failure (details on stderr)

Schema:
    See docs/spike/unified-cve-triage-schema.json
"""

import json
import re
import sys
from typing import Any

# ── Required fields in each triage record ────────────────────────────────────
REQUIRED_FIELDS = {
    "cve_id",
    "package",
    "version_installed",
    "severity",
    "fix_available",
    "fix_version",
    "fix_type",
    "affected_branches",
    "priority_score",
    "scanner_source",
}

VALID_SEVERITIES   = {"CRITICAL", "HIGH", "MEDIUM", "LOW"}
VALID_FIX_TYPES    = {"java-dep-bump", "os-base-bump", "dockerfile-change", "no-fix-monitor"}
VALID_SOURCES      = {"trivy", "snyk", "both"}
VALID_BRANCHES     = {"3.1_ds", "4.0_ds"}


def die(msg: str) -> None:
    print(f"[normalise-bob-output] ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def warn(msg: str) -> None:
    print(f"[normalise-bob-output] WARN:  {msg}", file=sys.stderr)


def extract_json_from_response(response: str) -> Any:
    """
    Attempt to extract a JSON value from Bob's response string.

    Bob may return:
      1. A raw JSON array/object (starts with [ or {)
      2. JSON embedded in a ```json ... ``` markdown code fence
      3. JSON embedded in a ``` ... ``` plain code fence
      4. Text with a JSON array somewhere inside it

    Returns the parsed Python object (expected: a list of dicts).
    Raises ValueError if no valid JSON is found.
    """
    text = response.strip()

    # Strategy 1: raw JSON at top level
    if text.startswith("[") or text.startswith("{"):
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            pass  # fall through to other strategies

    # Strategy 2: ```json ... ``` fence
    fence_json = re.search(r"```json\s*([\s\S]*?)```", text, re.IGNORECASE)
    if fence_json:
        try:
            return json.loads(fence_json.group(1).strip())
        except json.JSONDecodeError:
            pass

    # Strategy 3: plain ``` ... ``` fence
    fence_plain = re.search(r"```\s*([\[\{][\s\S]*?)```", text)
    if fence_plain:
        try:
            return json.loads(fence_plain.group(1).strip())
        except json.JSONDecodeError:
            pass

    # Strategy 4: find the first [ ... ] block anywhere in the text
    bracket_match = re.search(r"(\[[\s\S]*\])", text)
    if bracket_match:
        try:
            return json.loads(bracket_match.group(1))
        except json.JSONDecodeError:
            pass

    # Strategy 5: find the first { ... } block (single record, wrap in array)
    brace_match = re.search(r"(\{[\s\S]*\})", text)
    if brace_match:
        try:
            record = json.loads(brace_match.group(1))
            warn("response contained a single JSON object rather than an array — wrapped in []")
            return [record]
        except json.JSONDecodeError:
            pass

    raise ValueError(f"Could not extract valid JSON from response string. Preview: {text[:200]!r}")


def validate_record(record: dict, index: int) -> list[str]:
    """Validate a single triage record. Returns list of validation errors."""
    errors = []
    prefix = f"record[{index}]"

    # Required fields present
    for field in REQUIRED_FIELDS:
        if field not in record:
            errors.append(f"{prefix}: missing required field '{field}'")

    if "cve_id" in record:
        if not re.match(r"^CVE-\d{4}-\d+$", str(record["cve_id"])):
            errors.append(f"{prefix}.cve_id: invalid format '{record['cve_id']}' (expected CVE-YYYY-NNNNN)")

    if "severity" in record and record["severity"] not in VALID_SEVERITIES:
        errors.append(f"{prefix}.severity: invalid value '{record['severity']}' (expected one of {VALID_SEVERITIES})")

    if "fix_type" in record and record["fix_type"] not in VALID_FIX_TYPES:
        errors.append(f"{prefix}.fix_type: invalid value '{record['fix_type']}' (expected one of {VALID_FIX_TYPES})")

    if "scanner_source" in record and record["scanner_source"] not in VALID_SOURCES:
        errors.append(f"{prefix}.scanner_source: invalid value '{record['scanner_source']}' (expected one of {VALID_SOURCES})")

    if "affected_branches" in record:
        if not isinstance(record["affected_branches"], list) or len(record["affected_branches"]) == 0:
            errors.append(f"{prefix}.affected_branches: must be a non-empty array")
        else:
            for branch in record["affected_branches"]:
                if branch not in VALID_BRANCHES:
                    errors.append(f"{prefix}.affected_branches: unknown branch '{branch}'")

    if "priority_score" in record:
        ps = record["priority_score"]
        if not isinstance(ps, int) or ps < 1 or ps > 10:
            errors.append(f"{prefix}.priority_score: must be an integer 1–10, got '{ps}'")

    if "fix_available" in record and "fix_version" in record:
        if record["fix_available"] is True and record["fix_version"] is None:
            errors.append(f"{prefix}: fix_available=true but fix_version is null")
        if record["fix_available"] is False and record["fix_version"] not in (None, ""):
            warn(f"{prefix}: fix_available=false but fix_version='{record['fix_version']}' — setting to null")
            record["fix_version"] = None

    return errors


def main() -> None:
    # ── Read Bob's raw --output-format json output from stdin ─────────────────
    raw_input = sys.stdin.read().strip()

    if not raw_input:
        die("stdin is empty — no Bob output received")

    # ── Parse the outer Bob JSON envelope ─────────────────────────────────────
    # Bob may emit tool output text before the JSON block when --hide-intermediary-output
    # is not passed (or for older versions). Extract the JSON block that starts with {.
    outer_json_str = raw_input

    # If there's non-JSON preamble, find the last occurrence of a top-level { to { block
    if not raw_input.startswith("{"):
        json_start = raw_input.rfind("\n{")
        if json_start != -1:
            outer_json_str = raw_input[json_start:].strip()
        else:
            # Try finding any top-level { in the output
            json_start = raw_input.find("{")
            if json_start != -1:
                outer_json_str = raw_input[json_start:]
            else:
                die(f"Could not locate Bob JSON envelope in stdin. Preview: {raw_input[:300]!r}")

    try:
        outer = json.loads(outer_json_str)
    except json.JSONDecodeError as e:
        die(f"Failed to parse Bob JSON envelope: {e}\nInput preview: {outer_json_str[:300]!r}")

    # ── Extract the response field ─────────────────────────────────────────────
    if "error" in outer:
        die(f"Bob returned an error: {outer['error']}")

    if "response" not in outer:
        die(f"Bob JSON envelope missing 'response' field. Keys found: {list(outer.keys())}")

    response_str = outer["response"]

    # ── Log stats for observability ───────────────────────────────────────────
    if "stats" in outer:
        stats = outer["stats"]
        budget = stats.get("budgetSpend", "?")
        cost   = stats.get("sessionCost", "?")
        tools  = stats.get("tools", {}).get("totalCalls", "?")
        print(f"[normalise-bob-output] INFO: coins={budget}, cost_usd={cost}, tool_calls={tools}", file=sys.stderr)

    # ── Extract inner JSON from response string ────────────────────────────────
    try:
        parsed = extract_json_from_response(response_str)
    except ValueError as e:
        die(str(e))

    # Ensure it's a list
    if isinstance(parsed, dict):
        warn("Response JSON is an object, not an array — wrapping in []")
        parsed = [parsed]

    if not isinstance(parsed, list):
        die(f"Expected a JSON array of CVE records, got {type(parsed).__name__}")

    # ── Validate each record ───────────────────────────────────────────────────
    all_errors = []
    for i, record in enumerate(parsed):
        if not isinstance(record, dict):
            all_errors.append(f"record[{i}]: expected an object, got {type(record).__name__}")
            continue
        all_errors.extend(validate_record(record, i))

    if all_errors:
        for err in all_errors:
            print(f"[normalise-bob-output] VALIDATION: {err}", file=sys.stderr)
        die(f"{len(all_errors)} validation error(s) found. See stderr for details.")

    # ── Output clean validated JSON ────────────────────────────────────────────
    print(json.dumps(parsed, indent=2))
    print(f"[normalise-bob-output] INFO: {len(parsed)} CVE record(s) validated and written to stdout", file=sys.stderr)


if __name__ == "__main__":
    main()
