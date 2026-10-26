"""Contract seam and deterministic-generation regression tests; no live service required."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('contract_gate', Path(__file__).with_name('check-contract.py'))
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class ContractTest(unittest.TestCase):
    def setUp(self):
        self.document = json.loads((ROOT / 'contracts/openapi.yaml').read_text())

    def test_public_operations_and_same_origin_base(self):
        self.assertEqual('3.1.0', self.document['openapi'])
        self.assertEqual([{'url': '/api/v1'}], self.document['servers'])
        operations = [operation for path in self.document['paths'].values() for operation in path.values()]
        self.assertEqual({'listServices', 'availability', 'bootstrapSession', 'getSession', 'logoutSession',
                          'requestCreateProof', 'requestManagementProof', 'redeemGuestProof', 'loginAdministrator',
                          'createBooking', 'getBooking', 'cancelBooking', 'rescheduleBooking'},
                         {op['operationId'] for op in operations})
        public = {op['operationId'] for op in operations if op['security'] == []}
        self.assertEqual({'listServices', 'availability', 'bootstrapSession'}, public)
        self.assertEqual(len(operations), len({op['operationId'] for op in operations}))

    def test_administrator_login_requires_session_csrf_and_exact_write_only_factors(self):
        operation = self.document['paths']['/admin/login']['post']
        self.assertEqual([{'sessionCookie': []}], operation['security'])
        self.assertEqual(['X-CSRF-Token'], [header['name'] for header in operation['parameters']])
        schema = self.document['components']['schemas']['AdministratorLoginRequest']
        self.assertFalse(schema['additionalProperties'])
        self.assertEqual({'email', 'password', 'totp'}, set(schema['required']))
        self.assertEqual(set(schema['required']), set(schema['properties']))
        self.assertTrue(schema['properties']['password']['writeOnly'])
        self.assertTrue(schema['properties']['totp']['writeOnly'])
        self.assertEqual((15, 128), (schema['properties']['password']['minLength'], schema['properties']['password']['maxLength']))
        self.assertEqual('^[0-9]{6}$', schema['properties']['totp']['pattern'])
        self.assertEqual('#/components/schemas/SessionBootstrap', operation['responses']['200']['content']['application/json']['schema']['$ref'])
        self.assertIn('Set-Cookie', operation['responses']['200']['headers'])
        self.assertIn('AUTHENTICATION_FAILED', operation['responses']['401']['description'])
        self.assertIn('Retry-After', operation['responses']['429']['headers'])
        for response in operation['responses'].values():
            self.assertEqual(['no-store'], response['headers']['Cache-Control']['schema']['enum'])

    def test_customer_commands_bind_receipts_versions_and_explicit_private_projections(self):
        paths = self.document['paths']
        for path, method in (('/bookings', 'post'), ('/bookings/{bookingId}', 'get'),
                             ('/bookings/{bookingId}/cancel', 'post'), ('/bookings/{bookingId}/reschedule', 'post')):
            operation = paths[path][method]
            self.assertEqual([{'sessionCookie': []}], operation['security'])
            headers = {value['name']: value for value in operation['parameters'] if value['in'] == 'header'}
            if method == 'post':
                self.assertTrue(headers['X-CSRF-Token']['required'])
                self.assertTrue(headers['Idempotency-Key']['required'])
                self.assertTrue(operation['requestBody']['required'])
            if path.endswith(('/cancel', '/reschedule')):
                self.assertTrue(headers['If-Match']['required'])
                self.assertTrue({'404', '412', '428'} <= operation['responses'].keys())
            for response in operation['responses'].values():
                if '$ref' in response:
                    response = self.document['components']['responses'][response['$ref'].rsplit('/', 1)[1]]
                self.assertEqual(['no-store'], response['headers']['Cache-Control']['schema']['enum'])
        schemas = self.document['components']['schemas']
        request = schemas['CreateBookingRequest']
        self.assertEqual({'serviceId', 'serviceVersion', 'policyVersion', 'startInstant', 'displayZone', 'noticeVersion', 'intake'}, set(request['required']))
        self.assertEqual({'bookingId', 'version'}, set(schemas['BookingReceipt']['required']))
        self.assertEqual({}, schemas['CancelBookingRequest']['properties'])
        self.assertEqual({'policyVersion', 'startInstant'}, set(schemas['RescheduleBookingRequest']['required']))
        for value in schemas['BookingIntake']['properties'].values():
            self.assertTrue(value['writeOnly'])
        projection = schemas['CustomerBookingStatus']['properties']
        self.assertTrue({'pendingExpired', 'version', 'allowedActions', 'payment', 'serverNow', 'customerChangesUntil'} <= projection.keys())
        self.assertFalse({'intake', 'customerId', 'accountId', 'entries', 'snapshot'} & projection.keys())
        self.assertEqual(['string', 'null'], projection['meetingUrl']['type'])
        self.assertEqual(['CANCEL', 'RESCHEDULE'], projection['allowedActions']['items']['enum'])
        self.assertFalse(projection['allowedActions'].get('uniqueItems', False), 'The pinned Angular generator otherwise emits an unhydrated Set for a JSON array')
        self.assertEqual('#/components/schemas/CustomerBookingStatus', paths['/bookings/{bookingId}']['get']['responses']['200']['content']['application/json']['schema']['$ref'])
        self.assertIn('Location', paths['/bookings']['post']['responses']['201']['headers'])

    def test_all_references_resolve(self):
        def visit(value):
            if isinstance(value, dict):
                if '$ref' in value:
                    reference = value['$ref']
                    self.assertTrue(reference.startswith('#/'))
                    target = self.document
                    for key in reference[2:].split('/'):
                        target = target[key]
                    self.assertIsInstance(target, dict)
                for child in value.values():
                    visit(child)
            elif isinstance(value, list):
                for child in value:
                    visit(child)
        visit(self.document)

    def test_snapshot_values_and_time_labels_are_required(self):
        schemas = self.document['components']['schemas']
        for name, schema in schemas.items():
            with self.subTest(schema=name):
                self.assertFalse(schema['additionalProperties'])
                self.assertTrue(set(schema['required']) <= schema['properties'].keys())
        service = schemas['Service']['properties']
        self.assertEqual(120, service['durationMinutes']['maximum'])
        self.assertEqual(100000000, service['priceMinor']['maximum'])
        self.assertEqual(['EUR'], service['currency']['enum'])
        self.assertIn('offset', schemas['Slot']['required'])
        self.assertIn('reservedEndInstant', schemas['Slot']['required'])
        self.assertEqual(['Europe/Brussels'], schemas['Availability']['properties']['businessZone']['enum'])
        self.assertTrue({'correlationId', 'retryable', 'refreshAvailability'} <= set(schemas['Problem']['required']))

    def test_availability_inputs_and_problem_response(self):
        operation = self.document['paths']['/availability']['get']
        self.assertEqual({'serviceId', 'from', 'to', 'displayZone'}, {p['name'] for p in operation['parameters']})
        self.assertTrue(all(p['required'] for p in operation['parameters']))
        self.assertIn('31', operation['description'])
        for path in self.document['paths'].values():
            for operation in path.values():
                self.assertIn('application/problem+json', operation['responses']['default']['content'])

    def test_session_contract_is_same_origin_csrf_protected_and_no_store(self):
        paths = self.document['paths']
        self.assertEqual({'/session/bootstrap': 'post', '/session': 'get', '/session/logout': 'post'},
                         {path: next(iter(paths[path])) for path in ('/session/bootstrap', '/session', '/session/logout')})
        logout = paths['/session/logout']['post']
        self.assertEqual([{'sessionCookie': []}], logout['security'])
        csrf = logout['parameters'][0]
        self.assertEqual(('X-CSRF-Token', 'header', True), (csrf['name'], csrf['in'], csrf['required']))
        self.assertEqual({'sessionCookie': {'type': 'apiKey', 'in': 'cookie', 'name': '__Host-purpuragenda'}},
                         self.document['components']['securitySchemes'])
        schemas = self.document['components']['schemas']
        self.assertEqual({'role', 'idleExpiresAt', 'absoluteExpiresAt'}, schemas['SessionState']['properties'].keys())
        self.assertEqual({'role', 'idleExpiresAt', 'absoluteExpiresAt', 'csrfToken'}, set(schemas['SessionBootstrap']['required']))
        self.assertEqual(['ANONYMOUS', 'GUEST', 'ACCOUNT', 'ADMIN'], schemas['SessionState']['properties']['role']['enum'])
        for path in paths.values():
            for operation in (op for key, op in path.items() if key in ('get', 'post')):
                if operation['operationId'] in ('bootstrapSession', 'getSession'):
                    self.assertEqual(['no-store'], operation['responses']['200']['headers']['Cache-Control']['schema']['enum'])
                self.assertEqual(['no-store'], operation['responses']['default']['headers']['Cache-Control']['schema']['enum']
                                 if 'headers' in operation['responses']['default'] else ['no-store'])

    def test_generator_pin_and_validation(self):
        from xml.etree import ElementTree as ET
        ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
        tree = ET.parse(ROOT / 'contracts/pom.xml')
        self.assertEqual('7.26.0', tree.findtext('.//m:plugin/m:version', namespaces=ns))
        self.assertEqual('false', tree.findtext('.//m:skipValidateSpec', namespaces=ns))
        self.assertEqual('true', tree.findtext('.//m:hideGenerationTimestamp', namespaces=ns))

    def test_generated_file_addition_removal_and_modification_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            first, second = Path(temp) / 'first', Path(temp) / 'second'
            first.mkdir(); second.mkdir()
            (first / 'client.ts').write_text('original')
            (second / 'client.ts').write_text('original')
            self.assertEqual([], GATE.compare(first, second))
            (second / 'client.ts').write_text('stale')
            self.assertEqual(['client.ts'], GATE.compare(first, second))
            (second / 'client.ts').unlink()
            (second / 'extra.ts').write_text('unexpected')
            self.assertEqual(['client.ts', 'extra.ts'], GATE.compare(first, second))

    def test_missing_empty_or_symlinked_generation_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaises(ValueError):
                GATE.contents(root / 'absent')
            with self.assertRaises(ValueError):
                GATE.contents(root)
            (root / 'link').symlink_to(ROOT / 'contracts/openapi.yaml')
            with self.assertRaises(ValueError):
                GATE.contents(root)


    def test_replacement_preserves_old_sdk_for_invalid_generation(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            destination = root / 'frontend/generated'
            destination.mkdir(parents=True)
            (destination / 'old.ts').write_text('preserve')
            fresh = root / 'fresh'
            fresh.mkdir()
            for source in (fresh, root / 'absent'):
                with self.subTest(source=source), self.assertRaises(ValueError):
                    GATE.replace_generated(source, destination, root)
                self.assertEqual('preserve', (destination / 'old.ts').read_text())
            (fresh / 'bad.ts').symlink_to(destination / 'old.ts')
            with self.assertRaises(ValueError):
                GATE.replace_generated(fresh, destination, root)
            self.assertEqual('preserve', (destination / 'old.ts').read_text())

    def test_replacement_rejects_symlink_ancestors_and_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            root, outside, fresh = base / 'repo', base / 'outside', base / 'fresh'
            root.mkdir(); outside.mkdir(); fresh.mkdir()
            (fresh / 'new.ts').write_text('new')
            (outside / 'generated').mkdir()
            (outside / 'generated/old.ts').write_text('preserve')
            (root / 'frontend').symlink_to(outside, target_is_directory=True)
            with self.assertRaises(ValueError):
                GATE.replace_generated(fresh, root / 'frontend/generated', root)
            self.assertEqual('preserve', (outside / 'generated/old.ts').read_text())
            with self.assertRaises(ValueError):
                GATE.contents(root / 'frontend')
            with self.assertRaises(ValueError):
                GATE.replace_generated(fresh, outside / 'generated', root)

    def test_replacement_removes_stale_files_after_valid_staging(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            fresh, destination = root / 'fresh', root / 'generated'
            fresh.mkdir(); destination.mkdir()
            (fresh / 'new.ts').write_text('new')
            (destination / 'old.ts').write_text('old')
            GATE.replace_generated(fresh, destination, root)
            self.assertEqual({'new.ts': b'new'}, GATE.contents(destination))


    def test_failed_install_restores_previous_sdk(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            fresh, destination = root / 'fresh', root / 'generated'
            fresh.mkdir(); destination.mkdir()
            (fresh / 'new.ts').write_text('new')
            (destination / 'old.ts').write_text('preserve')
            rename = Path.rename
            def fail_install(source, target):
                if source.name == 'new':
                    raise OSError('synthetic install failure')
                return rename(source, target)
            with patch.object(Path, 'rename', fail_install), self.assertRaises(OSError):
                GATE.replace_generated(fresh, destination, root)
            self.assertEqual({'old.ts': b'preserve'}, GATE.contents(destination))


if __name__ == '__main__':
    unittest.main()
