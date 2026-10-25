"""A passing subset, skipped job or truncated inventory must never certify a PR/release."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('suite_gate', Path(__file__).with_name('check-suites.py'))
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class SuiteInventoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.document = json.loads((ROOT / 'scripts/ci/suites.json').read_text())
        self.text = (ROOT / '.github/workflows/verify.yml').read_text()
        for category, path in GATE.ACTIVATION.items():
            if self.document['suites'][category]['state'] == 'implemented':
                marker = self.root / path
                marker.parent.mkdir(parents=True, exist_ok=True)
                marker.write_text('synthetic implementation marker')
        self.jobs = GATE.inventory(self.document, self.root)

    def test_current_repository_inventory_and_workflow_agree(self):
        self.assertEqual(self.jobs, GATE.validate(ROOT))

    def test_missing_or_unknown_category_fails(self):
        for category in GATE.OWNERS:
            changed = copy.deepcopy(self.document)
            del changed['suites'][category]
            with self.subTest(category=category), self.assertRaises(ValueError):
                GATE.inventory(changed, self.root)
        changed['suites']['invented'] = {}
        with self.assertRaises(ValueError):
            GATE.inventory(changed, self.root)

    def test_invalid_owner_state_or_self_dependency_fails(self):
        for field, value in (('owner', 'P50'), ('state', 'passed'), ('job', 'verify'), ('job', None)):
            changed = copy.deepcopy(self.document)
            changed['suites']['documentation'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                GATE.inventory(changed, self.root)

    def test_planned_suite_never_claims_an_existing_job(self):
        changed = copy.deepcopy(self.document)
        changed['suites']['restore'].update(state='planned', job='build')
        with self.assertRaises(ValueError):
            GATE.inventory(changed, self.root)

    def test_new_source_requires_atomic_activation(self):
        for category, path in GATE.ACTIVATION.items():
            with self.subTest(category=category):
                source = self.root / path
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text('synthetic implementation marker')
                pending = copy.deepcopy(self.document)
                pending['suites'][category].update(state='planned', job=None)
                with self.assertRaisesRegex(ValueError, 'Source implementation requires suite activation: ' + category):
                    GATE.inventory(pending, self.root)
                source.unlink()
                if self.document['suites'][category]['state'] == 'implemented':
                    source.write_text('synthetic implementation marker')

    def test_release_rejects_any_unimplemented_category(self):
        incomplete = copy.deepcopy(self.document)
        incomplete['suites']['restore'].update(state='planned', job=None)
        with self.assertRaises(ValueError):
            GATE.inventory(incomplete, self.root, release=True)
        complete = copy.deepcopy(self.document)
        for item in complete['suites'].values():
            item.update(state='implemented', job='synthetic-checks')
        for path in GATE.ACTIVATION.values():
            marker = self.root / path
            marker.parent.mkdir(parents=True, exist_ok=True)
            marker.write_text('synthetic implementation marker')
        self.assertEqual({'synthetic-checks'}, GATE.inventory(complete, self.root, release=True))

    def test_aggregate_missing_dependency_or_run_guard_fails(self):
        for before, after in (('delivery-policy, ', ''), ('if: always()', 'if: success()'),
                              ('python3 scripts/ci/check-suites.py --results', 'echo passed')):
            with self.subTest(before=before), self.assertRaises(ValueError):
                GATE.workflow(self.text.replace(before, after), self.jobs)

    def test_unregistered_job_and_conditional_jobs_fail(self):
        for text in (self.text + '\n  unexpected:\n    runs-on: ubuntu-24.04\n',
                     self.text.replace('  build:\n', '  build:\n    if: false\n'),
                     self.text.replace('  build:\n', '  build:\n    continue-on-error: true\n'),
                     self.text.replace('  build:\n', '  build:\n    paths-ignore: [docs/**]\n')):
            with self.subTest(text=text[-40:]), self.assertRaises(ValueError):
                GATE.workflow(text, self.jobs)

    def test_all_success_and_only_complete_results_pass(self):
        successful = {name: {'result': 'success'} for name in self.jobs}
        GATE.results(successful, self.jobs)
        for state in ('failure', 'skipped', 'cancelled', 'neutral', '', None):
            partial = copy.deepcopy(successful)
            partial['build']['result'] = state
            with self.subTest(state=state), self.assertRaises(ValueError):
                GATE.results(partial, self.jobs)
        for partial in ({}, {**successful, 'invented': {'result': 'success'}}):
            with self.assertRaises(ValueError):
                GATE.results(partial, self.jobs)


    def test_suite_steps_cannot_skip_or_hide_failures(self):
        for setting in ('if: false', 'if: failure()', 'continue-on-error: true'):
            changed = self.text.replace('      - run: bash scripts/ci/verify-build.sh',
                                        '      - run: bash scripts/ci/verify-build.sh\n        ' + setting)
            with self.subTest(setting=setting), self.assertRaises(ValueError):
                GATE.workflow(changed, self.jobs)
        GATE.workflow(self.text, self.jobs)  # always() receipt upload remains valid.

    def test_implemented_category_cannot_lose_its_source(self):
        for category in GATE.ACTIVATION:
            changed = copy.deepcopy(self.document)
            changed['suites'][category].update(state='implemented', job='build')
            marker = self.root / GATE.ACTIVATION[category]
            if marker.exists():
                marker.unlink()
            with self.subTest(category=category), self.assertRaises(ValueError):
                GATE.inventory(changed, self.root)
            if self.document['suites'][category]['state'] == 'implemented':
                marker.write_text('synthetic implementation marker')


    def test_first_key_conditions_and_aggregate_step_conditions_fail(self):
        command = '      - run: bash scripts/ci/verify-build.sh'
        for setting in ('if: false', 'if : false', 'continue-on-error: true'):
            changed = self.text.replace(command, '      - ' + setting + '\n        run: bash scripts/ci/verify-build.sh')
            with self.subTest(setting=setting), self.assertRaises(ValueError):
                GATE.workflow(changed, self.jobs)
        changed = self.text.replace('        run: python3 scripts/ci/check-suites.py --results',
                                    '        if: false\n        run: python3 scripts/ci/check-suites.py --results')
        with self.assertRaises(ValueError):
            GATE.workflow(changed, self.jobs)
        changed = self.text.replace(command, '      - {run: bash scripts/ci/verify-build.sh, if: false}')
        with self.assertRaises(ValueError):
            GATE.workflow(changed, self.jobs)


if __name__ == '__main__':
    unittest.main()
