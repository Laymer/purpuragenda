#!/usr/bin/env python3
"""A passing unrelated adapter suite cannot replace real PostgreSQL migration evidence."""
from pathlib import Path
import xml.etree.ElementTree as ET


def validate(root):
    report = root / 'backend/adapters/target/surefire-reports/TEST-com.purpuragenda.adapters.MigrationTest.xml'
    if not report.is_file():
        raise ValueError('Missing mandatory PostgreSQL migration report')
    suite = ET.parse(report).getroot()
    if suite.get('name') != 'com.purpuragenda.adapters.MigrationTest' or int(suite.get('tests', '0')) < 11:
        raise ValueError('Incomplete mandatory PostgreSQL migration suite')
    if any(int(suite.get(key, '0')) for key in ('failures', 'errors', 'skipped')):
        raise ValueError('Failed or skipped PostgreSQL migration checks')


if __name__ == '__main__':
    try:
        validate(Path(__file__).resolve().parents[2])
    except (ValueError, OSError, ET.ParseError):
        raise SystemExit('Mandatory PostgreSQL migration report missing, invalid or failing')
    print('Mandatory PostgreSQL migration suite report verified')
