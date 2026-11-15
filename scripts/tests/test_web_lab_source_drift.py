"""Behavioral regression: passing Node tests mutate a tracked private fixture."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(os.environ.get('WEBLAB_REPO', Path(__file__).resolve().parents[2]))

class SourceDriftTests(unittest.TestCase):
    def test_developer_feedback_never_passes_after_source_drift(self):
        pwsh = shutil.which('pwsh') or shutil.which('powershell.exe')
        self.assertIsNotNone(pwsh, 'PowerShell required for source-drift regression')
        with tempfile.TemporaryDirectory(prefix='weblab-drift-') as tmp:
            root = Path(tmp)
            repo = root / 'fixture'
            (repo / 'scripts/pilot').mkdir(parents=True)
            (repo / 'frontend/test').mkdir(parents=True)
            for file in ['WebLab.psm1', 'Test-AroNexa-Feature.ps1', 'pilot/Win11p-MavenMatrix.psm1']:
                shutil.copyfile(ROOT / 'scripts' / file, repo / 'scripts' / file)
            names = ['reception-demo', 'staff-session-client', 'registration-client',
                     'connected-registration-flow', 'opd-journey', 'patient-candidate-search-client',
                     'appointment-candidate-client', 'queue-registration-continuity', 'reception-queue-summary']
            for n in names:
                code = "import test from 'node:test';test('fixture',()=>{});"
                if n == names[0]:
                    code = "import test from 'node:test';import{writeFileSync}from'node:fs';test('fixture',()=>{writeFileSync(new URL('../../tracked.txt',import.meta.url),'changed');});"
                (repo / 'frontend/test' / (n + '.test.mjs')).write_text(code)
            (repo / 'tracked.txt').write_text('original')
            def git(*args):
                return subprocess.check_output(['git', '-C', str(repo), *args], stderr=subprocess.DEVNULL).decode().strip()
            git('init', '--quiet')
            git('add', '.')
            git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', '-c', 'commit.gpgsign=false', 'commit', '--quiet', '-m', 'fixture')
            sha = git('rev-parse', 'HEAD')
            allowed = {'PATH', 'HOME', 'LANG', 'TMPDIR', 'TEMP', 'TMP', 'SystemRoot', 'SYSTEMROOT', 'WINDIR', 'COMSPEC', 'PATHEXT', 'USERPROFILE', 'APPDATA', 'LOCALAPPDATA', 'SSL_CERT_FILE', 'SSL_CERT_DIR', 'NODE_EXTRA_CA_CERTS'}
            env = {k:v for k,v in os.environ.items() if k in allowed or k.upper().endswith('_PROXY')}
            command = [pwsh, '-NoProfile', '-File', str(repo/'scripts/Test-AroNexa-Feature.ps1'), '-ExpectedCommit', sha, '-Level', 'Developer', '-ReportDirectory', str(root/'report')]
            result = subprocess.run(command, env=env, cwd=repo, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
            self.assertNotEqual(result.returncode, 0)
            report = json.loads((root/'report/feedback.json').read_text())
            self.assertEqual(report['node_tests'], 9)
            self.assertNotEqual(report['developer_feedback'], 'PASS', 'source-drift report must revoke developer PASS')
            self.assertEqual(report['source_integrity'], 'FAIL')
            self.assertEqual(report['overall'], 'FAIL')
            self.assertEqual(report['central_qa'], 'NOT_RUN')

if __name__ == '__main__':
    unittest.main()
