import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('migration_report', Path(__file__).with_name('check-migration-report.py'))
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class MigrationReportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.report = self.root / 'backend/adapters/target/surefire-reports/TEST-com.purpuragenda.adapters.MigrationTest.xml'
        self.report.parent.mkdir(parents=True)

    def write(self, tests=11, **counts):
        attrs = ' '.join(f'{key}="{value}"' for key, value in counts.items())
        self.report.write_text(f'<testsuite name="com.purpuragenda.adapters.MigrationTest" tests="{tests}" {attrs}/>')

    def test_real_complete_suite_passes(self):
        self.write()
        GATE.validate(self.root)

    def test_unrelated_passing_suite_cannot_replace_missing_migrations(self):
        self.report.with_name('TEST-example.OtherTest.xml').write_text('<testsuite tests="100"/>')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'):
            GATE.validate(self.root)

    def test_empty_partial_failed_errored_or_skipped_suite_fails(self):
        for tests, counts in ((0, {}), (10, {}), (11, {'failures': 1}), (11, {'errors': 1}), (11, {'skipped': 1})):
            with self.subTest(tests=tests, counts=counts):
                self.write(tests, **counts)
                with self.assertRaises(ValueError):
                    GATE.validate(self.root)


if __name__ == '__main__':
    unittest.main()
