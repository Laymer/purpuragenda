"""Synthetic evidence proves absent/failed frontend categories cannot pass."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('frontend_reports', Path(__file__).with_name('check-frontend-reports.py'))
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class FrontendReportsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.report = self.write('frontend/coverage/junit.xml',
            '<testsuites><testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="synthetic"/></testsuite></testsuites>')
        self.write('frontend/dist/purpuragenda/browser/index.html', '<app-root></app-root>')
        self.bundle = self.write('frontend/dist/purpuragenda/browser/main-synthetic.js', 'synthetic();')
        source = self.write('frontend/src/main.ts', 'synthetic();')
        self.coverage = self.write('frontend/coverage/coverage-final.json', json.dumps({str(source): {
            's': {'0': 1}, 'statementMap': {'0': {'start': {'line': 1}}}, 'b': {}, 'branchMap': {}}}))

    def write(self, name, content):
        file = self.root / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content)
        return file

    def test_complete_reports_pass(self):
        self.assertEqual(['Angular LINE: 1/1', 'Angular BRANCH: 0/0'], CHECK.validate(self.root))

    def test_missing_junit_fails(self):
        self.report.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing Angular JUnit'):
            CHECK.validate(self.root)

    def test_empty_failed_and_skipped_suites_fail(self):
        for field in ('tests="0"', 'failures="1"', 'errors="1"', 'skipped="1"'):
            with self.subTest(field=field):
                self.report.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"/>'.replace(field.split('=')[0] + '="' + ('1' if field.startswith('tests') else '0') + '"', field))
                with self.assertRaises(ValueError):
                    CHECK.validate(self.root)

    def test_case_level_skip_fails_even_if_summary_omits_it(self):
        self.report.write_text('<testsuite tests="1"><testcase><skipped/></testcase></testsuite>')
        with self.assertRaisesRegex(ValueError, 'skipped Angular test cases'):
            CHECK.validate(self.root)

    def test_nonempty_summary_without_cases_fails(self):
        self.report.write_text('<testsuite tests="1"/>')
        with self.assertRaisesRegex(ValueError, 'Missing Angular test cases'):
            CHECK.validate(self.root)

    def test_root_failure_summary_cannot_hide_behind_successful_cases(self):
        self.report.write_text('<testsuites tests="1" failures="1"><testsuite tests="1"><testcase/></testsuite></testsuites>')
        with self.assertRaisesRegex(ValueError, 'Failed or skipped Angular tests'):
            CHECK.validate(self.root)

    def test_phantom_or_truncated_case_counts_fail(self):
        for xml in ('<testsuite tests="2"><testcase/></testsuite>',
                    '<testsuites tests="2"><testsuite tests="1"><testcase/></testsuite></testsuites>'):
            with self.subTest(xml=xml):
                self.report.write_text(xml)
                with self.assertRaisesRegex(ValueError, 'count mismatch'):
                    CHECK.validate(self.root)

    def test_cleanup_removes_only_stale_artifacts(self):
        CHECK.cleanup(self.root)
        self.assertFalse((self.root / 'frontend/coverage').exists())
        self.assertFalse((self.root / 'frontend/dist').exists())
        self.assertTrue((self.root / 'frontend/src/main.ts').is_file())
        CHECK.cleanup(self.root)  # Absent artifacts are safe.

    def test_cleanup_unlinks_nested_symlink_without_touching_outside_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            outside = Path(temp)
            sentinel = outside / 'sentinel'
            sentinel.write_text('synthetic outside bytes')
            (self.root / 'frontend/dist/external').symlink_to(outside, target_is_directory=True)
            CHECK.cleanup(self.root)
            self.assertEqual('synthetic outside bytes', sentinel.read_text())
            self.assertFalse((self.root / 'frontend/dist').exists())

    def test_cleanup_refuses_symlinked_frontend_or_artifact_without_outside_deletion(self):
        for parent in (True, False):
            with self.subTest(parent=parent), tempfile.TemporaryDirectory() as temp:
                sandbox = Path(temp)
                outside = sandbox / 'outside'
                outside.mkdir()
                sentinel = outside / 'sentinel'
                sentinel.write_text('synthetic outside bytes')
                root = sandbox / 'repo'
                root.mkdir()
                if parent:
                    (root / 'frontend').symlink_to(outside, target_is_directory=True)
                else:
                    (root / 'frontend').mkdir()
                    (root / 'frontend/coverage').symlink_to(outside, target_is_directory=True)
                with self.assertRaises((OSError, ValueError)):
                    CHECK.cleanup(root)
                self.assertEqual('synthetic outside bytes', sentinel.read_text())

    def test_missing_build_fails(self):
        self.bundle.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing Angular production build'):
            CHECK.validate(self.root)

    def test_missing_coverage_fails(self):
        self.coverage.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing Angular coverage'):
            CHECK.validate(self.root)

    def test_omitted_bootstrap_fails(self):
        self.write('frontend/src/another.ts', 'synthetic();')
        with self.assertRaisesRegex(ValueError, 'Unreported Angular production'):
            CHECK.validate(self.root)


if __name__ == '__main__':
    unittest.main()
