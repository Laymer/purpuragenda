#!/usr/bin/env python3
"""Core-journey browser acceptance against an isolated real Compose stack (PostgreSQL, Mailpit, Caddy, backend).

One run owns its Compose project, network, secrets directory, volumes and free loopback ports. The administrator is
enrolled through the real private `admin-setup` terminal flow; its authenticator key is read from that terminal and used
only to compute the sign-in code. Chromium (Playwright for Java, acceptance-tests module) then drives the real UI.
No mock, no test endpoint and no authentication bypass exist. Output is scrubbed; only a count-only receipt and one
sanitized failure note are written under artifacts/e2e.
"""
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
from artifact_paths import remove_artifact, write_artifact  # noqa: E402
import e2e_support as support  # noqa: E402
from operator_session import Session, totp  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
LOCAL_SPEC = importlib.util.spec_from_file_location('local', ROOT / 'scripts/dev/local.py')
LOCAL = importlib.util.module_from_spec(LOCAL_SPEC)
LOCAL_SPEC.loader.exec_module(LOCAL)
COMPOSE_SPEC = importlib.util.spec_from_file_location('compose_gate', ROOT / 'scripts/ci/verify-compose.py')
COMPOSE_GATE = importlib.util.module_from_spec(COMPOSE_SPEC)
COMPOSE_SPEC.loader.exec_module(COMPOSE_GATE)
RECEIPT = ROOT / 'artifacts/e2e/core-journey.json'
EVIDENCE = ROOT / 'artifacts/e2e/core-journey-failure.txt'
MODULE = ROOT / 'acceptance-tests'
DEPENDENCY_PLUGIN = 'org.apache.maven.plugins:maven-dependency-plugin:3.8.1'


def enroll_administrator(command, environment, email, password):
    """Real private enrollment on a pseudo-terminal. Returns (authenticator key, TOTP step the enrollment code used)."""
    session = Session(command, ROOT, environment)
    try:
        session.expect('Administrator email: ')
        session.send(email)
        for prompt in (re.escape('Password (15-128 characters): '), 'Repeat password: '):
            session.expect(prompt)
            session.send(password)
        key = session.expect(r'Key: ([A-Z2-7]+)').group(1)
        session.expect('Authenticator code: ')
        used_step = int(time.time() // 30)
        session.send(totp(key))
        session.expect('Enrollment complete')
        status, remainder = session.finish()
        if status != 0 or password in remainder:
            raise AssertionError('ADMIN_ENROLLMENT_FAILED')
        return key, used_step
    finally:
        if session.process.poll() is None:
            session.process.kill()


def main():
    remove_artifact(ROOT, RECEIPT)
    remove_artifact(ROOT, EVIDENCE)
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=normal'], cwd=ROOT))
    report = {'revision': revision, 'dirty': dirty, 'browser': 'chromium', 'passed': False, 'checks': [],
              'execution': 'hosted-docker' if os.environ.get('GITHUB_ACTIONS') else 'local-docker'}
    run_id = uuid.uuid4().hex[:12]
    project = 'e2e-' + run_id
    directory = Path(tempfile.mkdtemp(prefix=project + '-'))
    base_port, mail_port = support.distinct_ports()
    password = 'synthetic ' + secrets.token_hex(12)
    email = f'operator+{run_id}@example.test'
    secret_values = [password]
    environment = LOCAL.docker_environment()
    environment.update(COMPOSE_PROJECT_NAME=project, PURPURAGENDA_SECRET_DIR=str(directory / 'secrets'),
                       PURPURAGENDA_IMAGE_TAG=project, PURPURAGENDA_MAIL_FROM='sender@example.test',
                       PURPURAGENDA_E2E_PORT=str(base_port), PURPURAGENDA_E2E_MAIL_PORT=str(mail_port))
    base = ['docker', 'compose', '-f', str(ROOT / 'compose.yaml'), '-f', str(ROOT / 'compose.e2e.yaml')]

    def run(*arguments, command=base, check=True, timeout=300):
        return subprocess.run([*command, *arguments], cwd=ROOT, env=environment, check=check, text=True,
                              capture_output=True, timeout=timeout)

    try:
        LOCAL.require_docker(environment)
        networks = run('network', 'ls', '-q', command=['docker']).stdout.split()
        existing = json.loads(run('network', 'inspect', *networks, command=['docker']).stdout) if networks else []
        subnets = COMPOSE_GATE.allocated_subnets(existing)
        choices = [ipaddress.ip_network(f'172.30.{i}.0/28') for i in range(80, 250)]
        subnet = next(n for n in choices if not any(n.overlaps(old) for old in subnets if n.version == old.version))
        environment.update(PURPURAGENDA_PROXY_SUBNET=str(subnet), PURPURAGENDA_PROXY_ADDRESS=str(subnet.network_address + 2),
                           PURPURAGENDA_PROXY_POOL=str(subnet.network_address + 8) + '/29')
        LOCAL.prepare(directory / 'secrets')
        support.validate_published(json.loads(run('config', '--format', 'json').stdout), base_port, mail_port)
        COMPOSE_GATE.validate_configuration(json.loads(run('--profile', 'operator', 'config', '--format', 'json').stdout))
        run('up', '--build', '--wait', '--wait-timeout', '300', timeout=1800)
        key, used_step = enroll_administrator(base + ['--profile', 'operator', 'run', '--rm', 'operator'], environment, email, password)
        secret_values.append(key)
        settings = support.journey_settings(base_port, mail_port, email, password, key, run_id, MODULE / 'target/e2e-evidence')
        support.wait_for_next_step(used_step)
        run_browser_journey(settings, secret_values)
        support.surefire(MODULE / f'target/surefire-reports/TEST-{support.JOURNEY_TEST}.xml')
        report['checks'] = list(support.CHECKS)
        report['passed'] = True
    finally:
        report['project'] = project
        try:
            run('down', check=True)  # Preserve named volumes AND the matching private secrets; never remove volumes.
        except (OSError, subprocess.SubprocessError, KeyError):
            report['passed'] = False
        keep_evidence(secret_values)
        write_artifact(ROOT, RECEIPT, json.dumps(report, indent=2) + '\n')
    support.validate_receipt(report, revision)
    print('Core journey verified in Chromium against an isolated real Compose stack: ' + report['execution'])


def run_browser_journey(settings, secret_values):
    java = shutil.which('java')
    if java is None or not (ROOT / 'mvnw').is_file():
        raise ValueError('JAVA_UNAVAILABLE')
    classpath_file = Path(tempfile.mkdtemp(prefix='e2e-classpath-')) / 'classpath.txt'
    maven = [str(ROOT / 'mvnw'), '--batch-mode', '--no-transfer-progress', '-f', str(ROOT / 'pom.xml'), '-pl', 'acceptance-tests']
    build = subprocess.run([*maven, DEPENDENCY_PLUGIN + ':build-classpath', '-Dmdep.outputFile=' + str(classpath_file),
                            '-Dmdep.includeScope=test'], cwd=ROOT, text=True, capture_output=True, timeout=900)
    if build.returncode != 0 or not classpath_file.is_file():
        print(support.scrub(build.stdout[-4000:], secret_values))
        raise ValueError('E2E_CLASSPATH_FAILED')
    install = ['install', '--with-deps', 'chromium'] if os.environ.get('GITHUB_ACTIONS') or os.environ.get('E2E_INSTALL_DEPS') else ['install', 'chromium']
    browser = subprocess.run([java, '-cp', classpath_file.read_text().strip(), 'com.microsoft.playwright.CLI', *install],
                             cwd=ROOT, text=True, capture_output=True, timeout=1800)
    if browser.returncode != 0:
        print(support.scrub((browser.stdout + browser.stderr)[-4000:], secret_values))
        raise ValueError('E2E_BROWSER_INSTALL_FAILED')
    environment = dict(os.environ, **settings)
    result = subprocess.run([*maven, 'test', '-De2e.groups=e2e', '-De2e.excludedGroups=disabled'], cwd=ROOT, env=environment,
                            text=True, capture_output=True, timeout=1500)
    output = result.stdout + result.stderr
    # Only the scrubbed result summary is shown: Playwright call logs can echo typed values.
    summary = [line for line in output.splitlines() if re.match(r'\[(INFO|ERROR|WARNING)\] (Tests run:|BUILD|.*CoreJourneyE2eTest)', line)]
    print(support.scrub('\n'.join(summary), secret_values))
    if result.returncode != 0:
        print(support.scrub('\n'.join(output.splitlines()[-60:]), secret_values))
        raise ValueError('E2E_JOURNEY_FAILED')


def keep_evidence(secret_values):
    source = MODULE / 'target/e2e-evidence/core-journey-failure.txt'
    try:
        if source.is_file():
            write_artifact(ROOT, EVIDENCE, support.scrub(source.read_text(encoding='utf-8')[:20000], secret_values))
    except (OSError, ValueError):
        pass


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, AssertionError, StopIteration, EOFError, RuntimeError,
            subprocess.SubprocessError) as error:
        # Compose and Maven diagnostics hold no credential (secrets travel in files and a child environment); still scrubbed.
        detail = getattr(error, 'stderr', None) or ''
        print('E2E_VERIFICATION_FAILED: ' + support.scrub(type(error).__name__ + ': ' + str(error)[:300]), file=sys.stderr)
        if isinstance(detail, str) and detail:
            print(support.scrub(detail[-3000:]), file=sys.stderr)
        raise SystemExit('E2E_VERIFICATION_FAILED: require a complete current-revision Chromium core-journey run.') from None
