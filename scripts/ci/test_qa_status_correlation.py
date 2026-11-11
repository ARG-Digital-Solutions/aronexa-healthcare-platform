#!/usr/bin/env python3
"""Offline negative-path tests for the actual Development-side QA status jq selector.

Runs without GitHub secrets, external network, source deployments, or a database.
The filter is extracted from the workflow under test, not reimplemented here.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import textwrap

ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / ".github/workflows/central-qa-handoff.yml").read_text(encoding="utf-8")
PREFIX = '''MATCH=$(jq -r --arg context "$CONTEXT" --arg request "$REQUEST_KEY" '\n'''
SUFFIX = '''\n            ' <<< "$STATUS_JSON")'''

assert SOURCE.count(PREFIX) == 1, "Missing or ambiguous production status selector"
start = SOURCE.index(PREFIX) + len(PREFIX)
end = SOURCE.index(SUFFIX, start)
STATUS_SELECTOR = SOURCE[start:end]
assert "contains($request)" not in STATUS_SELECTOR, "Substring request-ID match must be forbidden"
assert 'commits/$APP_SHA/statuses?per_page=100' in SOURCE, "Status must be fetched for the pinned commit"
assert 'if test "$MATCH" = "success"' in SOURCE, "Only success can mark the handoff complete"
assert 'if test "$MATCH" = "failure" || test "$MATCH" = "error"' in SOURCE, "Failures must stop the handoff"

PLAN_CONTEXT = "aronexa/central-qa/P01_T07_INT_REG_01"
REQUEST = "42595190272-2"


# Execute the production preflight before any token-bearing step.
job = SOURCE.split("  dispatch-and-await:\n", 1)[1]
job_settings, steps = job.split("    steps:\n", 1)
assert "\n    if:" not in job_settings, "A skipped required job can satisfy GitHub branch protection"
preflight, _ = steps.split("\n      - name: Dispatch exact SHA", 1)
assert "PR_DRAFT: ${{ toJSON(github.event.pull_request.draft) }}" in preflight
preflight_script = textwrap.dedent(preflight.split("        run: |\n", 1)[1])

def check_preflight(draft, labels, *, source_repo="example/product",
                    live_draft=None, live_sha=None, live_labels=None, live_state="open"):
    """Exercise the real production bash with a synthetic GitHub API response."""
    with TemporaryDirectory() as directory:
        temp = Path(directory)
        output = temp / "outputs"
        summary = temp / "summary"
        mock_gh = temp / "gh"
        if live_draft is None:
            live_draft = draft == "true"
        if live_sha is None:
            live_sha = "a" * 40
        if live_labels is None:
            live_labels = labels
        payload = {
            "state": live_state,
            "draft": live_draft,
            "head": {"sha": live_sha, "repo": {"full_name": "example/product"}},
            "labels": [{"name": x} for x in live_labels],
        }
        mock_gh.write_text(
            "#!/bin/sh\\n"
            "printf '%s\\\\n' '" + json.dumps(payload) + "'\\n",
            encoding="utf-8",
        )
        mock_gh.chmod(0o700)
        result = subprocess.run(
            ["bash", "-c", preflight_script],
            env={
                **os.environ,
                "PATH": str(temp) + os.pathsep + os.environ["PATH"],
                "GH_TOKEN": "synthetic-only",
                "PR_DRAFT": draft,
                "PR_LABELS": json.dumps(labels),
                "SOURCE_REPO": source_repo,
                "GITHUB_REPOSITORY": "example/product",
                "PR_NUMBER": "81",
                "APP_SHA": "a" * 40,
                "REQUEST_KEY": REQUEST,
                "GITHUB_OUTPUT": str(output),
                "GITHUB_STEP_SUMMARY": str(summary),
            },
            text=True,
            capture_output=True,
            check=False,
        )
        return result, output.read_text(encoding="utf-8") if output.exists() else ""

plan_label = "qa-plan:P01_T07_INT_REG_01"
result, outputs = check_preflight("true", [])
assert result.returncode == 0 and "Central QA deferred" in result.stdout, result
assert "dispatch=false\\n" in outputs, outputs
assert "plan_id=" not in outputs, outputs

for draft, labels, opts in [
    ("null", [], {}),
    ("true", [], {"live_draft": False}),
    ("true", [], {"live_sha": "b" * 40}),
    ("false", [], {"live_draft": True}),
    ("false", [], {"live_state": "closed"}),
    ("false", [], {}),
    ("false", [plan_label, "qa-plan:ANOTHER_PLAN"], {}),
    ("false", [plan_label], {"source_repo": "example/fork"}),
]:
    result, outputs = check_preflight(draft, labels, **opts)
    assert result.returncode != 0 and "dispatch=true" not in outputs, (result, outputs)

result, outputs = check_preflight("false", [plan_label])
assert result.returncode == 0, result
assert "dispatch=true\\n" in outputs, outputs
assert "plan_id=P01_T07_INT_REG_01\\n" in outputs, outputs
assert f"request_key={REQUEST}\\n" in outputs, outputs
print("PASS: ten draft/ready preflight cases incl stale states and exact plan")

# Independently prove a missing or changed live QA plan never dispatches.
for labels in ([], [plan_label, "qa-plan:UNAPPROVED_PLAN"]):
    result, outputs = check_preflight("false", [plan_label], live_labels=labels)
    assert result.returncode != 0 and "dispatch=true" not in outputs, (result, outputs)
print("PASS: live QA plan changes fail closed")

# The dispatch and await steps must be gated on successful ready-state preflight.
assert SOURCE.count("if: steps.plan.outputs.dispatch == 'true'") == 2
assert "LIVE_PR=$(gh api" in SOURCE
assert "PR_NUMBER: ${{ steps.plan.outputs.pr_number }}" in SOURCE
assert "PR_DRAFT: ${{ toJSON(github.event.pull_request.draft) }}" in SOURCE
print("PASS: draft blocks both privileged dispatch and result acceptance")

def receipt(record_id, request=REQUEST, state="success", *, context=PLAN_CONTEXT,
            description=None):
    if description is None:
        description = f"request={request}; qa={state}; release-not-authorized"
    return {
        "id": record_id, "context": context, "state": state,
        "description": description,
        "target_url": "https://github.com/ARG-Digital-Solutions/aronexa-qa-central/actions/runs/42597519458",
    }

def check_status(records, *, context=PLAN_CONTEXT, request=REQUEST):
    p = subprocess.run(
        ["jq", "-r", "--arg", "context", context, "--arg", "request", request, STATUS_SELECTOR],
        input=json.dumps(records),
        text=True,
        capture_output=True,
        check=False,
    )
    if p.returncode:
        raise AssertionError(f"Production jq filter failed: {p.stderr}")
    return p.stdout.strip()

tests = [
    ("existing INTREG01 reporter success remains recognized", [receipt(10)], "success"),
    ("exact reporter failure remains recognized", [receipt(10, state="failure")], "failure"),
    ("exact reporter error remains recognized", [receipt(10, state="error")], "error"),
    ("similar longer request ID is NOT accepted", [receipt(11, request=REQUEST+"0")], "NOT_FOUND"),
    ("request prefix without exact receipt is NOT accepted",
     [receipt(11, description=f"external prefix {REQUEST}; qa=success; release-not-authorized")],
     "NOT_FOUND"),
    ("wrong QA plan context is NOT accepted",
     [receipt(11, context="aronexa/central-qa/OTHER")], "NOT_FOUND"),
    ("status state and description must agree",
     [receipt(11, state="success", description=f"request={REQUEST}; qa=failure; release-not-authorized")],
     "NOT_FOUND"),
    ("missing release hold is NOT accepted",
     [receipt(11, description=f"request={REQUEST}; qa=success")], "NOT_FOUND"),
    ("extra trailing fields are NOT accepted",
     [receipt(11, description=f"request={REQUEST}; qa=success; release-not-authorized; extra=yes")],
     "NOT_FOUND"),
    ("missing description is NOT accepted",
     [{"id":11, "context":PLAN_CONTEXT, "state":"success", "description":None}], "NOT_FOUND"),
    ("empty list is NOT_FOUND", [], "NOT_FOUND"),
    ("other request success cannot override exact failure",
     [receipt(12, request=REQUEST+"0"), receipt(11, state="failure")], "failure"),
    ("most recent exact request status wins",
     [receipt(12, state="success"), receipt(10, state="failure")], "success"),
    ("most recent exact failure wins over an earlier exact success",
     [receipt(12, state="failure"), receipt(10)], "failure"),
    ("matching pending status is not treated as success",
     [receipt(11, state="pending")], "pending"),
    ("mixed invalid successes do not override correct exact success",
     [receipt(20, request=REQUEST+"0"), receipt(18, context="OTHER"), receipt(11)], "success"),
]
for label, records, expected in tests:
    outcome = check_status(records)
    assert outcome == expected, (label, outcome, expected)
    print(f"PASS: {label}")

print(f"PASS: all {len(tests)} exact-status regression cases")
