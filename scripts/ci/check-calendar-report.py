#!/usr/bin/env python3
"""Require executed projection, RFC writer, persisted authority and Angular calendar suites."""
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
JAVA = {
    ('application', 'com.purpuragenda.application.CalendarEventTest'): 4,
    ('application', 'com.purpuragenda.application.CalendarDownloadsTest'): 3,
    ('adapters', 'com.purpuragenda.adapters.out.calendar.ICalendarWriterTest'): 8,
    ('adapters', 'com.purpuragenda.adapters.in.rest.CalendarDownloadHttpTest'): 1,
    ('adapters', 'com.purpuragenda.adapters.CalendarCustomerHttpPostgresTest'): 4,
    ('adapters', 'com.purpuragenda.adapters.CalendarOwnerHttpPostgresTest'): 3,
    ('adapters', 'com.purpuragenda.adapters.CalendarAccountHttpPostgresTest'): 1,
}
ANGULAR = 'src/app/shared/calendar-actions.component.spec.ts'


def checked(suite, name, minimum):
    cases = suite.findall('testcase')
    if (suite.tag != 'testsuite' or suite.get('name') != name
            or int(suite.get('tests', '0')) != len(cases) or len(cases) < minimum
            or any(case.get('classname') != name for case in cases)
            or len({case.get('name') for case in cases}) != len(cases)
            or any(not case.get('name') for case in cases)):
        raise ValueError('Incomplete calendar suite')
    if (any(int(suite.get(key, '0')) for key in ('failures', 'errors', 'skipped'))
            or any(list(suite.iter(tag)) for tag in ('failure', 'error', 'skipped'))):
        raise ValueError('Failed or skipped calendar suite')
    return len(cases)


def validate(root):
    counts = {}
    for (module, name), minimum in JAVA.items():
        path = root / f'backend/{module}/target/surefire-reports/TEST-{name}.xml'
        counts[name] = checked(ET.parse(path).getroot(), name, minimum)
    document = ET.parse(root / 'frontend/coverage/junit.xml').getroot()
    suites = [suite for suite in document.iter('testsuite') if suite.get('name') == ANGULAR]
    if len(suites) != 1:
        raise ValueError('Missing or duplicated Angular calendar suite')
    counts[ANGULAR] = checked(suites[0], ANGULAR, 28)
    return counts


if __name__ == '__main__':
    receipt = ROOT / 'artifacts/calendar/verification.json'
    receipt.unlink(missing_ok=True)
    try:
        counts = validate(ROOT)
        revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        dirty = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip())
        receipt.parent.mkdir(parents=True, exist_ok=True)
        receipt.write_text(json.dumps({'schemaVersion': 1, 'revision': revision, 'dirty': dirty,
                                      'suites': counts, 'realClientImports': 'manual gate; not verified'}, indent=2) + '\n')
    except (ValueError, OSError, ET.ParseError, subprocess.CalledProcessError):
        raise SystemExit('Mandatory calendar reports missing, invalid or failing')
    print('Mandatory calendar reports verified: ' + str(sum(counts.values())) + ' cases')
