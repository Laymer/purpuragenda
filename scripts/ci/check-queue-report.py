#!/usr/bin/env python3
"""Require the real PostgreSQL queue suite, not unrelated passing adapter tests."""
from pathlib import Path
import xml.etree.ElementTree as ET


def validate(root):
    report = root / 'backend/adapters/target/surefire-reports/TEST-com.purpuragenda.adapters.NotificationQueueDatabaseTest.xml'
    if not report.is_file():
        raise ValueError('Missing mandatory PostgreSQL queue report')
    suite = ET.parse(report).getroot()
    count = int(suite.get('tests', '0'))
    if (suite.get('name') != 'com.purpuragenda.adapters.NotificationQueueDatabaseTest'
            or count < 10 or count != len(list(suite.iter('testcase')))):
        raise ValueError('Incomplete mandatory PostgreSQL queue suite')
    if any(int(suite.get(key, '0')) for key in ('failures', 'errors', 'skipped')):
        raise ValueError('Failed or skipped PostgreSQL queue checks')
    if any(list(suite.iter(tag)) for tag in ('failure', 'error', 'skipped')):
        raise ValueError('Failed or skipped PostgreSQL queue cases')


if __name__ == '__main__':
    try:
        validate(Path(__file__).resolve().parents[2])
    except (ValueError, OSError, ET.ParseError):
        raise SystemExit('Mandatory PostgreSQL queue report missing, invalid or failing')
    print('Mandatory PostgreSQL queue suite report verified')
