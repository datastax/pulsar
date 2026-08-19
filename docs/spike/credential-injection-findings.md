# Sub-Task 2 Findings: IBM Secrets Manager Credential Injection for Bob

**Spike:** AUTOMATE-CVE-000  
**Risk addressed:** A4 — "Can `BOBSHELL_API_KEY` be sourced from IBM Secrets Manager at container startup?"  
**Status:** ✅ ARCHITECTURE VALIDATED — pattern documented; live Code Engine confirmation needed in first deployment

---

## Summary

IBM Code Engine supports binding IBM Secrets Manager secrets directly as environment variables
at container startup via `--env-from-secret`. This is the standard IBM-native credential injection
pattern and requires no custom init script or secret fetching inside the container.

The Bob API key auth env var is confirmed as **`BOBSHELL_API_KEY`** (validated in Sub-Task 1).

---

## Confirmed Env Var Names

| Secret                     | Env Var Inside Container | Source Confirmed By |
|----------------------------|--------------------------|---------------------|
| Bob API key                | `BOBSHELL_API_KEY`       | Sub-Task 1 (bob.js bundle analysis) |
| Snyk org token             | `SNYK_TOKEN`             | Snyk CLI documentation |
| GitHub PAT                 | `GITHUB_PAT`             | GitHub REST API docs |
| IBM Cloud API key          | `IBM_CLOUD_API_KEY`      | IBM Cloud CLI / COS SDK |

---

## Code Engine Secret Binding Pattern

IBM Code Engine reads secrets from IBM Secrets Manager and exposes them as environment
variables inside the container at job startup. No secret values are stored in the job
definition YAML or container image.

### CLI binding command

```bash
ibmcloud ce job update \
  --name cve-scanner-3x \
  --env-from-secret bob-api-key:BOBSHELL_API_KEY \
  --env-from-secret snyk-token:SNYK_TOKEN \
  --env-from-secret ibm-cloud-api-key:IBM_CLOUD_API_KEY
```

The syntax is `<secrets-manager-secret-name>:<env-var-name>`.

### YAML representation (in job definition)

```yaml
envFrom:
  - secretRef:
      name: "bob-api-key"
      key: "payload"
      envVarName: "BOBSHELL_API_KEY"
  - secretRef:
      name: "snyk-token"
      key: "payload"
      envVarName: "SNYK_TOKEN"
  - secretRef:
      name: "ibm-cloud-api-key"
      key: "payload"
      envVarName: "IBM_CLOUD_API_KEY"
```

See full job definition: [`infra/cve-automation/job-definitions/cve-scanner-job.yaml`](../../infra/cve-automation/job-definitions/cve-scanner-job.yaml)

---

## Critical Requirement: settings.json Must Be Baked Into the Image

From Sub-Task 1 findings: setting `BOBSHELL_API_KEY` via env var injection is **necessary but
not sufficient** on its own. Bob's auth mode is determined by a priority chain:

1. `--auth-method` CLI flag (highest priority)
2. `security.auth.selectedType` in `~/.bob/settings.json`
3. `BOBSHELL_API_KEY` env var presence
4. `CI` env var presence
5. Default: W3ID SSO browser redirect (blocks indefinitely in a headless container)

**The required settings.json** (must be baked into every CVE pipeline image at build time):

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

**Dockerfile snippet to bake this in:**

```dockerfile
# Bake Bob auth settings — required for headless operation
# API key value itself is NOT stored here; it arrives at runtime via BOBSHELL_API_KEY env var
RUN mkdir -p /root/.bob && \
    echo '{"ibm":{"isNotFirstTime":true,"licenseConsent":true},"security":{"auth":{"selectedType":"api-key"}}}' \
    > /root/.bob/settings.json
```

This is already implemented in [`docker/cve-spike/Dockerfile.bob-test`](../../docker/cve-spike/Dockerfile.bob-test).

---

## Local Simulation Test

A local test script simulates the Code Engine secret injection pattern using `docker run -e`:

```bash
export BOBSHELL_API_KEY="your-api-key"
bash docker/cve-spike/test-credential-injection.sh
```

**Test coverage:**

| Test | What it validates |
|------|-------------------|
| C1   | `BOBSHELL_API_KEY` injected via `-e` → Bob authenticates and returns JSON response |
| C2   | No key injected → Bob exits with auth error (no TTY hang, no SSO browser block) |
| C3   | Stdin pipe + injected credentials → Bob receives piped CVE JSON and processes it |
| C4   | Multiple env vars injected simultaneously → all visible to Bob agent |

---

## IBM Secrets Manager Provisioning

See [`infra/cve-automation/secrets/required-secrets.md`](../../infra/cve-automation/secrets/required-secrets.md) for:
- Full list of required secrets with provisioning commands
- IBM Secrets Manager `ibmcloud sm secret-create` instructions
- Rotation schedule (90-day cadence for all secrets)

---

## Risk A2 Note: Code Engine Cron Scheduling

Risk A2 (Code Engine cron batch job support) is resolved by documentation:
IBM Code Engine supports periodic job scheduling via the `--schedule` flag (cron syntax):

```bash
ibmcloud ce job update --name cve-scanner-3x --schedule "0 2 * * 1"
```

This is embedded in the job definition YAML files. No fallback to IBM Cloud Functions is needed
for scheduling. IBM Cloud Functions is still used for the notification layer (event consumers).

---

## Files Created

| File | Description |
|------|-------------|
| [`infra/cve-automation/README.md`](../../infra/cve-automation/README.md) | IaC directory overview and deployment instructions |
| [`infra/cve-automation/secrets/required-secrets.md`](../../infra/cve-automation/secrets/required-secrets.md) | All secrets to provision, with CLI commands |
| [`infra/cve-automation/job-definitions/cve-scanner-job.yaml`](../../infra/cve-automation/job-definitions/cve-scanner-job.yaml) | Code Engine job YAML for 3.x and 4.x scanner jobs |
| [`docker/cve-spike/test-credential-injection.sh`](../../docker/cve-spike/test-credential-injection.sh) | Local simulation test script (4 tests) |
| `docs/spike/credential-injection-findings.md` | This document |

---

## Risk A4 Status: RESOLVED ✅

- `BOBSHELL_API_KEY` is the confirmed Bob auth env var
- IBM Code Engine `--env-from-secret` binding pattern is the correct injection mechanism
- `settings.json` must be baked into the image with `"selectedType": "api-key"` and `"licenseConsent": true`
- The injection of `SNYK_TOKEN`, `GITHUB_PAT`, and `IBM_CLOUD_API_KEY` follows the identical pattern
- Live validation in a Code Engine job will confirm at first deployment (AUTOMATE-CVE-001)
- Risk A2 (Code Engine cron) is also resolved — `--schedule` flag confirmed in IBM CE docs
