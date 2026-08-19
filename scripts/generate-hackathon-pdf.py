#!/usr/bin/env python3
"""
Generate the CVE AI Agent Triage Hackathon brief PDF.

Usage:
    uv run --no-project --python 3.12 \
        --with reportlab \
        scripts/generate-hackathon-pdf.py

Output:
    CVE-AI-Agent-Triage-Hackathon.pdf  (in the current directory)
"""

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.enums import TA_LEFT, TA_CENTER
from reportlab.lib import colors
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle,
    HRFlowable, KeepTogether
)

# ── Palette ────────────────────────────────────────────────────────────────
C_BLUE       = colors.HexColor("#1a4d8f")
C_BLUE_LIGHT = colors.HexColor("#f0f4ff")
C_PURPLE     = colors.HexColor("#7c5cd8")
C_GREEN      = colors.HexColor("#1a7f37")
C_GREEN_LIGHT= colors.HexColor("#f0fff4")
C_AMBER      = colors.HexColor("#9a6700")
C_AMBER_LIGHT= colors.HexColor("#fffbf0")
C_BORDER     = colors.HexColor("#e5e7eb")
C_MUTED      = colors.HexColor("#57606a")
C_TEXT       = colors.HexColor("#1f2328")
C_WHITE      = colors.white
C_SURFACE    = colors.HexColor("#f7f8fa")
C_RED_LIGHT  = colors.HexColor("#fff0f0")
C_RED        = colors.HexColor("#cf222e")

# ── Page setup ─────────────────────────────────────────────────────────────
PAGE_W, PAGE_H = A4
MARGIN = 18 * mm
DOC_W  = PAGE_W - 2 * MARGIN

# ── Shared cell paragraph styles ───────────────────────────────────────────
# Defined at module level so table builders can reference them directly.
_CELL_NORMAL = ParagraphStyle("cell_normal", fontName="Helvetica",
                               fontSize=8.5, textColor=C_TEXT, leading=12)
_CELL_SMALL  = ParagraphStyle("cell_small",  fontName="Helvetica",
                               fontSize=8,   textColor=C_TEXT, leading=11)
_CELL_MUTED  = ParagraphStyle("cell_muted",  fontName="Helvetica",
                               fontSize=8,   textColor=C_MUTED, leading=11)
_CELL_MONO   = ParagraphStyle("cell_mono",   fontName="Courier",
                               fontSize=7,   textColor=C_TEXT, leading=10,
                               wordWrap='CJK')
_CELL_MONO_SM= ParagraphStyle("cell_mono_sm",fontName="Courier",
                               fontSize=7,   textColor=C_MUTED, leading=10)
_CELL_BOLD   = ParagraphStyle("cell_bold",   fontName="Helvetica-Bold",
                               fontSize=8.5, textColor=C_WHITE, leading=12)
_CELL_CENTER = ParagraphStyle("cell_center", fontName="Helvetica",
                               fontSize=8.5, textColor=C_TEXT, leading=12,
                               alignment=TA_CENTER)


def p(text, style=None):
    """Wrap a string in a Paragraph so it wraps within table cells."""
    return Paragraph(text, style or _CELL_NORMAL)


def ph(text, style=None):
    """Header cell: white bold text."""
    return Paragraph(text, style or _CELL_BOLD)


def pm(text):
    """Monospace cell."""
    return Paragraph(text, _CELL_MONO)


def build_styles():
    base = getSampleStyleSheet()
    def S(name, **kw):
        return ParagraphStyle(name, **kw)

    return {
        "title":    S("title",    fontName="Helvetica-Bold",   fontSize=20, textColor=C_BLUE,
                       leading=24, spaceAfter=2),
        "subtitle": S("subtitle", fontName="Helvetica",        fontSize=11, textColor=C_MUTED,
                       leading=15, spaceAfter=10),
        "h2":       S("h2",       fontName="Helvetica-Bold",   fontSize=13, textColor=C_BLUE,
                       leading=17, spaceBefore=14, spaceAfter=4),
        "body":     S("body",     fontName="Helvetica",        fontSize=9.5, textColor=C_TEXT,
                       leading=14, spaceAfter=4),
        "small":    S("small",    fontName="Helvetica",        fontSize=8.5, textColor=C_MUTED,
                       leading=12),
        "mono_sm":  S("mono_sm",  fontName="Courier",          fontSize=8,   textColor=C_TEXT,
                       leading=12),
        "bullet":   S("bullet",   fontName="Helvetica",        fontSize=9.5, textColor=C_TEXT,
                       leading=14, leftIndent=12, bulletIndent=0, spaceAfter=2),
        "center":   S("center",   fontName="Helvetica",        fontSize=8.5, textColor=C_MUTED,
                       leading=12, alignment=TA_CENTER),
        "bold_body":S("bold_body",fontName="Helvetica-Bold",   fontSize=9.5, textColor=C_TEXT,
                       leading=14),
    }


def hr(width=DOC_W, color=C_BORDER, thickness=0.5):
    return HRFlowable(width=width, thickness=thickness, color=color, spaceAfter=6, spaceBefore=2)


def info_box(paragraphs, bg=C_BLUE_LIGHT, border=C_BLUE):
    """Wrap content in a coloured box using a single-cell Table."""
    tbl = Table([[item] for item in paragraphs], colWidths=[DOC_W - 2])
    tbl.setStyle(TableStyle([
        ("BACKGROUND",    (0, 0), (-1, -1), bg),
        ("BOX",           (0, 0), (-1, -1), 1,  border),
        ("LEFTPADDING",   (0, 0), (-1, -1), 10),
        ("RIGHTPADDING",  (0, 0), (-1, -1), 10),
        ("TOPPADDING",    (0, 0), (0,  0),   8),
        ("BOTTOMPADDING", (0,-1), (-1, -1),  8),
        ("TOPPADDING",    (0, 1), (-1, -2),  2),
        ("BOTTOMPADDING", (0, 0), (-1, -2),  2),
        ("ROWBACKGROUNDS",(0, 0), (-1, -1), [bg]),
    ]))
    return tbl


def bullet(text, style):
    return Paragraph(f"• {text}", style)


def build_pdf(output_path="CVE-AI-Agent-Triage-Hackathon.pdf"):
    doc = SimpleDocTemplate(
        output_path,
        pagesize=A4,
        leftMargin=MARGIN, rightMargin=MARGIN,
        topMargin=16*mm, bottomMargin=16*mm,
        title="Hackathon",
        author="DataStax Platform Engineering",
    )

    S = build_styles()
    story = []

    # ── COVER ────────────────────────────────────────────────────────────────
    story.append(Spacer(1, 6*mm))
    story.append(Paragraph("Hackathon", S["title"]))
    story.append(Paragraph(
        "CVE AI Agent Triage · datastax/pulsar · Branch: "
        "<font name='Courier'>hackathon/cve-ai-agent-triage</font>",
        S["subtitle"]))
    story.append(hr(color=C_BLUE, thickness=1.5))
    story.append(Spacer(1, 3*mm))

    # ── OVERVIEW ─────────────────────────────────────────────────────────────
    story.append(Paragraph("Overview", S["h2"]))
    story.append(Paragraph(
        "This hackathon implements the remaining phases of the AUTOMATE-CVE pipeline for the "
        "Apache Pulsar container image (branches <font name='Courier'>3.1_ds</font> and "
        "<font name='Courier'>4.0_ds</font>). The goal is for each pair to ship "
        "<b>one working IBM Code Engine job that can be triggered manually</b> by the end of "
        "the hackathon. All code runs in a single shared IBM Cloud account.",
        S["body"]))
    story.append(Spacer(1, 2*mm))

    # ── PIPELINE FLOW TABLE ───────────────────────────────────────────────────
    # All cells are Paragraph objects so text wraps within column boundaries.
    W_STEP = 36*mm
    W_SVC  = 38*mm
    W_WHAT = DOC_W - W_STEP - W_SVC

    flow_data = [
        [ph("Step"), ph("What happens"), ph("IBM Service")],
        [pm("1 · Scan"),    p("Trivy + Snyk scan the Pulsar container image"),         pm("Code Engine (cron)")],
        [pm("2 · Merge"),   p("Bob CLI merges and normalises scan JSON"),               pm("IBM Bob v1.0.6")],
        [pm("3 · Triage"),  p("Bob classifies each CVE by fix type"),                  pm("Code Engine job")],
        [pm("4 · Fix"),     p("Bob applies pom.xml / Dockerfile fixes in a branch"),   pm("Code Engine job")],
        [pm("5 · PR"),      p("Bob creates a draft PR to 3.1_ds / 4.0_ds"),            pm("GitHub + Bob")],
        [pm("6 · Observe"), p("Static dashboard + Slack alerts + CI gate"),             pm("COS / Event Streams")],
    ]
    flow_tbl = Table(flow_data, colWidths=[W_STEP, W_WHAT, W_SVC])
    flow_tbl.setStyle(TableStyle([
        ("BACKGROUND",    (0, 0), (-1, 0),  C_BLUE),
        ("ROWBACKGROUNDS",(0, 1), (-1, -1), [C_WHITE, C_SURFACE]),
        ("BOX",           (0, 0), (-1, -1), 0.5, C_BORDER),
        ("INNERGRID",     (0, 0), (-1, -1), 0.5, C_BORDER),
        ("TOPPADDING",    (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING",   (0, 0), (-1, -1), 7),
        ("RIGHTPADDING",  (0, 0), (-1, -1), 7),
        ("VALIGN",        (0, 0), (-1, -1), "TOP"),
    ]))
    story.append(flow_tbl)
    story.append(Spacer(1, 4*mm))

    # ── TEAM ASSIGNMENTS ─────────────────────────────────────────────────────
    story.append(Paragraph("Team Assignments", S["h2"]))

    pairs = [
        ("Pair A", "CVE-002, CVE-003", "pair-a/cve-triage",
         "Deploy the cron-scheduled scanner jobs on Code Engine. "
         "Build the Bob-powered Triage Agent that reads scan JSON from COS, "
         "classifies each CVE by fix type using prompts/triage.txt, "
         "and uploads the triage JSON back to COS.",
         [
             "Two cron jobs deployed: pair-a-cve-scanner-3x (Mon 02:00 UTC) and pair-a-cve-scanner-4x (Tue 02:00 UTC)",
             "Triage job pair-a-cve-triage-3x triggers manually and uploads validated triage JSON to COS",
             "infra/cve-automation/job-definitions/cve-triage-job.yaml committed",
         ],
         "infra/cve-automation/job-definitions/cve-scanner-job.yaml  +  prompts/triage.txt"),
        ("Pair B", "CVE-004, CVE-005, CVE-006", "pair-b/cve-fix-agents",
         "Build two Bob fix agent jobs. The Java fix agent reads triage JSON, "
         "locates the dependency version property in pom.xml, applies a bump with "
         "apply_diff, and validates with mvn dependency:resolve. "
         "The Dockerfile fix agent updates all three FROM alpine:3.21 lines atomically. "
         "Both jobs open a draft PR via Bob's create_pr_workflow.",
         [
             "Branch bot/cve-fix/3.1_ds/CVE-2026-54512 pushed, mvn dependency:resolve exits 0",
             "All 3 FROM alpine:3.21 lines in docker/pulsar/Dockerfile updated atomically",
             "Draft PR opened in GitHub from fix branch targeting 3.1_ds with CVE table in description",
         ],
         "prompts/fix-java-dep.txt  +  prompts/fix-dockerfile.txt  +  docker/cve-spike/e2e-dry-run.sh"),
        ("Pair C", "CVE-007, CVE-008, CVE-009, CVE-010", "pair-c/cve-observability",
         "Build the observability and governance layer. Generate a static HTML dashboard "
         "from COS triage JSONs and publish to the cve-dashboard COS bucket. "
         "Wire an IBM Cloud Functions consumer to send Slack alerts on cve.scan.completed events. "
         "Add a CI gate step to pulsar-ci.yaml that fails if CRITICAL CVEs exceed the threshold "
         "in .cve-policy.yaml. Write the operator runbook.",
         [
             "Dashboard URL accessible, seeded with docs/spike/sample-cve-input.json data",
             "Slack message fires when test event published to cve.scan.completed topic",
             "PR to 3.1_ds fails CI when CRITICAL CVE threshold is exceeded",
             "docs/cve-automation-runbook.md covers 5 failure modes",
         ],
         "docs/spike/ADR-001-cve-pipeline-architecture.md  +  docs/spike/unified-cve-triage-schema.json"),
    ]

    pair_colors = [C_BLUE, C_PURPLE, C_GREEN]
    pair_bgs    = [C_BLUE_LIGHT, colors.HexColor("#f8f0ff"), C_GREEN_LIGHT]

    for (pair_name, tickets, branch, desc, dod, start), bg, bc in zip(pairs, pair_bgs, pair_colors):
        col1 = [
            Paragraph(pair_name, ParagraphStyle(
                f"ph_{pair_name}", fontName="Helvetica-Bold", fontSize=11, textColor=bc, leading=14)),
            Paragraph(tickets, ParagraphStyle(
                f"pt_{pair_name}", fontName="Helvetica", fontSize=8.5, textColor=C_MUTED, leading=12)),
            Spacer(1, 4),
            Paragraph("Branch:", ParagraphStyle(
                f"pbl_{pair_name}", fontName="Helvetica", fontSize=8, textColor=C_MUTED, leading=11)),
            Paragraph(branch, ParagraphStyle(
                f"pb_{pair_name}", fontName="Courier", fontSize=7.5, textColor=C_MUTED, leading=11)),
        ]
        col2_items = [Paragraph(desc, S["body"]), Spacer(1, 4)]
        col2_items.append(Paragraph("<b>Definition of done:</b>", S["bold_body"]))
        for d in dod:
            col2_items.append(bullet(d, S["bullet"]))
        col2_items.append(Spacer(1, 4))
        col2_items.append(Paragraph("<b>Start with:</b>", S["bold_body"]))
        col2_items.append(Paragraph(start, ParagraphStyle(
            f"si_{pair_name}", fontName="Courier", fontSize=8, textColor=C_TEXT, leading=12)))

        pair_tbl = Table([[col1, col2_items]], colWidths=[48*mm, DOC_W - 50*mm])
        pair_tbl.setStyle(TableStyle([
            ("BACKGROUND",    (0, 0), (-1, -1), bg),
            ("BOX",           (0, 0), (-1, -1), 1,  bc),
            ("LEFTPADDING",   (0, 0), (-1, -1), 10),
            ("RIGHTPADDING",  (0, 0), (-1, -1), 10),
            ("TOPPADDING",    (0, 0), (-1, -1), 10),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 10),
            ("VALIGN",        (0, 0), (-1, -1), "TOP"),
            ("LINEAFTER",     (0, 0), (0,  -1), 0.5, bc),
        ]))
        story.append(KeepTogether([pair_tbl, Spacer(1, 4*mm)]))

    # ── GIT WORKFLOW ─────────────────────────────────────────────────────────
    story.append(Paragraph("Git Workflow", S["h2"]))
    story.append(info_box([
        Paragraph(
            "<b>All work branches off hackathon/cve-ai-agent-triage — not master, not 3.1_ds.</b>",
            ParagraphStyle("gw", fontName="Helvetica-Bold", fontSize=9.5, textColor=C_BLUE, leading=13)),
        Paragraph(
            "PRs target hackathon/cve-ai-agent-triage when your job is working and the YAML is committed.",
            ParagraphStyle("gws", fontName="Helvetica", fontSize=9, textColor=C_BLUE, leading=13)),
    ], bg=C_BLUE_LIGHT, border=C_BLUE))
    story.append(Spacer(1, 3*mm))

    git_lines = [
        "# Clone and check out the hackathon base branch",
        "git clone https://github.com/datastax/pulsar.git &amp;&amp; cd pulsar",
        "git fetch origin",
        "git checkout hackathon/cve-ai-agent-triage",
        " ",
        "# Create your pair branch from the hackathon branch",
        "git checkout -b pair-a/cve-triage          # Pair A",
        "git checkout -b pair-b/cve-fix-agents       # Pair B",
        "git checkout -b pair-c/cve-observability    # Pair C",
        " ",
        "# Push your work as you go",
        "git push origin pair-a/cve-triage",
    ]
    git_tbl = Table([[Paragraph(line, S["mono_sm"])] for line in git_lines],
                    colWidths=[DOC_W - 2])
    git_tbl.setStyle(TableStyle([
        ("BACKGROUND",    (0, 0), (-1, -1), C_SURFACE),
        ("BOX",           (0, 0), (-1, -1), 0.5, C_BORDER),
        ("LEFTPADDING",   (0, 0), (-1, -1), 10),
        ("RIGHTPADDING",  (0, 0), (-1, -1), 10),
        ("TOPPADDING",    (0, 0), (0,  0),   6),
        ("BOTTOMPADDING", (0,-1), (-1, -1),  6),
        ("TOPPADDING",    (0, 1), (-1, -1),  1),
        ("BOTTOMPADDING", (0, 0), (-1, -2),  1),
    ]))
    story.append(git_tbl)
    story.append(Spacer(1, 4*mm))

    # ── DAY-1 CHECKLIST ──────────────────────────────────────────────────────
    story.append(Paragraph("Day-1 Checklist (run before writing any code)", S["h2"]))

    _chk  = ParagraphStyle("chk_box",  fontName="Helvetica", fontSize=10, textColor=C_TEXT,  leading=13)
    _desc = ParagraphStyle("chk_desc", fontName="Helvetica", fontSize=8.5,textColor=C_TEXT,  leading=13)

    checklist = [
        [Paragraph("[ ]", _chk), Paragraph("Check out hackathon/cve-ai-agent-triage and create your pair branch (see Git Workflow above)", _desc)],
        [Paragraph("[ ]", _chk), Paragraph('<font name="Courier">ibmcloud login --apikey "${IBM_CLOUD_API_KEY}" -r us-south</font>', _desc)],
        [Paragraph("[ ]", _chk), Paragraph('<font name="Courier">ibmcloud ce project select --name cve-automation</font>', _desc)],
        [Paragraph("[ ]", _chk), Paragraph('<font name="Courier">ibmcloud cr login</font>', _desc)],
        [Paragraph("[ ]", _chk), Paragraph('<font name="Courier">docker pull icr.io/&lt;NAMESPACE&gt;/cve-scanner:hackathon-v1</font>  — image tag in docs/hackathon/shared-image-ref.md', _desc)],
        [Paragraph("[ ]", _chk), Paragraph('<font name="Courier">ibmcloud sm secrets --instance-id &lt;SM_INSTANCE_ID&gt;</font>  — confirm all 4 secrets are listed', _desc)],
        [Paragraph("[ ]", _chk), Paragraph("Read docs/hackathon/README.md and your pair's study-*.md cheat sheet", _desc)],
    ]
    check_tbl = Table(checklist, colWidths=[7*mm, DOC_W - 7*mm])
    check_tbl.setStyle(TableStyle([
        ("TOPPADDING",    (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING",   (0, 0), (0, -1),  4),
        ("LEFTPADDING",   (1, 0), (1, -1),  6),
        ("RIGHTPADDING",  (0, 0), (-1, -1), 6),
        ("VALIGN",        (0, 0), (-1, -1), "MIDDLE"),
        ("ROWBACKGROUNDS",(0, 0), (-1, -1), [C_WHITE, C_SURFACE]),
        ("BOX",           (0, 0), (-1, -1), 0.5, C_BORDER),
        ("INNERGRID",     (0, 0), (-1, -1), 0.5, C_BORDER),
    ]))
    story.append(check_tbl)
    story.append(Spacer(1, 4*mm))

    # ── KEY FILES ────────────────────────────────────────────────────────────
    story.append(Paragraph("Key Files on the Branch", S["h2"]))

    # col 0: file path (Courier), col 1: description (Helvetica), col 2: who
    W_FILE = 82*mm
    W_DESC = DOC_W - W_FILE - 14*mm
    W_WHO  = 14*mm

    files_header = [ph("File / Directory"), ph("What it is"), ph("Who")]
    files_rows = [
        ("docs/hackathon/README.md",                  "Start here: assignments, git workflow, day-1 checklist",           "All"),
        ("docs/hackathon/shared-image-ref.md",        "Pre-built scanner image tag + bucket names (fill before day 1)",   "All"),
        ("docs/hackathon/study-bob.md",               "IBM Bob cheat sheet (install, auth, invocation, troubleshoot)",    "All"),
        ("docs/hackathon/study-code-engine.md",       "IBM Code Engine cheat sheet (jobs, secrets, cron, YAML)",          "All"),
        ("docs/hackathon/study-secrets-manager.md",   "IBM Secrets Manager cheat sheet",                                  "All"),
        ("docs/hackathon/study-trivy.md",             "Trivy + Snyk cheat sheet (scan commands, JSON formats)",           "Pair A"),
        ("docs/spike/ADR-001-*.md",                   "Full pipeline architecture — MUST READ",                           "All"),
        ("docs/spike/unified-cve-triage-schema.json", "The data contract every job produces/consumes",                    "All"),
        ("docs/spike/sample-cve-input.json",          "10 real CVEs for local testing",                                   "All"),
        ("docs/spike/bob-tty-findings.md",            "Bob auth details + confirmed invocation patterns",                  "All"),
        ("docs/spike/coin-budget-findings.md",        "Coin cost model, --max-coins recommendations",                     "All"),
        ("prompts/triage.txt",                        "Canonical CVE triage prompt (production-ready)",                   "Pair A"),
        ("prompts/fix-java-dep.txt",                  "Java pom.xml dep-bump agent prompt",                               "Pair B"),
        ("prompts/fix-dockerfile.txt",                "Dockerfile FROM-bump / apk fix agent prompt",                      "Pair B"),
        ("scripts/normalise-bob-output.py",           "Bob output parser + schema validator",                             "All"),
        ("scripts/estimate-bob-coins.sh",             "Coin estimator: CVE JSON → --max-coins value",                     "All"),
        ("docker/cve-spike/Dockerfile.bob-test",      "Validated headless Bob base image — extend for your agents",       "All"),
        ("docker/cve-spike/e2e-dry-run.sh",           "7-step pipeline dry-run reference script",                         "Pair B"),
        ("infra/cve-automation/job-definitions/<br/>cve-scanner-job.yaml",
                                                      "Code Engine job YAML template — copy and adapt",                   "All"),
        ("infra/cve-automation/secrets/<br/>required-secrets.md",
                                                      "4 secrets + ibmcloud CLI provisioning commands",                   "All"),
    ]
    files_data = [files_header] + [
        [pm(f), p(d, _CELL_SMALL), p(w, _CELL_SMALL)] for f, d, w in files_rows
    ]
    files_tbl = Table(files_data, colWidths=[W_FILE, W_DESC, W_WHO])
    files_tbl.setStyle(TableStyle([
        ("BACKGROUND",    (0, 0), (-1, 0),  C_BLUE),
        ("ROWBACKGROUNDS",(0, 1), (-1, -1), [C_WHITE, C_SURFACE]),
        ("BOX",           (0, 0), (-1, -1), 0.5, C_BORDER),
        ("INNERGRID",     (0, 0), (-1, -1), 0.5, C_BORDER),
        ("TOPPADDING",    (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ("LEFTPADDING",   (0, 0), (-1, -1), 6),
        ("RIGHTPADDING",  (0, 0), (-1, -1), 6),
        ("VALIGN",        (0, 0), (-1, -1), "TOP"),
    ]))
    story.append(files_tbl)
    story.append(Spacer(1, 4*mm))

    # ── SHARED RESOURCES ─────────────────────────────────────────────────────
    story.append(Paragraph("Shared IBM Cloud Resources", S["h2"]))

    W_RES_KEY = 55*mm
    W_RES_VAL = DOC_W - W_RES_KEY

    resources_rows = [
        ("IBM Cloud region",         "us-south"),
        ("Code Engine project",      "cve-automation"),
        ("COS bucket — scan output", "cve-reports  (scanner writes → triage agent reads)"),
        ("COS bucket — triage",      "cve-triage  (triage writes → fix agents read)"),
        ("COS bucket — dashboard",   "cve-dashboard  (Pair C publishes static HTML here)"),
        ("Event Streams topics",     "cve.scan.completed · cve.triage.completed · cve.fix.pr_created · cve.critical_found"),
        ("Job name prefix — Pair A", "pair-a-cve-*    e.g. pair-a-cve-triage-3x"),
        ("Job name prefix — Pair B", "pair-b-cve-*    e.g. pair-b-cve-fix-java-3x"),
        ("Job name prefix — Pair C", "pair-c-cve-*    e.g. pair-c-cve-dashboard-gen"),
    ]
    res_data = [[ph("Resource"), ph("Value")]] + [
        [p(k), pm(v)] for k, v in resources_rows
    ]
    res_tbl = Table(res_data, colWidths=[W_RES_KEY, W_RES_VAL])
    res_tbl.setStyle(TableStyle([
        ("BACKGROUND",    (0, 0), (-1, 0),  C_BLUE),
        ("ROWBACKGROUNDS",(0, 1), (-1, -1), [C_WHITE, C_SURFACE]),
        ("BOX",           (0, 0), (-1, -1), 0.5, C_BORDER),
        ("INNERGRID",     (0, 0), (-1, -1), 0.5, C_BORDER),
        ("TOPPADDING",    (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING",   (0, 0), (-1, -1), 7),
        ("RIGHTPADDING",  (0, 0), (-1, -1), 7),
        ("VALIGN",        (0, 0), (-1, -1), "TOP"),
    ]))
    story.append(res_tbl)
    story.append(Spacer(1, 4*mm))

    # ── SECRETS ──────────────────────────────────────────────────────────────
    story.append(Paragraph("Provisioned Secrets (IBM Secrets Manager)", S["h2"]))

    W_SEC_NAME = 46*mm
    W_SEC_ENV  = 50*mm
    W_SEC_USE  = DOC_W - W_SEC_NAME - W_SEC_ENV

    secrets_rows = [
        ("bob-api-key",        "BOBSHELL_API_KEY",    "All pairs — Bob CLI authentication"),
        ("snyk-token",         "SNYK_TOKEN",          "Pair A — Snyk container scan"),
        ("ibm-cloud-api-key",  "IBM_CLOUD_API_KEY",   "All pairs — COS + ICR access"),
        ("github-pat",         "GITHUB_PAT",          "Pair B — push fix branches + create PRs"),
        ("slack-webhook-url",  "SLACK_WEBHOOK_URL",   "Pair C — Slack notification alerts"),
    ]
    sec_data = [[ph("Secret name"), ph("Env var"), ph("Used by")]] + [
        [pm(n), pm(e), p(u)] for n, e, u in secrets_rows
    ]
    sec_tbl = Table(sec_data, colWidths=[W_SEC_NAME, W_SEC_ENV, W_SEC_USE])
    sec_tbl.setStyle(TableStyle([
        ("BACKGROUND",    (0, 0), (-1, 0),  C_BLUE),
        ("ROWBACKGROUNDS",(0, 1), (-1, -1), [C_WHITE, C_SURFACE]),
        ("BOX",           (0, 0), (-1, -1), 0.5, C_BORDER),
        ("INNERGRID",     (0, 0), (-1, -1), 0.5, C_BORDER),
        ("TOPPADDING",    (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING",   (0, 0), (-1, -1), 7),
        ("RIGHTPADDING",  (0, 0), (-1, -1), 7),
        ("VALIGN",        (0, 0), (-1, -1), "TOP"),
    ]))
    story.append(sec_tbl)

    story.append(Spacer(1, 2*mm))
    story.append(info_box([
        Paragraph(
            "<b>Bind secrets to your Code Engine job:</b>  "
            "<font name='Courier' size=8>ibmcloud ce job update --name &lt;YOUR_JOB&gt; "
            "--env-from-secret bob-api-key:BOBSHELL_API_KEY "
            "--env-from-secret ibm-cloud-api-key:IBM_CLOUD_API_KEY</font>",
            ParagraphStyle("tip", fontName="Helvetica", fontSize=8.5, textColor=C_AMBER, leading=13)),
    ], bg=C_AMBER_LIGHT, border=C_AMBER))
    story.append(Spacer(1, 4*mm))

    # ── STUDY WEEK ───────────────────────────────────────────────────────────
    story.append(Paragraph("Study Week — What to Read", S["h2"]))

    W_NUM   = 7*mm
    W_FILE2 = 80*mm
    W_CVR   = DOC_W - W_NUM - W_FILE2 - 18*mm
    W_TIME  = 18*mm

    study_rows = [
        ("1", "docs/hackathon/study-bob.md",
              "IBM Bob: install, auth, headless invocation, troubleshooting",        "25 min"),
        ("2", "docs/hackathon/study-code-engine.md",
              "Code Engine: create jobs, bind secrets, cron, deploy from YAML",      "20 min"),
        ("3", "docs/hackathon/study-secrets-manager.md",
              "Secrets Manager: view, bind, create secrets",                         "15 min"),
        ("4", "docs/hackathon/study-trivy.md",
              "Trivy + Snyk: scan commands, JSON format, schema differences",        "20 min"),
        ("5", "docs/spike/ADR-001-cve-pipeline-architecture.md",
              "Full pipeline architecture — required for all pairs",                 "15 min"),
    ]
    study_data = [[ph("#"), ph("File"), ph("Covers"), ph("Time")]] + [
        [p(n, _CELL_CENTER), pm(f), p(c), p(t, _CELL_CENTER)]
        for n, f, c, t in study_rows
    ]
    study_tbl = Table(study_data, colWidths=[W_NUM, W_FILE2, W_CVR, W_TIME])
    study_tbl.setStyle(TableStyle([
        ("BACKGROUND",    (0, 0), (-1, 0),  C_BLUE),
        ("ROWBACKGROUNDS",(0, 1), (-1, -1), [C_WHITE, C_SURFACE]),
        ("BOX",           (0, 0), (-1, -1), 0.5, C_BORDER),
        ("INNERGRID",     (0, 0), (-1, -1), 0.5, C_BORDER),
        ("TOPPADDING",    (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING",   (0, 0), (-1, -1), 7),
        ("RIGHTPADDING",  (0, 0), (-1, -1), 7),
        ("VALIGN",        (0, 0), (-1, -1), "TOP"),
    ]))
    story.append(study_tbl)
    story.append(Spacer(1, 2*mm))
    story.append(Paragraph(
        "After reading the above, read <b>your pair's starting material files</b> listed in "
        "the Team Assignments section. Engineers already know Docker and GitHub — those are "
        "not covered in the study guides.",
        S["small"]))
    story.append(Spacer(1, 4*mm))

    # ── KNOWN CVES ───────────────────────────────────────────────────────────
    story.append(Paragraph("Known CVEs in the Current Image (inputs for Pair B)", S["h2"]))

    W_PKG  = 38*mm
    W_INST = 28*mm
    W_SEV  = 18*mm
    W_CNT  = 14*mm
    W_FIX  = 30*mm
    W_FIXV = DOC_W - W_PKG - W_INST - W_SEV - W_CNT - W_FIX

    cves_rows = [
        ("jackson-databind",  "2.18.6",        "CRITICAL", "2",  "java-dep-bump",  "2.18.8"),
        ("io.netty:*",        "4.1.131.Final", "HIGH",     "21", "java-dep-bump",  "4.1.135.Final"),
        ("protobuf (Python)", "3.20.3",        "HIGH",     "2",  "no-fix-monitor", "Blocked (pulsar-client pins ≤3.20.3)"),
        ("tar",               "1.35",          "MEDIUM",   "1",  "no-fix-monitor", "None available"),
        ("Alpine 3.21 OS",    "—",             "—",        "0",  "—",              "✓ Clean"),
    ]
    sev_colors = {
        "CRITICAL": C_RED_LIGHT,
        "HIGH":     colors.HexColor("#fff8e6"),
        "MEDIUM":   colors.HexColor("#fffbf0"),
        "—":        C_GREEN_LIGHT,
    }
    cve_data = [[ph("Package"), ph("Installed"), ph("Severity"), ph("CVEs"), ph("Fix type"), ph("Fix version")]] + [
        [p(pkg), p(inst), p(sev), p(cnt, _CELL_CENTER), pm(fix), p(fixv)]
        for pkg, inst, sev, cnt, fix, fixv in cves_rows
    ]
    cve_style = [
        ("BACKGROUND",    (0, 0), (-1, 0),  C_BLUE),
        ("BOX",           (0, 0), (-1, -1), 0.5, C_BORDER),
        ("INNERGRID",     (0, 0), (-1, -1), 0.5, C_BORDER),
        ("TOPPADDING",    (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ("LEFTPADDING",   (0, 0), (-1, -1), 6),
        ("RIGHTPADDING",  (0, 0), (-1, -1), 6),
        ("VALIGN",        (0, 0), (-1, -1), "TOP"),
    ]
    for i, (_, _, sev, *_rest) in enumerate(cves_rows, start=1):
        bg = sev_colors.get(sev, C_WHITE)
        cve_style.append(("BACKGROUND", (0, i), (-1, i), bg))

    cve_tbl = Table(cve_data, colWidths=[W_PKG, W_INST, W_SEV, W_CNT, W_FIX, W_FIXV])
    cve_tbl.setStyle(TableStyle(cve_style))
    story.append(cve_tbl)
    story.append(Spacer(1, 4*mm))

    # ── COIN BUDGET ──────────────────────────────────────────────────────────
    story.append(Paragraph("Bob Coin Budget Reference", S["h2"]))

    W_JOB   = 70*mm
    W_CVES  = 35*mm
    W_COINS = DOC_W - W_JOB - W_CVES

    coins_rows = [
        ("Triage (50 CVEs)",     "50",       "150"),
        ("Java fix agent",       "5 batch",  "150"),
        ("Dockerfile fix agent", "1 CVE",    "100"),
        ("PR creation",          "1 PR",     "100"),
    ]
    coins_data = [[ph("Job type"), ph("CVEs / run"), ph("Recommended --max-coins")]] + [
        [p(jt), p(cv, _CELL_CENTER), p(mc, _CELL_CENTER)]
        for jt, cv, mc in coins_rows
    ]
    coins_tbl = Table(coins_data, colWidths=[W_JOB, W_CVES, W_COINS])
    coins_tbl.setStyle(TableStyle([
        ("BACKGROUND",    (0, 0), (-1, 0),  C_BLUE),
        ("ROWBACKGROUNDS",(0, 1), (-1, -1), [C_WHITE, C_SURFACE]),
        ("BOX",           (0, 0), (-1, -1), 0.5, C_BORDER),
        ("INNERGRID",     (0, 0), (-1, -1), 0.5, C_BORDER),
        ("TOPPADDING",    (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING",   (0, 0), (-1, -1), 7),
        ("RIGHTPADDING",  (0, 0), (-1, -1), 7),
        ("VALIGN",        (0, 0), (-1, -1), "TOP"),
    ]))
    story.append(coins_tbl)
    story.append(Spacer(1, 2*mm))
    story.append(Paragraph(
        "Run  <font name='Courier'>bash scripts/estimate-bob-coins.sh &lt;cve-input.json&gt;</font>  "
        "to get a recommendation for any input file size. Base overhead is ~40 coins per run. "
        "Full pipeline estimated cost: ~2,560 coins/week (both branches combined).",
        S["small"]))

    # ── FOOTER ───────────────────────────────────────────────────────────────
    story.append(Spacer(1, 6*mm))
    story.append(hr(color=C_BORDER))
    story.append(Paragraph(
        "Made with IBM Bob  ·  datastax/pulsar  ·  Branch: hackathon/cve-ai-agent-triage",
        S["center"]))

    doc.build(story)
    print(f"PDF written to: {output_path}")


if __name__ == "__main__":
    build_pdf()
