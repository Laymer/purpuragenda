#!/usr/bin/env python3
"""Regenerate the pinned Angular SDK and fail on missing, stale or extra files."""
import argparse
from pathlib import Path
import subprocess
import shutil
import tempfile

ROOT = Path(__file__).resolve().parents[2]
DESTINATION = ROOT / 'frontend/src/app/shared/generated'


def contents(directory):
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError('Missing generated SDK directory')
    result = {}
    for file in directory.rglob('*'):
        if file.is_symlink():
            raise ValueError('Generated SDK must not contain symlinks')
        if file.is_file():
            result[file.relative_to(directory).as_posix()] = file.read_bytes()
    if not result:
        raise ValueError('Empty generated SDK')
    return result


def compare(expected, actual):
    fresh, committed = contents(expected), contents(actual)
    return sorted(path for path in fresh.keys() | committed.keys()
                  if fresh.get(path) != committed.get(path))


def generate(destination):
    subprocess.run([str(ROOT / 'mvnw'), '--batch-mode', '--no-transfer-progress',
                    '-Dmaven.repo.local=' + str(ROOT / '.cache/m2'),
                    '-f', str(ROOT / 'contracts/pom.xml'),
                    '-Dgenerated.output=' + str(destination), 'generate-sources'],
                   cwd=ROOT, check=True)


def replace_generated(fresh, destination, root):
    # Validate every output before touching the last known committed SDK.
    contents(fresh)
    root = root.resolve()
    try:
        relative = destination.relative_to(root)
    except ValueError:
        raise ValueError('Generated destination must stay inside the repository') from None
    if '..' in relative.parts or not relative.parts:
        raise ValueError('Invalid generated destination')
    current = root
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError('Generated destination ancestors must not be symlinks')
    if not destination.resolve().is_relative_to(root):
        raise ValueError('Generated destination escapes repository')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.sdk-replacement-', dir=destination.parent) as temp:
        staged, backup = Path(temp) / 'new', Path(temp) / 'old'
        shutil.copytree(fresh, staged)
        if destination.exists():
            destination.rename(backup)
        try:
            staged.rename(destination)
        except OSError:
            if backup.exists():
                backup.rename(destination)
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true', help='generate the reviewed SDK for committing')
    args = parser.parse_args()
    try:
        if args.write:
            # Generate fresh first; never leave obsolete files after a contract deletion.
            with tempfile.TemporaryDirectory(prefix='purpuragenda-contract-') as temp:
                fresh = Path(temp) / 'generated'
                generate(fresh)
                replace_generated(fresh, DESTINATION, ROOT)
        else:
            with tempfile.TemporaryDirectory(prefix='purpuragenda-contract-') as temp:
                fresh = Path(temp) / 'generated'
                generate(fresh)
                differences = compare(fresh, DESTINATION)
                if differences:
                    raise ValueError('Generated SDK drift: ' + ', '.join(differences))
            print('Pinned Angular SDK matches the validated public contract.')
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit('Contract verification failed: ' + str(error))


if __name__ == '__main__':
    main()
