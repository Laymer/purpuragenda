#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/dev/toolchain-env.sh
python3 scripts/ci/check-docs.py
python3 scripts/ci/check-toolchain.py
python3 - <<'PYTHON'
from pathlib import Path
for module in ('domain', 'application', 'adapters', 'bootstrap'):
    base = Path('backend') / module
    if list((base / 'src/main/java').rglob('*.java')) and not list((base / 'src/test/java').rglob('*Test.java')):
        raise SystemExit('Missing implemented-layer test suite: ' + module)
PYTHON
./mvnw --batch-mode --no-transfer-progress clean verify
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
modules = [Path('quality')]
modules += [Path('backend') / name for name in ('domain', 'application', 'adapters', 'bootstrap')
            if list((Path('backend') / name / 'src/main/java').rglob('*.java'))]
for module in modules:
    reports = list((module / 'target/surefire-reports').glob('TEST-*.xml'))
    if not reports:
        raise SystemExit('Missing test reports: ' + str(module))
    count = 0
    for report in reports:
        suite = ET.parse(report).getroot()
        if any(int(suite.get(key, '0')) for key in ('failures', 'errors', 'skipped')):
            raise SystemExit('Failed or skipped checks: ' + str(module))
        count += int(suite.get('tests', '0'))
    if count < (3 if module.name == 'quality' else 1):
        raise SystemExit('Incomplete suite: ' + str(module))
    print(f'Reports verified: {module}, {count} tests')
PY
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
if list(Path('backend/domain/src/main/java').rglob('*.java')):
    report = Path('backend/domain/target/site/jacoco/jacoco.xml')
    if not report.is_file():
        raise SystemExit('Missing domain coverage report')
    counters = {item.get('type'): item for item in ET.parse(report).getroot().findall('counter')}
    for name, minimum in (('LINE', 0.95), ('BRANCH', 0.90)):
        if name not in counters:
            raise SystemExit('Missing domain coverage counter: ' + name)
        covered = int(counters[name].get('covered'))
        total = covered + int(counters[name].get('missed'))
        if not total or covered / total < minimum:
            raise SystemExit('Domain coverage below threshold: ' + name)
        print(f'Domain {name}: {covered}/{total} ({covered / total:.2%})')
PY
