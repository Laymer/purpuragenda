#!/usr/bin/env python3
"""Install verified repository-local tools, including from macOS's Python 3.9."""
import hashlib
import json
import platform
import shutil
import stat
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[2]


class InstallError(Exception):
    pass


def select(lock, system, machine):
    platform_id = {'Linux': 'linux', 'Darwin': 'macos'}.get(system)
    if platform_id is None or machine not in ('x86_64', 'AMD64'):
        raise InstallError('Unsupported toolchain platform; use Linux or macOS Intel.')
    platform_id += '-x86_64'
    artifacts = dict(lock['artifacts'])
    artifacts.update(lock['platforms'][platform_id])
    return platform_id, artifacts


def checksum(path, algorithm):
    digest = hashlib.new(algorithm)
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def verified_archive(base, name, item):
    archive = base / (name + '.' + item['archive'])
    algorithm = 'sha256' if 'sha256' in item else 'sha512'
    expected = item[algorithm]
    if archive.is_symlink():
        raise InstallError('Archive must not be a symlink: ' + name)
    if not archive.exists():
        with tempfile.NamedTemporaryFile(dir=base, delete=False) as output:
            temporary_archive = Path(output.name)
            try:
                with urllib.request.urlopen(item['url'], timeout=180) as response:
                    shutil.copyfileobj(response, output)
                output.close()
                if checksum(temporary_archive, algorithm) != expected:
                    raise InstallError('Checksum mismatch: ' + name)
                temporary_archive.rename(archive)
            finally:
                temporary_archive.unlink(missing_ok=True)
    if checksum(archive, algorithm) != expected:
        raise InstallError('Checksum mismatch: ' + name)
    return archive


def member_path(staging, name):
    relative = PurePosixPath(name)
    if relative.is_absolute() or '..' in relative.parts or '\\' in name or not relative.parts:
        raise InstallError('Unsafe archive member')
    target = staging.joinpath(*relative.parts)
    if not target.resolve().is_relative_to(staging.resolve()):
        raise InstallError('Unsafe archive member')
    return target


def extract(archive, staging, kind):
    # Create files before links: no archive-supplied link can redirect a write.
    # This deliberately avoids the unsafe pre-3.12 extractall default during bootstrap.
    links = []
    if kind == 'zip':
        with zipfile.ZipFile(archive) as source:
            for entry in source.infolist():
                target = member_path(staging, entry.filename)
                mode = entry.external_attr >> 16
                if stat.S_ISLNK(mode):
                    raise InstallError('Unsupported zip link')
                if entry.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with source.open(entry) as content, target.open('xb') as output:
                        shutil.copyfileobj(content, output)
                    target.chmod((mode & 0o777) or 0o644)
    else:
        with tarfile.open(archive) as source:
            for entry in source:
                target = member_path(staging, entry.name)
                if entry.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                elif entry.isfile():
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with source.extractfile(entry) as content, target.open('xb') as output:
                        shutil.copyfileobj(content, output)
                    target.chmod(entry.mode & 0o777)
                elif entry.issym():
                    links.append((target, entry.linkname))
                else:
                    raise InstallError('Unsupported archive member')
        for target, linkname in links:
            if Path(linkname).is_absolute() or '\\' in linkname:
                raise InstallError('Unsafe archive link')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.symlink_to(linkname)
        for target, _ in links:
            if not target.resolve().is_relative_to(staging.resolve()):
                raise InstallError('Unsafe archive link')


def install(base, cache, name, item):
    destination = base / name
    if destination.is_symlink():
        raise InstallError('Tool destination must not be a symlink: ' + name)
    archive = verified_archive(cache, name, item)
    with tempfile.TemporaryDirectory(dir=base) as temporary:
        staging = Path(temporary)
        extract(archive, staging, item['archive'])
        children = list(staging.iterdir())
        if len(children) != 1 or not children[0].is_dir() or children[0].is_symlink():
            raise InstallError('Unexpected archive layout: ' + name)
        source = children[0]
        if item.get('home'):
            source = member_path(source, item['home'])
            if source.is_symlink() or not source.is_dir():
                raise InstallError('Unexpected tool home: ' + name)
        if name == 'maven':
            (source / 'bin/mvn').chmod(0o755)
        # Never trust installed marker files; replace only after verified extraction.
        if destination.exists():
            shutil.rmtree(destination)
        source.rename(destination)
    print('Verified and installed ' + name + ' ' + item['version'], flush=True)


def main():
    lock = json.loads((ROOT / 'ops/toolchain.lock.json').read_text())
    platform_id, artifacts = select(lock, platform.system(), platform.machine())
    base = ROOT / '.toolchain'
    if base.is_symlink():
        raise InstallError('Toolchain directory must not be a symlink')
    base.mkdir(exist_ok=True)
    cache = base / platform_id
    if cache.is_symlink():
        raise InstallError('Archive directory must not be a symlink')
    cache.mkdir(exist_ok=True)
    for name in ('python', 'jdk', 'node', 'maven'):
        install(base, cache, name, artifacts[name])


if __name__ == '__main__':
    try:
        main()
    except (InstallError, OSError, ValueError, RuntimeError, tarfile.TarError, zipfile.BadZipFile) as failure:
        # Provider messages may contain private proxy/URL details.
        raise SystemExit(str(failure) if isinstance(failure, InstallError) else 'Toolchain installation failed') from None
