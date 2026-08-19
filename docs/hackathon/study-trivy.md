# Study Guide — Trivy & Snyk Container Scanning

**Time to read:** ~20 minutes  
**Prerequisite:** Docker (you already know this)  
**Goal:** Run Trivy and Snyk against a container image and understand the JSON output format that
feeds into the Bob merge prompt.

---

## What are Trivy and Snyk?

Both are container vulnerability scanners. They inspect the packages inside a Docker image and
report CVEs (Common Vulnerabilities and Exposures).

| | Trivy | Snyk |
|-|-------|------|
| Type | Open source, CLI | Commercial, CLI + cloud |
| Auth | None needed for image scan | Requires `SNYK_TOKEN` |
| Strengths | Fast, works offline, broad DB | Deeper dependency graph, fix guidance |
| In this pipeline | Primary scanner | Confirmation scanner |

The spike confirmed **100% agreement** between the two scanners on all findings tested.

---

## Install Trivy locally

```bash
# macOS
brew install trivy

# Alpine (inside a Dockerfile)
RUN apk add --no-cache trivy

# Or install a specific version (recommended — pin in Dockerfile)
ARG TRIVY_VERSION=0.72.0
RUN wget -qO- "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/trivy_${TRIVY_VERSION}_Linux-ARM64.tar.gz" \
    | tar -xz -C /usr/local/bin trivy
```

---

## Run a Trivy scan (human-readable)

```bash
# Scan for HIGH and CRITICAL CVEs
trivy image --severity HIGH,CRITICAL alpine:3.21

# Scan the Pulsar image (once built)
trivy image --severity HIGH,CRITICAL pulsar-local:3.1_ds
```

---

## Run a Trivy scan (JSON output for pipeline)

```bash
trivy image \
  --format json \
  --severity HIGH,CRITICAL \
  --output trivy-output.json \
  pulsar-local:3.1_ds
```

### Trivy JSON structure (simplified)

```json
{
  "Results": [
    {
      "Target": "pulsar/lib/io.netty-netty-codec-4.1.131.Final.jar",
      "Type": "jar",
      "Vulnerabilities": [
        {
          "VulnerabilityID": "CVE-2026-42583",
          "PkgName": "io.netty:netty-codec",
          "InstalledVersion": "4.1.131.Final",
          "FixedVersion": "4.1.135.Final",
          "Severity": "HIGH",
          "Title": "Denial of Service via excessive memory allocation"
        }
      ]
    }
  ]
}
```

Key fields the Bob merge prompt reads: `VulnerabilityID`, `PkgName`, `InstalledVersion`,
`FixedVersion`, `Severity`.

---

## Install Snyk locally

```bash
npm install -g snyk

# Authenticate (one-time — uses SNYK_TOKEN)
snyk auth "${SNYK_TOKEN}"
```

---

## Run a Snyk container scan (JSON output for pipeline)

```bash
SNYK_TOKEN="${SNYK_TOKEN}" \
snyk container test pulsar-local:3.1_ds \
  --json \
  --severity-threshold=high \
  > snyk-output.json
```

> Snyk exits with code 1 when vulnerabilities are found — that is **expected behaviour**, not a failure.

### Snyk JSON structure (simplified)

```json
{
  "vulnerabilities": [
    {
      "id": "CVE-2026-42583",
      "packageName": "io.netty:netty-codec",
      "version": "4.1.131.Final",
      "fixedIn": ["4.1.135.Final"],
      "severity": "high",
      "title": "Denial of Service"
    }
  ]
}
```

**Important differences from Trivy:**
- Snyk uses lowercase severity (`"high"` not `"HIGH"`) — the Bob merge prompt handles this
- Snyk uses `packageName` not `PkgName`
- Snyk `fixedIn` is an array; the pipeline takes `fixedIn[0]`
- Snyk uses `id` not `VulnerabilityID`

The Bob merge prompt in `prompts/triage.txt` is written to handle both formats and produce a
normalised unified record.

---

## How the pipeline uses both scanners

```
trivy-output.json  ──┐
                     ├──▶  cat both.json | bob "$(cat prompts/triage.txt)"  ──▶  unified-cve.json
snyk-output.json   ──┘
```

Bob's merge prompt (the `prompts/triage.txt` "merge" sub-step) handles field name differences
and deduplicates by `cve_id + package`. If both scanners agree on a CVE, `scanner_source` is
set to `"both"` in the unified output.

---

## The unified CVE schema (what the pipeline produces)

After the Bob merge + triage step, every CVE record looks like this:

```json
{
  "cve_id": "CVE-2026-42583",
  "package": "io.netty:netty-codec",
  "version_installed": "4.1.131.Final",
  "severity": "HIGH",
  "fix_available": true,
  "fix_version": "4.1.135.Final",
  "fix_type": "java-dep-bump",
  "affected_branches": ["3.1_ds", "4.0_ds"],
  "priority_score": 8,
  "scanner_source": "both"
}
```

Full schema: `docs/spike/unified-cve-triage-schema.json`  
Sample data: `docs/spike/sample-cve-input.json`

---

## Current CVE state of the Pulsar image (`3.1_ds` branch)

From the spike scan (Alpine 3.21 image, 2026-07-08):

| Package | Version | Severity | CVEs | Fix |
|---------|---------|----------|------|-----|
| `jackson-databind` | 2.18.6 | CRITICAL | 2 | Bump to 2.18.8 |
| `io.netty:*` | 4.1.131.Final | HIGH | 21 | Bump to 4.1.135.Final |
| `protobuf` (Python) | 3.20.3 | HIGH | 2 | No fix (pulsar-client pins ≤3.20.3) |
| `tar` | 1.35 | MEDIUM | 1 | No fix available |
| **Alpine 3.21 OS** | — | — | **0** | ✅ Clean |

---

## Pin scanner versions in your Dockerfile

To avoid schema drift when scanners release new versions, pin the versions:

```dockerfile
ARG TRIVY_VERSION=0.72.0
ARG SNYK_VERSION=1.1291.0

RUN wget -qO- ".../trivy_${TRIVY_VERSION}_Linux-ARM64.tar.gz" | tar -xz -C /usr/local/bin
RUN npm install -g snyk@${SNYK_VERSION}
```

---

## Practical exercise (study week)

1. Install Trivy: `brew install trivy`
2. Run against the pre-built scanner image:
   ```bash
   docker pull icr.io/<NAMESPACE>/cve-scanner:hackathon-v1
   trivy image --severity HIGH,CRITICAL icr.io/<NAMESPACE>/cve-scanner:hackathon-v1
   ```
3. Save as JSON and inspect: `trivy image --format json -o /tmp/trivy-out.json ...`
4. Count CRITICAL CVEs: `jq '[.Results[].Vulnerabilities[]? | select(.Severity=="CRITICAL")] | length' /tmp/trivy-out.json`
5. Compare the JSON structure to `docs/spike/sample-cve-input.json`

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `trivy: command not found` | Install via `brew install trivy` or `apk add trivy` |
| Snyk exits with code 1 | Expected when vulnerabilities found — check if output JSON is non-empty |
| `SNYK_TOKEN not set` | Export `SNYK_TOKEN` from the shared Secrets Manager value |
| Trivy scan is slow | Use `--skip-update` flag if DB was already downloaded: `trivy image --skip-update ...` |
| No vulnerabilities found | You may have scanned the wrong image — confirm image name and tag |
