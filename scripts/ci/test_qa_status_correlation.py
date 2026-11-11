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

def check_preflight(draft, labels, *, source_repo="example/product"):
    with TemporaryDirectory() as directory:
        output = Path(directory) / "outputs"
        result = subprocess.run(
            ["bash", "-c", preflight_script],
            env={
                **os.environ,
                "PR_DRAFT": draft,
                "PR_LABELS": json.dumps(labels),
                "SOURCE_REPO": source_repo,
                "GITHUB_REPOSITORY": "example/product",
                "PR_NUMBER": "81",
                "APP_SHA": "a" * 40,
                "REQUEST_KEY": REQUEST,
                "GITHUB_OUTPUT": str(output),
            },
            text=True,
            capture_output=True,
            check=False,
        )
        return result, output.read_text(encoding="utf-8") if output.exists() else ""

plan_label = "qa-plan:P01_T07_INT_REG_01"
for draft in ("true", "null"):
    result, outputs = check_preflight(draft, [plan_label])
    assert result.returncode == 1 and "Central QA deferred" in result.stdout, result
    assert not outputs, outputs

for labels, source_repo in [
    ([], "example/product"),
    ([plan_label, "qa-plan:ANOTHER_PLAN"], "example/product"),
    ([plan_label], "example/fork"),
]:
    result, outputs = check_preflight("false", labels, source_repo=source_repo)
    assert result.returncode != 0 and not outputs, result

result, outputs = check_preflight("false", [plan_label])
assert result.returncode == 0, result
assert "plan_id=P01_T07_INT_REG_01\n" in outputs, outputs
assert f"request_key={REQUEST}\n" in outputs, outputs
print("PASS: six draft and ready-for-review preflight cases")

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
