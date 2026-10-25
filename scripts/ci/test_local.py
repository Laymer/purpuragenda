"""Private initialization is additive and fails closed on unsafe existing state."""
import concurrent.futures
import importlib.util
import os
import re
from pathlib import Path
import stat
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('local', Path(__file__).resolve().parents[1] / 'dev/local.py')
LOCAL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LOCAL)


class LocalSecretsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.directory = self.root / 'private'

    def contents(self):
        return {p.name: p.read_bytes() for p in self.directory.iterdir()}

    def test_independent_secrets_private_parent_and_repeat_preservation(self):
        LOCAL.prepare(self.directory)
        original = self.contents()
        self.assertEqual(6, len(original))
        self.assertEqual(6, len(set(original.values())))
        self.assertEqual(0o700, stat.S_IMODE(self.directory.stat().st_mode))
        self.assertEqual({0o444}, {stat.S_IMODE(p.stat().st_mode) for p in self.directory.iterdir()})
        for name in LOCAL.PASSWORDS:
            self.assertTrue(re.fullmatch(b'[a-f0-9]{64}\n', original[name]))
        for name in LOCAL.KEYS:
            self.assertEqual(32, len(original[name]))
        LOCAL.prepare(self.directory)
        self.assertTrue(original == self.contents())
        (self.directory / 'rate-key').unlink()
        LOCAL.prepare(self.directory)
        self.assertTrue({k: v for k, v in original.items() if k != 'rate-key'} ==
                        {k: v for k, v in self.contents().items() if k != 'rate-key'})

    def test_symlink_directory_and_leaf_never_write_outside(self):
        outside = self.root / 'outside'
        outside.mkdir()
        self.directory.symlink_to(outside)
        with self.assertRaises(ValueError):
            LOCAL.prepare(self.directory)
        self.assertEqual([], list(outside.iterdir()))
        self.directory.unlink()
        self.directory.mkdir()
        target = outside / 'marker'
        target.write_bytes(b'synthetic-marker')
        (self.directory / 'postgres-password').symlink_to(target)
        with self.assertRaises(OSError):
            LOCAL.prepare(self.directory)
        self.assertEqual(b'synthetic-marker', target.read_bytes())

    def test_malformed_duplicate_and_hardlinked_existing_secrets_are_not_replaced(self):
        LOCAL.prepare(self.directory)
        password = self.directory / 'postgres-password'
        password.chmod(0o600)
        password.write_bytes(b'invalid')
        with self.assertRaises(ValueError):
            LOCAL.prepare(self.directory)
        self.assertEqual(b'invalid', password.read_bytes())
        password.unlink()
        LOCAL.prepare(self.directory)
        delivery = self.directory / 'delivery-key'
        delivery.chmod(0o600)
        delivery.write_bytes((self.directory / 'totp-key').read_bytes())
        with self.assertRaises(ValueError):
            LOCAL.prepare(self.directory)
        delivery.unlink()
        LOCAL.prepare(self.directory)
        os.link(password, self.root / 'linked-secret')
        with self.assertRaises(ValueError):
            LOCAL.prepare(self.directory)

    def test_invalid_command_fails_without_initialization(self):
        with self.assertRaises(ValueError):
            LOCAL.main(['erase'])
        self.assertFalse(self.directory.exists())

    def test_parallel_initializers_preserve_one_complete_set(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as workers:
            list(workers.map(lambda _: LOCAL.prepare(self.directory), range(16)))
        original = self.contents()
        self.assertEqual(6, len(original))
        LOCAL.prepare(self.directory)
        self.assertTrue(original == self.contents())

    def test_invalid_key_lengths_never_truncate_or_regenerate_existing_material(self):
        LOCAL.prepare(self.directory)
        key = self.directory / 'totp-key'
        for size in (0, 31, 33, 4096):
            key.chmod(0o600)
            key.write_bytes(b'x' * size)
            with self.assertRaises(ValueError):
                LOCAL.prepare(self.directory)
            self.assertTrue(key.read_bytes() == b'x' * size)


if __name__ == '__main__':
    unittest.main()
