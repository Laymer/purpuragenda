#!/usr/bin/env python3
"""Require the real authentication delivery suite, not unrelated passing adapter tests."""
from pathlib import Path
import xml.etree.ElementTree as ET


def validate(root):
    report = root / 'backend/adapters/target/surefire-reports/TEST-com.purpuragenda.adapters.AuthDeliveryDatabaseTest.xml'
    if not report.is_file():
        raise ValueError('Missing mandatory authentication delivery report')
    suite = ET.parse(report).getroot()
    count = int(suite.get('tests', '0'))
    if (suite.get('name') != 'com.purpuragenda.adapters.AuthDeliveryDatabaseTest'
            or count < 12 or count != len(list(suite.iter('testcase')))):
        raise ValueError('Incomplete mandatory authentication delivery suite')
    if any(int(suite.get(key, '0')) for key in ('failures', 'errors', 'skipped')):
        raise ValueError('Failed or skipped authentication delivery checks')
    if any(list(suite.iter(tag)) for tag in ('failure', 'error', 'skipped')):
        raise ValueError('Failed or skipped authentication delivery cases')


if __name__ == '__main__':
    try:
        validate(Path(__file__).resolve().parents[2])
    except (ValueError, OSError, ET.ParseError):
        raise SystemExit('Mandatory authentication delivery report missing, invalid or failing')
    print('Mandatory authentication delivery suite report verified')
