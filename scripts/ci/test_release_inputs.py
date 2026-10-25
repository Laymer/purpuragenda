"""Owner-reported availability and approved core text never substitute for launch acceptance."""
import copy
import importlib.util
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import release_inputs as GATE

ROOT = Path(__file__).resolve().parents[2]


class ReleaseInputsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / 'source'
        for name in ('ops/release-inputs.json', 'docs/policies/privacy-core.en.txt'):
            destination = self.root / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, destination)
        self.file = self.root / 'ops/release-inputs.json'
        self.document = json.loads(self.file.read_text())
        self.evidence = self.base / 'external-acceptance'
        self.evidence.mkdir()
        self.sha = 'a' * 40

    def receipts(self):
        for gate in GATE.GATES:
            (self.evidence / (gate + '.json')).write_text(json.dumps({
                'schemaVersion': 1, 'gate': gate, 'sourceRevision': self.sha,
                'verifiedAt': '2026-10-25T10:00:00Z', 'ownerApproved': True}))

    def write(self, document):
        self.file.write_text(json.dumps(document))

    def test_current_register_records_only_core_approval_and_unfinished_external_facts(self):
        document = GATE.register(ROOT)
        self.assertFalse(document['facts']['publicIngressExists'])
        self.assertFalse(document['facts']['smtpSettingsReady'])
        self.assertTrue(document['privacyCore']['version'].startswith('core-'))
        with self.assertRaises(ValueError):
            GATE.validate(self.root, release=True)

    def test_missing_or_modified_core_requires_new_owner_reconciliation(self):
        core = self.root / self.document['privacyCore']['path']
        core.write_text('synthetic unapproved policy')
        with self.assertRaises(ValueError):
            GATE.register(self.root)
        core.unlink()
        with self.assertRaises(OSError):
            GATE.register(self.root)

    def test_required_launch_gate_cannot_be_removed_duplicated_or_replaced_by_core(self):
        for gates in (list(GATE.GATES)[1:], list(GATE.GATES) + ['full-privacy-notice'],
                      [gate if gate != 'full-privacy-notice' else 'approved-core' for gate in GATE.GATES]):
            changed = copy.deepcopy(self.document)
            changed['launchGates'] = gates
            self.write(changed)
            with self.assertRaises(ValueError):
                GATE.register(self.root)

    def test_unknown_credential_or_personal_detail_fields_are_rejected(self):
        for field in ('smtpPassword', 'ownerName', 'providerError'):
            changed = copy.deepcopy(self.document)
            changed['facts'][field] = 'synthetic private marker'
            self.write(changed)
            with self.assertRaises(ValueError):
                GATE.register(self.root)

    def complete_synthetic_notice(self):
        path = self.root / 'docs/policies/privacy-notice-1.en.txt'
        path.write_text('Synthetic full-notice fixture; never deploy this text.\n')
        self.document['fullNotice'] = {'version': 'notice-1', 'path': path.relative_to(self.root).as_posix(),
                                       'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
        self.write(self.document)

    def test_core_alone_cannot_release_even_with_all_owner_receipts(self):
        self.receipts()
        with patch.object(GATE, 'revision', return_value=self.sha), self.assertRaisesRegex(ValueError, 'complete separately'):
            GATE.validate(self.root, release=True, owner_evidence=self.evidence)
        changed = copy.deepcopy(self.document)
        changed['fullNotice'] = {'version': 'notice-1', 'path': 'docs/policies/privacy-notice-1.en.txt',
                                 'sha256': changed['privacyCore']['sha256']}
        self.write(changed)
        with self.assertRaisesRegex(ValueError, 'not full-notice'):
            GATE.register(self.root)

    def test_exact_revision_external_acceptance_passes_but_source_supplied_receipts_do_not(self):
        self.receipts()
        self.complete_synthetic_notice()
        GATE.acceptance(self.evidence, self.sha)
        with patch.object(GATE, 'revision', return_value=self.sha):
            GATE.validate(self.root, release=True, owner_evidence=self.evidence)
            inside = self.root / 'acceptance'
            shutil.copytree(self.evidence, inside)
            with self.assertRaisesRegex(ValueError, 'separately'):
                GATE.validate(self.root, release=True, owner_evidence=inside)

    def test_every_receipt_and_only_safe_receipts_are_required(self):
        self.receipts()
        for gate in GATE.GATES:
            path = self.evidence / (gate + '.json')
            value = path.read_bytes()
            path.unlink()
            with self.assertRaises(ValueError):
                GATE.acceptance(self.evidence, self.sha)
            path.write_bytes(value)
        (self.evidence / 'private-provider.log').write_text('synthetic marker')
        with self.assertRaises(ValueError):
            GATE.acceptance(self.evidence, self.sha)

    def test_stale_rejected_ambiguous_or_private_receipts_fail(self):
        self.receipts()
        gate = 'deployment-decision'
        path = self.evidence / (gate + '.json')
        original = json.loads(path.read_text())
        for changes in ({'sourceRevision': 'b' * 40}, {'ownerApproved': False}, {'ownerApproved': 'true'},
                        {'verifiedAt': '2026-10-25T10:00:00'}, {'verifiedAt': None},
                        {'gate': 'other'}, {'schemaVersion': True}, {'privateDetails': 'synthetic marker'}):
            path.write_text(json.dumps(original | changes))
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                GATE.acceptance(self.evidence, self.sha)

    def test_linked_oversized_and_nonregular_inputs_are_not_read(self):
        core = self.root / self.document['privacyCore']['path']
        core.unlink()
        outside = self.base / 'outside'; outside.write_text('synthetic private marker')
        core.symlink_to(outside)
        with self.assertRaises(OSError):
            GATE.register(self.root)
        core.unlink(); core.write_bytes(b'x' * 65537)
        with self.assertRaises(ValueError):
            GATE.register(self.root)
        with self.assertRaises(ValueError):
            GATE.read_safe(self.root, '../outside')
        directory = self.root / 'directory'; directory.mkdir()
        with self.assertRaises((ValueError, OSError)):
            GATE.read_safe(self.root, 'directory')

    def test_duplicate_acceptance_fields_and_linked_parent_are_rejected(self):
        self.receipts()
        receipt = self.evidence / 'deployment-decision.json'
        receipt.write_text(receipt.read_text().replace('"ownerApproved": true', '"ownerApproved": false, "ownerApproved": true'))
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            GATE.acceptance(self.evidence, self.sha)
        parent = self.root / 'docs/policies'
        outside = self.base / 'outside-policies'
        shutil.move(parent, outside)
        parent.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(OSError):
            GATE.register(self.root)
        self.assertTrue((outside / 'privacy-core.en.txt').is_file())

    def test_all_automated_categories_still_require_owner_acceptance_at_release(self):
        spec = importlib.util.spec_from_file_location('owner_suite_gate', ROOT / 'scripts/ci/check-suites.py')
        suites = importlib.util.module_from_spec(spec); spec.loader.exec_module(suites)
        with patch.object(suites, 'inventory', return_value={'documentation'}), patch.object(suites, 'workflow'), \
                patch.object(suites, 'validate_release_inputs', side_effect=ValueError('owner acceptance missing')) as owner:
            with self.assertRaisesRegex(ValueError, 'owner acceptance missing'):
                suites.validate(ROOT, release=True)
            owner.assert_called_once_with(ROOT, release=True, owner_evidence=None)

    def test_input_gate_cannot_disappear_from_pr_workflow(self):
        spec = importlib.util.spec_from_file_location('owner_workflow_gate', ROOT / 'scripts/ci/check-suites.py')
        suites = importlib.util.module_from_spec(spec); spec.loader.exec_module(suites)
        text = (ROOT / '.github/workflows/verify.yml').read_text()
        jobs = suites.inventory(json.loads((ROOT / 'scripts/ci/suites.json').read_text()), ROOT)
        command = '      - run: python3 scripts/ci/check-release-inputs.py'
        for replacement in ('', '      - run: true # python3 scripts/ci/check-release-inputs.py',
                            '      # python3 scripts/ci/check-release-inputs.py',
                            command + ' || true', command + '; true'):
            with self.subTest(replacement=replacement), self.assertRaisesRegex(ValueError, 'Owner input validation'):
                suites.workflow(text.replace(command, replacement), jobs)
