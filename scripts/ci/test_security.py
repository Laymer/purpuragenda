"""Fail-closed secret scanner and checksum installation tests with synthetic inputs."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


GATE = load('secret_gate', 'scripts/ci/check-secrets.py')
INSTALL = load('security_install', 'scripts/dev/install-security-tools.py')


class SecurityGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.report = self.root / 'report.json'
        self.config, self.ignore = self.root / 'config', self.root / 'ignore'
        self.config.write_text('[extend]\nuseDefault=true\n')
        self.ignore.write_text('')

    def scan(self, code=0, document=None, write=True):
        def runner(command, **kwargs):
            self.assertEqual(subprocess.DEVNULL, kwargs['stdout'])
            self.assertEqual(subprocess.DEVNULL, kwargs['stderr'])
            self.assertIn('--redact=100', command)
            self.assertIn('--ignore-gitleaks-allow', command)
            self.assertIn('--log-opts=--all', command)
            self.assertIn('--report-format=template', command)
            if write:
                self.report.write_text(json.dumps([] if document is None else document))
            return subprocess.CompletedProcess(command, code)
        return GATE.scan(Path('synthetic-tool'), 'git', self.root, self.report,
                         self.config, self.ignore, runner)

    def test_clean_and_detected_reports(self):
        self.assertEqual(0, self.scan())
        self.assertEqual(1, self.scan(1, [{'finding': True}]))
        self.assertEqual('[{{range $i, $f := .}}{{if $i}},{{end}}{"finding":true}{{end}}]',
                         (self.root / 'count-only.tmpl').read_text())

    def test_missing_report_fails(self):
        with self.assertRaisesRegex(ValueError, 'report is missing'):
            self.scan(write=False)

    def test_stale_report_cannot_satisfy_missing_current_report(self):
        self.report.write_text('[]')
        with self.assertRaisesRegex(ValueError, 'report is missing'):
            self.scan(write=False)

    def test_malformed_json_report_fails(self):
        def runner(command, **kwargs):
            self.report.write_text('{invalid')
            return subprocess.CompletedProcess(command, 0)
        with self.assertRaises(ValueError):
            GATE.scan(Path('synthetic-tool'), 'git', self.root, self.report,
                      self.config, self.ignore, runner)

    def test_scanner_error_never_counts_as_no_findings(self):
        for code in (2, 126, -1):
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, 'execution failed'):
                self.scan(code)

    def test_malformed_or_sensitive_report_fails(self):
        for document in ({}, [None], [{'Secret': 'synthetic-private-marker'}], [{'finding': False}], [{'finding': 1}]):
            with self.subTest(document=document), self.assertRaisesRegex(ValueError, 'Invalid'):
                self.scan(1, document)

    def test_exit_code_and_report_must_agree(self):
        for code, report in ((0, [{'finding': True}]), (1, [])):
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, 'disagree'):
                self.scan(code, report)

    def test_missing_tool_invalidates_stale_passing_receipt(self):
        self.report.write_text('{"passed":true}')
        with self.assertRaisesRegex(ValueError, 'unavailable'):
            GATE.run(self.root, self.root / 'absent', {}, self.report)
        self.assertFalse(self.report.exists())

    def test_binary_checksum_and_version_are_required(self):
        binary = self.root / 'scanner'
        binary.write_bytes(b'synthetic tool')
        binary.chmod(0o755)
        pin = {'binarySha256': hashlib.sha256(binary.read_bytes()).hexdigest(), 'version': '8.30.1'}
        with patch.object(subprocess, 'check_output', return_value='8.30.1\n'):
            GATE.verify_binary(binary, pin)
        with patch.object(subprocess, 'check_output', return_value='other'):
            with self.assertRaisesRegex(ValueError, 'version'):
                GATE.verify_binary(binary, pin)
        binary.write_bytes(b'tampered tool')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            GATE.verify_binary(binary, pin)

    def test_shallow_history_is_rejected(self):
        with patch.object(GATE, 'verify_binary'), patch.object(GATE, 'git', return_value=b'true'):
            with self.assertRaisesRegex(ValueError, 'complete Git history'):
                GATE.run(self.root, self.root / 'scanner', {}, self.report)

    def test_tracked_copy_excludes_ignored_files_and_does_not_follow_links(self):
        GATE.git(self.root, 'init', '-q')
        (self.root / 'source.txt').write_text('synthetic public source')
        ignored = self.root / 'ignored-private.txt'
        ignored.write_text('synthetic-private-marker')
        (self.root / 'pointer').symlink_to(ignored)
        GATE.git(self.root, 'add', 'source.txt', 'pointer')
        destination = self.root / 'copy'
        destination.mkdir()
        self.assertEqual(2, GATE.tracked_copy(self.root, destination))
        self.assertFalse((destination / 'ignored-private.txt').exists())
        self.assertEqual(str(ignored), (destination / 'pointer').read_text())
        self.assertFalse((destination / 'pointer').is_symlink())

    def test_tracked_directory_replaced_by_symlink_never_reads_external_bytes(self):
        GATE.git(self.root, 'init', '-q')
        tracked = self.root / 'tracked'
        tracked.mkdir()
        (tracked / 'source.txt').write_text('synthetic tracked source')
        GATE.git(self.root, 'add', 'tracked/source.txt')
        tracked.rename(self.root / 'original')
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'source.txt').write_text('synthetic-private-outside-marker')
        tracked.symlink_to(outside, target_is_directory=True)
        destination = self.root / 'copy'
        destination.mkdir()
        with self.assertRaisesRegex(ValueError, 'ancestors must not be symlinks'):
            GATE.tracked_copy(self.root, destination)
        self.assertFalse((destination / 'tracked/source.txt').exists())
        self.assertFalse(any(file.is_file() for file in destination.rglob('*')))

    def test_no_tracked_files_is_a_failure(self):
        GATE.git(self.root, 'init', '-q')
        with self.assertRaisesRegex(ValueError, 'No tracked sources'):
            GATE.tracked_copy(self.root, self.root / 'copy')

    def test_invalid_tracked_path_fails(self):
        with patch.object(GATE, 'git', return_value=b'../outside\0'):
            with self.assertRaisesRegex(ValueError, 'Invalid tracked'):
                GATE.tracked_copy(self.root, self.root / 'copy')

    def test_safe_receipt_contains_no_finding_or_provider_data(self):
        reports = []
        def scan(binary, mode, target, report, config, ignore):
            reports.append(report.name)
            return 1 if report.name in ('positive.json', 'history.json') else 0
        with patch.object(GATE, 'verify_binary'), patch.object(GATE, 'git',
                side_effect=[b'false', b'0' * 40]), patch.object(GATE, 'tracked_copy', return_value=3), \
                patch.object(GATE, 'scan', side_effect=scan):
            with self.assertRaisesRegex(ValueError, 'findings block'):
                GATE.run(self.root, self.root / 'scanner', {'version': '8.30.1'}, self.report)
        receipt = json.loads(self.report.read_text())
        self.assertEqual({'schemaVersion', 'tool', 'version', 'commit', 'trackedFiles',
                          'historyFindings', 'trackedTreeFindings', 'passed'}, set(receipt))
        self.assertEqual(1, receipt['historyFindings'])
        self.assertFalse(receipt['passed'])
        self.assertEqual(['clean.json', 'positive.json', 'history.json', 'tracked.json'], reports)

    def test_detection_canary_cannot_silently_pass(self):
        with patch.object(GATE, 'verify_binary'), patch.object(GATE, 'git',
                side_effect=[b'false', b'0' * 40]), patch.object(GATE, 'scan', return_value=0):
            with self.assertRaisesRegex(ValueError, 'detection canary'):
                GATE.run(self.root, self.root / 'scanner', {'version': '8.30.1'}, self.report)
        self.assertFalse(self.report.exists())


class ScannerInstallTest(unittest.TestCase):
    def archive(self, data=b'synthetic executable', symlink=False):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode='w:gz') as bundle:
            info = tarfile.TarInfo('gitleaks')
            info.size = len(data)
            if symlink:
                info.type, info.linkname = tarfile.SYMTYPE, '/synthetic-outside'
                bundle.addfile(info)
            else:
                bundle.addfile(info, io.BytesIO(data))
        archive = buffer.getvalue()
        return archive, {'archiveSha256': INSTALL.digest(archive), 'binarySha256': INSTALL.digest(data)}

    def test_verified_archive_extracts_only_expected_executable(self):
        archive, pin = self.archive()
        self.assertEqual(b'synthetic executable', INSTALL.extract(archive, pin))

    def test_modified_archive_or_binary_digest_fails(self):
        archive, pin = self.archive()
        with self.assertRaisesRegex(ValueError, 'archive checksum'):
            INSTALL.extract(archive + b'changed', pin)
        pin['binarySha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'executable checksum'):
            INSTALL.extract(archive, pin)

    def test_archive_symlink_member_is_never_extracted(self):
        archive, pin = self.archive(symlink=True)
        with self.assertRaisesRegex(ValueError, 'archive member'):
            INSTALL.extract(archive, pin)

    def test_official_provenance_and_both_checksum_pins_are_present(self):
        pin = json.loads(INSTALL.LOCK.read_text())['gitleaks']
        self.assertEqual('8.30.1', pin['version'])
        self.assertTrue(pin['url'].startswith('https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/'))
        for key in ('archiveSha256', 'binarySha256'):
            self.assertRegex(pin[key], r'^[a-f0-9]{64}$')
        self.assertTrue(any(url.endswith('checksums.txt') for url in pin['provenance']))


if __name__ == '__main__':
    unittest.main()
