"""Local exposure and incomplete/stale persistence receipts cannot pass verification."""
import copy
import importlib.util
from pathlib import Path
import unittest
import sys

sys.path.insert(0, str(Path(__file__).parent))

SPEC = importlib.util.spec_from_file_location('compose_gate', Path(__file__).with_name('verify-compose.py'))
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class ComposeGateTest(unittest.TestCase):
    def fixture(self):
        runtime = {'read_only': True, 'cap_drop': ['ALL'], 'security_opt': ['no-new-privileges:true'], 'logging': {'driver': 'none'}}
        services = {n: copy.deepcopy(runtime) for n in ('postgres', 'migrate', 'backend', 'web', 'mailpit')}
        for name, role in (('migrate', 'migrator'), ('backend', 'app')):
            services[name]['environment'] = {'APP_DATABASE_USER': 'purpuragenda_' + role}
        services['backend']['environment'].update(APP_MODE='local', APP_SMTP_HOST='mailpit', APP_SMTP_PORT='1025')
        services['backend']['environment']['APP_SECURITY_TRUSTED_PROXY_PEERS'] = '172.30.78.2'
        services['web']['networks'] = {'proxy': {'ipv4_address': '172.30.78.2'}}
        services['migrate']['secrets'] = [{'source': 'migrator_password'}]
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


if __name__ == '__main__':
    unittest.main()
