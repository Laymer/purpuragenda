#!/usr/bin/env python3
"""Check or apply only the managed main-branch ruleset, preserving other rulesets."""
import argparse
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / '.github/rules/main.json'
REPOSITORY = 'Laymer/purpuragenda'


def gh(method, endpoint, payload=None):
    args = ['gh', 'api', '--method', method, endpoint]
    if payload is not None:
        args += ['--input', '-']
    result = subprocess.run(args, input=json.dumps(payload) if payload is not None else None,
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            check=True)
    return json.loads(result.stdout)


def discrepancies(actual, expected):
    errors = []
    for key in ('name', 'target', 'enforcement', 'bypass_actors', 'conditions'):
        if actual.get(key) != expected[key]:
            errors.append('Ruleset differs: ' + key)
    actual_rules = {rule['type']: rule for rule in actual.get('rules', [])}
    for rule in expected['rules']:
        observed = actual_rules.get(rule['type'])
        if observed is None:
            errors.append('Missing rule: ' + rule['type'])
        elif observed.get('parameters', {}) != rule.get('parameters', {}):
            errors.append('Rule differs: ' + rule['type'])
    return errors


def reconcile(api, expected, apply=False):
    endpoint = 'repos/' + REPOSITORY + '/rulesets'
    # The API supports at most 100 rulesets per page; do not silently miss later pages.
    all_rules = []
    page = 1
    while True:
        batch = api('GET', endpoint + '?per_page=100&page=' + str(page))
        all_rules.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    matches = [rule for rule in all_rules if rule['name'] == expected['name']]
    if len(matches) > 1:
        raise ValueError('Duplicate managed rulesets')
    if not matches:
        if not apply:
            return ['Managed main ruleset is missing']
        created = api('POST', endpoint, expected)
        rule_id = created['id']
    else:
        rule_id = matches[0]['id']
        if apply:
            # Never replace an unexpectedly strengthened ruleset with weaker configuration.
            current = api('GET', endpoint + '/' + str(rule_id))
            known_types = {rule['type'] for rule in expected['rules']}
            if any(rule['type'] not in known_types for rule in current.get('rules', [])):
                raise ValueError('Unexpected managed rules require review before replacement')
            current_rules = {rule['type']: rule for rule in current.get('rules', [])}
            prior_pr = current_rules.get('pull_request', {}).get('parameters', {})
            if prior_pr.get('required_approving_review_count', 0) > 0 or any(
                    prior_pr.get(key, False) for key in
                    ('require_code_owner_review', 'require_last_push_approval')):
                raise ValueError('Stronger review requirements require manual reconciliation')
            api('PUT', endpoint + '/' + str(rule_id), expected)
    actual = api('GET', endpoint + '/' + str(rule_id))
    return discrepancies(actual, expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='explicitly apply the reviewed ruleset')
    args = parser.parse_args()
    try:
        errors = reconcile(gh, json.loads(CONFIG.read_text()), args.apply)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError):
        raise SystemExit('Protection verification failed: unavailable API or invalid configuration')
    if errors:
        raise SystemExit('\n'.join(errors))
    print('Main requires PRs, current-base GitHub Actions verify, resolved threads and no bypass.')


if __name__ == '__main__':
    main()
