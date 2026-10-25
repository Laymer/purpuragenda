"""Synthetic authentication worker report omissions, truncation and skips must fail closed."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('auth_worker_report', Path(__file__).with_name('check-auth-worker-report.py'))
GATE = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(GATE)


class AuthWorkerReportTest(unittest.TestCase):
    def test_complete_real_suite_name_with_ten_executed_cases_passes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.write(root)
            GATE.validate(root)

    def write(self, root, name='AuthWorkerDatabaseTest', tests=10, field='', cases=10):
        path = root / 'backend/adapters/target/surefire-reports/TEST-com.purpuragenda.adapters.AuthWorkerDatabaseTest.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f'<testsuite name="com.purpuragenda.adapters.{name}" tests="{tests}" {field}>'
                        + '<testcase/>' * cases + '</testsuite>')
        return path

    def test_build_requires_auth_worker_report_unconditionally(self):
        build=(Path(__file__).resolve().parents[2]/'scripts/ci/verify-build.sh').read_text()
        self.assertIn('\npython3 scripts/ci/check-auth-worker-report.py\n',build)
        self.assertNotIn('check-auth-worker-report.py ||',build)

    def test_nested_case_failures_and_errors_fail_even_with_zero_suite_counts(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for tag in ('failure', 'error'):
                with self.subTest(tag=tag):
                    path = self.write(root, field='failures="0" errors="0" skipped="0"')
                    path.write_text(path.read_text().replace('<testcase/>', f'<testcase><{tag}/></testcase>', 1))
                    with self.assertRaises(ValueError):
                        GATE.validate(root)

    def test_missing_incomplete_phantom_skipped_and_unrelated_suites_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaises(ValueError): GATE.validate(root)
            for arguments in ({'name':'SecurityPrimitivesTest'}, {'tests':9}, {'tests':11},
                              {'field':'failures="1"'}, {'field':'errors="1"'}, {'field':'skipped="1"'}):
                with self.subTest(arguments=arguments):
                    self.write(root, **arguments)
                    with self.assertRaises(ValueError): GATE.validate(root)
            path = self.write(root)
            path.write_text(path.read_text().replace('<testcase/>', '<testcase><skipped/></testcase>', 1))
            with self.assertRaises(ValueError): GATE.validate(root)


if __name__ == '__main__':
    unittest.main()
