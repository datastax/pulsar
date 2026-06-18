# MCP Server Demo - Agentic CI Workflow

This file demonstrates how to use the Pulsar PR MCP Server with agentic CI-check capabilities.

## 🚀 Quick Start

After adding your GitHub token to Bob's MCP settings, restart Bob and try these commands:

### 1. Proactive PR Monitoring
```
"Scan recent PRs for CI issues"
```
**What Bob does:**
- Automatically checks the last 10 PRs
- Identifies which ones have CI failures or pending checks
- Prioritizes PRs needing immediate attention
- Reports: "Found 3 PRs with issues: #23456 (build failure), #23457 (test timeout)"

### 2. Intelligent CI Status Check
```
"Monitor CI status for PR #23456"
```
**What Bob does:**
- Fetches real-time CI check results
- Analyzes pass/fail/pending status
- Checks PR mergeability
- Provides recommendations: "⚠️ 2 CI checks failed. Build error in Java 11. Not mergeable due to conflicts."

### 3. Failure Pattern Analysis
```
"Analyze CI failures for PR #23456"
```
**What Bob does:**
- Categorizes failures: test, build, lint, timeout
- Identifies patterns across failed checks
- Provides targeted recommendations per category
- Reports: "🧪 Test failures in integration tests. 🔨 Build failure in Java 11 module."

### 4. Actionable Fix Suggestions
```
"Get fix suggestions for PR #23456"
```
**What Bob does:**
- Generates specific commands to run locally
- Provides debugging steps based on failure type
- Includes links to detailed CI logs
- Suggests: "Run `mvn test -Dtest=BrokerTest` locally. Check for missing dependencies."

## 🎯 Real-World Scenarios

### Scenario 1: Daily PR Health Check
**You:** "Check if any recent PRs have CI failures"

**Bob:** "Scanning last 10 PRs... Found 2 PRs with failures:
- PR #23456: Build failure in Java 11 tests (needs immediate attention)
- PR #23457: Lint errors in 3 files (easy fix)

Would you like me to analyze the failures?"

### Scenario 2: Before Merging a PR
**You:** "Is PR #23456 ready to merge?"

**Bob:** "Checking PR #23456...
✅ All 8 CI checks passed
✅ PR is mergeable (no conflicts)
✅ 2 approving reviews
⚠️ PR branch is 5 commits behind master - consider rebasing

Recommendation: Ready to merge, but rebase first to get latest changes."

### Scenario 3: Debugging CI Failures
**You:** "Why is PR #23456 failing CI?"

**Bob:** "Analyzing failures for PR #23456...

Failed Checks:
1. Java 11 Build (timeout)
   - Test: BrokerServiceTest.testProducerConsumer
   - Likely cause: Test hanging or infinite loop
   - Fix: Run `mvn test -Dtest=BrokerServiceTest` locally
   - Increase timeout or check for blocking operations

2. Checkstyle (style violations)
   - 5 formatting issues in PulsarClient.java
   - Fix: Run `mvn spotless:apply`
   - Auto-fixes all formatting

Would you like detailed logs for any check?"

### Scenario 4: Cherry-Pick Discovery
**You:** "What commits need to be cherry-picked to branch-3.0?"

**Bob:** "Analyzing commits between master and branch-3.0...

High Priority (3 commits with [backport] label):
- abc123: Fix memory leak in broker (PR #23450)
- def456: Security patch for authentication (PR #23451)
- ghi789: Critical bug fix in consumer (PR #23452)

Medium Priority (5 commits mentioning branch-3.0):
- jkl012: Performance improvement (PR #23453)
...

Would you like me to check if any of these have conflicts?"

## 🔧 Advanced Usage

### Continuous Monitoring
Set up Bob to periodically check:
```
"Every hour, scan recent PRs and alert me if any have been failing for more than 24 hours"
```

### Pattern Detection
```
"What are the most common CI failures in the last 20 PRs?"
```

### Pre-Merge Checklist
```
"Before I merge PR #23456, verify: CI passed, no conflicts, has approvals, branch is up to date"
```

## 📊 Understanding the Output

### CI Status Indicators
- ✅ **Passed**: All checks successful
- ⚠️ **Failed**: One or more checks failed
- ⏳ **Pending**: Checks still running
- 🚫 **Not Mergeable**: Conflicts or branch protection issues

### Priority Levels
- **High**: Immediate attention needed (build failures, security issues)
- **Medium**: Should be addressed soon (test failures, performance issues)
- **Low**: Nice to have (style issues, documentation)

## 🎓 Tips for Best Results

1. **Be Specific**: "Monitor PR #23456" is better than "Check PR"
2. **Ask Follow-ups**: Bob can drill down into specific failures
3. **Use Context**: "The CI is failing, what should I do?" works when discussing a PR
4. **Combine Commands**: "Scan recent PRs and analyze failures for any with issues"

## 🔗 Related Commands

- List PRs: "Show me open PRs in Pulsar"
- PR Details: "Analyze PR #23456"
- Diff Review: "Show me what changed in PR #23456"
- Commit Check: "Is commit abc123 in branch-3.0?"

## 🆘 Troubleshooting

If Bob can't access GitHub:
1. Verify GitHub token is set in MCP settings
2. Check token has `repo` and `read:org` permissions
3. Restart Bob after updating settings

If commands don't work:
1. Make sure you're in the Pulsar repository directory
2. Try: "cd ~/Work/repos/pulsar" first
3. Fetch latest changes: "git fetch --all"

---

**Pro Tip**: Bob learns from context. The more you interact about a specific PR, the better Bob understands what you need!