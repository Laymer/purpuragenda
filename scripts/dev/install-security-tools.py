#!/usr/bin/env python3
"""Install checksum-pinned Linux x64 scanners from official project releases."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import platform
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
LOCK = ROOT / 'ops/security-tools.lock.json'
BINARY = ROOT / '.cache/security/gitleaks'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def extract(archive, pin):
    if digest(archive) != pin['archiveSha256']:
        raise ValueError('Scanner archive checksum mismatch')
    with tarfile.open(fileobj=io.BytesIO(archive), mode='r:gz') as bundle:
        member = bundle.getmember('gitleaks')
        if not member.isfile() or member.size > 100_000_000:
            raise ValueError('Invalid scanner archive member')
        executable = bundle.extractfile(member).read()
    if digest(executable) != pin['binarySha256']:
        raise ValueError('Scanner executable checksum mismatch')
    return executable


def materialize(data, pin):
    if pin.get('format') == 'executable':
        if digest(data) != pin['binarySha256']:
            raise ValueError('Scanner executable checksum mismatch')
        return data
    return extract(data, pin)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tool', choices=('gitleaks', 'osv-scanner'), default='gitleaks')
    args = parser.parse_args()
    try:
        if platform.system() != 'Linux' or platform.machine() not in ('x86_64', 'AMD64'):
            raise ValueError('Security scanner requires Linux x64 (including supported WSL)')
        pin = json.loads(LOCK.read_text())[args.tool]
        binary = BINARY.parent / args.tool
        suffix = '.bin' if pin.get('format') == 'executable' else '.tar.gz'
        cache = BINARY.parent / (args.tool + '-' + pin['version'] + suffix)
        cache.parent.mkdir(parents=True, exist_ok=True)
        if not cache.exists():
            with urllib.request.urlopen(pin['url'], timeout=60) as response:
                archive = response.read(100_000_001)
            executable = materialize(archive, pin)
            cache.write_bytes(archive)
        else:
            executable = materialize(cache.read_bytes(), pin)
        binary.write_bytes(executable)
        binary.chmod(0o755)
    except (OSError, ValueError, KeyError, tarfile.TarError):
        raise SystemExit('Pinned scanner installation failed; inspect tool availability/checksums.')
    print('Pinned scanner installed and checksums verified: ' + args.tool)


if __name__ == '__main__':
    main()
