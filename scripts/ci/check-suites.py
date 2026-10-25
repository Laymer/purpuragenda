#!/usr/bin/env python3
"""Keep every implemented category mandatory; planned categories block release readiness."""
import argparse
import json
import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
# Stable release requirements from docs/implementation-plan.md, not inferred from passing jobs.
OWNERS = {
    'documentation': 'P01', 'java': 'P02', 'architecture': 'P02', 'coverage': 'P40',
    'delivery-policy': 'P48', 'contracts': 'P12', 'secrets': 'P43',
    'postgres-migrations': 'P07', 'postgres-races': 'P38', 'outbox': 'P14',
    'api-acceptance': 'P38', 'angular': 'P12', 'browser-matrix': 'P39',
    'accessibility': 'P39', 'performance': 'P41', 'load': 'P42',
    'dependencies': 'P43', 'sbom': 'P43', 'sast': 'P43', 'containers': 'P43',
    'configuration': 'P43', 'zap': 'P44', 'persistence': 'P45',
    'backup': 'P46', 'restore': 'P47',
}
ACTIVATION = {
    'postgres-migrations': 'backend/adapters/src/main/resources/db/changelog/db.changelog-master.yaml',
    'angular': 'frontend/package.json',
    'outbox': 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/email/PostgresNotificationQueue.java',
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def inventory(document, root, release=False):
    require(isinstance(document, dict) and document.get('schemaVersion') == 1,
            'Invalid suite inventory schema')
    suites = document.get('suites')
    require(isinstance(suites, dict) and suites.keys() == OWNERS.keys(),
            'Missing or unknown required suite category')
    jobs, planned = set(), []
    for name, owner in OWNERS.items():
        item = suites[name]
        require(isinstance(item, dict) and set(item) == {'owner', 'state', 'job'}
                and item['owner'] == owner, 'Invalid suite ownership/shape: ' + name)
        require(item['state'] in ('implemented', 'planned'), 'Invalid suite state: ' + name)
        if item['state'] == 'implemented':
            require(isinstance(item['job'], str)
                    and re.fullmatch('[a-z][a-z0-9-]*', item['job'])
                    and item['job'] != 'verify', 'Implemented suite needs a prerequisite job: ' + name)
            if name in ACTIVATION:
                require((root / ACTIVATION[name]).is_file(), 'Implemented suite source is missing: ' + name)
            jobs.add(item['job'])
        else:
            require(item['job'] is None, 'Planned suite cannot certify a job: ' + name)
            require(name not in ACTIVATION or not (root / ACTIVATION[name]).exists(),
                    'Source implementation requires suite activation: ' + name)
            planned.append(name)
    require(jobs, 'No mandatory suite jobs')
    require(not release or not planned, 'Release has unimplemented categories: ' + ', '.join(planned))
    return jobs


def workflow(text, expected):
    # Deliberately accept the repository's simple, reviewed workflow shape only.
    # Refactors to expressions/multiline needs must update this validator and tests.
    require(not re.search(r'^\s*(?:paths|paths-ignore):', text, re.MULTILINE),
            'Every PR must run checks, including documentation changes')
    require(text.count('\njobs:\n') == 1, 'Workflow must declare one job graph')
    body = text.partition('\njobs:\n')[2]
    blocks = re.findall(r'^  ([a-z][a-z0-9-]*):\n(.*?)(?=^  [a-z][a-z0-9-]*:|\Z)',
                        body, re.MULTILINE | re.DOTALL)
    jobs = dict(blocks)
    require(len(jobs) == len(blocks) and set(jobs) == expected | {'verify'},
            'Workflow jobs do not match mandatory inventory')
    aggregate = jobs['verify']
    require(re.search(r'^    if: always\(\)\s*$', aggregate, re.MULTILINE),
            'Aggregate must run after failed/skipped prerequisites')
    needs = re.findall(r'^    needs: \[([^\]\n]*)\]\s*$', aggregate, re.MULTILINE)
    require(len(needs) == 1, 'Aggregate needs must be explicit')
    dependencies = [name.strip() for name in needs[0].split(',')]
    require(len(dependencies) == len(set(dependencies)) and set(dependencies) == expected,
            'Aggregate omits or duplicates mandatory jobs')
    require('python3 scripts/ci/check-suites.py --results' in aggregate,
            'Aggregate must enforce exact successful results')
    for name in expected:
        require(not re.search(r'^    if:', jobs[name], re.MULTILINE),
                'Required job cannot be conditional: ' + name)
    for name, block in jobs.items():
        require(not re.search(r'^[ \t]+(?:-[ \t]+)?continue-on-error[ \t]*:', block, re.MULTILINE),
                'Required checks cannot continue after failure: ' + name)
        conditions = re.findall(r'^[ \t]+(?:-[ \t]+)?if[ \t]*:[ \t]*(.*?)[ \t]*$', block, re.MULTILINE)
        require(all(condition == 'always()' for condition in conditions),
                'Required check steps cannot be conditional: ' + name)
        require(not re.search(r'^[ \t]+-[ \t]*\{', block, re.MULTILINE),
                'Flow-style steps require explicit validator review: ' + name)


def results(document, expected):
    require(isinstance(document, dict) and document.keys() == expected,
            'Missing or unexpected prerequisite results')
    for name, value in document.items():
        require(isinstance(value, dict) and value.get('result') == 'success',
                'Required job failed, skipped, cancelled or missing: ' + name)


def validate(root, outcomes=None, release=False):
    jobs = inventory(json.loads((root / 'scripts/ci/suites.json').read_text()), root, release)
    workflow((root / '.github/workflows/verify.yml').read_text(), jobs)
    if outcomes is not None:
        results(outcomes, jobs)
    return jobs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--results', action='store_true', help='require successful current needs results')
    parser.add_argument('--release', action='store_true', help='reject every planned category')
    args = parser.parse_args()
    try:
        outcomes = json.loads(os.environ['RESULTS']) if args.results else None
        jobs = validate(ROOT, outcomes, args.release)
    except (OSError, ValueError, TypeError, KeyError):
        raise SystemExit('Suite gate failed: inventory, workflow, source activation and successful reports must agree.')
    print('Mandatory suite inventory verified: ' + ', '.join(sorted(jobs)))


if __name__ == '__main__':
    main()
