# Sub-Task 1 Findings: Bob TTY-Free Execution in a Containerised Environment

**Spike:** AUTOMATE-CVE-000  
**Date:** 2026-08-18  
**Bob version tested:** 1.0.6  
**Base image:** `alpine:3.21` (Node.js 22.23.2 from Alpine community repo)  
**Risk addressed:** A3 — "Does Bob operate correctly in a non-TTY container environment?"  
**Status:** ✅ RESOLVED

---

## Test Results

| # | Test | Command | Result |
|---|------|---------|--------|
| T1 | `bob --version` (no API key, no TTY) | `docker run --rm bob-test bob --version` | **PASS** — `1.0.6` |
| T2 | Headless prompt, no TTY | `docker run --rm -e BOBSHELL_API_KEY=... bob-test bob "list files" --approval-mode yolo --output-format json --hide-intermediary-output` | **PASS** — tool ran, JSON stats returned, exit 0 |
| T3 | Stdin pipe, no TTY (only `-i`) | `echo '{"test":"data"}' \| docker run --rm -i -e BOBSHELL_API_KEY=... bob-test bob "echo back the JSON" --approval-mode yolo --output-format json` | **PASS** — stdin content received and echoed back |
| T4 | Exit code | Same as T2 | **PASS** — exit 0 |

---

## Confirmed Working Invocation Patterns

### Pattern 1 — No-TTY direct prompt (IBM Code Engine batch job)

```bash
docker run --rm \
  -e BOBSHELL_API_KEY="${BOBSHELL_API_KEY}" \
  bob-test \
  bob "list files" \
  --approval-mode yolo \
  --output-format json \
  --hide-intermediary-output
```

- No `-t` flag. No TTY required or allocated.
- `--approval-mode yolo` suppresses all tool confirmation prompts (zero interactive pauses).
- `--hide-intermediary-output` suppresses tool-call noise; only the final `attempt_completion`
  result reaches stdout.
- `--output-format json` wraps final output in a JSON envelope with `response` + `stats` fields.
- Exit code `0` on success; non-zero on error or exceeded `--max-coins`.

### Pattern 2 — Stdin pipe (CVE JSON injection)

```bash
echo '{"cve_id":"CVE-2024-1234","severity":"HIGH","package":"netty"}' | \
docker run --rm -i \
  -e BOBSHELL_API_KEY="${BOBSHELL_API_KEY}" \
  bob-test \
  bob "analyse this CVE record and classify it" \
  --approval-mode yolo \
  --output-format json \
  --hide-intermediary-output
```

- `-i` (stdin open) is required for pipe input; `-t` must **not** be passed.
- Piped content is prepended to the prompt as context.
- Works with multi-line input and large JSON payloads (tested with CVE records).

### Pattern 3 — Multi-file pipe (Trivy + Snyk merge, Sub-Task 5)

```bash
cat trivy-output.json snyk-output.json | \
docker run --rm -i \
  -e BOBSHELL_API_KEY="${BOBSHELL_API_KEY}" \
  bob-test \
  bob "merge these two CVE scan outputs into a unified JSON list with fields:
       cve_id, package, severity, fix_available, fix_version, fix_type, scanner_source" \
  --approval-mode yolo \
  --output-format json \
  --hide-intermediary-output
```

---

## Installation Method

`bobshell` is **not published to the public npmjs.org registry**.  
It is distributed via IBM Cloud Object Storage — **no credentials required at download time**:

```
https://s3.us-south.cloud-object-storage.appdomain.cloud/bobshell/bobshell-<VERSION>.tgz
```

Confirmed accessible (HTTP 200) from outside IBM network:
```
curl -sI https://s3.us-south.cloud-object-storage.appdomain.cloud/bobshell/bobshell-1.0.6.tgz
# → HTTP/1.1 200 OK
```

**Minimal Dockerfile install block:**

```dockerfile
FROM alpine:3.21

ARG BOB_VERSION=1.0.6

RUN apk add --no-cache nodejs npm
RUN npm install -g \
    "https://s3.us-south.cloud-object-storage.appdomain.cloud/bobshell/bobshell-${BOB_VERSION}.tgz"
```

Node.js requirement: **>= 20**. Alpine 3.21 ships Node 22.23.2 — satisfies this requirement.

---

## Authentication

### Environment variable

| Variable | Required | Purpose |
|----------|----------|---------|
| `BOBSHELL_API_KEY` | **Yes — mandatory for headless use** | Static IBM Bob API key. Obtained from IBM Bob admin panel or Ask Bob Slack DM welcome message. |
| `NODE_EXTRA_CA_CERTS` | Only on IBM corporate network with TLS inspection | Path to corporate root CA certificate to prevent cert errors. |

The only auth env var bob v1.0.6 reads is `BOBSHELL_API_KEY` (confirmed from `process.env`
references in `bob.js` bundle). Not `BOB_API_KEY`, not `IBM_BOB_KEY`.

### Critical finding: SSO vs API key — settings.json auth lock

Bob's auth mode is determined by this priority chain (highest → lowest):
1. `--auth-method` CLI flag
2. `security.auth.selectedType` in `~/.bob/settings.json`
3. `BOBSHELL_API_KEY` env var presence
4. `CI` env var presence  
5. Default: W3ID SSO (browser redirect)

**The failure mode**: on a developer machine with `settings.json` containing
`"selectedType": "sso"`, even setting `BOBSHELL_API_KEY` in the environment is not enough —
bob still falls through to browser SSO and blocks indefinitely with:

```
Could not open browser automatically. Please open this URL manually:
https://bob.ibm.com/login?callback_uri=http://localhost:PORT/bob-callback&state=...
Waiting for authentication...
BFF authentication failed: Error: Authentication timeout (3 minutes)
```

**The fix**: bake a `~/.bob/settings.json` into the container image that pre-sets the auth type:

```json
{
  "ibm": {
    "isNotFirstTime": true,
    "licenseConsent": true
  },
  "security": {
    "auth": {
      "selectedType": "api-key"
    }
  }
}
```

This is included in [`docker/cve-spike/Dockerfile.bob-test`](../../docker/cve-spike/Dockerfile.bob-test) and must be replicated in all production CVE pipeline images.

### License acceptance

`licenseConsent: true` in `settings.json` is required. Without it, bob exits with:

```json
{
  "error": {
    "type": "Error",
    "message": "A license agreement is required. Please accept the license terms before proceeding.",
    "code": 1
  }
}
```

Both fields — `licenseConsent` and `selectedType: "api-key"` — must be present in the baked-in
settings.json for headless operation to work.

---

## `--output-format json` Schema (observed)

```json
{
  "response": "<final answer text from attempt_completion>",
  "stats": {
    "models": {
      "premium": {
        "api": { "totalRequests": 2, "totalErrors": 0, "totalLatencyMs": 7954 },
        "tokens": {
          "prompt": 16488, "candidates": 197, "total": 16685,
          "cached": 7946, "thoughts": 0, "tool": 0
        }
      }
    },
    "tools": {
      "totalCalls": 2, "totalSuccess": 2, "totalFail": 0,
      "byName": { "<tool_name>": { "count": 1, "success": 1, ... } }
    },
    "budgetSpend": 38.41,
    "maxBudget": 500,
    "sessionCost": 0.03337
  }
}
```

Key fields for the CVE pipeline:
- `response` — the natural-language or structured text produced by bob
- `stats.budgetSpend` — coins consumed (for Sub-Task 4 calibration)
- `stats.sessionCost` — USD cost of the run
- `stats.tools.byName` — which tools were called (useful for verifying bob took the right actions)

Note: with `--hide-intermediary-output`, stdout before the JSON block contains tool output text
(e.g. `"Directory listing for /workspace: README.md"`). The JSON block always begins with `{` on
its own line and can be isolated with `sed -n '/^{/,/^}/p'` or `jq` if required.

---

## Files Created

| File | Description |
|------|-------------|
| [`docker/cve-spike/Dockerfile.bob-test`](../../docker/cve-spike/Dockerfile.bob-test) | Minimal Alpine 3.21 image with bobshell installed from IBM COS and settings.json pre-configured for headless API key auth |
| [`docker/cve-spike/test-bob-tty.sh`](../../docker/cve-spike/test-bob-tty.sh) | Automated test script for all four TTY-free patterns; generates this findings document |
| `docs/spike/bob-tty-findings.md` | This document |

---

## Risk A3 Status: RESOLVED ✅

Bob v1.0.6 operates correctly in a non-TTY Alpine 3.21 container when:
1. `BOBSHELL_API_KEY` is set as an environment variable
2. `~/.bob/settings.json` contains `"selectedType": "api-key"` and `"licenseConsent": true`

No TTY allocation is required at runtime. The confirmed invocation for IBM Code Engine batch jobs is `docker run --rm -e BOBSHELL_API_KEY=... image bob "..." --approval-mode yolo --output-format json --hide-intermediary-output`.

---

## Recommended Next Steps

- **Sub-Task 2**: Confirm `BOBSHELL_API_KEY` injection via IBM Secrets Manager in a Code Engine
  job definition. See [`docs/spike/credential-injection-findings.md`](credential-injection-findings.md).
- **Sub-Task 3**: The `--output-format json` schema observed here shows `response` is a plain text
  string. Validate schema stability across 5 CVE triage prompts.
- **Sub-Task 4**: `budgetSpend: 38.41` coins for a trivial 2-tool run on the test workspace.
  A real CVE triage against this repo will consume significantly more — coin calibration is needed.
