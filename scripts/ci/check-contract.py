#!/usr/bin/env python3
"""Regenerate the pinned Angular SDK and fail on missing, stale or extra files."""
import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
DESTINATION = ROOT / 'frontend/src/app/shared/generated'


def contents(directory):
    if not directory.is_dir():
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true', help='generate the reviewed SDK for committing')
    args = parser.parse_args()
    try:
        if args.write:
            # Generate fresh first; never leave obsolete files after a contract deletion.
            import shutil
            with tempfile.TemporaryDirectory(prefix='purpuragenda-contract-') as temp:
                fresh = Path(temp) / 'generated'
                generate(fresh)
                if DESTINATION.exists():
                    shutil.rmtree(DESTINATION)
                shutil.copytree(fresh, DESTINATION)
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
