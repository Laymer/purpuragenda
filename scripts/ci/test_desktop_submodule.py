import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('desktop_submodule', Path(__file__).with_name('check-desktop-submodule.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DesktopSubmoduleTest(unittest.TestCase):
    configuration = ('[submodule "desktop"]\n'
                     'path = desktop\n'
                     'url = https://github.com/Laymer/purpuragenda-desktop.git\n')
    index = f'160000 {module.DESKTOP_COMMIT} 0\tdesktop\n'

    def test_reviewed_checkpoint_with_no_private_checkout_required(self):
        module.validate(self.configuration, self.index)

    def test_missing_gitlink_and_regular_directory_rejected(self):
        for index in ('', self.index.replace('160000', '100644'), self.index.replace(' 0\t', ' 2\t')):
            with self.subTest(index=index), self.assertRaises(ValueError):
                module.validate(self.configuration, index)

    def test_unreviewed_or_floating_revision_rejected(self):
        for revision in ('main', '0' * 40):
            with self.subTest(revision=revision), self.assertRaises(ValueError):
                module.validate(self.configuration, self.index.replace(module.DESKTOP_COMMIT, revision))

    def test_repository_redirect_and_path_escape_rejected(self):
        for before, after in (('Laymer/', 'another-owner/'), ('path = desktop', 'path = ../desktop'),
                              ('https://github.com/', 'file:///tmp/')):
            with self.subTest(after=after), self.assertRaises(ValueError):
                module.validate(self.configuration.replace(before, after), self.index)

    def test_custom_update_command_and_extra_submodule_rejected(self):
        for extra in ('update = !unexpected-command\n', '[submodule "other"]\npath = other\n'):
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                module.validate(self.configuration + extra, self.index)

    def test_inherited_default_configuration_cannot_supply_submodule_values(self):
        inherited = ('[DEFAULT]\nurl = https://github.com/Laymer/purpuragenda-desktop.git\n'
                     '[submodule "desktop"]\npath = desktop\n')
        with self.assertRaises(ValueError):
            module.validate(inherited, self.index)

    def test_conflicting_index_entries_rejected(self):
        with self.assertRaises(ValueError):
            module.validate(self.configuration, self.index + self.index)
