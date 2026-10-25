"""Require actual native Windows PowerShell test execution; this does not certify WSL itself."""
import argparse
import json
from pathlib import Path
import re


def validate(path):
    document = json.loads(path.read_text(encoding='utf-8-sig'))
    if (set(document) != {'schemaVersion', 'platform', 'powershell', 'tests', 'failures', 'skipped'}
            or type(document['schemaVersion']) is not int or document['schemaVersion'] != 1 or document['platform'] != 'windows'
            or not isinstance(document['powershell'], str)
            or not re.fullmatch(r'[0-9]{1,2}\.[0-9]{1,2}\.[0-9]{1,3}', document['powershell'])
            or tuple(map(int, document['powershell'].split('.'))) < (7, 4, 0)
            or type(document['tests']) is not int or document['tests'] < 16
            or any(type(document[key]) is not int or document[key] != 0 for key in ('failures', 'skipped'))):
        raise ValueError('Missing native Windows test execution')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    try:
        validate(args.report)
    except (OSError, ValueError, TypeError, KeyError):
        raise SystemExit('WSL native test report gate failed')
    print('Native Windows preflight tests executed without failures or skips; actual WSL acceptance remains separate.')
