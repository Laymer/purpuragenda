"""Calendar success cannot be inferred from unrelated tests or fabricated summary counters."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('calendar_report', Path(__file__).with_name('check-calendar-report.py'))
GATE = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(GATE)


class CalendarReportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.paths = []
        for (module, name), minimum in GATE.JAVA.items():
            self.write(f'backend/{module}/target/surefire-reports/TEST-{name}.xml', name, minimum)
        self.write('frontend/coverage/junit.xml', GATE.ANGULAR, 28)

    def write(self, path, name, count):
        suite = ET.Element('testsuite', name=name, tests=str(count))
        for index in range(count): ET.SubElement(suite, 'testcase', classname=name, name=f'synthetic-case-{index}')
        path = self.root / path; path.parent.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(suite).write(path); self.paths.append(path)

    def test_complete_named_suites_pass_and_only_counts_are_returned(self):
        counts = GATE.validate(self.root)
        self.assertEqual(52, sum(counts.values()))
        self.assertEqual(8, len(counts))
        self.assertNotIn('synthetic-case', json.dumps(counts))

    def test_every_missing_report_fails(self):
        for path in self.paths:
            original = path.read_bytes(); path.unlink()
            with self.subTest(report=path.name), self.assertRaises(OSError): GATE.validate(self.root)
            path.write_bytes(original)

    def test_each_suite_rejects_wrong_name_counts_skips_failures_and_unexecuted_cases(self):
        for path in self.paths:
            original = path.read_bytes()
            def mutate(kind):
                suite = ET.fromstring(original); case = suite.find('testcase')
                if kind == 'name': suite.set('name', 'unrelated')
                elif kind == 'summary': suite.set('tests', '999')
                elif kind == 'missing': suite.remove(case)
                elif kind == 'short': suite.remove(case); suite.set('tests', str(len(suite)))
                elif kind == 'class': case.set('classname', 'unrelated')
                elif kind == 'duplicate': case.set('name', suite.findall('testcase')[1].get('name') if len(suite) > 1 else '')
                elif kind.startswith('summary-'): suite.set(kind[8:], '1')
                else: ET.SubElement(case, kind)
                ET.ElementTree(suite).write(path)
            for kind in ('name', 'summary', 'missing', 'short', 'class', 'duplicate', 'failure', 'error', 'skipped', 'summary-failures', 'summary-errors', 'summary-skipped'):
                mutate(kind)
                with self.subTest(report=path.name, kind=kind), self.assertRaises(ValueError): GATE.validate(self.root)
            path.write_bytes(original)

    def test_angular_cannot_duplicate_the_required_suite(self):
        path = self.paths[-1]; suite = ET.parse(path).getroot(); document = ET.Element('testsuites')
        document.extend([suite, suite]); ET.ElementTree(document).write(path)
        with self.assertRaises(ValueError): GATE.validate(self.root)

    def test_calendar_is_mandatory_in_hosted_and_local_builds_with_retained_receipt(self):
        inventory = json.loads((ROOT / 'scripts/ci/suites.json').read_text())
        self.assertEqual({'owner': 'P37', 'state': 'implemented', 'job': 'build'}, inventory['suites']['calendar'])
        self.assertIn("Path('artifacts/calendar/verification.json').unlink(missing_ok=True)", (ROOT / 'scripts/ci/verify-build.sh').read_text())
        self.assertIn('python3 scripts/ci/check-calendar-report.py', (ROOT / 'scripts/ci/verify-build.sh').read_text())
        for path in ('.github/workflows/verify.yml', 'scripts/dev/verify_local.py'):
            self.assertIn('artifacts/calendar/verification.json', (ROOT / path).read_text())
