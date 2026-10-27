#!/usr/bin/env python3
"""Run every implemented suite locally through the CI scripts; fail closed on any gap."""
from dataclasses import dataclass
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
PYTHON = sys.executable


@dataclass(frozen=True)
class Step:
    """One local command group mirroring a hosted CI job (.github/workflows/verify.yml)."""
    commands: tuple = ()
    tools: tuple = ()
    reports: tuple = ()
    host: str = ''          # platform.system() value required, e.g. 'Darwin'
    hosted_only: str = ''   # reason this cannot run on a developer host
    dynamic: tuple = ()     # runtime-resolved command groups, e.g. 'delivery'


def delivery_commands(root):
    base = subprocess.run(['git', '-C', str(root), 'merge-base', 'origin/main', 'HEAD'],
                          capture_output=True, text=True)
    if base.returncode != 0 or not base.stdout.strip():
        raise ValueError('origin/main is unavailable; run git fetch origin')
    return ((PYTHON, 'scripts/ci/run-python-tests.py', '--start', 'scripts/ci/tests'),
            (PYTHON, 'scripts/ci/check-commit-tests.py', '--base', base.stdout.strip(), '--head', 'HEAD'),
            (PYTHON, 'scripts/ci/main-protection.py'))


STEPS = {
    'documentation': Step(commands=((PYTHON, 'scripts/ci/run-python-tests.py', '--start', 'scripts/ci'),
                                    (PYTHON, 'scripts/ci/check-docs.py'))),
    'build': Step(commands=(('bash', 'scripts/ci/verify-build.sh'),), tools=('bash', 'git', 'docker-daemon'),
                  reports=('frontend/coverage/junit.xml', 'frontend/coverage/coverage-final.json',
                           'backend/*/target/site/jacoco/jacoco.xml')),
    'delivery-policy': Step(tools=('git', 'gh'), dynamic=('delivery',)),
    'contracts': Step(commands=((PYTHON, 'scripts/ci/check-contract.py'),), tools=('java', 'node')),
    'secrets': Step(commands=((PYTHON, 'scripts/dev/install-security-tools.py'),
                              (PYTHON, 'scripts/ci/check-secrets.py')), tools=('git',),
                    reports=('artifacts/security/secrets.json',)),
    'dependencies': Step(commands=((PYTHON, 'scripts/dev/install-security-tools.py', '--tool', 'osv-scanner'),
                                   (PYTHON, 'scripts/ci/check-dependencies.py')), tools=('java', 'node'),
                         reports=('artifacts/security/dependencies.json', 'artifacts/security/java.cdx.json')),
    'persistence': Step(commands=((PYTHON, 'scripts/ci/verify-compose.py'),), tools=('git', 'docker-daemon'),
                        reports=('artifacts/compose/persistence.json',)),
    'macos-bootstrap': Step(commands=((PYTHON, 'scripts/ci/check-toolchain.py'),
                                      (PYTHON, 'scripts/ci/run-python-tests.py', '--start', 'scripts/ci'),
                                      (PYTHON, 'scripts/ci/run-python-tests.py', '--start', 'scripts/ci/tests'),
                                      ('/bin/bash', 'scripts/ci/verify-macos-package.sh')),
                            host='Darwin', reports=('artifacts/host/macos-package.json',)),
    'sast': Step(hosted_only='needs the pinned GitHub CodeQL action (hosted job sast)'),
    'windows-preflight': Step(hosted_only='needs native Windows PowerShell 7.4 (hosted job windows-preflight)'),
}

# Every implemented suite must map to a local step; build covers all categories of the CI build job.
SUITE_STEPS = {
    'documentation': 'documentation',
    'java': 'build', 'architecture': 'build', 'coverage': 'build', 'postgres-migrations': 'build',
    'outbox': 'build', 'angular': 'build',
    'delivery-policy': 'delivery-policy',
    'contracts': 'contracts',
    'secrets': 'secrets',
    'dependencies': 'dependencies',
    'sast': 'sast',
    'persistence': 'persistence',
    'macos-bootstrap': 'macos-bootstrap',
    'windows-preflight': 'windows-preflight',
}


def plan(document, suite_steps=SUITE_STEPS, steps=STEPS):
    """Return ordered (step, suites) pairs; raise before running anything on an unmapped suite."""
    if not isinstance(document, dict) or document.get('schemaVersion') != 1 or not isinstance(document.get('suites'), dict):
        raise ValueError('Invalid suite inventory: scripts/ci/suites.json')
    grouped = {}
    for name, suite in document['suites'].items():
        if not isinstance(suite, dict) or suite.get('state') not in ('implemented', 'planned'):
            raise ValueError('Invalid suite entry: ' + str(name))
        if suite['state'] != 'implemented':
            continue
        step = suite_steps.get(name)
        if step not in steps:
            raise ValueError('Implemented suite has no mapped local command: ' + str(name))
        grouped.setdefault(step, []).append(name)
    if not grouped:
        raise ValueError('No implemented suites')
    return [(step, grouped[step]) for step in steps if step in grouped]


def docker_daemon():
    if not shutil.which('docker'):
        return False
    try:
        return subprocess.run(['docker', 'version', '--format', '{{.Server.Version}}'], stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL, timeout=30).returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def missing_tools(tools):
    missing = []
    for tool in tools:
        if tool == 'docker-daemon':
            if not docker_daemon():
                missing.append('Docker daemon')
        elif not shutil.which(tool):
            missing.append(tool)
    return missing


def fresh_reports(root, patterns, started):
    """Each report pattern must match at least one file written by this run."""
    missing = []
    for pattern in patterns:
        matches = [path for path in root.glob(pattern) if path.is_file() and not path.is_symlink()]
        if not matches or any(path.stat().st_mtime < started for path in matches):
            missing.append(pattern)
    return missing


def run_step(name, step, root=ROOT, runner=subprocess.run, host=None):
    """Return (passed, detail). Never reports success for anything that did not run here."""
    if step.hosted_only:
        return False, 'not runnable on this host: ' + step.hosted_only
    if step.host and (host or platform.system()) != step.host:
        return False, 'requires a ' + step.host + ' host (hosted job ' + name + ' is authoritative)'
    missing = missing_tools(step.tools)
    if missing:
        return False, 'missing required tool: ' + ', '.join(missing) + " (run ./purpuragenda.sh doctor)"
    try:
        commands = step.commands + (delivery_commands(root) if 'delivery' in step.dynamic else ())
    except ValueError as error:
        return False, str(error)
    started = time.time() - 1
    for command in commands:
        print('$ ' + ' '.join(command if not command[0] == PYTHON else ('python3',) + tuple(command[1:])), flush=True)
        try:
            completed = runner(list(command), cwd=root)
        except OSError:
            return False, 'could not start: ' + command[0]
        if completed.returncode != 0:
            return False, 'command failed (exit ' + str(completed.returncode) + '): ' + ' '.join(command[1:3])
    stale = fresh_reports(root, step.reports, started)
    if stale:
        return False, 'missing or stale report: ' + ', '.join(stale)
    return True, 'passed'


def run_all(document, root=ROOT, suite_steps=SUITE_STEPS, steps=STEPS, execute=None):
    execute = execute or run_step
    ordered = plan(document, suite_steps, steps)
    outcomes = []
    for step_name, suites in ordered:
        print('== ' + step_name + ': ' + ', '.join(suites), flush=True)
        passed, detail = execute(step_name, steps[step_name], root)
        outcomes.extend((suite, step_name, passed, detail) for suite in suites)
    print('\nLocal verification summary')
    for suite, step_name, passed, detail in outcomes:
        print(('  PASS ' if passed else '  FAIL ') + suite.ljust(20) + ' [' + step_name + '] ' + detail)
    failed = sum(not passed for _, _, passed, _ in outcomes)
    print(f'{len(outcomes) - failed} passed, {failed} failed of {len(outcomes)} implemented suites.')
    if failed:
        print('Local verification is incomplete; hosted CI remains the merge gate.')
    return failed == 0


def describe(document):
    for step_name, suites in plan(document):
        step = STEPS[step_name]
        if step.hosted_only:
            command = 'hosted only: ' + step.hosted_only
        else:
            command = '; '.join(' '.join(('python3',) + c[1:] if c[0] == PYTHON else c) for c in step.commands)
            if 'delivery' in step.dynamic:
                command = 'tests, check-commit-tests.py --base $(git merge-base origin/main HEAD), main-protection.py'
            if step.host:
                command += ' (' + step.host + ' only)'
        print(step_name + ' [' + ', '.join(suites) + ']: ' + command)


def main(arguments, root=ROOT):
    if arguments not in (['all'], ['list']):
        print('Usage: ./verify.sh {all|list|help}', file=sys.stderr)
        return 2
    try:
        document = json.loads((root / 'scripts/ci/suites.json').read_text())
        if arguments == ['list']:
            describe(document)
            return 0
        return 0 if run_all(document, root) else 1
    except (OSError, ValueError) as error:
        print('VERIFY_FAILED: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    os.chdir(ROOT)
    sys.exit(main(sys.argv[1:]))
