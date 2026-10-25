#!/usr/bin/env python3
"""Require the real authentication worker suite, not unrelated passing adapter tests."""
from pathlib import Path
import xml.etree.ElementTree as ET


def validate(root):
    report = root / 'backend/adapters/target/surefire-reports/TEST-com.purpuragenda.adapters.AuthWorkerDatabaseTest.xml'
    if not report.is_file():
        raise ValueError('Missing mandatory authentication worker report')
    suite = ET.parse(report).getroot()
    count = int(suite.get('tests', '0'))
    if (suite.get('name') != 'com.purpuragenda.adapters.AuthWorkerDatabaseTest'
            or count < 10 or count != len(list(suite.iter('testcase')))):
        raise ValueError('Incomplete mandatory authentication worker suite')
    if any(int(suite.get(key, '0')) for key in ('failures', 'errors', 'skipped')):
        raise ValueError('Failed or skipped authentication worker checks')
    if any(list(suite.iter(tag)) for tag in ('failure', 'error', 'skipped')):
        raise ValueError('Failed or skipped authentication worker cases')


if __name__ == '__main__':
    try:
        validate(Path(__file__).resolve().parents[2])
    except (ValueError, OSError, ET.ParseError):
        raise SystemExit('Mandatory authentication worker report missing, invalid or failing')
    print('Mandatory authentication worker suite report verified')
