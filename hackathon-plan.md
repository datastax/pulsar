# CVE Automation Hackathon — Plan

## Overview

A one-day (or multi-day) hackathon where 6 engineers in 3 pairs implement the remaining phases of
the AUTOMATE-CVE pipeline. The spike (AUTOMATE-CVE-000) is complete. The scanner image
(AUTOMATE-CVE-001) will be pre-built and pushed to ICR by Hariharan before hackathon day.

**Goal:** Each pair ships a working IBM Code Engine job that can be triggered manually against the
`3.1_ds` branch by end of the hackathon.

**Environment:** Single shared IBM Cloud account. Code Engine project: `cve-automation`.
Job names are namespaced per pair (e.g. `pair-a-cve-triage-3x`).

**Branch for all work:** `3.1_ds`

---

## Team Pairings

| Pair | Engineers | AUTOMATE-CVE tickets | Core deliverable |
|------|-----------|----------------------|-----------------|
| **Pair A** | TBD, TBD | CVE-002, CVE-003 | Cron schedule deploy + Bob Triage Agent job |
| **Pair B** | TBD, TBD | CVE-004, CVE-005, CVE-006 | Java Fix Agent + Dockerfile Fix Agent + PR creation |
| **Pair C** | TBD, TBD | CVE-007, CVE-008, CVE-009, CVE-010 | Dashboard + Alerting + CI Gate + Runbook |

---

## Pre-Hackathon Setup (Hariharan — must be done before day 1)

### Status: `[ ] pending`

**Intent:** Create the shared infrastructure baseline so no pair is blocked on day 1.

**Todo List**
1. Build the scanner image from `docker/cve-spike/Dockerfile.bob-test` extended with Trivy + Snyk:
   - Add `trivy` and `snyk` CLI installs on top of the bob-test image
   - Add `scripts/normalise-bob-output.py` and `prompts/` directory into the image
   - Add an entrypoint script that runs: trivy scan → snyk scan → bob merge → normalise → COS upload
2. Push to ICR: `ibmcloud cr image-push icr.io/<NAMESPACE>/cve-scanner:hackathon-v1`
3. Record the full image reference (including digest) in `docs/hackathon/shared-image-ref.md`
4. Provision the 4 secrets in IBM Secrets Manager (see `infra/cve-automation/secrets/required-secrets.md`):
   - `bob-api-key` → `BOBSHELL_API_KEY`
   - `snyk-token` → `SNYK_TOKEN`
   - `github-pat` → `GITHUB_PAT`
   - `ibm-cloud-api-key` → `IBM_CLOUD_API_KEY`
5. Create the Code Engine project: `ibmcloud ce project create --name cve-automation`
6. Create IBM COS buckets: `cve-reports`, `cve-triage`, `cve-rescans`, `cve-dashboard`
7. Create IBM Event Streams instance and topics:
   - `cve.scan.completed`, `cve.triage.completed`, `cve.fix.pr_created`, `cve.critical_found`, `cve.no_fix_available`
8. Create a `.cve-policy.yaml` in the repo root (default thresholds: CRITICAL=0, HIGH=5)
9. Share the IBM Cloud account credentials/access to all 3 pairs via IBM Secrets Manager service credentials
10. Write `docs/hackathon/shared-image-ref.md` with the exact ICR image tag and all COS bucket names

**Expected Outcomes**
- Scanner image is live in ICR and tested with a manual `ibmcloud ce jobrun submit`
- All 4 secrets are in Secrets Manager and bindable
- Code Engine project, COS buckets, and Event Streams topics exist
- `.cve-policy.yaml` committed to the repo
- All pairs can `ibmcloud login` and see the shared project on day 1

---

## Sub-Task 0 — Study Week Materials (Hariharan — write before handing off)

### Status: `[ ] pending`

**Intent:** Produce written cheat sheets for the 4 tools engineers need from scratch: IBM Bob,
IBM Code Engine, IBM Secrets Manager, and Trivy. Engineers know Docker and GitHub already.

**Todo List**
1. Write `docs/hackathon/study-bob.md` — IBM Bob cheat sheet
2. Write `docs/hackathon/study-code-engine.md` — IBM Code Engine cheat sheet
3. Write `docs/hackathon/study-secrets-manager.md` — IBM Secrets Manager cheat sheet
4. Write `docs/hackathon/study-trivy.md` — Trivy + Snyk basics cheat sheet
5. Write `docs/hackathon/README.md` — hackathon overview, team assignments, day-1 checklist

**Expected Outcomes**
- All 5 docs committed to `docs/hackathon/` on the `3.1_ds` branch
- Each doc is self-contained: install → authenticate → one real command relevant to the pipeline
- Engineers can complete the study week by reading only these files + the ADR

**Relevant Context**
- `docs/spike/bob-tty-findings.md` — source for Bob cheat sheet (install, settings.json, invocation)
- `docs/spike/credential-injection-findings.md` — source for Secrets Manager cheat sheet
- `infra/cve-automation/job-definitions/cve-scanner-job.yaml` — source for Code Engine cheat sheet
- `docs/spike/ADR-001-cve-pipeline-architecture.md` — architectural context for all cheat sheets

---

## Sub-Task A — Pair A: Cron Schedule Deploy + Bob Triage Agent (CVE-002, CVE-003)

### Status: `[ ] pending`

**Intent:** Deploy the cron-scheduled scanner jobs and build the Bob-powered triage agent that
classifies CVEs by fix type using the prompt and schema already authored in the spike.

**Starting materials available**
- `infra/cve-automation/job-definitions/cve-scanner-job.yaml` — scanner job YAML (fill placeholders)
- `prompts/triage.txt` — canonical triage prompt (production-ready)
- `scripts/normalise-bob-output.py` — output normalisation wrapper
- `docs/spike/unified-cve-triage-schema.json` — target output schema
- `docs/spike/sample-cve-input.json` — 10 real CVEs for local testing

**Expected Outcomes**
- Two Code Engine cron jobs deployed: `pair-a-cve-scanner-3x` (Mon 02:00 UTC) and `pair-a-cve-scanner-4x` (Tue 02:00 UTC)
- A triage agent Code Engine job: `pair-a-cve-triage-3x` that reads from COS, runs Bob triage, uploads classified JSON
- Triage job can be triggered manually: `ibmcloud ce jobrun submit --job pair-a-cve-triage-3x`
- Triage output validates against `docs/spike/unified-cve-triage-schema.json` (normalise-bob-output.py exits 0)
- `infra/cve-automation/job-definitions/cve-triage-job.yaml` committed

**Todo List (Pair A)**
1. Clone and read: `infra/cve-automation/job-definitions/cve-scanner-job.yaml`, `prompts/triage.txt`, `docs/hackathon/study-code-engine.md`
2. Deploy scanner jobs: fill `<ICR_NAMESPACE>` and `<IMAGE_TAG>` in the YAML with the pre-built image ref from `docs/hackathon/shared-image-ref.md`
3. Bind secrets to scanner jobs using `ibmcloud ce job update --env-from-secret ...`
4. Trigger a manual scan run; verify JSON lands in COS bucket `cve-reports/3.1_ds/`
5. Build the triage agent Dockerfile (extend `docker/cve-spike/Dockerfile.bob-test`; mount COS download + run triage prompt)
6. Push triage agent image to ICR: `icr.io/<NAMESPACE>/cve-triage-agent:pair-a-v1`
7. Write `infra/cve-automation/job-definitions/cve-triage-job.yaml` using scanner YAML as template
8. Deploy triage job; trigger manually; verify output in COS `cve-triage/3.1_ds/`
9. Pipe triage output through `scripts/normalise-bob-output.py` — confirm exit 0
10. Run triage against `docs/spike/sample-cve-input.json` locally; verify all 10 CVEs classified correctly

**Relevant Context**
- `docs/spike/coin-budget-findings.md` — use `--max-coins 150` for triage
- `prompts/triage-v1.txt` through `triage-v5.txt` — alternative prompts if v1 underperforms
- Bob invocation pattern: `cat scan.json | bob "$(cat prompts/triage.txt)" --approval-mode yolo --output-format json --hide-intermediary-output --max-coins 150`

---

## Sub-Task B — Pair B: Java Fix Agent + Dockerfile Fix Agent + PR Creation (CVE-004, CVE-005, CVE-006)

### Status: `[ ] pending`

**Intent:** Build the two Bob fix agents that apply code changes to the repo, plus the automated
PR creation step. These are the most technically complex jobs — they require Bob to modify files
in a checked-out working copy, validate the change, and push a branch.

**Starting materials available**
- `prompts/fix-java-dep.txt` — Java dep-bump fix agent prompt (production-ready)
- `prompts/fix-dockerfile.txt` — Dockerfile fix agent prompt (production-ready)
- `docs/spike/e2e-dry-run-findings.md` — confirmed pom.xml property location + 3 FROM line finding
- `docker/cve-spike/e2e-dry-run.sh` — step-by-step dry run reference (Steps 5 + 6)
- `infra/cve-automation/secrets/required-secrets.md` — GITHUB_PAT provisioning

**Expected Outcomes**
- A Java fix agent Code Engine job: `pair-b-cve-fix-java-3x`
  - Reads triage JSON from COS `cve-triage/3.1_ds/`
  - Applies a version bump to `jackson-databind` (2.18.6 → 2.18.8) in `pom.xml`
  - Runs `mvn dependency:resolve` to validate
  - Commits and pushes to branch `bot/cve-fix/3.1_ds/CVE-2026-54512`
- A Dockerfile fix agent job: `pair-b-cve-fix-dockerfile-3x`
  - Updates all three `FROM alpine:3.21` lines atomically in `docker/pulsar/Dockerfile`
- A PR creation step: calls Bob's `create_pr_workflow` to open a draft PR from the fix branch
- `infra/cve-automation/job-definitions/cve-fix-java-job.yaml` and `cve-fix-dockerfile-job.yaml` committed

**Todo List (Pair B)**
1. Read: `prompts/fix-java-dep.txt`, `prompts/fix-dockerfile.txt`, `docs/spike/e2e-dry-run-findings.md`
2. Build fix-java Dockerfile: extend bob-test with `git`, `maven` (or `mvn` wrapper), and repo checkout
3. Test locally: run `docker/cve-spike/e2e-dry-run.sh` Steps 5 + 7 to confirm Bob finds the pom.xml property
4. Build the fix-java Code Engine job; bind `BOBSHELL_API_KEY` and `GITHUB_PAT` secrets
5. Test: trigger job with a single CVE record from `docs/spike/sample-cve-input.json`; confirm branch is pushed
6. Build fix-dockerfile Dockerfile: extend bob-test with `git` and Docker CLI (for re-scan validation)
7. Test locally: run dry-run Step 6 to confirm Bob enumerates all 3 FROM lines correctly
8. Build the fix-dockerfile Code Engine job; test with a patched Alpine tag
9. Add PR creation as the final step of both fix jobs using Bob's `create_pr_workflow`
10. Verify PR targets the correct base branch and carries the CVE table in the description

**Relevant Context**
- Critical finding: `docker/pulsar/Dockerfile` has 3 `FROM alpine:3.21` lines (lines 52, 70, 87) — all must update atomically
- `--max-coins 150` for fix agents; `--max-coins 100` for PR creation
- Maven is required in the fix-java container: `RUN apk add --no-cache maven` (or use the Maven wrapper)
- Commit message format: `fix(security): bump {package} to {version} — {CVE-IDs}`

---

## Sub-Task C — Pair C: Dashboard + Alerting + CI Gate + Runbook (CVE-007, CVE-008, CVE-009, CVE-010)

### Status: `[ ] pending`

**Intent:** Build the observability and governance layer: a static HTML dashboard, Slack/Jira
alerting via IBM Cloud Functions, a CI gate on PRs, and the operator runbook.

**Starting materials available**
- `docs/spike/ADR-001-cve-pipeline-architecture.md` — IBM Event Streams topics and Cloud Functions description
- `docs/spike/unified-cve-triage-schema.json` — dashboard data model (all fields to display)
- `docs/spike/sample-cve-input.json` — seed data for dashboard development
- `.github/workflows/ci-owasp-dependency-check.yaml` — existing security workflow pattern to extend

**Expected Outcomes**
- A static HTML dashboard at a COS public endpoint showing CVE status for both branches
  (can be seeded with `docs/spike/sample-cve-input.json` for the demo)
- A Slack notification fires when a scan completes (test with a manual Event Streams event publish)
- A GitHub Actions workflow step in `.github/workflows/pulsar-ci.yaml` that runs the scanner image
  and fails the build if CRITICAL CVEs exceed the threshold in `.cve-policy.yaml`
- `docs/cve-automation-runbook.md` committed covering the top 5 failure modes from the spike

**Todo List (Pair C)**
1. Read: `docs/spike/ADR-001-cve-pipeline-architecture.md`, `docs/spike/unified-cve-triage-schema.json`, `.github/workflows/ci-owasp-dependency-check.yaml`
2. **Dashboard (CVE-007):** Write a Python/Node script that reads `cve-triage/3.1_ds/` from COS and renders an HTML page; upload to `cve-dashboard/` bucket with public access
3. Deploy as a Code Engine job: `pair-c-cve-dashboard-gen` triggered by `cve.scan.completed` event
4. **Alerting (CVE-008):** Create an IBM Cloud Functions action for `notify-scan-complete` that sends a Slack webhook message; wire it to the `cve.scan.completed` Event Streams topic
5. Test by publishing a manual event: `ibmcloud es message-produce --topic cve.scan.completed --payload '{"branch":"3.1_ds","critical":2,"high":21}'`
6. **CI Gate (CVE-009):** Add a new step to `.github/workflows/pulsar-ci.yaml` that runs the pre-built scanner image and exits non-zero if CRITICAL CVEs exceed `.cve-policy.yaml` threshold
7. Test the gate by temporarily lowering the threshold to 0 and verifying the CI job fails
8. **Runbook (CVE-010):** Write `docs/cve-automation-runbook.md` covering:
   - How to trigger a manual scan run
   - How to re-run a failed triage agent
   - How to override a Bob fix (delete branch, re-trigger)
   - How to rotate a secret in IBM Secrets Manager
   - How to add a new branch (e.g. `4.2_ds`) to the pipeline

**Relevant Context**
- Event Streams topics already created in pre-hackathon setup: use them as trigger sources
- `.cve-policy.yaml` already created in pre-hackathon setup; CI gate reads from it
- The dashboard can use the `cve_automation_jira` HTML file as a design reference
- Runbook should reference `docker/cve-spike/e2e-dry-run.sh` as the manual scan template

---

## Day-1 Kickoff Checklist (for Hariharan to present)

- [ ] Confirm all 3 pairs can `ibmcloud login` with shared account credentials
- [ ] Confirm `ibmcloud ce project select --name cve-automation` works for everyone
- [ ] Confirm `ibmcloud cr login` works for everyone (ICR pull access)
- [ ] Show pre-built scanner image: `docker pull icr.io/<NAMESPACE>/cve-scanner:hackathon-v1`
- [ ] Run a live demo of the scanner job: `ibmcloud ce jobrun submit --job cve-scanner-3x`
- [ ] Show COS output: `ibmcloud cos objects --bucket cve-reports`
- [ ] Walk through `docs/hackathon/README.md` — 5 minutes max
- [ ] Pairs read their starting materials and write their first job definition

---

## Deliverable Checklist (end of hackathon)

| Pair | Deliverable | Definition of Done |
|------|-------------|-------------------|
| A | `pair-a-cve-scanner-3x` cron job deployed | `ibmcloud ce jobrun submit --job pair-a-cve-scanner-3x` runs and uploads JSON to COS |
| A | `pair-a-cve-triage-3x` job deployed | Triage JSON in COS passes `normalise-bob-output.py` validation |
| B | `pair-b-cve-fix-java-3x` job | Git branch `bot/cve-fix/3.1_ds/CVE-2026-54512` pushed, `mvn dependency:resolve` passed |
| B | `pair-b-cve-fix-dockerfile-3x` job | All 3 FROM lines updated atomically in a fix branch |
| B | PR creation step | Draft PR opened in GitHub from fix branch to `3.1_ds` |
| C | COS static dashboard | URL accessible; shows CVE table seeded from sample data |
| C | Slack alert | Slack message fires when test event published to Event Streams |
| C | CI gate | PR to `3.1_ds` fails CI when CRITICAL CVEs exceed threshold |
| C | Runbook | `docs/cve-automation-runbook.md` covers 5 failure modes |

---

## Open Questions (resolve before hackathon day)

1. **IBM Cloud account access:** What is the mechanism for sharing credentials? Service account API key per pair, or a shared key via the study-week Slack channel?
2. **ICR namespace:** Confirm the namespace name before pushing the scanner image — teams need it on day 1.
3. **Slack webhook:** A Slack webhook URL for Pair C's alerting work needs to be provisioned and stored as a 5th secret (`slack-webhook-url` → `SLACK_WEBHOOK_URL`).
4. **GitHub PAT scope:** Confirm the PAT has `repo` write scope on the `pulsar` repo so Pair B can push fix branches.
5. **Maven in fix-java container:** Confirm whether to use `apk add maven` (Alpine package) or install a specific Maven version from the Maven binary distribution (faster, more version control).
