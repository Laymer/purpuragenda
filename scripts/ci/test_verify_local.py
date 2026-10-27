"""verify.sh suite mapping fails closed and never reports success for work it did not do."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('verify_local', ROOT / 'scripts/dev/verify_local.py')
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


def inventory(**states):
    return {'schemaVersion': 1, 'suites': {name: {'owner': 'P01', 'state': state, 'job': None}
                                            for name, state in states.items()}}


class Completed:
    def __init__(self, code):
        self.returncode = code


class PlanTest(unittest.TestCase):
    def test_every_implemented_repository_suite_has_a_local_command(self):
        document = json.loads((ROOT / 'scripts/ci/suites.json').read_text())
        implemented = {name for name, suite in document['suites'].items() if suite['state'] == 'implemented'}
        planned = VERIFY.plan(document)
        self.assertEqual(implemented, {suite for _, suites in planned for suite in suites})
        self.assertEqual(['java', 'architecture', 'coverage', 'postgres-migrations', 'outbox', 'angular'],
                         dict(planned)['build'])

    def test_unmapped_implemented_suite_fails_before_anything_runs(self):
        document = inventory(documentation='implemented', zap='implemented')
        with self.assertRaisesRegex(ValueError, 'no mapped local command: zap'):
            VERIFY.plan(document)
        executed = []
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(ValueError):
            VERIFY.run_all(document, execute=lambda *arguments: executed.append(arguments))
        self.assertEqual([], executed)

    def test_main_fails_closed_on_unmapped_or_invalid_inventory(self):
        for document in (inventory(documentation='implemented', zap='implemented'), {'schemaVersion': 2},
                         inventory(documentation='enabled'), inventory(zap='planned')):
            with self.subTest(document=document), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / 'scripts/ci').mkdir(parents=True)
                (root / 'scripts/ci/suites.json').write_text(json.dumps(document))
                errors = io.StringIO()
                with contextlib.redirect_stderr(errors), mock.patch.object(VERIFY, 'run_step') as run_step:
                    self.assertEqual(1, VERIFY.main(['all'], root))
                run_step.assert_not_called()
                self.assertIn('VERIFY_FAILED', errors.getvalue())
        with tempfile.TemporaryDirectory() as directory:  # Missing inventory file.
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, VERIFY.main(['all'], Path(directory)))
                self.assertEqual(2, VERIFY.main(['everything'], Path(directory)))

    def test_planned_suites_are_not_run(self):
        document = inventory(documentation='implemented', zap='planned', sbom='planned')
        self.assertEqual([('documentation', ['documentation'])], VERIFY.plan(document))


class RunTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.commands = []

    def runner(self, code=0, writes=()):
        def run(command, cwd):
            self.commands.append(command)
            for name in writes:
                (cwd / name).parent.mkdir(parents=True, exist_ok=True)
                (cwd / name).write_text('{}')
            return Completed(code)
        return run

    def step(self, **values):
        return VERIFY.Step(commands=(('tool', 'one'), ('tool', 'two')), **values)

    def test_failed_command_stops_the_step(self):
        passed, detail = VERIFY.run_step('x', self.step(), self.root, self.runner(3))
        self.assertFalse(passed)
        self.assertIn('exit 3', detail)
        self.assertEqual([['tool', 'one']], self.commands)

    def test_missing_tool_fails_without_running(self):
        with mock.patch.object(VERIFY.shutil, 'which', return_value=None):
            passed, detail = VERIFY.run_step('x', self.step(tools=('gh',)), self.root, self.runner())
        self.assertFalse(passed)
        self.assertIn('missing required tool: gh', detail)
        with mock.patch.object(VERIFY, 'docker_daemon', return_value=False):
            passed, detail = VERIFY.run_step('x', self.step(tools=('docker-daemon',)), self.root, self.runner())
        self.assertFalse(passed)
        self.assertIn('Docker daemon', detail)
        self.assertEqual([], self.commands)

    def test_missing_or_stale_reports_fail_even_after_successful_commands(self):
        step = self.step(reports=('artifacts/receipt.json', 'backend/*/target/jacoco.xml'))
        passed, detail = VERIFY.run_step('x', step, self.root, self.runner())
        self.assertFalse(passed)
        self.assertIn('artifacts/receipt.json', detail)
        stale = self.root / 'artifacts/receipt.json'
        stale.parent.mkdir()
        stale.write_text('{}')
        old = time.time() - 3600
        os.utime(stale, (old, old))
        passed, detail = VERIFY.run_step('x', step, self.root, self.runner(writes=('backend/a/target/jacoco.xml',)))
        self.assertFalse(passed)
        self.assertIn('stale report: artifacts/receipt.json', detail)
        passed, _ = VERIFY.run_step('x', step, self.root, self.runner(
            writes=('artifacts/receipt.json', 'backend/a/target/jacoco.xml')))
        self.assertTrue(passed)

    def test_host_restricted_and_hosted_only_steps_never_pass_elsewhere(self):
        passed, detail = VERIFY.run_step('macos-bootstrap', self.step(host='Darwin'), self.root, self.runner(), host='Linux')
        self.assertFalse(passed)
        self.assertIn('requires a Darwin host', detail)
        passed, _ = VERIFY.run_step('macos-bootstrap', self.step(host='Darwin'), self.root, self.runner(), host='Darwin')
        self.assertTrue(passed)
        for name in ('sast', 'windows-preflight'):
            passed, detail = VERIFY.run_step(name, VERIFY.STEPS[name], self.root, self.runner())
            self.assertFalse(passed)
            self.assertIn('hosted job ' + name, detail)
        self.assertEqual([['tool', 'one'], ['tool', 'two']], self.commands)

    def test_delivery_policy_requires_a_main_base(self):
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        passed, detail = VERIFY.run_step('delivery-policy', VERIFY.Step(dynamic=('delivery',)), self.root, self.runner())
        self.assertFalse(passed)
        self.assertIn('origin/main is unavailable', detail)
        self.assertEqual([], self.commands)

    def test_summary_reports_each_suite_and_any_failure_fails_the_run(self):
        document = inventory(documentation='implemented', java='implemented', angular='implemented', sast='implemented')
        outcomes = {'documentation': (True, 'passed'), 'build': (False, 'command failed'), 'sast': (False, 'hosted')}
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertFalse(VERIFY.run_all(document, self.root, execute=lambda name, step, root: outcomes[name]))
        text = output.getvalue()
        for line in ('PASS documentation', 'FAIL java', 'FAIL angular', 'FAIL sast'):
            self.assertIn(line, text)
        self.assertIn('1 passed, 3 failed of 4 implemented suites.', text)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertTrue(VERIFY.run_all(inventory(documentation='implemented'), self.root,
                                           execute=lambda *arguments: (True, 'passed')))


class WrapperTest(unittest.TestCase):
    def run_wrapper(self, *arguments):
        return subprocess.run(['bash', str(ROOT / 'verify.sh'), *arguments], capture_output=True, text=True, timeout=60)

    def test_help_unknown_and_list(self):
        result = self.run_wrapper('help')
        self.assertEqual(0, result.returncode)
        self.assertIn('Usage: ./verify.sh <command>', result.stdout)
        for arguments in ([], ['everything'], ['all', 'extra']):
            self.assertEqual(2, self.run_wrapper(*arguments).returncode)
        result = self.run_wrapper('list')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('build [java, architecture, coverage, postgres-migrations, outbox, angular]: bash scripts/ci/verify-build.sh',
                      result.stdout)
        self.assertIn('sast [sast]: hosted only', result.stdout)


if __name__ == '__main__':
    unittest.main()
