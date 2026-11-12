"""Adversarial tests for the documentary release gate."""
import unittest
from release_gate import APP_CHECKS, QA_CHECKS, REVIEW_CHECKS, GateError, validate

SHA = "1" * 40

def fixtures():
    app = {"schema_version":1,"kind":"aronexa_application_local_ci","application_sha":SHA,"result":"PASS","synthetic_only":True,"customer_release_approved":False,"junit_tests":71,"junit_skipped":0,"checks":{k:"PASS" for k in APP_CHECKS}}
    qa = {"schema_version":1,"kind":"aronexa_central_qa_local","application_sha":SHA,"qa_sha":"2"*40,"result":"PASS","synthetic_only":True,"customer_release_approved":False,"browser_tests":8,"browser_failures":0,"browser_skipped":0,"checks":{k:"PASS" for k in QA_CHECKS}}
    accept = {"schema_version":1,"application_sha":SHA,"release_type":"CUSTOMER_PILOT","owner_approved":True,"owner_identity":"Documented owner","approval_reference":"recorded-approval-001","reviews":{k:{"status":"PASS","evidence_reference":"documented-evidence-001"} for k in REVIEW_CHECKS}}
    return app, qa, accept

class ReleaseGateTests(unittest.TestCase):
    def test_complete_evidence_not_deployment(self):
        self.assertEqual(validate(*fixtures(), SHA), "READY_FOR_OWNER_RELEASE_REVIEW")
    def test_wrong_source_sha(self):
        a,q,h=fixtures();q["application_sha"]="3"*40
        with self.assertRaises(GateError):validate(a,q,h,SHA)
    def test_missing_backend_tests(self):
        a,q,h=fixtures();a["junit_tests"]=0
        with self.assertRaises(GateError):validate(a,q,h,SHA)
    def test_missing_or_skipped_app_check(self):
        for key in APP_CHECKS:
            a,q,h=fixtures();a["checks"][key]="NOT_RUN"
            with self.subTest(key=key),self.assertRaises(GateError):validate(a,q,h,SHA)
    def test_browser_skips(self):
        a,q,h=fixtures();q["browser_skipped"]=1
        with self.assertRaises(GateError):validate(a,q,h,SHA)
    def test_missing_review_or_owner(self):
        for key in REVIEW_CHECKS:
            a,q,h=fixtures();h["reviews"][key]["evidence_reference"]=""
            with self.subTest(key=key),self.assertRaises(GateError):validate(a,q,h,SHA)
        a,q,h=fixtures();h["owner_approved"]=False
        with self.assertRaises(GateError):validate(a,q,h,SHA)
    def test_ci_cannot_self_authorize(self):
        a,q,h=fixtures();a["customer_release_approved"]=True
        with self.assertRaises(GateError):validate(a,q,h,SHA)
    def test_stale_acceptance(self):
        a,q,h=fixtures();h["application_sha"]="4"*40
        with self.assertRaises(GateError):validate(a,q,h,SHA)
if __name__ == "__main__":
    unittest.main()
