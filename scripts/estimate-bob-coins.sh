#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────────────────
# estimate-bob-coins.sh
#
# AUTOMATE-CVE pipeline — Bob coin estimation helper
#
# Estimates the coin budget needed to run a Bob triage or fix job against a CVE JSON file.
# Based on the token-based cost model documented in docs/spike/coin-budget-findings.md.
#
# Usage:
#   bash scripts/estimate-bob-coins.sh <path-to-cve-json>
#   bash scripts/estimate-bob-coins.sh docs/spike/sample-cve-input.json
#
# Output:
#   CVE count, estimated coin range (low/high), recommended --max-coins value
#
# Requirements:
#   - jq (for JSON parsing)
#   - python3 (for arithmetic)
# ─────────────────────────────────────────────────────────────────────────────────────────

set -euo pipefail

INPUT_FILE="${1:-}"

if [[ -z "${INPUT_FILE}" ]]; then
  echo "Usage: $0 <path-to-cve-json>"
  echo "Example: $0 docs/spike/sample-cve-input.json"
  exit 1
fi

if [[ ! -f "${INPUT_FILE}" ]]; then
  echo "ERROR: File not found: ${INPUT_FILE}"
  exit 1
fi

# ── Coin cost model (from docs/spike/coin-budget-findings.md) ─────────────────────────
# Based on Sub-Task 1 observation: a trivial 2-tool run consumed 38.41 coins.
# Triage run cost model (empirical + token-based estimation):
#
#   Base overhead (system prompt + Bob toolchain init): ~40 coins
#   Per-CVE cost (triage classification):               ~25 coins/CVE
#   Per-CVE cost (Java fix agent):                      ~80 coins/CVE
#   Per-CVE cost (Dockerfile fix agent):                ~60 coins/CVE
#   PR creation cost (per branch):                      ~50 coins
#
#   Safety headroom multiplier: 1.25 (25% buffer above estimate)

BASE_OVERHEAD=40
COINS_PER_CVE_TRIAGE=25
COINS_PER_CVE_JAVA_FIX=80
COINS_PER_CVE_DOCKERFILE_FIX=60
COINS_PER_PR=50
HEADROOM=1.25

# ── Count CVEs in the input file ──────────────────────────────────────────────────────
# Handles both top-level arrays and objects with a "cves" key
if command -v jq &>/dev/null; then
  # Try top-level array first
  CVE_COUNT=$(jq 'if type=="array" then length
                   elif type=="object" and has("cves") then .cves | length
                   else 0
                   end' "${INPUT_FILE}" 2>/dev/null || echo "0")
else
  # Fallback: count "cve_id" occurrences
  CVE_COUNT=$(grep -o '"cve_id"' "${INPUT_FILE}" | wc -l | tr -d ' ')
fi

if [[ "${CVE_COUNT}" -eq 0 ]]; then
  echo "WARN: Could not determine CVE count from ${INPUT_FILE} (got 0)"
  echo "      Assuming 10 CVEs for estimation."
  CVE_COUNT=10
fi

# ── Calculate estimates ──────────────────────────────────────────────────────────────
python3 - <<PYTHON
import math

cve_count    = ${CVE_COUNT}
base         = ${BASE_OVERHEAD}
per_triage   = ${COINS_PER_CVE_TRIAGE}
per_java_fix = ${COINS_PER_CVE_JAVA_FIX}
per_df_fix   = ${COINS_PER_CVE_DOCKERFILE_FIX}
per_pr       = ${COINS_PER_PR}
headroom     = ${HEADROOM}

def recommend(raw_estimate):
    """Round up to nearest 50, then apply headroom."""
    with_headroom = raw_estimate * headroom
    return int(math.ceil(with_headroom / 50) * 50)

# Triage job
triage_raw = base + (cve_count * per_triage)
triage_rec = recommend(triage_raw)

# Java fix agent (assume ~30% of CVEs are java-dep-bump type)
java_cves    = max(1, round(cve_count * 0.30))
java_raw     = base + (java_cves * per_java_fix)
java_rec     = recommend(java_raw)

# Dockerfile fix agent (assume ~10% of CVEs are dockerfile-change/os-base-bump)
df_cves      = max(1, round(cve_count * 0.10))
df_raw       = base + (df_cves * per_df_fix)
df_rec       = recommend(df_raw)

# PR creation (one per batch per branch, 2 branches)
pr_raw = base + (2 * per_pr)
pr_rec = recommend(pr_raw)

bar = "═" * 60
print(bar)
print(f"  Bob Coin Estimate for: ${INPUT_FILE}")
print(bar)
print(f"  CVEs in input file: {cve_count}")
print()
print(f"  ┌─────────────────────────────────────────────────────┐")
print(f"  │ Job Type              Est. Coins  Rec. --max-coins  │")
print(f"  ├─────────────────────────────────────────────────────┤")
print(f"  │ Triage (all {cve_count} CVEs)    {triage_raw:>6.0f}      {triage_rec:>5}          │")
print(f"  │ Java fix agent        {java_raw:>6.0f}      {java_rec:>5}          │")
print(f"  │ Dockerfile fix agent  {df_raw:>6.0f}      {df_rec:>5}          │")
print(f"  │ PR creation (2 branch){pr_raw:>6.0f}      {pr_rec:>5}          │")
print(f"  └─────────────────────────────────────────────────────┘")
print()
print(f"  Model assumptions:")
print(f"    Base overhead per run    : {base} coins")
print(f"    Per-CVE triage cost      : {per_triage} coins")
print(f"    Per-CVE Java fix cost    : {per_java_fix} coins")
print(f"    Per-CVE Dockerfile fix   : {per_df_fix} coins")
print(f"    Per-PR creation cost     : {per_pr} coins")
print(f"    Safety headroom          : {int((headroom-1)*100)}% buffer above estimate")
print()
print(f"  NOTE: These are estimates based on the token-cost model in")
print(f"        docs/spike/coin-budget-findings.md. Validate against")
print(f"        actual budgetSpend values from the first live run.")
print(bar)
PYTHON
