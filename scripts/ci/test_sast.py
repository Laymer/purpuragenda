"""Synthetic CodeQL result/source/tool failures must prevent passing SAST receipts."""
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('sast_gate', Path(__file__).with_name('check-sast.py'))
GATE = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(GATE)


class SastTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.language = 'java-kotlin'; self.files = {'backend/Main.java'}
        rule = {'id': 'java/synthetic-security', 'properties': {'security-severity': '6.9'}}
        self.document = {'version': '2.1.0', 'runs': [{
            'tool': {'driver': {'name': 'CodeQL', 'semanticVersion': '2.27.3', 'rules': [rule]}},
            'automationDetails': {'id': 'purpuragenda/java-kotlin/'},
            'invocations': [{'executionSuccessful': True}],
            'artifacts': [{'location': {'uri': 'backend/Main.java'}}], 'results': []}]}
        self.pin = json.loads((ROOT / 'ops/security-tools.lock.json').read_text())['codeql']

    def check(self, document=None):
        return GATE.sarif(self.document if document is None else document, self.language,
                          self.root, self.files, '2.27.3')

    def finding(self, score):
        self.document['runs'][0]['tool']['driver']['rules'][0]['properties']['security-severity'] = score
        self.document['runs'][0]['results'] = [{'ruleId': 'java/synthetic-security', 'ruleIndex': 0}]

    def test_clean_and_medium_findings_pass(self):
        self.assertTrue(self.check()['passed'])
        self.finding('6.9')
        self.assertTrue(self.check()['passed'])
        self.assertEqual(1, self.check()['findings'])

    def test_official_cli_driver_identity_and_untrusted_identity(self):
        self.document['runs'][0]['tool']['driver']['name'] = 'CodeQL command-line toolchain'
        self.assertTrue(self.check()['passed'])
        self.document['runs'][0]['tool']['driver']['name'] = 'other tool'
        with self.assertRaises(ValueError): self.check()

    def test_high_and_critical_scores_block(self):
        for score in ('7.0', '9.8', '10'):
            with self.subTest(score=score):
                self.finding(score)
                self.assertFalse(self.check()['passed'])
                self.assertEqual(1, self.check()['highOrCritical'])

    def test_safe_blocking_counts_identify_only_public_rules_without_source_messages(self):
        self.finding('9.8')
        self.document['runs'][0]['results'] *= 2
        self.assertEqual({'java/synthetic-security': 2}, self.check()['blockingRules'])
        self.finding('6.9')
        self.assertEqual({}, self.check()['blockingRules'])

    def test_unclassified_invalid_or_suppressed_findings_fail(self):
        for score in (None, True, 'NaN', 'Infinity', '-1', '11', 'invalid'):
            self.finding(score)
            with self.subTest(score=score), self.assertRaises(ValueError): self.check()
        self.finding('9.8'); self.document['runs'][0]['results'][0]['suppressions'] = [{'kind': 'external'}]
        with self.assertRaisesRegex(ValueError, 'suppression'): self.check()

    def test_missing_run_tool_rules_or_results_fail(self):
        cases = [{}, dict(self.document, runs=[]), dict(self.document, runs=[{}, {}])]
        for key in ('tool', 'results', 'invocations'):
            changed = copy.deepcopy(self.document); del changed['runs'][0][key]; cases.append(changed)
        changed = copy.deepcopy(self.document); changed['runs'][0]['tool']['driver']['rules'] = []; cases.append(changed)
        for document in cases:
            with self.subTest(document=document), self.assertRaises(ValueError): self.check(document)

    def test_source_correspondence_cannot_pass_a_subset(self):
        self.document['runs'][0]['artifacts'] = []
        with self.assertRaisesRegex(ValueError, 'omits tracked'): self.check()
        self.assertIsNone(GATE.source_uri('../outside.java', self.root))
        self.assertIsNone(GATE.source_uri('file://otherhost/backend/Main.java', self.root))
        self.assertEqual('backend/Main.java', GATE.source_uri((self.root / 'backend/Main.java').as_uri(), self.root))

    def test_failed_execution_or_wrong_category_or_version_fails(self):
        for path, value in (('invocations', [{'executionSuccessful': False}]),
                            ('automationDetails', {'id': 'other/python'})):
            changed = copy.deepcopy(self.document); changed['runs'][0][path] = value
            with self.subTest(path=path), self.assertRaises(ValueError): self.check(changed)
        self.document['runs'][0]['tool']['driver']['semanticVersion'] = 'other'
        with self.assertRaises(ValueError): self.check()

    def test_snippets_and_full_source_contents_are_rejected(self):
        for private in ({'location': {'uri': 'backend/Main.java'}, 'contents': {'text': 'synthetic-private-marker'}},
                        {'location': {'uri': 'backend/Main.java'}, 'region': {'snippet': {'text': 'synthetic-private-marker'}}}):
            self.document['runs'][0]['artifacts'] = [private]
            with self.assertRaisesRegex(ValueError, 'snippets'): self.check()

    def test_extension_rule_indices_and_ambiguity_are_validated(self):
        self.finding('8.0'); driver = self.document['runs'][0]['tool']['driver']
        extension = {'name': 'codeql/java-queries', 'rules': driver.pop('rules')}
        self.document['runs'][0]['tool']['extensions'] = [extension]
        result = self.document['runs'][0]['results'][0]
        result['rule'] = {'id': result['ruleId'], 'index': 0, 'toolComponent': {'index': 0}}
        self.assertFalse(self.check()['passed'])
        result['rule']['toolComponent']['index'] = -1
        with self.assertRaises(ValueError): self.check()
        result['rule']['toolComponent']['index'] = 0
        driver['rules'] = extension['rules']
        with self.assertRaisesRegex(ValueError, 'ambiguous'): self.check()

    def test_official_pins_config_and_all_three_matrix_cells_are_enforced(self):
        GATE.configuration(ROOT, self.pin)
        self.assertEqual('2.27.3', self.pin['version'])
        self.assertEqual('9b3254085494b55a0f7c4c2b3d2d52cc712ce370', self.pin['actionCommit'])
        self.assertEqual(set(GATE.LANGUAGES), set(self.pin['bundles']))
        for pin in self.pin['bundles'].values():
            self.assertTrue(pin['url'].startswith('https://github.com/github/codeql-action/releases/download/codeql-bundle-v2.27.3/'))
            self.assertRegex(pin['sha256'], r'^[a-f0-9]{64}$')
        config = self.root / '.github/codeql/config.yml'; config.parent.mkdir(parents=True)
        config.write_text((ROOT / '.github/codeql/config.yml').read_text())
        workflow = self.root / '.github/workflows/verify.yml'; workflow.parent.mkdir(parents=True)
        text = (ROOT / '.github/workflows/verify.yml').read_text()
        for old, new in (('          - language: python\n            build-mode: none\n', ''),
                         ('fail-fast: false', 'fail-fast: true'), (self.pin['actionCommit'], 'floating')):
            workflow.write_text(text.replace(old, new))
            with self.subTest(old=old), self.assertRaises(ValueError): GATE.configuration(self.root, self.pin)
        workflow.write_text(text)
        changed = json.loads(config.read_text()); changed['paths-ignore'] = ['**']
        config.write_text(json.dumps(changed))
        with self.assertRaises(ValueError): GATE.configuration(self.root, self.pin)

    def test_tool_dependencies_are_relocated_before_extraction_without_ignoring_project_sources(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / '.github/workflows').mkdir(parents=True)
            (root / '.github/codeql').mkdir()
            shutil.copyfile(ROOT / '.github/codeql/config.yml', root / '.github/codeql/config.yml')
            workflow = (ROOT / '.github/workflows/verify.yml').read_text()
            (root / '.github/workflows/verify.yml').write_text(workflow)
            GATE.configuration(root, self.pin)
            altered = workflow.replace('mv .toolchain "$tool_root/toolchain"', ':')
            (root / '.github/workflows/verify.yml').write_text(altered)
            with self.assertRaisesRegex(ValueError, 'tool dependencies'):
                GATE.configuration(root, self.pin)

    def test_bundle_checksum_size_and_transport_failures(self):
        data = b'synthetic bundle'
        pin = {'url': 'https://synthetic.invalid', 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
        bundle = GATE.download(pin, lambda *a, **k: io.BytesIO(data)); self.addCleanup(shutil.rmtree, bundle.parent)
        self.assertEqual(data, bundle.read_bytes())
        for altered in (dict(pin, size=1), dict(pin, sha256='0' * 64)):
            with self.subTest(pin=altered), self.assertRaises(ValueError):
                GATE.download(altered, lambda *a, **k: io.BytesIO(data))
        def unavailable(*args, **kwargs): raise OSError('synthetic provider error')
        with self.assertRaises(OSError): GATE.download(pin, unavailable)

    def test_missing_tool_and_report_erase_old_passing_receipt(self):
        receipt = self.root / 'artifacts/security/sast-java-kotlin.json'
        receipt.parent.mkdir(parents=True); receipt.write_text('{"passed":true}')
        with self.assertRaisesRegex(ValueError, 'missing'):
            GATE.run(self.root, self.language, None, self.root, self.pin)
        self.assertFalse(receipt.exists())
        tool = self.root / 'codeql'; tool.write_text('synthetic'); tool.chmod(0o755)
        with patch.object(subprocess, 'check_output', return_value=b'{"version":"2.27.3"}'):
            with self.assertRaisesRegex(ValueError, 'SARIF'):
                GATE.run(self.root, self.language, str(tool), self.root, self.pin)

    def test_runtime_receipt_blocks_high_findings_without_retaining_messages(self):
        tool = self.root / 'codeql'; tool.write_text('synthetic'); tool.chmod(0o755)
        report = self.root / 'java.sarif'
        source = self.root / 'backend/Main.java'; source.parent.mkdir(); source.write_text('class Main {}')
        database = self.root / 'database'; database.mkdir()
        with zipfile.ZipFile(database / 'src.zip', 'w') as archive:
            archive.write(source, str(source).lstrip('/'))
        for score in ('6.9', '8.0'):
            self.finding(score)
            self.document['runs'][0]['results'][0]['message'] = {'text': 'synthetic-private-marker'}
            report.write_text(json.dumps(self.document))
            with patch.object(subprocess, 'check_output', return_value=b'{"version":"2.27.3"}'), \
                    patch.object(GATE, 'sources', return_value=self.files):
                if score == '8.0':
                    with self.assertRaisesRegex(ValueError, 'High or critical'):
                        GATE.run(self.root, self.language, str(tool), self.root, self.pin, database)
                else:
                    self.assertTrue(GATE.run(self.root, self.language, str(tool), self.root, self.pin, database)['passed'])
            text = (self.root / 'artifacts/security/sast-java-kotlin.json').read_text()
            self.assertNotIn('synthetic-private-marker', text)
            self.assertEqual(score == '6.9', json.loads(text)['passed'])

    def test_manual_compile_forces_real_java_extraction_without_test_skip_flags(self):
        with patch.object(sys, 'argv', ['check-sast.py', '--language', 'java-kotlin', '--compile']), \
                patch.object(GATE, 'configuration'), patch.object(GATE, 'sources', return_value=self.files), \
                patch.object(subprocess, 'run') as invoked:
            GATE.main()
        command = invoked.call_args.args[0]
        self.assertEqual(['clean', 'test-compile'], command[-2:])
        self.assertFalse(any('skip' in argument.lower() for argument in command))
        self.assertEqual(GATE.ROOT, invoked.call_args.kwargs['cwd'])

    def test_source_archive_must_capture_exact_checkout_bytes(self):
        source = self.root / 'backend/Main.java'; source.parent.mkdir(); source.write_text('class Main {}')
        database = self.root / 'database'; database.mkdir()
        with self.assertRaisesRegex(ValueError, 'archive is missing'):
            GATE.captured_sources(self.root, database, self.files)
        with zipfile.ZipFile(database / 'src.zip', 'w') as archive:
            archive.writestr('backend/Main.java', 'stale source')
        with self.assertRaisesRegex(ValueError, 'differs'):
            GATE.captured_sources(self.root, database, self.files)
        with zipfile.ZipFile(database / 'src.zip', 'w') as archive:
            archive.writestr('other.java', 'unrelated')
        with self.assertRaisesRegex(ValueError, 'omitted'):
            GATE.captured_sources(self.root, database, self.files)
        with zipfile.ZipFile(database / 'src.zip', 'w') as archive:
            archive.write(source, str(source).lstrip('/'))
        GATE.captured_sources(self.root, database, self.files)

    def test_tracked_source_selection_and_symlink_refusal(self):
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        source = self.root / 'backend/Main.java'; source.parent.mkdir(); source.write_text('class Main {}')
        subprocess.run(['git', '-C', str(self.root), 'add', '.'], check=True)
        self.assertEqual(self.files, GATE.sources(self.root, self.language))
        source.unlink(); source.symlink_to(self.root / 'outside.java')
        with self.assertRaisesRegex(ValueError, 'symlinks'): GATE.sources(self.root, self.language)


if __name__ == '__main__':
    unittest.main()
