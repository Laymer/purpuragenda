import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('wsl_report', ROOT / 'scripts/ci/check-wsl-report.py')
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class WslReportTest(unittest.TestCase):
    def test_native_evidence_and_complete_safe_shape_are_required(self):
        good = {'schemaVersion': 1, 'platform': 'windows', 'powershell': '7.4.13',
                'tests': 18, 'failures': 0, 'skipped': 0}
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / 'receipt.json'
            with self.assertRaises(OSError):
                GATE.validate(report)
            report.write_text(json.dumps(good))
            GATE.validate(report)
            for changes in ({'schemaVersion': True}, {'platform': 'other'}, {'powershell': '7.3.10'}, {'powershell': 'unknown'},
                            {'tests': 0}, {'tests': 17}, {'tests': True}, {'failures': 1},
                            {'skipped': 1}, {'skipped': False}, {'privateDetails': 'synthetic marker'}):
                with self.subTest(changes=changes):
                    report.write_text(json.dumps(good | changes))
                    with self.assertRaises(ValueError):
                        GATE.validate(report)

    def test_windows_job_executes_native_cases_and_requires_report(self):
        workflow = (ROOT / '.github/workflows/verify.yml').read_text()
        self.assertIn('runs-on: windows-2025', workflow)
        self.assertIn('./scripts/ci/test-wsl-preflight.ps1', workflow)
        self.assertIn('python scripts/ci/check-wsl-report.py --report "$env:REPORT"', workflow)
        self.assertIn('path: ${{ steps.wsl-tests.outputs.receipt }}', workflow)
        script = (ROOT / 'scripts/ops/preflight-wsl.ps1').read_text()
        self.assertIn('ArgumentList.Add($argument)', script)
        self.assertIn('[ValidateRange(1, 30000)]', script)
        self.assertIn('WaitForExit($TimeoutMilliseconds)', script)
        for mutation in ('--unregister', '--set-default', '--set-version', '--install', 'Invoke-Expression'):
            self.assertNotIn(mutation, script)
