#!/usr/bin/env python3
"""Check or apply only the managed main-branch ruleset, preserving other rulesets."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / '.github/rules/main.json'
RECEIPT = ROOT / '.github/rules/main.receipt.json'
REPOSITORY = 'Laymer/purpuragenda'


def gh(method, endpoint, payload=None):
    args = ['gh', 'api', '--method', method, endpoint]
    if payload is not None:
        args += ['--input', '-']
    result = subprocess.run(args, input=json.dumps(payload) if payload is not None else None,
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            check=True)
    return json.loads(result.stdout)


def config_hash(expected):
    canonical = json.dumps(expected, sort_keys=True, separators=(',', ':')).encode()
    return hashlib.sha256(canonical).hexdigest()


def receipt_for(actual, expected):
    if actual.get('bypass_actors') != [] or not actual.get('id') or not actual.get('updated_at'):
        raise ValueError('Administrator no-bypass readback and version metadata required')
    return {'ruleset_id': actual['id'], 'updated_at': actual['updated_at'],
            'config_sha256': config_hash(expected), 'verified_bypass_actors': []}


def valid_receipt(actual, expected, receipt):
    return bool(receipt and actual.get('id') and actual.get('updated_at') and
                receipt.get('ruleset_id') == actual['id'] and
                receipt.get('updated_at') == actual['updated_at'] and
                receipt.get('config_sha256') == config_hash(expected) and
                receipt.get('verified_bypass_actors') == [])


def discrepancies(actual, expected, receipt=None):
    errors = []
    for key in ('name', 'target', 'enforcement', 'bypass_actors', 'conditions'):
        if key == 'bypass_actors' and key not in actual and valid_receipt(actual, expected, receipt):
            continue
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


def prevent_weakening(current, expected):
    """Refuse any removal or relaxation of an existing managed requirement."""
    if current.get('conditions') != expected['conditions']:
        raise ValueError('Changed branch selection requires manual reconciliation')
    desired_rules = {rule['type']: rule.get('parameters', {}) for rule in expected['rules']}
    for rule in current.get('rules', []):
        if rule['type'] not in desired_rules:
            raise ValueError('Unexpected managed rules require review before replacement')
        desired = desired_rules[rule['type']]
        for key, value in rule.get('parameters', {}).items():
            replacement = desired.get(key)
            if key == 'required_status_checks':
                for check in value:
                    if not any(candidate.get('context') == check['context'] and
                               (check.get('integration_id') is None or
                                candidate.get('integration_id') == check['integration_id'])
                               for candidate in replacement or []):
                        raise ValueError('Existing required checks must be preserved')
                    if set(check) - {'context', 'integration_id'}:
                        raise ValueError('Unknown required-check settings require review')
            elif key == 'allowed_merge_methods':
                if not set(replacement or []).issubset(value):
                    raise ValueError('Existing merge restrictions must be preserved')
            elif key == 'do_not_enforce_on_create':
                if not value and replacement is not False:
                    raise ValueError('Checks must remain required for branch creation')
            elif isinstance(value, bool):
                if value and replacement is not True:
                    raise ValueError('Existing enabled requirements must be preserved')
            elif isinstance(value, int):
                if not isinstance(replacement, int) or replacement < value:
                    raise ValueError('Existing numeric requirements must be preserved')
            elif value and replacement != value:
                raise ValueError('Changed managed parameters require manual reconciliation')


def reconcile(api, expected, apply=False, receipt=None, record=None):
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
            prevent_weakening(current, expected)
            api('PUT', endpoint + '/' + str(rule_id), expected)
    actual = api('GET', endpoint + '/' + str(rule_id))
    errors = discrepancies(actual, expected, receipt)
    if not errors and record is not None:
        record(receipt_for(actual, expected))
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='explicitly apply the reviewed ruleset')
    parser.add_argument('--record-receipt', action='store_true',
                        help='record administrator-verified no-bypass version for public CI')
    args = parser.parse_args()
    def record(value):
        RECEIPT.write_text(json.dumps(value, indent=2) + '\n')
    try:
        receipt = json.loads(RECEIPT.read_text()) if RECEIPT.exists() else None
        errors = reconcile(gh, json.loads(CONFIG.read_text()), args.apply, receipt,
                           record if args.apply or args.record_receipt else None)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError):
        raise SystemExit('Protection verification failed: unavailable API or invalid configuration')
    if errors:
        raise SystemExit('\n'.join(errors))
    print('Main requires PRs, current-base GitHub Actions verify, resolved threads and no bypass.')


if __name__ == '__main__':
    main()
