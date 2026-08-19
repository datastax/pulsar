# Study Guide — IBM Bob CLI

**Time to read:** ~25 minutes  
**Prerequisite:** None (you know Docker and GitHub already)  
**Goal:** Run a Bob agent headlessly inside a Docker container with no interactive prompts.

---

## What is IBM Bob?

IBM Bob is an AI coding agent. You give it a text prompt describing a task; it uses tools
(`grep`, `read_file`, `apply_diff`, `execute_command`, etc.) to carry out the task on your
codebase and returns a result. In this pipeline, Bob is invoked headlessly inside container
jobs — no browser, no terminal interaction.

---

## Install Bob CLI (in a container)

Bob is **not** on the public npm registry. It is downloaded from IBM Cloud Object Storage:

```dockerfile
# In your Dockerfile — copy this block exactly
ARG BOB_VERSION=1.0.6

RUN apk add --no-cache curl nodejs npm \
    && npm install -g \
       "https://s3.us-south.cloud-object-storage.appdomain.cloud/bobshell/bobshell-${BOB_VERSION}.tgz" \
    && bob --version
```

**Node.js ≥ 20 is required.** Alpine 3.21 ships Node 22 — satisfies this.

**No credentials needed at build time.** The COS bucket is publicly readable.

---

## Configure Bob for headless use

Bob defaults to W3ID SSO (browser login). For container use you must bake this config file
into the image — otherwise the container hangs waiting for a browser:

```dockerfile
RUN mkdir -p /root/.bob && cat > /root/.bob/settings.json <<'EOF'
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
EOF
```

The actual API key is **never** baked into the image — it arrives at runtime via env var.

---

## Authenticate at runtime

Bob reads one environment variable for auth:

```bash
export BOBSHELL_API_KEY="your-api-key-here"
```

In Code Engine jobs this is injected from IBM Secrets Manager automatically.  
On your laptop, export it before running any `bob` command.

> **Where do I get a key?**  
> Ask your team lead / IBM Bob admin, or look in the Ask Bob Slack channel welcome DM.

---

## Core invocation patterns

### Pattern 1 — Simple headless prompt (no TTY)

```bash
bob "list files in the current directory" \
  --approval-mode yolo \
  --output-format json \
  --hide-intermediary-output \
  --max-coins 100
```

- `--approval-mode yolo` — never ask for confirmation; auto-approve all tool calls
- `--output-format json` — wrap the final answer in `{"response":"...", "stats":{...}}`
- `--hide-intermediary-output` — suppress tool-call noise; only the final answer appears on stdout
- `--max-coins 100` — hard spending limit; Bob exits with error if exceeded

### Pattern 2 — Pipe data into Bob via stdin

```bash
cat docs/spike/sample-cve-input.json | \
bob "$(cat prompts/triage.txt)" \
  --approval-mode yolo \
  --output-format json \
  --hide-intermediary-output \
  --max-coins 150
```

Use `-i` (not `-t`) when running inside `docker run`:

```bash
cat scan.json | docker run --rm -i \
  -e BOBSHELL_API_KEY="${BOBSHELL_API_KEY}" \
  my-bob-image \
  bob "$(cat prompts/triage.txt)" --approval-mode yolo --output-format json --max-coins 150
```

### Pattern 3 — Resume a session (multi-step tasks)

```bash
# Step 1: apply fix
bob "$(cat prompts/fix-java-dep.txt)" --approval-mode yolo --output-format json --max-coins 150

# Step 2: resume the same session and open a PR
bob --resume latest "open a PR for the changes just made" \
  --approval-mode yolo --hide-intermediary-output --max-coins 100
```

---

## Understand Bob's JSON output

When `--output-format json` is used, stdout looks like this:

```json
{
  "response": "Here are the results: [{...}]",
  "stats": {
    "budgetSpend": 38.41,
    "maxBudget": 500,
    "sessionCost": 0.033,
    "tools": { "totalCalls": 3, "totalSuccess": 3 }
  }
}
```

Key points:
- `response` is a **plain text string** — it may contain JSON inside it (raw or in ` ```json ``` ` fences)
- `stats.budgetSpend` — coins consumed for this run
- `stats.sessionCost` — USD cost of the run

**Always pipe Bob's output through `scripts/normalise-bob-output.py`** to extract and validate
the inner JSON before passing it to the next pipeline stage.

---

## Bob coin budget guide

| Job type | CVEs/run | Use `--max-coins` |
|----------|----------|-------------------|
| Triage (50 CVEs) | 50 | `150` |
| Java fix agent | 5 CVEs batch | `150` |
| Dockerfile fix agent | 1 CVE | `100` |
| PR creation | 1 PR | `100` |

Run `bash scripts/estimate-bob-coins.sh <your-cve-input.json>` to get a recommendation for
any input file size.

---

## Practical exercise (do this during study week)

1. Export your `BOBSHELL_API_KEY`
2. Build the reference container: `docker build -t bob-test -f docker/cve-spike/Dockerfile.bob-test .`
3. Run a simple headless test:
   ```bash
   docker run --rm -e BOBSHELL_API_KEY="${BOBSHELL_API_KEY}" bob-test \
     bob "list files in /workspace" --approval-mode yolo --output-format json
   ```
4. Pipe the CVE sample through the triage prompt:
   ```bash
   cat docs/spike/sample-cve-input.json | docker run --rm -i \
     -e BOBSHELL_API_KEY="${BOBSHELL_API_KEY}" \
     -v "$(pwd)/prompts:/prompts:ro" \
     bob-test \
     bob "$(cat prompts/triage.txt)" --approval-mode yolo --output-format json --max-coins 150 \
     | python3 scripts/normalise-bob-output.py
   ```
5. Confirm the output is a valid JSON array of CVE triage records.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Authentication timeout (3 minutes)` | settings.json has `"selectedType":"sso"` or is missing | Bake the settings.json block into your Dockerfile |
| `A license agreement is required` | `licenseConsent` is missing from settings.json | Add `"licenseConsent": true` |
| `BudgetExceededError` | `--max-coins` too low | Increase `--max-coins` or reduce CVE batch size |
| Bob hangs after 3 minutes | No `BOBSHELL_API_KEY` env var | Export `BOBSHELL_API_KEY` or check `--env-from-secret` binding |
| `response` field contains markdown, not raw JSON | Prompt did not enforce JSON-only output | Use `prompts/triage.txt` (already enforces this); or add "Output only raw JSON starting with [" to your prompt |
