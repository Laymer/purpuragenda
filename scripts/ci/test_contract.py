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
        self.assertEqual({'listServices', 'availability'}, {op['operationId'] for op in operations})
        self.assertTrue(all(op['security'] == [] for op in operations))
        self.assertEqual(len(operations), len({op['operationId'] for op in operations}))

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
            self.assertIn('application/problem+json', path['get']['responses']['default']['content'])

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


if __name__ == '__main__':
    unittest.main()
