# Study Guide — IBM Code Engine

**Time to read:** ~20 minutes  
**Prerequisite:** Docker (you already know this)  
**Goal:** Deploy a container as a Code Engine batch job, bind secrets to it, and trigger it manually.

---

## What is IBM Code Engine?

IBM Code Engine runs container workloads without managing servers. For this pipeline we use it
exclusively for **batch jobs** — containers that start, run to completion, and stop.

Key concepts:
- **Job** — a definition: which image, what env vars, how much CPU/memory
- **Job run** — one execution of a job definition
- **Cron job** — a job definition with a schedule (like a Kubernetes CronJob)

---

## Install the IBM Cloud CLI and Code Engine plugin

```bash
# Install ibmcloud CLI (macOS)
curl -fsSL https://clis.cloud.ibm.com/install/osx | sh

# Install the Code Engine plugin
ibmcloud plugin install code-engine

# Log in
ibmcloud login --apikey "${IBM_CLOUD_API_KEY}" -r us-south

# Target the shared project
ibmcloud ce project select --name cve-automation
```

---

## Create a job from a container image

```bash
ibmcloud ce job create \
  --name pair-a-cve-triage-3x \
  --image icr.io/<NAMESPACE>/cve-triage-agent:pair-a-v1 \
  --memory 4G \
  --cpu 1 \
  --maxexecutiontime 3600
```

> The `<NAMESPACE>` and image tag come from `docs/hackathon/shared-image-ref.md`

---

## Bind secrets as environment variables

Secrets are stored in IBM Secrets Manager. Code Engine binds them as env vars at job startup.
The 4 pipeline secrets are already provisioned — you just bind them:

```bash
# Bind all 4 secrets to your job
ibmcloud ce job update \
  --name pair-a-cve-triage-3x \
  --env-from-secret bob-api-key:BOBSHELL_API_KEY \
  --env-from-secret snyk-token:SNYK_TOKEN \
  --env-from-secret ibm-cloud-api-key:IBM_CLOUD_API_KEY

# For Pair B only (needs GitHub PAT for PR creation):
ibmcloud ce job update \
  --name pair-b-cve-fix-java-3x \
  --env-from-secret github-pat:GITHUB_PAT
```

The syntax is `<secret-name>:<env-var-name-inside-container>`.

---

## Add static environment variables

Non-sensitive config goes directly in the job definition:

```bash
ibmcloud ce job update \
  --name pair-a-cve-triage-3x \
  --env TARGET_BRANCH=3.1_ds \
  --env COS_BUCKET=cve-triage \
  --env MAX_COINS_TRIAGE=150
```

---

## Trigger a job manually

```bash
ibmcloud ce jobrun submit --job pair-a-cve-triage-3x
```

Watch the logs:

```bash
# List recent runs
ibmcloud ce jobrun list --job pair-a-cve-triage-3x

# Stream logs for the most recent run
ibmcloud ce jobrun logs --job pair-a-cve-triage-3x --follow
```

---

## Schedule a job (cron)

```bash
# Run every Monday at 02:00 UTC
ibmcloud ce job update \
  --name pair-a-cve-scanner-3x \
  --schedule "0 2 * * 1"

# View the schedule
ibmcloud ce job get --name pair-a-cve-scanner-3x
```

---

## Deploy a job from a YAML file

The spike already created a template at `infra/cve-automation/job-definitions/cve-scanner-job.yaml`.
Copy it for your own job:

```bash
# Copy and edit the template
cp infra/cve-automation/job-definitions/cve-scanner-job.yaml \
   infra/cve-automation/job-definitions/cve-triage-job.yaml

# Edit: change the job name, image, env vars, schedule
# Then deploy:
ibmcloud ce job create -f infra/cve-automation/job-definitions/cve-triage-job.yaml
```

---

## Push your image to IBM Container Registry

```bash
# Log in to ICR
ibmcloud cr login

# Build and push
docker build -t icr.io/<NAMESPACE>/cve-triage-agent:pair-a-v1 \
  -f docker/cve-agents/Dockerfile.triage-agent .

docker push icr.io/<NAMESPACE>/cve-triage-agent:pair-a-v1
```

---

## Check job run status

```bash
# See all job runs in the project
ibmcloud ce jobrun list

# Get details of a specific run
ibmcloud ce jobrun get --name <RUN_NAME>

# Delete a failed run (cleanup)
ibmcloud ce jobrun delete --name <RUN_NAME>
```

---

## Practical exercise (study week)

1. Log in and select the project (see day-1 checklist in `docs/hackathon/README.md`)
2. Create a test job using the pre-built scanner image:
   ```bash
   ibmcloud ce job create \
     --name my-test-job \
     --image icr.io/<NAMESPACE>/cve-scanner:hackathon-v1 \
     --env TARGET_BRANCH=3.1_ds \
     --memory 2G --cpu 1
   ```
3. Bind the Bob API key secret:
   ```bash
   ibmcloud ce job update --name my-test-job \
     --env-from-secret bob-api-key:BOBSHELL_API_KEY
   ```
4. Trigger it: `ibmcloud ce jobrun submit --job my-test-job`
5. Stream the logs: `ibmcloud ce jobrun logs --job my-test-job --follow`
6. Clean up: `ibmcloud ce job delete --name my-test-job`

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `Error: project not found` | Run `ibmcloud ce project select --name cve-automation` |
| Job run exits immediately with non-zero | Check logs: `ibmcloud ce jobrun logs --job <name>` |
| `ImagePullBackOff` | Check ICR login: `ibmcloud cr login`; confirm image tag is correct |
| Env var not set inside container | Check secret binding: `ibmcloud ce job get --name <name>` |
| `maxexecutiontime exceeded` | Increase: `ibmcloud ce job update --name <name> --maxexecutiontime 7200` |
