#!/usr/bin/env python3
"""Real local images, isolated PostgreSQL/Mailpit persistence and Caddy boundary checks."""
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import uuid
from artifact_paths import remove_artifact, write_artifact

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('local', ROOT / 'scripts/dev/local.py')
LOCAL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LOCAL)
CHECKS = {'configuration', 'startup', 'persistence', 'maintenance', 'proxy-headers'}


def allocated_subnets(networks):
    return [ipaddress.ip_network(c['Subnet']) for n in networks for c in (n.get('IPAM', {}).get('Config') or []) if c.get('Subnet')]


def validate_configuration(document):
    services = document['services']
    if set(services) != {'postgres', 'migrate', 'backend', 'web', 'mailpit'}:
        raise ValueError('INVALID_LOCAL_SERVICES')
    for name, service in services.items():
        if (not service.get('read_only') or service.get('cap_drop') != ['ALL']
                or 'no-new-privileges:true' not in service.get('security_opt', [])
                or service.get('logging', {}).get('driver') != 'none'):
            raise ValueError('UNSAFE_LOCAL_RUNTIME')
        for port in service.get('ports', []):
            if name != 'web' or port.get('host_ip') != '127.0.0.1':
                raise ValueError('UNSAFE_LOCAL_PORT')
        if any('docker.sock' in str(volume) for volume in service.get('volumes', [])):
            raise ValueError('DOCKER_SOCKET_EXPOSED')
        if any(not mount.startswith('/') for mount in service.get('tmpfs', [])):
            raise ValueError('INVALID_TMPFS_PATH')
    if (services['backend']['environment']['APP_DATABASE_USER'] != 'purpuragenda_app'
            or services['migrate']['environment']['APP_DATABASE_USER'] != 'purpuragenda_migrator'
            or {s['source'] for s in services['migrate']['secrets']} != {'migrator_password'}
            or not all(document['networks'][n]['internal'] for n in ('private', 'proxy', 'mail'))
            or services['backend']['environment']['APP_MODE'] != 'local'
            or services['backend']['environment']['APP_SMTP_HOST'] != 'mailpit'
            or services['backend']['environment']['APP_SMTP_PORT'] != '1025'):
        raise ValueError('INVALID_LOCAL_BOUNDARY')
    network = document['networks']['proxy']['ipam']['config'][0]
    peer = services['web']['networks']['proxy']['ipv4_address']
    address = ipaddress.ip_address(peer)
    if (services['backend']['environment']['APP_SECURITY_TRUSTED_PROXY_PEERS'] != peer
            or address not in ipaddress.ip_network(network['subnet']) or address in ipaddress.ip_network(network['ip_range'])):
        raise ValueError('INVALID_PROXY_ALLOCATION')


def validate_report(report, revision):
    if (report.get('revision') != revision or report.get('dirty') is not False or report.get('passed') is not True
            or not isinstance(report.get('checks'), list)
            or set(report.get('checks', [])) != CHECKS or len(report['checks']) != len(CHECKS)
            or report.get('execution') not in ('local-docker', 'hosted-docker')):
        raise ValueError('INCOMPLETE_COMPOSE_EVIDENCE')


def main():
    receipt = ROOT / 'artifacts/compose/persistence.json'
    remove_artifact(ROOT, receipt)
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=normal'], cwd=ROOT))
    report = {'revision': revision, 'dirty': dirty, 'execution': 'hosted-docker' if os.environ.get('GITHUB_ACTIONS') else 'local-docker',
              'passed': False, 'checks': []}
    environment = LOCAL.docker_environment()
    project = 'p45-' + uuid.uuid4().hex[:12]
    directory = Path(tempfile.mkdtemp(prefix=project + '-'))
    environment.update(COMPOSE_PROJECT_NAME=project, PURPURAGENDA_SECRET_DIR=str(directory / 'secrets'),
                       PURPURAGENDA_VERIFY_DIR=str(directory), PURPURAGENDA_IMAGE_TAG=project,
                       PURPURAGENDA_MAIL_FROM='sender@example.test')
    base = ['docker', 'compose', '-f', str(ROOT / 'compose.yaml')]
    isolated = base + ['-f', str(ROOT / 'compose.verify.yaml')]
    def run(*arguments, input=None, base_command=isolated):
        return subprocess.run([*base_command, *arguments], cwd=ROOT, env=environment, input=input,
                              check=True, text=True, capture_output=True,
                              timeout=1800 if '--build' in arguments else 300).stdout.strip()
    def sql(query):
        return run('exec', '-T', 'postgres', 'psql', '-U', 'purpuragenda_owner', '-d', 'purpuragenda', '-Atc', query)
    def get(service, url, *headers):
        return run('exec', '-T', service, 'wget', '-q', '-O', '-', *headers, url)
    try:
        LOCAL.require_docker(environment)
        networks = run('network', 'ls', '-q', base_command=['docker']).splitlines()
        existing = json.loads(run('network', 'inspect', *networks, base_command=['docker'])) if networks else []
        subnets = allocated_subnets(existing)
        choices = [ipaddress.ip_network(f'172.30.{i}.0/28') for i in range(80, 250)]
        subnet = next(n for n in choices if not any(n.overlaps(old) for old in subnets if n.version == old.version))
        environment.update(PURPURAGENDA_PROXY_SUBNET=str(subnet), PURPURAGENDA_PROXY_ADDRESS=str(subnet.network_address + 2),
                           PURPURAGENDA_PROXY_POOL=str(subnet.network_address + 8) + '/29')
        LOCAL.prepare(directory / 'secrets')
        original = {p.name: p.read_bytes() for p in (directory / 'secrets').iterdir()}
        shutil.copyfile(ROOT / 'ops/caddy/Caddyfile', directory / 'Caddyfile')
        validate_configuration(json.loads(run('config', '--format', 'json', base_command=base)))
        assert not any(s.get('ports') for s in json.loads(run('config', '--format', 'json'))['services'].values())
        report['checks'].append('configuration')
        run('up', '--build', '--wait', '--wait-timeout', '240')
        assert json.loads(get('web', 'http://127.0.0.1:8080/healthz'))['status'] == 'UP'
        assert '<app-root>' in get('web', 'http://127.0.0.1:8080/')
        assert sql('SELECT count(*) FROM service') == '2'
        assert sql('SELECT count(*) FROM scheduling_resource') == '1'
        for name in ('postgres', 'backend', 'web', 'mailpit'):
            identity = run('inspect', '--format', '{{.Config.User}}', run('ps', '-q', name), base_command=['docker'])
            assert identity and identity.split(':')[0] not in ('0', 'root')
        report['checks'].append('startup')
        sql("UPDATE service SET name='Synthetic persisted service' WHERE id='10000000-0000-0000-0000-000000000001'")
        run('exec', '-T', 'mailpit', '/mailpit', 'sendmail', 'synthetic@example.test',
            input='From: sender@example.test\nTo: synthetic@example.test\nSubject: Synthetic persistence check\n\nSynthetic content.\n')
        count = lambda: json.loads(get('mailpit', 'http://127.0.0.1:8025/api/v1/messages'))['total']
        assert count() == 1
        run('stop')
        LOCAL.prepare(directory / 'secrets')
        assert original == {p.name: p.read_bytes() for p in (directory / 'secrets').iterdir()}
        run('up', '--force-recreate', '--wait', '--wait-timeout', '240')
        assert sql('SELECT count(*) FROM service') == '2'
        assert sql("SELECT count(*) FROM service WHERE name='Synthetic persisted service'") == '1'
        assert count() == 1
        report['checks'].append('persistence')
        sql('UPDATE operation_control SET maintenance=true WHERE id=1')
        assert 'DOWN' in run('exec', '-T', 'backend', 'curl', '-sS', 'http://127.0.0.1:8081/actuator/health/readiness')
        sql('UPDATE operation_control SET maintenance=false WHERE id=1')
        assert json.loads(get('web', 'http://127.0.0.1:8080/healthz'))['status'] == 'UP'
        report['checks'].append('maintenance')
        run('--profile', 'proxy-probe', 'up', '-d', '--wait', '--wait-timeout', '90', 'proxy-probe')
        caddy = (ROOT / 'ops/caddy/Caddyfile').read_text().replace('backend:8081', 'proxy-probe:8081')
        (directory / 'Caddyfile').write_text(caddy)
        run('up', '-d', '--force-recreate', '--wait', '--wait-timeout', '90', 'web')
        headers = ('--header=X-Purpuragenda-Client-IP: 203.0.113.9', '--header=Forwarded: for=203.0.113.9',
                   '--header=X-Forwarded-For: 203.0.113.9', '--header=X-Forwarded-Host: synthetic.invalid', '--header=X-Forwarded-Proto: https')
        assert get('web', 'http://proxy-probe:8081/api/probe', *headers).startswith('203.0.113.9|')
        assert get('web', 'http://127.0.0.1:8080/api/probe', *headers) == '127.0.0.1|-|-|-|-'
        report['checks'].append('proxy-headers')
        report['passed'] = True
    finally:
        report['project'] = project
        report['stateDirectory'] = str(directory)
        try:
            run('--profile', 'proxy-probe', 'down')  # Preserve named volumes AND the matching private secrets.
        except (OSError, subprocess.SubprocessError):
            report['passed'] = False
        write_artifact(ROOT, receipt, json.dumps(report, indent=2) + '\n')
    validate_report(report, revision)
    print('Compose configuration, startup, restart, persistent SMTP and proxy boundary verified: ' + report['execution'])


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, AssertionError, StopIteration, subprocess.SubprocessError):
        raise SystemExit('COMPOSE_VERIFICATION_FAILED: require complete current-revision persistence evidence.') from None
