"""Synthetic queue report omissions, truncation and skips must fail closed."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('queue_report', Path(__file__).with_name('check-queue-report.py'))
GATE = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(GATE)


class QueueReportTest(unittest.TestCase):
    def test_complete_real_suite_name_with_eleven_executed_cases_passes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.write(root)
            GATE.validate(root)

    def write(self, root, name='NotificationQueueDatabaseTest', tests=11, field='', cases=11):
        path = root / 'backend/adapters/target/surefire-reports/TEST-com.purpuragenda.adapters.NotificationQueueDatabaseTest.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f'<testsuite name="com.purpuragenda.adapters.{name}" tests="{tests}" {field}>'
                        + '<testcase/>' * cases + '</testsuite>')
        return path

    def test_ci_retains_only_counter_xml_for_backend_coverage_diagnostics(self):
        import re
        workflow=(Path(__file__).resolve().parents[2]/'.github/workflows/verify.yml').read_text()
        block=re.search(r'      - name: Retain backend coverage counters\n((?:        [^\n]*\n)+)',workflow)
        self.assertIsNotNone(block)
        self.assertIn('if: always()',block[1])
        self.assertIn('uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02',block[1])
        self.assertIn('path: backend/*/target/site/jacoco/jacoco.xml',block[1])
        self.assertIn('if-no-files-found: error',block[1])
        self.assertNotIn('surefire-reports',block[1])

    def test_booking_notifications_require_actual_expansion_delivery_and_fencing_cases(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.write(root)
            source = root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/email/PostgresOutboxStore.java'
            source.parent.mkdir(parents=True); source.write_text('Synthetic implemented outbox adapter')
            with self.assertRaisesRegex(ValueError, 'Missing mandatory booking notification'):
                GATE.validate(root)
            report = root / 'backend/adapters/target/surefire-reports/TEST-com.purpuragenda.adapters.BookingNotificationDatabaseTest.xml'
            for count, cases, nested in ((20,20,''), (21,20,''), (22,21,''),
                                         (21,21,'<failure/>'), (21,21,'<error/>'), (21,21,'<skipped/>')):
                report.write_text(f'<testsuite name="com.purpuragenda.adapters.BookingNotificationDatabaseTest" tests="{count}">'
                                  + f'<testcase>{nested}</testcase>' * cases + '</testsuite>')
                with self.assertRaises(ValueError): GATE.validate(root)
            report.write_text('<testsuite name="com.purpuragenda.adapters.BookingNotificationDatabaseTest" tests="21">'
                              + '<testcase/>' * 21 + '</testsuite>')
            GATE.validate(root)

    def test_missing_incomplete_phantom_skipped_and_unrelated_suites_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaises(ValueError): GATE.validate(root)
            for arguments in ({'name':'SecurityPrimitivesTest'}, {'tests':10}, {'tests':12},
                              {'field':'failures="1"'}, {'field':'errors="1"'}, {'field':'skipped="1"'}):
                with self.subTest(arguments=arguments):
                    self.write(root, **arguments)
                    with self.assertRaises(ValueError): GATE.validate(root)
            path = self.write(root)
            path.write_text(path.read_text().replace('<testcase/>', '<testcase><skipped/></testcase>', 1))
            with self.assertRaises(ValueError): GATE.validate(root)


if __name__ == '__main__':
    unittest.main()
