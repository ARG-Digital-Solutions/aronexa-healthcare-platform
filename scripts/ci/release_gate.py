#!/usr/bin/env python3
"""Fail-closed structural customer-pilot evidence check. Does not deploy or authenticate signatures."""
import argparse
import json
import re
import sys
from pathlib import Path

APP_CHECKS = (
    "prerequisites", "clean_revision", "docs", "source_scope",
    "tracked_secrets", "docker_local", "compose_config",
    "backend_testcontainers", "frontend_build_and_tests",
    "npm_high_critical_audit", "release_gate_unit_tests"
)
QA_CHECKS = ("synthetic_localhost", "verified_playwright")
REVIEW_CHECKS = (
    "independent_security_review", "tenant_branch_role_rls",
    "clinical_prescription_review", "privacy_and_consent",
    "windows_clean_install", "offline_license_and_expiry",
    "encrypted_backup_restore", "rollback_rehearsal",
    "customer_uat", "critical_high_defects_resolved"
)
SHA = re.compile(r"^[0-9a-f]{40}$")

class GateError(ValueError):
    pass

def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))

def require(condition, message):
    if not condition:
        raise GateError(message)

def validate(app, qa, acceptance, expected_sha):
    require(bool(SHA.fullmatch(expected_sha)), "Exact expected application SHA required")
    require(app.get("schema_version") == 1 and app.get("kind") == "aronexa_application_local_ci", "Wrong app evidence schema")
    require(app.get("application_sha") == expected_sha, "App evidence SHA mismatch")
    require(app.get("result") == "PASS" and app.get("synthetic_only") is True, "Application CI incomplete")
    require(app.get("customer_release_approved") is False, "CI cannot authorize release")
    require(isinstance(app.get("junit_tests"), int) and app["junit_tests"] > 0, "Missing backend test execution")
    require(app.get("junit_skipped") == 0, "Backend tests skipped")
    for check in APP_CHECKS:
        require(app.get("checks", {}).get(check) == "PASS", "Application check not PASS: " + check)

    require(qa.get("schema_version") == 1 and qa.get("kind") == "aronexa_central_qa_local", "Wrong QA evidence schema")
    require(qa.get("application_sha") == expected_sha, "QA evidence SHA mismatch")
    require(bool(SHA.fullmatch(qa.get("qa_sha", ""))), "QA repo SHA required")
    require(qa.get("result") == "PASS" and qa.get("synthetic_only") is True, "Synthetic QA incomplete")
    require(qa.get("customer_release_approved") is False, "QA cannot authorize release")
    require(isinstance(qa.get("browser_tests"), int) and qa["browser_tests"] > 0, "Missing browser test count")
    require(qa.get("browser_failures") == 0 and qa.get("browser_skipped") == 0, "Browser tests failed or skipped")
    for check in QA_CHECKS:
        require(qa.get("checks", {}).get(check) == "PASS", "QA check not PASS: " + check)

    require(acceptance.get("schema_version") == 1, "Wrong acceptance schema")
    require(acceptance.get("application_sha") == expected_sha, "Acceptance SHA mismatch")
    require(acceptance.get("release_type") == "CUSTOMER_PILOT", "Wrong release classification")
    require(acceptance.get("owner_approved") is True, "Explicit owner approval missing")
    require(isinstance(acceptance.get("owner_identity"), str) and len(acceptance["owner_identity"].strip()) >= 3, "Owner identity missing")
    require(isinstance(acceptance.get("approval_reference"), str) and len(acceptance["approval_reference"].strip()) >= 8, "Approval reference missing")
    for check in REVIEW_CHECKS:
        info = acceptance.get("reviews", {}).get(check)
        require(isinstance(info, dict) and info.get("status") == "PASS", "Release gate missing: " + check)
        require(isinstance(info.get("evidence_reference"), str) and len(info["evidence_reference"].strip()) >= 8, "Evidence reference missing: " + check)
    return "READY_FOR_OWNER_RELEASE_REVIEW"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-evidence", required=True)
    parser.add_argument("--qa-evidence", required=True)
    parser.add_argument("--acceptance", required=True)
    parser.add_argument("--expected-sha", required=True)
    args = parser.parse_args()
    try:
        status = validate(read_json(args.app_evidence), read_json(args.qa_evidence), read_json(args.acceptance), args.expected_sha)
    except (GateError, OSError, ValueError, TypeError, KeyError) as exc:
        print("RELEASE_GATE=BLOCKED: " + str(exc), file=sys.stderr)
        return 1
    print("RELEASE_GATE=" + status + " (documentary review only, no deployment)")
    return 0

if __name__ == "__main__":
    sys.exit(main())
