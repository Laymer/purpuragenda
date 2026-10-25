#!/usr/bin/env python3
"""Generate a source-corresponding Java SBOM and fail closed on public OSV findings."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import unquote
from xml.etree import ElementTree as ET
from artifact_paths import remove_artifact, write_artifact

ROOT = Path(__file__).resolve().parents[2]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def manifests(root):
    project = ET.parse(root / 'pom.xml').getroot()
    group, name, version = (project.findtext('m:' + key, namespaces=NS)
                            for key in ('groupId', 'artifactId', 'version'))
    modules = project.findall('m:modules/m:module', NS)
    require(group and name and version and modules, 'Missing Maven reactor identity')
    identities, declared = set(), {(d.findtext('m:groupId', namespaces=NS),
                                    d.findtext('m:artifactId', namespaces=NS))
                                   for d in project.findall('m:dependencies/m:dependency', NS)}
    for module in modules:
        path = (root / module.text / 'pom.xml').resolve()
        require(path.is_relative_to(root.resolve()), 'Reactor module escapes source root')
        pom = ET.parse(path).getroot()
        artifact = pom.findtext('m:artifactId', namespaces=NS)
        identities.add((pom.findtext('m:groupId', namespaces=NS) or group, artifact))
        for dependency in pom.findall('m:dependencies/m:dependency', NS):
            declared.add((dependency.findtext('m:groupId', namespaces=NS),
                          dependency.findtext('m:artifactId', namespaces=NS)))
    return (group, name, version), identities | declared


def sbom(document, root):
    require(isinstance(document, dict), 'Invalid SBOM document')
    require(document.get('bomFormat') == 'CycloneDX' and document.get('specVersion') == '1.6',
            'Missing or invalid CycloneDX SBOM')
    identity, required = manifests(root)
    require(isinstance(document.get('metadata'), dict), 'Missing SBOM metadata')
    metadata = document['metadata'].get('component', {})
    require(isinstance(metadata, dict), 'Invalid SBOM root metadata')
    require(metadata.get('purl') == metadata.get('bom-ref') and
            unquote(metadata.get('purl', '').split('?')[0]) ==
            f'pkg:maven/{identity[0]}/{identity[1]}@{identity[2]}', 'Invalid SBOM root purl')
    require(tuple(metadata.get(key) for key in ('group', 'name', 'version')) == identity,
            'SBOM root does not correspond to Maven source')
    components = document.get('components')
    require(isinstance(components, list) and components, 'Empty Java dependency inventory')
    clean, packages, coordinates = [], set(), set()
    for component in components:
        require(isinstance(component, dict) and component.get('type') == 'library', 'Invalid SBOM component')
        group, name, version = (component.get(key) for key in ('group', 'name', 'version'))
        require(all(isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_.+:-]{1,200}', value)
                    for value in (group, name, version)), 'Invalid dependency coordinate')
        purl = component.get('purl', '')
        require(unquote(purl.split('?')[0]) == f'pkg:maven/{group}/{name}@{version}',
                'Dependency purl/coordinate mismatch')
        require(component.get('bom-ref') == purl, 'Invalid dependency graph reference')
        require(('Maven', group + ':' + name, version) not in packages, 'Duplicate dependency')
        packages.add(('Maven', group + ':' + name, version)); coordinates.add((group, name))
        clean.append({key: component[key] for key in ('type', 'bom-ref', 'group', 'name', 'version', 'purl')})
    require(required <= coordinates, 'SBOM omits declared dependencies or reactor modules')
    root_ref = metadata.get('bom-ref')
    references = {item['bom-ref'] for item in clean} | {root_ref}
    graph = document.get('dependencies', [])
    require(isinstance(graph, list) and all(isinstance(node, dict) for node in graph),
            'Malformed Java dependency graph')
    require(len(graph) == len(references) and {node.get('ref') for node in graph} == references,
            'Incomplete Java dependency graph')
    for node in graph:
        require(isinstance(node.get('dependsOn'), list) and set(node['dependsOn']) <= references,
                'Unknown or malformed transitive dependency')
    sanitized = {'bomFormat': 'CycloneDX', 'specVersion': '1.6', 'version': 1,
                 'metadata': {'component': {key: metadata[key] for key in
                              ('type', 'bom-ref', 'group', 'name', 'version', 'purl')}},
                 'components': clean, 'dependencies': [{'ref': node['ref'], 'dependsOn': node['dependsOn']} for node in graph]}
    return sanitized, packages


def npm_packages(lock, manifest):
    require(isinstance(lock, dict), 'Invalid npm lock')
    require(lock.get('lockfileVersion') in (2, 3) and isinstance(lock.get('packages'), dict),
            'Missing complete npm lock inventory')
    require(isinstance(manifest, dict), 'Missing npm source manifest')
    package_root = lock['packages'].get('', {})
    require(isinstance(package_root, dict), 'Invalid npm lock root')
    for key in ('name', 'version'):
        require(isinstance(manifest.get(key), str) and manifest[key] and
                lock.get(key) == manifest[key] == package_root.get(key), 'Stale npm root identity')
    for kind in ('dependencies', 'devDependencies', 'optionalDependencies'):
        declared = manifest.get(kind, {})
        require(isinstance(declared, dict) and package_root.get(kind, {}) == declared,
                'npm lock omits or changes source declarations')
        for name, version in declared.items():
            require(isinstance(version, str) and re.fullmatch(r'\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?', version),
                    'Direct npm dependencies must use exact pinned versions')
            resolved = lock['packages'].get('node_modules/' + name, {})
            require(isinstance(resolved, dict) and resolved.get('version') == version,
                    'npm lock omits or changes a direct resolved dependency')
    result = set()
    for path, item in lock['packages'].items():
        require(isinstance(item, dict), 'Invalid npm lock package')
        if not path:
            continue
        require('node_modules/' in path and not item.get('link'), 'Unsupported npm lock entry')
        name = item.get('name') or path.rsplit('node_modules/', 1)[1]
        version = item.get('version')
        require(isinstance(version, str) and version and isinstance(name, str) and name,
                'Unversioned npm dependency')
        result.add(('npm', name, version))
    require(result, 'Empty npm dependency inventory')
    return result


def osv_report(document, expected, exit_code):
    require(isinstance(document, dict), 'Malformed advisory report')
    require(exit_code in (0, 1), 'Advisory feed/scanner execution failed')
    sources = document.get('results')
    require(isinstance(sources, list) and sources, 'Missing OSV package report')
    seen, issues = set(), []
    for source in sources:
        require(isinstance(source, dict), 'Invalid advisory source')
        require(isinstance(source.get('packages'), list), 'Missing OSV source packages')
        for item in source['packages']:
            require(isinstance(item, dict) and isinstance(item.get('package'), dict), 'Invalid advisory package')
            package = item['package']
            key = tuple(package.get(field) for field in ('ecosystem', 'name', 'version'))
            require(key in expected, 'Unexpected advisory package')
            seen.add(key)
            vulnerabilities = item.get('vulnerabilities', [])
            require(isinstance(vulnerabilities, list), 'Invalid advisory findings')
            for finding in vulnerabilities:
                require(isinstance(finding, dict), 'Invalid advisory finding')
                identifier = finding.get('id', '')
                require(re.fullmatch(r'[A-Z][A-Za-z0-9_.:-]{0,127}', identifier), 'Invalid advisory ID')
                issues.append({'ecosystem': key[0], 'package': key[1], 'version': key[2], 'id': identifier})
    require(seen == expected, 'Advisory scan omits resolved dependencies')
    require((exit_code == 0) == (not issues), 'Advisory result/report disagree')
    return issues


def invoke(command, timeout=300):
    return subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                          timeout=timeout, check=False)


def scan(binary, inputs, expected, scratch):
    config, report = scratch / 'osv.toml', scratch / 'osv.json'
    config.write_text('')  # Explicitly avoid repository ignores or vulnerability suppressions.
    if report.exists():
        report.unlink()
    command = [str(binary), 'scan', 'source', '--config', str(config), '--all-packages',
               '--all-vulns', '--no-resolve', '--format', 'json', '--output-file', str(report)]
    for source in inputs:
        command.extend(['--lockfile', str(source)])
    result = invoke(command)
    require(report.is_file(), 'Required advisory report is missing')
    return osv_report(json.loads(report.read_text()), expected, result.returncode)


def run(root):
    output = root / 'artifacts/security'
    for name in ('dependencies.json', 'java.cdx.json'):
        remove_artifact(root, output / name)
    lock = json.loads((root / 'ops/security-tools.lock.json').read_text())
    pin = lock['osv-scanner']; binary = root / '.cache/security/osv-scanner'
    require(binary.is_file() and os.access(binary, os.X_OK), 'Required OSV scanner is unavailable')
    require(hashlib.sha256(binary.read_bytes()).hexdigest() == pin['binarySha256'], 'OSV binary checksum mismatch')
    version = subprocess.check_output([str(binary), '--version'], stderr=subprocess.DEVNULL,
                                      timeout=15, text=True).splitlines()
    require(version and version[0] == 'osv-scanner version: ' + pin['version'], 'OSV version mismatch')
    with tempfile.TemporaryDirectory(prefix='purpuragenda-dependencies-') as temporary:
        scratch = Path(temporary)
        command = [str(root / 'mvnw'), '--batch-mode', '--no-transfer-progress', '-f', str(root / 'pom.xml'),
                   'org.cyclonedx:cyclonedx-maven-plugin:' + lock['cyclonedx-maven']['version'] + ':makeAggregateBom',
                   '-DincludeTestScope=true', '-DoutputFormat=json', '-DschemaVersion=1.6',
                   '-DoutputReactorProjects=false', '-DskipAttach=true', '-DoutputDirectory=' + str(scratch)]
        require(invoke(command).returncode == 0, 'Transitive Java SBOM generation failed')
        java = scratch / 'bom.json'
        require(java.is_file(), 'Required Java SBOM is missing')
        clean, expected = sbom(json.loads(java.read_text()), root)
        java.write_text(json.dumps(clean))
        inputs = [java]
        frontend = root / 'frontend'
        if (frontend / 'package.json').exists():
            require((frontend / 'package-lock.json').is_file(), 'Frontend source requires npm lock')
            npm = json.loads((frontend / 'package-lock.json').read_text())
            expected |= npm_packages(npm, json.loads((frontend / 'package.json').read_text()))
            copied = scratch / 'package-lock.json'; copied.write_text(json.dumps(npm)); inputs.append(copied)
        canary = scratch / 'canary.cdx.json'
        canary.write_text(json.dumps({'bomFormat': 'CycloneDX', 'specVersion': '1.6', 'version': 1,
            'components': [{'type': 'library', 'name': 'log4j-core', 'group': 'org.apache.logging.log4j',
                'version': '2.14.1', 'purl': 'pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1'}]}))
        require(scan(binary, [canary], {('Maven', 'org.apache.logging.log4j:log4j-core', '2.14.1')}, scratch),
                'Live advisory detection canary failed')
        issues = scan(binary, inputs, expected, scratch)
        write_artifact(root, output / 'java.cdx.json', json.dumps(clean, indent=2) + '\n')
        summary = {'schemaVersion': 1, 'scanner': 'osv-scanner', 'version': pin['version'],
                   'packages': len(expected), 'findings': issues, 'passed': not issues}
        write_artifact(root, output / 'dependencies.json', json.dumps(summary, indent=2) + '\n')
        require(not issues, 'Known dependency vulnerabilities block this revision')
    return summary


def main():
    try:
        summary = run(ROOT)
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, subprocess.SubprocessError):
        raise SystemExit('Dependency gate failed: tools, feeds, complete SBOM/reports and zero vulnerabilities are required.')
    print('Dependency gate passed: ' + str(summary['packages']) + ' resolved packages; zero known vulnerabilities.')


if __name__ == '__main__':
    main()
