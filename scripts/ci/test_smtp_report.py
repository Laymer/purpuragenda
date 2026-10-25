"""Synthetic SMTP report omissions, truncation and skips must fail closed."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('smtp_report', Path(__file__).with_name('check-smtp-report.py'))
GATE = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(GATE)


class SmtpReportTest(unittest.TestCase):
    def test_complete_real_suite_name_with_eight_executed_cases_passes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.write(root)
            GATE.validate(root)

    def write(self, root, name='out.email.SmtpDeliveryTest', tests=8, field='', cases=8):
        path = root / 'backend/adapters/target/surefire-reports/TEST-com.purpuragenda.adapters.out.email.SmtpDeliveryTest.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f'<testsuite name="com.purpuragenda.adapters.{name}" tests="{tests}" {field}>'
                        + '<testcase/>' * cases + '</testsuite>')
        return path

    def test_missing_incomplete_phantom_skipped_and_unrelated_suites_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaises(ValueError): GATE.validate(root)
            for arguments in ({'name':'SecurityPrimitivesTest'}, {'tests':7}, {'tests':9},
                              {'field':'failures="1"'}, {'field':'errors="1"'}, {'field':'skipped="1"'}):
                with self.subTest(arguments=arguments):
                    self.write(root, **arguments)
                    with self.assertRaises(ValueError): GATE.validate(root)
            path = self.write(root)
            path.write_text(path.read_text().replace('<testcase/>', '<testcase><skipped/></testcase>', 1))
            with self.assertRaises(ValueError): GATE.validate(root)


if __name__ == '__main__':
    unittest.main()
