#!/usr/bin/env python3
"""Local initialization never replaces existing secrets or removes data volumes."""
import json
import fcntl
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
PASSWORDS = ('postgres-password', 'migrator-password', 'app-password')
KEYS = ('totp-key', 'delivery-key', 'rate-key')
# Tag-name match: tolerant of Angular attributes, whitespace and attribute order.
FRONTEND_MARKER = re.compile(r'<app-root[\s>/]')
# No credential, environment value or option ever appears here; the prompts run inside the container TTY.
ADMIN_SETUP_ARGUMENTS = ('--profile', 'operator', 'run', '--rm', '--build', 'operator')


def prepare(directory):
    directory = Path(directory)
    if directory.is_symlink():
        raise ValueError('INVALID_SECRET_DIRECTORY')
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    if directory.stat().st_uid != os.getuid():
        raise ValueError('INVALID_SECRET_OWNER')
    directory.chmod(0o700)
    descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        prepare_files(directory)
    finally:
        os.close(descriptor)


def prepare_files(directory):
    values = []
    for name in PASSWORDS + KEYS:
        path = directory / name
        if not path.exists() and not path.is_symlink():
            value = (secrets.token_hex(32) + '\n').encode() if name in PASSWORDS else secrets.token_bytes(32)
            descriptor, temporary = tempfile.mkstemp(dir=directory)
            try:
                with os.fdopen(descriptor, 'wb') as output:
                    output.write(value)
                    output.flush()
                    os.fsync(output.fileno())
                    os.fchmod(output.fileno(), 0o444)
                try:
                    os.link(temporary, path)  # Atomic exclusive publication; concurrent initialization cannot replace it.
                except FileExistsError:
                    pass
            finally:
                os.unlink(temporary)
        with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW), 'rb') as source:
            info = os.fstat(source.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1:
                raise ValueError('INVALID_SECRET_FILE')
            value = source.read(67)
            if (name in PASSWORDS and not re.fullmatch(b'[a-f0-9]{64}\n?', value)) or (name in KEYS and len(value) != 32):
                raise ValueError('INVALID_SECRET_CONTENT')
            os.fchmod(source.fileno(), 0o444)
            values.append(value.rstrip(b'\n') if name in PASSWORDS else value)
    if len(set(values)) != len(values):
        raise ValueError('REUSED_SECRET_MATERIAL')


def docker_environment():
    environment = dict(os.environ)
    desktop = Path('/Applications/Docker.app/Contents/Resources/bin')
    if desktop.joinpath('docker').is_file():
        environment['PATH'] = str(desktop) + os.pathsep + environment.get('PATH', '')
    if not shutil.which('docker', path=environment.get('PATH')):
        raise ValueError('DOCKER_UNAVAILABLE')
    environment.pop('COMPOSE_FILE', None)
    return environment


def require_docker(environment):
    def available():
        return subprocess.run(['docker', 'info'], env=environment, stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL, timeout=15).returncode == 0
    if available():
        return
    if platform.system() != 'Darwin' or not Path('/Applications/Docker.app').is_dir():
        raise ValueError('DOCKER_UNAVAILABLE')
    subprocess.run(['open', '-a', '/Applications/Docker.app'], check=True, timeout=15)
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if available():
            return
        time.sleep(2)
    raise ValueError('DOCKER_NOT_READY')


def compose(environment, *arguments, capture=False):
    return subprocess.run(['docker', 'compose', '-f', str(ROOT / 'compose.yaml'), *arguments],
                          cwd=ROOT, env=environment, check=True, text=True, capture_output=capture)


def smoke():
    for url, check in [('http://127.0.0.1:8080/healthz', lambda text: json.loads(text).get('status') == 'UP'),
                       ('http://127.0.0.1:8025/readyz', lambda text: True),
                       ('http://127.0.0.1:8080/', lambda text: bool(FRONTEND_MARKER.search(text)))]:
        with urllib.request.urlopen(url, timeout=10) as response:
            if response.status != 200 or not check(response.read(100_000).decode()):
                raise ValueError('LOCAL_SMOKE_FAILED')


def admin_setup(interactive):
    # Real credentials need a private terminal on both ends; piped input could be logged or scripted.
    if not interactive:
        raise ValueError('ADMIN_SETUP_REQUIRES_TERMINAL')
    prepare(os.environ.get('PURPURAGENDA_SECRET_DIR', ROOT / '.secrets'))
    environment = docker_environment()
    require_docker(environment)
    compose(environment, *ADMIN_SETUP_ARGUMENTS)  # Inherits the terminal; nothing is captured or passed as an argument.


def main(arguments):
    if arguments not in (['init'], ['up'], ['smoke'], ['admin-setup']):
        raise ValueError('Usage: ./scripts/dev/local.sh {init|up|smoke|admin-setup}')
    if arguments == ['admin-setup']:
        admin_setup(sys.stdin.isatty() and sys.stdout.isatty())
        return
    if arguments[0] in ('init', 'up'):
        prepare(os.environ.get('PURPURAGENDA_SECRET_DIR', ROOT / '.secrets'))
    if arguments[0] == 'up':
        environment = docker_environment()
        require_docker(environment)
        compose(environment, 'up', '--build', '--wait', '--wait-timeout', '240')
        smoke()
    elif arguments[0] == 'smoke':
        smoke()
    print('Private local secrets initialized; existing files preserved.' if arguments[0] == 'init'
          else 'Local application ready: http://localhost:8080 · Mailpit: http://localhost:8025')


if __name__ == '__main__':
    try:
        main(sys.argv[1:])
    except (OSError, ValueError, subprocess.SubprocessError):
        raise SystemExit('LOCAL_COMMAND_FAILED: check Docker Desktop, private secret files and local service readiness.') from None
