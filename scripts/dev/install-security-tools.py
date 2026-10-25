#!/usr/bin/env python3
"""Install the checksum-pinned Linux x64 Gitleaks executable from official releases."""
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


def main():
    try:
        if platform.system() != 'Linux' or platform.machine() not in ('x86_64', 'AMD64'):
            raise ValueError('Security scanner requires Linux x64 (including supported WSL)')
        pin = json.loads(LOCK.read_text())['gitleaks']
        cache = BINARY.parent / ('gitleaks-' + pin['version'] + '.tar.gz')
        cache.parent.mkdir(parents=True, exist_ok=True)
        if not cache.exists():
            with urllib.request.urlopen(pin['url'], timeout=60) as response:
                archive = response.read(100_000_001)
            executable = extract(archive, pin)
            cache.write_bytes(archive)
        else:
            executable = extract(cache.read_bytes(), pin)
        BINARY.write_bytes(executable)
        BINARY.chmod(0o755)
    except (OSError, ValueError, KeyError, tarfile.TarError):
        raise SystemExit('Pinned scanner installation failed; inspect tool availability/checksums.')
    print('Pinned Gitleaks installed and archive/executable checksums verified.')


if __name__ == '__main__':
    main()
