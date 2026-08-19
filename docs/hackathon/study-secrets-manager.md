# Study Guide — IBM Secrets Manager

**Time to read:** ~15 minutes  
**Prerequisite:** IBM Cloud CLI installed (see `study-code-engine.md`)  
**Goal:** Understand how the 4 pipeline secrets are stored and how Code Engine jobs access them.

---

## What is IBM Secrets Manager?

IBM Secrets Manager stores sensitive values (API keys, tokens, passwords) centrally. Code Engine
jobs bind these secrets as environment variables at job startup — secrets are never hardcoded in
Dockerfiles or job definitions.

---

## The 4 pipeline secrets (already provisioned)

| Secret name | Env var in container | Used by |
|-------------|---------------------|---------|
| `bob-api-key` | `BOBSHELL_API_KEY` | All Bob-powered jobs |
| `snyk-token` | `SNYK_TOKEN` | Scanner job |
| `ibm-cloud-api-key` | `IBM_CLOUD_API_KEY` | All jobs (COS upload, ICR pull) |
| `github-pat` | `GITHUB_PAT` | Fix agents (branch push + PR creation) |

These are already provisioned in the shared Secrets Manager instance. You do **not** need to
create them — only bind them to your jobs.

---

## View secrets

```bash
# Install the Secrets Manager plugin (once)
ibmcloud plugin install secrets-manager

# List all secrets (confirm the 4 exist)
ibmcloud sm secrets \
  --instance-id "${SM_INSTANCE_ID}" \
  --region us-south
```

> `SM_INSTANCE_ID` is in `docs/hackathon/shared-image-ref.md`

---

## How secrets reach your container

When you bind a secret to a Code Engine job with `--env-from-secret`, Code Engine fetches the
secret value from Secrets Manager at job startup and sets it as an environment variable inside
the container. The value is never written to disk or logged.

```
Secrets Manager                  Code Engine Job
  "bob-api-key"                     container
  payload: "sk-abc123..."  ──────▶  BOBSHELL_API_KEY=sk-abc123...
```

### Binding command (you will run this for your own job)

```bash
ibmcloud ce job update \
  --name pair-a-cve-triage-3x \
  --env-from-secret bob-api-key:BOBSHELL_API_KEY \
  --env-from-secret ibm-cloud-api-key:IBM_CLOUD_API_KEY
```

### Pair B only (also needs GitHub PAT)

```bash
ibmcloud ce job update \
  --name pair-b-cve-fix-java-3x \
  --env-from-secret github-pat:GITHUB_PAT
```

---

## Verify the binding worked

After binding, check the job definition:

```bash
ibmcloud ce job get --name pair-a-cve-triage-3x
# Look for "Environment Variables" section — each bound secret appears as:
#   BOBSHELL_API_KEY  (from secret bob-api-key)
```

To confirm the secret is visible inside the container, add a test step that prints:

```bash
bob "run execute_command: echo BOBSHELL_API_KEY is set: \$BOBSHELL_API_KEY" \
  --approval-mode yolo --max-coins 50
```

*(This confirms the env var is present without logging the actual value.)*

---

## If you need to create a new secret (e.g. Slack webhook for Pair C)

```bash
ibmcloud sm secret-create \
  --secret-type arbitrary \
  --name "slack-webhook-url" \
  --payload "https://hooks.slack.com/services/..." \
  --instance-id "${SM_INSTANCE_ID}" \
  --region us-south

# Then bind to your job
ibmcloud ce job update \
  --name pair-c-cve-dashboard-gen \
  --env-from-secret slack-webhook-url:SLACK_WEBHOOK_URL
```

---

## Practical exercise (study week)

1. Run `ibmcloud sm secrets --instance-id <SM_INSTANCE_ID>` and confirm all 4 secrets are listed
2. Create a test secret:
   ```bash
   ibmcloud sm secret-create \
     --secret-type arbitrary \
     --name "my-test-secret" \
     --payload "hello-hackathon" \
     --instance-id "${SM_INSTANCE_ID}"
   ```
3. Bind it to a test Code Engine job; submit a run; confirm the env var is set inside the container
4. Delete the test secret afterwards

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `Secret not found` when binding | Check the exact secret name: `ibmcloud sm secrets --instance-id <ID>` |
| Env var is empty inside container | The secret payload may be empty or encoded; check `ibmcloud sm secret-get --id <SECRET_ID>` |
| `Authorization failed` for sm commands | Ensure your IBM Cloud API key has Secrets Manager `Reader` + `SecretsReader` IAM roles |
| Bob `Authentication timeout` | `BOBSHELL_API_KEY` is not set — check the `bob-api-key` binding on your job |
