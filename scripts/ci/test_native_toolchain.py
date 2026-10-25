import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('native_installer', ROOT / 'scripts/dev/install-toolchain.py')
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class NativeToolchainTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.stage = self.root / 'stage'
        self.stage.mkdir()

    def tearDown(self):
        self.temporary.cleanup()

    def archive(self, entries):
        archive = self.root / 'fixture.tar.gz'
        with tarfile.open(archive, 'w:gz') as output:
            for name, kind, value in entries:
                entry = tarfile.TarInfo(name)
                entry.mode = 0o755
                if kind == 'file':
                    entry.size = len(value)
                    output.addfile(entry, io.BytesIO(value))
                else:
                    entry.type = {'link': tarfile.SYMTYPE, 'hard': tarfile.LNKTYPE,
                                  'device': tarfile.CHRTYPE}[kind]
                    entry.linkname = value
                    output.addfile(entry)
        return archive

    def test_platform_selection_preserves_approved_linux_pins_and_native_versions(self):
        lock = json.loads((ROOT / 'ops/toolchain.lock.json').read_text())
        linux_id, linux = installer.select(lock, 'Linux', 'x86_64')
        mac_id, mac = installer.select(lock, 'Darwin', 'x86_64')
        self.assertEqual('linux-x86_64', linux_id)
        self.assertEqual('macos-x86_64', mac_id)
        for name in ('jdk', 'node', 'maven'):
            self.assertEqual(lock['artifacts'][name], linux[name])
            self.assertEqual(linux[name]['version'], mac[name]['version'])
        self.assertEqual(linux['python']['version'], mac['python']['version'])
        for system, machine in [('Linux', 'arm64'), ('Darwin', 'arm64'), ('Windows', 'AMD64')]:
            with self.subTest(system=system, machine=machine), self.assertRaises(installer.InstallError):
                installer.select(lock, system, machine)

    def test_tar_regular_files_and_internal_links_keep_executable_mode(self):
        archive = self.archive([('python/bin/python3.12', 'file', b'synthetic'),
                                ('python/bin/python3', 'link', 'python3.12')])
        installer.extract(archive, self.stage, 'tar.gz')
        self.assertEqual(b'synthetic', (self.stage / 'python/bin/python3').read_bytes())
        self.assertEqual(0o755, (self.stage / 'python/bin/python3.12').stat().st_mode & 0o777)

    def test_tar_paths_links_devices_and_duplicate_writes_fail_closed(self):
        cases = [[('../escape', 'file', b'outside')], [('/escape', 'file', b'outside')],
                 [('p/link', 'link', '../../escape')], [('p/link', 'link', '/escape')],
                 [('p/bin', 'hard', '../../escape')], [('p/device', 'device', '')],
                 [('p/link', 'link', 'link')], [('p/file', 'file', b'1'), ('p/file', 'file', b'2')]]
        sentinel = self.root / 'escape'
        sentinel.write_bytes(b'unchanged')
        for entries in cases:
            with self.subTest(entries=entries), tempfile.TemporaryDirectory(dir=self.root) as directory:
                with self.assertRaises((installer.InstallError, OSError, RuntimeError)):
                    installer.extract(self.archive(entries), Path(directory), 'tar.gz')
                self.assertEqual(b'unchanged', sentinel.read_bytes())

    def test_link_cannot_redirect_later_regular_file(self):
        sentinel = self.root / 'sentinel'
        sentinel.write_bytes(b'unchanged')
        archive = self.archive([('p/alias', 'link', '../..'), ('p/alias/sentinel', 'file', b'overwrite')])
        with self.assertRaises(installer.InstallError):
            installer.extract(archive, self.stage, 'tar.gz')
        self.assertEqual(b'unchanged', sentinel.read_bytes())

    def test_deferred_links_cannot_create_an_outside_entry_before_rejection(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'sentinel').write_bytes(b'unchanged')
        cases = [[('p/escape', 'link', '../../outside'),
                  ('p/escape/injected', 'link', 'sentinel')],
                 [('p/first', 'link', 'later'), ('p/later', 'link', '../../outside'),
                  ('p/first/injected', 'link', 'sentinel')]]
        for entries in cases:
            with self.subTest(entries=entries), tempfile.TemporaryDirectory(dir=self.root) as directory:
                with self.assertRaises(installer.InstallError):
                    installer.extract(self.archive(entries), Path(directory), 'tar.gz')
                self.assertEqual(['sentinel'], sorted(p.name for p in outside.iterdir()))
                self.assertEqual(b'unchanged', (outside / 'sentinel').read_bytes())

    def test_zip_rejects_traversal_and_links(self):
        for name, mode in [('../escape', 0o100644), ('p/link', 0o120777)]:
            with self.subTest(name=name):
                archive = self.root / 'fixture.zip'
                with zipfile.ZipFile(archive, 'w') as output:
                    info = zipfile.ZipInfo(name)
                    info.external_attr = mode << 16
                    output.writestr(info, b'outside')
                with self.assertRaises(installer.InstallError):
                    installer.extract(archive, self.stage, 'zip')

    def test_mac_jdk_home_is_normalized_and_verified_cache_replaces_modified_install(self):
        cache = self.root / 'cache'
        cache.mkdir()
        archive = self.archive([('jdk-25.jdk/Contents/Home/bin/java', 'file', b'verified')])
        archive.rename(cache / 'jdk.tar.gz')
        item = {'archive': 'tar.gz', 'sha256': installer.checksum(cache / 'jdk.tar.gz', 'sha256'),
                'version': 'synthetic', 'home': 'Contents/Home'}
        installer.install(self.root, cache, 'jdk', item)
        binary = self.root / 'jdk/bin/java'
        binary.write_bytes(b'modified')
        installer.install(self.root, cache, 'jdk', item)
        self.assertEqual(b'verified', binary.read_bytes())
        (cache / 'jdk.tar.gz').write_bytes(b'corrupt')
        with self.assertRaisesRegex(installer.InstallError, 'Checksum mismatch'):
            installer.install(self.root, cache, 'jdk', item)
        self.assertEqual(b'verified', binary.read_bytes())

    def test_linked_cache_or_destination_is_rejected_before_reading(self):
        outside = self.root / 'outside'
        outside.write_bytes(b'sentinel')
        item = {'archive': 'tar.gz', 'sha256': hashlib.sha256(b'sentinel').hexdigest()}
        (self.root / 'jdk.tar.gz').symlink_to(outside)
        with self.assertRaisesRegex(installer.InstallError, 'Archive must not'):
            installer.verified_archive(self.root, 'jdk', item)
        (self.root / 'jdk').symlink_to(self.stage, target_is_directory=True)
        with self.assertRaisesRegex(installer.InstallError, 'Tool destination'):
            installer.install(self.root, self.root, 'jdk', item)
        self.assertEqual(b'sentinel', outside.read_bytes())

    def test_failed_download_is_not_cached(self):
        item = {'archive': 'tar.gz', 'sha256': hashlib.sha256(b'good').hexdigest(),
                'url': 'https://example.invalid/synthetic'}
        with patch.object(installer.urllib.request, 'urlopen', return_value=io.BytesIO(b'bad')):
            with self.assertRaisesRegex(installer.InstallError, 'Checksum mismatch'):
                installer.verified_archive(self.root, 'jdk', item)
        self.assertEqual(['stage'], sorted(p.name for p in self.root.iterdir()))

    def test_environment_resolves_temporary_directory_without_changing_home(self):
        alias = self.root / 'alias'
        alias.symlink_to(self.stage, target_is_directory=True)
        command = 'source scripts/dev/toolchain-env.sh; python3 -c '\
                  "'import json,os; print(json.dumps([os.environ[\"TMPDIR\"], os.environ[\"HOME\"]]))'"
        result = subprocess.run(['bash', '-c', command], cwd=ROOT, check=True, capture_output=True,
                                text=True, env={**os.environ, 'TMPDIR': str(alias)})
        selected = json.loads(result.stdout)
        self.assertEqual([str(self.stage), os.environ['HOME']], selected)

    def test_native_ci_exercises_selected_tools_and_strict_python_suites(self):
        workflow = (ROOT / '.github/workflows/verify.yml').read_text()
        native = workflow.partition('  macos-bootstrap:\n')[2].split('  windows-preflight:', 1)[0]
        self.assertIn('bash scripts/dev/install-toolchain.sh', native)
        self.assertIn('python3 scripts/ci/check-toolchain.py', native)
        self.assertIn('python3 scripts/ci/run-python-tests.py --start scripts/ci', native)
        self.assertIn('python3 scripts/ci/run-python-tests.py --start scripts/ci/tests', native)


if __name__ == '__main__':
    unittest.main()
