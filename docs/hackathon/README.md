# Hackathon Overview — CVE Automation Pipeline

**Working branch:** `hackathon/cve-ai-agent-triage` · **Code Engine project:** `cve-automation` · **IBM Cloud region:** `us-south`

Welcome to the AUTOMATE-CVE hackathon. The spike (CVE-000) is complete. The scanner image is
pre-built and live in IBM Container Registry. Your job is to build the remaining pipeline stages
as working IBM Code Engine jobs.

---

## Team Assignments

| Pair | Tickets | Your deliverable |
|------|---------|-----------------|
| **Pair A** | CVE-002, CVE-003 | Deploy the scanner cron jobs + build the Bob Triage Agent job |
| **Pair B** | CVE-004, CVE-005, CVE-006 | Build the Java Fix Agent + Dockerfile Fix Agent + PR creation |
| **Pair C** | CVE-007, CVE-008, CVE-009, CVE-010 | Dashboard + Slack alerting + CI gate + runbook |

---

## Pre-reading (study week — do this before hackathon day)

Read these in order — each takes 15–30 minutes:

| # | File | What you learn |
|---|------|---------------|
| 1 | [`docs/hackathon/study-bob.md`](study-bob.md) | IBM Bob CLI: install, headless invocation, prompts |
| 2 | [`docs/hackathon/study-code-engine.md`](study-code-engine.md) | IBM Code Engine: create jobs, bind secrets, trigger runs |
| 3 | [`docs/hackathon/study-secrets-manager.md`](study-secrets-manager.md) | IBM Secrets Manager: create and bind secrets |
| 4 | [`docs/hackathon/study-trivy.md`](study-trivy.md) | Trivy + Snyk: scan commands, JSON output format |
| 5 | [`docs/spike/ADR-001-cve-pipeline-architecture.md`](../spike/ADR-001-cve-pipeline-architecture.md) | Full pipeline architecture (required reading for all pairs) |

Then read **your pair's starting materials** listed in [`hackathon-plan.md`](../../hackathon-plan.md).

---

## Shared resources (available from day 1)

| Resource | Value | How to use |
|----------|-------|------------|
| Pre-built scanner image | See `docs/hackathon/shared-image-ref.md` | Reference as base in your Dockerfiles |
| IBM Cloud region | `us-south` | `ibmcloud login -r us-south` |
| Code Engine project | `cve-automation` | `ibmcloud ce project select --name cve-automation` |
| COS bucket — scan results | `cve-reports` | Scanner writes here; triage agent reads from here |
| COS bucket — triage results | `cve-triage` | Triage agent writes here; fix agents read from here |
| COS bucket — dashboard | `cve-dashboard` | Pair C writes dashboard HTML here |
| Event Streams topics | See ADR-001 | `cve.scan.completed`, `cve.triage.completed`, etc. |

---

## Git workflow — branch from here, not from 3.1_ds or master

**All hackathon work branches off `hackathon/cve-ai-agent-triage`.**
Do not branch from `3.1_ds` (production) or `master`.

```bash
# Clone (first time)
git clone https://github.com/datastax/pulsar.git
cd pulsar

# Check out the hackathon base branch
git fetch origin
git checkout hackathon/cve-ai-agent-triage

# Create your pair's working branch from it
# Pair A:
git checkout -b pair-a/cve-triage

# Pair B:
git checkout -b pair-b/cve-fix-agents

# Pair C:
git checkout -b pair-c/cve-observability
```

Push your work to origin as you go:

```bash
git push origin pair-a/cve-triage
```

When your Code Engine job is working and your YAML is committed, open a PR targeting
`hackathon/cve-ai-agent-triage` (not master, not 3.1_ds).

---

## Day-1 checklist (do this first, before writing any code)

```bash
# 0. Check out the hackathon branch (see Git workflow above)
git checkout hackathon/cve-ai-agent-triage

# 1. Log in to IBM Cloud
ibmcloud login --apikey "${IBM_CLOUD_API_KEY}" -r us-south

# 2. Select the shared Code Engine project
ibmcloud ce project select --name cve-automation

# 3. Log in to IBM Container Registry
ibmcloud cr login

# 4. Pull the pre-built scanner image (confirm it works)
#    Image tag is in docs/hackathon/shared-image-ref.md
docker pull icr.io/<NAMESPACE>/cve-scanner:hackathon-v1

# 5. Confirm you can see the secrets
ibmcloud sm secrets --instance-id <SM_INSTANCE_ID>
```

---

## Naming convention for your jobs

All Code Engine job names must be prefixed with your pair ID to avoid collisions:

| Pair | Job name pattern | Example |
|------|-----------------|---------|
| A | `pair-a-cve-*` | `pair-a-cve-triage-3x` |
| B | `pair-b-cve-*` | `pair-b-cve-fix-java-3x` |
| C | `pair-c-cve-*` | `pair-c-cve-dashboard-gen` |

---

## Key files to know

```
docker/
  cve-spike/
    Dockerfile.bob-test        ← your base image for every agent container
    e2e-dry-run.sh             ← step-by-step pipeline reference
    test-credential-injection.sh ← test that secrets are wired correctly

docs/spike/
  ADR-001-cve-pipeline-architecture.md  ← full architecture (MUST READ)
  unified-cve-triage-schema.json        ← the data model every job produces/consumes
  sample-cve-input.json                 ← 10 real CVEs for local testing
  bob-tty-findings.md                   ← Bob auth and invocation details
  e2e-dry-run-findings.md               ← confirmed pom.xml + FROM line findings

prompts/
  triage.txt                 ← Pair A: canonical triage prompt
  fix-java-dep.txt           ← Pair B: Java fix prompt
  fix-dockerfile.txt         ← Pair B: Dockerfile fix prompt

scripts/
  normalise-bob-output.py    ← every job that calls Bob pipes through this
  estimate-bob-coins.sh      ← check coin budget before running a large batch

infra/cve-automation/
  job-definitions/
    cve-scanner-job.yaml     ← TEMPLATE — copy and adapt for your job
  secrets/
    required-secrets.md      ← 4 secrets, CLI provisioning commands
```

---

## Definition of done (per pair)

A job is "done" when:
1. `ibmcloud ce jobrun submit --job <your-job-name>` runs to completion (exit 0)
2. The job's output artefact appears in the expected COS bucket path
3. The output passes schema validation (`scripts/normalise-bob-output.py` exits 0 for triage/fix jobs)
4. The job definition YAML is committed to `infra/cve-automation/job-definitions/`
