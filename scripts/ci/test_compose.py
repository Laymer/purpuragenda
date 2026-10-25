"""Local exposure and incomplete/stale persistence receipts cannot pass verification."""
import base64
import copy
import importlib.util
import re
from pathlib import Path
import unittest
import sys

sys.path.insert(0, str(Path(__file__).parent))

SPEC = importlib.util.spec_from_file_location('compose_gate', Path(__file__).with_name('verify-compose.py'))
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)
import operator_session as OPERATOR


class ComposeGateTest(unittest.TestCase):
    def fixture(self):
        runtime = {'read_only': True, 'cap_drop': ['ALL'], 'security_opt': ['no-new-privileges:true'], 'logging': {'driver': 'none'}}
        services = {n: copy.deepcopy(runtime) for n in ('postgres', 'migrate', 'backend', 'web', 'mailpit')}
        for name, role in (('migrate', 'migrator'), ('backend', 'app')):
            services[name]['environment'] = {'APP_DATABASE_USER': 'purpuragenda_' + role}
        services['backend']['environment'].update(APP_MODE='local', APP_SMTP_HOST='mailpit', APP_SMTP_PORT='1025')
        services['backend']['environment']['APP_SECURITY_TRUSTED_PROXY_PEERS'] = '172.30.78.2'
        services['web']['networks'] = {'proxy': {'ipv4_address': '172.30.78.2'}}
        for name, sources in GATE.SECRETS.items():
            if name != 'operator':
                services[name]['secrets'] = [{'source': source} for source in sorted(sources)]
        services['web']['ports'] = [{'host_ip': '127.0.0.1'}]
        networks = {n: {'internal': True} for n in ('private', 'proxy', 'mail')}
        networks['proxy']['ipam'] = {'config': [{'subnet': '172.30.78.0/28', 'ip_range': '172.30.78.8/29'}]}
        return {'services': services, 'networks': networks}

    def test_unsafe_ports_privileges_roles_and_socket_mounts_are_refused(self):
        GATE.validate_configuration(self.fixture())
        for name, field, value in [('web', 'ports', [{'host_ip': '0.0.0.0'}]), ('backend', 'ports', [{'host_ip': '127.0.0.1'}]),
                                   ('postgres', 'read_only', False), ('backend', 'cap_drop', []), ('web', 'security_opt', []),
                                   ('postgres', 'logging', {'driver': 'json-file'}), ('web', 'volumes', ['/var/run/docker.sock:/socket']),
                                   ('backend', 'environment', {'APP_DATABASE_USER': 'purpuragenda_migrator'}),
                                   ('migrate', 'secrets', [{'source': 'totp_key'}])]:
            changed = self.fixture()
            changed['services'][name][field] = value
            with self.assertRaises(ValueError):
                GATE.validate_configuration(changed)
        changed = self.fixture()
        changed['services']['postgres']['tmpfs'] = ['/tmp', 'gid=999']
        with self.assertRaises(ValueError):
            GATE.validate_configuration(changed)
        changed = self.fixture()
        changed['networks']['proxy']['internal'] = False
        with self.assertRaises(ValueError):
            GATE.validate_configuration(changed)

    def test_every_service_has_exactly_its_secret_set(self):
        self.assertEqual({'app_password', 'totp_key', 'delivery_key', 'rate_key'}, GATE.SECRETS['backend'])
        self.assertEqual({'postgres_password', 'migrator_password', 'app_password'}, GATE.SECRETS['postgres'])
        self.assertEqual({'migrator_password'}, GATE.SECRETS['migrate'])
        self.assertEqual(set(), GATE.SECRETS['web'] | GATE.SECRETS['mailpit'])
        for name, sources in GATE.SECRETS.items():
            if name == 'operator':
                continue
            for changed_sources in [sources | {'extra_password'}, set(list(sorted(sources))[1:]) if sources else {'app_password'}]:
                changed = self.fixture()
                if changed_sources:
                    changed['services'][name]['secrets'] = [{'source': s} for s in sorted(changed_sources)]
                else:
                    changed['services'][name].pop('secrets', None)
                with self.assertRaises(ValueError):
                    GATE.validate_configuration(changed)
        absent = self.fixture()
        absent['services']['web'].pop('secrets')
        GATE.validate_configuration(absent)

    def test_private_operator_profile_has_no_exposure_and_exact_secrets(self):
        document = self.fixture()
        operator = copy.deepcopy(document['services']['web'])
        operator.pop('ports')
        operator.pop('networks')
        operator.update(command=['admin-setup'], tty=True, stdin_open=True, networks={'private': None},
                        secrets=[{'source': s} for s in sorted(GATE.SECRETS['operator'])],
                        environment={'APP_DATABASE_USER': 'purpuragenda_app', 'APP_MODE': 'local'})
        document['services']['operator'] = operator
        GATE.validate_configuration(document)
        for field, value in [('ports', [{'host_ip': '127.0.0.1'}]), ('tty', False), ('networks', {'private': None, 'mail': None}),
                             ('secrets', [{'source': 'app_password'}]), ('command', ['migrate']),
                             ('secrets', [{'source': s} for s in sorted(GATE.SECRETS['backend'])])]:
            changed = copy.deepcopy(document)
            changed['services']['operator'][field] = value
            with self.assertRaises(ValueError):
                GATE.validate_configuration(changed)

    def test_caddy_contract_overwrites_client_address_and_strips_forwarding_without_trusting_proxies(self):
        text = (Path(__file__).parents[2] / 'ops/caddy/Caddyfile').read_text()
        snippet = re.search(r'\(backend\) \{\n(.*?)\n\}\n', text, re.S).group(1)
        self.assertEqual(['header_up -Forwarded', 'header_up -X-Forwarded-For', 'header_up -X-Forwarded-Host',
                          'header_up -X-Forwarded-Proto', 'header_up X-Purpuragenda-Client-IP {remote_host}'],
                         [line.strip() for line in snippet.splitlines() if line.strip().startswith('header_up')])
        self.assertNotIn('trusted_proxies', text)
        self.assertEqual(1, text.count('reverse_proxy backend:8081'))
        for route in ('/healthz', '/api/*'):
            self.assertRegex(text, r'handle ' + re.escape(route) + r' \{[^}]*import backend')
        self.assertRegex(text, r'handle /actuator/\* \{\s*respond 404')

    def test_receipt_requires_exact_clean_revision_all_checks_and_execution_location(self):
        report = {'revision': 'a' * 40, 'dirty': False, 'passed': True, 'checks': sorted(GATE.CHECKS), 'execution': 'local-docker'}
        GATE.validate_report(report, 'a' * 40)
        for field, value in [('revision', 'b' * 40), ('dirty', True), ('passed', False), ('checks', []),
                             ('checks', sorted(GATE.CHECKS) + ['startup']), ('execution', 'unknown')]:
            with self.assertRaises(ValueError):
                GATE.validate_report({**report, field: value}, 'a' * 40)
        for field in report:
            changed = dict(report)
            changed.pop(field)
            with self.assertRaises(ValueError):
                GATE.validate_report(changed, 'a' * 40)

    def test_sender_does_not_allow_external_transport_configuration(self):
        document = self.fixture()
        document['services']['backend']['environment']['APP_MAIL_FROM'] = 'project@example.test'
        GATE.validate_configuration(document)
        for name, value in [('APP_MODE', 'operational'), ('APP_SMTP_HOST', 'smtp.example.test'), ('APP_SMTP_PORT', '587')]:
            changed = copy.deepcopy(document)
            changed['services']['backend']['environment'][name] = value
            with self.assertRaises(ValueError):
                GATE.validate_configuration(changed)

    def test_proxy_peer_must_be_fixed_and_outside_dynamic_pool(self):
        changed = self.fixture()
        changed['networks']['proxy']['ipam']['config'][0]['ip_range'] = '172.30.78.0/28'
        with self.assertRaises(ValueError):
            GATE.validate_configuration(changed)
        changed = self.fixture()
        changed['services']['backend']['environment']['APP_SECURITY_TRUSTED_PROXY_PEERS'] = '172.30.78.0/28'
        with self.assertRaises(ValueError):
            GATE.validate_configuration(changed)

    def test_host_and_none_networks_have_no_ipam_allocations(self):
        networks = [{'IPAM': {'Config': None}}, {'IPAM': {}},
                    {'IPAM': {'Config': [{'Subnet': '172.30.78.0/28'}, {}]}}]
        self.assertEqual(['172.30.78.0/28'], [str(n) for n in GATE.allocated_subnets(networks)])

    def test_frontend_marker_is_tolerant_of_attributes_but_not_unrelated_text(self):
        local = importlib.util.module_from_spec(importlib.util.spec_from_file_location('local_marker', Path(__file__).parents[1] / 'dev/local.py'))
        local.__spec__.loader.exec_module(local)
        for text in ('<app-root></app-root>', '<app-root ng-version="21">', '<app-root\n_nghost-ng-c1>', '<app-root/>'):
            self.assertTrue(local.FRONTEND_MARKER.search(text))
        for text in ('', '<app-rooted>', 'app-root', '<app-roots>'):
            self.assertFalse(local.FRONTEND_MARKER.search(text))

    def test_receipt_never_records_host_state_directory(self):
        self.assertNotIn('stateDirectory', Path(GATE.__file__).read_text())

    def test_proxy_probe_source_path_ends_with_its_package_directories(self):
        # The JDK source launcher rejects a packaged source file mounted outside its package path.
        text = (Path(__file__).parents[2] / 'compose.verify.yaml').read_text()
        package_path = 'com/purpuragenda/bootstrap'
        self.assertIn('command: [/probe/' + package_path + '/ProxyHeaderProbe.java]', text)
        self.assertIn(':/probe/' + package_path + ':ro', text)


class OperatorSessionTest(unittest.TestCase):
    def test_totp_matches_rfc6238_sha1_vectors(self):
        key = base64.b32encode(b'12345678901234567890').decode()  # RFC 6238 appendix B key
        for moment, expected in [(59, '287082'), (1111111109, '081804'), (1234567890, '005924')]:
            self.assertEqual(expected, OPERATOR.totp(key, moment))

    def test_pty_session_reads_prompts_hides_unexpected_text_and_reports_status(self):
        program = ('import getpass,sys\nname=input("Name: ")\nsecret=getpass.getpass("Secret: ")\n'
                   'print("hello", name)\nsys.exit(3 if secret=="synthetic" else 0)')
        session = OPERATOR.Session([sys.executable, '-c', program], None, None, timeout=20)
        session.expect('Name: ')
        session.send('ada')
        session.expect('Secret: ')
        session.send('synthetic')
        session.expect('hello ada')
        status, remainder = session.finish()
        self.assertEqual(3, status)
        self.assertNotIn('synthetic', remainder)

    def test_session_fails_closed_when_program_ends_or_stalls(self):
        ended = OPERATOR.Session([sys.executable, '-c', 'print("done")'], None, None, timeout=20)
        with self.assertRaises(EOFError):
            ended.expect('never appears')
        ended.finish()
        stalled = OPERATOR.Session([sys.executable, '-c', 'import time;time.sleep(30)'], None, None, timeout=1)
        with self.assertRaises(TimeoutError):
            stalled.expect('never appears')
        stalled.process.kill()
        stalled.finish()


if __name__ == '__main__':
    unittest.main()
