#!/usr/bin/env python3
"""Check desktop integration without requiring private submodule credentials in source CI."""
import configparser
from pathlib import Path
import subprocess

DESKTOP_COMMIT = '4b751567e011744fff0bc86bd1265fcb9b870b2e'
ROOT = Path(__file__).resolve().parents[2]


def validate(configuration, index):
    parsed = configparser.ConfigParser(interpolation=None, strict=True)
    parsed.read_string(configuration)
    if parsed.sections() != ['submodule "desktop"'] or parsed.defaults():
        raise ValueError('Unexpected submodule configuration')
    module = parsed['submodule "desktop"']
    if dict(module) != {'path': 'desktop', 'url': 'https://github.com/Laymer/purpuragenda-desktop.git'}:
        raise ValueError('Desktop must reference the approved private repository')
    if index.splitlines() != [f'160000 {DESKTOP_COMMIT} 0\tdesktop']:
        raise ValueError('Desktop must pin the independently reviewed initial checkpoint')


if __name__ == '__main__':
    index = subprocess.run(['git', 'ls-files', '--stage', '--', 'desktop'], cwd=ROOT,
                           check=True, capture_output=True, text=True).stdout
    validate((ROOT / '.gitmodules').read_text(), index)
    print('Desktop integration points to the reviewed inventory checkpoint; C++ parity remains pending.')
