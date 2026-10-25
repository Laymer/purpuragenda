"""Synthetic completeness, vulnerability and tool failures must block dependency acceptance."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


GATE = load('dependency_gate', 'scripts/ci/check-dependencies.py')
INSTALL = load('security_install', 'scripts/dev/install-security-tools.py')


class DependencyGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'domain').mkdir()
        (self.root / 'pom.xml').write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
          <groupId>com.synthetic</groupId><artifactId>booking</artifactId><version>1.0</version>
          <modules><module>domain</module></modules></project>''')
        (self.root / 'domain/pom.xml').write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
          <artifactId>domain</artifactId><dependencies><dependency><groupId>org.synthetic</groupId>
          <artifactId>dependency</artifactId><version>2.0</version></dependency></dependencies></project>''')
        def component(group, name, version):
            purl = f'pkg:maven/{group}/{name}@{version}'
            return {'type': 'library', 'group': group, 'name': name, 'version': version,
                    'purl': purl, 'bom-ref': purl, 'author': 'synthetic-private-marker'}
        root = component('com.synthetic', 'booking', '1.0')
        domain = component('com.synthetic', 'domain', '1.0')
        external = component('org.synthetic', 'dependency', '2.0')
        self.document = {'bomFormat': 'CycloneDX', 'specVersion': '1.6', 'version': 1,
                         'metadata': {'component': root, 'authors': ['synthetic-private-marker']},
                         'components': [domain, external], 'dependencies': [
                             {'ref': root['purl'], 'dependsOn': [domain['purl']]},
                             {'ref': domain['purl'], 'dependsOn': [external['purl']]},
                             {'ref': external['purl'], 'dependsOn': []}]}
        self.expected = {('Maven', 'com.synthetic:domain', '1.0'), ('Maven', 'org.synthetic:dependency', '2.0')}

    def report(self, issues=False):
        packages = [{'package': dict(zip(('ecosystem', 'name', 'version'), key))}
                    for key in sorted(self.expected)]
        if issues:
            packages[0]['vulnerabilities'] = [{'id': 'CVE-2026-00001', 'credits': ['synthetic-private-marker']}]
        return {'results': [{'packages': packages}]}

    def test_complete_sbom_corresponds_to_source_and_removes_private_metadata(self):
        self.document['dependencies'][0]['provider_error'] = 'synthetic-private-marker'
        clean, packages = GATE.sbom(self.document, self.root)
        self.assertEqual(self.expected, packages)
        self.assertNotIn('synthetic-private-marker', json.dumps(clean))
        self.assertEqual(3, len(clean['dependencies']))

    def test_wrong_root_and_missing_declared_dependency_fail(self):
        changed = copy.deepcopy(self.document); changed['metadata']['component']['version'] = 'wrong'
        with self.assertRaises(ValueError): GATE.sbom(changed, self.root)
        changed = copy.deepcopy(self.document); changed['components'] = changed['components'][:1]
        changed['dependencies'] = changed['dependencies'][:2]
        changed['dependencies'][1]['dependsOn'] = []
        with self.assertRaisesRegex(ValueError, 'omits declared'): GATE.sbom(changed, self.root)

    def test_truncated_or_unknown_transitive_graph_fails(self):
        changed = copy.deepcopy(self.document); changed['dependencies'].pop()
        with self.assertRaisesRegex(ValueError, 'Incomplete'): GATE.sbom(changed, self.root)
        changed = copy.deepcopy(self.document); changed['dependencies'][0]['dependsOn'].append('unknown')
        with self.assertRaisesRegex(ValueError, 'Unknown'): GATE.sbom(changed, self.root)

    def test_empty_duplicate_and_malformed_sbom_fail(self):
        for value in (None, {}, [], dict(self.document, components=[])):
            with self.subTest(value=value), self.assertRaises(ValueError): GATE.sbom(value, self.root)
        changed = copy.deepcopy(self.document); changed['components'].append(changed['components'][0])
        with self.assertRaisesRegex(ValueError, 'Duplicate'): GATE.sbom(changed, self.root)
        changed = copy.deepcopy(self.document); changed['components'][0]['purl'] = 'https://synthetic.invalid'
        with self.assertRaisesRegex(ValueError, 'purl'): GATE.sbom(changed, self.root)

    def test_npm_lock_covers_scoped_and_nested_versions(self):
        lock = {'lockfileVersion': 3, 'packages': {'': {'name': 'app'},
                'node_modules/@synthetic/library': {'version': '1.0.0'},
                'node_modules/parent/node_modules/nested': {'version': '2.0.0'}}}
        self.assertEqual({('npm', '@synthetic/library', '1.0.0'), ('npm', 'nested', '2.0.0')},
                         GATE.npm_packages(lock))

    def test_npm_missing_versions_empty_inventory_and_links_fail(self):
        for packages in ({}, {'node_modules/a': {}}, {'node_modules/a': {'link': True}}, {'elsewhere': {}}):
            with self.subTest(packages=packages), self.assertRaises(ValueError):
                GATE.npm_packages({'lockfileVersion': 3, 'packages': packages})

    def test_complete_clean_and_vulnerable_reports(self):
        self.assertEqual([], GATE.osv_report(self.report(), self.expected, 0))
        issues = GATE.osv_report(self.report(True), self.expected, 1)
        self.assertEqual('CVE-2026-00001', issues[0]['id'])
        self.assertNotIn('synthetic-private-marker', json.dumps(issues))

    def test_feed_failure_and_no_packages_cannot_pass(self):
        for code in (127, 128, -1):
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, 'execution failed'):
                GATE.osv_report(self.report(), self.expected, code)

    def test_omitted_resolved_package_fails_even_with_success_exit(self):
        document = self.report(); document['results'][0]['packages'].pop()
        with self.assertRaisesRegex(ValueError, 'omits resolved'):
            GATE.osv_report(document, self.expected, 0)

    def test_malformed_and_inconsistent_reports_fail(self):
        for document in (None, [], {}, {'results': []}, {'results': [{'packages': None}]}):
            with self.subTest(document=document), self.assertRaises(ValueError):
                GATE.osv_report(document, self.expected, 0)
        for document, code in ((self.report(), 1), (self.report(True), 0)):
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, 'disagree'):
                GATE.osv_report(document, self.expected, code)

    def test_missing_current_report_invalidates_stale_success(self):
        (self.root / 'osv.json').write_text(json.dumps(self.report()))
        with patch.object(GATE, 'invoke', return_value=subprocess.CompletedProcess([], 0)):
            with self.assertRaisesRegex(ValueError, 'report is missing'):
                GATE.scan(Path('scanner'), [], self.expected, self.root)

    def test_missing_tool_erases_prior_dependency_receipts(self):
        (self.root / 'ops').mkdir()
        (self.root / 'ops/security-tools.lock.json').write_text(json.dumps({'osv-scanner': {}}))
        output = self.root / 'artifacts/security'; output.mkdir(parents=True)
        for name in ('dependencies.json', 'java.cdx.json'): (output / name).write_text('stale')
        with self.assertRaisesRegex(ValueError, 'unavailable'): GATE.run(self.root)
        self.assertEqual([], list(output.iterdir()))

    def test_missing_configuration_also_invalidates_old_receipts(self):
        output = self.root / 'artifacts/security'; output.mkdir(parents=True)
        (output / 'dependencies.json').write_text('{"passed":true}')
        with self.assertRaises(OSError): GATE.run(self.root)
        self.assertEqual([], list(output.iterdir()))

    def prepared_run(self, scan_results):
        binary = self.root / '.cache/security/osv-scanner'
        binary.parent.mkdir(parents=True); binary.write_bytes(b'synthetic tool'); binary.chmod(0o755)
        (self.root / 'ops').mkdir()
        (self.root / 'ops/security-tools.lock.json').write_text(json.dumps({
            'osv-scanner': {'version': '2.7.0', 'binarySha256': INSTALL.digest(binary.read_bytes())},
            'cyclonedx-maven': {'version': '2.9.3'}}))
        def invoke(command):
            directory = next(value.split('=', 1)[1] for value in command if value.startswith('-DoutputDirectory='))
            Path(directory, 'bom.json').write_text(json.dumps(self.document))
            return subprocess.CompletedProcess(command, 0)
        with patch.object(subprocess, 'check_output', return_value='osv-scanner version: 2.7.0\n'), \
                patch.object(GATE, 'invoke', side_effect=invoke), patch.object(GATE, 'scan', side_effect=scan_results):
            return GATE.run(self.root)

    def test_successful_run_keeps_only_sanitized_sbom_and_receipt(self):
        result = self.prepared_run([[{'id': 'CVE-2021-44228'}], []])
        self.assertTrue(result['passed'])
        self.assertEqual(2, result['packages'])
        report = self.root / 'artifacts/security/java.cdx.json'
        self.assertNotIn('synthetic-private-marker', report.read_text())

    def test_missing_live_detection_canary_blocks_receipts(self):
        with self.assertRaisesRegex(ValueError, 'canary failed'):
            self.prepared_run([[]])
        self.assertFalse((self.root / 'artifacts/security/dependencies.json').exists())

    def test_vulnerability_results_block_and_leave_safe_failure_receipt(self):
        with self.assertRaisesRegex(ValueError, 'vulnerabilities block'):
            self.prepared_run([[{'id': 'CVE-2021-44228'}], [{'id': 'CVE-2026-00001'}]])
        receipt = json.loads((self.root / 'artifacts/security/dependencies.json').read_text())
        self.assertFalse(receipt['passed'])

    def test_pinned_raw_scanner_binary_rejects_tampering(self):
        data = b'synthetic executable'
        pin = {'format': 'executable', 'binarySha256': INSTALL.digest(data)}
        self.assertEqual(data, INSTALL.materialize(data, pin))
        with self.assertRaisesRegex(ValueError, 'checksum'): INSTALL.materialize(data + b'bad', pin)

    def test_official_tools_and_transitive_scope_are_pinned(self):
        lock = json.loads((ROOT / 'ops/security-tools.lock.json').read_text())
        self.assertEqual('2.7.0', lock['osv-scanner']['version'])
        self.assertEqual('2.9.3', lock['cyclonedx-maven']['version'])
        self.assertTrue(lock['osv-scanner']['url'].startswith('https://github.com/google/osv-scanner/releases/'))
        self.assertRegex(lock['osv-scanner']['binarySha256'], r'^[0-9a-f]{64}$')


if __name__ == '__main__':
    unittest.main()
