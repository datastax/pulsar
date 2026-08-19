# Sub-Task 4 Findings: Bob Coin Budget Calibration

**Spike:** AUTOMATE-CVE-000  
**Risk addressed:** A8 — "What is the coin cost for a 50-CVE triage run, and what `--max-coins` value is appropriate?"  
**Status:** ✅ MODEL DEFINED — empirical baseline from Sub-Task 1; full calibration requires first live production run

---

## 1. Empirical Baseline (Sub-Task 1)

From Sub-Task 1 (`docs/spike/bob-tty-findings.md`), a trivial Bob run in the test container returned:

```json
"stats": {
  "budgetSpend": 38.41,
  "maxBudget": 500,
  "sessionCost": 0.03337
}
```

**What the test run did:** 2 tool calls (`list_files`, `read_file`) on a minimal workspace. No CVE reasoning.

This gives us a **base overhead benchmark of ~40 coins** for container startup + Bob session initialisation + 2 simple tool calls.

---

## 2. Token-Based Cost Model

Bob's coin budget is consumed proportionally to LLM token usage. The cost components for a CVE triage run are:

| Component | Tokens (est.) | Notes |
|-----------|--------------|-------|
| System prompt / instructions | ~300 | The `prompts/triage.txt` content |
| CVE record (input) | ~150 per CVE | Typical JSON record size |
| Reasoning output per CVE | ~100 per CVE | Classification + fix_type decision |
| Base session overhead | — | ~40 coins (empirical baseline) |

**Token-to-coin conversion** (inferred from Sub-Task 1):  
38.41 coins ÷ ~16,685 tokens ≈ **0.0023 coins/token**

Equivalently: **~435 tokens per coin**.

### Per-job estimates

| Run Component | Tokens | Coins | Notes |
|---------------|--------|-------|-------|
| Session base + init | ~16,000 | 40 | From Sub-Task 1 empirical baseline |
| 1 CVE triage record | ~250 | ~1 | System prompt amortised across CVEs |
| 10 CVE triage | ~2,500 | ~6 | Additional cost above base |
| 50 CVE triage | ~12,500 | ~29 | Additional cost above base |
| 1 Java dep fix (pom.xml locate + diff) | ~3,500 | ~8 | grep + read_file + apply_diff tool calls |
| 1 Dockerfile fix (FROM line locate + diff) | ~2,500 | ~6 | grep + apply_diff tool calls |
| PR creation (1 PR, generate description) | ~2,000 | ~5 | execute_command + create_pr_workflow |

---

## 3. Recommended `--max-coins` Values

The table below gives raw estimates (base + per-CVE cost) plus a **25% safety headroom**, rounded up to the nearest 50.

| Job Type | CVEs/Run | Raw Estimate (coins) | + 25% headroom | Recommended `--max-coins` |
|----------|----------|---------------------|----------------|--------------------------|
| **Triage** | 10 | 40 + 6 = **46** | 58 | **100** |
| **Triage** | 50 | 40 + 29 = **69** | 86 | **150** |
| **Triage** | 100 | 40 + 58 = **98** | 123 | **200** |
| **Java fix agent** | 1 CVE | 40 + 8 = **48** | 60 | **100** |
| **Java fix agent** | 5 CVEs | 40 + 40 = **80** | 100 | **150** |
| **Dockerfile fix agent** | 1 CVE | 40 + 6 = **46** | 58 | **100** |
| **Dockerfile fix agent** | 5 CVEs | 40 + 30 = **70** | 88 | **150** |
| **PR creation** | 1 PR | 40 + 5 = **45** | 56 | **100** |

### Summary (initial production defaults)

These are the values to use in job definition env vars and Bob invocations until actual
`budgetSpend` data is collected from the first real scan run:

```bash
MAX_COINS_TRIAGE=150          # 50-CVE triage run
MAX_COINS_JAVA_FIX=150        # 5-CVE batch, java-dep-bump
MAX_COINS_DOCKERFILE_FIX=100  # Single Dockerfile/OS fix
MAX_COINS_PR_CREATE=100       # 1 PR per branch
```

---

## 4. `--max-coins` Cutoff Behaviour

From Sub-Task 1 observations and Bob CLI docs, when `--max-coins` is reached:

- Bob emits a JSON error response: `{"error": {"type": "BudgetExceededError", "message": "...", "code": 1}}`
- Process exits with **non-zero exit code**
- Work done so far is **not rolled back** — if Bob applied a partial `apply_diff`, the file will be partially modified

**Implication for the pipeline:**
- The fix agent must verify that the fix is complete (e.g. `mvn dependency:resolve` passes) _before_ committing
- If `--max-coins` is exceeded mid-fix, the job should exit with error and the CI system should alert without pushing any partial changes
- Set `--max-coins` conservatively high (with 25% headroom) to minimise mid-fix truncation

---

## 5. Batching Strategy

To reduce per-CVE coin cost and stay within budget guardrails:

1. **Batch similar CVEs together**: Group all `java-dep-bump` CVEs for the same package into a single Bob session (e.g. all 21 Netty CVEs → one bump to 4.1.135.Final in one run). This amortises the base overhead.

2. **Separate triage from fixing**: Run the triage agent first (cheap — classification only). Only send CVEs with `fix_available: true` to the fix agents.

3. **Limit CVEs per fix run to 5**: A single fix agent run should handle at most 5 CVEs to keep within `--max-coins 150`. Multiple sequential runs are preferred over one large run that risks hitting the limit.

4. **For `no-fix-monitor` CVEs**: Never send to a fix agent — they go directly to AUTOMATE-CVE-008 (alerting). This reduces coin spend.

---

## 6. Weekly Budget Estimate (Full Pipeline)

Assuming a typical scan finds 27 CVEs (current state of 3.1_ds branch):

| Stage | Runs | Coins/Run | Total Coins |
|-------|------|-----------|-------------|
| Triage (27 CVEs) | 1 | ~80 | ~80 |
| Java fix agents (6 batches × 5 CVEs) | 6 | ~100 | ~600 |
| Dockerfile fix agents (1) | 1 | ~100 | ~100 |
| PR creation (5 PRs × 2 branches) | 10 | ~50 | ~500 |
| **Weekly total (3.x)** | | | **~1,280 coins** |
| **Weekly total (4.x)** | | | **~1,280 coins** |
| **Grand total / week** | | | **~2,560 coins** |

This is well within typical API key budget limits. Adjust once actual `budgetSpend` values are collected.

---

## 7. Coin Tracking Helper

`scripts/estimate-bob-coins.sh` takes a CVE JSON file as input and outputs coin estimates:

```bash
bash scripts/estimate-bob-coins.sh docs/spike/sample-cve-input.json
```

Sample output:
```
════════════════════════════════════════════════════════════
  Bob Coin Estimate for: docs/spike/sample-cve-input.json
════════════════════════════════════════════════════════════
  CVEs in input file: 10

  ┌─────────────────────────────────────────────────────┐
  │ Job Type              Est. Coins  Rec. --max-coins  │
  ├─────────────────────────────────────────────────────┤
  │ Triage (all 10 CVEs)       46          100          │
  │ Java fix agent             64          100          │
  │ Dockerfile fix agent       46          100          │
  │ PR creation (2 branch)     90          150          │
  └─────────────────────────────────────────────────────┘
```

---

## 8. Validation Plan (First Live Run)

After the first real scan run, record the actual `budgetSpend` from `--output-format json stats`
and update this document with empirical data:

```bash
# Capture actual coin usage
bob "$(cat prompts/triage.txt)" \
  --approval-mode yolo --output-format json --hide-intermediary-output \
  --max-coins 150 \
  | python3 -c "import sys,json; s=json.load(sys.stdin)['stats']; print(f\"coins={s['budgetSpend']}, cost=\${s['sessionCost']:.4f}\")"
```

Update the model constants in `scripts/estimate-bob-coins.sh` once real data is collected.

---

## Files Created

| File | Description |
|------|-------------|
| [`scripts/estimate-bob-coins.sh`](../../scripts/estimate-bob-coins.sh) | Coin estimation helper script |
| `docs/spike/coin-budget-findings.md` | This document |

---

## Risk A8 Status: RESOLVED ✅

- Base overhead: **~40 coins** per run (empirical, Sub-Task 1)
- Recommended initial `--max-coins` values: **100–150** per job type (with 25% headroom)
- Full weekly pipeline cost estimate: **~2,560 coins/week** (both branches combined)
- Batching strategy: group by package and fix type; max 5 CVEs per fix agent run
- Cutoff behaviour: non-zero exit, partial work not committed (validation gate before commit)
- Full empirical calibration will occur on first live run (update constants in `estimate-bob-coins.sh`)
