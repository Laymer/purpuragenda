#!/usr/bin/env python3
"""Pinned CodeQL preparation and complete, privacy-safe SARIF severity enforcement."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from urllib.parse import unquote, urlparse
from urllib.request import urlopen
import zipfile
from artifact_paths import remove_artifact, write_artifact

ROOT = Path(__file__).resolve().parents[2]
LANGUAGES = {'java-kotlin': ('.java',), 'python': ('.py',),
             'javascript-typescript': ('.js', '.jsx', '.mjs', '.ts', '.tsx', '.mts', '.cts')}
PREFIXES = {'java-kotlin': 'java/', 'python': 'py/', 'javascript-typescript': 'js/'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def configuration(root, pin):
    config = json.loads((root / '.github/codeql/config.yml').read_text())
    require(config == {'name': 'Purpuragenda complete security analysis', 'disable-default-queries': False,
                       'queries': [{'uses': 'security-extended'}]}, 'SAST query configuration changed')
    text = (root / '.github/workflows/verify.yml').read_text()
    block = re.search(r'^  sast:\n(.*?)(?=^  [a-z][a-z0-9-]*:|\Z)', text, re.M | re.S)
    require(block, 'Missing mandatory SAST job')
    block = block.group(1)
    cells = re.findall(r'^\s+- language: (\S+)\n\s+build-mode: (\S+)', block, re.M)
    require(len(cells) == 3 and dict(cells) == {name: value['buildMode'] for name, value in pin['bundles'].items()}
            and set(dict(cells)) == set(LANGUAGES), 'SAST language matrix must be complete')
    require('fail-fast: false' in block, 'All SAST languages must finish')
    relocation = 'mv .toolchain "$tool_root/toolchain"'
    require(relocation in block and 'ln -s "$tool_root/toolchain" .toolchain' in block and
            block.index(relocation) < block.index('github/codeql-action/init@'),
            'Installed tool dependencies must remain outside analyzed project source')
    for action in ('init', 'analyze'):
        require('github/codeql-action/' + action + '@' + pin['actionCommit'] in block, 'Unpinned CodeQL action')
    require('upload-database: false' in block and 'debug: false' in block,
            'Raw CodeQL databases/debug artifacts must not be uploaded')
    require(not re.search(r'skip-queries:|-D(?:skipTests|maven.test.skip)', block),
            'SAST may not skip queries or tests')


def sources(root, language):
    names = subprocess.check_output(['git', '-C', str(root), 'ls-files', '-z'],
                                    stderr=subprocess.DEVNULL).decode().split('\0')
    expected = {name for name in names if Path(name).suffix in LANGUAGES[language]}
    generated = root / 'frontend/src/app/shared/generated'
    manifest, version = generated / '.openapi-generator/FILES', generated / '.openapi-generator/VERSION'
    if manifest.is_file() and version.is_file() and version.read_text().strip():
        for name in manifest.read_text().splitlines():
            relative = Path(name)
            require(not relative.is_absolute() and '..' not in relative.parts, 'Generated manifest escapes source')
            expected.discard((generated / relative).relative_to(root).as_posix())
    require(expected, 'SAST language has no tracked sources')
    for name in expected:
        current = root
        for part in Path(name).parts:
            current /= part
            require(not current.is_symlink(), 'SAST source symlinks are forbidden')
        require(current.is_file(), 'Tracked SAST source is unavailable')
    return expected


def download(pin, opener=urlopen):
    directory = Path(tempfile.mkdtemp(prefix='purpuragenda-codeql-'))
    bundle = directory / 'bundle.tar.zst'
    try:
        digest, size = hashlib.sha256(), 0
        with opener(pin['url'], timeout=90) as response, bundle.open('xb') as output:
            while chunk := response.read(1024 * 1024):
                size += len(chunk)
                require(size <= pin['size'], 'CodeQL bundle exceeds pinned size')
                digest.update(chunk); output.write(chunk)
        require(size == pin['size'] and digest.hexdigest() == pin['sha256'], 'CodeQL bundle checksum mismatch')
    except (OSError, ValueError):
        shutil.rmtree(directory)
        raise
    return bundle


def source_uri(uri, root):
    if not isinstance(uri, str):
        return None
    parsed = urlparse(uri)
    if parsed.netloc not in ('', 'localhost') or (parsed.scheme and parsed.scheme != 'file'):
        return None
    path = Path(unquote(parsed.path))
    if path.is_absolute():
        try:
            path = path.relative_to(root)
        except ValueError:
            return None
    return path.as_posix() if '..' not in path.parts else None


def snippets(value):
    if isinstance(value, dict):
        return bool(value.get('snippet')) or any(snippets(child) for child in value.values())
    return isinstance(value, list) and any(snippets(child) for child in value)


def sarif(document, language, root, expected, version):
    require(isinstance(document, dict) and document.get('version') == '2.1.0', 'Invalid SARIF version')
    runs = document.get('runs')
    require(isinstance(runs, list) and len(runs) == 1, 'Missing or duplicate SAST run')
    run = runs[0]
    require(isinstance(run, dict) and isinstance(run.get('tool'), dict), 'Missing CodeQL tool')
    tool = run['tool']; driver = tool.get('driver', {})
    require(isinstance(driver, dict), 'Missing CodeQL driver')
    require(driver.get('name') in ('CodeQL', 'CodeQL command-line toolchain') and driver.get('semanticVersion', driver.get('version')) == version,
            'Missing or mismatched CodeQL tool version')
    require(run.get('automationDetails', {}).get('id', '').rstrip('/') == 'purpuragenda/' + language,
            'SAST report language/category mismatch')
    invocations = run.get('invocations')
    require(isinstance(invocations, list) and invocations and
            all(item.get('executionSuccessful') is True for item in invocations), 'Incomplete CodeQL execution')
    require(not any(note.get('level') == 'error' for item in invocations
                    for note in item.get('toolExecutionNotifications', [])), 'CodeQL extraction/query errors')
    components = [driver] + tool.get('extensions', [])
    rules = {}
    for component in components:
        for rule in component.get('rules', []):
            identifier = rule.get('id')
            require(isinstance(identifier, str) and identifier not in rules, 'Missing or ambiguous SAST rule')
            rules[identifier] = rule
    require(rules and any(name.startswith(PREFIXES[language]) for name in rules), 'Missing language security rules')
    analyzed = {source_uri(item.get('location', {}).get('uri'), root) for item in run.get('artifacts', [])}
    require(expected <= analyzed, 'SAST report omits tracked sources')
    require(not snippets(run) and not any(item.get('contents') for item in run.get('artifacts', [])),
            'Source snippets must not be retained')
    results = run.get('results')
    require(isinstance(results, list), 'Missing SAST results')
    high = 0; blocking_rules = {}
    for result in results:
        require(not result.get('suppressions'), 'SAST result suppression is forbidden')
        reference = result.get('rule', {})
        identifier = result.get('ruleId') or reference.get('id')
        require(identifier in rules, 'SAST result has no known rule')
        index = reference.get('index', result.get('ruleIndex'))
        if index is not None:
            component = driver
            if 'toolComponent' in reference:
                component_index = reference['toolComponent'].get('index')
                require(isinstance(component_index, int) and not isinstance(component_index, bool) and
                        0 <= component_index < len(components) - 1, 'Invalid SAST rule component')
                component = components[1 + component_index]
            table = component.get('rules', [])
            require(isinstance(index, int) and not isinstance(index, bool) and 0 <= index < len(table)
                    and table[index]['id'] == identifier, 'SAST rule index disagrees')
        score = rules[identifier].get('properties', {}).get('security-severity')
        require(isinstance(score, str), 'Unclassified SAST result severity')
        number = float(score)
        require(math.isfinite(number) and 0 <= number <= 10, 'Invalid SAST security severity')
        high += number >= 7.0
        if number >= 7.0:
            require(re.fullmatch(r'[a-z][a-z0-9_./-]{1,128}', identifier), 'Invalid public rule identifier')
            blocking_rules[identifier] = blocking_rules.get(identifier, 0) + 1
    return {'schemaVersion': 1, 'tool': 'CodeQL', 'version': version, 'language': language,
            'sources': len(expected), 'rules': len(rules), 'findings': len(results),
            'highOrCritical': high, 'blockingRules': blocking_rules, 'passed': high == 0}


def captured_sources(root, database, expected):
    require(database is not None, 'CodeQL database location is required')
    archive = database / 'src.zip'
    require(archive.is_file() and not archive.is_symlink(), 'CodeQL source archive is missing')
    found = set()
    with zipfile.ZipFile(archive) as bundle:
        for item in bundle.infolist():
            name = item.filename
            relative = name if name in expected else source_uri('/' + name.lstrip('/'), root)
            if relative not in expected:
                continue
            require(relative not in found, 'Ambiguous CodeQL captured source')
            actual = (root / relative).read_bytes()
            require(item.file_size == len(actual) and hashlib.sha256(bundle.read(item)).digest() ==
                    hashlib.sha256(actual).digest(), 'CodeQL captured source differs from checkout')
            found.add(relative)
    require(found == expected, 'CodeQL extraction omitted tracked sources')


def run(root, language, tool, report_directory, pin, database=None):
    receipt = root / ('artifacts/security/sast-' + language + '.json')
    remove_artifact(root, receipt)
    require(tool and Path(tool).is_file() and os.access(tool, os.X_OK), 'Required CodeQL executable is missing')
    actual = json.loads(subprocess.check_output([tool, 'version', '--format=json'],
                       stderr=subprocess.DEVNULL, timeout=30))
    require(actual.get('version') == pin['version'], 'CodeQL executable version mismatch')
    reports = list(report_directory.glob('*.sarif'))
    require(len(reports) == 1 and not reports[0].is_symlink(), 'Missing or extra CodeQL SARIF reports')
    expected = sources(root, language)
    captured_sources(root, database, expected)
    summary = sarif(json.loads(reports[0].read_text()), language, root, expected, pin['version'])
    write_artifact(root, receipt, json.dumps(summary, indent=2) + '\n')
    require(summary['passed'], 'High or critical SAST findings block this revision')
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--language', choices=LANGUAGES, required=True)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--prepare', action='store_true')
    modes.add_argument('--compile', action='store_true')
    parser.add_argument('--tool')
    parser.add_argument('--sarif-dir', type=Path)
    args = parser.parse_args()
    try:
        pin = json.loads((ROOT / 'ops/security-tools.lock.json').read_text())['codeql']
        configuration(ROOT, pin)
        sources(ROOT, args.language)
        if args.prepare:
            require(not any(os.getenv(key, '').lower() in ('1', 'true') for key in
                            ('RUNNER_DEBUG', 'ACTIONS_STEP_DEBUG')), 'CodeQL debug artifacts must remain disabled')
            output = os.environ['GITHUB_OUTPUT']
            remove_artifact(ROOT, ROOT / ('artifacts/security/sast-' + args.language + '.json'))
            bundle = download(pin['bundles'][args.language])
            with open(output, 'a', encoding='utf-8') as stream:
                stream.write('bundle=' + str(bundle) + '\n')
        elif args.compile:
            if args.language == 'java-kotlin':
                subprocess.run([str(ROOT / 'mvnw'), '--batch-mode', '--no-transfer-progress', 'clean', 'test-compile'],
                               cwd=ROOT, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            require(args.sarif_dir is not None, 'SARIF directory is required')
            databases = json.loads(os.environ['CODEQL_DATABASES'])
            database = Path(databases[pin['bundles'][args.language]['canonical']])
            run(ROOT, args.language, args.tool, args.sarif_dir, pin, database)
    except (OSError, ValueError, TypeError, KeyError, IndexError, zipfile.BadZipFile, subprocess.SubprocessError):
        raise SystemExit('SAST gate failed: pinned tools, complete sources/rules/reports and no high/critical findings are required.')
    print('SAST phase passed for ' + args.language + '.')


if __name__ == '__main__':
    main()
