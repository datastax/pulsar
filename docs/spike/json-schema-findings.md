# Sub-Task 3 Findings: Bob `--output-format json` Schema Stability

**Spike:** AUTOMATE-CVE-000  
**Risk addressed:** A5 — "Is `--output-format json` output structured enough for the downstream triage pipeline to parse reliably?"  
**Status:** ✅ RESOLVED — normalisation pattern defined; 5 prompt variants designed and assessed

---

## 1. `--output-format json` Output Structure

When Bob is invoked with `--output-format json`, the raw stdout is a JSON object with this shape:

```json
{
  "response": "<final answer text from attempt_completion>",
  "stats": {
    "models": {
      "premium": {
        "api": { "totalRequests": 2, "totalErrors": 0, "totalLatencyMs": 7954 },
        "tokens": { "prompt": 16488, "candidates": 197, "total": 16685, "cached": 7946 }
      }
    },
    "tools": {
      "totalCalls": 2, "totalSuccess": 2, "totalFail": 0,
      "byName": { "read_file": { "count": 1, "success": 1 } }
    },
    "budgetSpend": 38.41,
    "maxBudget": 500,
    "sessionCost": 0.03337
  }
}
```

**Key finding**: The `response` field is always a **plain text string** — it is _not_ a parsed JSON object.

Even when the prompt asks for JSON output, Bob returns a string that _contains_ JSON.
The string may take one of these forms:

| Form | Example |
|------|---------|
| Raw JSON array | `[{"cve_id":"CVE-2026-54512", ...}]` |
| JSON in a markdown code fence | ` ```json\n[\n  {...}\n]\n``` ` |
| JSON in a plain code fence | ` ```\n[\n  {...}\n]\n``` ` |
| JSON embedded after prose | `Here are the results:\n[...]` |

All four forms are handled by `scripts/normalise-bob-output.py`.

---

## 2. Five Prompt Variants — Design and Assessment

### v1 (`prompts/triage-v1.txt`) — Strict JSON-only with inline schema

**Approach**: Provides the complete output schema as a JSON template. Instructs Bob to output only the JSON array — no markdown, no explanation, no preamble.

**Expected behaviour**: Most likely to produce raw JSON output (form 1 above) since the instruction explicitly forbids everything else.

**Risk**: If Bob adds a sentence before the JSON (e.g. "Here is the triage result:"), the normaliser must handle the preamble text — Strategy 4 in `normalise-bob-output.py` does this.

**Recommendation**: ✅ **Best choice for production** — combine with v2's few-shot example for highest reliability.

---

### v2 (`prompts/triage-v2.txt`) — Schema table + few-shot example

**Approach**: Presents the schema as a markdown table alongside a concrete input/output example. Avoids repeating the full JSON schema inline.

**Expected behaviour**: The few-shot example strongly biases Bob toward the correct field names and structure. Produces consistent field naming even without explicit schema instructions.

**Risk**: The table format may be slightly more token-expensive than v1.

**Recommendation**: ✅ **Best choice for schema consistency** — the few-shot example is the most reliable mechanism for getting consistent field names.

---

### v3 (`prompts/triage-v3.txt`) — Chain-of-thought then JSON

**Approach**: Asks Bob to reason through each CVE step by step, then emit the final JSON between `===JSON_OUTPUT_BEGIN===` / `===JSON_OUTPUT_END===` delimiters.

**Expected behaviour**: Higher reasoning quality for ambiguous CVEs (e.g. deciding between `os-base-bump` and `dockerfile-change`). The delimiters make extraction deterministic.

**Risk**: More coins consumed due to intermediate reasoning steps. Output is longer.

**Recommendation**: ✅ **Best choice for accuracy** on ambiguous CVEs — use for the initial triage pass, then optimise to v1 once classification rules are stable.

---

### v4 (`prompts/triage-v4.txt`) — Minimal prompt, rely on output-format flag

**Approach**: Very short prompt. Assumes `--output-format json` alone is sufficient to produce structured output.

**Expected behaviour**: Bob may produce inconsistent field names across runs since it has to invent the schema itself. The output might also be a single object rather than an array.

**Risk**: ❌ High schema instability. Not recommended for production.

**Recommendation**: ❌ **Research/calibration only** — useful for measuring baseline coin cost.

---

### v5 (`prompts/triage-v5.txt`) — Table reasoning then JSON in code fence

**Approach**: Two-step: first produce a classification table, then emit JSON in a ` ```json ` code fence.

**Expected behaviour**: The explicit ` ```json ` instruction means the output will consistently be in form 2 (JSON in markdown code fence), which is easy for the normaliser to extract.

**Risk**: Slightly more verbose than v1/v2 due to the table step.

**Recommendation**: ✅ **Good for human review** of Bob's reasoning — useful during the spike validation phase.

---

## 3. Prompt Comparison Summary

| Variant | Schema Consistency | Extraction Reliability | Token Cost | Recommended For |
|---------|-------------------|----------------------|------------|-----------------|
| v1      | High              | High (raw JSON)       | Low        | Production       |
| v2      | Highest           | High (raw JSON)       | Medium     | Production (preferred) |
| v3      | High              | Highest (delimiters)  | High       | Ambiguous CVEs   |
| v4      | Low               | Medium                | Lowest     | Cost calibration |
| v5      | High              | High (code fence)     | Medium     | Spike / review   |

**Canonical prompt**: `prompts/triage.txt` combines the strict JSON-only instruction of v1 with the few-shot example of v2. It is the recommended prompt for AUTOMATE-CVE-003.

---

## 4. Normalisation Wrapper: `scripts/normalise-bob-output.py`

The wrapper handles the two-layer parsing problem:

1. **Outer layer**: Parse the Bob `--output-format json` envelope (`response`, `stats`)
2. **Inner layer**: Extract the CVE triage JSON from the `response` string

Extraction strategies (tried in order):
1. Raw JSON at top level (`[` or `{` prefix)
2. ` ```json ` markdown code fence
3. Plain ` ``` ` code fence with JSON content
4. First `[...]` bracket block anywhere in the text
5. First `{...}` brace block (single record — wrapped in array)

After extraction, each record is validated against the unified schema:
- All required fields present
- `cve_id` matches `CVE-YYYY-NNNNN` format
- `severity`, `fix_type`, `scanner_source` are valid enum values
- `affected_branches` is a non-empty array of known branch names
- `priority_score` is an integer 1–10
- `fix_available`/`fix_version` consistency

**Exit code 0** = clean validated JSON array on stdout.  
**Exit code 1** = validation errors on stderr (pipeline stops; no bad data passed downstream).

---

## 5. Unified CVE Triage Schema

See [`docs/spike/unified-cve-triage-schema.json`](unified-cve-triage-schema.json) for the full JSON Schema document.

**Required fields per record:**

| Field              | Type              | Notes |
|--------------------|-------------------|-------|
| `cve_id`           | string            | `CVE-YYYY-NNNNN` format |
| `package`          | string            | `groupId:artifactId` for Maven; plain name for OS/Python |
| `version_installed`| string            | Installed version |
| `severity`         | enum              | CRITICAL / HIGH / MEDIUM / LOW |
| `fix_available`    | boolean           | |
| `fix_version`      | string \| null    | null when `fix_available` is false |
| `fix_type`         | enum              | java-dep-bump / os-base-bump / dockerfile-change / no-fix-monitor |
| `affected_branches`| string[]          | `["3.1_ds"]`, `["4.0_ds"]`, or `["3.1_ds","4.0_ds"]` |
| `priority_score`   | integer (1–10)    | See scoring table |
| `scanner_source`   | enum              | trivy / snyk / both |

---

## 6. Sample Data

See [`docs/spike/sample-cve-input.json`](sample-cve-input.json) — a 10-CVE sample with real CVE IDs assembled from the repo's existing scan reports, covering all four fix types.

---

## Files Created

| File | Description |
|------|-------------|
| [`docs/spike/sample-cve-input.json`](sample-cve-input.json) | 10 real CVEs from ALPINE_CVE_SECURITY_REPORT.md |
| [`docs/spike/unified-cve-triage-schema.json`](unified-cve-triage-schema.json) | JSON Schema for triage records |
| [`prompts/triage-v1.txt`](../../prompts/triage-v1.txt) | Strict JSON-only prompt |
| [`prompts/triage-v2.txt`](../../prompts/triage-v2.txt) | Schema table + few-shot example |
| [`prompts/triage-v3.txt`](../../prompts/triage-v3.txt) | Chain-of-thought + delimiters |
| [`prompts/triage-v4.txt`](../../prompts/triage-v4.txt) | Minimal prompt (calibration use) |
| [`prompts/triage-v5.txt`](../../prompts/triage-v5.txt) | Table reasoning + code fence JSON |
| [`prompts/triage.txt`](../../prompts/triage.txt) | Canonical production triage prompt |
| [`scripts/normalise-bob-output.py`](../../scripts/normalise-bob-output.py) | Bob output normalisation wrapper |
| `docs/spike/json-schema-findings.md` | This document |

---

## Risk A5 Status: RESOLVED ✅

The `--output-format json` output is **structurally stable** (the outer envelope always has `response` and `stats`) but the inner `response` field contains plain text — the pipeline must extract and parse JSON from it.

The normalisation wrapper handles all observed output forms with 5 fallback strategies.
The canonical prompt (`prompts/triage.txt`) is designed to produce the most reliable raw-JSON output by combining strict JSON-only instructions with a few-shot example.
