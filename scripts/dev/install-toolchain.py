#!/usr/bin/env python3
"""Install checksum-verified Linux x86_64 tools without changing system tools."""
import hashlib
import json
import os
import platform
import shutil
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[2]
if platform.system() != 'Linux' or platform.machine() != 'x86_64':
    raise SystemExit('Pinned archives require Linux x86_64 (including supported WSL2).')
lock = json.loads((root / 'ops/toolchain.lock.json').read_text())
base = root / '.toolchain'
base.mkdir(exist_ok=True)
for name, item in lock['artifacts'].items():
    archive = base / (name + '.' + item['archive'])
    algorithm = 'sha256' if 'sha256' in item else 'sha512'
    expected = item[algorithm]
    if not archive.exists():
        # A failed download never becomes a reusable archive.
        with tempfile.NamedTemporaryFile(dir=base, delete=False) as output:
            temporary_archive = Path(output.name)
            try:
                with urllib.request.urlopen(item['url'], timeout=180) as response:
                    shutil.copyfileobj(response, output)
                output.close()
                if hashlib.new(algorithm, temporary_archive.read_bytes()).hexdigest() != expected:
                    raise SystemExit('Checksum mismatch: ' + name)
                temporary_archive.rename(archive)
            finally:
                temporary_archive.unlink(missing_ok=True)
    if hashlib.new(algorithm, archive.read_bytes()).hexdigest() != expected:
        raise SystemExit('Checksum mismatch: ' + name)
    # Always extract the verified archive. A marker file alone cannot attest installed tools.
    destination = base / name
    with tempfile.TemporaryDirectory(dir=base) as temporary:
        staging = Path(temporary)
        if item['archive'] == 'zip':
            with zipfile.ZipFile(archive) as source:
                for member in source.infolist():
                    target = (staging / member.filename).resolve()
                    if not target.is_relative_to(staging.resolve()):
                        raise SystemExit('Unsafe archive member: ' + name)
                source.extractall(staging)
        else:
            with tarfile.open(archive) as source:
                source.extractall(staging, filter='data')
        children = list(staging.iterdir())
        if len(children) != 1 or not children[0].is_dir():
            raise SystemExit('Unexpected archive layout: ' + name)
        if name == 'maven':
            (children[0] / 'bin/mvn').chmod(0o755)
        if destination.is_symlink():
            raise SystemExit('Tool destination must not be a symlink: ' + name)
        if destination.exists():
            shutil.rmtree(destination)
        children[0].rename(destination)
    print('Verified and installed ' + name + ' ' + item['version'])
