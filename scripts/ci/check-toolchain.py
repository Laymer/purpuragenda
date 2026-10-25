#!/usr/bin/env python3
"""Check the selected tools and wrapper against the approved lock."""
import json
import platform
import runpy
import re
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[2]
lock = json.loads((root / 'ops/toolchain.lock.json').read_text())
select = runpy.run_path(str(root / 'scripts/dev/install-toolchain.py'))['select']
_, artifacts = select(lock, platform.system(), platform.machine())
for name, command, expected in (
    ('python', ['python3', '--version'], artifacts['python']['version']),
    ('jdk', ['java', '-version'], artifacts['jdk']['version'].split('+')[0]),
    ('node', ['node', '--version'], artifacts['node']['version']),
    ('maven', ['./mvnw', '--version'], artifacts['maven']['version']),
):
    result = subprocess.run(command, cwd=root, text=True, capture_output=True, check=True)
    output = result.stdout + result.stderr
    if not re.search(r'(?<![\d.])' + re.escape(expected) + r'(?![\d.])', output):
        raise SystemExit('Wrong selected tool version: ' + name)
    print('Selected ' + name + ' ' + expected)
properties = dict(line.split('=', 1) for line in
                  (root / '.mvn/wrapper/maven-wrapper.properties').read_text().splitlines() if '=' in line)
if properties['distributionUrl'] != lock['artifacts']['maven']['url']:
    raise SystemExit('Wrapper distribution differs from lock')
if not re.fullmatch('[0-9a-f]{64}', properties.get('distributionSha256Sum', '')):
    raise SystemExit('Wrapper requires a SHA256 checksum')
