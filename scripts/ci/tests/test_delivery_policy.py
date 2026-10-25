"""Synthetic repository and API cases for commit and protection enforcement."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), SCRIPTS / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


commits = load('check-commit-tests')
protection = load('main-protection')


class CommitPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Synthetic Tests')
        self.git('config', 'user.email', 'tests@example.test')
        self.write('README.md', 'Initial synthetic repository\n')
        self.base = self.commit('historical baseline')

    def git(self, *args):
        return commits.git(self.root, *args).strip()

    def write(self, name, text):
        target = self.root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)

    def commit(self, message):
        self.git('add', '.')
        self.git('commit', '-qm', message)
        return self.git('rev-parse', 'HEAD')

    def validate(self, head=None):
        return commits.validate(self.root, self.base, head or self.git('rev-parse', 'HEAD'), self.base)

    def test_added_java_test_passes(self):
        self.write('backend/domain/src/test/java/example/BookingTest.java',
                   'class BookingTest { void boundary() { assertEquals(1, 1); } }\n')
        self.assertEqual([], self.validate(self.commit('unit test')))

    def test_each_commit_checked_even_if_later_commit_has_tests(self):
        self.write('README.md', 'Updated synthetic documentation\n')
        bad = self.commit('untested change')
        self.write('scripts/ci/tests/test_example.py', 'assert 1 == 1\n')
        errors = self.validate(self.commit('tested change'))
        self.assertEqual(1, len(errors))
        self.assertIn(bad[:12], errors[0])

    def test_history_is_not_retroactively_rejected(self):
        self.assertEqual([], self.validate(self.base))

    def test_updated_frontend_test_passes(self):
        self.write('frontend/src/app/example.spec.ts', 'expect(result).toBe(1);\n')
        first = self.commit('add frontend test')
        self.write('frontend/src/app/example.spec.ts', 'expect(result).toBe(2);\n')
        self.assertEqual([], commits.validate(self.root, first, self.commit('update test'), self.base))

    def test_comment_only_and_whitespace_changes_fail(self):
        self.write('scripts/ci/tests/test_example.py', 'assert result == 1\n')
        first = self.commit('add test')
        self.write('scripts/ci/tests/test_example.py', '# more commentary\n  assert result == 1\n')
        self.assertEqual(1, len(commits.validate(self.root, first, self.commit('comments only'), self.base)))

    def test_delete_or_rename_without_changed_assertion_fails(self):
        self.write('tests/test_before.py', 'assert result == 1\n')
        first = self.commit('add test')
        self.git('mv', 'tests/test_before.py', 'tests/test_after.py')
        self.assertEqual(1, len(commits.validate(self.root, first, self.commit('rename only'), self.base)))
        (self.root / 'tests/test_after.py').unlink()
        self.assertEqual(2, len(commits.validate(self.root, first, self.commit('delete test'), self.base)))

    def test_non_test_file_named_test_inside_production_fails(self):
        self.write('backend/domain/src/main/java/Test.java', 'assert value == 1;\n')
        self.assertEqual(1, len(self.validate(self.commit('production file'))))

    def test_merge_inherits_real_test_changes_from_second_parent(self):
        main = self.git('branch', '--show-current')
        self.git('checkout', '-qb', 'feature')
        self.write('README.md', 'Branch documentation\n')
        self.commit('feature history')
        self.git('checkout', '-q', main)
        self.write('tests/test_integration.py', 'assert integrity_check()\n')
        updated = self.commit('integrated test')
        self.git('checkout', '-q', 'feature')
        self.git('merge', '--no-ff', '-qm', 'integrate tested main', main)
        # Only the merge is new relative to the feature's previous commit.
        parent = self.git('rev-parse', 'HEAD^1')
        self.assertEqual([], commits.validate(self.root, parent, self.git('rev-parse', 'HEAD'), updated))

    def test_missing_history_fails_closed(self):
        with self.assertRaises(subprocess.CalledProcessError):
            commits.validate(self.root, self.base, 'missing', self.base)

    def test_shallow_history_fails_closed(self):
        (self.root / '.git/shallow').write_text(self.base + '\n')
        with self.assertRaises(ValueError):
            self.validate()


class ProtectionTest(unittest.TestCase):
    def setUp(self):
        self.expected = json.loads(protection.CONFIG.read_text())
        self.managed = dict(copy.deepcopy(self.expected), id=25)
        self.calls = []

    def api(self, method, endpoint, payload=None):
        self.calls.append((method, endpoint, payload))
        if '?' in endpoint:
            return [{'id': 1, 'name': 'nodelete'}, {'id': 25, 'name': self.expected['name']}]
        if method == 'PUT':
            self.managed = dict(copy.deepcopy(payload), id=25)
        return copy.deepcopy(self.managed)

    def test_expected_configuration_requires_all_agreed_gates(self):
        self.assertEqual([], self.expected['bypass_actors'])
        self.assertEqual('active', self.expected['enforcement'])
        rules = {rule['type']: rule for rule in self.expected['rules']}
        self.assertTrue({'deletion', 'non_fast_forward', 'pull_request', 'required_status_checks'} <= rules.keys())
        checks = rules['required_status_checks']['parameters']
        self.assertTrue(checks['strict_required_status_checks_policy'])
        self.assertFalse(checks['do_not_enforce_on_create'])
        self.assertEqual([{'context': 'verify', 'integration_id': 15368}], checks['required_status_checks'])
        self.assertEqual(['refs/heads/main'], self.expected['conditions']['ref_name']['include'])
        self.assertEqual([], self.expected['conditions']['ref_name']['exclude'])
        pr = rules['pull_request']['parameters']
        self.assertTrue(pr['required_review_thread_resolution'])
        self.assertEqual(['squash'], pr['allowed_merge_methods'])

    def test_read_only_check_never_writes(self):
        self.assertEqual([], protection.reconcile(self.api, self.expected))
        self.assertEqual({'GET'}, {call[0] for call in self.calls})

    def test_missing_ruleset_fails_without_applying(self):
        self.assertEqual(['Managed main ruleset is missing'],
                         protection.reconcile(lambda *args: [], self.expected))

    def test_apply_changes_only_managed_ruleset_and_reads_back(self):
        self.assertEqual([], protection.reconcile(self.api, self.expected, apply=True))
        writes = [call for call in self.calls if call[0] != 'GET']
        self.assertEqual(1, len(writes))
        self.assertTrue(writes[0][1].endswith('/rulesets/25'))
        self.assertEqual('GET', self.calls[-1][0])

    def test_apply_readback_rejects_ineffective_api_update(self):
        self.managed['enforcement'] = 'disabled'
        def api(method, endpoint, payload=None):
            if '?' in endpoint:
                return [{'id': 25, 'name': self.expected['name']}]
            # Simulate a provider accepting PUT without actually enforcing it.
            return copy.deepcopy(self.managed)
        self.assertIn('Ruleset differs: enforcement',
                      protection.reconcile(api, self.expected, apply=True))

    def test_absent_ruleset_created_then_verified(self):
        def api(method, endpoint, payload=None):
            if '?' in endpoint:
                return []
            self.calls.append((method, endpoint, payload))
            return self.managed
        self.assertEqual([], protection.reconcile(api, self.expected, apply=True))
        self.assertEqual(['POST', 'GET'], [call[0] for call in self.calls])

    def test_duplicate_managed_rulesets_fail(self):
        with self.assertRaises(ValueError):
            protection.reconcile(lambda *args: [{'id': 1, 'name': self.expected['name']}] * 2,
                                 self.expected)

    def test_missing_or_weakened_gate_is_rejected(self):
        cases = []
        changed = copy.deepcopy(self.expected)
        changed['bypass_actors'] = [{'actor_id': 5, 'actor_type': 'RepositoryRole', 'bypass_mode': 'always'}]
        cases.append(changed)
        changed = copy.deepcopy(self.expected)
        changed['enforcement'] = 'disabled'
        cases.append(changed)
        changed = copy.deepcopy(self.expected)
        changed['rules'] = changed['rules'][1:]
        cases.append(changed)
        changed = copy.deepcopy(self.expected)
        changed['rules'][-1]['parameters']['strict_required_status_checks_policy'] = False
        cases.append(changed)
        for actual in cases:
            with self.subTest(actual=actual):
                self.assertTrue(protection.discrepancies(actual, self.expected))

    def test_ruleset_pagination_is_complete(self):
        def api(method, endpoint, payload=None):
            if endpoint.endswith('&page=1'):
                return [{'id': i, 'name': 'unrelated-' + str(i)} for i in range(100)]
            if endpoint.endswith('&page=2'):
                return [{'id': 25, 'name': self.expected['name']}]
            return self.managed
        self.assertEqual([], protection.reconcile(api, self.expected))

    def test_stronger_review_count_is_not_overwritten(self):
        self.managed['rules'][2]['parameters']['required_approving_review_count'] = 1
        with self.assertRaises(ValueError):
            protection.reconcile(self.api, self.expected, apply=True)
        self.assertEqual({'GET'}, {call[0] for call in self.calls})

    def test_unexpected_stronger_rules_not_overwritten(self):
        self.managed['rules'].append({'type': 'required_signatures'})
        with self.assertRaises(ValueError):
            protection.reconcile(self.api, self.expected, apply=True)
        self.assertEqual({'GET'}, {call[0] for call in self.calls})


if __name__ == '__main__':
    unittest.main()
