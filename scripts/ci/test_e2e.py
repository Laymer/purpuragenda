"""Docker-free tests for the core-journey orchestrator: scrubbing, settings, report gates and the terminal enrollment."""
import base64
import copy
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import textwrap
import unittest

sys.path.insert(0, str(Path(__file__).parent))
import e2e_support as support
import operator_session

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('verify_e2e', Path(__file__).with_name('verify-e2e.py'))
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)
KEY = base64.b32encode(b'12345678901234567890').decode()  # RFC 6238 vector secret, derived rather than stored
TOKEN = 'AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde'


def report(root='com.purpuragenda.acceptance.CoreJourneyE2eTest', name=support.JOURNEY_CASE, tests=1, extra='', counts=''):
    return (f'<testsuite name="{root}" tests="{tests}" failures="0" errors="0" skipped="0" {counts}>'
            f'<testcase classname="com.purpuragenda.acceptance.CoreJourneyE2eTest" name="{name}">{extra}</testcase></testsuite>')


class ScrubTest(unittest.TestCase):
    def test_exact_secrets_and_secret_shaped_text_never_survive(self):
        text = ('fill("synthetic password value") for guest+abc@example.test at http://localhost:1/verify#token=' + TOKEN
                + '&purpose=CREATE code 123456 Cookie: session=abc; x\nGET /api?token=abc ' + KEY)
        cleaned = support.scrub(text, ['synthetic password value', KEY])
        for secret in ('synthetic password value', 'guest+abc', TOKEN, '123456', 'session=abc', 'token=abc', KEY):
            self.assertNotIn(secret, cleaned)
        self.assertIn('/verify#[redacted]', cleaned)
        self.assertIn('[redacted-email]', cleaned)

    def test_selectors_and_ordinary_messages_stay_readable(self):
        text = 'locator("#booking-email") expected visible; Status: pending confirmation. 1234567'
        self.assertEqual(text, support.scrub(text))
        self.assertEqual('', support.scrub(None))

    def test_typed_synthetic_intake_values_are_redacted(self):
        cleaned = support.scrub('fill("Exampleville") fill("+32 470 00 00 01") fill("1990-05-17")')
        for value in support.SYNTHETIC_LITERALS:
            self.assertNotIn(value, cleaned)

    def test_longer_secret_is_removed_before_its_substring(self):
        self.assertEqual('[redacted] and [redacted]', support.scrub('abcdef and abc', ['abc', 'abcdef']))


class SettingsTest(unittest.TestCase):
    def valid(self, **override):
        values = dict(base_port=18080, mail_port=18025, email='operator+0123456789ab@example.test',
                      password='synthetic ' + 'a' * 24, key=KEY, run_id='0123456789ab', evidence_dir=Path('/tmp/evidence'))
        values.update(override)
        return support.journey_settings(**values)

    def test_builds_loopback_environment_for_the_browser_suite(self):
        settings = self.valid()
        self.assertEqual('http://localhost:18080', settings['E2E_BASE_URL'])
        self.assertEqual('http://127.0.0.1:18025', settings['E2E_MAIL_URL'])
        self.assertEqual(KEY, settings['E2E_ADMIN_TOTP_KEY'])
        self.assertEqual('/tmp/evidence', settings['E2E_EVIDENCE_DIR'])

    def test_rejects_unsafe_ports_identities_and_credentials(self):
        for override in ({'base_port': 80}, {'base_port': 18025}, {'mail_port': 99999}, {'base_port': '18080'},
                         {'run_id': 'UPPER'}, {'key': 'abc'}, {'email': 'operator@example.com'}, {'password': 'short'}):
            with self.subTest(override=override), self.assertRaises(ValueError):
                self.valid(**override)

    def test_ports_are_distinct_loopback_ports(self):
        first, second = support.distinct_ports()
        self.assertNotEqual(first, second)
        self.assertTrue(1024 < first < 65536 and 1024 < second < 65536)
        sequence = iter([5000, 5000, 5000, 6000])
        self.assertEqual((5000, 6000), support.distinct_ports(lambda: next(sequence)))
        with self.assertRaises(RuntimeError):
            support.distinct_ports(lambda: 5000)


class PublishedModelTest(unittest.TestCase):
    def fixture(self):
        runtime = {'ports': [], 'volumes': []}
        services = {name: copy.deepcopy(runtime) for name in ('postgres', 'migrate', 'backend', 'web', 'mailpit')}
        services['web']['ports'] = [{'host_ip': '127.0.0.1', 'published': '18080', 'target': 8080},
                                    {'host_ip': '127.0.0.1', 'published': '18025', 'target': 8025}]
        services['backend']['environment'] = {'APP_PUBLIC_ORIGIN': 'http://localhost:18080', 'APP_MODE': 'local', 'APP_SMTP_HOST': 'mailpit'}
        return {'services': services}

    def test_accepts_exactly_the_two_loopback_ports_and_matching_origin(self):
        support.validate_published(self.fixture(), 18080, 18025)

    def test_refuses_wide_extra_or_mismatched_publication(self):
        def mutate(change):
            document = self.fixture()
            change(document['services'])
            return document
        cases = [lambda s: s['web']['ports'][0].update(host_ip='0.0.0.0'),
                 lambda s: s['web']['ports'].pop(),
                 lambda s: s['backend'].update(ports=[{'host_ip': '127.0.0.1', 'published': '1', 'target': 8081}]),
                 lambda s: s['mailpit'].update(ports=[{'host_ip': '127.0.0.1', 'published': '2', 'target': 8025}]),
                 lambda s: s['backend']['environment'].update(APP_PUBLIC_ORIGIN='http://localhost:8080'),
                 lambda s: s['backend']['environment'].update(APP_MODE='operational'),
                 lambda s: s['backend']['environment'].update(APP_SMTP_HOST='smtp.example.test'),
                 lambda s: s['postgres'].update(volumes=['/var/run/docker.sock:/socket'])]
        for change in cases:
            with self.subTest(), self.assertRaises(ValueError):
                support.validate_published(mutate(change), 18080, 18025)

    def test_overlay_file_publishes_only_loopback_and_overrides_ports(self):
        text = (ROOT / 'compose.e2e.yaml').read_text()
        self.assertIn('ports: !override', text)
        self.assertEqual(2, text.count('"127.0.0.1:${PURPURAGENDA_E2E_'))
        self.assertNotIn('0.0.0.0', text)
        self.assertNotIn('docker.sock', text)
        self.assertIn('APP_PUBLIC_ORIGIN: http://localhost:${PURPURAGENDA_E2E_PORT:?', text)


class TimeStepTest(unittest.TestCase):
    def test_waits_for_the_step_after_the_enrollment_code(self):
        clock = [89.0]
        slept = []
        def sleep(seconds):
            slept.append(seconds)
            clock[0] += 1
        step = support.wait_for_next_step(2, now=lambda: clock[0], sleep=sleep)
        self.assertEqual(3, step)
        self.assertEqual([1], slept)  # 89 s is still step 2; 90 s begins step 3.
        self.assertEqual(3, support.wait_for_next_step(2, now=lambda: 95.0, sleep=lambda s: self.fail('must not wait')))


class EvidenceGateTest(unittest.TestCase):
    def write(self, text):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / 'TEST.xml'
        path.write_text(text)
        return path

    def test_complete_single_case_passes(self):
        self.assertEqual(1, support.surefire(self.write(report()))['tests'])

    def test_missing_wrong_failed_skipped_or_extra_reports_fail(self):
        with self.assertRaisesRegex(ValueError, 'MISSING'):
            support.surefire(Path('/nonexistent/TEST.xml'))
        with self.assertRaisesRegex(ValueError, 'MISSING'):
            support.surefire(self.write('<testsuite'))
        for text in (report(root='other.Suite'), report(name='somethingElse'), report(tests=2),
                     '<testsuite name="com.purpuragenda.acceptance.CoreJourneyE2eTest" tests="0"></testsuite>'):
            with self.subTest(text=text[:60]), self.assertRaisesRegex(ValueError, 'INCOMPLETE'):
                support.surefire(self.write(text))
        for extra in ('<failure/>', '<error/>', '<skipped/>'):
            with self.subTest(extra=extra), self.assertRaisesRegex(ValueError, 'FAILED'):
                support.surefire(self.write(report(extra=extra)))
        with self.assertRaisesRegex(ValueError, 'FAILED'):
            support.surefire(self.write(report().replace('skipped="0"', 'skipped="1"')))

    def test_receipt_requires_current_clean_passing_hosted_or_local_chromium_run(self):
        good = {'revision': 'abc', 'dirty': False, 'passed': True, 'checks': ['core-journey'], 'browser': 'chromium',
                'execution': 'hosted-docker'}
        support.validate_receipt(good, 'abc')
        support.validate_receipt(dict(good, execution='local-docker'), 'abc')
        for change in ({'revision': 'old'}, {'dirty': True}, {'passed': False}, {'checks': []}, {'checks': ['core-journey', 'extra']},
                       {'browser': 'firefox'}, {'execution': 'mock'}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                support.validate_receipt(dict(good, **change), 'abc')


FAKE_TERMINAL = textwrap.dedent('''
    import os, sys
    mode = os.environ.get('FAKE_MODE', 'ok')
    def ask(prompt):
        sys.stdout.write(prompt); sys.stdout.flush()
        return sys.stdin.readline().strip()
    email = ask('Administrator email: ')
    first = ask('Password (15-128 characters): ')
    second = ask('Repeat password: ')
    print('Authenticator setup')
    print('Key: JBSWY3DPEHPK3PXP')
    code = ask('Authenticator code: ')
    if mode == 'reject' or not code.isdigit() or len(code) != 6:
        print('Code rejected'); sys.exit(3)
    print('Enrollment complete')
    if mode == 'echo':
        print('password was ' + first)
    sys.exit(0 if first == second else 4)
''')


class EnrollmentTest(unittest.TestCase):
    def command(self, mode):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        script = Path(directory.name) / 'terminal.py'
        script.write_text(FAKE_TERMINAL)
        return [sys.executable, str(script)], dict(os.environ, FAKE_MODE=mode)

    def test_reads_the_key_from_the_terminal_and_records_the_step_of_the_code_it_typed(self):
        command, environment = self.command('ok')
        key, used_step = GATE.enroll_administrator(command, environment, 'operator@example.test', 'synthetic password value')
        self.assertEqual('JBSWY3DPEHPK3PXP', key)
        self.assertLessEqual(abs(used_step - int(__import__('time').time() // 30)), 1)

    def test_a_rejected_code_or_an_echoed_password_fails_the_run(self):
        for mode, error in (('reject', EOFError), ('echo', AssertionError)):
            command, environment = self.command(mode)
            with self.subTest(mode=mode), self.assertRaises((error, AssertionError, EOFError, TimeoutError)):
                GATE.enroll_administrator(command, environment, 'operator@example.test', 'synthetic password value')

    def test_totp_helper_matches_the_rfc_vector_used_by_the_java_suite(self):
        self.assertEqual('287082', operator_session.totp(KEY, 59))
        self.assertEqual('353130', operator_session.totp(KEY, 20_000_000_000))


class WiringTest(unittest.TestCase):
    def test_orchestrator_selects_only_the_tagged_journey_and_never_removes_volumes(self):
        source = (ROOT / 'scripts/ci/verify-e2e.py').read_text()
        self.assertIn("'-De2e.groups=e2e'", source)
        self.assertNotIn("'-v'", source)
        self.assertNotIn('down -v', source)
        self.assertNotIn('--volumes', source)
        pom = (ROOT / 'acceptance-tests/pom.xml').read_text()
        self.assertIn('<e2e.excludedGroups>e2e</e2e.excludedGroups>', pom)
        self.assertIn('<failIfNoTests>true</failIfNoTests>', pom)


if __name__ == '__main__':
    unittest.main()
