"""verify.sh suite mapping fails closed and never reports success for work it did not do."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
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
        for local in (False, True):
            executed = []
            with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(ValueError):
                VERIFY.run_all(document, local=local, execute=lambda *arguments, **options: executed.append(arguments))
            self.assertEqual([], executed)

    def test_main_fails_closed_on_unmapped_or_invalid_inventory(self):
        for document in (inventory(documentation='implemented', zap='implemented'), {'schemaVersion': 2},
                         inventory(documentation='enabled'), inventory(zap='planned')):
            for mode in ('all', 'local'):
                with self.subTest(document=document, mode=mode), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    (root / 'scripts/ci').mkdir(parents=True)
                    (root / 'scripts/ci/suites.json').write_text(json.dumps(document))
                    errors = io.StringIO()
                    with contextlib.redirect_stderr(errors), contextlib.redirect_stdout(io.StringIO()), \
                            mock.patch.object(VERIFY, 'run_step') as run_step:
                        self.assertEqual(1, VERIFY.main([mode], root))
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
        status, detail = VERIFY.run_step('x', self.step(), self.root, self.runner(3))
        self.assertEqual('FAIL', status)
        self.assertIn('exit 3', detail)
        self.assertEqual([['tool', 'one']], self.commands)

    def test_missing_tool_fails_without_running_in_both_modes(self):
        for local in (False, True):
            with mock.patch.object(VERIFY.shutil, 'which', return_value=None):
                status, detail = VERIFY.run_step('x', self.step(tools=('gh',)), self.root, self.runner(), local=local)
            self.assertEqual('FAIL', status)
            self.assertIn('missing required tool: gh', detail)
            with mock.patch.object(VERIFY, 'docker_daemon', return_value=False):
                status, detail = VERIFY.run_step('x', self.step(tools=('docker-daemon',)), self.root, self.runner(),
                                                 local=local)
            self.assertEqual('FAIL', status)
            self.assertIn('Docker daemon', detail)
        self.assertEqual([], self.commands)

    def test_missing_or_stale_reports_fail_even_after_successful_commands(self):
        step = self.step(reports=('artifacts/receipt.json', 'backend/*/target/jacoco.xml'))
        status, detail = VERIFY.run_step('x', step, self.root, self.runner(), local=True)
        self.assertEqual('FAIL', status)
        self.assertIn('artifacts/receipt.json', detail)
        stale = self.root / 'artifacts/receipt.json'
        stale.parent.mkdir()
        stale.write_text('{}')
        old = time.time() - 3600
        os.utime(stale, (old, old))
        status, detail = VERIFY.run_step('x', step, self.root, self.runner(writes=('backend/a/target/jacoco.xml',)))
        self.assertEqual('FAIL', status)
        self.assertIn('stale report: artifacts/receipt.json', detail)
        status, _ = VERIFY.run_step('x', step, self.root, self.runner(
            writes=('artifacts/receipt.json', 'backend/a/target/jacoco.xml')))
        self.assertEqual('PASS', status)

    def test_all_mode_fails_and_local_mode_skips_steps_this_host_cannot_run(self):
        darwin = self.step(host='Darwin')
        status, detail = VERIFY.run_step('macos-bootstrap', darwin, self.root, self.runner(), host='Linux')
        self.assertEqual('FAIL', status)
        self.assertIn('requires a Darwin host', detail)
        status, _ = VERIFY.run_step('macos-bootstrap', darwin, self.root, self.runner(), host='Linux', local=True)
        self.assertEqual('SKIPPED (HOST)', status)
        status, _ = VERIFY.run_step('macos-bootstrap', darwin, self.root, self.runner(), host='Darwin', local=True)
        self.assertEqual('PASS', status)
        for name in ('sast', 'windows-preflight'):
            status, detail = VERIFY.run_step(name, VERIFY.STEPS[name], self.root, self.runner())
            self.assertEqual('FAIL', status)
            self.assertIn('hosted job ' + name, detail)
            status, _ = VERIFY.run_step(name, VERIFY.STEPS[name], self.root, self.runner(), local=True)
            self.assertEqual('SKIPPED (HOSTED-ONLY)', status)
        self.assertEqual([['tool', 'one'], ['tool', 'two']], self.commands)

    def test_delivery_policy_requires_a_main_base(self):
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        status, detail = VERIFY.run_step('delivery-policy', VERIFY.Step(dynamic=('delivery',)), self.root, self.runner())
        self.assertEqual('FAIL', status)
        self.assertIn('origin/main is unavailable', detail)
        self.assertEqual([], self.commands)

    def summarize(self, document, outcomes, local):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = VERIFY.run_all(document, self.root, local=local,
                                    execute=lambda name, step, root, local: outcomes[name])
        return result, output.getvalue()

    def test_all_summary_reports_each_suite_and_any_failure_fails_the_run(self):
        document = inventory(documentation='implemented', java='implemented', angular='implemented', sast='implemented')
        outcomes = {'documentation': ('PASS', 'passed'), 'build': ('FAIL', 'command failed'), 'sast': ('FAIL', 'hosted')}
        result, text = self.summarize(document, outcomes, False)
        self.assertFalse(result)
        for suite, status in (('documentation', 'PASS'), ('java', 'FAIL'), ('angular', 'FAIL'), ('sast', 'FAIL')):
            self.assertRegex(text, status + r' +' + suite)
        self.assertIn('1 passed, 3 failed, 0 skipped of 4 implemented suites.', text)
        self.assertNotIn('NOT RELEASE EVIDENCE', text)
        result, _ = self.summarize(inventory(documentation='implemented'), {'documentation': ('PASS', 'passed')}, False)
        self.assertTrue(result)

    def test_local_summary_is_banner_marked_and_passes_only_on_runnable_success(self):
        document = inventory(documentation='implemented', sast='implemented')
        skipped = ('SKIPPED (HOSTED-ONLY)', 'CodeQL')
        result, text = self.summarize(document, {'documentation': ('PASS', 'passed'), 'sast': skipped}, True)
        self.assertTrue(result)
        self.assertEqual(2, text.count('NOT RELEASE EVIDENCE'))
        self.assertRegex(text, r'SKIPPED \(HOSTED-ONLY\) +sast')
        self.assertIn('1 passed, 0 failed, 1 skipped of 2 implemented suites.', text)
        result, _ = self.summarize(document, {'documentation': ('FAIL', 'x'), 'sast': skipped}, True)
        self.assertFalse(result)
        result, _ = self.summarize(inventory(sast='implemented'), {'sast': skipped}, True)
        self.assertFalse(result)  # Nothing ran: nothing proven.

    def test_main_selects_mode(self):
        calls = []
        with mock.patch.object(VERIFY, 'run_all', side_effect=lambda document, root, local: calls.append(local) or True):
            self.assertEqual(0, VERIFY.main(['local']))
            self.assertEqual(0, VERIFY.main(['all']))
        self.assertEqual([True, False], calls)


class WorkflowDriftTest(unittest.TestCase):
    def test_local_steps_match_hosted_jobs_and_suite_inventory(self):
        workflow = (ROOT / '.github/workflows/verify.yml').read_text()
        jobs = set(re.findall(r'^  ([a-z][a-z0-9-]*):\s*$', workflow.partition('\njobs:\n')[2], re.MULTILINE))
        self.assertIn('verify', jobs)
        self.assertEqual(jobs - {'verify'}, set(VERIFY.STEPS))
        suites = json.loads((ROOT / 'scripts/ci/suites.json').read_text())['suites']
        self.assertLessEqual(set(VERIFY.SUITE_STEPS), set(suites))
        for name, suite in suites.items():
            if suite['state'] == 'implemented':
                with self.subTest(suite=name):
                    self.assertEqual(suite['job'], VERIFY.SUITE_STEPS[name])


class WrapperTest(unittest.TestCase):
    def run_wrapper(self, *arguments):
        return subprocess.run(['bash', str(ROOT / 'verify.sh'), *arguments], capture_output=True, text=True, timeout=60)

    def test_help_unknown_and_list(self):
        result = self.run_wrapper('help')
        self.assertEqual(0, result.returncode)
        self.assertIn('Usage: ./verify.sh <command>', result.stdout)
        self.assertIn('NOT release evidence', result.stdout)
        self.assertIn('./verify.sh local', result.stdout)
        for arguments in ([], ['everything'], ['all', 'extra'], ['local', 'extra']):
            self.assertEqual(2, self.run_wrapper(*arguments).returncode)
        result = self.run_wrapper('list')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('build [java, architecture, coverage, postgres-migrations, outbox, angular]: bash scripts/ci/verify-build.sh',
                      result.stdout)
        self.assertIn('sast [sast]: hosted only', result.stdout)


if __name__ == '__main__':
    unittest.main()
