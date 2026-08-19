# Shared Hackathon Resources

This file is filled in by Hariharan before hackathon day. All pairs read this on day 1.

---

## Pre-built Scanner Image (AUTOMATE-CVE-001)

| Field | Value |
|-------|-------|
| ICR namespace | `<FILL_IN>` |
| Full image reference | `icr.io/<NAMESPACE>/cve-scanner:hackathon-v1` |
| Image digest | `<FILL_IN after push>` |
| Build date | `<FILL_IN>` |
| Trivy version bundled | `0.72.0` |
| Snyk version bundled | `<FILL_IN>` |
| Bob version bundled | `1.0.6` |

**To pull and verify:**
```bash
docker pull icr.io/<NAMESPACE>/cve-scanner:hackathon-v1
docker run --rm icr.io/<NAMESPACE>/cve-scanner:hackathon-v1 bob --version
docker run --rm icr.io/<NAMESPACE>/cve-scanner:hackathon-v1 trivy --version
docker run --rm icr.io/<NAMESPACE>/cve-scanner:hackathon-v1 snyk --version
```

---

## IBM Cloud Environment

| Resource | Value |
|----------|-------|
| IBM Cloud region | `us-south` |
| Code Engine project | `cve-automation` |
| Code Engine project ID | `<FILL_IN>` |
| IBM Container Registry region | `us.icr.io` |
| Secrets Manager instance ID | `<FILL_IN>` |

---

## IBM Cloud Object Storage Buckets

| Bucket name | Purpose | Path pattern |
|-------------|---------|-------------|
| `cve-reports` | Raw Trivy + Snyk scan output | `{branch}/{date}/{image-tag}.json` |
| `cve-triage` | Bob-classified triage records | `{branch}/{date}/triage.json` |
| `cve-rescans` | Post-fix re-scan evidence | `{branch}/{date}/{cve-id}-rescan.json` |
| `cve-dashboard` | Static HTML dashboard | `index.html` |

**COS service endpoint (us-south):** `https://s3.us-south.cloud-object-storage.appdomain.cloud`

---

## IBM Event Streams Topics

| Topic name | Published by | Consumed by |
|------------|-------------|-------------|
| `cve.scan.completed` | Scanner job | Triage agent, dashboard generator |
| `cve.triage.completed` | Triage agent | Fix agents |
| `cve.fix.pr_created` | Fix agents | Dashboard, alerting |
| `cve.critical_found` | Triage agent | Alerting (immediate Slack alert) |
| `cve.no_fix_available` | Triage agent | Jira ticket creation |

**Event Streams broker URL:** `<FILL_IN>`  
**Event Streams API key:** sourced from `ibm-cloud-api-key` secret

---

## Secrets Manager — Provisioned Secrets

| Secret name | Env var name | Notes |
|-------------|-------------|-------|
| `bob-api-key` | `BOBSHELL_API_KEY` | Bob CLI authentication |
| `snyk-token` | `SNYK_TOKEN` | Snyk container scan |
| `ibm-cloud-api-key` | `IBM_CLOUD_API_KEY` | COS + ICR + Secrets Manager access |
| `github-pat` | `GITHUB_PAT` | Push fix branches + create PRs (Pair B) |
| `slack-webhook-url` | `SLACK_WEBHOOK_URL` | Slack notifications (Pair C) |

**To bind to your job:**
```bash
ibmcloud ce job update --name <YOUR_JOB> \
  --env-from-secret bob-api-key:BOBSHELL_API_KEY \
  --env-from-secret ibm-cloud-api-key:IBM_CLOUD_API_KEY
```

---

## IBM Cloud Login Command (day-1)

```bash
ibmcloud login --apikey "${IBM_CLOUD_API_KEY}" -r us-south
ibmcloud ce project select --name cve-automation
ibmcloud cr login
```

> Your `IBM_CLOUD_API_KEY` will be shared via the hackathon Slack channel DM before day 1.
